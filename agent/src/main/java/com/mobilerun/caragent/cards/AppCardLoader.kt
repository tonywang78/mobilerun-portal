package com.mobilerun.caragent.cards

import android.content.Context

class AppCardLoader(private val context: Context) {
    fun load(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val assetPath = "app_cards/$packageName/CARD.md"
        return try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            // Also try sanitized aliases.
            val alias = when {
                packageName.contains("chrome", true) -> "com.android.chrome"
                packageName.contains("settings", true) -> "com.android.car.settings"
                packageName.contains("youtube", true) -> "com.google.android.youtube"
                packageName.contains("calendar", true) -> "com.android.calendar"
                else -> null
            } ?: return null
            try {
                context.assets.open("app_cards/$alias/CARD.md").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
