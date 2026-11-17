package com.browsermcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Browser MCP, keep alive, version 0.4.0.
 *
 * A tiny foreground service with a visible notification. Its only job
 * is to keep the app process alive while MCP is on, so the MCP server
 * keeps answering while the owner uses the target app in front.
 * Without it, Android may kill the app as soon as it goes to the
 * background, and the server would die exactly when it is needed.
 * It does nothing else: no network, no location, no data.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Browser MCP",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Browser MCP is on")
            .setContentText("MCP server running on this phone.")
            .setSmallIcon(R.mipmap.ic_launcher)
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
