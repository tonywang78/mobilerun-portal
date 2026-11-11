package com.mobilerun.caragent.tools

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap(),
)

data class ToolCall(
    val name: String,
    val args: Map<String, Any?> = emptyMap(),
)

data class ToolResult(
    val success: Boolean,
    val summary: String,
    val data: Any? = null,
)

fun interface ToolHandler {
    suspend fun invoke(args: Map<String, Any?>): ToolResult
}
