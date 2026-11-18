package com.browsermcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * AgentPilot, keep alive, version 0.5.1.
 *
 * A tiny foreground service with a visible notification. Its only job
 * is to keep the app process alive while MCP is on, so the MCP server
 * keeps answering while the owner uses the target app in front.
 * Without it, Android may kill the app as soon as it goes to the
 * background, and the server would die exactly when it is needed.
 * Tapping the notification returns to AgentPilot, one tap, while
 * staying connected. It does nothing else: no network, no location,
 * no data.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "AgentPilot",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        // Tap the notification to return to AgentPilot from the
        // target app. The existing activity is brought forward, not
        // recreated, so MCP keeps running.
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentPilot is on")
            .setContentText("Tap to return to AgentPilot.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tap)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "browser_mcp_keep_alive"
    }
}
