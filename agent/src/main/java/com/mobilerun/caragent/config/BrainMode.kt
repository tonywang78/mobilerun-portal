package com.mobilerun.caragent.config

/**
 * LLM backend for FastAgent / Manager+Executor.
 * Jev orchestration is [AgentRunPath.JEV], not a brain mode.
 */
enum class BrainMode {
    HEURISTIC,
    OPENAI,
    ;

    companion object {
        fun fromStorage(raw: String?): BrainMode =
            when {
                raw.equals(OPENAI.name, ignoreCase = true) -> OPENAI
                // Legacy "JEV" brain value is no longer a backend; fall back to heuristic.
                else -> HEURISTIC
            }
    }
}
