package com.carrun.caragent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallParserTest {
    @Test
    fun parsesXmlToolCalls() {
        val text = """
            <tool_call name="open_app"><arg name="package">com.android.settings</arg></tool_call>
            <tool_call name="complete"><arg name="success">true</arg><arg name="message">done</arg></tool_call>
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(2, calls.size)
        assertEquals("open_app", calls[0].name)
        assertEquals("com.android.settings", calls[0].args["package"])
        assertEquals(true, calls[1].args["success"])
    }
}
