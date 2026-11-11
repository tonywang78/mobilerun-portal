package com.carrun.caragent.config

/** Where the decision-model (System One) client points. */
enum class SystemOneEndpoint {
    CLOUD,
    LAYA,
    CUSTOM,
    ;

    companion object {
        fun fromStorage(raw: String?): SystemOneEndpoint =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CLOUD

        fun inferFromBaseUrl(baseUrl: String): SystemOneEndpoint {
            val normalized = AgentPreferences.normalizeBaseUrl(baseUrl)
            return when {
                normalized.contains("api.typesafe.ai", ignoreCase = true) -> CLOUD
                normalized.contains("127.0.0.1:8000") ||
                    normalized.contains("10.0.2.2:8000") ||
                    normalized.contains("localhost:8000") -> LAYA
                else -> CUSTOM
            }
        }
    }
}
