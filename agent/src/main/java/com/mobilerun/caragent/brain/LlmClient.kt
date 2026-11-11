package com.mobilerun.caragent.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmRequest(
    val system: String,
    val user: String,
    val imageBase64: String? = null,
)

data class LlmResponse(
    val text: String,
    val backend: String,
)

interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

/**
 * Heuristic on-device planner used as a no-key fallback.
 * Interprets common car-agent goals into tool-call XML for FastAgent / Executor.
 */
class OnDeviceLlmClient : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val goal = request.user.lowercase()
        val text = when {
            "manager" in request.system.lowercase() -> managerPlan(goal)
            "executor" in request.system.lowercase() -> executorAction(goal, request.user)
            "structured" in request.system.lowercase() -> structured(goal)
            else -> fastAction(goal)
        }
        return LlmResponse(text = text, backend = "heuristic")
    }

    private fun managerPlan(goal: String): String = when {
        goal.contains("youtube") ->
            "PLAN: Open YouTube, locate the requested video, skip ads if present, seek if needed.\nNEXT: yt_open"
        goal.contains("通知") || goal.contains("notification") || goal.contains("日程") || goal.contains("calendar") ->
            "PLAN: Collect notifications/calendar and summarize.\nNEXT: list_notifications"
        goal.contains("商品") || goal.contains("product") || goal.contains("官网") ->
            "PLAN: Open product site, search, screenshot results.\nNEXT: product_search"
        goal.contains("生活") || goal.contains("japan") || goal.contains("日式") ->
            "PLAN: Open lifestyle site and complete GUI steps.\nNEXT: jp_lifestyle_open"
        goal.contains("设置") || goal.contains("settings") || goal.contains("教程") ->
            "PLAN: Explain and optionally open vehicle settings.\nNEXT: open_settings_guide"
        goal.contains("设置") || goal.contains("settings") ->
            "PLAN: Open car settings app.\nNEXT: open_car_settings"
        else ->
            "PLAN: Inspect UI state then act toward the goal.\nNEXT: get_state"
    }

    private fun executorAction(goal: String, raw: String): String {
        val next = raw.lines().firstOrNull { it.startsWith("NEXT:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.lowercase()
        return when (next) {
            "yt_open" -> """<tool_call name="yt_open"><arg name="query">${extractQuery(goal)}</arg></tool_call>"""
            "list_notifications" -> """<tool_call name="list_notifications"></tool_call>"""
            "product_search" -> """<tool_call name="product_search"><arg name="query">${extractQuery(goal)}</arg></tool_call>"""
            "jp_lifestyle_open" -> """<tool_call name="jp_lifestyle_open"><arg name="url">https://www.google.com/search?q=japan+lifestyle</arg></tool_call>"""
            "open_settings_guide" -> """<tool_call name="open_settings_guide"></tool_call>"""
            "open_car_settings", "open_app" ->
                """<tool_call name="open_app"><arg name="package">com.android.car.settings</arg></tool_call>"""
            else -> """<tool_call name="get_state"></tool_call>"""
        }
    }

    private fun fastAction(goal: String): String = when {
        goal.contains("通知") || goal.contains("notification") ->
            """<tool_call name="list_notifications"></tool_call>
<tool_call name="summarize_notifications"></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">notifications summarized</arg></tool_call>"""
        goal.contains("日程") || goal.contains("calendar") || goal.contains("备忘") ->
            """<tool_call name="query_calendar"></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">calendar queried</arg></tool_call>"""
        goal.contains("教程") || goal.contains("说明") ->
            """<tool_call name="speak_tutorial"><arg name="topic">${extractQuery(goal)}</arg></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">tutorial spoken</arg></tool_call>"""
        goal.contains("设置") || goal.contains("settings") ->
            """<tool_call name="open_app"><arg name="package">com.android.car.settings</arg></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">opened car settings</arg></tool_call>"""
        goal.contains("youtube") ->
            """<tool_call name="yt_open"><arg name="query">${extractQuery(goal)}</arg></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">youtube opened</arg></tool_call>"""
        goal.contains("截图") || goal.contains("screenshot") || goal.contains("商品") ->
            """<tool_call name="product_search"><arg name="query">${extractQuery(goal)}</arg></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">search and screenshot done</arg></tool_call>"""
        else ->
            """<tool_call name="get_state"></tool_call>
<tool_call name="complete"><arg name="success">true</arg><arg name="message">state inspected</arg></tool_call>"""
    }

    private fun structured(goal: String): String =
        """{"summary":"Processed: ${goal.take(80)}","items":[]}"""

    private fun extractQuery(goal: String): String {
        val cleaned = goal
            .replace("打开", " ")
            .replace("搜索", " ")
            .replace("youtube", " ", ignoreCase = true)
            .replace("商品官网", " ")
            .trim()
        return cleaned.ifBlank { goal }.take(80)
    }
}

/**
 * OpenAI-compatible chat completions client.
 */
class CloudLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = defaultClient(),
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            error("OpenAI 未配置：请在 Setup 填写 base URL 与 API key")
        }
        val root = baseUrl.trimEnd('/')
        val url = if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", request.system))
            .put(JSONObject().put("role", "user").put("content", request.user))
        val body = JSONObject()
            .put("model", model.ifBlank { "gpt-4o-mini" })
            .put("messages", messages)
            .put("temperature", 0.2)
        val httpReq = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(httpReq).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("OpenAI HTTP ${response.code}: ${text.take(400)}")
            }
            val json = JSONObject(text.ifBlank { "{}" })
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            if (content.isBlank()) error("OpenAI 返回空 content")
            LlmResponse(text = content, backend = "openai:$model")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
