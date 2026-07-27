// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.harness.deviceloop.android.AccessibilityAvailability
import dev.androidagent.harness.sdk.AgentSessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HomeActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newSingleThreadExecutor()

    private lateinit var providerStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var recentSessions: LinearLayout
    private lateinit var preferences: SamplePreferences
    private lateinit var providerSettings: ProviderSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = SamplePreferences(this)
        providerSettings = ProviderSettingsRepository(this)
        setContentView(R.layout.activity_home)
        applySampleInsets(findViewById(R.id.homeContent))

        providerStatus = findViewById(R.id.homeProviderStatus)
        runtimeStatus = findViewById(R.id.homeRuntimeStatus)
        recentSessions = findViewById(R.id.recentSessionsContainer)
        findViewById<Button>(R.id.continueChatButton).setOnClickListener {
            openChat(preferences.lastSessionId())
        }
        findViewById<Button>(R.id.homeHouseButton).apply {
            removeClippedShadow()
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, AgentHouseActivity::class.java))
            }
        }
        findViewById<Button>(R.id.homeSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.homeModelButton).apply {
            removeClippedShadow()
            setOnClickListener {
                startActivity(
                    Intent(this@HomeActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_PROVIDER_SETTINGS, true)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderProvider()
        loadLocalState()
    }

    override fun onDestroy() {
        diskExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun renderProvider() {
        val profile = providerSettings.profile()
        val readiness = when (profile.kind.credentialMode) {
            ProviderCredentialMode.NONE -> "离线演示可用"
            ProviderCredentialMode.CODEX_LOGIN -> {
                val auth = CodexAuthRepository(this).getProfile()
                if (auth == null) "Codex 尚未登录" else "Codex 已登录"
            }
            ProviderCredentialMode.API_KEY -> {
                if (providerSettings.hasSecret(profile.kind)) {
                    "API Key 已加密保存"
                } else {
                    "尚未配置 API Key"
                }
            }
        }
        providerStatus.text = "${profile.kind.title} · ${profile.kind.modelLabel(profile.model)}\n" +
            "$readiness · Phone Use 按需进入"
    }

    private fun loadLocalState() {
        runtimeStatus.text = "正在读取本地状态…"
        diskExecutor.execute {
            val summaries = SampleRuntime.sessions(this).listSessions().take(5)
            val house = SampleRuntime.house(this).snapshot()
            val accessibility = AccessibilityAvailability.isServiceEnabled(this)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                runtimeStatus.text = buildString {
                    append(if (accessibility) "● 无障碍已开启" else "○ 无障碍未开启")
                    append("  ·  ")
                    append(house.profile.name)
                    append("\n")
                    append("${house.coreFiles.size} 个核心文件 · ")
                    append("${house.skills.count { skill -> skill.enabled }} 个技能启用 · ")
                    append("${house.dailyMemories.size} 篇每日记忆")
                }
                renderRecentSessions(summaries)
            }
        }
    }

    private fun renderRecentSessions(items: List<AgentSessionSummary>) {
        recentSessions.removeAllViews()
        if (items.isEmpty()) {
            recentSessions.addView(
                TextView(this).apply {
                    text = "还没有已保存的会话。开始一次对话后会显示在这里。"
                    textSize = 13f
                    setTextColor(getColor(R.color.textSecondary))
                    setPadding(dp(16), dp(15), dp(16), dp(15))
                    background = getDrawable(R.drawable.bg_surface_soft)
                }
            )
            return
        }
        items.forEachIndexed { index, summary ->
            val button = Button(this).apply {
                removeClippedShadow()
                text = "${summary.title}\n" +
                    "${formatTime(summary.updatedAtEpochMillis)} · ${summary.messageCount} 条消息"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 13f
                setTypeface(typeface, Typeface.NORMAL)
                setTextColor(getColor(R.color.textPrimary))
                background = getDrawable(R.drawable.bg_surface_button)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener { openChat(summary.id) }
            }
            recentSessions.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(62)
                ).apply {
                    if (index > 0) topMargin = dp(8)
                }
            )
        }
    }

    private fun openChat(sessionId: String?) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                sessionId?.let { value -> putExtra(MainActivity.EXTRA_SESSION_ID, value) }
            }
        )
    }

    private fun formatTime(epochMillis: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }
}
