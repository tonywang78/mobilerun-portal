package com.carrun.caragent.device

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PortalNode(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: String?,
    val clickable: Boolean,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val password: Boolean = false,
    val hint: String? = null,
)

data class PortalState(
    val packageName: String?,
    val rawJson: String,
    val nodes: List<PortalNode>,
)

class PortalTools(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
) {
    fun ping(): Boolean = try {
        val req = Request.Builder().url("$baseUrl/ping").get().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    fun version(): String {
        val body = getJson("/version")
        return body.optString("result", body.toString())
    }

    fun getState(): PortalState {
        val body = getJson("/state_full")
        val result = body.opt("result")
        val raw = when (result) {
            is JSONObject -> result.toString()
            is String -> result
            else -> body.toString()
        }
        val nodes = parseNodes(raw)
        val pkg = extractPackage(raw)
        return PortalState(pkg, raw, nodes)
    }

    fun tap(x: Int, y: Int): String = postAction("tap", JSONObject().put("x", x).put("y", y))

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 400): String =
        postAction(
            "swipe",
            JSONObject()
                .put("startX", startX)
                .put("startY", startY)
                .put("endX", endX)
                .put("endY", endY)
                .put("duration", durationMs),
        )

    fun openApp(packageName: String, activity: String? = null): String {
        val params = JSONObject().put("package", packageName)
        if (!activity.isNullOrBlank()) params.put("activity", activity)
        return postAction("app", params)
    }

    fun typeText(text: String, clear: Boolean = true): String {
        val encoded = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        return postAction(
            "keyboard/input",
            JSONObject().put("base64_text", encoded).put("clear", clear),
        )
    }

    fun keyboardKey(keyCode: Int): String =
        postAction("keyboard/key", JSONObject().put("key_code", keyCode))

    fun globalAction(action: Int): String =
        postAction("global", JSONObject().put("action", action))

    fun screenshotBytes(): ByteArray {
        val body = getJson("/screenshot?hideOverlay=true")
        if (body.optString("status") == "error") {
            error(body.optString("error", "screenshot failed"))
        }
        val b64 = body.optString("result")
        if (b64.isBlank()) error("empty screenshot")
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    fun discoverAuthTokenViaContent(context: android.content.Context): String? {
        return try {
            val uri = android.net.Uri.parse("content://com.carrun.portal/auth_token")
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val idx = cursor.getColumnIndex("result")
                val raw = if (idx >= 0) {
                    cursor.getString(idx)
                } else {
                    (0 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "" }
                }
                unwrapAuthTokenColumn(raw)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun postAction(path: String, params: JSONObject): String {
        val payload = params.toString().toRequestBody(JSON)
        val candidates = listOf("/$path", "/action/$path")
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val req = authorizedBuilder(candidate).post(payload).build()
                client.newCall(req).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) return text.ifBlank { """{"status":"success"}""" }
                    lastError = IllegalStateException("HTTP ${response.code}: $text")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("portal action failed: $path")
    }

    private fun getJson(path: String): JSONObject {
        val req = authorizedBuilder(path).get().build()
        client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GET $path failed: HTTP ${response.code} $text")
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun authorizedBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url("$baseUrl$path")
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun parseNodes(raw: String): List<PortalNode> {
        return try {
            val root = JSONObject(raw)
            val result = root.optJSONObject("result") ?: root
            val flat = mutableListOf<PortalNode>()
            val index = intArrayOf(0)

            fun boundsOf(n: JSONObject): String? {
                val b = n.optJSONObject("boundsInScreen") ?: return n.optString("bounds").ifBlank { null }
                return "[${b.optInt("left")},${b.optInt("top")}][${b.optInt("right")},${b.optInt("bottom")}]"
            }

            fun fromJson(n: JSONObject, fallbackIndex: Int): PortalNode = PortalNode(
                index = if (n.has("index")) n.optInt("index") else fallbackIndex,
                text = n.optString("text").ifBlank { null },
                contentDescription = n.optString("contentDescription").ifBlank { null },
                className = n.optString("className").ifBlank { null },
                bounds = boundsOf(n) ?: n.optString("bounds").ifBlank { null },
                clickable = n.optBoolean("isClickable", n.optBoolean("clickable", false)),
                editable = n.optBoolean("isEditable", n.optBoolean("editable", false)),
                scrollable = n.optBoolean("isScrollable", n.optBoolean("scrollable", false)),
                checkable = n.optBoolean("isCheckable", n.optBoolean("checkable", false)),
                checked = n.optBoolean("isChecked", n.optBoolean("checked", false)),
                enabled = n.optBoolean("isEnabled", n.optBoolean("enabled", true)),
                focused = n.optBoolean("isFocused", n.optBoolean("focused", false)),
                selected = n.optBoolean("isSelected", n.optBoolean("selected", false)),
                password = n.optBoolean("isPassword", n.optBoolean("password", false)),
                hint = n.optString("hintText").ifBlank { n.optString("hint") }.ifBlank { null },
            )

            fun walk(n: JSONObject) {
                flat += fromJson(n, index[0]++)
                val children = n.optJSONArray("children") ?: return
                for (i in 0 until children.length()) {
                    children.optJSONObject(i)?.let { walk(it) }
                }
            }

            when {
                result.opt("a11y_tree") is JSONObject -> walk(result.getJSONObject("a11y_tree"))
                result.opt("a11y_tree") is JSONArray -> {
                    val arr = result.getJSONArray("a11y_tree")
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { walk(it) }
                }
                result.opt("nodes") is JSONArray -> {
                    val arr = result.getJSONArray("nodes")
                    for (i in 0 until arr.length()) {
                        val n = arr.optJSONObject(i) ?: continue
                        flat += fromJson(n, i)
                    }
                }
                root.opt("a11y_tree") is JSONObject -> walk(root.getJSONObject("a11y_tree"))
            }
            flat
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractPackage(raw: String): String? {
        return try {
            val root = JSONObject(raw)
            val result = root.optJSONObject("result")
            val tree = result?.optJSONObject("a11y_tree") ?: root.optJSONObject("a11y_tree")
            result?.optString("packageName")?.takeIf { it.isNotBlank() }
                ?: tree?.optString("packageName")?.takeIf { it.isNotBlank() }
                ?: root.optString("packageName").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val KEYCODE_ENTER = 66

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Portal ContentProvider returns `{"status":"success","result":"<token>"}` in the result column. */
        fun unwrapAuthTokenColumn(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return null
            if (value.startsWith("{")) {
                return try {
                    JSONObject(value).optString("result").trim().takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    Regex("\"result\"\\s*:\\s*\"([^\"]+)\"").find(value)?.groupValues?.getOrNull(1)
                }
            }
            return value
        }

        fun boundsRect(bounds: String?): IntArray? {
            if (bounds.isNullOrBlank()) return null
            val nums = Regex("-?\\d+").findAll(bounds).map { it.value.toInt() }.toList()
            if (nums.size < 4) return null
            return intArrayOf(nums[0], nums[1], nums[2], nums[3])
        }

        fun boundsCenter(bounds: String?): Pair<Int, Int>? {
            val r = boundsRect(bounds) ?: return null
            return ((r[0] + r[2]) / 2) to ((r[1] + r[3]) / 2)
        }
    }
}
