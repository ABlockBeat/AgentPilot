// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AgentPilot, session owner, version 0.6.7.
 *
 * This service owns the whole connection: the MCP server, the
 * tunnel, and the log of what the agent did. It is a started
 * foreground service, so the session survives the app screen
 * completely: Back, swipe away, rotation, the system reclaiming
 * the activity, all leave the session running. The Activity only
 * shows state and sends commands. Nothing here stops unless the
 * owner taps Disconnect, or the server itself dies.
 *
 * How it works:
 * - The Activity sends commands as intents: start session, stop
 *   session, start tunnel, stop tunnel, restart the server with a
 *   new token. The service does the work and broadcasts state
 *   changes, so the Activity refreshes without polling.
 * - The MCP server binds 127.0.0.1 port 8765 with the bearer token
 *   saved in prefs. The token never changes on its own.
 * - The tunnel starts by itself with the session and stops with
 *   it. One tap on Start connects everything: server and tunnel
 *   together, no separate tunnel step. After the process dies and
 *   the service restarts, the session comes back if the owner had
 *   it on, and the tunnel comes back with it.
 * - If the tunnel process dies while it is wanted, it restarts by
 *   itself with backoff, a few tries.
 * - A watchdog checks the Accessibility service every few seconds
 *   while the session runs. If it dies mid-session, the
 *   notification says so at once instead of showing a lying
 *   Running state.
 * - The action log keeps the last 50 tool calls with time, in
 *   memory. Errors are mapped to plain words for the owner; the
 *   raw detail goes in the log only.
 *
 * It does nothing else: no location, no data out, no password ever.
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "browser_mcp_keep_alive"
        const val ACTION_STATE_CHANGED = "com.browsermcp.SESSION_STATE"
        const val ACTION_START_SESSION = "com.browsermcp.START_SESSION"
        const val ACTION_STOP_SESSION = "com.browsermcp.STOP_SESSION"
        const val ACTION_START_TUNNEL = "com.browsermcp.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "com.browsermcp.STOP_TUNNEL"
        const val ACTION_RESTART_SERVER = "com.browsermcp.RESTART_SERVER"
        const val EXTRA_ERROR = "error"
        const val KEY_SESSION_WANTED = "session_wanted"
        const val KEY_TUNNEL_BACKEND = "tunnel_backend"
        const val KEY_NGROK_TOKEN = "ngrok_token"
        const val KEY_FLOATING_WINDOW = "floating_window"

        @Volatile
        var instance: KeepAliveService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private var mcpServer: McpServer? = null
    private val tunnelManager: TunnelManager by lazy { TunnelManager(this) }
    private val ngrokManager: NgrokManager by lazy { NgrokManager(this) }
    private val floatingOverlay: FloatingOverlay by lazy { FloatingOverlay(this) }
    private val actionLog = ArrayDeque<String>()

    @Volatile var sessionRunning: Boolean = false
        private set
    @Volatile var a11yDead: Boolean = false
        private set
    @Volatile private var lastTunnelLine: String = ""
    @Volatile private var lastTunnelError: String = ""

    /** Cloudflare is the default; ngrok is the alternative. */
    private fun activeTunnel(): TunnelBackend =
        if (prefs.getString(KEY_TUNNEL_BACKEND, "cloudflare") == "ngrok") ngrokManager else tunnelManager

    val tunnelRunning: Boolean get() = activeTunnel().running
    val tunnelInstalling: Boolean get() = activeTunnel().installing
    val tunnelInstalled: Boolean get() = activeTunnel().installed
    val tunnelRestarting: Boolean get() = activeTunnel().restarting
    val tunnelUrl: String? get() = activeTunnel().publicUrl
    val tunnelLastLine: String get() = lastTunnelLine
    val tunnelError: String get() = lastTunnelError

    /** The action log, newest first, for the Help screen. */
    fun actionLogSnapshot(): List<String> = synchronized(actionLog) { actionLog.toList() }

    /** One line in the action log, with the time it happened. */
    fun logAction(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        synchronized(actionLog) {
            actionLog.addFirst("$time  $text")
            while (actionLog.size > 50) actionLog.removeLast()
        }
    }

    private val watchdogRunnable: Runnable = Runnable {
        if (!sessionRunning) return@Runnable
        val dead = DriveService.instance == null
        if (dead != a11yDead) {
            a11yDead = dead
            updateNotification(
                if (dead) "Accessibility off. Turn it on in Settings."
                else "Tap to return to AgentPilot."
            )
            broadcastState()
        }
        mainHandler.postDelayed(watchdogRunnable, 5000)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(DriveService.PREFS, MODE_PRIVATE)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "AgentPilot",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
        startForeground(1, buildNotification("Tap to return to AgentPilot."))
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession()
            ACTION_STOP_SESSION -> stopSession()
            ACTION_START_TUNNEL -> startTunnelFlow()
            ACTION_STOP_TUNNEL -> stopTunnel()
            ACTION_RESTART_SERVER -> restartServer()
            null -> {
                // The system restarted the service after the process
                // died. Bring the session back if the owner had it
                // on. The tunnel comes back with the session.
                if (prefs.getBoolean(KEY_SESSION_WANTED, false)) startSession()
            }
            else -> { }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchdogRunnable)
        tunnelManager.stop()
        ngrokManager.stop()
        floatingOverlay.hide()
        mcpServer?.stop()
        mcpServer = null
        sessionRunning = false
        instance = null
        super.onDestroy()
    }

    // ---- Session ----

    /**
     * Start the MCP server with the saved token. The bind happens
     * on this thread, so a failure is known at once and the session
     * is never claimed Running while the server is dead. The tunnel
     * starts by itself right after the server, so one tap connects
     * everything. If the tunnel is already running, starting it
     * again is a no-op.
     */
    private fun startSession() {
        if (sessionRunning) return
        val token = DriveService.storedToken(this)
        val server = McpServer(
            token,
            onLog = { name -> logAction(name) },
            onServerStopped = { msg -> onServerDied(msg) }
        )
        if (!server.start()) {
            logAction("Server failed to start")
            broadcastState(error = "Could not start. Try again.")
            return
        }
        mcpServer = server
        sessionRunning = true
        a11yDead = DriveService.instance == null
        prefs.edit().putBoolean(KEY_SESSION_WANTED, true).apply()
        logAction("Session started")
        updateNotification(
            if (a11yDead) "Accessibility off. Turn it on in Settings."
            else "Tap to return to AgentPilot."
        )
        broadcastState()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.post(watchdogRunnable)
        // One tap connects everything: the tunnel starts by itself
        // with the session, no separate step for the owner.
        startTunnelFlow()
        updateFloatingOverlay()
    }

    private fun stopSession() {
        mainHandler.removeCallbacks(watchdogRunnable)
        tunnelManager.stop()
        ngrokManager.stop()
        mcpServer?.stop()
        mcpServer = null
        sessionRunning = false
        a11yDead = false
        prefs.edit().putBoolean(KEY_SESSION_WANTED, false).apply()
        logAction("Session stopped")
        broadcastState()
        stopForeground(true)
        stopSelf()
    }

    /** The server died on its own. Never claim Running after that. */
    private fun onServerDied(msg: String?) {
        if (!sessionRunning) return
        logAction("Server stopped")
        broadcastState(error = "Connection lost. Tap Start again.")
        stopSession()
    }

    /** Restart the server with the current saved token. */
    private fun restartServer() {
        if (!sessionRunning) return
        mcpServer?.stop()
        mcpServer = null
        sessionRunning = false
        startSession()
    }

    // ---- Tunnel ----

    /**
     * Start the tunnel to the local MCP server. Called by itself
     * when the session starts, so the owner never taps a tunnel
     * button. If the session is off, it starts first, then the
     * tunnel. The tunnel check runs, then the tunnel starts.
     * ngrok needs a key saved first: without it the error says
     * so in plain words instead of failing silently.
     */
    private fun startTunnelFlow() {
        if (!sessionRunning) startSession()
        if (!sessionRunning) return
        lastTunnelError = ""
        val tunnel = activeTunnel()
        if (tunnel.running || tunnel.installing) return
        if (prefs.getString(KEY_TUNNEL_BACKEND, "cloudflare") == "ngrok" &&
            prefs.getString(KEY_NGROK_TOKEN, "").isNullOrEmpty()
        ) {
            onTunnelError("ngrok needs a key. Add it in Help.")
            return
        }
        tunnel.install(
            onProgress = { },
            onDone = { launchTunnel() },
            onError = { raw -> onTunnelError(raw) }
        )
        broadcastState()
    }

    private fun launchTunnel() {
        activeTunnel().start(
            onUrl = {
                lastTunnelError = ""
                logAction("Tunnel started")
                updateFloatingOverlay()
                broadcastState()
            },
            onLog = { line -> lastTunnelLine = line },
            onStopped = { broadcastState() },
            onError = { raw -> onTunnelError(raw) }
        )
    }

    /** Stop every tunnel backend, so a switch never leaves one running. */
    private fun stopTunnel() {
        tunnelManager.stop()
        ngrokManager.stop()
        broadcastState()
    }

    /**
     * A tunnel failure in plain words for the owner. The raw detail
     * goes in the action log only, never on his screen. The last
     * line is also kept for Help, so a failure says why.
     */
    private fun onTunnelError(raw: String) {
        lastTunnelLine = "Error: $raw"
        val plain = plainTunnelError(raw)
        lastTunnelError = plain
        logAction("Tunnel error: $raw")
        broadcastState(error = plain)
    }

    private fun plainTunnelError(msg: String): String {
        val lower = msg.lowercase()
        return when {
            "reinstall" in lower || "key" in lower || "help" in lower -> msg
            "cannot run program" in lower || "permission denied" in lower ->
                "Tunnel did not start. Reinstall the app."
            "unknownhost" in lower || "unable to resolve" in lower ||
                "no address" in lower || "connectexception" in lower ||
                "sockettimeout" in lower || "network" in lower ->
                "No internet. Try again."
            else -> "Tunnel did not start. Try again."
        }
    }

    // ---- Floating window ----

    /** Turn the floating window on or off. The choice is saved. */
    fun setFloatingWindow(on: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_WINDOW, on).apply()
        if (on) updateFloatingOverlay() else floatingOverlay.hide()
        broadcastState()
    }

    /**
     * Keep the floating window in step with the session. It shows
     * only while the session runs and the owner has it on; when
     * the session stops, the window goes away with it.
     */
    private fun updateFloatingOverlay() {
        if (!prefs.getBoolean(KEY_FLOATING_WINDOW, false)) return
        if (!sessionRunning) {
            floatingOverlay.hide()
            return
        }
        val statusText = if (a11yDead) "AgentPilot: Accessibility off" else "AgentPilot: Running"
        floatingOverlay.show(statusText, tunnelUrl)
    }

    // ---- Notification and state broadcast ----

    private fun buildNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentPilot is on")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tap)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(1, buildNotification(text))
    }

    private fun broadcastState(error: String? = null) {
        val intent = Intent(ACTION_STATE_CHANGED).setPackage(packageName)
        if (error != null) intent.putExtra(EXTRA_ERROR, error)
        sendBroadcast(intent)
        updateFloatingOverlay()
    }
}
