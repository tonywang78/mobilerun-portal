package com.mobilerun.caragent.router

import com.mobilerun.caragent.brain.CloudLlmClient
import com.mobilerun.caragent.brain.LlmClient
import com.mobilerun.caragent.brain.LlmRequest
import com.mobilerun.caragent.brain.LlmResponse
import com.mobilerun.caragent.brain.OnDeviceLlmClient
import com.mobilerun.caragent.config.AgentPreferences
import com.mobilerun.caragent.config.BrainMode
import com.mobilerun.caragent.policy.RiskLevel

/**
 * Selects on-device vs cloud LLM backends per profile, with BrainMode override.
 * [com.mobilerun.caragent.config.AgentRunPath.JEV] does not use this router for the main loop (see JevAgent).
 */
class LlmProfileRouter(
    private val prefs: AgentPreferences? = null,
    private val onDevice: OnDeviceLlmClient = OnDeviceLlmClient(),
    private val cloudToEdgeFallback: Boolean = true,
    private val autoPreferCloudFor: Set<LlmProfile> = setOf(LlmProfile.MANAGER, LlmProfile.EXECUTOR),
) {
    private fun cloudClient(): CloudLlmClient {
        val p = prefs
        return CloudLlmClient(
            baseUrl = p?.openaiBaseUrl.orEmpty(),
            apiKey = p?.openaiApiKey.orEmpty(),
            model = p?.openaiModel ?: AgentPreferences.DEFAULT_OPENAI_MODEL,
        )
    }

    fun resolve(profile: LlmProfile, mode: ExecutionMode, risk: RiskLevel): LlmBackendKind {
        when (prefs?.brainMode) {
            BrainMode.HEURISTIC -> return LlmBackendKind.ON_DEVICE
            BrainMode.OPENAI -> return LlmBackendKind.CLOUD
            null -> Unit
        }
        return when (mode) {
            ExecutionMode.ALL_EDGE -> LlmBackendKind.ON_DEVICE
            ExecutionMode.ALL_CLOUD -> LlmBackendKind.CLOUD
            ExecutionMode.AUTO -> {
                when {
                    risk == RiskLevel.HIGH && profile in autoPreferCloudFor -> LlmBackendKind.CLOUD
                    profile == LlmProfile.STRUCTURED_OUTPUT -> LlmBackendKind.ON_DEVICE
                    profile == LlmProfile.FAST_AGENT -> LlmBackendKind.ON_DEVICE
                    else -> LlmBackendKind.ON_DEVICE
                }
            }
        }
    }

    suspend fun complete(
        profile: LlmProfile,
        mode: ExecutionMode,
        risk: RiskLevel,
        request: LlmRequest,
    ): LlmResponse {
        val primary = resolve(profile, mode, risk)
        return try {
            client(primary).complete(request)
        } catch (t: Throwable) {
            if (primary == LlmBackendKind.CLOUD && cloudToEdgeFallback &&
                mode != ExecutionMode.ALL_CLOUD &&
                prefs?.brainMode != BrainMode.OPENAI
            ) {
                onDevice.complete(request.copy(system = request.system + "\n[fallback from cloud]"))
            } else {
                throw t
            }
        }
    }

    fun client(kind: LlmBackendKind): LlmClient = when (kind) {
        LlmBackendKind.ON_DEVICE -> onDevice
        LlmBackendKind.CLOUD -> cloudClient()
    }
}
