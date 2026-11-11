package com.carrun.caragent.agent.car

import android.content.Context
import com.carrun.caragent.agent.PromptLoader
import com.carrun.caragent.agent.executor.ExecutorAgent
import com.carrun.caragent.agent.fast.FastAgent
import com.carrun.caragent.agent.jev.JevAgent
import com.carrun.caragent.agent.manager.ManagerAgent
import com.carrun.caragent.cards.AppCardLoader
import com.carrun.caragent.config.AgentPreferences
import com.carrun.caragent.config.AgentRunPath
import com.carrun.caragent.credentials.CredentialStore
import com.carrun.caragent.device.AppLauncher
import com.carrun.caragent.device.PortalTools
import com.carrun.caragent.events.AgentEvent
import com.carrun.caragent.events.AgentEventBus
import com.carrun.caragent.policy.DrivingPolicyGate
import com.carrun.caragent.policy.RiskLevel
import com.carrun.caragent.router.ExecutionMode
import com.carrun.caragent.router.LlmProfileRouter
import com.carrun.caragent.state.CarAgentState
import com.carrun.caragent.tools.ToolRegistry
import com.carrun.caragent.usecases.UseCaseTools
import com.carrun.caragent.vehicle.VehicleStateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Orchestrator aligned with carrun MobileAgent.
 */
class CarAgent(
    private val context: Context,
    private val portal: PortalTools,
    private val vehicleMonitor: VehicleStateMonitor,
    private val prefs: AgentPreferences,
    private val router: LlmProfileRouter = LlmProfileRouter(prefs),
    private val gate: DrivingPolicyGate = DrivingPolicyGate(),
    private val credentials: CredentialStore = CredentialStore(),
    val events: AgentEventBus = AgentEventBus(),
    private val appCards: AppCardLoader = AppCardLoader(context),
    private val prompts: PromptLoader = PromptLoader(context),
) {
    private val appLauncher = AppLauncher(portal)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val inbox = ConcurrentLinkedQueue<String>()
    @Volatile
    var running: Boolean = false
        private set

    fun sendUserMessage(message: String) {
        inbox.add(message)
        events.emit(AgentEvent.Log("queued user message: $message"))
    }

    fun stop() {
        job?.cancel()
        running = false
        events.emit(AgentEvent.Log("stopped"))
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    fun runAsync(
        goal: String,
        runPath: AgentRunPath = prefs.runPath,
        maxSteps: Int = 15,
        initialRisk: RiskLevel = RiskLevel.LOW,
        onFinished: ((AgentEvent.Result) -> Unit)? = null,
    ) {
        stop()
        running = true
        job = scope.launch {
            val result = run(goal, runPath, maxSteps, initialRisk)
            onFinished?.invoke(result)
            running = false
        }
    }

    suspend fun run(
        goal: String,
        runPath: AgentRunPath = prefs.runPath,
        maxSteps: Int = 15,
        initialRisk: RiskLevel = RiskLevel.LOW,
    ): AgentEvent.Result {
        val mode = ExecutionMode.AUTO
        val state = CarAgentState(
            goal = goal,
            maxSteps = maxSteps,
            executionMode = mode,
            reasoning = runPath == AgentRunPath.REASONING,
            riskLevel = initialRisk,
            vehicleState = vehicleMonitor.current(),
        )
        events.emit(
            AgentEvent.Log(
                "start goal=$goal runPath=$runPath brain=${prefs.brainMode} ${state.vehicleState.summary()}",
            ),
        )

        // Bootstrap state when portal is reachable.
        try {
            if (portal.ping()) {
                val portalState = portal.getState()
                state.formattedDeviceState = portalState.rawJson.take(8000)
                state.currentPackageName = portalState.packageName
                state.appCard = appCards.load(state.currentPackageName)
            } else {
                events.emit(AgentEvent.Log("Portal ping failed — continuing with limited tools"))
            }
        } catch (t: Throwable) {
            events.emit(AgentEvent.Log("Portal bootstrap error: ${t.message}"))
        }

        inferRisk(goal, state)

        when (runPath) {
            AgentRunPath.JEV -> {
                drainInbox(state)
                state.vehicleState = vehicleMonitor.current()
                JevAgent(
                    context = context,
                    portal = portal,
                    state = state,
                    prefs = prefs,
                    gate = gate,
                    events = events,
                    appLauncher = appLauncher,
                    vehicleMonitor = vehicleMonitor,
                ).run()
            }
            AgentRunPath.REASONING -> {
                val useCaseTools = UseCaseTools(context, portal, state, events, appLauncher)
                val tools = ToolRegistry(
                    portal = portal,
                    state = state,
                    gate = gate,
                    credentials = credentials,
                    events = events,
                    appLauncher = appLauncher,
                    customHandlers = useCaseTools.handlers(),
                )
                val manager = ManagerAgent(
                    state,
                    router,
                    events,
                    prompts.load("manager_system.txt", "You are ManagerAgent."),
                )
                val executor = ExecutorAgent(
                    state,
                    tools,
                    router,
                    events,
                    prompts.load("executor_system.txt", "You are ExecutorAgent."),
                )
                while (!state.finished && state.stepNumber < state.maxSteps) {
                    drainInbox(state)
                    state.vehicleState = vehicleMonitor.current()
                    state.stepNumber += 1
                    val plan = manager.plan()
                    if (plan.done || state.finished) break
                    executor.execute(plan)
                    state.appCard = appCards.load(state.currentPackageName) ?: state.appCard
                }
            }
            AgentRunPath.FAST -> {
                val useCaseTools = UseCaseTools(context, portal, state, events, appLauncher)
                val tools = ToolRegistry(
                    portal = portal,
                    state = state,
                    gate = gate,
                    credentials = credentials,
                    events = events,
                    appLauncher = appLauncher,
                    customHandlers = useCaseTools.handlers(),
                )
                val fast = FastAgent(
                    state,
                    tools,
                    router,
                    events,
                    prompts.load("fast_agent_system.txt", "You are FastAgent."),
                )
                state.vehicleState = vehicleMonitor.current()
                drainInbox(state)
                fast.run()
            }
        }

        if (!state.finished) {
            state.finished = true
            state.success = false
            state.reason = "max_steps reached"
        }

        val result = AgentEvent.Result(state.success, state.reason, state.stepNumber, state.structuredOutput)
        events.emit(result)
        return result
    }

    private fun drainInbox(state: CarAgentState) {
        while (true) {
            val msg = inbox.poll() ?: break
            state.remember("user:$msg")
            state.goal = "$msg (updated from: ${state.goal})"
            events.emit(AgentEvent.Log("applied user message"))
        }
    }

    private fun inferRisk(goal: String, state: CarAgentState) {
        val g = goal.lowercase()
        state.riskLevel = when {
            listOf("youtube", "商品", "官网", "生活", "browser", "网页", "截图").any { it in g } -> RiskLevel.HIGH
            listOf("设置", "settings").any { it in g } && listOf("打开", "open", "改", "切换").any { it in g } -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
}
