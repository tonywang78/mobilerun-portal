package com.mobilerun.caragent.agent.manager

import com.mobilerun.caragent.brain.LlmRequest
import com.mobilerun.caragent.events.AgentEvent
import com.mobilerun.caragent.events.AgentEventBus
import com.mobilerun.caragent.router.LlmProfile
import com.mobilerun.caragent.router.LlmProfileRouter
import com.mobilerun.caragent.state.CarAgentState

data class ManagerPlan(
    val planText: String,
    val done: Boolean,
)

class ManagerAgent(
    private val state: CarAgentState,
    private val router: LlmProfileRouter,
    private val events: AgentEventBus,
    private val systemPrompt: String,
) {
    suspend fun plan(): ManagerPlan {
        val user = buildString {
            appendLine("GOAL: ${state.goal}")
            appendLine("VEHICLE: ${state.vehicleState.summary()}")
            appendLine("PACKAGE: ${state.currentPackageName}")
            if (!state.appCard.isNullOrBlank()) appendLine("APP_CARD:\n${state.appCard}")
            appendLine("STATE:\n${state.formattedDeviceState.take(3000)}")
            appendLine("HISTORY:\n${state.actionHistory.takeLast(8).joinToString("\n") { "${it.name}:${it.summary}" }}")
            appendLine("Respond with PLAN and NEXT lines. If done, write DONE: reason")
        }
        val response = router.complete(
            LlmProfile.MANAGER,
            state.executionMode,
            state.riskLevel,
            LlmRequest(systemPrompt, user),
        )
        val text = response.text
        events.emit(AgentEvent.ManagerPlan(text))
        state.currentPlan = text
        val done = text.contains("DONE:", ignoreCase = true) ||
            state.actionHistory.any { it.name == "complete" && it.success }
        if (done) {
            val reason = text.lineSequence()
                .firstOrNull { it.startsWith("DONE:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
                ?: state.reason.ifBlank { "manager marked done" }
            state.finished = true
            state.success = !reason.contains("fail", ignoreCase = true)
            state.reason = reason
        }
        return ManagerPlan(text, done)
    }
}
