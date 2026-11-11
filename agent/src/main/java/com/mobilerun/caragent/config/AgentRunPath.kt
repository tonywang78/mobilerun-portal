package com.mobilerun.caragent.config

/**
 * Orchestration path selected on the home screen.
 * Distinct from [BrainMode], which only picks the LLM backend for Fast / Reasoning.
 */
enum class AgentRunPath {
    FAST,
    REASONING,
    JEV,
    ;

    companion object {
        fun fromStorage(raw: String?): AgentRunPath? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
