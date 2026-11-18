package com.browsermcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Built-in tunnel for AgentPilot, version 0.5.2.
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
 *   server address 127.0.0.1 port 8765. The MCP server must already
 *   be listening on 8765, so the tunnel only starts while the app
 *   is Running. The first trycloudflare.com URL in the output is
 *   the public link, shown in the app and tappable to copy.
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
        private const val BINARY_NAME = "libcloudflared.so"
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

    private fun binaryFile(): File = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /**
     * Check the bundled tunnel binary. There is nothing to download:
     * the binary ships inside the APK. This only proves it runs on
     * this phone, then done runs. Progress reports 100 at once, so
     * the caller UI barely changes.
     */
    fun install(
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
            mainHandler.post { onError("Tunnel binary missing, reinstall the app") }
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
