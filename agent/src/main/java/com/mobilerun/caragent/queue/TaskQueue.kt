package com.mobilerun.caragent.queue

import com.mobilerun.caragent.policy.RiskLevel
import java.util.ArrayDeque

data class QueuedTask(
    val goal: String,
    val risk: RiskLevel,
)

/**
 * Ensures only one high-risk GUI task runs at a time.
 */
class TaskQueue {
    private val highRisk = ArrayDeque<QueuedTask>()
    private var activeHighRisk: QueuedTask? = null

    @Synchronized
    fun tryStart(task: QueuedTask): Boolean {
        if (task.risk != RiskLevel.HIGH) return true
        if (activeHighRisk != null) {
            highRisk.addLast(task)
            return false
        }
        activeHighRisk = task
        return true
    }

    @Synchronized
    fun finishHighRisk() {
        activeHighRisk = null
    }

    @Synchronized
    fun pollNextHighRisk(): QueuedTask? {
        if (activeHighRisk != null) return null
        val next = highRisk.pollFirst() ?: return null
        activeHighRisk = next
        return next
    }

    @Synchronized
    fun pendingHighRiskCount(): Int = highRisk.size + if (activeHighRisk != null) 1 else 0

    @Synchronized
    fun clear() {
        highRisk.clear()
        activeHighRisk = null
    }
}
