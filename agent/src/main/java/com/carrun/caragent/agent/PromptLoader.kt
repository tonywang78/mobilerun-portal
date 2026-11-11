package com.carrun.caragent.agent

import android.content.Context

class PromptLoader(private val context: Context) {
    fun load(assetName: String, fallback: String): String {
        return try {
            context.assets.open("prompts/$assetName").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            fallback
        }
    }
}
