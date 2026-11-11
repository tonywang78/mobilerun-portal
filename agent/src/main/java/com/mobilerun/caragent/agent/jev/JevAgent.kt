package com.mobilerun.caragent.agent.jev

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mobilerun.caragent.brain.jev.HistoryItem
import com.mobilerun.caragent.brain.jev.InstalledApp
import com.mobilerun.caragent.brain.jev.JevAction
import com.mobilerun.caragent.brain.jev.JevObservationFactory
import com.mobilerun.caragent.brain.jev.JevPolicy
import com.mobilerun.caragent.brain.jev.TypeSafeClient
import com.mobilerun.caragent.config.AgentPreferences
import com.mobilerun.caragent.device.AppLauncher
import com.mobilerun.caragent.device.PortalTools
import com.mobilerun.caragent.events.AgentEvent
import com.mobilerun.caragent.events.AgentEventBus
import com.mobilerun.caragent.policy.DrivingPolicyGate
import com.mobilerun.caragent.policy.RiskLevel
import com.mobilerun.caragent.state.ActionRecord
import com.mobilerun.caragent.state.CarAgentState
import com.mobilerun.caragent.vehicle.VehicleStateMonitor
import kotlinx.coroutines.delay

/**
 * Observe → TypeSafe Jev decide → Portal execute loop (mobile-jev style, local Portal).
 */
