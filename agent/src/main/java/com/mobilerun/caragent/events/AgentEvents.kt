package com.mobilerun.caragent.events

sealed class AgentEvent {
    data class Log(val message: String) : AgentEvent()
    data class ManagerPlan(val plan: String) : AgentEvent()
    data class ToolExecution(
        val toolName: String,
        val args: Map<String, Any?>,
        val success: Boolean,
        val summary: String,
    ) : AgentEvent()
    data class Result(
        val success: Boolean,
        val reason: String,
        val steps: Int,
        val structuredOutput: String? = null,
    ) : AgentEvent()
}

class AgentEventBus {
    private val listeners = mutableListOf<(AgentEvent) -> Unit>()

    fun subscribe(listener: (AgentEvent) -> Unit) {
        listeners += listener
    }

    fun unsubscribe(listener: (AgentEvent) -> Unit) {
        listeners -= listener
    }

    fun emit(event: AgentEvent) {
        listeners.toList().forEach { it(event) }
    }
}
