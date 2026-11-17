package com.browsermcp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
 * Browser MCP, Android app, version 0.3.0.
 *
 * A WebView browser the owner opens himself. Muse drives it through the
 * built-in MCP server on 127.0.0.1 only, with a bearer token, when the
 * owner turns MCP on in the app. The owner sees everything on this screen
 * and can take over at any time with his own hands.
 *
 * Browser must not think it is a coding agent:
 * - No automation flags. WebView has no --enable-automation to set.
 * - User agent is real Chrome, the wv token is removed, so the page sees
 *   a normal Chrome browser, not a WebView. Note: WebView is still
 *   detectable by other signals, X-Requested-With header and missing
 *   window.chrome, so it is a fallback only. Real Chrome, attached to,
 *   is primary for exchanges that check.
 * - Human timing: small random pause before every action, slow typing,
 *   scroll in steps. No bulk, no instant fills.
 * - WebView debugging is OFF until the owner turns MCP on, and only then,
 *   only for this app, only on this phone.
 * - No password is ever filled by the agent. Login is always by hand.
 */
class MainActivity : Activity() {

    lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var mcpServer: McpServer? = null
    private var token: String = UUID.randomUUID().toString()
    var mcpOn: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        statusBar = findViewById(R.id.statusBar)
        val urlBar = findViewById<EditText>(R.id.urlBar)
        val goButton = findViewById<Button>(R.id.goButton)
        val mcpButton = findViewById<Button>(R.id.mcpButton)

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

        webView.loadUrl("https://example.com")
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
        status("MCP on. Local only: http://127.0.0.1:8765/mcp  Token: $token")
        Toast.makeText(this, "MCP on. Token shown in status.", Toast.LENGTH_LONG).show()
    }

    private fun stopMcp() {
        mcpServer?.stop()
        mcpServer = null
        WebView.setWebContentsDebuggingEnabled(false)
        mcpOn = false
        status("MCP off.")
    }

    fun regenerateToken(): String {
        token = UUID.randomUUID().toString()
        return token
    }

    fun currentToken(): String = token

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

    // ---- The 12 tools, same as the desktop MCP server ----

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

    private fun json(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    override fun onDestroy() {
        stopMcp()
        super.onDestroy()
    }
}
