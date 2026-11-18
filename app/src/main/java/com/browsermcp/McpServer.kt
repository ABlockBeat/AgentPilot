package com.browsermcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Built-in MCP server, streamable HTTP, local only, version 0.6.0.
 *
 * Binds 127.0.0.1 port 8765 only, never 0.0.0.0, never reachable off the
 * phone. Every request needs Authorization: Bearer, with the token the
 * app generated once and saved. The token is shown in Help, with
 * tap to copy, and changes only when the owner taps New key there.
 * 12 tools only: the drive tools that drive the REAL apps on the phone
 * through DriveService. The in-app WebView browser and its 12 tools are
 * gone: that was an option that did not do the real work, exchanges
 * detect a WebView and block logins, so only what works stays.
 *
 * Safety, read this before changing anything:
 * - The Authorization header is checked FIRST, before any body is
 *   read, and bodies over 1 MB are refused without allocating them.
 *   Without that, anyone reaching the server could force a huge
 *   allocation before any authentication happened.
 * - No password is ever filled, no credential file is ever uploaded.
 *   The drive tools refuse password fields outright, and FLAG_SECURE
 *   screens return nothing.
 * - The server does not own the UI. It reports tool calls through
 *   onLog and a dead server through onServerStopped. It never shows
 *   a toast: the owner is not a log viewer.
 */
