package com.mobilerun.caragent.tools

import com.mobilerun.caragent.credentials.CredentialStore
import com.mobilerun.caragent.device.AppLauncher
import com.mobilerun.caragent.device.PortalTools
import com.mobilerun.caragent.events.AgentEvent
import com.mobilerun.caragent.events.AgentEventBus
import com.mobilerun.caragent.policy.DrivingPolicyGate
import com.mobilerun.caragent.state.ActionRecord
import com.mobilerun.caragent.state.CarAgentState
import kotlinx.coroutines.delay

class ToolRegistry(
    private val portal: PortalTools,
    private val state: CarAgentState,
    private val gate: DrivingPolicyGate,
    private val credentials: CredentialStore,
    private val events: AgentEventBus,
    private val appLauncher: AppLauncher? = null,
    private val customHandlers: Map<String, ToolHandler> = emptyMap(),
) {
    private val handlers = linkedMapOf<String, ToolHandler>()

    init {
        registerDefaults()
        customHandlers.forEach { (name, handler) -> handlers[name] = handler }
    }

    fun specs(): List<ToolSpec> = handlers.keys.map { ToolSpec(it, "tool:$it") }

    suspend fun dispatch(call: ToolCall): ToolResult {
        val risk = gate.riskForTool(call.name)
        state.riskLevel = risk
        val decision = gate.check(risk, state.vehicleState)
        if (!decision.allowed) {
            val blocked = ToolResult(false, decision.reason)
            emit(call, blocked)
            return blocked
        }

        // Only gate interactive input/clicks — never block complete/open_app/get_state.
        val credentialSensitive = call.name in setOf("click", "click_at", "long_press", "type", "type_secret", "swipe")
        if (credentialSensitive &&
            call.name != "type_secret" &&
            credentials.get("AUTO_CONTINUE") == null &&
            credentials.needsUserForCredentialScreen(state.formattedDeviceState)
        ) {
            credentials.requestUser("Credential / payment screen detected. Authorize to continue.")
            val paused = ToolResult(false, credentials.pendingUserPrompt ?: "credential gate")
            emit(call, paused)
            return paused
        }

        val handler = handlers[call.name]
            ?: return ToolResult(false, "unknown tool: ${call.name}").also { emit(call, it) }

        val result = try {
            handler.invoke(call.args)
        } catch (t: Throwable) {
            ToolResult(false, t.message ?: "tool failed")
        }
        emit(call, result)
        return result
    }

    private fun emit(call: ToolCall, result: ToolResult) {
        state.recordAction(ActionRecord(call.name, call.args, result.success, result.summary))
        events.emit(AgentEvent.ToolExecution(call.name, call.args, result.success, result.summary))
    }

    private fun registerDefaults() {
        handlers["get_state"] = ToolHandler {
            val portalState = portal.getState()
            state.formattedDeviceState = portalState.rawJson.take(8000)
            state.currentPackageName = portalState.packageName
            ToolResult(true, "state package=${portalState.packageName} nodes=${portalState.nodes.size}", portalState)
        }
        handlers["click"] = ToolHandler { args ->
            val index = args.int("index")
            val portalState = portal.getState()
            val node = portalState.nodes.firstOrNull { it.index == index }
                ?: return@ToolHandler ToolResult(false, "node $index not found")
            val (x, y) = boundsCenter(node.bounds) ?: return@ToolHandler ToolResult(false, "no bounds")
            portal.tap(x, y)
            ToolResult(true, "clicked index=$index at ($x,$y)")
        }
        handlers["click_at"] = ToolHandler { args ->
            val x = args.int("x")
            val y = args.int("y")
            portal.tap(x, y)
            ToolResult(true, "clicked at ($x,$y)")
        }
        handlers["long_press"] = ToolHandler { args ->
            val x = args.int("x")
            val y = args.int("y")
            portal.swipe(x, y, x, y, 800)
            ToolResult(true, "long_press at ($x,$y)")
        }
        handlers["swipe"] = ToolHandler { args ->
            portal.swipe(args.int("startX"), args.int("startY"), args.int("endX"), args.int("endY"), args.intOr("duration", 400))
            ToolResult(true, "swiped")
        }
        handlers["type"] = ToolHandler { args ->
            val text = args.string("text")
            portal.typeText(text, args.boolOr("clear", true))
            ToolResult(true, "typed ${text.length} chars")
        }
        handlers["type_secret"] = ToolHandler { args ->
            val id = args.string("secret_id")
            val secret = credentials.get(id) ?: return@ToolHandler ToolResult(false, "unknown secret id")
            portal.typeText(secret, clear = true)
            ToolResult(true, "typed secret:$id")
        }
        handlers["system_button"] = ToolHandler { args ->
            val button = args.string("button").lowercase()
            val action = when (button) {
                "back" -> 1
                "home" -> 2
                "recents" -> 3
                else -> return@ToolHandler ToolResult(false, "unsupported button $button")
            }
            portal.globalAction(action)
            ToolResult(true, "system_button=$button")
        }
        handlers["wait"] = ToolHandler { args ->
            delay((args.doubleOr("duration", 1.0) * 1000).toLong())
            ToolResult(true, "waited")
        }
        handlers["open_app"] = ToolHandler { args ->
            val pkg = args.string("package").ifBlank { args.string("text") }
            val summary = appLauncher?.open(pkg) ?: run {
                portal.openApp(pkg)
                "opened $pkg"
            }
            ToolResult(true, summary)
        }
        handlers["take_screenshot"] = ToolHandler {
            val bytes = portal.screenshotBytes()
            ToolResult(true, "screenshot ${bytes.size} bytes", bytes)
        }
        handlers["complete"] = ToolHandler { args ->
            state.finished = true
            state.success = args.boolOr("success", true)
            state.reason = args.string("message").ifBlank { if (state.success) "done" else "failed" }
            ToolResult(state.success, state.reason)
        }
    }

    private fun boundsCenter(bounds: String?): Pair<Int, Int>? {
        if (bounds.isNullOrBlank()) return null
        val nums = Regex("-?\\d+").findAll(bounds).map { it.value.toInt() }.toList()
        if (nums.size < 4) return null
        val left = nums[0]
        val top = nums[1]
        val right = nums[2]
        val bottom = nums[3]
        return ((left + right) / 2) to ((top + bottom) / 2)
    }

    private fun Map<String, Any?>.string(key: String): String = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.int(key: String): Int = this[key].toString().toDouble().toInt()
    private fun Map<String, Any?>.intOr(key: String, default: Int): Int =
        this[key]?.toString()?.toDoubleOrNull()?.toInt() ?: default
    private fun Map<String, Any?>.doubleOr(key: String, default: Double): Double =
        this[key]?.toString()?.toDoubleOrNull() ?: default
    private fun Map<String, Any?>.boolOr(key: String, default: Boolean): Boolean =
        when (val v = this[key]) {
            is Boolean -> v
            null -> default
            else -> v.toString().equals("true", true)
        }
}
