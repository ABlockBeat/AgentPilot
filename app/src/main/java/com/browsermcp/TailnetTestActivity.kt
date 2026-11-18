// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.browsermcp.tailnet.Tailnet
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * TEST ONLY: embedded Tailscale spike. A hidden debug screen,
 * reached from Settings and clearly marked TEST. It starts the
 * embedded tsnet node with a pasted one-time auth key, shows the
 * Status JSON and the 100.x address, and stops the node. It touches
 * nothing else: the Cloudflare, ngrok and separate-app Tailscale
 * backends, the MCP server and its bind behaviour, and the version
 * are all unchanged.
 *
 * The auth key is used once and cleared from the field at once. It
 * is never saved, shown back, or logged. Start and Stop run off the
 * UI thread: Start blocks until the node is up, the login URL
 * appears, or 60 seconds pass.
 */
class TailnetTestActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var keyInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button
    private lateinit var proxyButton: Button
    private var busy: Boolean = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Node state lives outside backup: it holds the node key. */
    private fun stateDir(): String = File(noBackupFilesDir, "tsnet").absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val title = TextView(this).apply {
            text = "TEST: Embedded Tailscale"
            textSize = 18f
            setPadding(dp(0), dp(0), dp(0), dp(8))
        }
        body.addView(title)
        val note = TextView(this).apply {
            text = "Spike only. Paste a one-time Tailscale auth key, Start, and check the 100.x address below. The real check is the cloud dialing that address. This screen changes nothing about the normal tunnel."
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(0), dp(0), dp(0), dp(12))
        }
        body.addView(note)
        keyInput = EditText(this).apply {
            hint = "Paste Tailscale auth key here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(keyInput)
        statusView = TextView(this).apply {
            text = "Not started."
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(0), dp(12), dp(0), dp(12))
        }
        body.addView(statusView)
        fun testButton(label: String, action: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 15f
                setBackgroundResource(R.drawable.rounded_button_outline)
                setTextColor(getColor(R.color.accent))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
                setOnClickListener { action() }
            }
        }
        startButton = testButton("Start node") { startNode() }
        body.addView(startButton)
        stopButton = testButton("Stop node") { stopNode() }
        body.addView(stopButton)
        refreshButton = testButton("Refresh status") { refreshStatus() }
        body.addView(refreshButton)
        clearButton = testButton("Clear node state") { clearState() }
        body.addView(clearButton)
        proxyButton = testButton("Test local MCP (proxy target)") { testLocalMcp() }
        body.addView(proxyButton)
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)
        refreshStatus()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        startButton.isEnabled = !b
        stopButton.isEnabled = !b
        refreshButton.isEnabled = !b
        clearButton.isEnabled = !b
        proxyButton.isEnabled = !b
    }

    private fun startNode() {
        val key = keyInput.text.toString().trim()
        // The key never stays in the field, on any path.
        keyInput.setText("")
        setBusy(true)
        statusView.text = "Starting..."
        thread {
            var failure: String? = null
            try {
                Tailnet.start(stateDir(), key, "agentpilot", false, 60000L)
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            runOnUiThread {
                setBusy(false)
                if (failure != null) {
                    statusView.text = "Start failed: $failure"
                } else {
                    refreshStatus()
                }
            }
        }
    }

    private fun stopNode() {
        setBusy(true)
        statusView.text = "Stopping..."
        thread {
            var failure: String? = null
            try {
                Tailnet.stop()
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            runOnUiThread {
                setBusy(false)
                if (failure != null) {
                    statusView.text = "Stop failed: $failure"
                } else {
                    refreshStatus()
                }
            }
        }
    }

    private fun refreshStatus() {
        thread {
            var json: String? = null
            var failure: String? = null
            try {
                json = Tailnet.status()
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            var ip = ""
            try {
                ip = Tailnet.tailnetIP()
            } catch (e: Exception) {
                // Status already carries the error; the IP is extra.
            }
            runOnUiThread {
                statusView.text = if (json != null) {
                    json + "\nTailnet IP: " + if (ip.isEmpty()) "(none yet)" else ip
                } else {
                    "Status failed: $failure"
                }
            }
        }
    }

    private fun clearState() {
        setBusy(true)
        statusView.text = "Clearing state..."
        thread {
            var failure: String? = null
            try {
                Tailnet.clearState(stateDir())
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            runOnUiThread {
                setBusy(false)
                if (failure != null) {
                    statusView.text = "Clear failed: $failure"
                } else {
                    statusView.text = "Node state cleared. Next Start enrolls a fresh node."
                    Toast.makeText(this, "Node state cleared.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * The Go proxy forwards to this server, so it must answer
     * before any cloud test can pass. Needs a running session from
     * the home screen: this screen starts no session by itself.
     */
    private fun testLocalMcp() {
        statusView.text = "Testing local MCP..."
        thread {
            val ok = try {
                val conn = URL("http://127.0.0.1:8765/mcp").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer " + DriveService.storedToken(this))
                conn.doOutput = true
                val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                code == 200 && body.contains("\"tools\"")
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                statusView.text = if (ok) {
                    "Local MCP answers on 127.0.0.1:8765. The Go proxy forwards to this."
                } else {
                    "Local MCP does not answer. Start a session from the home screen first."
                }
            }
        }
    }
}
