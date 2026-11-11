package com.mobilerun.caragent.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.mobilerun.caragent.CarAgentApplication
import com.mobilerun.caragent.agent.car.CarAgent
import com.mobilerun.caragent.config.AgentRunPath
import com.mobilerun.caragent.config.BrainMode
import com.mobilerun.caragent.databinding.ActivityMainBinding
import com.mobilerun.caragent.device.PortalTools
import com.mobilerun.caragent.events.AgentEvent
import com.mobilerun.caragent.policy.RiskLevel
import com.mobilerun.caragent.queue.QueuedTask
import com.mobilerun.caragent.queue.TaskQueue
import com.mobilerun.caragent.vehicle.MockVehiclePreset
import com.mobilerun.caragent.vehicle.VehicleStateMonitor
import com.mobilerun.caragent.voice.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechInput: SpeechInput
    private val taskQueue = TaskQueue()
    private var carAgent: CarAgent? = null
    private val logLines = ArrayDeque<String>()

    private val eventListener: (AgentEvent) -> Unit = { event ->
        runOnUiThread {
            when (event) {
                is AgentEvent.Log -> appendLog(event.message)
                is AgentEvent.ManagerPlan -> appendLog("PLAN: ${event.plan.take(300)}")
                is AgentEvent.ToolExecution -> appendLog("TOOL ${event.toolName} ok=${event.success} ${event.summary}")
                is AgentEvent.Result -> appendLog("RESULT success=${event.success} steps=${event.steps} ${event.reason}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        speechInput = SpeechInput(this)

        val prefs = (application as CarAgentApplication).preferences
        binding.vehicleMockSwitch.isChecked = prefs.mockVehicle
        when (prefs.runPath) {
            AgentRunPath.FAST -> binding.modeFast.isChecked = true
            AgentRunPath.REASONING -> binding.modeReasoning.isChecked = true
            AgentRunPath.JEV -> binding.modeJev.isChecked = true
        }
        selectPresetChip(MockVehiclePreset.from(prefs.mockGear, prefs.mockSpeedKph) ?: MockVehiclePreset.PARKED)
        updateMockPresetEnabled(prefs.mockVehicle)
        refreshVehicleLabel()

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        binding.modeGroup.setOnCheckedChangeListener { _, _ ->
            prefs.runPath = currentRunPath()
        }
        binding.vehicleMockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.mockVehicle = checked
            updateMockPresetEnabled(checked)
            refreshVehicleLabel()
        }
        binding.mockPresetGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(chipId) ?: return@setOnCheckedStateChangeListener
            val preset = presetForChip(chip.id) ?: return@setOnCheckedStateChangeListener
            prefs.setMockScenario(preset.gear, preset.speedKph)
            refreshVehicleLabel()
        }

        binding.btnListen.setOnClickListener {
            appendLog("listening…")
            speechInput.start(
                onResult = { text ->
                    binding.goalInput.setText(text)
                    appendLog("STT: $text")
                },
                onError = { err -> appendLog(err) },
            )
        }

        binding.btnRun.setOnClickListener { startGoal() }
        binding.btnStop.setOnClickListener {
            carAgent?.stop()
            taskQueue.clear()
            appendLog("stop requested")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVehicleLabel()
    }

    private fun startGoal() {
        val prefs = (application as CarAgentApplication).preferences
        val goal = binding.goalInput.text?.toString().orEmpty().trim()
        if (goal.isBlank()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            return
        }
        val risk = inferRisk(goal)
        val task = QueuedTask(goal, risk)
        if (!taskQueue.tryStart(task)) {
            appendLog("高风险任务排队中（已有 GUI 任务在执行）")
            return
        }

        val portal = PortalTools(prefs.portalUrl, prefs.portalToken)
        val monitor = VehicleStateMonitor(
            this,
            useMock = { prefs.mockVehicle },
            mockGear = { prefs.mockGear },
            mockSpeedKph = { prefs.mockSpeedKph },
        )
        carAgent?.events?.unsubscribe(eventListener)
        carAgent?.dispose()
        val agent = CarAgent(
            context = this,
            portal = portal,
            vehicleMonitor = monitor,
            prefs = prefs,
        )
        carAgent = agent
        agent.events.subscribe(eventListener)

        val runPath = currentRunPath()
        prefs.runPath = runPath

        appendLog("running: $goal runPath=$runPath brain=${prefs.brainMode}")
        if (runPath == AgentRunPath.JEV &&
            prefs.requiresTypeSafeApiKey() &&
            prefs.typeSafeApiKey.isBlank()
        ) {
            appendLog("错误：决策模型使用 TypeSafe 云端时需要 API key，请到「配置」填写")
            if (risk == RiskLevel.HIGH) taskQueue.finishHighRisk()
            return
        }
        if (runPath == AgentRunPath.JEV && prefs.typeSafeBaseUrl.isBlank()) {
            appendLog("错误：决策模型未配置 System One base URL")
            if (risk == RiskLevel.HIGH) taskQueue.finishHighRisk()
            return
        }
        if (runPath != AgentRunPath.JEV &&
            prefs.brainMode == BrainMode.OPENAI &&
            (prefs.openaiApiKey.isBlank() || prefs.openaiBaseUrl.isBlank())
        ) {
            appendLog("错误：LLM 后端=OpenAI 但未配置 base URL / API key")
            if (risk == RiskLevel.HIGH) taskQueue.finishHighRisk()
            return
        }
        agent.runAsync(
            goal = goal,
            runPath = runPath,
            initialRisk = risk,
        ) { result ->
            lifecycleScope.launch {
                withContext(Dispatchers.Main) {
                    if (risk == RiskLevel.HIGH) {
                        taskQueue.finishHighRisk()
                        taskQueue.pollNextHighRisk()?.let {
                            binding.goalInput.setText(it.goal)
                            startGoal()
                        }
                    }
                    appendLog("finished success=${result.success}")
                }
            }
        }
    }

    private fun currentRunPath(): AgentRunPath = when {
        binding.modeReasoning.isChecked -> AgentRunPath.REASONING
        binding.modeJev.isChecked -> AgentRunPath.JEV
        else -> AgentRunPath.FAST
    }

    private fun inferRisk(goal: String): RiskLevel {
        val g = goal.lowercase()
        return when {
            listOf("youtube", "商品", "官网", "生活", "网页", "截图").any { it in g } -> RiskLevel.HIGH
            listOf("设置", "settings").any { it in g } -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun refreshVehicleLabel() {
        val prefs = (application as CarAgentApplication).preferences
        val monitor = VehicleStateMonitor(
            this,
            { prefs.mockVehicle },
            { prefs.mockGear },
            { prefs.mockSpeedKph },
        )
        binding.vehicleStatus.text = "当前: ${monitor.current().summary()}"
    }

    private fun updateMockPresetEnabled(enabled: Boolean) {
        binding.mockPresetGroup.isEnabled = enabled
        for (i in 0 until binding.mockPresetGroup.childCount) {
            binding.mockPresetGroup.getChildAt(i).isEnabled = enabled
        }
    }

    private fun selectPresetChip(preset: MockVehiclePreset) {
        val chipId = when (preset) {
            MockVehiclePreset.PARKED -> binding.mockParked.id
            MockVehiclePreset.REVERSE -> binding.mockReverse.id
            MockVehiclePreset.NEUTRAL -> binding.mockNeutral.id
            MockVehiclePreset.CITY -> binding.mockCity.id
            MockVehiclePreset.URBAN -> binding.mockUrban.id
            MockVehiclePreset.HIGHWAY -> binding.mockHighway.id
        }
        binding.mockPresetGroup.check(chipId)
    }

    private fun presetForChip(chipId: Int): MockVehiclePreset? = when (chipId) {
        binding.mockParked.id -> MockVehiclePreset.PARKED
        binding.mockReverse.id -> MockVehiclePreset.REVERSE
        binding.mockNeutral.id -> MockVehiclePreset.NEUTRAL
        binding.mockCity.id -> MockVehiclePreset.CITY
        binding.mockUrban.id -> MockVehiclePreset.URBAN
        binding.mockHighway.id -> MockVehiclePreset.HIGHWAY
        else -> null
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 300) logLines.removeFirst()
        binding.eventLog.text = logLines.joinToString("\n")
    }

    override fun onDestroy() {
        speechInput.stop()
        carAgent?.events?.unsubscribe(eventListener)
        carAgent?.dispose()
        super.onDestroy()
    }
}
