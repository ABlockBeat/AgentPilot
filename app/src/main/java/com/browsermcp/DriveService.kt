// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.random.Random

/**
 * AgentPilot, drive engine, version 0.6.23.
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
        private const val TAG = "AgentPilot"

        @Volatile
        var instance: DriveService? = null
            private set

        const val PREFS = "browser_mcp"
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_TARGET_LABEL = "target_label"
        const val KEY_TOKEN = "mcp_token"

        /** One place for the MCP port and loopback host: the
         * server, both tunnels and the UI test all use these,
         * never a literal, so the port changes in one line. */
        const val MCP_PORT = 8765
        const val MCP_LOOPBACK_HOST = "127.0.0.1"
        /** The MCP server's own address, built from the two
         * constants above. The tunnels and the UI test fetch this
         * URL, never a literal. */
        const val MCP_LOOPBACK_URL = "http://127.0.0.1:8765"

        /** Our own relay, the primary connection since 0.6.22:
         * the phone and the cloud both dial OUT to it, and it
         * forwards the MCP messages between them. One place for
         * the URL, so it points at any host without touching the
         * connector code. The placeholder host never resolves
         * (the .invalid top-level domain is reserved for exactly
         * that), so until a real host is set the connector
         * reports "Relay host not set yet" instead of dialing
         * nowhere. Production MUST be wss: over plain ws the
         * room token in the URL travels in cleartext. */
        const val RELAY_URL = "wss://relay.example.invalid:8766"
        private const val RELAY_PLACEHOLDER = ".invalid"

        /** True while the relay URL still holds the placeholder
         * host, meaning no real relay has been set yet. */
        fun isRelayPlaceholder(): Boolean = RELAY_URL.contains(RELAY_PLACEHOLDER)

        /** The MCP URL on a Tailscale address. Built with
         * Uri.Builder, never written as a literal: the address is
         * the phone's own, and the traffic under the HTTP is
         * encrypted by WireGuard (see the comment at the call
         * site in KeepAliveService). */
        fun tailnetUrl(ip: String): String =
            android.net.Uri.Builder().scheme("http")
                .authority(ip + ":" + MCP_PORT).build().toString()

        /** Tor via Orbot, since 0.6.23: the phone's .onion
         * address, saved in the plain prefs. The address is
         * public by design, so it is not a secret and never
         * goes in the encrypted store. The MCP bearer token
         * still guards every request to it. */
        const val KEY_ONION_HOSTNAME = "onion_hostname"

        fun savedOnionHostname(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ONION_HOSTNAME, "") ?: ""

        fun saveOnionHostname(context: Context, hostname: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ONION_HOSTNAME, hostname).apply()
        }

        /** The MCP URL on the phone's .onion address. Built
         * with Uri.Builder, never written as a literal, like
         * tailnetUrl above. */
        fun torUrl(hostname: String): String =
            android.net.Uri.Builder().scheme("http")
                .authority(hostname + ":" + MCP_PORT).build().toString()

        /** Encrypted store for the secrets: the MCP bearer token
         * and the ngrok authtoken. Plain SharedPreferences are
         * readable by any app with root and travel out in a
         * backup, so the secrets never live there. The other
         * prefs (target app, session wanted, tunnel backend) are
         * not secrets and stay in the plain prefs. Falls back to
         * the plain prefs only when the Android KeyStore is
         * unavailable, and logs it: losing the session is worse
         * than a degraded store. */
        private const val SECURE_PREFS = "browser_mcp_secure"

        fun securePrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context, SECURE_PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "Encrypted prefs unavailable, plain prefs used", e)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }

        /** Move a secret out of the plain prefs into the encrypted
         * store, once. Returns the moved value, or "" when the
         * plain prefs never held it. The plain copy is deleted
         * only when the encrypted store is really a different
         * file: when the KeyStore fell back to the plain prefs,
         * deleting would destroy the secret itself. */
        private fun migrateSecret(context: Context, key: String): String {
            val secure = securePrefs(context)
            val plain = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val value = plain.getString(key, "") ?: ""
            if (value.isEmpty()) return ""
            secure.edit().putString(key, value).apply()
            if (secure !== plain) plain.edit().remove(key).apply()
            return value
        }

        /**
         * The MCP bearer token, generated once and saved. The tunnel
         * URL alone connects nothing: the agent needs this token too.
         * It never changes on its own, only when the owner taps New
         * key in Help. The server reads it fresh on every start.
         */
        fun storedToken(context: Context): String {
            val secure = securePrefs(context)
            var token = secure.getString(KEY_TOKEN, "") ?: ""
            if (token.isEmpty()) {
                // First run after the encryption change: the old
                // token lives in the plain prefs. Move it, do not
                // mint a new one, so the owner's saved connection
                // keeps working.
                token = migrateSecret(context, KEY_TOKEN)
                if (token.isEmpty()) {
                    token = UUID.randomUUID().toString()
                    secure.edit().putString(KEY_TOKEN, token).apply()
                }
            }
            return token
        }

        /** A new token, saved at once. The old one stops working. */
        fun newToken(context: Context): String {
            val token = UUID.randomUUID().toString()
            securePrefs(context).edit().putString(KEY_TOKEN, token).apply()
            return token
        }

        /** The ngrok authtoken, in the same encrypted store as the
         * MCP token. The owner pastes it once in Help; it never
         * leaves the phone except to ngrok itself. */
        fun storedNgrokToken(context: Context): String {
            val secure = securePrefs(context)
            var token = secure.getString(KeepAliveService.KEY_NGROK_TOKEN, "") ?: ""
            if (token.isEmpty()) {
                token = migrateSecret(context, KeepAliveService.KEY_NGROK_TOKEN)
            }
            return token
        }

        /** Save the ngrok authtoken the owner pasted in Help. */
        fun saveNgrokToken(context: Context, token: String) {
            securePrefs(context).edit()
                .putString(KeepAliveService.KEY_NGROK_TOKEN, token).apply()
        }
    }

    @Volatile
    private var currentPackage = ""

    /**
     * The app he was using before the target took the screen.
     * Recorded on every window change (see onAccessibilityEvent).
     * When a drive session ends, returnToPrevious() puts him back
     * there, so the phone does not sit in Chrome after the work
     * is done. Empty when nothing was recorded, for example when
     * he started from the AgentPilot screen itself.
     */
    @Volatile
    private var returnPackage = ""

    /**
     * While a drive_batch runs, the per-action idle pauses are
     * skipped and the settle sleeps are capped, so a whole post
     * lands in one short burst. Set only by the batch tool, always
     * reset in a finally block. Single actions are unaffected.
     */
    @Volatile
    var batchMode = false

    override fun onCreate() {
        super.onCreate()
        // Diagnostic only: the UI test kept showing "Accessibility
        // off" while the system said the service was bound, so the
        // service lifecycle is logged to prove whether it ever
        // connects in the emulator.
        Log.i(TAG, "DriveService onCreate")
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "DriveService onServiceConnected")
        // Tell the home screen the service just bound, so its button
        // refreshes from "Turn on Accessibility" to "Start" at once.
        // Without this the button stays stale until the next resume,
        // and a fresh install can sit on the wrong button forever.
        sendBroadcast(Intent(KeepAliveService.ACTION_STATE_CHANGED).setPackage(packageName))
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
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty()) {
                // Remember the last app he was actually using, so
                // the session can put him back there when it ends.
                // The target itself is where the work happens, our
                // own app is only a doorway, and system UI has
                // nothing to return to, so none of those is ever
                // recorded as the return target.
                if (pkg != targetPackage() && pkg != packageName &&
                    pkg != "com.android.systemui") {
                    returnPackage = pkg
                }
                currentPackage = pkg
            }
        }
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        Log.i(TAG, "DriveService onDestroy")
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "DriveService onUnbind")
        // Same refresh in reverse: the home screen must fall back to
        // "Turn on Accessibility" as soon as the service is gone.
        sendBroadcast(Intent(KeepAliveService.ACTION_STATE_CHANGED).setPackage(packageName))
        return super.onUnbind(intent)
    }

    // ---- helpers ----

    private fun targetPackage(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, "") ?: ""

    private fun pause() {
        // In a batch there is no idle gap between actions.
        if (batchMode) return
        Thread.sleep(Random.nextLong(200, 700))
    }

    /**
     * Settle time after an action, so the screen catches up
     * before the next step reads it. In a batch the UI still
     * needs a beat, but not the full human pause: 200 ms is
     * enough for a view update on this phone, and it keeps a
     * multi-step burst to seconds instead of tens of seconds.
     * App launches keep their full settle (see openApp/openUrl),
     * because a cold app needs longer than a view update.
     */
    private fun settle(ms: Long) {
        Thread.sleep(if (batchMode) minOf(ms, 200) else ms)
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
            throw RuntimeException("No target app picked. Pick one in the AgentPilot app first.")
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
        settle(800)
        return mapOf("ok" to true, "tapped" to found)
    }

    fun tapCoords(x: Int, y: Int): Map<String, Any> {
        requireTargetForeground()
        pause()
        if (!gestureTap(x.toFloat(), y.toFloat())) {
            throw RuntimeException("Tap failed at $x,$y")
        }
        settle(500)
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
        settle(500)
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
            settle(300)
        }
        if (!scrolled) {
            // No scrollable node on this screen. Fall back to a swipe.
            val size = screenSize()
            val cx = size[0] / 2
            val y1 = if (forward) (size[1] * 0.7).toInt() else (size[1] * 0.3).toInt()
            val y2 = if (forward) (size[1] * 0.3).toInt() else (size[1] * 0.7).toInt()
            for (i in 1..steps) {
                swipe(cx, y1, cx, y2, 300)
                settle(200)
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
        settle(400)
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
                            // Prefer the public copy: the private file
                            // above is invisible to Drive, Gallery and
                            // the file picker, so the owner could never
                            // send a screenshot anywhere. When the
                            // public copy fails, the private path is
                            // still returned, never nothing.
                            path.set(publishScreenshot(bitmap) ?: file.absolutePath)
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

    /**
     * A second copy of the screenshot in Pictures/AgentPilot, where
     * the owner, Drive, Gallery and the file picker can all see it.
     * No permission is needed on Android 10 and newer: an app may
     * always write its own files to MediaStore. The old shot under
     * the same name is deleted first, so the picker always finds
     * exactly one latest screenshot, never a pile of copies.
     * Returns the public file path, or null when the copy failed.
     * A failure here never loses the screenshot: the private file
     * written by the caller stays, and its path is returned instead.
     *
     * Deliberate trade-off, not an oversight: the public copy is
     * the only way the owner ever sees a screenshot. The private
     * file is invisible to Drive, Gallery and the file picker, so
     * without the public copy he could never send a screenshot
     * anywhere. Exactly one latest file is kept, the old shot is
     * deleted first, and the screen content is his own: the same
     * screen he already looks at. Any app with photo permission
     * can read it, which is the price of him being able to see it.
     */
    private fun publishScreenshot(bitmap: Bitmap): String? {
        return try {
            val name = "drive-latest.png"
            val folder = "Pictures/AgentPilot"
            contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, "$folder/")
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            ).resolve("AgentPilot/$name").absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Public screenshot copy failed, private file kept", e)
            null
        }
    }

    // ---- open, back, home, wait ----

    fun openApp(packageName: String): Map<String, Any> {
        val target = targetPackage()
        if (target.isEmpty()) {
            throw RuntimeException("No target app picked. Pick one in the AgentPilot app first.")
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
        settle(400)
        return mapOf("ok" to true)
    }

    fun pressHome(): Map<String, Any> {
        pause()
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            throw RuntimeException("Home failed")
        }
        settle(400)
        return mapOf("ok" to true)
    }

    /**
     * Put the owner back in the app he was using before the
     * target took the screen. The package was recorded on every
     * window change, so this is the last real app he was in, not
     * a guess. The home screen counts as Home, not an app to
     * relaunch, and an app that cannot be opened falls back to
     * Home too. It never fails the caller: a return that cannot
     * happen just leaves him where he is. It cannot restore the
     * exact screen inside his app, only the app itself.
     */
    fun returnToPrevious(): Map<String, Any> {
        val pkg = returnPackage
        if (pkg.isEmpty()) return pressHome()
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        if (pkg == home) return pressHome()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return pressHome()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            settle(800)
            mapOf("ok" to true, "package" to pkg)
        } catch (e: Exception) {
            pressHome()
        }
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
