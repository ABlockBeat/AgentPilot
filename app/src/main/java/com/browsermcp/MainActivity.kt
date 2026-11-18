package com.browsermcp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
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
 * AgentPilot, Android app, version 0.4.4.
 *
 * One engine only: DriveService, an AccessibilityService that drives
 * the REAL apps on the phone, the ones the owner already uses and is
 * already logged into: real Chrome, KuCoin, Bitget, any app the owner
 * picks. That is how your agent drives your browser on your phone: no
 * cloud browser, no laptop, no extension.
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

    private lateinit var appStatus: TextView
    private lateinit var mcpStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var mcpButton: Button
    private lateinit var updateButton: Button
    private var updateDownloadUrl: String = ""
    private lateinit var prefs: SharedPreferences
    private var mcpServer: McpServer? = null
    private var token: String = UUID.randomUUID().toString()
    var mcpOn: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(DriveService.PREFS, MODE_PRIVATE)
        appStatus = findViewById(R.id.appStatus)
        mcpStatus = findViewById(R.id.mcpStatus)
        a11yStatus = findViewById(R.id.a11yStatus)
        mcpButton = findViewById(R.id.mcpButton)
        updateButton = findViewById(R.id.updateButton)
        val pickTargetButton = findViewById<Button>(R.id.pickTargetButton)
        val a11yButton = findViewById<Button>(R.id.a11yButton)

        mcpButton.setOnClickListener {
            if (mcpOn) stopMcp() else startMcp()
            updateMcpUi()
        }

        pickTargetButton.setOnClickListener { showAppPicker() }

        a11yButton.setOnClickListener { openA11ySettings() }

        updateButton.setOnClickListener {
            if (updateDownloadUrl.isNotEmpty()) downloadUpdate(updateDownloadUrl)
        }

        updateDriveStatus()
        updateMcpUi()
        maybeAutoStartMcp()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // The owner may have toggled the service in Settings. Refresh.
        updateDriveStatus()
        updateMcpUi()
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
        updateMcpUi()
        status("MCP on")
    }

    private fun stopMcp() {
        mcpServer?.stop()
        mcpServer = null
        mcpOn = false
        stopService(Intent(this, KeepAliveService::class.java))
        updateMcpUi()
        status("MCP off")
    }

    /** One place for the MCP button and status line, so they never disagree. */
    private fun updateMcpUi() {
        if (mcpOn) {
            mcpStatus.text = "MCP: ON"
            mcpStatus.setTextColor(getColor(R.color.accent))
            mcpButton.text = "MCP on"
            mcpButton.setBackgroundResource(R.drawable.rounded_button)
            mcpButton.setTextColor(getColor(R.color.white))
        } else {
            mcpStatus.text = "MCP: OFF"
            mcpStatus.setTextColor(getColor(R.color.secondary))
            mcpButton.text = "MCP off"
            mcpButton.setBackgroundResource(R.drawable.rounded_button_outline)
            mcpButton.setTextColor(getColor(R.color.accent))
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
                    updateMcpUi()
                }
            }, 1500)
            return
        }
        startMcp()
        updateMcpUi()
    }

    fun regenerateToken(): String {
        token = UUID.randomUUID().toString()
        return token
    }

    fun currentToken(): String = token

    // ---- Target app picker and Accessibility settings ----

    /**
     * The owner picks ONE app your agent may drive. The picker is a
     * grid, 3 apps in a row, each with its official icon and name, so
     * the list is short and there is little scrolling. Only the
     * important apps are listed, Chrome first, and only those that
     * are actually installed. No crypto apps are listed at all, by
     * the owner's order. If none of them is installed, every
     * launchable app is shown instead so the picker is never empty.
     * Tap an app to select it, it gets highlighted, then tap SELECT
     * to confirm. SELECT stays off until an app is chosen. CANCEL
     * only closes the picker without changing anything.
     * Android 11 and newer hide most installed apps from
     * queryIntentActivities unless QUERY_ALL_PACKAGES is declared in
     * the manifest, so that permission is declared there. The app
     * itself is never listed, it cannot drive itself. The choice is
     * saved and can be changed any time by picking again.
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
        var selected = -1
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(16), dp(16), dp(16), dp(16))
            clipToPadding = false
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }
                // The chosen app is highlighted with a teal border.
                cell.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    if (position == selected) {
                        setColor(0xFFE0F2F1.toInt())
                        setStroke(dp(2), Color.parseColor("#0F766E"))
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                val icon = ImageView(this@MainActivity).apply {
                    // The official icon of the app, from the phone itself.
                    setImageDrawable(apps[position].loadIcon(packageManager))
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                }
                val label = TextView(this@MainActivity).apply {
                    text = apps[position].loadLabel(packageManager).toString()
                    gravity = Gravity.CENTER
                    textSize = 13f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                cell.addView(icon)
                cell.addView(label)
                return cell
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pick the app your agent may drive")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Select") { _, _ ->
                if (selected >= 0) {
                    val info = apps[selected]
                    val pkg = info.activityInfo.packageName
                    val label = info.loadLabel(packageManager).toString()
                    prefs.edit()
                        .putString(DriveService.KEY_TARGET_PACKAGE, pkg)
                        .putString(DriveService.KEY_TARGET_LABEL, label)
                        .apply()
                    updateDriveStatus()
                    status("Target app: $label")
                }
            }
            .create()
        grid.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, which, _ ->
                selected = which
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        dialog.show()
        // SELECT stays off until an app is chosen.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
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

    private fun updateDriveStatus() {
        val target = prefs.getString(DriveService.KEY_TARGET_LABEL, "") ?: ""
        appStatus.text = if (target.isEmpty()) {
            "App: not picked"
        } else {
            "App: $target"
        }
        val on = DriveService.instance != null
        if (on) {
            a11yStatus.text = "Accessibility: ON"
            a11yStatus.setTextColor(getColor(R.color.accent))
        } else {
            a11yStatus.text = "Accessibility: OFF"
            a11yStatus.setTextColor(getColor(R.color.secondary))
        }
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
                val conn = URL("https://api.github.com/repos/ranabdullah1065-blip/browser-mcp/releases/latest").openConnection() as HttpURLConnection
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
