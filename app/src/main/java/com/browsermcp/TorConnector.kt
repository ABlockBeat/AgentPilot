// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * Tor backend, version 0.6.23.
 *
 * The direct connection that depends on nobody: no relay host,
 * no Cloudflare, no ngrok, no Tailscale. Orbot (Guardian
 * Project, free on F-Droid, no account) runs Tor on the phone
 * and publishes this phone's MCP server as a v3 onion service.
 * The cloud reaches the phone at its .onion address through
 * Tor. The address is public by design, and the MCP bearer
 * token still guards every request, checked first, exactly as
 * on every other transport.
 *
 * How it works:
 * - MainActivity asks Orbot for the onion service once, with
 *   the intent action org.torproject.android.REQUEST_V3_ONION_SERVICE
 *   (local port 8765, onion port 8765, name agentpilot-mcp).
 *   Orbot shows the owner a one-time Allow dialog and returns
 *   the .onion hostname, which is saved in the plain prefs:
 *   the address is public, it is not a secret.
 * - From then on this backend only reports the saved address.
 *   The onion service lives in Orbot, so the address stays the
 *   same across sessions. If Orbot is reinstalled, or the
 *   address is lost, MainActivity asks Orbot again from
 *   Settings.
 * - Orbot must be running for the address to answer. If Orbot
 *   was stopped, the cloud cannot reach the phone until Orbot
 *   runs again; the TEST screen shows the saved address and
 *   the state, and Settings can ask Orbot again.
 *
 * All callbacks run on the main thread, so the caller can touch
 * the UI in them directly.
 */
class TorConnector(private val context: Context) : TunnelBackend {

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"

        /** Orbot's external-app API (Orbot commit 0123927a):
         * ask Orbot to publish a v3 onion service, and it
         * returns the .onion hostname in the activity result. */
        const val INTENT_ACTION_REQUEST_V3_ONION_SERVICE =
            "org.torproject.android.REQUEST_V3_ONION_SERVICE"
        const val EXTRA_LOCAL_PORT = "hs_port"
        const val EXTRA_ONION_PORT = "hs_onion_port"
        const val EXTRA_SERVICE_NAME = "hs_name"
        const val EXTRA_REQUESTED_HOSTNAME =
            "org.torproject.android.REQUESTED_V3_HOSTNAME"
        const val SERVICE_NAME = "agentpilot-mcp"

        /** Connector state for the TEST screen: "ready" when the
         * saved address is reported, "not set up" when no address
         * was ever saved, "Orbot missing" when Orbot is not
         * installed. The token never appears here, only the state. */
        @Volatile
        var lastState: String = "not connected"
            private set
        @Volatile
        var lastError: String = ""
            private set

        /** True when Orbot is installed on this phone. */
        @Suppress("DEPRECATION")
        fun isOrbotInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** Nothing to install in this app: Orbot is a separate app. */
    override val installed: Boolean get() = true

    override fun install(
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post { onDone() }
    }

    /**
     * Report the saved .onion address as the connection. The
     * onion service itself lives in Orbot: this backend holds
     * no connection of its own, it only publishes the address
     * Orbot gave us, the same way Tailscale mode publishes the
     * phone's private-network address.
     */
    override fun start(
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        wanted = true
        running = true
        publicUrl = null
        if (!isOrbotInstalled(context)) {
            wanted = false
            running = false
            lastState = "Orbot missing"
            lastError = "Orbot is not installed."
            mainHandler.post { onError("Install Orbot from F-Droid, then try again.") }
            mainHandler.post { onStopped() }
            return
        }
        val hostname = DriveService.savedOnionHostname(context)
        if (hostname.isEmpty()) {
            wanted = false
            running = false
            lastState = "not set up"
            lastError = "No Tor address yet."
            mainHandler.post { onError("Tor is not set up yet. Open AgentPilot and tap Start.") }
            mainHandler.post { onStopped() }
            return
        }
        val url = DriveService.torUrl(hostname)
        publicUrl = url
        lastState = "ready"
        lastError = ""
        mainHandler.post { onUrl(url) }
    }

    /** Drop the reported address. Safe to call anytime. */
    override fun stop() {
        wanted = false
        restarting = false
        publicUrl = null
        running = false
        lastState = "not connected"
    }
}
