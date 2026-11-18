package com.browsermcp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * AgentPilot, Android app, version 0.5.0.
 *
 * Minimal interface, one rule only: one status line and one
 * button. Nothing else on the screen. The button always does
 * the next step: set up, turn on Accessibility, start, stop.
 *
 * Once a target app is picked, two small buttons appear under
 * the main button, and only then: Continue to Chrome, so the
 * owner can log in by hand in the real browser, and Tunnel, so
 * the owner's agent can reach this phone. A small Help button
 * sits at the bottom. Help is a guided flow, not a reading list:
 * every step is a row with a button that does that step right
 * there, one tap, no dead ends.
 *
 * One engine only: DriveService, an AccessibilityService that drives
 * the REAL apps on the phone, the ones the owner already uses and is
 * already logged into: real Chrome, KuCoin, Bitget, any app the owner
 * picks. That is how your agent drives your browser on your phone: no
 * cloud browser, no laptop, no extension.
 *
 * The tunnel lives inside the app, in TunnelManager. No Termux, no
 * separate download, no Play Store, no outside app at any point.
 * One tap on Install Tunnel downloads the cloudflared binary, about
 * 38 MB, one time only, installs it and verifies it runs. Start
 * Tunnel then opens the public link to the MCP server on this phone.
 * The tunnel only runs while the app is Running, and only when the
 * owner taps Start.
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
 *   the 12 drive tools. The owner sees everything on this screen and
 *   can take over at any time with his own hands.
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
    private lateinit var tunnelButton: Button
    private lateinit var tunnelStatus: TextView
    private lateinit var helpButton: Button
    private var updateDownloadUrl: String = ""
    private lateinit var prefs: SharedPreferences
    private var mcpServer: McpServer? = null
    private var token: String = UUID.randomUUID().toString()
    private val tunnelManager: TunnelManager by lazy { TunnelManager(this) }
    private var installProgress: Int = 0
    private var lastTunnelLog: String = ""
    var mcpOn: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(DriveService.PREFS, MODE_PRIVATE)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        updateButton = findViewById(R.id.updateButton)
        continueButton = findViewById(R.id.continueButton)
        tunnelButton = findViewById(R.id.tunnelButton)
        tunnelStatus = findViewById(R.id.tunnelStatus)
        helpButton = findViewById(R.id.helpButton)

        actionButton.setOnClickListener { doNextStep() }
        continueButton.setOnClickListener { openTargetApp() }
        tunnelButton.setOnClickListener { onTunnelButton() }
        tunnelStatus.setOnClickListener { copyTunnelUrl() }
        helpButton.setOnClickListener { showHelp() }

        updateButton.setOnClickListener {
            if (updateDownloadUrl.isNotEmpty()) downloadUpdate(updateDownloadUrl)
        }

        updateUi()
        maybeAutoStartMcp()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // The owner may have toggled the service in Settings. Refresh.
        updateUi()
    }

    fun status(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun startMcp() {
        mcpServer = McpServer(this, token).also { it.start() }
        mcpOn = true
        // Keep the process alive while MCP is on, so the server keeps
        // answering while the owner uses the target app in front.
        val keepAlive = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(keepAlive)
        } else {
            startService(keepAlive)
        }
        updateUi()
    }

    private fun stopMcp() {
        tunnelManager.stop()
        lastTunnelLog = ""
        mcpServer?.stop()
        mcpServer = null
        mcpOn = false
        stopService(Intent(this, KeepAliveService::class.java))
        updateUi()
    }

    /**
     * One status line and one button, the whole interface. The
     * button always does the next step, so there is nothing to
     * figure out: set up, turn on Accessibility, start, stop.
     * Once a target app is picked, two small buttons appear under
     * the main button: Continue to Chrome, to log in by hand in
     * the real browser, and Tunnel, to connect the agent to this
     * phone. Before a target is picked they stay hidden, so the
     * home screen stays minimal.
     */
    private fun updateUi() {
        val target = prefs.getString(DriveService.KEY_TARGET_LABEL, "") ?: ""
        val a11yOn = DriveService.instance != null
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
            mcpOn -> {
                statusText.text = "$target · Running"
                statusText.setTextColor(getColor(R.color.accent))
                statusDot.setBackgroundResource(R.drawable.dot_green)
                actionButton.text = "Stop"
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
        // by hand. Then Tunnel connects the agent to this phone.
        val hasTarget = target.isNotEmpty()
        continueButton.visibility = if (hasTarget) View.VISIBLE else View.GONE
        tunnelButton.visibility = if (hasTarget) View.VISIBLE else View.GONE
        if (hasTarget) {
            continueButton.text = if (target == "Chrome") "Continue to Chrome" else "Open $target"
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
            mcpOn -> stopMcp()
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
     * if it is not there, try once more after a short delay.
     */
    private fun maybeAutoStartMcp() {
        if (mcpOn) return
        val target = prefs.getString(DriveService.KEY_TARGET_PACKAGE, "") ?: ""
        if (target.isEmpty()) return
        if (DriveService.instance == null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!mcpOn && DriveService.instance != null) {
                    startMcp()
                    updateUi()
                }
            }, 1500)
            return
        }
        startMcp()
        updateUi()
    }

    fun regenerateToken(): String {
        token = UUID.randomUUID().toString()
        return token
    }

    fun currentToken(): String = token

    // ---- Target app picker and Accessibility settings ----

    /**
     * The owner picks ONE app your agent may drive. The picker shows
     * centered rows of 3 apps, each with its official icon and name,
     * so the list is short, balanced and there is no scrolling. A
     * short last row is centered too, never left hanging. Only the
     * important apps are listed, Chrome first, and only those that
     * are actually installed. No crypto apps are listed at all, by
     * the owner's order. If none of them is installed, every
     * launchable app is shown instead so the picker is never empty.
     * Tap an app and it is picked at once, the picker closes, no
     * extra button to tap. Android 11 and newer hide most installed
     * apps from queryIntentActivities unless QUERY_ALL_PACKAGES is
     * declared in the manifest, so that permission is declared
     * there. The app itself is never listed, it cannot drive
     * itself. The choice is saved and can be changed any time by
     * picking again.
     */
    private fun showAppPicker() {
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
        val apps = if (picked.isEmpty()) {
            installed.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        } else {
            picked
        }
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
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

    // ---- Continue to Chrome, tunnel, and guided help ----

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
     * The Tunnel button does whatever the tunnel needs next, one tap:
     * install it, start it, or stop it. It only works while the app
     * is Running, because the MCP server must be listening on 8765
     * first. Otherwise a short toast says to tap Start first.
     */
    private fun onTunnelButton() {
        when {
            tunnelManager.running -> {
                tunnelManager.stop()
                lastTunnelLog = ""
                updateTunnelUi()
                status("Tunnel stopped.")
            }
            tunnelManager.installing -> {
                // Button is disabled while installing, nothing to do.
            }
            !mcpOn -> status("Tap Start first, then tap Tunnel.")
            !tunnelManager.installed -> installTunnel()
            else -> startTunnel()
        }
    }

    /**
     * One tap installs the tunnel: download, save, make executable,
     * verify it runs. Everything happens inside the app. When the
     * install finishes, the tunnel starts by itself, so the owner
     * does not have to tap again.
     */
    private fun installTunnel() {
        installProgress = 0
        updateTunnelUi()
        tunnelManager.install(
            onProgress = { pct ->
                installProgress = pct
                updateTunnelUi()
            },
            onDone = {
                installProgress = 100
                status("Tunnel installed. Starting it now.")
                updateTunnelUi()
                startTunnel()
            },
            onError = { msg ->
                status("Tunnel install failed: $msg")
                updateTunnelUi()
            }
        )
    }

    /** Start the tunnel to the local MCP server, if the app is Running. */
    private fun startTunnel() {
        if (!mcpOn) {
            status("Tap Start first, then tap Tunnel.")
            return
        }
        updateTunnelUi()
        tunnelManager.start(
            onUrl = {
                lastTunnelLog = ""
                status("Tunnel is on. Tap the link to copy it.")
                updateTunnelUi()
            },
            onLog = { line ->
                lastTunnelLog = line
                updateTunnelUi()
            },
            onStopped = { updateTunnelUi() },
            onError = { msg ->
                status("Tunnel failed: $msg")
                updateTunnelUi()
            }
        )
    }

    /**
     * The Tunnel button label and the small tunnel status line always
     * show the true state: installing with percent, running with the
     * public link and the last tunnel output line, or idle. The
     * status line is a tiny terminal-style view, two lines at most,
     * visible only while the tunnel is installing or running.
     */
    private fun updateTunnelUi() {
        when {
            tunnelManager.installing -> {
                tunnelButton.isEnabled = false
                tunnelButton.text = "Installing… $installProgress%"
                tunnelStatus.visibility = View.VISIBLE
                tunnelStatus.text = "Installing tunnel… $installProgress%"
            }
            tunnelManager.running -> {
                tunnelButton.isEnabled = true
                tunnelButton.text = "Stop Tunnel"
                tunnelStatus.visibility = View.VISIBLE
                val url = tunnelManager.publicUrl
                tunnelStatus.text = if (url != null) {
                    if (lastTunnelLog.isNotEmpty()) "$url\n$lastTunnelLog" else url
                } else {
                    "Starting tunnel…"
                }
            }
            else -> {
                tunnelButton.isEnabled = true
                tunnelButton.text = if (tunnelManager.installed) {
                    "Start Tunnel"
                } else {
                    "Install Tunnel (~38 MB, once)"
                }
                tunnelStatus.visibility = View.GONE
            }
        }
    }

    /** Tap the tunnel status line to copy the public link. */
    private fun copyTunnelUrl() {
        val url = tunnelManager.publicUrl ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AgentPilot tunnel", url))
        status("Tunnel link copied.")
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
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        lateinit var dialog: AlertDialog
        fun helpRow(stepText: String, buttonText: String, enabled: Boolean, action: () -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(0), dp(10), dp(0), dp(10))
            }
            val text = TextView(this).apply {
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
            row.addView(text)
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
        body.addView(helpRow("3. Start the app.", "Start", hasTarget && a11yOn && !mcpOn) {
            startMcp()
        })
        val continueLabel = if (target == "Chrome") "Continue to Chrome" else "Open $target"
        body.addView(helpRow("4. Continue to Chrome and log in by hand.", continueLabel, hasTarget) {
            openTargetApp()
        })
        val tunnelLabel = when {
            tunnelManager.running -> "Stop Tunnel"
            tunnelManager.installing -> "Installing…"
            tunnelManager.installed -> "Start Tunnel"
            else -> "Install Tunnel"
        }
        body.addView(helpRow("5. Tunnel: connect your agent to this phone. One-time download, about 38 MB.", tunnelLabel, !tunnelManager.installing) {
            onTunnelButton()
        })
        val done = TextView(this).apply {
            text = "Tap Stop on the home screen when you are done."
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(0), dp(12), dp(0), dp(4))
        }
        body.addView(done)
        val scroll = ScrollView(this).apply { addView(body) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Help")
            .setView(scroll)
            .create()
        dialog.show()
    }

    // ---- The 12 drive tools, real apps via DriveService ----

    private fun drive(): DriveService =
        DriveService.instance
            ?: throw RuntimeException("Accessibility service is off. Turn it on in Settings, Accessibility, AgentPilot.")

    fun driveTapText(text: String): Map<String, Any> = drive().tapText(text)

    fun driveTapCoords(x: Int, y: Int): Map<String, Any> = drive().tapCoords(x, y)

    fun driveTypeText(field: String, text: String): Map<String, Any> = drive().typeText(field, text)

    fun driveScroll(direction: String, steps: Int): Map<String, Any> = drive().scroll(direction, steps)

    fun driveSwipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Map<String, Any> =
        drive().swipe(x1, y1, x2, y2, ms)

    fun driveScreenshot(): String = drive().screenshot()

    fun driveScreenText(): Map<String, Any> = drive().screenText()

    fun driveOpenApp(packageName: String): Map<String, Any> = drive().openApp(packageName)

    fun driveOpenUrl(url: String): Map<String, Any> = drive().openUrl(url)

    fun drivePressBack(): Map<String, Any> = drive().pressBack()

    fun drivePressHome(): Map<String, Any> = drive().pressHome()

    fun driveWaitForOwner(marker: String, timeoutSeconds: Int): Map<String, Any> =
        drive().waitForOwner(marker, timeoutSeconds)

    // ---- In-app update: check GitHub releases, download, install ----

    /**
     * Check the latest public release on GitHub, in a background thread.
     * If it is newer than this app, show the Update button. Fails
     * silently when offline, never blocks the UI, never auto-downloads.
     */
    private fun checkForUpdate() {
        thread {
            try {
                val conn = URL("https://api.github.com/repos/ranabdullah1065-blip/AgentPilot/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "AgentPilot")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name", "")
                val releaseVersion = tag.removePrefix("v").substringBefore("-")
                val current = currentVersionName()
                if (releaseVersion.isEmpty() || current.isEmpty()) return@thread
                if (compareVersions(releaseVersion, current) <= 0) return@thread
                val assets = release.optJSONArray("assets") ?: return@thread
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name") == "app-debug.apk") {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
                if (downloadUrl.isEmpty()) return@thread
                updateDownloadUrl = downloadUrl
                runOnUiThread { updateButton.visibility = View.VISIBLE }
            } catch (e: Exception) {
                // Offline or any error: no Update button, nothing shown.
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

    /** Download the release APK to the cache, then open the installer. */
    private fun downloadUpdate(downloadUrl: String) {
        updateButton.isEnabled = false
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
                runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Update"
                    status("Update failed: " + (e.message ?: "error"))
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        updateButton.isEnabled = true
        updateButton.text = "Update"
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        stopMcp()
        super.onDestroy()
    }
}
