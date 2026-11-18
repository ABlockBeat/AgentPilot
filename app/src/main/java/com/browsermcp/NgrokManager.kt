// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * ngrok tunnel for AgentPilot, version 0.6.7.
 *
 * The second tunnel, for phones where Cloudflare does not start.
 * ngrok is free with an account: the owner signs up once at
 * ngrok.com, pastes the authtoken once in Help, and the app saves
 * it. The token never leaves the phone except to ngrok itself.
 *
 * How it works:
 * - Check: run the bundled binary with "version". It must exit 0
 *   and print "ngrok". If the file is missing or not runnable,
 *   the only fix is reinstalling the app, and the error says so.
 * - Start: run the bundled binary with "http" and the local MCP
 *   server address 127.0.0.1 port 8765. The token goes in as an
 *   environment value, never on a command line the system could
 *   log. The MCP server must already be listening, so the tunnel
 *   only starts while the app is Running.
 * - Link: ngrok prints its public link in the log, and it also
 *   serves it on its own local API at 127.0.0.1 port 4040. The
 *   API is tried first, the log is the backup. Whichever finds
 *   the link first wins.
 * - Stay up: if the tunnel process dies while the tunnel is
 *   wanted, it restarts by itself with backoff, a few tries, and
 *   only then gives up.
 * - Stop: kill the process. The tunnel is also stopped when the
 *   app is destroyed, and when the drive session stops.
 *
 * Safety, read this before changing anything:
 * - An ngrok tunnel terminates TLS at ngrok, so ngrok can see the
 *   traffic in transit. The MCP bearer token still guards the
 *   server: without the token, the tunnel link answers nothing
 *   useful.
 * - The tunnel runs only when the owner taps Start. It never
 *   starts by itself, never runs in the background on its own,
 *   and it stops when the owner taps Stop or closes the app.
 * - The binary comes from the official ngrok release only.
 *   Nothing is downloaded from anywhere else, and nothing here
 *   sends the owner's logins or files anywhere.
 *
 * All callbacks run on the main thread, so the caller can touch the
 * UI in them directly.
 */
class NgrokManager(private val context: Context) : TunnelBackend {

