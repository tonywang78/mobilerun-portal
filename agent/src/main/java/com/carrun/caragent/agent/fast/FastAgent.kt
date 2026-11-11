package com.carrun.caragent.agent.fast

import com.carrun.caragent.brain.LlmRequest
import com.carrun.caragent.events.AgentEvent
import com.carrun.caragent.events.AgentEventBus
import com.carrun.caragent.router.LlmProfile
import com.carrun.caragent.router.LlmProfileRouter
import com.carrun.caragent.state.CarAgentState
import com.carrun.caragent.tools.ToolCallParser
import com.carrun.caragent.tools.ToolRegistry

class FastAgent(
    private val state: CarAgentState,
    private val tools: ToolRegistry,
    private val router: LlmProfileRouter,
    private val events: AgentEventBus,
    private val systemPrompt: String,
) {
    suspend fun run() {
        while (!state.finished && state.stepNumber < state.maxSteps) {
            state.stepNumber += 1
            events.emit(AgentEvent.Log("FastAgent step ${state.stepNumber}"))
            val user = buildString {
                appendLine("GOAL: ${state.goal}")
                appendLine("VEHICLE: ${state.vehicleState.summary()}")
                appendLine("PACKAGE: ${state.currentPackageName}")
                if (!state.appCard.isNullOrBlank()) appendLine("APP_CARD:\n${state.appCard}")
                appendLine("STATE:\n${state.formattedDeviceState.take(3000)}")
                appendLine("HISTORY:\n${state.actionHistory.takeLast(5).joinToString("\n") { it.summary }}")
            }
            val response = router.complete(
                LlmProfile.FAST_AGENT,
                state.executionMode,
                state.riskLevel,
                LlmRequest(systemPrompt, user),
            )
            events.emit(AgentEvent.Log("FastAgent LLM(${response.backend}): ${response.text.take(200)}"))
            val calls = ToolCallParser.parse(response.text)
            if (calls.isEmpty()) {
                state.finished = true
                state.success = false
                state.reason = "FastAgent produced no tool calls"
                break
            }
            for (call in calls) {
                tools.dispatch(call)
                if (state.finished) break
            }
        }
        if (!state.finished) {
            state.finished = true
            state.success = false
            state.reason = "max_steps reached"
        }
    }
}
