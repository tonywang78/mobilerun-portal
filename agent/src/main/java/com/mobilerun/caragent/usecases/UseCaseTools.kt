package com.mobilerun.caragent.usecases

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.mobilerun.caragent.device.AppLauncher
import com.mobilerun.caragent.device.PortalTools
import com.mobilerun.caragent.events.AgentEvent
import com.mobilerun.caragent.events.AgentEventBus
import com.mobilerun.caragent.state.CarAgentState
import com.mobilerun.caragent.tools.ToolHandler
import com.mobilerun.caragent.tools.ToolResult
import com.mobilerun.caragent.ui.YoutubeWebActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom tools for use cases 1–5, registered into ToolRegistry.
 */
class UseCaseTools(
    private val context: Context,
    private val portal: PortalTools,
    private val state: CarAgentState,
    private val events: AgentEventBus,
    private val appLauncher: AppLauncher? = null,
) {
    private val notificationBuffer = mutableListOf<JSONObject>()
    private val carPlayPackages = setOf(
        "com.apple.android.music",
        "com.google.android.projection.gearhead",
        "com.apple", // prefix match handled below
    )
    private val lifestyleWhitelist = listOf(
        "https://www.google.com/search?q=japan+lifestyle",
        "https://www.hotpepper.jp/",
        "https://tabelog.com/",
        "https://www.rakuten.co.jp/",
    )

    fun handlers(): Map<String, ToolHandler> = mapOf(
        // Use case 1
        "list_notifications" to ToolHandler { listNotifications() },
        "summarize_notifications" to ToolHandler { summarizeNotifications() },
        "query_calendar" to ToolHandler { queryCalendar() },
        // Use case 2
        "jp_lifestyle_open" to ToolHandler { args ->
            val url = args["url"]?.toString()?.ifBlank { lifestyleWhitelist.first() } ?: lifestyleWhitelist.first()
            val host = Uri.parse(url).host.orEmpty()
            val allowed = lifestyleWhitelist.any { allowedUrl ->
                val allowedHost = Uri.parse(allowedUrl).host.orEmpty()
                host.isNotBlank() && allowedHost.isNotBlank() && host.endsWith(allowedHost)
            } || url.contains("google.com/search")
            if (!allowed) return@ToolHandler ToolResult(false, "URL not in lifestyle whitelist: $url")
            openBrowser(url)
            ToolResult(true, "opened lifestyle url $url")
        },
        // Use case 3
        "yt_open" to ToolHandler { args ->
            val query = args["query"]?.toString().orEmpty().ifBlank { "music" }
            val intent = Intent(context, YoutubeWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(YoutubeWebActivity.EXTRA_QUERY, query)
            context.startActivity(intent)
            state.remember("youtube query=$query")
            ToolResult(true, "opened YouTube webview query=$query")
        },
        "yt_skip_ad" to ToolHandler {
            YoutubeWebActivity.controller?.skipAd()
            ToolResult(true, "requested skip ad")
        },
        "yt_seek" to ToolHandler { args ->
            val seconds = args["seconds"]?.toString()?.toIntOrNull()
                ?: args["time"]?.toString()?.toIntOrNull()
                ?: 0
            YoutubeWebActivity.controller?.seekTo(seconds)
            ToolResult(true, "seek to ${seconds}s")
        },
        // Use case 4
        "product_search" to ToolHandler { args ->
            val query = args["query"]?.toString().orEmpty().ifBlank { "product" }
            val url = "https://www.google.com/search?q=${Uri.encode(query)}"
            openBrowser(url)
            // Best-effort screenshot after launching search.
            val bytes = try {
                Thread.sleep(1200)
                portal.screenshotBytes()
            } catch (t: Throwable) {
                events.emit(AgentEvent.Log("screenshot after product search failed: ${t.message}"))
                null
            }
            val path = bytes?.let { saveScreenshot(it) }
            state.remember("product_search query=$query file=$path")
            ToolResult(true, "product search opened; screenshot=${path ?: "n/a"}", path)
        },
        "save_screenshot" to ToolHandler {
            val bytes = portal.screenshotBytes()
            val path = saveScreenshot(bytes)
            ToolResult(true, "saved $path", path)
        },
        // Use case 5
        "speak_tutorial" to ToolHandler { args ->
            val topic = args["topic"]?.toString().orEmpty().ifBlank { "车辆基础设置" }
            val script = tutorialScript(topic)
            state.structuredOutput = script
            state.remember("tutorial:$topic")
            events.emit(AgentEvent.Log("TUTORIAL: $script"))
            ToolResult(true, script)
        },
        "open_settings_guide" to ToolHandler {
            // AAOS uses Car Settings; phone package is a fallback.
            val pkg = "com.android.car.settings"
            val summary = try {
                appLauncher?.open(pkg) ?: run {
                    portal.openApp(pkg)
                    "opened $pkg"
                }
            } catch (_: Throwable) {
                appLauncher?.open("com.android.settings")
                    ?: run {
                        portal.openApp("com.android.settings")
                        "opened com.android.settings"
                    }
            }
            val script = tutorialScript("系统设置导航")
            state.structuredOutput = script
            ToolResult(true, "$summary; $script")
        },
    )

    private fun listNotifications(): ToolResult {
        // Portal notification streaming may not be queried via simple HTTP; provide a local
        // capability probe + placeholder buffer that UI/tests can seed.
        val native = notificationBuffer.toList()
        val carPlay = native.filter { isCarPlay(it.optString("packageName")) }
        val payload = JSONObject()
            .put("nativeCount", native.size)
            .put("carPlayCount", carPlay.size)
            .put(
                "items",
                JSONArray().also { arr ->
                    native.takeLast(20).forEach { arr.put(it) }
                },
            )
            .put(
                "degradation",
                if (carPlay.isEmpty()) {
                    "CarPlay notifications not observed; using native notification buffer / Portal listener when available"
                } else {
                    "CarPlay-related notifications present"
                },
            )
        state.structuredOutput = payload.toString()
        state.remember("notifications listed native=${native.size} carplay=${carPlay.size}")
        return ToolResult(true, payload.getString("degradation"), payload)
    }

    private fun summarizeNotifications(): ToolResult {
        val items = notificationBuffer.takeLast(10)
        val summary = if (items.isEmpty()) {
            "当前没有缓冲的通知。请确认 Portal 通知监听已开启，或 CarPlay/原生通知可达。"
        } else {
            items.joinToString("\n") {
                val pkg = it.optString("packageName")
                val title = it.optString("title")
                val text = it.optString("text")
                "- [$pkg] $title: $text"
            }
        }
        state.structuredOutput = JSONObject().put("summary", summary).toString()
        return ToolResult(true, summary)
    }

    private fun queryCalendar(): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val end = now + 3L * 24 * 60 * 60 * 1000
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, now)
            ContentUris.appendId(builder, end)
            val eventsList = mutableListOf<String>()
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.EVENT_LOCATION,
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                var count = 0
                while (cursor.moveToNext() && count < 10) {
                    val title = cursor.getString(titleIdx) ?: "(无标题)"
                    val begin = cursor.getLong(beginIdx)
                    val loc = cursor.getString(locIdx).orEmpty()
                    val whenText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(begin))
                    eventsList += "$whenText $title ${if (loc.isBlank()) "" else "@ $loc"}".trim()
                    count++
                }
            }
            val summary = if (eventsList.isEmpty()) {
                "近 3 天无日程（或未授予日历权限）。"
            } else {
                eventsList.joinToString("\n")
            }
            state.structuredOutput = JSONObject().put("calendar", summary).toString()
            ToolResult(true, summary)
        } catch (t: Throwable) {
            ToolResult(false, "calendar query failed: ${t.message}")
        }
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        // Also ask Portal to open via deep link if possible.
        try {
            // HTTP deep-link endpoint exists on portal; best-effort.
            portal.openApp("com.android.chrome")
        } catch (_: Throwable) {
        }
    }

    private fun saveScreenshot(bytes: ByteArray): String {
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun tutorialScript(topic: String): String = buildString {
        append("【车辆教程】$topic\n")
        append("1. 确认车辆驻车且注意驾驶分心限制。\n")
        append("2. 打开系统设置，按关键词搜索目标项。\n")
        append("3. 调整前先朗读当前值，确认后再修改。\n")
        append("4. 完成后回到主屏幕，口头确认结果。")
    }

    private fun isCarPlay(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return carPlayPackages.any { pkg.equals(it, true) || pkg.startsWith(it) } ||
            pkg.contains("carplay", true) ||
            pkg.contains("projection", true)
    }

    /** Test/helper API to seed notification buffer. */
    fun seedNotification(packageName: String, title: String, text: String) {
        notificationBuffer += JSONObject()
            .put("packageName", packageName)
            .put("title", title)
            .put("text", text)
            .put("timestamp", System.currentTimeMillis())
    }
}
