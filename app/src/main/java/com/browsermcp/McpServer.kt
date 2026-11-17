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
 * Built-in MCP server, streamable HTTP, local only.
 *
 * Binds 127.0.0.1 port 8765 only, never 0.0.0.0, never reachable off the
 * phone. Every request needs Authorization: Bearer <token>. The token is
 * generated in the app, shown once in the app, regenerated in the app.
 * Same 12 tools as the desktop server, same names, same arguments.
 * No password is ever filled, no credential file is ever uploaded.
 */
class McpServer(private val activity: MainActivity, private val token: String) {

    private var socket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        thread {
            try {
                socket = ServerSocket(8765, 1, InetAddress.getByName("127.0.0.1"))
                while (running) {
                    val client = socket?.accept() ?: break
                    thread { handle(client) }
                }
            } catch (e: Exception) {
                activity.status("MCP server stopped: " + e.message)
            }
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
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val bodyText = String(body, 0, read)

            // Bearer token check, every request. No token, no access.
            val auth = headers["authorization"] ?: ""
            if (auth != "Bearer $token") {
                send(out, 401, JSONObject().put("error", "unauthorized").toString())
                return
            }
            if (!requestLine.contains("POST /mcp")) {
                send(out, 404, JSONObject().put("error", "not found").toString())
                return
            }

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

    private fun send(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun dispatch(bodyText: String): String {
        val req = JSONObject(bodyText)
        val id = if (req.has("id")) req.get("id") else JSONObject.NULL
        val method = req.optString("method", "")
        return try {
            val result = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "browser-mcp").put("version", "0.3.0"))
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
        list.put(tool("navigate", "Go to a URL.", JSONObject().put("url", str()), JSONArray().put("url")))
        list.put(tool("get_page", "Read the current page.", JSONObject(), JSONArray()))
        list.put(tool("click_text", "Click the first element with this text.", JSONObject().put("text", str()), JSONArray().put("text")))
        list.put(tool("type_text", "Type slowly into a field. Never a password field.", JSONObject().put("field", str()).put("text", str()), JSONArray().put("field").put("text")))
        list.put(tool("scroll", "Scroll in steps.", JSONObject().put("direction", str()).put("steps", num()), JSONArray()))
        list.put(tool("screenshot", "Save a screenshot.", JSONObject(), JSONArray()))
        list.put(tool("wait_for_login", "Pause while the owner logs in by hand.", JSONObject().put("marker", str()).put("timeout_seconds", num()), JSONArray().put("marker")))
        list.put(tool("wait_for_owner", "Pause while the owner does a step by hand.", JSONObject().put("marker", str()).put("timeout_seconds", num()), JSONArray().put("marker")))
        list.put(tool("upload_file", "Choose a file the owner named.", JSONObject().put("field", str()).put("file_path", str()), JSONArray().put("file_path")))
        list.put(tool("download_by_text", "Click a link and save the download.", JSONObject().put("text", str()), JSONArray().put("text")))
        list.put(tool("close", "Close the browser, keep the profile.", JSONObject(), JSONArray()))
        list.put(tool("check_human", "Self check: does this browser look like a coding agent?", JSONObject(), JSONArray()))
        return list
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result: Any = when (name) {
            "navigate" -> activity.navigate(args.getString("url"))
            "get_page" -> activity.getPage()
            "click_text" -> activity.clickText(args.getString("text"))
            "type_text" -> activity.typeText(args.getString("field"), args.getString("text"))
            "scroll" -> activity.scroll(args.optString("direction", "down"), args.optInt("steps", 3))
            "screenshot" -> mapOf("path" to activity.screenshot())
            "wait_for_login" -> activity.waitFor(args.getString("marker"), args.optInt("timeout_seconds", 120))
            "wait_for_owner" -> activity.waitFor(args.getString("marker"), args.optInt("timeout_seconds", 300))
            "upload_file" -> activity.uploadFile(args.optString("field", ""), args.getString("file_path"))
            "download_by_text" -> activity.downloadByText(args.getString("text"))
            "close" -> { activity.runOnUiThread { activity.finish() }; mapOf("ok" to true) }
            "check_human" -> activity.checkHuman()
            else -> throw RuntimeException("Unknown tool: $name")
        }
        val text = when (result) {
            is Map<*, *> -> JSONObject(result as Map<String, Any>).toString()
            else -> result.toString()
        }
        activity.status("MCP: $name")
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    }
}
