package com.browsermcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Browser MCP, drive engine, version 0.4.0.
 *
 * An AccessibilityService that drives the REAL apps on the phone, the
 * ones the owner already uses and is already logged into: real Chrome,
 * KuCoin, Bitget, any app the owner picks. This is what makes the app
 * drive HIS browser, not a cloud browser, not a laptop, not an
 * extension, and not a WebView.
 *
 * How it works:
 * - The owner picks ONE target app in the app UI. The service drives
 *   only that package, plus system UI for Back and Home. Every other
 *   package is refused with a clear error.
 * - The owner turns the service on by hand in Settings, Accessibility.
 *   He can turn it off any time. Nothing here runs without that switch.
 * - Actions are the same shape as the WebView tools: tap by text, tap
 *   by coordinates, type, scroll, swipe, screenshot, read the screen,
 *   open an app, open a URL, Back, Home, wait for the owner.
 *
 * Safety, hard rules, never broken:
 * - NEVER type into a password field. NEVER read a password field's
 *   text. A password field is refused before any action touches it.
 * - FLAG_SECURE screens (banking, DRM) expose no nodes and no pixels.
 *   screen_text comes back empty and the screenshot fails. That is the
 *   platform protecting the owner, not a bug. Return the empty result,
 *   never work around it.
 * - Login, OTP, biometrics and payments are always by hand. The agent
 *   waits with drive_wait_for_owner while the owner does them.
 * - No Google Play publish. Accessibility automation is prohibited
 *   there. This APK is sideloaded from GitHub releases, as before.
 */
