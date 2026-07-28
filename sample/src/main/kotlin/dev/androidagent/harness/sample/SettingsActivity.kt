// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import dev.androidagent.harness.deviceloop.android.AccessibilityAvailability
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private lateinit var providerSettings: ProviderSettingsRepository
    private lateinit var providerStatus: TextView
    private lateinit var approvalStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var dataStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerSettings = ProviderSettingsRepository(this)
        setContentView(R.layout.activity_settings)
        applySampleInsets(findViewById(R.id.settingsContent))

        providerStatus = findViewById(R.id.settingsProviderStatus)
        approvalStatus = findViewById(R.id.settingsApprovalStatus)
        accessibilityStatus = findViewById(R.id.settingsAccessibilityStatus)
        dataStatus = findViewById(R.id.settingsDataStatus)

        findViewById<Button>(R.id.settingsBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.configureProviderButton).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_PROVIDER_SETTINGS, true)
            )
        }
        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(AccessibilityAvailability.settingsIntent())
        }
        findViewById<Button>(R.id.configureApprovalModeButton).setOnClickListener {
            showApprovalModeDialog()
        }
        findViewById<Button>(R.id.settingsHouseButton).setOnClickListener {
            startActivity(Intent(this, AgentHouseActivity::class.java))
        }
        findViewById<Button>(R.id.settingsControlCenterButton).setOnClickListener {
            startActivity(Intent(this, ProductCenterActivity::class.java))
        }
        findViewById<Button>(R.id.clearSessionsButton).setOnClickListener {
            confirmClearSessions()
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    override fun onDestroy() {
        diskExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun renderState() {
        val profile = providerSettings.profile()
        val credential = when (profile.kind.credentialMode) {
            ProviderCredentialMode.NONE -> "无需账号"
            ProviderCredentialMode.CODEX_LOGIN -> {
                if (CodexAuthRepository(this).getProfile() == null) {
                    "尚未登录 ChatGPT"
                } else {
                    "ChatGPT 已登录"
                }
            }
            ProviderCredentialMode.API_KEY -> {
                if (providerSettings.hasSecret(profile.kind)) {
                    "API Key 已加密保存"
                } else {
                    "尚未配置 API Key"
                }
            }
        }
        providerStatus.text = "${profile.kind.title} · ${profile.kind.modelLabel(profile.model)}\n$credential"
        val approvalMode = SampleRuntime.approvalMode()
        approvalStatus.text = "${approvalMode.title}\n${approvalMode.description}"
        accessibilityStatus.text = if (AccessibilityAvailability.isServiceEnabled(this)) {
            when (approvalMode) {
                SampleApprovalMode.NONE ->
                    "● 已开启。模型按需调用手机工具；设备操作不会弹出审批。"
                SampleApprovalMode.RISK_BASED ->
                    "● 已开启。模型按需调用手机工具；高风险动作需要你确认。"
                SampleApprovalMode.STRICT ->
                    "● 已开启。模型按需调用手机工具；每个 Phone Use 操作都需要你确认。"
            }
        } else {
            "○ 未开启。模型若请求 Phone Use 会收到权限提示；普通对话与 Agent House 不受影响。"
        }
        diskExecutor.execute {
            val count = SampleRuntime.sessions(this).listSessions().size
            mainHandler.post {
                if (!isDestroyed && !isFinishing) {
                    dataStatus.text = "$count 个会话保存在应用私有目录；停止或失败的本轮不会写入历史。"
                }
            }
        }
    }

    private fun showApprovalModeDialog() {
        val modes = SampleApprovalMode.entries
        var selectedIndex = modes.indexOf(SampleRuntime.approvalMode()).coerceAtLeast(0)
        val choices = modes.map { mode ->
            "${mode.title}\n${mode.description}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("审批模式")
            .setSingleChoiceItems(choices, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("应用") { _, _ ->
                val selected = modes[selectedIndex]
                SampleRuntime.setApprovalMode(this, selected)
                Toast.makeText(
                    this,
                    "已切换为${selected.title.removeSuffix("（默认）")}",
                    Toast.LENGTH_SHORT
                ).show()
                renderState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearSessions() {
        AlertDialog.Builder(this)
            .setTitle("清除全部会话？")
            .setMessage("这会删除应用私有目录里的聊天历史，但不会清除模型账号或 Agent House。")
            .setPositiveButton("清除") { _, _ ->
                diskExecutor.execute {
                    val deleted = SampleRuntime.sessions(this).clearSessions()
                    mainHandler.post {
                        if (!isDestroyed && !isFinishing) {
                            Toast.makeText(
                                this,
                                "已清除 $deleted 个会话",
                                Toast.LENGTH_SHORT
                            ).show()
                            renderState()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
