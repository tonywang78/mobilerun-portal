package com.mobilerun.caragent.brain.jev

import com.mobilerun.caragent.config.AgentPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * System One client for hosted TypeSafe Jev or local-compatible servers (Laya, jeff, …).
 */
class TypeSafeClient(
    private val apiKey: String,
    private val model: String = "jev-latest",
    private val baseUrl: String = AgentPreferences.DEFAULT_TYPESAFE_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val endpoint: String = AgentPreferences.systemOneUrl(baseUrl)

    suspend fun systemOne(state: JSONObject, questions: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model.ifBlank { "jev-latest" })
                .put("state", state)
                .put("questions", questions)
            val payload = body.toString()
            if (payload.toByteArray().size > 150_000) {
                error("Screen is too large for Jev policy; narrow the observation.")
            }
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON))
            // Local Laya without LAYA_API_KEY accepts unauthenticated requests.
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }
            client.newCall(reqBuilder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("System One HTTP ${response.code} ($endpoint): ${text.take(500)}")
                }
                JSONObject(text.ifBlank { "{}" })
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
