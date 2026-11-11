package com.mobilerun.caragent.config

import android.content.Context
import com.mobilerun.caragent.router.ExecutionMode
import com.mobilerun.caragent.vehicle.Gear

class AgentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var portalUrl: String
        get() = prefs.getString(KEY_PORTAL_URL, DEFAULT_PORTAL_URL) ?: DEFAULT_PORTAL_URL
        set(value) = prefs.edit().putString(KEY_PORTAL_URL, value.trimEnd('/')).apply()

    var portalToken: String
        get() = prefs.getString(KEY_PORTAL_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PORTAL_TOKEN, value).apply()

    /** Retained for router/tests; home UI no longer exposes this. */
    var executionMode: ExecutionMode
        get() = ExecutionMode.fromStorage(prefs.getString(KEY_MODE, ExecutionMode.AUTO.name))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var runPath: AgentRunPath
        get() {
            AgentRunPath.fromStorage(prefs.getString(KEY_RUN_PATH, null))?.let { return it }
            // Migrate from legacy brain_mode=JEV / reasoning boolean.
            val legacyBrain = prefs.getString(KEY_BRAIN_MODE, null)
            val migrated = when {
                legacyBrain.equals("JEV", ignoreCase = true) -> AgentRunPath.JEV
                prefs.getBoolean(KEY_REASONING, false) -> AgentRunPath.REASONING
                else -> AgentRunPath.FAST
            }
            prefs.edit().putString(KEY_RUN_PATH, migrated.name).apply()
            return migrated
        }
        set(value) {
            prefs.edit()
                .putString(KEY_RUN_PATH, value.name)
                .putBoolean(KEY_REASONING, value == AgentRunPath.REASONING)
                .apply()
        }

    /** Derived from [runPath] for callers that still use a boolean. */
    var reasoning: Boolean
        get() = runPath == AgentRunPath.REASONING
        set(value) {
            runPath = when {
                value -> AgentRunPath.REASONING
                runPath == AgentRunPath.JEV -> AgentRunPath.JEV
                else -> AgentRunPath.FAST
            }
        }

    var mockVehicle: Boolean
        get() = prefs.getBoolean(KEY_MOCK_VEHICLE, true)
        set(value) = prefs.edit().putBoolean(KEY_MOCK_VEHICLE, value).apply()

    var mockGear: Gear
        get() {
            val stored = prefs.getString(KEY_MOCK_GEAR, null)
            if (stored != null) {
                return runCatching { Gear.valueOf(stored) }.getOrDefault(Gear.PARK)
            }
            // Migrate legacy mock_parked boolean.
            return if (prefs.getBoolean(KEY_MOCK_PARKED, true)) Gear.PARK else Gear.DRIVE
        }
        set(value) = prefs.edit().putString(KEY_MOCK_GEAR, value.name).apply()

    var mockSpeedKph: Float
        get() {
            if (prefs.contains(KEY_MOCK_SPEED)) {
                return prefs.getFloat(KEY_MOCK_SPEED, 0f)
            }
            // Migrate legacy mock_parked boolean.
            return if (prefs.getBoolean(KEY_MOCK_PARKED, true)) 0f else 40f
        }
        set(value) = prefs.edit().putFloat(KEY_MOCK_SPEED, value.coerceAtLeast(0f)).apply()

    var brainMode: BrainMode
        get() = BrainMode.fromStorage(prefs.getString(KEY_BRAIN_MODE, BrainMode.HEURISTIC.name))
        set(value) = prefs.edit().putString(KEY_BRAIN_MODE, value.name).apply()

    var openaiBaseUrl: String
        get() = prefs.getString(KEY_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL) ?: DEFAULT_OPENAI_BASE_URL
        set(value) = prefs.edit().putString(KEY_OPENAI_BASE_URL, value.trimEnd('/')).apply()

    var openaiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var openaiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    var systemOneEndpoint: SystemOneEndpoint
        get() {
            val stored = prefs.getString(KEY_SYSTEM_ONE_ENDPOINT, null)
            if (stored != null) return SystemOneEndpoint.fromStorage(stored)
            val inferred = SystemOneEndpoint.inferFromBaseUrl(typeSafeBaseUrl)
            prefs.edit().putString(KEY_SYSTEM_ONE_ENDPOINT, inferred.name).apply()
            return inferred
        }
        set(value) = prefs.edit().putString(KEY_SYSTEM_ONE_ENDPOINT, value.name).apply()

    /** System One API root (e.g. TypeSafe cloud or local Laya). */
    var typeSafeBaseUrl: String
        get() = prefs.getString(KEY_TYPESAFE_BASE_URL, DEFAULT_TYPESAFE_BASE_URL)
            ?: DEFAULT_TYPESAFE_BASE_URL
        set(value) = prefs.edit().putString(KEY_TYPESAFE_BASE_URL, normalizeBaseUrl(value)).apply()

    var typeSafeApiKey: String
        get() = prefs.getString(KEY_TYPESAFE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TYPESAFE_API_KEY, value).apply()

    var typeSafeModel: String
        get() = prefs.getString(KEY_TYPESAFE_MODEL, DEFAULT_TYPESAFE_MODEL) ?: DEFAULT_TYPESAFE_MODEL
        set(value) = prefs.edit().putString(KEY_TYPESAFE_MODEL, value).apply()

    var jevConfidenceThreshold: Float
        get() = prefs.getFloat(KEY_JEV_CONFIDENCE, 0f)
        set(value) = prefs.edit().putFloat(KEY_JEV_CONFIDENCE, value.coerceIn(0f, 1f)).apply()

    fun typeSafeSystemOneUrl(): String = systemOneUrl(typeSafeBaseUrl)

    /** Hosted TypeSafe requires a real API key; local Laya/jeff usually do not. */
    fun requiresTypeSafeApiKey(): Boolean =
        systemOneEndpoint == SystemOneEndpoint.CLOUD ||
            typeSafeBaseUrl.contains("api.typesafe.ai", ignoreCase = true)

    fun setMockScenario(gear: Gear, speedKph: Float) {
        prefs.edit()
            .putString(KEY_MOCK_GEAR, gear.name)
            .putFloat(KEY_MOCK_SPEED, speedKph.coerceAtLeast(0f))
            .apply()
    }

    companion object {
        const val DEFAULT_PORTAL_URL = "http://127.0.0.1:8080"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_TYPESAFE_BASE_URL = "https://api.typesafe.ai"
        const val DEFAULT_TYPESAFE_MODEL = "jev-latest"
        const val LOCAL_LAYA_BASE_URL = "http://127.0.0.1:8000"
        const val LOCAL_LAYA_MODEL = "laya"

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.endsWith("/v1/systemone", ignoreCase = true)) {
                url = url.dropLast("/v1/systemone".length).trimEnd('/')
            } else if (url.endsWith("/v1", ignoreCase = true)) {
                url = url.dropLast("/v1".length).trimEnd('/')
            }
            return url.ifBlank { DEFAULT_TYPESAFE_BASE_URL }
        }

        fun systemOneUrl(baseUrl: String): String =
            "${normalizeBaseUrl(baseUrl)}/v1/systemone"

        private const val PREFS = "car_gui_agent"
        private const val KEY_PORTAL_URL = "portal_url"
        private const val KEY_PORTAL_TOKEN = "portal_token"
        private const val KEY_MODE = "execution_mode"
        private const val KEY_RUN_PATH = "run_path"
        private const val KEY_REASONING = "reasoning"
        private const val KEY_MOCK_VEHICLE = "mock_vehicle"
        private const val KEY_MOCK_PARKED = "mock_parked"
        private const val KEY_MOCK_GEAR = "mock_gear"
        private const val KEY_MOCK_SPEED = "mock_speed_kph"
        private const val KEY_BRAIN_MODE = "brain_mode"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_SYSTEM_ONE_ENDPOINT = "system_one_endpoint"
        private const val KEY_TYPESAFE_BASE_URL = "typesafe_base_url"
        private const val KEY_TYPESAFE_API_KEY = "typesafe_api_key"
        private const val KEY_TYPESAFE_MODEL = "typesafe_model"
        private const val KEY_JEV_CONFIDENCE = "jev_confidence"
    }
}
