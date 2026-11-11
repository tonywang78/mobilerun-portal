package com.carrun.caragent.tools

object ToolCallParser {
    private val toolRegex = Regex(
        """<tool_call\s+name="([^"]+)">(.*?)</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val argRegex = Regex(
        """<arg\s+name="([^"]+)">(.*?)</arg>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parse(text: String): List<ToolCall> {
        return toolRegex.findAll(text).map { match ->
            val name = match.groupValues[1]
            val body = match.groupValues[2]
            val args = argRegex.findAll(body).associate { arg ->
                arg.groupValues[1] to coerce(arg.groupValues[2].trim())
            }
            ToolCall(name, args)
        }.toList()
    }

    private fun coerce(raw: String): Any {
        return when {
            raw.equals("true", true) -> true
            raw.equals("false", true) -> false
            raw.toIntOrNull() != null -> raw.toInt()
            raw.toDoubleOrNull() != null -> raw.toDouble()
            else -> raw
        }
    }
}
