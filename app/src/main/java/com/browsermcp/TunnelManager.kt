// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Built-in tunnel for AgentPilot, version 0.6.25.
 *
 * The tunnel lives inside the app. No Termux, no download, no Play
 * Store, no outside app at any point. The cloudflared binary ships
 * inside the APK as a native library, and the installer extracts it
 * to the app native library dir, where Android lets the app run it.
 *
 * Why bundled: version 0.5.0 downloaded the binary into the app
 * private files dir and tried to run it there. Android 10 and newer
 * refuse to run a program from the app data dir, so the owner phone
 * answered "Cannot run program" and the tunnel never started. A
 * native library is the fix: it is extracted at install time to a
 * dir the app is allowed to run programs from.
 *
 * How it works:
 * - Check: run the bundled binary with "version". It must exit 0
 *   and print "cloudflared". If the file is missing or not
 *   runnable, the only fix is reinstalling the app, and the error
 *   says so in plain words.
 * - Start: run the bundled binary with "tunnel" and the local MCP
 *   server address, loopback on the MCP port. The MCP server must
 *   already be listening on that port, so the tunnel only starts
 *   while the app is Running. The first trycloudflare.com URL in
 *   the output is the public link, shown in the app and tappable
 *   to copy. Cloudflare's own API host api.trycloudflare.com also
 *   matches that shape, so the matcher skips it: it is not a
 *   tunnel.
 * - Stay up: if the tunnel process dies while the tunnel is
 *   wanted, it restarts by itself with backoff, a few tries, and
 *   only then gives up. A network change or a doze must not leave
 *   the owner with a dead link and no message.
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
class TunnelManager(private val context: Context) : TunnelBackend {

    companion object {
        private const val BINARY_NAME = "libcloudflared.so"
        private const val MAX_RESTARTS = 5
        private val TUNNEL_URL_REGEX = Regex("https://(?!api\\.)[A-Za-z0-9-]+\\.trycloudflare\\.com")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var restartRunnable: Runnable? = null
    private var restartAttempts: Int = 0

    @Volatile var wanted: Boolean = false
        private set
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

    /**
     * Check the bundled tunnel binary. There is nothing to download:
     * the binary ships inside the APK. This only proves it runs on
     * this phone, then done runs. Progress reports 100 at once, so
     * the caller UI barely changes.
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
                if (code != 0 || !out.contains("cloudflared")) {
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
     * Start the tunnel to the local MCP server. Reports the public
     * URL as soon as cloudflared prints it. If the process dies
     * while the tunnel is wanted, it restarts by itself with
     * backoff, a few tries, and only then gives up. When the
     * process ends, onStopped runs. If it ends before any URL
     * appears and no restart is left, onError runs with the last
     * output line.
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
        wanted = true
        restartAttempts = 0
        running = true
        publicUrl = null
        launchProcess(onUrl, onLog, onStopped, onError)
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
                val proc = ProcessBuilder(
                    binaryFile().absolutePath, "tunnel", "--url",
                    DriveService.MCP_LOOPBACK_URL
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
                                restartAttempts = 0
                                restarting = false
                                val url = found.value
                                mainHandler.post { onUrl(url) }
                            }
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