class DriveService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DriveService? = null
            private set

        const val PREFS = "browser_mcp"
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_TARGET_LABEL = "target_label"
    }

    @Volatile
    private var currentPackage = ""

    override fun onServiceConnected() {
        instance = this
        // Config also lives in res/xml/accessibilityservice.xml. Keep the
        // flags in sync here in case the XML is ever skipped.
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { currentPackage = it.toString() }
        }
    }

    override fun onInterrupt() { }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ---- helpers ----

    private fun targetPackage(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, "") ?: ""

    private fun pause() {
        Thread.sleep(Random.nextLong(200, 700))
    }

    /**
     * Run a block on the main thread and wait for its result. Node
     * operations must run on the main thread. Never call this from the
     * main thread itself, and never wait on a latch inside the block,
     * or it deadlocks: the callback also needs the main thread.
     */
    private fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val out = AtomicReference<Any?>()
        val err = AtomicReference<Exception?>()
        Handler(Looper.getMainLooper()).post {
            try {
                out.set(block())
            } catch (e: Exception) {
                err.set(e)
            }
            latch.countDown()
        }
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw RuntimeException("Drive timed out")
        }
        err.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out.get() as T
    }

    /**
     * Walk the tree depth first, visiting every node, recycling every
     * child after it is visited. visit returns true to stop early.
     * Actions are done inside visit, while the node is still alive.
     */
    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (visit(node)) return true
        var i = 0
        while (i < node.childCount) {
            val child = node.getChild(i)
            i++
            if (child == null) continue
            val stop = walk(child, visit)
            child.recycle()
            if (stop) return true
        }
        return false
    }

    private fun root(): AccessibilityNodeInfo =
        rootInActiveWindow
            ?: throw RuntimeException("No active window. Open the target app first.")

    fun foregroundPackage(): String {
        if (currentPackage.isNotEmpty()) return currentPackage
        return try {
            rootInActiveWindow?.packageName?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Refuse any drive action unless the picked target app is on screen. */
    private fun requireTargetForeground() {
        val target = targetPackage()
        if (target.isEmpty()) {
            throw RuntimeException("No target app picked. Pick one in the Browser MCP app first.")
        }
        val cur = foregroundPackage()
        if (cur != target) {
            throw RuntimeException(
                "Target app is $target but the screen shows $cur. " +
                    "Open the target app first with drive_open_app."
            )
        }
    }

    private fun isPassword(node: AccessibilityNodeInfo): Boolean = node.isPassword

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString() ?: ""
        if (t.isNotEmpty()) return t
        return node.contentDescription?.toString() ?: ""
    }

    /** Center of the node on screen, or null if it has no bounds. */
    private fun nodeCenter(node: AccessibilityNodeInfo): IntArray? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null
        return intArrayOf(rect.centerX(), rect.centerY())
    }

    private fun screenSize(): IntArray {
        val metrics = resources.displayMetrics
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    // ---- screen ----

    fun screenText(): Map<String, Any> {
        requireTargetForeground()
        pause()
        return runOnMain {
            val root = root()
            val lines = ArrayList<String>()
            var blocked = false
            try {
                walk(root) { node ->
                    if (lines.size >= 200) return@walk true
                    if (!node.isVisibleToUser) return@walk false
                    if (isPassword(node)) {
                        // Never read a password field's text. Only note
                        // that one is there, with its position, no content.
                        val c = nodeCenter(node)
                        lines.add("[password field]" + if (c != null) " @ ${c[0]},${c[1]}" else "")
                        return@walk false
                    }
                    val text = nodeText(node).trim()
                    if (text.isEmpty()) return@walk false
                    val clean = text.replace("\n", " ").take(120)
                    val c = nodeCenter(node)
                    if (node.isClickable && c != null) {
                        lines.add("$clean [tap ${c[0]},${c[1]}]")
                    } else {
                        lines.add(clean)
                    }
                    false
                }
            } catch (e: Exception) {
                blocked = true
            } finally {
                root.recycle()
            }
            val out = lines.joinToString("\n").take(6000)
            if (blocked || lines.isEmpty()) {
                mapOf(
                    "package" to foregroundPackage(),
                    "text" to out,
                    "note" to "Screen is empty or protected. FLAG_SECURE screens return nothing."
                )
            } else {
                mapOf("package" to foregroundPackage(), "text" to out)
            }
        }
    }

    // ---- tap, type, scroll, swipe ----

    fun tapText(text: String): Map<String, Any> {
        requireTargetForeground()
        pause()
        val found = runOnMain {
            val root = root()
            var hit: String? = null
            try {
                walk(root) { node ->
                    if (isPassword(node)) return@walk false
                    val t = nodeText(node)
                    if (t.isEmpty() || !t.contains(text, ignoreCase = true)) return@walk false
                    var cur: AccessibilityNodeInfo? = node
                    var clicked = false
                    while (cur != null) {
                        if (cur.isClickable) {
                            clicked = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        }
                        val parent = cur.parent
                        if (cur !== node) cur.recycle()
                        cur = parent
                    }
                    if (cur != null && cur !== node) cur.recycle()
                    if (clicked) {
                        hit = t.trim().take(120)
                        true
                    } else {
                        false
                    }
                }
            } finally {
                root.recycle()
            }
            hit
        }
        if (found == null) throw RuntimeException("No tappable text found: $text")
        Thread.sleep(800)
        return mapOf("ok" to true, "tapped" to found)
    }

    fun tapCoords(x: Int, y: Int): Map<String, Any> {
        requireTargetForeground()
        pause()
        if (!gestureTap(x.toFloat(), y.toFloat())) {
            throw RuntimeException("Tap failed at $x,$y")
        }
        Thread.sleep(500)
        return mapOf("ok" to true, "x" to x, "y" to y)
    }

    fun typeText(field: String, text: String): Map<String, Any> {
        requireTargetForeground()
        pause()
        val matched = runOnMain {
            val root = root()
            var matched: String? = null
            var refused = false
            var setFailed = false
            try {
                walk(root) { node ->
                    if (!node.isEditable) return@walk false
                    if (isPassword(node)) {
                        // A password field matching the query: refuse it
                        // outright, do not touch it at all.
                        if (matchesField(node, field)) {
                            refused = true
                            return@walk true
                        }
                        return@walk false
                    }
                    if (!matchesField(node, field)) return@walk false
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        matched = nodeText(node).trim().take(120)
                        true
                    } else {
                        setFailed = true
                        true
                    }
                }
            } finally {
                root.recycle()
            }
            if (refused) throw RuntimeException("Password field refused, login by hand")
            if (matched == null && setFailed) {
                throw RuntimeException("Found the field but could not set its text: $field")
            }
            matched
        }
        if (matched == null) throw RuntimeException("No editable field matching: $field")
        Thread.sleep(500)
        return mapOf("ok" to true, "field" to matched)
    }

    private fun matchesField(node: AccessibilityNodeInfo, field: String): Boolean {
        val q = field.lowercase()
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""
        return hint.contains(q) || text.contains(q) || desc.contains(q) || id.contains(q)
    }

    fun scroll(direction: String, steps: Int): Map<String, Any> {
        requireTargetForeground()
        pause()
        val forward = direction != "up"
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var scrolled = false
        for (i in 1..steps) {
            val hit = runOnMain {
                val root = root()
                var hit = false
                try {
                    walk(root) { node ->
                        if (node.isScrollable && node.performAction(action)) {
                            hit = true
                            true
                        } else {
                            false
                        }
                    }
                } finally {
                    root.recycle()
                }
                hit
            }
            if (!hit) break
            scrolled = true
            Thread.sleep(300)
        }
        if (!scrolled) {
            // No scrollable node on this screen. Fall back to a swipe.
            val size = screenSize()
            val cx = size[0] / 2
            val y1 = if (forward) (size[1] * 0.7).toInt() else (size[1] * 0.3).toInt()
            val y2 = if (forward) (size[1] * 0.3).toInt() else (size[1] * 0.7).toInt()
            for (i in 1..steps) {
                swipe(cx, y1, cx, y2, 300)
                Thread.sleep(200)
            }
            return mapOf("ok" to true, "method" to "swipe")
        }
        return mapOf("ok" to true, "method" to "scroll")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Map<String, Any> {
        requireTargetForeground()
        pause()
        val duration = ms.coerceIn(50, 2000)
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            .build()
        if (!dispatchGestureResult(desc)) throw RuntimeException("Swipe failed")
        Thread.sleep(400)
        return mapOf("ok" to true)
    }

    // ---- gestures and screenshot ----

    private fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGestureResult(desc)
    }

    /**
     * Call dispatchGesture from a worker thread and wait for its
     * callback. The callback runs on the main thread, so waiting here
     * is safe. Never wait for it on the main thread, it deadlocks.
     */
    private fun dispatchGestureResult(desc: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicReference(false)
        dispatchGesture(
            desc,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    latch.countDown()
                }
            },
            null
        )
        latch.await(10, TimeUnit.SECONDS)
        return ok.get()
    }

    fun screenshot(): String {
        requireTargetForeground()
        pause()
        if (Build.VERSION.SDK_INT < 30) {
            throw RuntimeException("Screenshot needs Android 11 or newer")
        }
        val latch = CountDownLatch(1)
        val path = AtomicReference("")
        val err = AtomicReference<String?>(null)
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { cmd -> Handler(Looper.getMainLooper()).post(cmd) },
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val buffer = result.hardwareBuffer
                        val bitmap = if (buffer != null) {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        } else {
                            null
                        }
                        if (bitmap == null) {
                            err.set("Screenshot produced no image")
                        } else {
                            val file = screenshotFile()
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            path.set(file.absolutePath)
                        }
                        buffer?.close()
                    } catch (e: Exception) {
                        err.set(e.message ?: "Screenshot failed")
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    err.set("Screenshot failed, code $errorCode. Secure screens return nothing.")
                    latch.countDown()
                }
            }
        )
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw RuntimeException("Screenshot timed out")
        }
        err.get()?.let { throw RuntimeException(it) }
        val p = path.get()
        if (p.isEmpty()) throw RuntimeException("Screenshot failed")
        return p
    }

    private fun screenshotFile(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        return File(dir, "drive-latest.png")
    }

    // ---- open, back, home, wait ----

    fun openApp(packageName: String): Map<String, Any> {
        val target = targetPackage()
        if (target.isEmpty()) {
            throw RuntimeException("No target app picked. Pick one in the Browser MCP app first.")
        }
        if (packageName != target) {
            throw RuntimeException("That app is not the picked target. The target is $target.")
        }
        pause()
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw RuntimeException("Cannot open app: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Thread.sleep(1500)
        return mapOf("ok" to true, "package" to packageName)
    }

    fun openUrl(url: String): Map<String, Any> {
        pause()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            throw RuntimeException("Cannot open URL: " + e.message)
        }
        Thread.sleep(1500)
        return mapOf("ok" to true, "url" to url)
    }

    fun pressBack(): Map<String, Any> {
        pause()
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            throw RuntimeException("Back failed")
        }
        Thread.sleep(400)
        return mapOf("ok" to true)
    }

    fun pressHome(): Map<String, Any> {
        pause()
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            throw RuntimeException("Home failed")
        }
        Thread.sleep(400)
        return mapOf("ok" to true)
    }

    /**
     * Wait while the owner does a step by hand: login, OTP, biometrics,
     * payment. Polls the screen for the marker text. The owner is free
     * to use the phone meanwhile; this only watches.
     */
    fun waitForOwner(marker: String, timeoutSeconds: Int): Map<String, Any> {
        val end = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < end) {
            try {
                val text = screenText()["text"] as? String ?: ""
                if (text.contains(marker)) {
                    return mapOf("found" to true, "marker" to marker)
                }
            } catch (e: Exception) {
                // Screen may be empty or protected while the owner
                // works. Keep waiting, do not fail.
            }
            Thread.sleep(2000)
        }
        return mapOf("found" to false, "marker" to marker)
    }
}
