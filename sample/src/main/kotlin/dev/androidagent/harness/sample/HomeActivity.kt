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
import android.widget.Toast
import dev.androidagent.harness.deviceloop.android.AccessibilityAvailability
import dev.androidagent.harness.sdk.AgentSessionSummary
import dev.androidagent.harness.data.android.ProductDataStatus
import dev.androidagent.harness.data.android.TodoItem
import dev.androidagent.harness.data.android.TodoMutationResult
import dev.androidagent.harness.data.android.TodoState
import dev.androidagent.harness.feedback.HomeBriefCompiler
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.scheduling.LongTaskStatus
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class HomeActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newSingleThreadExecutor()

    private lateinit var providerStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var briefText: TextView
    private lateinit var statsSummary: TextView
    private lateinit var todoSummary: TextView
    private lateinit var agentSummary: TextView
    private lateinit var todoQuickContainer: LinearLayout
    private lateinit var recentSessions: LinearLayout
    private lateinit var preferences: SamplePreferences
    private lateinit var providerSettings: ProviderSettingsRepository
    private lateinit var approvalUi: SampleApprovalUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = SamplePreferences(this)
        providerSettings = ProviderSettingsRepository(this)
        setContentView(R.layout.activity_home)
        applySampleInsets(findViewById(R.id.homeContent))

        providerStatus = findViewById(R.id.homeProviderStatus)
        runtimeStatus = findViewById(R.id.homeRuntimeStatus)
        briefText = findViewById(R.id.homeBriefText)
        statsSummary = findViewById(R.id.homeStatsSummary)
        todoSummary = findViewById(R.id.homeTodoSummary)
        agentSummary = findViewById(R.id.homeAgentSummary)
        todoQuickContainer = findViewById(R.id.homeTodoQuickContainer)
        recentSessions = findViewById(R.id.recentSessionsContainer)
        approvalUi = SampleApprovalUi(this, ::loadLocalState)
        bindSampleNavigation(SampleTab.HOME)
        findViewById<Button>(R.id.continueChatButton).setOnClickListener {
            openChat(preferences.lastSessionId())
        }
        findViewById<Button>(R.id.homeSettingsButton).apply {
            removeClippedShadow()
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
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
        bindProductButton(R.id.homeStatsButton, "stats")
        bindProductButton(R.id.homeTodoButton, "todo")
        findViewById<Button>(R.id.continueChatButton).removeClippedShadow()
    }

    override fun onStart() {
        super.onStart()
        approvalUi.attach()
    }

    override fun onStop() {
        approvalUi.detach()
        super.onStop()
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
            val state = SampleRuntime.state(this).snapshot()
            val todos = SampleRuntime.todo(this).list()
            val schedules = SampleRuntime.schedules(this).list()
            val outcomes = SampleRuntime.outcomes(this).query()
            val checkpoints = SampleRuntime.checkpoints(this).list()
            val stats = SampleRuntime.usageStats(this).snapshot()
            val accessibility = AccessibilityAvailability.isServiceEnabled(this)
            val overdue = todos.count { item ->
                item.state == TodoState.COMMITTED &&
                    item.dueDate?.let { value ->
                        runCatching { LocalDate.parse(value).isBefore(LocalDate.now()) }
                            .getOrDefault(false)
                    } == true
            }
            val brief = HomeBriefCompiler().compile(
                overdueTodoCount = overdue,
                candidates = state.candidates,
                enabledScheduleCount = schedules.count { schedule -> schedule.enabled },
                outcomes = outcomes,
                findings = emptyList()
            )
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
                    append(" · ${checkpoints.count { it.status.name == "RUNNING" }} 个 LongTask 运行中")
                }
                briefText.text = buildString {
                    val enabledSchedules = schedules.count { schedule -> schedule.enabled }
                    if (
                        overdue == 0 &&
                        brief.pendingCandidateCount == 0 &&
                        enabledSchedules == 0
                    ) {
                        appendLine("今天状态平稳")
                    } else {
                        appendLine("今天有需要留意的进展")
                    }
                    appendLine(
                        "逾期待办 $overdue · 待审候选 ${brief.pendingCandidateCount} · " +
                            "自动任务 $enabledSchedules"
                    )
                    append(
                        if (brief.pendingCandidateCount == 0) {
                            "没有待审候选"
                        } else {
                            "${brief.pendingCandidateCount} 个记忆 / 技能 / 人格候选等待处理"
                        }
                    )
                    val activeRuns = SampleRuntime.activeRunSnapshot().size
                    if (activeRuns > 0) append(" · $activeRuns 个 Agent 正在运行")
                }
                val statsText = when (stats.availability.status) {
                    ProductDataStatus.AVAILABLE ->
                        "前台 ${formatDuration(stats.totalForegroundMillis)} · " +
                            "解锁 ${stats.unlockCount} 次" +
                            if (stats.isRealZero) " · 今日真实零数据" else ""
                    else ->
                        usageStatusText(stats.availability.status)
                }
                statsSummary.text = statsText
                val committed = todos.filter { item -> item.state == TodoState.COMMITTED }
                val todoText = "${committed.size} 个待办 · " +
                    "$overdue 个逾期 · ${todos.count { it.state == TodoState.DRAFT }} 个草稿"
                todoSummary.text = todoText
                findViewById<Button>(R.id.homeStatsButton).text =
                    "使用统计\n$statsText"
                findViewById<Button>(R.id.homeTodoButton).text =
                    "待办事项\n$todoText"
                agentSummary.text = buildString {
                    val pendingApprovals = SampleRuntime.approvalBridge().pending().size
                    val pendingCandidates = state.candidates.count { candidate ->
                        candidate.status in setOf(
                            AgentCandidateStatus.PROPOSED,
                            AgentCandidateStatus.VALIDATED,
                            AgentCandidateStatus.EVALUATED,
                            AgentCandidateStatus.WAITING_APPROVAL
                        )
                    }
                    val activeLongTasks = checkpoints.count { checkpoint ->
                        checkpoint.status in setOf(
                            LongTaskStatus.READY,
                            LongTaskStatus.RUNNING,
                            LongTaskStatus.PAUSED
                        )
                    }
                    append(
                        when {
                            pendingApprovals > 0 -> "$pendingApprovals 项操作等待你审批"
                            pendingCandidates > 0 -> "$pendingCandidates 项 Agent 候选等待处理"
                            activeLongTasks > 0 -> "$activeLongTasks 个长期任务可以继续"
                            else -> "当前没有需要处理的事项"
                        }
                    )
                    append("\n候选 $pendingCandidates · 审批 $pendingApprovals · 长期任务 $activeLongTasks")
                }
                renderQuickTodos(committed.take(3))
                renderRecentSessions(summaries)
            }
        }
    }

    private fun renderQuickTodos(items: List<TodoItem>) {
        todoQuickContainer.removeAllViews()
        if (items.isEmpty()) {
            todoQuickContainer.addView(TextView(this).apply {
                text = "今天没有可快速完成的待办。"
                textSize = 12f
                setTextColor(getColor(R.color.textSecondary))
            })
            return
        }
        items.forEachIndexed { index, item ->
            todoQuickContainer.addView(
                Button(this).apply {
                    removeClippedShadow()
                    text = "完成待办 · ${item.title}"
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 12f
                    minWidth = 0
                    setTextColor(getColor(R.color.textPrimary))
                    background = getDrawable(R.drawable.bg_secondary_button)
                    setPadding(dp(13), 0, dp(13), 0)
                    setOnClickListener { completeTodo(item) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)
                ).apply {
                    if (index > 0) topMargin = dp(7)
                }
            )
        }
    }

    private fun completeTodo(item: TodoItem) {
        diskExecutor.execute {
            val message = runCatching {
                SampleRuntime.todo(this).updateCommitted(
                    id = item.id,
                    expectedRevision = item.revision,
                    title = item.title,
                    note = item.note,
                    tags = item.tags,
                    dueDate = item.dueDate,
                    completed = true,
                    runId = "home-${UUID.randomUUID()}",
                    sessionId = "home",
                    approvals = SampleRuntime.approvalCoordinator()
                )
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is TodoMutationResult.Applied -> "Todo 已完成"
                        is TodoMutationResult.Rejected -> "未完成：${result.reason}"
                    }
                },
                onFailure = { error -> "完成失败：${error.message}" }
            )
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                loadLocalState()
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

    private fun bindProductButton(id: Int, section: String) {
        findViewById<Button>(id).apply {
            removeClippedShadow()
            setOnClickListener {
                startActivity(
                    Intent(this@HomeActivity, ProductCenterActivity::class.java)
                        .putExtra(ProductCenterActivity.EXTRA_SECTION, section)
                )
            }
        }
    }

    private fun formatTime(epochMillis: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes < 60) "${minutes} 分钟" else "${minutes / 60}小时${minutes % 60}分"
    }

    private fun usageStatusText(status: ProductDataStatus): String = when (status) {
        ProductDataStatus.AVAILABLE -> "可用"
        ProductDataStatus.DISABLED -> "使用统计未开启"
        ProductDataStatus.PERMISSION_REQUIRED -> "需要授予使用情况访问权限"
        ProductDataStatus.NOT_DECLARED -> "应用未声明使用统计能力"
        ProductDataStatus.SERVICE_DISABLED -> "系统使用统计服务不可用"
        ProductDataStatus.UNAVAILABLE -> "暂时无法读取使用统计"
    }
}