class JevAgent(
    private val context: Context,
    private val portal: PortalTools,
    private val state: CarAgentState,
    private val prefs: AgentPreferences,
    private val gate: DrivingPolicyGate,
    private val events: AgentEventBus,
    private val appLauncher: AppLauncher?,
    private val vehicleMonitor: VehicleStateMonitor,
) {
    suspend fun run() {
        if (prefs.requiresTypeSafeApiKey() && prefs.typeSafeApiKey.isBlank()) {
            state.finished = true
            state.success = false
            state.reason = "决策模型使用 TypeSafe 云端时需要 API key（配置 → Jev 凭据）"
            events.emit(AgentEvent.Log(state.reason))
            return
        }

        events.emit(
            AgentEvent.Log(
                "Jev System One endpoint=${prefs.typeSafeSystemOneUrl()} model=${prefs.typeSafeModel}",
            ),
        )
        val policy = JevPolicy(
            client = TypeSafeClient(
                apiKey = prefs.typeSafeApiKey,
                model = prefs.typeSafeModel,
                baseUrl = prefs.typeSafeBaseUrl,
            ),
            threshold = prefs.jevConfidenceThreshold.toDouble(),
        )
        val history = mutableListOf<HistoryItem>()
        val apps = listLauncherApps()

        while (!state.finished && state.stepNumber < state.maxSteps) {
            state.stepNumber += 1
            state.vehicleState = vehicleMonitor.current()
            val riskCheck = gate.check(state.riskLevel, state.vehicleState)
            if (!riskCheck.allowed && state.riskLevel != RiskLevel.LOW) {
                // Allow observation-only; block mutating steps below per-action.
                events.emit(AgentEvent.Log("policy: ${riskCheck.reason}"))
            }

            val portalState = try {
                portal.getState()
            } catch (t: Throwable) {
                state.finished = true
                state.success = false
                state.reason = "Portal get_state failed: ${t.message}"
                events.emit(AgentEvent.Log(state.reason))
                return
            }
            state.formattedDeviceState = portalState.rawJson.take(8000)
            state.currentPackageName = portalState.packageName
            val observation = JevObservationFactory.fromPortal(portalState)

            val decision = try {
                policy.decide(
                    goal = state.goal,
                    observation = observation,
                    history = history,
                    apps = apps,
                )
            } catch (t: Throwable) {
                state.finished = true
                state.success = false
                state.reason = "Jev decide failed: ${t.message}"
                events.emit(AgentEvent.Log(state.reason))
                return
            }

            events.emit(
                AgentEvent.Log(
                    "JEV step=${state.stepNumber} op=${decision.operation} " +
                        "status=${decision.status} conf=${"%.2f".format(decision.confidence)} " +
                        "label=${decision.label} ${decision.latencyMs}ms",
                ),
            )
            events.emit(AgentEvent.ManagerPlan("${decision.operation}: ${decision.label}"))

            when (decision.status) {
                "done" -> {
                    state.finished = true
                    state.success = true
                    state.reason = "Jev DONE"
                    return
                }
                "blocked", "uncertain", "needs_input" -> {
                    state.finished = true
                    state.success = false
                    state.reason = decision.reason ?: "Jev ${decision.status}"
                    return
                }
            }

            val action = decision.action
            if (action == null) {
                state.finished = true
                state.success = false
                state.reason = "Jev returned action status without action"
                return
            }

            val mutateRisk = when (action) {
                is JevAction.OpenApp -> RiskLevel.MEDIUM
                is JevAction.Wait -> RiskLevel.LOW
                else -> RiskLevel.HIGH
            }
            state.riskLevel = maxOfRisk(state.riskLevel, mutateRisk)
            val allowed = gate.check(state.riskLevel, state.vehicleState)
            if (!allowed.allowed) {
                state.finished = true
                state.success = false
                state.reason = allowed.reason
                events.emit(AgentEvent.Log(state.reason))
                return
            }

            val summary = try {
                execute(action)
            } catch (t: Throwable) {
                state.finished = true
                state.success = false
                state.reason = "execute failed: ${t.message}"
                events.emit(AgentEvent.Log(state.reason))
                return
            }

            state.recordAction(
                ActionRecord(decision.operation, mapOf("label" to decision.label), true, summary),
            )
            events.emit(
                AgentEvent.ToolExecution(decision.operation, mapOf("label" to decision.label), true, summary),
            )
            history += HistoryItem(
                operation = decision.operation,
                label = decision.label,
                text = (action as? JevAction.Type)?.text,
                screenChanged = action !is JevAction.Wait,
            )
            if (action is JevAction.Wait) {
                delay(800)
            } else {
                delay(250)
            }
        }

        if (!state.finished) {
            state.finished = true
            state.success = false
            state.reason = "max_steps reached"
        }
    }

    private fun execute(action: JevAction): String = when (action) {
        is JevAction.OpenApp -> {
            appLauncher?.open(action.packageName) ?: run {
                portal.openApp(action.packageName)
                "opened ${action.packageName}"
            }
        }
        is JevAction.TapElement -> {
            val node = portal.getState().nodes.firstOrNull { "n${it.index}" == action.elementId }
                ?: error("element ${action.elementId} not found")
            val (x, y) = PortalTools.boundsCenter(node.bounds) ?: error("no bounds for ${action.elementId}")
            portal.tap(x, y)
            "tap ${action.elementId} ($x,$y)"
        }
        is JevAction.Swipe -> {
            portal.swipe(action.startX, action.startY, action.endX, action.endY, action.duration)
            "swipe ${action.regionId}"
        }
        is JevAction.Type -> {
            portal.typeText(action.text, action.clear)
            "type ${action.text.length} chars"
        }
        is JevAction.Global -> {
            val code = when (action.name.lowercase()) {
                "back" -> 1
                "home" -> 2
                else -> error("unsupported global ${action.name}")
            }
            portal.globalAction(code)
            "global ${action.name}"
        }
        is JevAction.Key -> {
            if (action.key.equals("enter", true)) {
                portal.keyboardKey(PortalTools.KEYCODE_ENTER)
                "key enter"
            } else {
                error("unsupported key ${action.key}")
            }
        }
        JevAction.Wait -> "wait"
    }

    private fun listLauncherApps(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolves.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = try {
                info.loadLabel(pm)?.toString().orEmpty()
            } catch (_: Throwable) {
                pkg
            }
            InstalledApp(pkg, label.ifBlank { pkg })
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .take(200)
    }

    private fun maxOfRisk(a: RiskLevel, b: RiskLevel): RiskLevel {
        val order = listOf(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH)
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }
}
