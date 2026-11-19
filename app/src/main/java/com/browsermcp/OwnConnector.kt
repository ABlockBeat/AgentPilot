// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Our own connector, version 0.6.23.
 *
 * The primary connection, and the only one that depends on nobody:
 * no Cloudflare, no ngrok, no Tailscale, no outside tunnel
 * service. The phone dials OUT to our own relay (see relay/) and
 * holds the connection; the cloud dials the same relay, and the
 * relay forwards the MCP messages between them. The room is the
 * MCP token, so only someone holding the token can reach this
 * phone. The existing backends stay in the app as fallback, but
 * this one is first.
 *
 * It implements TunnelBackend so the session owner never learns
 * which connection is running: install is a no-op (there is no
 * binary), start connects to the relay, and a drop while the
 * session is wanted restarts with backoff, a few tries, exactly
 * like the tunnel managers. The MCP requests arriving over the
 * relay go through the same dispatcher as the HTTP server
 * (McpServer.handleJsonRpc), so the tools are identical on both
 * transports. The local HTTP server still runs on loopback; the
 * relay is an additional transport, not a replacement for it.
 *
 * All callbacks run on the main thread, so the caller can touch
 * the UI in them directly.
 */
class OwnConnector(
    private val context: Context,
    private val dispatch: (String) -> String?
) : TunnelBackend {

    companion object {
        private const val MAX_RESTARTS = 5

        /** Connector state for the TEST screen: "connected" or
         * "not connected", plus the last error in plain words.
         * The token never appears here, only the state. */
        @Volatile
        var lastState: String = "not connected"
            private set
        @Volatile
        var lastError: String = ""
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: RelayClient? = null
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

    /** Nothing to install: the connector is code, not a binary. */
    override val installed: Boolean get() = true

    override fun install(
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post { onDone() }
    }

    /**
     * Connect to the relay and hold the connection. Reports the
     * relay URL as soon as the handshake passes. If the relay
     * drops while the session is wanted, it reconnects with
     * backoff, a few tries, and only then gives up.
     */
    override fun start(
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        wanted = true
        restartAttempts = 0
        running = true
        publicUrl = null
        launchConnection(onUrl, onLog, onStopped, onError)
    }

    /** One connection attempt, with restart on unexpected end. */
    private fun launchConnection(
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        thread {
            if (!wanted) return@thread
            if (DriveService.isRelayPlaceholder()) {
                // The relay URL is still the placeholder: no host
                // exists to dial, so retrying would only burn
                // battery. Say so plainly instead.
                wanted = false
                running = false
                lastError = "Relay host not set yet."
                mainHandler.post { onError("Relay host not set yet. Set the relay URL first.") }
                mainHandler.post { onStopped() }
                return@thread
            }
            val token = DriveService.storedToken(context)
            val relayClient = RelayClient(
                onMessage = { json -> dispatch(json) },
                onState = { state ->
                    if (state == "connected") {
                        lastState = "connected"
                        lastError = ""
                        mainHandler.post { onLog("Relay connected") }
                    } else {
                        lastState = "not connected"
                        lastError = state.removePrefix("error: ")
                    }
                },
                // The launch thread below waits on awaitDisconnect,
                // so this callback has nothing to do.
                onDisconnected = { }
            )
            client = relayClient
            val ok = relayClient.connect(DriveService.RELAY_URL, token)
            if (!ok) {
                client = null
                if (!wanted) {
                    running = false
                    return@thread
                }
                if (restartAttempts < MAX_RESTARTS) {
                    restartAttempts++
                    restarting = true
                    val delayMs = minOf(5000L shl (restartAttempts - 1), 30000L)
                    val retry = Runnable {
                        if (wanted) launchConnection(onUrl, onLog, onStopped, onError)
                    }
                    restartRunnable = retry
                    mainHandler.postDelayed(retry, delayMs)
                    return@thread
                }
                wanted = false
                running = false
                restarting = false
                mainHandler.post { onError("Relay unreachable. Try again.") }
                mainHandler.post { onStopped() }
                return@thread
            }
            // Connected. If stop() raced in during the handshake,
            // end at once instead of reporting a URL after a stop.
            if (!wanted) {
                relayClient.stop()
                client = null
                running = false
                mainHandler.post { onStopped() }
                return@thread
            }
            restartAttempts = 0
            restarting = false
            publicUrl = DriveService.RELAY_URL
            lastState = "connected"
            val url = DriveService.RELAY_URL
            mainHandler.post { onUrl(url) }
            relayClient.awaitDisconnect()
            lastError = relayClient.lastError
            client = null
            publicUrl = null
            lastState = "not connected"
            if (!wanted) {
                running = false
                restarting = false
                mainHandler.post { onStopped() }
                return@thread
            }
            if (restartAttempts < MAX_RESTARTS) {
                restartAttempts++
                restarting = true
                val delayMs = minOf(5000L shl (restartAttempts - 1), 30000L)
                val retry = Runnable {
                    if (wanted) launchConnection(onUrl, onLog, onStopped, onError)
                }
                restartRunnable = retry
                mainHandler.postDelayed(retry, delayMs)
                return@thread
            }
            wanted = false
            running = false
            restarting = false
            mainHandler.post { onError("Relay connection lost. Try again.") }
            mainHandler.post { onStopped() }
        }
    }

    /** Drop the relay connection, if one is up. Safe to call anytime. */
    override fun stop() {
        wanted = false
        restarting = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        // Stop on a worker thread: the client join can take a
        // moment, and this is called from the main thread.
        val current = client
        client = null
        if (current != null) thread { current.stop() }
        publicUrl = null
        running = false
        lastState = "not connected"
    }
}
