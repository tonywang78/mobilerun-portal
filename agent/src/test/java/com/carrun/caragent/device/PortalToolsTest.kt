package com.carrun.caragent.device

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalToolsTest {
    @Test
    fun pingAndVersion() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"status":"success","result":"pong"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"success","result":"1.0.0"}"""))
        server.start()
        try {
            val tools = PortalTools(server.url("/").toString().trimEnd('/'), "token")
            assertTrue(tools.ping())
            assertEquals("1.0.0", tools.version())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getStateParsesPackage() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"status":"success","result":{"packageName":"com.android.settings","nodes":[{"index":0,"text":"Network","clickable":true,"bounds":"[0,0][100,50]"}]}}""",
            ),
        )
        server.start()
        try {
            val tools = PortalTools(server.url("/").toString().trimEnd('/'), "token")
            val state = tools.getState()
            assertEquals("com.android.settings", state.packageName)
            assertEquals(1, state.nodes.size)
        } finally {
            server.shutdown()
        }
    }
}
