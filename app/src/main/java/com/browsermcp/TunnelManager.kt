package com.browsermcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Built-in tunnel for AgentPilot, version 0.5.0.
 *
 * The tunnel lives inside the app. No Termux, no separate download,
 * no Play Store, no outside app at any point. One tap on "Install
 * Tunnel" and the app does everything by itself: it downloads the
 * cloudflared binary into the app's private files, makes it
 * executable, verifies it runs, and reports ready. The owner does
 * nothing else.
 *
 * How it works:
 * - Install: download the official cloudflared arm64 binary, about
 *   38 MB, one time only, from the Cloudflare release page. Save it
 *   in the app's private files dir, chmod it executable, then run
 *   "cloudflared version" to prove the binary really runs on this
 *   phone. Any failure is reported as a plain error, never silent.
 * - Start: run "cloudflared tunnel --url http://127.0.0.1:8765".
 *   The MCP server must already be listening on 8765, so the tunnel
 *   only starts while the app is Running. The first
 *   https://*.trycloudflare.com URL in the output is the public
 *   link, shown in the app and tappable to copy.
 * - Stop: kill the process. The tunnel is also stopped when the app
 *   is destroyed, and when the drive session stops.
 *
 * Safety, read this before changing anything:
 * - A Cloudflare Quick Tunnel terminates TLS at Cloudflare, so
 *   Cloudflare can see the traffic in transit. The MCP bearer token
 *   still guards the server: without the token, the tunnel link
 *   answers nothing useful.
 * - The tunnel runs only when the owner taps Start. It never starts
 *   by itself, never runs in the background on its own, and it stops
 *   when the owner taps Stop or closes the app.
 * - The binary comes from the official Cloudflare release only.
 *   Nothing is downloaded from anywhere else, and nothing here
 *   sends the owner's logins or files anywhere.
 *
 * All callbacks run on the main thread, so the caller can touch the
 * UI in them directly.
 */
class TunnelManager(private val context: Context) {

    companion object {
        const val DOWNLOAD_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        private const val BINARY_NAME = "cloudflared"
        private val TUNNEL_URL_REGEX = Regex("https://[A-Za-z0-9-]+\\.trycloudflare\\.com")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var process: Process? = null

    @Volatile var publicUrl: String? = null
        private set
    @Volatile var running: Boolean = false
        private set
    @Volatile var installing: Boolean = false
        private set

    /** The binary is installed when the file exists and is executable. */
    val installed: Boolean
        get() {
            val f = binaryFile()
            return f.exists() && f.canExecute()
        }

    private fun binaryFile(): File = File(context.filesDir, BINARY_NAME)

    /**
     * Download, install and verify the tunnel binary. One tap does
     * everything. Progress is 0 to 100. If the binary is already
     * installed, done runs at once and nothing is downloaded again.
     */
    fun install(
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (installing) return
        if (installed) {
            mainHandler.post { onDone() }
            return
        }
        installing = true
        thread {
            try {
                val tmp = File(context.filesDir, "$BINARY_NAME.tmp")
                val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "AgentPilot")
                conn.connect()
                if (conn.responseCode != 200) {
                    throw RuntimeException("Download failed, HTTP " + conn.responseCode)
                }
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                mainHandler.post { onProgress(pct) }
                            }
                        }
                    }
                }
                // Make it executable, then prove it runs on this phone.
                if (!tmp.setExecutable(true)) {
                    val chmod = Runtime.getRuntime().exec(arrayOf("chmod", "700", tmp.absolutePath))
                    if (chmod.waitFor() != 0) {
                        throw RuntimeException("Could not make the tunnel executable")
                    }
                }
                val check = Runtime.getRuntime().exec(arrayOf(tmp.absolutePath, "version"))
                val out = check.inputStream.bufferedReader().use { it.readText() } +
                    check.errorStream.bufferedReader().use { it.readText() }
                val code = check.waitFor()
                if (code != 0 || !out.contains("cloudflared")) {
                    throw RuntimeException("Tunnel download is not a working cloudflared binary")
                }
                if (!tmp.renameTo(binaryFile())) {
                    throw RuntimeException("Could not install the tunnel")
                }
                installing = false
                mainHandler.post { onDone() }
            } catch (e: Exception) {
                installing = false
                val msg = e.message ?: "error"
                mainHandler.post { onError(msg) }
            }
        }
    }

    /**
     * Start the tunnel to the local MCP server. Reports the public
     * URL as soon as cloudflared prints it, then every output line
     * after that so the app can show the tunnel is working. When
     * the process ends, onStopped runs. If it ends before any URL
     * appears, onError runs with the last output line.
     */
    fun start(
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        if (!installed) {
            mainHandler.post { onError("Tunnel is not installed yet. Tap Install Tunnel first.") }
            return
        }
        running = true
        publicUrl = null
        thread {
            try {
                val proc = ProcessBuilder(
                    binaryFile().absolutePath, "tunnel", "--url", "http://127.0.0.1:8765"
                ).redirectErrorStream(true).start()
                process = proc
                var urlSent = false
                var lastLine = ""
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        lastLine = line
                        if (!urlSent) {
                            val found = TUNNEL_URL_REGEX.find(line)
                            if (found != null) {
                                urlSent = true
                                publicUrl = found.value
                                val url = found.value
                                mainHandler.post { onUrl(url) }
                            }
                        }
                        val shown = line.take(90)
                        mainHandler.post { onLog(shown) }
                    }
                }
                val code = proc.waitFor()
                running = false
                process = null
                if (!urlSent) {
                    val detail = if (lastLine.isNotEmpty()) lastLine.take(120) else "exit code $code"
                    mainHandler.post { onError("Tunnel stopped: $detail") }
                }
                mainHandler.post { onStopped() }
            } catch (e: Exception) {
                running = false
                process = null
                val msg = e.message ?: "error"
                mainHandler.post { onError(msg) }
                mainHandler.post { onStopped() }
            }
        }
    }

    /** Kill the tunnel process, if one is running. Safe to call anytime. */
    fun stop() {
        val proc = process
        process = null
        publicUrl = null
        running = false
        if (proc == null) return
        thread {
            proc.destroy()
            try {
                proc.waitFor(2000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Timed out waiting, fall through to force kill.
            }
            if (proc.isAlive) proc.destroyForcibly()
        }
    }
}
