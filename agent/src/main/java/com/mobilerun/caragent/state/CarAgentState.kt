package com.mobilerun.caragent.state

import com.mobilerun.caragent.policy.RiskLevel
import com.mobilerun.caragent.router.ExecutionMode
import com.mobilerun.caragent.vehicle.VehicleState

data class ActionRecord(
    val name: String,
    val args: Map<String, Any?>,
    val success: Boolean,
    val summary: String,
)

/**
 * Shared run state aligned with mobilerun MobileAgentState, plus car fields.
 */
data class CarAgentState(
    var goal: String = "",
    var stepNumber: Int = 0,
    var maxSteps: Int = 15,
    var formattedDeviceState: String = "",
    var currentPackageName: String? = null,
    var actionHistory: MutableList<ActionRecord> = mutableListOf(),
    var agentMemory: MutableList<String> = mutableListOf(),
    var customVariables: MutableMap<String, String> = mutableMapOf(),
    var finished: Boolean = false,
    var success: Boolean = false,
    var reason: String = "",
    var currentPlan: String = "",
    var vehicleState: VehicleState = VehicleState.mockParked(),
    var riskLevel: RiskLevel = RiskLevel.LOW,
    var executionMode: ExecutionMode = ExecutionMode.AUTO,
    var reasoning: Boolean = false,
    var appCard: String? = null,
    var structuredOutput: String? = null,
) {
    fun remember(fact: String) {
        agentMemory.add(fact)
    }

    fun recordAction(record: ActionRecord) {
        actionHistory.add(record)
    }
}