    companion object {
        private const val BINARY_NAME = "libngrok.so"
        private const val MAX_RESTARTS = 5
        private val TUNNEL_URL_REGEX = Regex("https://[A-Za-z0-9-]+\\.ngrok(-free)?\\.(app|io)")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var restartRunnable: Runnable? = null
    private var restartAttempts: Int = 0

    @Volatile private var wanted: Boolean = false
    @Volatile private var urlSent: Boolean = false

    @Volatile override var restarting: Boolean = false
        private set
    @Volatile override var publicUrl: String? = null
        private set
    @Volatile override var running: Boolean = false
        private set
    @Volatile override var installing: Boolean = false
        private set

    /** The binary is installed when the file exists and is executable. */
    override val installed: Boolean
        get() {
            val f = binaryFile()
            return f.exists() && f.canExecute()
        }

    private fun binaryFile(): File = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun savedToken(): String =
        context.getSharedPreferences(DriveService.PREFS, Context.MODE_PRIVATE)
            .getString(KeepAliveService.KEY_NGROK_TOKEN, "") ?: ""

    /**
     * Check the bundled tunnel binary. There is nothing to download:
     * the binary ships inside the APK. This only proves it runs on
     * this phone, then done runs.
     */
    override fun install(
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (installing) return
        if (!installed) {
            mainHandler.post { onError("Tunnel binary missing, reinstall the app") }
            return
        }
        installing = true
        thread {
            try {
                val check = Runtime.getRuntime().exec(arrayOf(binaryFile().absolutePath, "version"))
                val out = check.inputStream.bufferedReader().use { it.readText() } +
                    check.errorStream.bufferedReader().use { it.readText() }
                val code = check.waitFor()
                installing = false
                if (code != 0 || !out.contains("ngrok")) {
                    mainHandler.post { onError("Tunnel binary is not working, reinstall the app") }
                    return@thread
                }
                mainHandler.post { onProgress(100) }
                mainHandler.post { onDone() }
            } catch (e: Exception) {
                installing = false
                val msg = e.message ?: "error"
                mainHandler.post { onError(msg) }
            }
        }
    }

    /**
     * Start the tunnel to the local MCP server. Without a saved
     * token there is nothing to start: the error says to add the
     * key in Help. Reports the public URL as soon as ngrok prints
     * it or its local API serves it. If the process dies while the
     * tunnel is wanted, it restarts by itself with backoff, a few
     * tries, and only then gives up. When the process ends,
     * onStopped runs. If it ends before any URL appears and no
     * restart is left, onError runs with the last output line.
     */
    override fun start(
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        if (!installed) {
            mainHandler.post { onError("Tunnel binary missing, reinstall the app") }
            return
        }
        if (savedToken().isEmpty()) {
            mainHandler.post { onError("ngrok needs a key. Add it in Help.") }
            return
        }
        wanted = true
        restartAttempts = 0
        running = true
        publicUrl = null
        launchProcess(onUrl, onLog, onStopped, onError)
    }

    /**
     * Claim a found URL exactly once. The log reader and the local
     * API poll race each other; the first one to find the link
     * wins and the other one stays silent.
     */
    private fun claimUrl(url: String, onUrl: (String) -> Unit): Boolean {
        synchronized(this) {
            if (urlSent) return false
            urlSent = true
            publicUrl = url
            restartAttempts = 0
            restarting = false
        }
        mainHandler.post { onUrl(url) }
        return true
    }

    /** Ask ngrok's own local API for the public URL, if it has one yet. */
    private fun fetchTunnelUrlFromApi(): String? {
        return try {
            val conn = URL("http://127.0.0.1:4040/api/tunnels").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tunnels = JSONObject(body).optJSONArray("tunnels") ?: return null
            for (i in 0 until tunnels.length()) {
                val url = tunnels.getJSONObject(i).optString("public_url", "")
                if (url.startsWith("https://")) return url
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Poll the local API until the link appears or the tunnel goes away. */
    private fun pollLocalApi(onUrl: (String) -> Unit) {
        thread {
            var tries = 0
            while (tries < 30) {
                if (!wanted) return@thread
                val proc = process
                if (proc == null || !proc.isAlive) return@thread
                if (urlSent) return@thread
                val found = fetchTunnelUrlFromApi()
                if (found != null) {
                    claimUrl(found, onUrl)
                    return@thread
                }
                tries++
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    /** One run of the tunnel process, with restart on unexpected exit. */
    private fun launchProcess(
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            try {
                val builder = ProcessBuilder(
                    binaryFile().absolutePath, "http", "--log=stdout", "127.0.0.1:8765"
                )
                builder.environment()["NGROK_AUTHTOKEN"] = savedToken()
                builder.environment()["HOME"] = context.filesDir.absolutePath
                builder.redirectErrorStream(true)
                val proc = builder.start()
                process = proc
                urlSent = false
                pollLocalApi(onUrl)
                var lastLine = ""
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        lastLine = line
                        if (!urlSent) {
                            val found = TUNNEL_URL_REGEX.find(line)
                            if (found != null) claimUrl(found.value, onUrl)
                        }
                        val shown = line.take(90)
                        mainHandler.post { onLog(shown) }
                    }
                }
                val code = proc.waitFor()
                process = null
                if (!wanted) {
                    running = false
                    restarting = false
                    if (!urlSent) {
                        val detail = if (lastLine.isNotEmpty()) lastLine.take(120) else "exit code $code"
                        mainHandler.post { onError("Tunnel stopped: $detail") }
                    }
                    mainHandler.post { onStopped() }
                    return@thread
                }
                // Unexpected exit while the tunnel is wanted: restart
                // with backoff, a few tries, then give up.
                if (restartAttempts < MAX_RESTARTS) {
                    restartAttempts++
                    restarting = true
                    val delayMs = minOf(5000L shl (restartAttempts - 1), 30000L)
                    val retry = Runnable {
                        if (wanted) launchProcess(onUrl, onLog, onStopped, onError)
                    }
                    restartRunnable = retry
                    mainHandler.postDelayed(retry, delayMs)
                    return@thread
                }
                wanted = false
                running = false
                restarting = false
                mainHandler.post { onError("Tunnel stopped. Try again.") }
                mainHandler.post { onStopped() }
            } catch (e: Exception) {
                process = null
                if (!wanted) {
                    running = false
                    restarting = false
                    val msg = e.message ?: "error"
                    mainHandler.post { onError(msg) }
                    mainHandler.post { onStopped() }
                    return@thread
                }
                if (restartAttempts < MAX_RESTARTS) {
                    restartAttempts++
                    restarting = true
                    val delayMs = minOf(5000L shl (restartAttempts - 1), 30000L)
                    val retry = Runnable {
                        if (wanted) launchProcess(onUrl, onLog, onStopped, onError)
                    }
                    restartRunnable = retry
                    mainHandler.postDelayed(retry, delayMs)
                    return@thread
                }
                wanted = false
                running = false
                restarting = false
                mainHandler.post { onError("Tunnel stopped. Try again.") }
                mainHandler.post { onStopped() }
            }
        }
    }

    /** Kill the tunnel process, if one is running. Safe to call anytime. */
    override fun stop() {
        wanted = false
        restarting = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
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