class McpServer(
    private val token: String,
    private val onLog: (String) -> Unit,
    private val onServerStopped: (String?) -> Unit
) {

    companion object {
        private const val MAX_BODY_BYTES = 1024 * 1024
    }

    private var socket: ServerSocket? = null
    @Volatile private var running = false

    /**
     * Bind the server socket on this thread, so a bind failure is
     * known at once instead of showing Running while the server is
     * dead. Returns true when the server is up and accepting.
     */
    fun start(): Boolean {
        return try {
            socket = ServerSocket(8765, 8, InetAddress.getByName("127.0.0.1"))
            running = true
            thread {
                try {
                    while (running) {
                        val client = socket?.accept() ?: break
                        thread { handle(client) }
                    }
                } catch (e: Exception) {
                    if (running) onServerStopped(e.message)
                }
            }
            true
        } catch (e: Exception) {
            onServerStopped(e.message)
            false
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
    }

    private fun handle(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out: OutputStream = client.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line: String?
            var length = 0
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val parts = line!!.split(":", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].trim().lowercase()] = parts[1].trim()
                    if (parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                        length = parts[1].trim().toIntOrNull() ?: 0
                    }
                }
            }

            // Bearer token check FIRST, before any body is read.
            // No token, no work, no allocation.
            val auth = headers["authorization"] ?: ""
            if (auth != "Bearer $token") {
                send(out, 401, JSONObject().put("error", "unauthorized").toString())
                return
            }
            if (length < 0) length = 0
            if (length > MAX_BODY_BYTES) {
                send(out, 413, JSONObject().put("error", "request too large").toString())
                return
            }
            if (!requestLine.contains("POST /mcp")) {
                send(out, 404, JSONObject().put("error", "not found").toString())
                return
            }
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val bodyText = String(body, 0, read)

            val response = dispatch(bodyText)
            send(out, 200, response)
        } catch (e: Exception) {
            try {
                send(client.getOutputStream(), 500, JSONObject().put("error", e.message).toString())
            } catch (ignored: Exception) { }
        } finally {
            try { client.close() } catch (e: Exception) { }
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun send(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun dispatch(bodyText: String): String {
        val req = JSONObject(bodyText)
        val id = if (req.has("id")) req.get("id") else JSONObject.NULL
        val method = req.optString("method", "")
        // Real MCP clients send ping to check liveness and
        // notifications after initialize. Both are answered with an
        // empty result, never an error, so a strict client can finish
        // the handshake.
        if (method == "ping" || method.startsWith("notifications/")) {
            return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject()).toString()
        }
        return try {
            val result = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "agentpilot").put("version", "0.6.0"))
                "tools/list" -> JSONObject().put("tools", toolsList())
                "tools/call" -> callTool(req.getJSONObject("params"))
                else -> throw RuntimeException("Unknown method: $method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
        } catch (e: Exception) {
            val error = JSONObject().put("code", -32603).put("message", e.message)
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", error).toString()
        }
    }

    private fun tool(name: String, description: String, props: JSONObject, required: JSONArray): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", required))
    }

    private fun str(): JSONObject = JSONObject().put("type", "string")
    private fun num(): JSONObject = JSONObject().put("type", "number")

    private fun toolsList(): JSONArray {
        val list = JSONArray()
        // The 12 drive tools. They drive the REAL target app on the
        // phone through the AccessibilityService. Only the one app the
        // owner picked, plus system Back and Home.
        list.put(tool("drive_tap_text", "Tap the first tappable text in the target app.", JSONObject().put("text", str()), JSONArray().put("text")))
        list.put(tool("drive_tap_coords", "Tap a point on the screen in the target app.", JSONObject().put("x", num()).put("y", num()), JSONArray().put("x").put("y")))
        list.put(tool("drive_type_text", "Type into a field in the target app. Never a password field.", JSONObject().put("field", str()).put("text", str()), JSONArray().put("field").put("text")))
        list.put(tool("drive_scroll", "Scroll the target app, up or down, in steps.", JSONObject().put("direction", str()).put("steps", num()), JSONArray()))
        list.put(tool("drive_swipe", "Swipe in the target app from one point to another.", JSONObject().put("x1", num()).put("y1", num()).put("x2", num()).put("y2", num()).put("duration_ms", num()), JSONArray().put("x1").put("y1").put("y2").put("x2")))
        list.put(tool("drive_screenshot", "Screenshot the target app screen to a file.", JSONObject(), JSONArray()))
        list.put(tool("drive_screen_text", "Read the text on the target app screen, with tap positions.", JSONObject(), JSONArray()))
        list.put(tool("drive_open_app", "Open the picked target app.", JSONObject().put("package", str()), JSONArray().put("package")))
        list.put(tool("drive_open_url", "Open a URL in the real browser.", JSONObject().put("url", str()), JSONArray().put("url")))
        list.put(tool("drive_press_back", "Press Back.", JSONObject(), JSONArray()))
        list.put(tool("drive_press_home", "Press Home.", JSONObject(), JSONArray()))
        list.put(tool("drive_wait_for_owner", "Pause while the owner does a step by hand in the target app, for example login or OTP.", JSONObject().put("marker", str()).put("timeout_seconds", num()), JSONArray().put("marker")))
        return list
    }

    private fun drive(): DriveService =
        DriveService.instance
            ?: throw RuntimeException("Accessibility service is off. Turn it on in Settings, Accessibility, AgentPilot.")

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val service = drive()
        val result: Any = when (name) {
            "drive_tap_text" -> service.tapText(args.getString("text"))
            "drive_tap_coords" -> service.tapCoords(args.optInt("x", 0), args.optInt("y", 0))
            "drive_type_text" -> service.typeText(args.getString("field"), args.getString("text"))
            "drive_scroll" -> service.scroll(args.optString("direction", "down"), args.optInt("steps", 3))
            "drive_swipe" -> service.swipe(args.optInt("x1", 0), args.optInt("y1", 0), args.optInt("x2", 0), args.optInt("y2", 0), args.optInt("duration_ms", 300))
            "drive_screenshot" -> mapOf("path" to service.screenshot())
            "drive_screen_text" -> service.screenText()
            "drive_open_app" -> service.openApp(args.getString("package"))
            "drive_open_url" -> service.openUrl(args.getString("url"))
            "drive_press_back" -> service.pressBack()
            "drive_press_home" -> service.pressHome()
            "drive_wait_for_owner" -> service.waitForOwner(args.getString("marker"), args.optInt("timeout_seconds", 300))
            else -> throw RuntimeException("Unknown tool: $name")
        }
        val text = when (result) {
            is Map<*, *> -> JSONObject(result as Map<String, Any>).toString()
            else -> result.toString()
        }
        // No toast: the call goes in the action log instead.
        onLog(name)
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    }
}
