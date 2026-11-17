package com.browsermcp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Browser MCP, Android app, version 0.4.0.
 *
 * Two engines in one app:
 * 1. A WebView browser the owner opens himself. Muse drives it through
 *    the built-in MCP server on 127.0.0.1 only, with a bearer token,
 *    when the owner turns MCP on in the app. The owner sees everything
 *    on this screen and can take over at any time with his own hands.
 *    The WebView is a fallback only: exchanges detect it, so it is
 *    not the primary for real logins.
 * 2. A drive engine, DriveService, an AccessibilityService that drives
 *    the REAL apps on the phone: real Chrome, KuCoin, Bitget, any app
 *    the owner picks. The owner picks one target app in this UI and
 *    turns the service on by hand in Settings, Accessibility. That is
 *    how Muse drives HIS browser on HIS phone: no cloud browser, no
 *    laptop, no extension.
 *
 * Browser must not think it is a coding agent:
 * - No automation flags. WebView has no --enable-automation to set.
 * - User agent is real Chrome, the wv token is removed, so the page sees
 *   a normal Chrome browser, not a WebView. Note: WebView is still
 *   detectable by other signals, X-Requested-With header and missing
 *   window.chrome, so it is a fallback only. Real Chrome, driven by the
 *   AccessibilityService, is primary for exchanges that check.
 * - Human timing: small random pause before every action, slow typing,
 *   scroll in steps. No bulk, no instant fills.
 * - WebView debugging is OFF until the owner turns MCP on, and only then,
 *   only for this app, only on this phone.
 * - No password is ever filled by the agent. Login is always by hand.
 */
class MainActivity : Activity() {

    lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private lateinit var targetAppLabel: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var tunnelUrlBar: EditText
    private lateinit var tunnelStatus: TextView
    private lateinit var prefs: SharedPreferences
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var mcpServer: McpServer? = null
    private var token: String = UUID.randomUUID().toString()
    var mcpOn: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(DriveService.PREFS, MODE_PRIVATE)
        webView = findViewById(R.id.webView)
        statusBar = findViewById(R.id.statusBar)
        targetAppLabel = findViewById(R.id.targetAppLabel)
        a11yStatus = findViewById(R.id.a11yStatus)
        tunnelUrlBar = findViewById(R.id.tunnelUrlBar)
        tunnelStatus = findViewById(R.id.tunnelStatus)
        val urlBar = findViewById<EditText>(R.id.urlBar)
        val goButton = findViewById<Button>(R.id.goButton)
        val mcpButton = findViewById<Button>(R.id.mcpButton)
        val pickTargetButton = findViewById<Button>(R.id.pickTargetButton)
        val a11yButton = findViewById<Button>(R.id.a11yButton)
        val tunnelSaveButton = findViewById<Button>(R.id.tunnelSaveButton)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        // Real Chrome user agent, wv token removed. The page sees Chrome.
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(" Version/4.0", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                urlBar.setText(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(intent, "Choose a file"), 1)
                return true
            }
        }
        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            // Download goes to the app downloads folder, owner named link.
            thread {
                try {
                    val name = Uri.parse(url).lastPathSegment ?: "download"
                    val out = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
                    java.net.URL(url).openStream().use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    runOnUiThread { status("Saved: " + out.absolutePath) }
                } catch (e: Exception) {
                    runOnUiThread { status("Download failed: " + e.message) }
                }
            }
        })

        goButton.setOnClickListener {
            var url = urlBar.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                navigate(url)
            }
        }

        mcpButton.setOnClickListener {
            if (mcpOn) stopMcp() else startMcp()
            mcpButton.text = if (mcpOn) "MCP on" else "MCP off"
        }

        pickTargetButton.setOnClickListener { showAppPicker() }

        a11yButton.setOnClickListener { openA11ySettings() }

        tunnelSaveButton.setOnClickListener {
            val url = tunnelUrlBar.text.toString().trim()
            prefs.edit().putString(KEY_TUNNEL_URL, url).apply()
            updateDriveStatus()
            status("Tunnel URL saved: $url")
        }

        updateDriveStatus()
        webView.loadUrl("https://example.com")
    }

    override fun onResume() {
        super.onResume()
        // The owner may have toggled the service in Settings. Refresh.
        updateDriveStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            val uris = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            fileChooser?.onReceiveValue(uris)
            fileChooser = null
        }
    }

    fun status(text: String) {
        runOnUiThread { statusBar.text = text }
    }

    private fun startMcp() {
        WebView.setWebContentsDebuggingEnabled(true)
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
        status("MCP on. Local only: http://127.0.0.1:8765/mcp  Token: $token")
        Toast.makeText(this, "MCP on. Token shown in status.", Toast.LENGTH_LONG).show()
    }

    private fun stopMcp() {
        mcpServer?.stop()
        mcpServer = null
        WebView.setWebContentsDebuggingEnabled(false)
        mcpOn = false
        stopService(Intent(this, KeepAliveService::class.java))
        status("MCP off.")
    }

    fun regenerateToken(): String {
        token = UUID.randomUUID().toString()
        return token
    }

    fun currentToken(): String = token

    // ---- Target app picker, Accessibility settings, tunnel URL ----

    /**
     * The owner picks ONE app Muse may drive. Only launchable apps are
     * listed, so no special package permission is needed. The choice is
     * saved and can be changed any time by picking again.
     */
    private fun showAppPicker() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pick the app Muse may drive")
            .setItems(labels) { _, which ->
                val info = apps[which]
                val pkg = info.activityInfo.packageName
                prefs.edit()
                    .putString(DriveService.KEY_TARGET_PACKAGE, pkg)
                    .putString(DriveService.KEY_TARGET_LABEL, labels[which])
                    .apply()
                updateDriveStatus()
                status("Target app: " + labels[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Open Accessibility settings so the owner can turn the service on by hand. */
    private fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "Turn on Browser MCP in Accessibility, then come back.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateDriveStatus() {
        val target = prefs.getString(DriveService.KEY_TARGET_LABEL, "") ?: ""
        val pkg = prefs.getString(DriveService.KEY_TARGET_PACKAGE, "") ?: ""
        targetAppLabel.text = if (target.isEmpty()) {
            "None picked"
        } else {
            "$target ($pkg)"
        }
        val on = DriveService.instance != null
        a11yStatus.text = if (on) {
            "Accessibility: ON. Driving: " + if (target.isEmpty()) {
                "no app picked yet."
            } else {
                "$target."
            }
        } else {
            "Accessibility: OFF. Tap Accessibility to turn it on in Settings."
        }
        val url = prefs.getString(KEY_TUNNEL_URL, "") ?: ""
        tunnelStatus.text = if (url.isEmpty()) {
            "Tunnel: not set up yet. MCP is local only (127.0.0.1:8765)."
        } else {
            "Tunnel URL: $url"
        }
        if (tunnelUrlBar.text.toString().isEmpty() && url.isNotEmpty()) {
            tunnelUrlBar.setText(url)
        }
    }

    // ---- Human like helpers ----

    fun pause() {
        Thread.sleep(Random.nextLong(200, 700))
    }

    /** Run JavaScript on the UI thread and wait for its string result. */
    fun eval(js: String): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference<String>("")
        runOnUiThread {
            webView.evaluateJavascript(js) { value ->
                out.set(value ?: "")
                latch.countDown()
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        return out.get()
    }

    /** Screenshot the WebView to a file, returns the path. */
    fun screenshot(): String {
        pause()
        val latch = CountDownLatch(1)
        val path = AtomicReference<String>("")
        runOnUiThread {
            try {
                val bitmap = Bitmap.createBitmap(
                    webView.width, webView.height, Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                webView.draw(canvas)
                val file = File(getExternalFilesDir(null), "latest.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                path.set(file.absolutePath)
            } catch (e: Exception) {
                path.set("ERROR: " + e.message)
            }
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        return path.get()
    }

    // ---- The 12 WebView tools, same as the desktop MCP server ----

    fun navigate(url: String): Map<String, String> {
        pause()
        runOnUiThread { webView.loadUrl(url) }
        Thread.sleep(1500)
        return mapOf("url" to url, "title" to (webView.title ?: ""))
    }

    fun getPage(): Map<String, String> {
        pause()
        val text = eval("(function(){return document.title + '\\n' + document.body.innerText.slice(0,4000)})()")
        return mapOf("url" to (webView.url ?: ""), "title" to (webView.title ?: ""), "text" to text)
    }

    fun clickText(text: String): Map<String, String> {
        pause()
        val js = "(function(){var els=document.querySelectorAll('a,button,[role=button],input[type=submit]');for(var i=0;i<els.length;i++){if(els[i].innerText && els[i].innerText.trim().indexOf(" + json(text) + ")>=0){els[i].click();return 'clicked';}}return 'not found';})()"
        val result = eval(js)
        if (result.contains("not found")) throw RuntimeException("No clickable element with text: $text")
        Thread.sleep(1000)
        return mapOf("url" to (webView.url ?: ""), "title" to (webView.title ?: ""))
    }

    fun typeText(field: String, text: String): Map<String, String> {
        pause()
        val js = "(function(){var f=document.querySelector('input[placeholder*=" + json(field) + "],textarea[placeholder*=" + json(field) + "],input[name*=" + json(field) + "]');if(!f)return 'not found';if(f.type==='password')return 'password';f.focus();return 'ok';})()"
        val result = eval(js)
        if (result.contains("not found")) throw RuntimeException("No field matching: $field")
        if (result.contains("password")) throw RuntimeException("Password field refused, login by hand")
        // Slow typing, per character, human timing. Each character is
        // appended in the page with an input event, so the page sees real
        // typing, not one instant fill.
        for (ch in text) {
            val js = "(function(){var f=document.querySelector('input[placeholder*=" + json(field) + "],textarea[placeholder*=" + json(field) + "],input[name*=" + json(field) + "]');if(!f)return 'gone';var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;setter.call(f,f.value+" + json(ch.toString()) + ");f.dispatchEvent(new Event('input',{bubbles:true}));return 'ok';})()"
            eval(js)
            Thread.sleep(Random.nextLong(40, 120))
        }
        eval("(function(){var f=document.querySelector('input[placeholder*=" + json(field) + "],textarea[placeholder*=" + json(field) + "],input[name*=" + json(field) + "]');if(f)f.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})()")
        return mapOf("ok" to "true")
    }

    fun scroll(direction: String, steps: Int): Map<String, String> {
        pause()
        val dy = if (direction == "up") -400 else 400
        for (i in 1..steps) {
            runOnUiThread { webView.scrollBy(0, dy) }
            Thread.sleep(Random.nextLong(150, 400))
        }
        return mapOf("ok" to "true")
    }

    fun waitFor(marker: String, timeoutSeconds: Int): Map<String, Any> {
        val end = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < end) {
            val text = eval("(function(){return document.body.innerText})()")
            if (text.contains(marker)) return mapOf("logged_in" to true, "marker" to marker)
            Thread.sleep(2000)
        }
        return mapOf("logged_in" to false, "marker" to marker)
    }

    fun uploadFile(field: String, filePath: String): Map<String, String> {
        val lower = filePath.lowercase()
        if (lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".env") ||
            lower.contains("credential")
        ) throw RuntimeException("Credential file refused")
        val file = File(filePath)
        if (!file.exists()) throw RuntimeException("File not found: $filePath")
        pause()
        val js = "(function(){var f=document.querySelector('input[type=file]');if(!f)return 'not found';f.click();return 'ok';})()"
        val result = eval(js)
        if (result.contains("not found")) throw RuntimeException("No file input on this page")
        // The owner picks the file in the chooser by hand. Agent never sees it.
        return mapOf("ok" to "true", "file" to file.name)
    }

    fun downloadByText(text: String): Map<String, String> {
        pause()
        val js = "(function(){var els=document.querySelectorAll('a');for(var i=0;i<els.length;i++){if(els[i].innerText && els[i].innerText.trim().indexOf(" + json(text) + ")>=0){els[i].click();return 'clicked';}}return 'not found';})()"
        val result = eval(js)
        if (result.contains("not found")) throw RuntimeException("No link with text: $text")
        Thread.sleep(2000)
        return mapOf("ok" to "true", "text" to text)
    }

    fun checkHuman(): Map<String, Any> {
        pause()
        val result = eval("(function(){return JSON.stringify({webdriver: navigator.webdriver, chrome: !!window.chrome, ua: navigator.userAgent, headless: navigator.userAgent.indexOf('Headless')>=0})})()")
        val looksHuman = result.contains("\"webdriver\":false") &&
            result.contains("\"chrome\":true") &&
            !result.contains("\"headless\":true")
        return mapOf("raw" to result, "looks_human" to looksHuman)
    }

    // ---- The 12 drive tools, real apps via DriveService ----

    private fun drive(): DriveService =
        DriveService.instance
            ?: throw RuntimeException("Accessibility service is off. Turn it on in Settings, Accessibility, Browser MCP.")

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

    private fun json(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    override fun onDestroy() {
        stopMcp()
        super.onDestroy()
    }

    companion object {
        const val KEY_TUNNEL_URL = "tunnel_url"
    }
}
