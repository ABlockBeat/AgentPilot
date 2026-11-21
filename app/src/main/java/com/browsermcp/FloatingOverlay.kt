// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Floating window for AgentPilot, version 0.6.26.
 *
 * A small window over other apps, so the owner can see the
 * connection state and the tunnel link while the agent drives
 * Chrome. It never takes focus, so taps still reach Chrome
 * underneath. Drag it anywhere by its empty space; tap the link
 * to copy it; tap the cross to hide it. It shows only when the
 * owner turns it on in Help, and only while the session runs.
 * The overlay permission is asked once, in Help, before the
 * first show.
 */
class FloatingOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var urlView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentUrl: String? = null

    fun shown(): Boolean = root != null

    fun show(status: String, url: String?) {
        if (root != null) {
            update(status, url)
            return
        }
        val statusText = TextView(context).apply {
            text = status
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        statusView = statusText
        val linkText = TextView(context).apply {
            text = url ?: "No link yet"
            textSize = 12f
            setTextColor(Color.parseColor("#7DD3FC"))
            setOnClickListener {
                val link = currentUrl ?: return@setOnClickListener
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AgentPilot tunnel", link))
                Toast.makeText(context, "Tunnel link copied.", Toast.LENGTH_SHORT).show()
            }
        }
        urlView = linkText
        val closeText = TextView(context).apply {
            text = "X"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(0), dp(0), dp(0))
            setOnClickListener { hide() }
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusText)
            addView(closeText)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC111827"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(topRow)
            addView(linkText)
            setOnTouchListener(DragTouch())
        }
        currentUrl = url
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24)
            y = dp(120)
        }
        params = lp
        root = box
        try {
            windowManager.addView(box, lp)
        } catch (e: Exception) {
            root = null
            statusView = null
            urlView = null
            params = null
            currentUrl = null
        }
    }

    fun update(status: String, url: String?) {
        if (root == null) {
            show(status, url)
            return
        }
        currentUrl = url
        statusView?.text = status
        urlView?.text = url ?: "No link yet"
    }

    fun hide() {
        val v = root ?: return
        root = null
        statusView = null
        urlView = null
        params = null
        currentUrl = null
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            // Already gone, nothing to do.
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /**
     * Drag the window by its empty space. Taps on the link and on
     * the cross reach those views first, so a tap never drags and
     * a drag never taps.
     */
    private inner class DragTouch : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    val shown = root ?: return true
                    windowManager.updateViewLayout(shown, lp)
                    return true
                }
            }
            return false
        }
    }
}
