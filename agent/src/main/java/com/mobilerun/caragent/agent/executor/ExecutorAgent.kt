package com.mobilerun.caragent.agent.executor

import com.mobilerun.caragent.agent.manager.ManagerPlan
import com.mobilerun.caragent.brain.LlmRequest
import com.mobilerun.caragent.events.AgentEvent
import com.mobilerun.caragent.events.AgentEventBus
import com.mobilerun.caragent.router.LlmProfile
import com.mobilerun.caragent.router.LlmProfileRouter
import com.mobilerun.caragent.state.CarAgentState
import com.mobilerun.caragent.tools.ToolCallParser
import com.mobilerun.caragent.tools.ToolRegistry

class ExecutorAgent(
    private val state: CarAgentState,
    private val tools: ToolRegistry,
    private val router: LlmProfileRouter,
    private val events: AgentEventBus,
    private val systemPrompt: String,
) {
    suspend fun execute(plan: ManagerPlan) {
        events.emit(AgentEvent.Log("Executor acting on plan"))
        val user = buildString {
            appendLine(plan.planText)
            appendLine("GOAL: ${state.goal}")
            appendLine("VEHICLE: ${state.vehicleState.summary()}")
            appendLine("STATE:\n${state.formattedDeviceState.take(2500)}")
        }
        val response = router.complete(
            LlmProfile.EXECUTOR,
            state.executionMode,
            state.riskLevel,
            LlmRequest(systemPrompt, user),
        )
        events.emit(AgentEvent.Log("Executor LLM(${response.backend}): ${response.text.take(200)}"))
        val calls = ToolCallParser.parse(response.text)
        if (calls.isEmpty()) {
            // Fallback: honor NEXT: tool name from manager plan.
            val next = plan.planText.lineSequence()
                .firstOrNull { it.startsWith("NEXT:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
            if (!next.isNullOrBlank()) {
                tools.dispatch(com.mobilerun.caragent.tools.ToolCall(next))
            } else {
                events.emit(AgentEvent.Log("Executor produced no tool calls"))
            }
            return
        }
        for (call in calls) {
            tools.dispatch(call)
            if (state.finished) break
        }
    }
}
