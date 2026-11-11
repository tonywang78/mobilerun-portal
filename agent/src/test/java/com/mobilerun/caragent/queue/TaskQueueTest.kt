package com.mobilerun.caragent.queue

import com.mobilerun.caragent.policy.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskQueueTest {
    @Test
    fun queuesSecondHighRiskTask() {
        val q = TaskQueue()
        assertTrue(q.tryStart(QueuedTask("a", RiskLevel.HIGH)))
        assertFalse(q.tryStart(QueuedTask("b", RiskLevel.HIGH)))
        assertEquals(2, q.pendingHighRiskCount())
        q.finishHighRisk()
        val next = q.pollNextHighRisk()
        assertEquals("b", next?.goal)
    }

    @Test
    fun lowRiskNeverBlocked() {
        val q = TaskQueue()
        assertTrue(q.tryStart(QueuedTask("a", RiskLevel.HIGH)))
        assertTrue(q.tryStart(QueuedTask("summary", RiskLevel.LOW)))
    }
}
