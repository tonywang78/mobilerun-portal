package com.carrun.caragent.router

enum class ExecutionMode {
    AUTO,
    ALL_EDGE,
    ALL_CLOUD,
    ;

    companion object {
        fun fromStorage(raw: String?): ExecutionMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
    }
}

enum class LlmProfile {
    MANAGER,
    EXECUTOR,
    FAST_AGENT,
    STRUCTURED_OUTPUT,
    APP_OPENER,
}

enum class LlmBackendKind {
    ON_DEVICE,
    CLOUD,
}
