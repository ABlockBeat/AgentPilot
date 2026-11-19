// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Our own WebSocket client, version 0.6.22.
 *
 * Plain Java sockets only, no library, no new dependency. It dials
 * OUT to our own relay (see relay/relay.py) when the session
 * starts and holds the connection: the phone reaches the cloud
 * without a public address, a port forward, a VPN app, or any
 * outside tunnel service. The relay pairs it with the cloud side
 * by the room, which is the MCP token, so only someone holding
 * the token can reach this phone. Production must use wss with a
 * real certificate: over plain ws the room token in the URL would
 * travel in cleartext. The client supports both, because a local
 * relay for testing is plain ws.
 *
 * Frames follow RFC 6455: client frames are always masked, the
 * server handshake is verified (101 plus the accept key), pings
 * are answered, and a close from the relay ends the connection.
 * The room token is never logged: state lines name the host
 * only, never the full URL.
 *
 * Threading: connect() blocks on the calling thread for the
 * handshake, then a read thread owns the socket. onMessage runs
 * on that read thread, the same threading the HTTP MCP server
 * already uses for its per-connection threads. stop() is safe
 * from any thread except the read thread itself.
 */
class RelayClient(
    private val onMessage: (String) -> String?,
    private val onState: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 5000
        private const val PING_AFTER_MS = 30000L
        private const val MAX_PAYLOAD = 1024 * 1024
    }

    @Volatile var isConnected = false
        private set
    @Volatile var lastError = ""
        private set

    @Volatile private var stopped = false
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val sendLock = Any()
    private var readThread: Thread? = null
    private val ended = CountDownLatch(1)
    @Volatile private var lastActivity = 0L

    /**
     * Dial out to the relay and hold the connection. Returns true
     * when the handshake passed and the read thread is running.
     * Blocking: call from a worker thread, never the main thread.
     */
    fun connect(url: String, room: String): Boolean {
        if (socket != null) return false
        stopped = false
        lastError = ""
        try {
            val uri = URI(url)
            val secure = uri.scheme.equals("wss", ignoreCase = true)
            val host = uri.host ?: throw IOException("relay URL has no host")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val sock: Socket = if (secure) {
                // Default trust store, system certificates only. No
                // trust-all override: a relay with a bad certificate
                // must fail, never silently connect.
                (SSLSocketFactory.getDefault().createSocket() as SSLSocket)
            } else {
                Socket()
            }
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            if (sock is SSLSocket) sock.startHandshake()
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            input = BufferedInputStream(sock.getInputStream())
            output = sock.getOutputStream()
            val key = Base64.getEncoder().encodeToString(SecureRandom().generateSeed(16))
            val path = (uri.rawPath ?: "").ifEmpty { "/" }
            val baseQuery = uri.rawQuery
            val query = if (baseQuery.isNullOrEmpty()) {
                "room=" + URLEncoder.encode(room, "UTF-8")
            } else {
                baseQuery + "&room=" + URLEncoder.encode(room, "UTF-8")
            }
            val request = "GET $path?$query HTTP/1.1\r\n" +
                "Host: $host" + (if ((secure && port != 443) || (!secure && port != 80)) ":$port" else "") + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            output!!.write(request.toByteArray(Charsets.UTF_8))
            output!!.flush()
            val headers = readHeaders()
            if (!headers.first.contains("101")) {
                throw IOException("relay refused the upgrade: " + headers.first)
            }
            val accept = headers.second["sec-websocket-accept"] ?: ""
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8))
            )
            if (accept != expected) {
                throw IOException("relay handshake failed: bad accept key")
            }
            isConnected = true
            lastActivity = System.currentTimeMillis()
            onState("connected")
            readThread = thread {
                try {
                    readLoop()
                } finally {
                    isConnected = false
                    ended.countDown()
                    onDisconnected()
                }
            }
            return true
        } catch (e: Exception) {
            lastError = e.message ?: "connect failed"
            onState("error: $lastError")
            try { socket?.close() } catch (ignored: Exception) { }
            socket = null
            return false
        }
    }

    /** Block until the read thread ends (a drop, a close, or stop). */
    fun awaitDisconnect() {
        try {
            ended.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** End the connection. Safe from any thread but the read thread. */
    fun stop() {
        stopped = true
        try {
            sendFrame(8, byteArrayOf(0x03, 0xE8.toByte()))
        } catch (ignored: Exception) { }
        try {
            socket?.close()
        } catch (ignored: Exception) { }
        ended.countDown()
        val t = readThread
        if (t != null && Thread.currentThread() != t) {
            try {
                t.join(2000)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        isConnected = false
    }

    /** Read the HTTP handshake response head. Returns the status line and headers. */
    private fun readHeaders(): Pair<String, Map<String, String>> {
        val raw = ByteArrayOutputStream()
        val buf = ByteArray(1)
        var matched = 0
        val end = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)
        while (true) {
            val n = input!!.read(buf)
            if (n < 0) throw IOException("relay closed during handshake")
            raw.write(buf, 0, n)
            if (buf[0] == end[matched]) {
                matched++
                if (matched == end.size) break
            } else {
                matched = if (buf[0] == end[0]) 1 else 0
            }
            if (raw.size() > 8192) throw IOException("handshake headers too large")
        }
        val lines = String(raw.toByteArray(), Charsets.UTF_8).split("\r\n")
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
        }
        return Pair(lines.firstOrNull() ?: "", headers)
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val count = input!!.read(buf, read, n - read)
            if (count < 0) throw IOException("relay closed the connection")
            read += count
        }
        return buf
    }

    /** One frame from the relay. Server frames are unmasked; a masked one is still accepted and unmasked. */
    private fun readFrame(): Triple<Boolean, Int, ByteArray> {
        val head = readExact(2)
        val fin = (head[0].toInt() and 0x80) != 0
        val opcode = head[0].toInt() and 0x0F
        val masked = (head[1].toInt() and 0x80) != 0
        var length = (head[1].toInt() and 0x7F).toLong()
        if (length == 126L) {
            val ext = readExact(2)
            length = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
        } else if (length == 127L) {
            val ext = readExact(8)
            var value = 0L
            for (b in ext) value = (value shl 8) or (b.toInt() and 0xFF).toLong()
            length = value
        }
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("frame too large")
        val mask = if (masked) readExact(4) else null
        val payload = readExact(length.toInt())
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Triple(fin, opcode, payload)
    }

    /** One frame to the relay. Client frames are always masked. */
    private fun sendFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0x00) or opcode)
        val n = payload.size
        if (n < 126) {
            out.write(0x80 or n)
        } else if (n < 65536) {
            out.write(0x80 or 126)
            out.write((n shr 8) and 0xFF)
            out.write(n and 0xFF)
        } else {
            out.write(0x80 or 127)
            // 64-bit length: use a Long, because Int shr masks the
            // shift to 5 bits and would corrupt shifts above 31.
            val longN = n.toLong()
            for (shift in 56 downTo 0 step 8) out.write(((longN shr shift) and 0xFF).toInt())
        }
        val mask = SecureRandom().generateSeed(4)
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        synchronized(sendLock) {
            val stream = output ?: throw IOException("not connected")
            stream.write(out.toByteArray())
            stream.flush()
        }
        lastActivity = System.currentTimeMillis()
    }

    private fun sendText(text: String) {
        sendFrame(1, text.toByteArray(Charsets.UTF_8))
    }

    private fun readLoop() {
        var fragBuffer: ByteArrayOutputStream? = null
        while (!stopped) {
            try {
                val (fin, opcode, payload) = readFrame()
                lastActivity = System.currentTimeMillis()
                when (opcode) {
                    9 -> sendFrame(10, payload) // ping: answer with a pong.
                    10 -> { } // pong: ignore.
                    8 -> { // close: answer, then end.
                        try {
                            sendFrame(8, byteArrayOf(0x03, 0xE8.toByte()))
                        } catch (ignored: Exception) { }
                        break
                    }
                    1, 2 -> {
                        if (!fin) {
                            // A fragmented message starts: keep its
                            // bytes until the final continuation.
                            fragBuffer = ByteArrayOutputStream().apply { write(payload) }
                        } else {
                            deliver(payload)
                        }
                    }
                    0 -> {
                        val buffer = fragBuffer
                        if (buffer == null) continue
                        buffer.write(payload)
                        if (fin) {
                            val whole = buffer.toByteArray()
                            fragBuffer = null
                            deliver(whole)
                        }
                    }
                    else -> { } // Unknown opcode: ignore.
                }
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() - lastActivity > PING_AFTER_MS) {
                    try {
                        sendFrame(9, ByteArray(0))
                    } catch (ignored: Exception) {
                        break
                    }
                }
            } catch (e: IOException) {
                lastError = e.message ?: "connection closed"
                break
            } catch (e: Exception) {
                lastError = e.message ?: "read failed"
                break
            }
        }
    }

    /** Hand one complete text message to the MCP dispatcher and send the answer back. */
    private fun deliver(payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        val response = try {
            onMessage(text)
        } catch (e: Exception) {
            lastError = e.message ?: "dispatch failed"
            null
        }
        if (response != null) {
            try {
                sendText(response)
            } catch (e: Exception) {
                lastError = e.message ?: "send failed"
            }
        }
    }
}
