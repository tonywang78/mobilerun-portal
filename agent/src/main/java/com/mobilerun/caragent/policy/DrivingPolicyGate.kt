package com.mobilerun.caragent.policy

import com.mobilerun.caragent.vehicle.VehicleState

data class GateDecision(
    val allowed: Boolean,
    val reason: String,
)

/**
 * Graded driving restrictions:
 * - LOW: allowed while moving (summaries / tutorials speech)
 * - MEDIUM: allowed when parked or near-stationary
 * - HIGH: parked only (complex GUI / web automation)
 */
class DrivingPolicyGate {
    fun check(risk: RiskLevel, vehicle: VehicleState): GateDecision {
        return when (risk) {
            RiskLevel.LOW -> GateDecision(true, "low-risk allowed")
            RiskLevel.MEDIUM -> {
                if (vehicle.isParked || vehicle.speedKph <= 5f) {
                    GateDecision(true, "medium-risk allowed while parked/slow")
                } else {
                    GateDecision(false, "medium-risk blocked while driving (${vehicle.summary()})")
                }
            }
            RiskLevel.HIGH -> {
                if (vehicle.isParked) {
                    GateDecision(true, "high-risk allowed while parked")
                } else {
                    GateDecision(false, "high-risk GUI blocked while not parked (${vehicle.summary()})")
                }
            }
        }
    }

    fun riskForTool(toolName: String): RiskLevel = when (toolName) {
        "list_notifications",
        "summarize_notifications",
        "query_calendar",
        "complete",
        "wait",
        "get_state",
        "speak_tutorial",
        -> RiskLevel.LOW

        "open_app",
        "system_button",
        "open_settings_guide",
        -> RiskLevel.MEDIUM

        "click",
        "click_at",
        "long_press",
        "type",
        "type_secret",
        "swipe",
        "take_screenshot",
        "save_screenshot",
        "open_browser_url",
        "yt_open",
        "yt_skip_ad",
        "yt_seek",
        "product_search",
        "jp_lifestyle_open",
        -> RiskLevel.HIGH

        else -> RiskLevel.MEDIUM
    }
}
