package com.mobilerun.caragent.router

import com.mobilerun.caragent.policy.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmProfileRouterTest {
    private val router = LlmProfileRouter()

    @Test
    fun allEdgeForcesOnDevice() {
        val kind = router.resolve(LlmProfile.MANAGER, ExecutionMode.ALL_EDGE, RiskLevel.HIGH)
        assertEquals(LlmBackendKind.ON_DEVICE, kind)
    }

    @Test
    fun allCloudForcesCloud() {
        val kind = router.resolve(LlmProfile.FAST_AGENT, ExecutionMode.ALL_CLOUD, RiskLevel.LOW)
        assertEquals(LlmBackendKind.CLOUD, kind)
    }

    @Test
    fun autoPrefersCloudForHighRiskManager() {
        val kind = router.resolve(LlmProfile.MANAGER, ExecutionMode.AUTO, RiskLevel.HIGH)
        assertEquals(LlmBackendKind.CLOUD, kind)
    }

    @Test
    fun autoPrefersEdgeForFastAgent() {
        val kind = router.resolve(LlmProfile.FAST_AGENT, ExecutionMode.AUTO, RiskLevel.LOW)
        assertEquals(LlmBackendKind.ON_DEVICE, kind)
    }
}
