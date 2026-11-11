package com.carrun.caragent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.carrun.caragent.CarAgentApplication
import com.carrun.caragent.R
import com.carrun.caragent.config.AgentPreferences
import com.carrun.caragent.config.BrainMode
import com.carrun.caragent.config.SystemOneEndpoint
import com.carrun.caragent.databinding.ActivitySetupBinding
import com.carrun.caragent.device.PortalTools

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private var applyingDecisionPreset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = (application as CarAgentApplication).preferences
        binding.portalUrl.setText(prefs.portalUrl)
        binding.portalToken.setText(prefs.portalToken)
        binding.openaiBaseUrl.setText(prefs.openaiBaseUrl)
        binding.openaiApiKey.setText(prefs.openaiApiKey)
        binding.openaiModel.setText(prefs.openaiModel)
        binding.typeSafeBaseUrl.setText(prefs.typeSafeBaseUrl)
        binding.typeSafeApiKey.setText(prefs.typeSafeApiKey)
        binding.typeSafeModel.setText(prefs.typeSafeModel)
        binding.jevConfidence.setText(prefs.jevConfidenceThreshold.toString())
        binding.typeSafeApiKeyStatus.text = when {
            prefs.typeSafeApiKey.isBlank() -> "未配置 API key"
            else -> "已配置（${prefs.typeSafeApiKey.length} 字符，以 ${prefs.typeSafeApiKey.take(7)} 开头）"
        }

        when (prefs.brainMode) {
            BrainMode.HEURISTIC -> binding.brainHeuristic.isChecked = true
            BrainMode.OPENAI -> binding.brainOpenai.isChecked = true
        }
        when (prefs.systemOneEndpoint) {
            SystemOneEndpoint.CLOUD -> binding.decisionCloud.isChecked = true
            SystemOneEndpoint.LAYA -> binding.decisionLaya.isChecked = true
            SystemOneEndpoint.CUSTOM -> binding.decisionCustom.isChecked = true
        }
        updateBrainFieldsVisibility()
        updateDecisionFieldsVisibility(applyDefaults = false)

        binding.btnBack.setOnClickListener { finish() }

        binding.brainGroup.setOnCheckedChangeListener { _, _ ->
            updateBrainFieldsVisibility()
        }
        binding.decisionGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            updateDecisionFieldsVisibility(applyDefaults = true)
        }

        binding.btnDiscoverToken.setOnClickListener {
            val tools = PortalTools(
                binding.portalUrl.text?.toString().orEmpty().ifBlank { prefs.portalUrl },
                prefs.portalToken,
            )
            val token = tools.discoverAuthTokenViaContent(this)
                ?: PortalTools.unwrapAuthTokenColumn(binding.portalToken.text?.toString())
            if (token.isNullOrBlank()) {
                binding.setupStatus.text = "未读到 token，请确认 Portal 已安装并开启。"
            } else {
                binding.portalToken.setText(token)
                prefs.portalToken = token
                binding.setupStatus.text = "已写入 token，点「保存」一并持久化其余项。"
            }
        }

        binding.btnRequestPermissions.setOnClickListener {
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                binding.setupStatus.text = "本机权限已齐。无障碍与 HTTP 请在 Portal 开启。"
            } else {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
            }
        }

        binding.btnSaveAll.setOnClickListener { saveAll() }
    }

    private fun saveAll() {
        val prefs = (application as CarAgentApplication).preferences
        val endpoint = currentDecisionEndpoint()

        prefs.portalUrl = binding.portalUrl.text?.toString().orEmpty()
        prefs.portalToken = binding.portalToken.text?.toString().orEmpty()

        prefs.brainMode = currentBrainMode()
        prefs.openaiBaseUrl = binding.openaiBaseUrl.text?.toString().orEmpty()
            .ifBlank { AgentPreferences.DEFAULT_OPENAI_BASE_URL }
        prefs.openaiApiKey = binding.openaiApiKey.text?.toString().orEmpty()
        prefs.openaiModel = binding.openaiModel.text?.toString().orEmpty()
            .ifBlank { AgentPreferences.DEFAULT_OPENAI_MODEL }

        prefs.systemOneEndpoint = endpoint
        when (endpoint) {
            SystemOneEndpoint.CLOUD -> {
                prefs.typeSafeBaseUrl = AgentPreferences.DEFAULT_TYPESAFE_BASE_URL
                prefs.typeSafeModel = binding.typeSafeModel.text?.toString().orEmpty()
                    .ifBlank { AgentPreferences.DEFAULT_TYPESAFE_MODEL }
            }
            SystemOneEndpoint.LAYA -> {
                prefs.typeSafeBaseUrl = binding.typeSafeBaseUrl.text?.toString().orEmpty()
                    .ifBlank { AgentPreferences.LOCAL_LAYA_BASE_URL }
                prefs.typeSafeModel = binding.typeSafeModel.text?.toString().orEmpty()
                    .ifBlank { AgentPreferences.LOCAL_LAYA_MODEL }
            }
            SystemOneEndpoint.CUSTOM -> {
                prefs.typeSafeBaseUrl = binding.typeSafeBaseUrl.text?.toString().orEmpty()
                    .ifBlank { AgentPreferences.DEFAULT_TYPESAFE_BASE_URL }
                prefs.typeSafeModel = binding.typeSafeModel.text?.toString().orEmpty()
                    .ifBlank { AgentPreferences.DEFAULT_TYPESAFE_MODEL }
            }
        }
        prefs.typeSafeApiKey = binding.typeSafeApiKey.text?.toString().orEmpty()
        prefs.jevConfidenceThreshold =
            binding.jevConfidence.text?.toString()?.toFloatOrNull() ?: 0f

        // Keep UI fields in sync with what was persisted (e.g. cloud base URL).
        binding.typeSafeBaseUrl.setText(prefs.typeSafeBaseUrl)
        binding.typeSafeModel.setText(prefs.typeSafeModel)

        binding.setupStatus.text = "已保存"
    }

    private fun currentBrainMode(): BrainMode =
        if (binding.brainOpenai.isChecked) BrainMode.OPENAI else BrainMode.HEURISTIC

    private fun currentDecisionEndpoint(): SystemOneEndpoint = when {
        binding.decisionLaya.isChecked -> SystemOneEndpoint.LAYA
        binding.decisionCustom.isChecked -> SystemOneEndpoint.CUSTOM
        else -> SystemOneEndpoint.CLOUD
    }

    private fun updateBrainFieldsVisibility() {
        val mode = currentBrainMode()
        binding.openaiFields.visibility =
            if (mode == BrainMode.OPENAI) View.VISIBLE else View.GONE
        binding.brainModeHint.setText(
            when (mode) {
                BrainMode.OPENAI -> R.string.brain_hint_openai
                BrainMode.HEURISTIC -> R.string.brain_hint_heuristic
            },
        )
    }

    private fun updateDecisionFieldsVisibility(applyDefaults: Boolean) {
        if (applyingDecisionPreset) return
        val endpoint = currentDecisionEndpoint()
        binding.decisionHint.setText(
            when (endpoint) {
                SystemOneEndpoint.CLOUD -> R.string.decision_hint_cloud
                SystemOneEndpoint.LAYA -> R.string.decision_hint_laya
                SystemOneEndpoint.CUSTOM -> R.string.decision_hint_custom
            },
        )

        val showBaseUrl = endpoint != SystemOneEndpoint.CLOUD
        binding.typeSafeBaseUrl.visibility = if (showBaseUrl) View.VISIBLE else View.GONE

        if (!applyDefaults) return
        applyingDecisionPreset = true
        try {
            when (endpoint) {
                SystemOneEndpoint.CLOUD -> {
                    binding.typeSafeBaseUrl.setText(AgentPreferences.DEFAULT_TYPESAFE_BASE_URL)
                    if (binding.typeSafeModel.text.isNullOrBlank() ||
                        binding.typeSafeModel.text?.toString() == AgentPreferences.LOCAL_LAYA_MODEL
                    ) {
                        binding.typeSafeModel.setText(AgentPreferences.DEFAULT_TYPESAFE_MODEL)
                    }
                }
                SystemOneEndpoint.LAYA -> {
                    val current = binding.typeSafeBaseUrl.text?.toString().orEmpty()
                    if (current.isBlank() ||
                        current.contains("api.typesafe.ai", ignoreCase = true)
                    ) {
                        binding.typeSafeBaseUrl.setText(AgentPreferences.LOCAL_LAYA_BASE_URL)
                    }
                    if (binding.typeSafeModel.text.isNullOrBlank() ||
                        binding.typeSafeModel.text?.toString() == AgentPreferences.DEFAULT_TYPESAFE_MODEL
                    ) {
                        binding.typeSafeModel.setText(AgentPreferences.LOCAL_LAYA_MODEL)
                    }
                }
                SystemOneEndpoint.CUSTOM -> Unit
            }
        } finally {
            applyingDecisionPreset = false
        }
    }
}
