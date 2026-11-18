// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * AgentPilot, Android app, version 0.6.8.
 *
 * Minimal interface, one rule only: one status line and one
 * button. Nothing else on the screen. The button always does
 * the next step: set up, turn on Accessibility, start, disconnect.
 *
 * Once a target app is picked, two small buttons appear under
 * the main button, and only then: Continue to Chrome, so the
 * owner can log in by hand in the real browser, and Change app,
 * to switch to another app while staying connected. One tap on
 * Start connects everything: the tunnel starts by itself with
 * the session and the link appears on its own. A Share connection
 * button sits at the bottom, next to Help, only when the link
 * exists, and it sends the link and the key together. Help is a
 * guided flow, not a reading list: every step is a row with a
 * button that does that step right there, one tap, no dead ends.
 *
 * One engine only: DriveService, an AccessibilityService that drives
 * the REAL apps on the phone, the ones the owner already uses and is
 * already logged into: real Chrome, KuCoin, Bitget, any app the owner
 * picks. That is how your agent drives your browser on your phone: no
 * cloud browser, no laptop, no extension.
 *
 * The connection lives in KeepAliveService, not in this screen.
 * The MCP server, the tunnel and the action log all belong to the
 * foreground service, so Back, swipe away, or the system
 * reclaiming this screen never disconnects anything. This screen
 * only shows state and sends commands. Back sends the app to the
 * background instead of finishing it.
 *
 * The tunnel lives inside the app, in TunnelManager. No Termux, no
 * download, no Play Store, no outside app at any point. The
 * cloudflared binary ships inside the APK and runs from there.
 * The tunnel starts by itself when the session starts and stops
 * with it. The public link to the MCP server on this phone
 * appears on the home screen as soon as it exists. If the tunnel
 * drops while it is wanted, it restarts by itself.
 *
 * The in-app WebView browser is gone. It was an option that did not
 * do the real work: exchanges detect a WebView and block logins, so
 * it only looked like a browser. Only what works stays in the app.
 *
 * How it works:
 * - The owner picks ONE target app in this UI. The service drives
 *   only that package, plus system UI for Back and Home. Every other
 *   package is refused with a clear error.
 * - The owner turns the service on by hand in Settings, Accessibility.
 *   He can turn it off any time. Nothing here runs without that switch.
 * - The MCP server on this phone only, with a bearer token, exposes
 *   the 12 drive tools. The token is generated once, saved, and
 *   shown in Help with tap to copy. The owner sees everything on
 *   this screen and can take over at any time with his own hands.
 * - Setup is one-time and saved: the target pick and the Accessibility
 *   switch stay saved, and MCP starts by itself when the app opens.
 *   The owner only taps the app and it is ready.
 *
 * Safety, hard rules, never broken:
 * - No password is ever filled by the agent. Login is always by hand.
 */
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var updateButton: Button
    private lateinit var continueButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var changeButton: Button
    private lateinit var tunnelStatus: TextView
    private lateinit var helpButton: Button
    private var updateDownloadUrl: String = ""
    private var updateVersion: String = ""
    private var downloading: Boolean = false
    private var transientStatus: String? = null
    private var transientUntil: Long = 0
    private lateinit var prefs: SharedPreferences
    private var autoStartTries: Int = 0
    private var autoStartScheduled: Boolean = false
    private var receiverRegistered: Boolean = false

    /**
     * Crypto apps stay out of the picker, standing owner order.
     * The shortlist never had them; the all-apps list keeps the
     * same exclusion, by package and by name.
     */
    private val cryptoPackages = setOf(
        "com.binance.dev",
        "com.kubi.kucoin",
        "com.bitget.exchange",
        "com.okinc.okex.gp",
        "com.gateio.gateio",
        "com.tradingview.tradingviewapp"
    )
    private val cryptoLabels = listOf(
        "binance", "kucoin", "bitget", "okx", "gate.io", "tradingview",
        "bybit", "coinbase", "kraken"
    )

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi()
            val error = intent?.getStringExtra(KeepAliveService.EXTRA_ERROR)
            if (!error.isNullOrEmpty()) status(error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(DriveService.PREFS, MODE_PRIVATE)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        updateButton = findViewById(R.id.updateButton)
        continueButton = findViewById(R.id.continueButton)
        shareButton = findViewById(R.id.shareButton)
        copyButton = findViewById(R.id.copyButton)
        changeButton = findViewById(R.id.changeButton)
        tunnelStatus = findViewById(R.id.tunnelStatus)
        helpButton = findViewById(R.id.helpButton)

        actionButton.setOnClickListener { doNextStep() }
        continueButton.setOnClickListener { openTargetApp() }
        shareButton.setOnClickListener { shareConnection() }
        copyButton.setOnClickListener { copyConnection() }
        changeButton.setOnClickListener { showAppPicker() }
        tunnelStatus.setOnClickListener { copyTunnelUrl() }
        helpButton.setOnClickListener { showHelp() }

        updateButton.setOnClickListener {
            when {
                downloading -> status("Downloading update... please wait.")
                updateDownloadUrl.isNotEmpty() -> downloadUpdate(updateDownloadUrl)
                else -> {
                    // The launch-time check never armed a download
                    // link (offline, or the check failed): a tap must
                    // never do nothing. Say so and check again now.
                    status("Checking for update...")
                    checkForUpdate(manual = true)
                }
            }
        }

        // The connection key is generated once, the first time the
        // app runs, and saved. It never changes on its own.
        DriveService.storedToken(this)

        updateUi()
        maybeAutoStartMcp()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            // Android 13 and newer require this flag on registerReceiver
            // for a non-system broadcast. Our state broadcast is sent
            // only to this app's own package, so NOT_EXPORTED is right.
            // Without it the app crashes at resume on API 33 and newer.
            registerReceiver(stateReceiver, IntentFilter(KeepAliveService.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        // The owner may have toggled the service in Settings. Refresh,
        // and give the auto-start a fresh chance to catch the bind.
        autoStartTries = 0
        updateUi()
        maybeAutoStartMcp()
        // A launch-time update check that failed (offline at the
        // time) gets another chance whenever the owner comes back.
        if (updateDownloadUrl.isEmpty()) checkForUpdate()
    }

    override fun onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(stateReceiver) } catch (e: Exception) { }
            receiverRegistered = false
        }
        super.onPause()
    }

    /**
     * Back sends the app to the background instead of finishing it.
     * The session stays connected while the owner goes back.
     */
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        // Nothing to stop here on purpose: the session belongs to the
        // foreground service and survives this screen going away. It
        // only stops when the owner taps Disconnect.
        super.onDestroy()
    }

    fun status(text: String) {
        // The message also stays on the home screen for a few seconds:
        // a toast alone vanishes before the owner reads it, and the
        // next state refresh used to wipe it at once.
        transientStatus = text
        transientUntil = System.currentTimeMillis() + 8000
        runOnUiThread {
            statusText.text = text
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The session, if the service is up. Null means no session. */
    private fun service(): KeepAliveService? = KeepAliveService.instance

    private fun sessionRunning(): Boolean = service()?.sessionRunning == true

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, KeepAliveService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startMcp() {
        sendServiceAction(KeepAliveService.ACTION_START_SESSION)
        updateUi()
    }

    private fun stopMcp() {
        sendServiceAction(KeepAliveService.ACTION_STOP_SESSION)
        updateUi()
    }

    /**
     * One status line and one button, the whole interface. The
     * button always does the next step, so there is nothing to
     * figure out: set up, turn on Accessibility, start, disconnect.
     * Once a target app is picked, two small buttons appear under
     * the main button: Continue to Chrome, to log in by hand in
     * the real browser, and Change app, to switch the target while
     * staying connected. Before a target is picked they stay
     * hidden, so the home screen stays minimal. The tunnel starts
     * by itself with the session, so there is no tunnel button:
     * the link appears on its own and Share connection sends it.
     */
    private fun updateUi() {
        val target = prefs.getString(DriveService.KEY_TARGET_LABEL, "") ?: ""
        val a11yOn = DriveService.instance != null
        val running = sessionRunning()
        when {
            target.isEmpty() -> {
                statusText.text = "Not set up"
                statusText.setTextColor(getColor(R.color.secondary))
                statusDot.setBackgroundResource(R.drawable.dot_amber)
                actionButton.text = "Set up"
            }
            !a11yOn -> {
                statusText.text = "$target · Accessibility off"
                statusText.setTextColor(getColor(R.color.secondary))
                statusDot.setBackgroundResource(R.drawable.dot_amber)
                actionButton.text = "Turn on Accessibility"
            }
            running -> {
                statusText.text = "$target · Running"
                statusText.setTextColor(getColor(R.color.accent))
                statusDot.setBackgroundResource(R.drawable.dot_green)
                actionButton.text = "Disconnect"
            }
            else -> {
                statusText.text = "$target · Ready"
                statusText.setTextColor(getColor(R.color.accent))
                statusDot.setBackgroundResource(R.drawable.dot_green)
                actionButton.text = "Start"
            }
        }
        // The two small buttons only exist once a target is picked.
        // Continue to Chrome is the next step after Running: log in
        // by hand. Change app switches the target while staying
        // connected. The Share connection button lives in
        // updateTunnelUi: it only shows once the link exists.
        val hasTarget = target.isNotEmpty()
        continueButton.visibility = if (hasTarget) View.VISIBLE else View.GONE
        changeButton.visibility = if (hasTarget) View.VISIBLE else View.GONE
        if (hasTarget) {
            continueButton.text = if (target == "Chrome") "Continue to Chrome" else "Open $target"
        }
        // A fresh message from status() stays on screen for a few
        // seconds instead of being wiped by the next state refresh.
        if (transientStatus != null && System.currentTimeMillis() < transientUntil) {
            statusText.text = transientStatus
        } else {
            transientStatus = null
        }
        updateTunnelUi()
    }

    /** The one button does whatever the next step is. */
    private fun doNextStep() {
        val target = prefs.getString(DriveService.KEY_TARGET_PACKAGE, "") ?: ""
        val a11yOn = DriveService.instance != null
        when {
            target.isEmpty() -> showAppPicker()
            !a11yOn -> openA11ySettings()
            sessionRunning() -> stopMcp()
            else -> startMcp()
        }
    }

    /**
     * One-time setup, saved: the target app pick and the Accessibility
     * switch already stay saved, so the only step the owner had to
     * repeat was turning MCP on. Now the app does that by itself when
     * it opens, but only when the setup is already done: a target app
     * is picked and the Accessibility service is on. Otherwise nothing
     * starts by itself and the owner sets it up by hand first.
     * The service may not be bound yet the moment the app opens, so
     * it tries a few times with a longer window instead of once.
     */
    private fun maybeAutoStartMcp() {
        if (sessionRunning()) return
        val target = prefs.getString(DriveService.KEY_TARGET_PACKAGE, "") ?: ""
        if (target.isEmpty()) return
        if (DriveService.instance != null) {
            autoStartTries = 0
            startMcp()
            return
        }
        if (autoStartTries >= 3 || autoStartScheduled) return
        autoStartTries++
        autoStartScheduled = true
        val delay = longArrayOf(1500L, 4000L, 8000L)[autoStartTries - 1]
        Handler(Looper.getMainLooper()).postDelayed({
            autoStartScheduled = false
            if (!sessionRunning()) maybeAutoStartMcp()
        }, delay)
    }

    // ---- Target app picker and Accessibility settings ----

    private fun isCryptoApp(pkg: String, label: String): Boolean {
        if (pkg in cryptoPackages) return true
        val lower = label.lowercase()
        return cryptoLabels.any { lower.contains(it) }
    }

    /**
     * The owner picks ONE app your agent may drive. The picker shows
     * centered rows of 3 apps, each with its official icon and name,
     * so the list is short, balanced and there is no scrolling. A
     * short last row is centered too, never left hanging. Only the
     * important apps are listed, Chrome first, and only those that
     * are actually installed. No crypto apps are listed at all, by
     * the owner's order. "Show all apps" at the end lists every
     * launchable app instead, still without the crypto ones, so the
     * owner can pick any other app too. If none of the five is
     * installed, every launchable app is shown instead so the picker
     * is never empty. Tap an app and it is picked at once, the picker
     * closes, no extra button to tap. Android 11 and newer hide most
     * installed apps from queryIntentActivities unless
     * QUERY_ALL_PACKAGES is declared in the manifest, so that
     * permission is declared there. The app itself is never listed,
     * it cannot drive itself. The choice is saved and can be changed
     * any time by picking again. Picking a new app while Running
     * switches the target at once, no restart: DriveService reads
     * the target from prefs on every action, so MCP keeps running
     * and the status line updates to the new app.
     */
    private fun showAppPicker(showAll: Boolean = false) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
        val important = listOf(
            "com.android.chrome",
            "org.telegram.messenger",
            "com.twitter.android",
            "com.instagram.android",
            "com.zhiliaoapp.musically"
        )
        val picked = important.mapNotNull { pkg ->
            installed.firstOrNull { it.activityInfo.packageName == pkg }
        }
        val apps = when {
            showAll -> installed
                .filter { !isCryptoApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            picked.isEmpty() -> installed.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            else -> picked
        }
        lateinit var dialog: AlertDialog
        fun cellView(position: Int): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val icon = ImageView(this).apply {
                // The official icon of the app, from the phone itself.
                setImageDrawable(apps[position].loadIcon(packageManager))
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            val label = TextView(this).apply {
                text = apps[position].loadLabel(packageManager).toString()
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            cell.addView(icon)
            cell.addView(label)
            cell.setOnClickListener {
                val info = apps[position]
                val pkg = info.activityInfo.packageName
                val labelText = info.loadLabel(packageManager).toString()
                prefs.edit()
                    .putString(DriveService.KEY_TARGET_PACKAGE, pkg)
                    .putString(DriveService.KEY_TARGET_LABEL, labelText)
                    .apply()
                updateUi()
                dialog.dismiss()
            }
            return cell
        }
        // Centered rows of 3, so a short last row sits in the middle.
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        for (start in apps.indices step 3) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (position in start until minOf(start + 3, apps.size)) {
                row.addView(cellView(position))
            }
            rows.addView(row)
        }
        if (!showAll) {
            val allButton = Button(this).apply {
                text = "Show all apps"
                textSize = 15f
                setBackgroundResource(R.drawable.rounded_button_outline)
                setTextColor(getColor(R.color.accent))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
                setOnClickListener {
                    dialog.dismiss()
                    showAppPicker(true)
                }
            }
            rows.addView(allButton)
        }
        val scroll = ScrollView(this).apply { addView(rows) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Pick an app")
            .setView(scroll)
            .create()
        dialog.show()
    }

    /** Open Accessibility settings so the owner can turn the service on by hand. */
    private fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "Turn on AgentPilot in Accessibility, then come back.",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---- Continue to Chrome, tunnel status, and guided help ----

    /**
     * Open the picked target app, one tap, so the owner can log in
     * by hand in the real browser, or check he is already logged in.
     * Nothing is typed for him, login is always by his own hands.
     * If no target is picked yet, open the picker instead.
     */
    private fun openTargetApp() {
        val pkg = prefs.getString(DriveService.KEY_TARGET_PACKAGE, "") ?: ""
        val label = prefs.getString(DriveService.KEY_TARGET_LABEL, "") ?: ""
        if (pkg.isEmpty()) {
            showAppPicker()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            status("Cannot open $label.")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * The tunnel status line and the Share connection button always
     * show the true state. The status line shows the public link
     * once it exists, or one short line while it is on its way.
     * Share connection only shows once the link exists, and it
     * sends the link and the key together. No raw tunnel output
     * on the home screen, and no tunnel button: the tunnel starts
     * by itself with the session.
     */
    private fun updateTunnelUi() {
        val svc = service()
        val url = svc?.tunnelUrl
        val installing = svc?.tunnelInstalling == true
        val running = svc?.tunnelRunning == true
        when {
            installing -> {
                tunnelStatus.visibility = View.VISIBLE
                tunnelStatus.text = "Checking tunnel…"
                shareButton.visibility = View.GONE
            }
            running -> {
                tunnelStatus.visibility = View.VISIBLE
                tunnelStatus.text = if (svc?.tunnelRestarting == true) {
                    "Reconnecting…"
                } else {
                    url ?: "Connecting…"
                }
                shareButton.visibility = if (url != null) View.VISIBLE else View.GONE
            }
            sessionRunning() -> {
                tunnelStatus.visibility = View.VISIBLE
                tunnelStatus.text = "Connecting…"
                shareButton.visibility = View.GONE
            }
            else -> {
                tunnelStatus.visibility = View.GONE
                shareButton.visibility = View.GONE
            }
        }
        // Copy sits next to Share and shows exactly when Share does:
        // both need a link, and Copy is the one that works when the
        // share sheet does not.
        copyButton.visibility = shareButton.visibility
    }

    /** Tap the tunnel status line to copy the public link. */
    private fun copyTunnelUrl() {
        val url = service()?.tunnelUrl ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AgentPilot tunnel", url))
        status("Tunnel link copied.")
    }

    /** Copy the connection key to the clipboard. */
    private fun copyToken() {
        val token = DriveService.storedToken(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AgentPilot key", token))
        status("Key copied.")
    }

    /** Copy the tunnel link and the key together, one tap, no share sheet. */
    private fun copyConnection() {
        val url = service()?.tunnelUrl ?: return
        val token = DriveService.storedToken(this)
        val text = "AgentPilot\nTunnel: $url\nKey: $token"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AgentPilot connection", text))
        status("Link and key copied.")
    }

    /** Share the tunnel link and the key together, one tap. */
    private fun shareConnection() {
        val url = service()?.tunnelUrl ?: return
        val token = DriveService.storedToken(this)
        val text = "AgentPilot\nTunnel: $url\nKey: $token"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share connection"))
    }

    /** Ask the local server for its tools. Connected or not, plainly. */
    private fun testConnection() {
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
            runOnUiThread { status(if (ok) "Connected." else "Not connected.") }
        }
    }

    /** Open the battery settings, so the owner can exempt the app. */
    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Switch the tunnel service. The tunnel restarts on the new one. */
    private fun switchTunnelBackend() {
        val current = prefs.getString(KeepAliveService.KEY_TUNNEL_BACKEND, "cloudflare") ?: "cloudflare"
        val next = if (current == "ngrok") "cloudflare" else "ngrok"
        prefs.edit().putString(KeepAliveService.KEY_TUNNEL_BACKEND, next).apply()
        status("Tunnel service: $next.")
        if (sessionRunning()) {
            sendServiceAction(KeepAliveService.ACTION_STOP_TUNNEL)
            sendServiceAction(KeepAliveService.ACTION_START_TUNNEL)
        }
    }

    /** Save the ngrok key once. The tunnel restarts on ngrok with it. */
    private fun saveNgrokKey(token: String) {
        if (token.isEmpty()) {
            status("Paste the key first.")
            return
        }
        prefs.edit().putString(KeepAliveService.KEY_NGROK_TOKEN, token).apply()
        status("ngrok key saved.")
        val backend = prefs.getString(KeepAliveService.KEY_TUNNEL_BACKEND, "cloudflare") ?: "cloudflare"
        if (backend == "ngrok" && sessionRunning()) {
            sendServiceAction(KeepAliveService.ACTION_STOP_TUNNEL)
            sendServiceAction(KeepAliveService.ACTION_START_TUNNEL)
        }
    }

    /** Floating window on or off. Needs the overlay permission once. */
    private fun toggleFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) {
            status("Allow 'Display over other apps', then tap Show again.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val on = !prefs.getBoolean(KeepAliveService.KEY_FLOATING_WINDOW, false)
        val svc = service()
        if (svc == null) {
            prefs.edit().putBoolean(KeepAliveService.KEY_FLOATING_WINDOW, on).apply()
            status(if (on) "Floating window will show when the app starts." else "Floating window off.")
            return
        }
        svc.setFloatingWindow(on)
        status(if (on) "Floating window on." else "Floating window off.")
    }

    /** A new connection key. The old one stops working at once. */
    private fun newTokenFlow() {
        DriveService.newToken(this)
        status("New key saved. Share it again.")
        if (sessionRunning()) {
            sendServiceAction(KeepAliveService.ACTION_RESTART_SERVER)
        }
    }

    /** The log of what the agent did, newest first. */
    private fun showActionLog() {
        val lines = service()?.actionLogSnapshot() ?: emptyList()
        val logView = TextView(this).apply {
            text = if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        AlertDialog.Builder(this)
            .setTitle("What the agent did")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Help is a guided flow, not a reading list. Every step is a row
     * with short text AND a button that DOES that step right there:
     * tap it and the help closes and the step opens. One tap, no dead
     * ends, no guessing what to tap next. Steps the owner cannot do
     * yet, like Start before a target is picked, show a disabled
     * button instead of a button that leads nowhere.
     */
    private fun showHelp() {
        val target = prefs.getString(DriveService.KEY_TARGET_LABEL, "") ?: ""
        val hasTarget = target.isNotEmpty()
        val a11yOn = DriveService.instance != null
        val running = sessionRunning()
        val svc = service()
        lateinit var dialog: AlertDialog
        fun helpRow(stepText: String, buttonText: String, enabled: Boolean, action: () -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(0), dp(10), dp(0), dp(10))
            }
            val stepLabel = TextView(this).apply {
                text = stepText
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val button = Button(this).apply {
                text = buttonText
                isEnabled = enabled
                textSize = 14f
                minimumHeight = dp(40)
                setBackgroundResource(R.drawable.rounded_button_outline)
                setTextColor(getColor(R.color.accent))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(12)
                }
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            row.addView(stepLabel)
            row.addView(button)
            return row
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        body.addView(helpRow("1. Pick an app for your agent to drive.", "Pick", true) {
            showAppPicker()
        })
        body.addView(helpRow("2. Turn on Accessibility for AgentPilot.", "Open Settings", true) {
            openA11ySettings()
        })
        body.addView(helpRow("3. Start the app.", "Start", hasTarget && a11yOn && !running) {
            startMcp()
        })
        val continueLabel = if (target == "Chrome") "Continue to Chrome" else "Open $target"
        body.addView(helpRow("4. Continue to Chrome and log in by hand.", continueLabel, hasTarget) {
            openTargetApp()
        })
        // The tunnel starts by itself with the session. There is
        // no tunnel button anymore: the link appears on the home
        // screen and Share connection sends it with the key.
        val tunnelNote = TextView(this).apply {
            text = "5. The tunnel starts by itself when the app starts. The link appears on the home screen."
            textSize = 15f
            setPadding(dp(0), dp(10), dp(0), dp(10))
        }
        body.addView(tunnelNote)
        // The tunnel's last message, so a failure says WHY instead
        // of only "Try again". The owner reads it and tells me.
        val tunnelLine = TextView(this).apply {
            text = "Tunnel: " + (svc?.tunnelLastLine?.takeIf { it.isNotEmpty() } ?: "no messages yet")
            textSize = 13f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(0), dp(2), dp(0), dp(10))
        }
        body.addView(tunnelLine)
        // The tunnel service: Cloudflare is free with no account,
        // ngrok is the alternative when Cloudflare does not start.
        val backend = prefs.getString(KeepAliveService.KEY_TUNNEL_BACKEND, "cloudflare") ?: "cloudflare"
        body.addView(helpRow("Tunnel service: $backend", "Switch", true) {
            switchTunnelBackend()
        })
        // The ngrok key: paste once, saved, never shown back.
        val hasNgrokKey = !(prefs.getString(KeepAliveService.KEY_NGROK_TOKEN, "") ?: "").isEmpty()
        val ngrokKeyInput = EditText(this).apply {
            hint = if (hasNgrokKey) "ngrok key saved, paste to replace" else "Paste ngrok key here"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ngrokKeySave = Button(this).apply {
            text = "Save key"
            textSize = 14f
            minimumHeight = dp(40)
            setBackgroundResource(R.drawable.rounded_button_outline)
            setTextColor(getColor(R.color.accent))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
            }
            setOnClickListener { saveNgrokKey(ngrokKeyInput.text.toString().trim()) }
        }
        val ngrokKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(0), dp(10), dp(0), dp(10))
            addView(ngrokKeyInput)
            addView(ngrokKeySave)
        }
        body.addView(ngrokKeyRow)
        // The floating window: a small status and link over other
        // apps, so the owner sees the connection while the agent
        // drives Chrome.
        val floatingOn = prefs.getBoolean(KeepAliveService.KEY_FLOATING_WINDOW, false)
        body.addView(helpRow("Floating window over other apps.", if (floatingOn) "Hide" else "Show", true) {
            toggleFloatingWindow()
        })
        // The connection key, shown in full, tap to copy. The agent
        // needs this key together with the tunnel link.
        val keyLabel = TextView(this).apply {
            text = "Connection key (tap to copy):"
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(0), dp(12), dp(0), dp(4))
        }
        body.addView(keyLabel)
        val tokenView = TextView(this).apply {
            text = DriveService.storedToken(this@MainActivity)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text))
            setPadding(dp(0), dp(0), dp(0), dp(4))
            setOnClickListener { copyToken() }
        }
        body.addView(tokenView)
        body.addView(helpRow("Share the connection (link + key).", "Share", svc?.tunnelUrl != null) {
            shareConnection()
        })
        body.addView(helpRow("See what the agent did.", "View", true) {
            showActionLog()
        })
        body.addView(helpRow("Test the connection.", "Test", running) {
            testConnection()
        })
        body.addView(helpRow("Keep the app running when the screen is off.", "Battery settings", true) {
            openBatterySettings()
        })
        body.addView(helpRow("New connection key. The old one stops working.", "New key", true) {
            newTokenFlow()
        })
        val versionView = TextView(this).apply {
            text = "AgentPilot " + currentVersionName()
            textSize = 13f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(0), dp(12), dp(0), dp(0))
        }
        body.addView(versionView)
        val done = TextView(this).apply {
            text = "Tap Disconnect on the home screen when you are done."
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(0), dp(4), dp(0), dp(4))
        }
        body.addView(done)
        val scroll = ScrollView(this).apply { addView(body) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Help")
            .setView(scroll)
            .create()
        dialog.show()
    }

    // ---- In-app update: check GitHub releases, download, install ----

    /**
     * Check the latest public release on GitHub, in a background thread.
     * If it is newer than this app, the update downloads by itself in the
     * background and the installer opens on its own, so the owner never
     * downloads an update by hand. The one tap Android still needs is the
     * system Install dialog: a sideloaded app cannot install silently.
     * If that install is dismissed, the Update button stays for a retry.
     * Fails silently when offline, never blocks the UI.
     */
    /**
     * Check GitHub for a newer release. When one is found the Update
     * button appears with the download link armed. A tap on the button
     * installs from the already downloaded APK when it is good, so the
     * owner never waits on a 21 MB download twice. A launch-time
     * (automatic) check fails silently, as before. A manual check,
     * from a tap on Update, always says what happened.
     */
    private fun checkForUpdate(manual: Boolean = false) {
        thread {
            try {
                val conn = URL("https://api.github.com/repos/ABlockBeat/AgentPilot/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "AgentPilot")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) {
                    if (manual) status("Could not check for updates. Check your connection and try again.")
                    return@thread
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name", "")
                val releaseVersion = tag.removePrefix("v").substringBefore("-")
                val current = currentVersionName()
                if (releaseVersion.isEmpty() || current.isEmpty()) {
                    if (manual) status("Could not check for updates. Check your connection and try again.")
                    return@thread
                }
                if (compareVersions(releaseVersion, current) <= 0) {
                    if (manual) status("You already have the latest version.")
                    return@thread
                }
                val assets = release.optJSONArray("assets")
                if (assets == null) {
                    if (manual) status("Could not check for updates. Check your connection and try again.")
                    return@thread
                }
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name") == "app-debug.apk") {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
                if (downloadUrl.isEmpty()) {
                    if (manual) status("Could not check for updates. Check your connection and try again.")
                    return@thread
                }
                updateDownloadUrl = downloadUrl
                updateVersion = releaseVersion
                runOnUiThread { updateButton.visibility = View.VISIBLE }
                // Already downloaded and verified for this version:
                // do not download 21 MB again, the tap installs it.
                if (cachedApkOk(releaseVersion)) return@thread
                downloadUpdate(downloadUrl, auto = true)
            } catch (e: Exception) {
                // Offline or any error: an automatic check stays
                // silent, as before. A manual check says so.
                if (manual) status("Could not check for updates. Check your connection and try again.")
            }
        }
    }

    /** Compare dotted versions numerically, segment by segment. */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    @Suppress("DEPRECATION")
    private fun currentVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** True when the cached APK is for this version and looks whole:
     * at least 10 MB and a real APK (ZIP magic "PK"). A download cut
     * short fails this instead of opening a broken installer. */
    private fun apkFileOk(f: File): Boolean {
        if (!f.exists() || f.length() < 10_000_000L) return false
        return try {
            FileInputStream(f).use { input ->
                val magic = ByteArray(2)
                input.read(magic) == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun cachedApkOk(version: String): Boolean {
        val cachedVersion = prefs.getString("updateCachedVersion", "") ?: ""
        if (cachedVersion != version) return false
        return apkFileOk(File(cacheDir, "update.apk"))
    }

    /** Download the release APK to the cache, then open the installer.
     * A tap never re-downloads a good cached APK: it installs it at
     * once. Auto downloads fail silently (the Update button stays for
     * a retry); a manual tap reports the failure in plain words. */
    private fun downloadUpdate(downloadUrl: String, auto: Boolean = false) {
        if (downloading) {
            if (!auto) status("Downloading update... please wait.")
            return
        }
        // A good cached APK for this version installs at once.
        if (updateVersion.isNotEmpty() && cachedApkOk(updateVersion)) {
            runOnUiThread { installApk(File(cacheDir, "update.apk")) }
            return
        }
        downloading = true
        updateButton.text = "Downloading..."
        thread {
            try {
                val apkFile = File(cacheDir, "update.apk")
                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "AgentPilot")
                if (conn.responseCode != 200) throw RuntimeException("Download failed")
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output -> input.copyTo(output) }
                }
                if (!apkFileOk(apkFile)) {
                    apkFile.delete()
                    throw RuntimeException("Download incomplete")
                }
                prefs.edit()
                    .putString("updateCachedVersion", updateVersion)
                    .putString("updateInstallerShown", updateVersion)
                    .apply()
                downloading = false
                runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                downloading = false
                runOnUiThread {
                    updateButton.text = "Update"
                    if (!auto) status("Update failed. Try again.")
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        updateButton.text = "Update"
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // The installer could not open (installs from this app
            // not allowed, or no handler): say so in plain words
            // instead of crashing.
            status("Could not open the installer. Allow installs from AgentPilot in Settings, then tap Update again.")
        }
    }
}
