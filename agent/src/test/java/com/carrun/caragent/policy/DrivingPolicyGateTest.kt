package com.carrun.caragent.policy

import com.carrun.caragent.vehicle.VehicleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingPolicyGateTest {
    private val gate = DrivingPolicyGate()

    @Test
    fun lowRiskAllowedWhileDriving() {
        val decision = gate.check(RiskLevel.LOW, VehicleState.mockDriving())
        assertTrue(decision.allowed)
    }

    @Test
    fun highRiskBlockedWhileDriving() {
        val decision = gate.check(RiskLevel.HIGH, VehicleState.mockDriving())
        assertFalse(decision.allowed)
    }

    @Test
    fun highRiskAllowedWhenParked() {
        val decision = gate.check(RiskLevel.HIGH, VehicleState.mockParked())
        assertTrue(decision.allowed)
    }

    @Test
    fun toolRiskMapping() {
        assertTrue(gate.riskForTool("list_notifications") == RiskLevel.LOW)
        assertTrue(gate.riskForTool("yt_open") == RiskLevel.HIGH)
        assertTrue(gate.riskForTool("open_app") == RiskLevel.MEDIUM)
    }
}
