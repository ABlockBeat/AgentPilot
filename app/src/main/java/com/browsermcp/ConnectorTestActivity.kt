// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet6Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Hidden TEST screen for our own connector, version 0.6.22.
 *
 * Reached from a TEST row in Settings. It shows, as plain text:
 * the relay URL, the connector state (connected or not, and the
 * last error in plain words), and the phone's IPv6 addresses per
 * interface, each marked global, unique-local, link-local or
 * loopback. The IPv6 list is what tells us whether a direct
 * connection to the phone is possible one day; reading the
 * phone's own addresses is not a dependence on anyone.
 *
 * No virtual-display test, no Tailscale test: this screen only
 * covers our own connector.
 */
class ConnectorTestActivity : Activity() {

    private lateinit var outputView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val runButton = Button(this).apply {
            text = "Run test"
            setOnClickListener { runTest() }
        }
        layout.addView(runButton)
        outputView = TextView(this).apply { textSize = 15f }
        val scroll = ScrollView(this).apply { addView(outputView) }
        layout.addView(scroll)
        setContentView(layout)
        runTest()
    }

    /** Gather the lines on a worker thread, then show them. */
    private fun runTest() {
        outputView.text = "Running..."
        thread {
            val lines = ArrayList<String>()
            lines.add("Own connector test (0.6.22)")
            lines.add("")
            lines.add("Relay URL: " + DriveService.RELAY_URL)
            if (DriveService.isRelayPlaceholder()) {
                lines.add("Relay host: NOT SET YET (placeholder URL)")
            }
            lines.add("Connector state: " + OwnConnector.lastState)
            val error = OwnConnector.lastError
            if (error.isNotEmpty()) {
                lines.add("Last error: " + error)
            }
            lines.add("")
            lines.add("IPv6 addresses:")
            var foundAny = false
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address !is Inet6Address) continue
                        foundAny = true
                        val kind = when {
                            address.isLoopbackAddress -> "loopback"
                            address.isLinkLocalAddress -> "link-local"
                            address.isSiteLocalAddress -> "unique-local"
                            else -> "global"
                        }
                        lines.add("  " + iface.name + ": " + address.hostAddress + " (" + kind + ")")
                    }
                }
            } catch (e: Exception) {
                lines.add("  Could not list interfaces: " + e.message)
            }
            if (!foundAny) {
                lines.add("  None found.")
            }
            lines.add("")
            lines.add("Global = the phone can be reached directly on IPv6.")
            lines.add("Unique-local = only inside its own network.")
            lines.add("Link-local = only on its own link.")
            val result = lines.joinToString("\n")
            runOnUiThread { outputView.text = result }
        }
    }
}
