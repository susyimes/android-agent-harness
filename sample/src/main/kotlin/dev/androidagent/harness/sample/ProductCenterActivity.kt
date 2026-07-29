// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.androidagent.harness.approval.AgentApprovalRecord
import dev.androidagent.harness.data.android.ProductDataStatus
import dev.androidagent.harness.data.android.TodoDataSnapshot
import dev.androidagent.harness.data.android.TodoItem
import dev.androidagent.harness.data.android.TodoMutationResult
import dev.androidagent.harness.data.android.TodoState
import dev.androidagent.harness.data.android.UsageStatsSnapshot
import dev.androidagent.harness.feedback.AgentSelfCheck
import dev.androidagent.harness.feedback.HomeBriefCompiler
import dev.androidagent.harness.permission.android.AndroidSettingsAction
import dev.androidagent.harness.permission.android.PermissionSnapshot
import dev.androidagent.harness.scheduling.DeliveryPolicy
import dev.androidagent.harness.scheduling.DispatchReceipt
import dev.androidagent.harness.scheduling.MissedRunPolicy
import dev.androidagent.harness.scheduling.MutableRunControl
import dev.androidagent.harness.scheduling.LongTaskCheckpoint
import dev.androidagent.harness.scheduling.LongTaskStatus
import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
import dev.androidagent.harness.scheduling.OccurrenceTrigger
import dev.androidagent.harness.scheduling.ScheduleCadence
import dev.androidagent.harness.scheduling.ScheduleCalculator
import dev.androidagent.harness.scheduling.ScheduleConstraints
import dev.androidagent.harness.scheduling.ScheduleSpec
import dev.androidagent.harness.scheduling.ScheduleTargetType
import dev.androidagent.harness.scheduling.android.AndroidSchedulerBackend
import dev.androidagent.harness.scheduling.GovernedScheduleService
import dev.androidagent.harness.sdk.AgentReplayReport
import dev.androidagent.harness.sdk.AgentTraceReplayEvaluator
import dev.androidagent.harness.sdk.house.AgentHouseMigrationMode
import dev.androidagent.harness.sdk.house.AgentHouseStateMigrator
import dev.androidagent.harness.sdk.house.AgentHouseSnapshot
import dev.androidagent.harness.sdk.house.GovernedAgentHouseMaintenance
import dev.androidagent.harness.state.AgentAssetCandidate
import dev.androidagent.harness.state.AgentAssetRevision
import dev.androidagent.harness.state.AgentAssetRevisionStatus
import dev.androidagent.harness.state.AgentCandidateSource
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.state.AgentStateCollection
import dev.androidagent.harness.state.AgentStateRetentionPolicy
import dev.androidagent.harness.state.AgentStateSnapshot
import dev.androidagent.harness.state.GovernedAgentStateMaintenance
import dev.androidagent.harness.state.recordCount
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

class ProductCenterActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var navigation: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var backButton: Button
    private lateinit var stopAllRunsButton: Button
    private lateinit var approvalUi: SampleApprovalUi
    private lateinit var preferences: SamplePreferences
    private var section = Section.OVERVIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = SamplePreferences(this)
        section = Section.fromId(intent.getStringExtra(EXTRA_SECTION))
        setContentView(R.layout.activity_product_center)
        root = findViewById(R.id.productCenterRoot)
        titleView = findViewById(R.id.productTitle)
        subtitleView = findViewById(R.id.productSubtitle)
        navigation = findViewById(R.id.productNavigation)
        content = findViewById(R.id.productContent)
        backButton = findViewById(R.id.productBackButton)
        stopAllRunsButton = findViewById(R.id.stopAllRunsButton)
        applySampleInsets(root)
        bindSampleNavigation(SampleTab.WORKBENCH)
        backButton.apply {
            removeClippedShadow()
            setOnClickListener {
                if (section == Section.OVERVIEW) {
                    startActivity(
                        Intent(this@ProductCenterActivity, SettingsActivity::class.java)
                    )
                } else {
                    section = Section.OVERVIEW
                    render()
                }
            }
        }
        stopAllRunsButton.apply {
            removeClippedShadow()
            setOnClickListener {
                val stopped = SampleRuntime.stopAllRuns()
                Toast.makeText(
                    this@ProductCenterActivity,
                    if (stopped == 0) "当前没有运行中的 Agent" else "已停止 $stopped 个运行",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
        approvalUi = SampleApprovalUi(this, ::render)
        buildNavigation()
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        section = Section.fromId(intent.getStringExtra(EXTRA_SECTION))
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    override fun onStart() {
        super.onStart()
        approvalUi.attach()
    }

    override fun onStop() {
        approvalUi.detach()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildNavigation() {
        navigation.removeAllViews()
        navigation.visibility = View.GONE
    }

    private fun render() {
        titleView.text = section.title
        subtitleView.text = section.subtitle
        backButton.text = if (section == Section.OVERVIEW) "设置" else "总览"
        val activeRuns = SampleRuntime.activeRunSnapshot().size
        stopAllRunsButton.visibility = if (activeRuns > 0) View.VISIBLE else View.GONE
        stopAllRunsButton.text = "停止正在运行的 Agent（$activeRuns）"
        content.removeAllViews()
        addInfoCard("正在读取", "正在汇总本机数据…")
        when (section) {
            Section.OVERVIEW -> loadOverview()
            Section.STATS -> loadStats()
            Section.TODO -> loadTodo()
            Section.STATE -> loadState()
            Section.AUTOMATION -> loadAutomation()
            Section.PERMISSIONS -> loadPermissions()
            Section.DEBUG -> loadDebug()
            Section.DATA -> loadData()
        }
    }

    private fun loadOverview() {
        worker.execute {
            val stats = SampleRuntime.usageStats(this).snapshot()
            val todos = SampleRuntime.todo(this).list()
            val schedules = SampleRuntime.schedules(this).list()
            val checkpoints = SampleRuntime.checkpoints(this).list()
            val pendingApprovals = SampleRuntime.approvalBridge().pending().size
            runOnUiThreadSafe {
                if (section != Section.OVERVIEW) return@runOnUiThreadSafe
                renderOverview(
                    stats = stats,
                    todos = todos,
                    schedules = schedules,
                    checkpoints = checkpoints,
                    pendingApprovals = pendingApprovals
                )
            }
        }
    }

    private fun renderOverview(
        stats: UsageStatsSnapshot,
        todos: List<TodoItem>,
        schedules: List<ScheduleSpec>,
        checkpoints: List<LongTaskCheckpoint>,
        pendingApprovals: Int
    ) {
        content.removeAllViews()
        addSectionLabel("数据源")
        val statsStatus = when (stats.availability.status) {
            ProductDataStatus.AVAILABLE ->
                "今日前台 ${formatDuration(stats.totalForegroundMillis)} · 解锁 ${stats.unlockCount} 次"
            else -> usageStatusText(stats.availability.status)
        }
        addWorkbenchRow(
            "使用统计",
            statsStatus,
            "查看"
        ) { openSection(Section.STATS) }
        val committed = todos.count { item -> item.state == TodoState.COMMITTED }
        val drafts = todos.count { item -> item.state == TodoState.DRAFT }
        addWorkbenchRow(
            "待办事项",
            "$committed 个待办 · $drafts 个草稿",
            "查看"
        ) { openSection(Section.TODO) }
        addWorkbenchRow(
            "权限与能力",
            "Phone Use、使用情况、通知和语音权限",
            "查看"
        ) { openSection(Section.PERMISSIONS) }

        addSectionLabel("周期任务")
        val activeLongTasks = checkpoints.count { checkpoint ->
            checkpoint.status in setOf(
                LongTaskStatus.READY,
                LongTaskStatus.RUNNING,
                LongTaskStatus.PAUSED
            )
        }
        addWorkbenchRow(
            "自动任务",
            "${schedules.count { it.enabled }}/${schedules.size} 项已开启 · " +
                "$activeLongTasks 个长期任务可继续\n" +
                "Heartbeat、Dream、Proactive、Cron、LongTask",
            "管理"
        ) { openSection(Section.AUTOMATION) }

        addSectionLabel("运行与开发")
        val activeRuns = SampleRuntime.activeRunSnapshot().size
        addWorkbenchRow(
            "运行记录",
            "$activeRuns 个运行中 · $pendingApprovals 项待审批",
            "查看"
        ) { openSection(Section.DEBUG) }
        addWorkbenchRow(
            "本地数据",
            "按域导出、保留和删除；凭据不进入导出",
            "管理"
        ) { openSection(Section.DATA) }
    }

    private fun openSection(target: Section) {
        section = target
        render()
    }

    private fun loadStats() {
        worker.execute {
            val snapshot = SampleRuntime.usageStats(this).snapshot()
            runOnUiThreadSafe {
                if (section == Section.STATS) renderStats(snapshot)
            }
        }
    }

    private fun renderStats(snapshot: UsageStatsSnapshot) {
        content.removeAllViews()
        val availability = when (snapshot.availability.status) {
            ProductDataStatus.AVAILABLE ->
                if (snapshot.isRealZero) "可用 · 今日真实零数据" else "可用"
            else -> usageStatusText(snapshot.availability.status)
        }
        addInfoCard(
            "今日 Stats",
            "$availability\n前台 ${formatDuration(snapshot.totalForegroundMillis)} · " +
                "解锁 ${snapshot.unlockCount} 次 · 最长 ${formatDuration(snapshot.longestSessionMillis)}",
            actions = listOf(
                Action(
                    if (preferences.usageStatsEnabled()) "关闭采集" else "开启采集"
                ) {
                    preferences.setUsageStatsEnabled(!preferences.usageStatsEnabled())
                    render()
                },
                Action("系统授权") {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )
        )
        if (snapshot.topApps.isEmpty()) {
            addInfoCard(
                "Top Apps",
                if (snapshot.availability.status == ProductDataStatus.AVAILABLE) {
                    "今天还没有可展示的应用使用记录。"
                } else {
                    "授权并开启 Stats 后显示聚合排行；默认不会把完整 timeline 发给模型。"
                }
            )
        } else {
            snapshot.topApps.forEachIndexed { index, app ->
                addInfoCard(
                    "${index + 1}. ${app.packageName}",
                    "前台 ${formatDuration(app.foregroundMillis)}"
                )
            }
        }
        addInfoCard(
            "数据披露",
            "CCP 默认只读取聚合摘要；timeline 与 raw events 不进入长期记忆、House 或 trace。"
        )
    }

    private fun loadTodo() {
        worker.execute {
            val items = SampleRuntime.todo(this).list(includeArchived = true)
            runOnUiThreadSafe {
                if (section == Section.TODO) renderTodo(items)
            }
        }
    }

    private fun renderTodo(items: List<TodoItem>) {
        content.removeAllViews()
        addInfoCard(
            "Todo",
            "${items.count { it.state == TodoState.COMMITTED }} 个待办 · " +
                "${items.count { it.state == TodoState.DRAFT }} 个草稿 · " +
                "${SampleRuntime.todo(this).effects().size} 条已审批 effect",
            listOf(Action("新建草稿", ::showCreateTodoDialog))
        )
        if (items.isEmpty()) {
            addInfoCard(
                "还没有 Todo",
                "创建先成为草稿；提交、完成、归档和删除都会绑定 exact hash 并要求审批。"
            )
        }
        items.filter { it.state != TodoState.DELETED }.forEach { item ->
            val actions = buildList {
                if (item.state == TodoState.DRAFT) {
                    add(Action("审批并提交") { mutateTodo("提交") {
                        SampleRuntime.todo(this@ProductCenterActivity).commitDraft(
                            item.id,
                            "ui-${UUID.randomUUID()}",
                            "product-center",
                            SampleRuntime.approvalCoordinator()
                        )
                    } })
                }
                if (item.state in setOf(TodoState.COMMITTED, TodoState.COMPLETED)) {
                    add(Action(if (item.state == TodoState.COMPLETED) "重新打开" else "完成") {
                        mutateTodo("更新") {
                            SampleRuntime.todo(this@ProductCenterActivity).updateCommitted(
                                id = item.id,
                                expectedRevision = item.revision,
                                title = item.title,
                                note = item.note,
                                tags = item.tags,
                                dueDate = item.dueDate,
                                completed = item.state != TodoState.COMPLETED,
                                runId = "ui-${UUID.randomUUID()}",
                                sessionId = "product-center",
                                approvals = SampleRuntime.approvalCoordinator()
                            )
                        }
                    })
                }
                if (item.state in setOf(TodoState.COMMITTED, TodoState.COMPLETED)) {
                    add(Action("归档") {
                        mutateTodo("归档") {
                            SampleRuntime.todo(this@ProductCenterActivity).archive(
                                id = item.id,
                                expectedRevision = item.revision,
                                runId = "ui-${UUID.randomUUID()}",
                                sessionId = "product-center",
                                approvals = SampleRuntime.approvalCoordinator()
                            )
                        }
                    })
                }
                add(Action("删除") {
                    mutateTodo("删除") {
                        SampleRuntime.todo(this@ProductCenterActivity).delete(
                            id = item.id,
                            expectedRevision = item.revision,
                            runId = "ui-${UUID.randomUUID()}",
                            sessionId = "product-center",
                            approvals = SampleRuntime.approvalCoordinator()
                        )
                    }
                })
            }
            addInfoCard(
                item.title,
                "${item.state} · r${item.revision}" +
                    (item.dueDate?.let { "\n截止 $it" } ?: "") +
                    (item.note.takeIf(String::isNotBlank)?.let { "\n$it" } ?: ""),
                actions
            )
        }
    }

    private fun showCreateTodoDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val title = EditText(this).apply { hint = "待办标题" }
        val note = EditText(this).apply { hint = "备注（可选）" }
        container.addView(title)
        container.addView(note)
        AlertDialog.Builder(this)
            .setTitle("新建 Todo 草稿")
            .setView(container)
            .setPositiveButton("保存草稿") { _, _ ->
                val value = title.text.toString().trim()
                if (value.isBlank()) return@setPositiveButton
                worker.execute {
                    runCatching {
                        SampleRuntime.todo(this).createDraft(
                            title = value,
                            note = note.text.toString(),
                            source = "sample-user"
                        )
                    }
                    runOnUiThreadSafe { render() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mutateTodo(
        label: String,
        operation: () -> TodoMutationResult
    ) {
        worker.execute {
            val result = runCatching(operation).fold(
                onSuccess = { value ->
                    when (value) {
                        is TodoMutationResult.Applied -> "${label}成功 · r${value.item.revision}"
                        is TodoMutationResult.Rejected -> "${label}未执行：${value.reason}"
                    }
                },
                onFailure = { error -> "${label}失败：${error.message}" }
            )
            runOnUiThreadSafe {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun loadState() {
        worker.execute {
            val snapshot = SampleRuntime.state(this).snapshot()
            runOnUiThreadSafe {
                if (section == Section.STATE) renderState(snapshot)
            }
        }
    }

    private fun renderState(snapshot: AgentStateSnapshot) {
        content.removeAllViews()
        val pending = snapshot.candidates.filter { candidate ->
            candidate.status in PENDING_CANDIDATE_STATES
        }
        addInfoCard(
            "State Vault / Obsidian",
            "${snapshot.documents.size} documents · ${snapshot.evidence.size} evidence · " +
                "${snapshot.effects.size} effects · ${snapshot.revisions.size} revisions\n" +
                "${pending.size} 个候选等待处理；未批准内容不会进入 Agent context。",
            listOf(
                Action("House") {
                    startActivity(Intent(this, AgentHouseActivity::class.java))
                },
                Action("导出摘要") { shareText("Agent State", stateExport(snapshot)) },
                Action("迁移 House") { migrateHouse() }
            )
        )
        if (pending.isEmpty()) {
            addInfoCard(
                "Memory / Skill / Persona Inbox",
                "当前没有待审候选。Agent 只能提出 candidate/draft/proposal，不能直接修改长期资产。"
            )
        } else {
            pending.forEach { candidate -> addCandidateCard(candidate, snapshot) }
        }
        renderRevisionHistory(snapshot)
        if (snapshot.briefs.isNotEmpty()) {
            addSectionLabel("Remote Brief Preview")
            val briefDocuments = snapshot.documents
                .filter { document -> document.collection == AgentStateCollection.BRIEFS }
                .associateBy { document -> document.id }
            snapshot.briefs.takeLast(3).reversed().forEach { brief ->
                val metadata = briefDocuments[brief.id]?.metadata.orEmpty()
                val generation = when (metadata["remoteStatus"]) {
                    "ENHANCED" -> "远端增强 · ${metadata["remoteProviderId"] ?: "provider"}"
                    "TIMED_OUT" -> "规则降级 · 远端超时已丢弃"
                    "FAILED" -> "规则降级 · 远端失败"
                    "REJECTED" -> "规则降级 · 远端响应不可用"
                    else -> "规则生成"
                }
                addInfoCard(
                    brief.title,
                    "$generation\n${brief.summary}\n" +
                        "events=${brief.eventRefs.size} · evidence=${brief.evidenceRefs.size} · " +
                        "openLoops=${brief.openLoopRefs.size} · " +
                        "pending=${brief.pendingCandidateRefs.size} · " +
                        "latency=${metadata["remoteElapsedMillis"] ?: "0"}ms"
                )
            }
        }
        if (snapshot.events.isNotEmpty() || snapshot.psycheObservations.isNotEmpty()) {
            addSectionLabel("Self Timeline / Psyche")
            snapshot.psycheObservations.takeLast(5).reversed().forEach { observation ->
                addInfoCard(
                    "Psyche · ${observation.dimension}",
                    "confidence=${observation.confidence} · " +
                        "evidence=${observation.evidenceRefs.joinToString().ifBlank { "none" }}\n" +
                        observation.observation
                )
            }
            snapshot.events.takeLast(8).reversed().forEach { event ->
                addInfoCard(
                    event.type,
                    "${event.source} · ${event.createdAtEpochMillis}\n${event.summary}"
                )
            }
        }
        if (snapshot.evidence.isNotEmpty()) {
            addSectionLabel("Evidence")
            snapshot.evidence.takeLast(10).reversed().forEach { evidence ->
                addInfoCard(
                    evidence.source,
                    "${evidence.trust} · ${evidence.privacy} · " +
                        "hash ${evidence.contentHash.take(12)}…\n${evidence.summary}"
                )
            }
        }
        snapshot.documents.take(12).forEach { document ->
            addInfoCard(
                "${document.collection} · ${document.title}",
                "r${document.revision} · ${document.source} · ${document.content.length} chars\n" +
                    document.content.take(220)
            )
        }
        val effects = snapshot.effects.takeLast(10).reversed()
        if (effects.isNotEmpty()) {
            addSectionLabel("最近 Effects")
            effects.forEach { effect ->
                addInfoCard(effect.kind, "${effect.status} · ${effect.summary}")
            }
        }
    }

    private fun addCandidateCard(
        candidate: AgentAssetCandidate,
        snapshot: AgentStateSnapshot
    ) {
        val evaluation = snapshot.evalReports
            .filter { report -> report.candidateId == candidate.id }
            .maxByOrNull { report -> report.createdAtEpochMillis }
        val actions = buildList {
            if (candidate.status in setOf(
                    AgentCandidateStatus.PROPOSED,
                    AgentCandidateStatus.VALIDATED
                )
            ) {
                add(Action("校验 / 评估") { governCandidate(candidate, "evaluate") })
            }
            if (candidate.status in setOf(
                    AgentCandidateStatus.EVALUATED,
                    AgentCandidateStatus.WAITING_APPROVAL
                )
            ) {
                add(Action("审批并晋升") { governCandidate(candidate, "promote") })
            }
            add(Action("拒绝") { governCandidate(candidate, "reject") })
        }
        addInfoCard(
            "${candidate.kind} · ${candidate.title}",
            "${candidate.status} · hash ${candidate.candidateHash.take(12)}…\n" +
                "来源 ${candidate.source.author}/${candidate.source.trigger} · " +
                "run ${candidate.source.runId} · 置信度 ${candidate.confidence}\n" +
                "证据 ${candidate.evidenceRefs.joinToString().ifBlank { "none" }}" +
                candidate.diff.takeIf(String::isNotBlank)?.let { "\nDiff: $it" }.orEmpty() +
                candidate.capabilityClaims.takeIf(Set<String>::isNotEmpty)
                    ?.let { "\n能力声明：${it.joinToString()}" }.orEmpty() +
                evaluation?.let { "\nEval ${it.verdict}: ${it.summary}" }.orEmpty() +
                candidate.statusReason?.let { "\n状态：$it" }.orEmpty() +
                "\n" +
                candidate.proposedContent.take(300),
            actions
        )
    }

    private fun renderRevisionHistory(snapshot: AgentStateSnapshot) {
        addSectionLabel("Approved Assets / Rollback")
        if (snapshot.revisions.isEmpty()) {
            addInfoCard(
                "还没有 approved revision",
                "候选经过 validation、eval、exact approval 和 promotion 后会在这里形成回滚点。"
            )
            return
        }
        snapshot.revisions.groupBy(AgentAssetRevision::assetKey)
            .toSortedMap()
            .forEach { (assetKey, revisions) ->
                val active = revisions.firstOrNull { revision ->
                    revision.status == AgentAssetRevisionStatus.ACTIVE
                }
                val targets = revisions
                    .filter { revision -> revision.id != active?.id }
                    .sortedByDescending(AgentAssetRevision::revision)
                addInfoCard(
                    assetKey,
                    buildString {
                        if (active == null) {
                            append("没有 active revision")
                        } else {
                            append(
                                "ACTIVE r${active.revision} · ${active.kind} · " +
                                    "approval ${active.approvalId.take(12)}…"
                            )
                            append("\nEval ${active.evalReportId} · ")
                            append("hash ${active.candidateHash.take(12)}…")
                            append("\n证据 ${active.evidenceRefs.joinToString().ifBlank { "none" }}")
                            append("\n${active.content.take(260)}")
                        }
                        if (targets.isNotEmpty()) {
                            append("\n历史：")
                            append(
                                targets.joinToString { revision ->
                                    "r${revision.revision} ${revision.status}"
                                }
                            )
                        }
                    },
                    targets.take(2).map { target ->
                        Action("回滚到 r${target.revision}") {
                            rollbackAsset(assetKey, target)
                        }
                    }
                )
            }
    }

    private fun rollbackAsset(
        assetKey: String,
        target: AgentAssetRevision
    ) {
        worker.execute {
            val message = runCatching {
                SampleRuntime.governance(this).rollback(
                    assetKey = assetKey,
                    targetRevisionId = target.id,
                    runId = "rollback-${UUID.randomUUID()}",
                    sessionId = "product-center",
                    reason = "用户从 State / Obsidian 页面选择已知 revision。"
                )
                "已回滚到 r${target.revision}，操作已写入 effect journal"
            }.getOrElse { error ->
                "回滚未执行：${error.message}"
            }
            runOnUiThreadSafe {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun governCandidate(candidate: AgentAssetCandidate, action: String) {
        worker.execute {
            val message = runCatching {
                when (action) {
                    "evaluate" -> {
                        val report = SampleRuntime.governance(this)
                            .validateAndEvaluate(candidate.id)
                        "评估完成：${report.verdict} · ${report.summary}"
                    }
                    "promote" -> {
                        val result = SampleRuntime.governance(this).promote(candidate.id)
                        if (result.promoted) "已晋升并建立回滚点" else result.effect.summary
                    }
                    else -> {
                        SampleRuntime.governance(this)
                            .reject(candidate.id, "用户在 sample 控制中心拒绝。")
                        "候选已拒绝"
                    }
                }
            }.fold(
                onSuccess = { it },
                onFailure = { "处理失败：${it.message}" }
            )
            runOnUiThreadSafe {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun migrateHouse() {
        worker.execute {
            val result = runCatching {
                AgentHouseStateMigrator(
                    repository = SampleRuntime.house(this),
                    vault = SampleRuntime.state(this),
                    governance = SampleRuntime.governance(this)
                ).migrate(
                    source = AgentCandidateSource(
                        runId = "house-migration-${UUID.randomUUID()}",
                        sessionId = "product-center",
                        author = "host",
                        trigger = "user"
                    ),
                    mode = AgentHouseMigrationMode.PROPOSE_ONLY
                )
            }.fold(
                onSuccess = { report ->
                    "迁移完成：${report.proposedCount} 个候选进入评估，" +
                        "${report.items.count { item -> item.skippedReason != null }} 个跳过。"
                },
                onFailure = { error -> "House 迁移失败：${error.message}" }
            )
            runOnUiThreadSafe {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun loadAutomation() {
        worker.execute {
            val schedules = SampleRuntime.schedules(this).list()
            val checkpoints = SampleRuntime.checkpoints(this).list()
            val receipts = SampleRuntime.occurrenceSnapshot()
            runOnUiThreadSafe {
                if (section == Section.AUTOMATION) {
                    renderAutomation(schedules, checkpoints, receipts)
                }
            }
        }
    }

    private fun renderAutomation(
        schedules: List<ScheduleSpec>,
        checkpoints: List<LongTaskCheckpoint>,
        receipts: List<DispatchReceipt>
    ) {
        content.removeAllViews()
        val initiative = preferences.initiativeLevel()
        val quietStart = preferences.proactiveQuietStart()
        val quietEnd = preferences.proactiveQuietEnd()
        val dailyCap = preferences.proactiveDailyCap()
        addInfoCard(
            "Proactive 主动性",
            "$initiative · 勿扰 $quietStart–$quietEnd · 每日激活上限 $dailyCap\n" +
                "主动性只能产生 ActivationRequest，不会扩大权限或绕过审批。",
            listOf(
                Action("主动性档位") { showInitiativeDialog() },
                Action("勿扰时间") { showQuietHoursDialog() },
                Action("每日上限") { showDailyCapDialog() }
            )
        )
        ScheduleTargetType.entries.forEach { target ->
            val id = scheduleId(target)
            val current = schedules.firstOrNull { it.id == id }
            val next = current?.let { ScheduleCalculator.nextRunAt(it, System.currentTimeMillis()) }
            addInfoCard(
                targetLabel(target),
                buildString {
                    append(if (current?.enabled == true) "已开启" else "已关闭")
                    append(" · revision ${current?.revision ?: 0}")
                    if (next != null) append("\n下次运行：${java.util.Date(next)}")
                    append("\n")
                    append(scheduleDescription(target))
                },
                buildList {
                    add(Action(if (current?.enabled == true) "停用" else "审批并启用") {
                        toggleSchedule(target, current)
                    })
                    if (current?.enabled == true) {
                        add(Action("立即运行") { runScheduleNow(current) })
                    }
                    if (current != null) {
                        add(Action("删除") { deleteSchedule(current) })
                    }
                }
            )
        }
        addInfoCard(
            "LongTask Checkpoints",
            if (checkpoints.isEmpty()) {
                "没有长任务检查点。每个 burst 有预算、重复失败上限、deadline 与 Stop fence。"
            } else {
                "${checkpoints.size} 个 durable checkpoint；READY/PAUSED 可继续，" +
                    "RUNNING 可暂停，Stop 后 late result 不能恢复任务。"
            }
        )
        checkpoints.sortedByDescending(LongTaskCheckpoint::updatedAtEpochMillis)
            .forEach { checkpoint ->
                val schedule = schedules.firstOrNull { spec ->
                    SamplePeriodicRunner.longTaskJobId(spec.id, spec.revision) ==
                        checkpoint.jobId
                }
                val terminal = checkpoint.status in setOf(
                    LongTaskStatus.COMPLETED,
                    LongTaskStatus.FAILED,
                    LongTaskStatus.CANCELLED,
                    LongTaskStatus.EXPIRED
                )
                addInfoCard(
                    checkpoint.jobId,
                    "${checkpoint.status} · revision ${checkpoint.revision} · " +
                        "burst ${checkpoint.burst}\n" +
                        (checkpoint.nextAction ?: checkpoint.lastReceipt?.summary
                            ?: "没有待继续动作"),
                    buildList {
                        when (checkpoint.status) {
                            LongTaskStatus.RUNNING,
                            LongTaskStatus.READY -> add(
                                Action("暂停") { pauseLongTask(checkpoint) }
                            )
                            LongTaskStatus.PAUSED -> if (schedule != null) {
                                add(
                                    Action("恢复并继续") {
                                        resumeLongTask(checkpoint, schedule)
                                    }
                                )
                            }
                            else -> Unit
                        }
                        if (checkpoint.status == LongTaskStatus.READY && schedule != null) {
                            add(Action("继续一轮") { runScheduleNow(schedule) })
                        }
                        if (!terminal) {
                            add(Action("停止") { stopLongTask(checkpoint, schedule) })
                        }
                    }
                )
            }
        if (receipts.isNotEmpty()) {
            addSectionLabel("最近周期运行")
            receipts.takeLast(10).reversed().forEach { receipt ->
                addInfoCard(
                    receipt.status.name,
                    "${receipt.occurrenceId}\n${receipt.summary.take(300)}"
                )
            }
        }
    }

    private fun toggleSchedule(target: ScheduleTargetType, current: ScheduleSpec?) {
        worker.execute {
            val now = System.currentTimeMillis()
            val enabled = current?.enabled != true
            val draft = ScheduleSpec(
                id = scheduleId(target),
                targetType = target,
                cadence = cadence(target, now),
                timezone = ZoneId.systemDefault().id,
                validFromEpochMillis = current?.validFromEpochMillis ?: now,
                executionWindowMillis = 15 * 60_000L,
                missedRunPolicy = MissedRunPolicy.SKIP,
                maxJitterMillis = if (target == ScheduleTargetType.PROACTIVE) 5 * 60_000L else 0,
                constraints = ScheduleConstraints(
                    requiresNetwork = true,
                    requiresCharging = target == ScheduleTargetType.DREAM
                ),
                revision = (current?.revision ?: 0L) + 1,
                enabled = enabled,
                deliveryPolicy = if (target == ScheduleTargetType.LONG_TASK) {
                    DeliveryPolicy.VISIBLE_LONG_TASK
                } else {
                    DeliveryPolicy.IN_APP
                },
                reason = scheduleDescription(target),
                toolProfileId = "automation-read-only",
                contextPolicyId = "ccp-v2"
            )
            val result = runCatching {
                GovernedScheduleService(
                    repository = SampleRuntime.schedules(this),
                    backend = AndroidSchedulerBackend(this),
                    approvals = SampleRuntime.approvalCoordinator()
                ).apply(
                    draft,
                    runId = "schedule-ui-${UUID.randomUUID()}",
                    sessionId = "product-center"
                )
            }.fold(
                onSuccess = { receipt -> receipt.summary },
                onFailure = { error -> "Schedule 更新失败：${error.message}" }
            )
            runOnUiThreadSafe {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun showInitiativeDialog() {
        val values = arrayOf("OFF", "LOW", "BALANCED", "HIGH")
        var selected = values.indexOf(preferences.initiativeLevel()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("主动性档位")
            .setSingleChoiceItems(values, selected) { _, which -> selected = which }
            .setPositiveButton("保存") { _, _ ->
                preferences.setInitiativeLevel(values[selected])
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showQuietHoursDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val start = EditText(this).apply {
            hint = "开始 HH:mm"
            setText(preferences.proactiveQuietStart())
        }
        val end = EditText(this).apply {
            hint = "结束 HH:mm"
            setText(preferences.proactiveQuietEnd())
        }
        container.addView(start)
        container.addView(end)
        AlertDialog.Builder(this)
            .setTitle("Proactive 勿扰时间")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val message = runCatching {
                    preferences.setProactiveQuietHours(
                        start.text.toString().trim(),
                        end.text.toString().trim()
                    )
                    "勿扰时间已保存"
                }.getOrElse { "时间格式应为 HH:mm" }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDailyCapDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(preferences.proactiveDailyCap().toString())
            setSelectAllOnFocus(true)
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("每日主动激活上限（1–20）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val cap = input.text.toString().toIntOrNull()
                val message = runCatching {
                    preferences.setProactiveDailyCap(requireNotNull(cap))
                    "每日上限已保存"
                }.getOrElse { "请输入 1–20 的整数" }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runScheduleNow(spec: ScheduleSpec) {
        worker.execute {
            val now = System.currentTimeMillis()
            val trigger = OccurrenceTrigger(
                scheduleId = spec.id,
                scheduleRevision = spec.revision,
                targetType = spec.targetType,
                occurrenceId = "${spec.id}-manual-${UUID.randomUUID()}",
                plannedAtEpochMillis = now,
                actualAtEpochMillis = now,
                attempt = 1,
                reason = "用户从 Automation 页面手动运行。",
                authorization = SampleRuntime.authorizationSnapshot(this, spec)
            )
            val receipt = runCatching {
                SamplePeriodicRunner(this).dispatch(trigger, MutableRunControl())
            }.getOrElse { error ->
                DispatchReceipt(
                    occurrenceId = trigger.occurrenceId,
                    status = dev.androidagent.harness.scheduling.DispatchStatus.FAILED,
                    summary = error.message ?: "手动运行失败。",
                    retryable = false
                )
            }
            SampleRuntime.recordOccurrence(receipt)
            runOnUiThreadSafe {
                Toast.makeText(this, receipt.summary, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun pauseLongTask(checkpoint: LongTaskCheckpoint) {
        worker.execute {
            val paused = SampleRuntime.longTasks(this).pause(
                checkpoint.jobId,
                "用户从 Automation 页面暂停。"
            )
            runOnUiThreadSafe {
                Toast.makeText(
                    this,
                    if (paused) "LongTask 已暂停" else "LongTask 当前不能暂停",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
    }

    private fun resumeLongTask(
        checkpoint: LongTaskCheckpoint,
        schedule: ScheduleSpec
    ) {
        worker.execute {
            val authorization = SampleRuntime.authorizationSnapshot(this, schedule)
            val currentScope = SamplePeriodicRunner.longTaskScopeHash(
                schedule.id,
                schedule.revision,
                authorization
            )
            val resumed = SampleRuntime.longTasks(this).resume(
                checkpoint.jobId,
                currentScope
            )
            runOnUiThreadSafe {
                Toast.makeText(
                    this,
                    if (resumed) {
                        "LongTask 授权范围一致，继续一轮"
                    } else {
                        "LongTask 未恢复：状态或权限 / 凭据范围已变化"
                    },
                    Toast.LENGTH_LONG
                ).show()
                if (resumed) runScheduleNow(schedule) else render()
            }
        }
    }

    private fun stopLongTask(
        checkpoint: LongTaskCheckpoint,
        schedule: ScheduleSpec?
    ) {
        worker.execute {
            val stopped = SampleRuntime.longTasks(this).stop(
                checkpoint.jobId,
                "用户从 Automation 页面停止。"
            )
            val disabled = schedule?.let { value ->
                SampleRuntime.disableScheduleForStop(this, value.id)
            } ?: false
            val cancelled = schedule?.let { value ->
                AndroidSchedulerBackend(this).cancel(value.id)
            } ?: false
            runOnUiThreadSafe {
                Toast.makeText(
                    this,
                    if (stopped || disabled || cancelled) {
                        "LongTask 已停止，后续调度已取消"
                    } else {
                        "LongTask 已是终态"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
    }

    private fun deleteSchedule(spec: ScheduleSpec) {
        worker.execute {
            val result = runCatching {
                GovernedScheduleService(
                    repository = SampleRuntime.schedules(this),
                    backend = AndroidSchedulerBackend(this),
                    approvals = SampleRuntime.approvalCoordinator()
                ).delete(
                    scheduleId = spec.id,
                    expectedRevision = spec.revision,
                    runId = "schedule-delete-${UUID.randomUUID()}",
                    sessionId = "product-center"
                )
            }.fold(
                onSuccess = { deleted ->
                    if (deleted) "Schedule 已删除" else "Schedule 删除未获批准"
                },
                onFailure = { error -> "Schedule 删除失败：${error.message}" }
            )
            runOnUiThreadSafe {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun loadPermissions() {
        worker.execute {
            val snapshots = SampleRuntime.permissions(this).snapshots()
            runOnUiThreadSafe {
                if (section == Section.PERMISSIONS) renderPermissions(snapshots)
            }
        }
    }

    private fun renderPermissions(snapshots: List<PermissionSnapshot>) {
        content.removeAllViews()
        val approvalMode = SampleRuntime.approvalMode()
        addInfoCard(
            "权限原则",
            "SDK AAR 不在 manifest 中强制扩权；sample 只声明自身展示的能力。撤销后 Adapter " +
                "会立即返回 typed unavailable；当前应用级审批模式为${approvalMode.title}。"
        )
        snapshots.forEach { snapshot ->
            val disclosure = when {
                snapshot.capabilityId.contains("usage") ->
                    "用于 Stats 聚合；可读取应用前台时长，不上传 raw timeline。"
                snapshot.capabilityId.contains("accessibility") ->
                    "用于 Phone Use 的语义观察和单步操作；每次动作后重新观察。"
                snapshot.capabilityId.contains("overlay") ->
                    if (approvalMode == SampleApprovalMode.NONE) {
                        "当前无审批模式不会使用；切换到风险或严格审批后用于 Phone Use 审批。"
                    } else {
                        "用于 Phone Use 审批 overlay；不用于悬浮广告或后台操作。"
                    }
                snapshot.capabilityId.contains("voice") ->
                    "仅在用户点按语音后使用；原始音频默认不持久化。"
                snapshot.capabilityId.contains("location") ->
                    "可选粗粒度位置 adapter；默认关闭，不保存精确轨迹。"
                snapshot.capabilityId.contains("calendar") ->
                    "可选只读日历摘要；默认关闭，不自动创建或修改日程。"
                snapshot.capabilityId.contains("notification") ->
                    "用于可见长任务或经审批的通知；通知内容不会自动进入长期记忆。"
                snapshot.capabilityId.contains("alarm") ->
                    "sample 当前只使用 WorkManager；未声明 exact alarm，不会要求该能力。"
                else -> "仅在对应能力开启时读取；关闭后不采集。"
            }
            addInfoCard(
                snapshot.displayName,
                "${snapshot.status} · ${snapshot.reason}\n$disclosure",
                if (snapshot.settingsAction == AndroidSettingsAction.NONE) {
                    emptyList()
                } else {
                    listOf(Action("打开系统设置") { openPermissionSettings(snapshot) })
                }
            )
        }
        addInfoCard(
            "语音输入",
            "RECORD_AUDIO 仅在用户点按语音按钮后请求；原始音频不进入长期存储。",
            listOf(Action("打开应用权限") {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            })
        )
        addInfoCard(
            "文件 / 附件",
            "通过系统文档选择器按 URI 授权，不申请 all-files access。读取内容作为 " +
                "untrusted evidence；覆盖和删除必须绑定观测 hash 并走通用审批。"
        )
        addInfoCard(
            "视觉 / Sensor（实验）",
            "默认关闭且不随 SDK 自动启用。视觉原图只放在带 run/session/call scope 与 TTL " +
                "的临时 raw payload；传感器 adapter 只返回短期聚合值。"
        )
    }

    private fun openPermissionSettings(snapshot: PermissionSnapshot) {
        val intent = when (snapshot.settingsAction) {
            AndroidSettingsAction.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AndroidSettingsAction.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AndroidSettingsAction.NOTIFICATION_LISTENER ->
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            AndroidSettingsAction.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            AndroidSettingsAction.EXACT_ALARM -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            AndroidSettingsAction.APP_DETAILS -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            AndroidSettingsAction.NONE -> return
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun loadDebug() {
        worker.execute {
            val traces = SampleRuntime.traceSnapshot()
            val approvals = SampleRuntime.approvalRecords()
            val receipts = SampleRuntime.occurrenceSnapshot()
            val selfCheck = AgentSelfCheck().run(
                sessionStoreReadable = runCatching {
                    SampleRuntime.sessions(this).listSessions()
                }.isSuccess,
                stateStoreReadable = runCatching {
                    SampleRuntime.state(this).snapshot()
                }.isSuccess,
                pendingApprovalCount = SampleRuntime.approvalBridge().pending().size,
                stuckRunCount = SampleRuntime.activeRunSnapshot().size,
                scheduleCount = SampleRuntime.schedules(this).list().size,
                credentialAvailable = providerCredentialAvailable()
            )
            runOnUiThreadSafe {
                if (section == Section.DEBUG) {
                    renderDebug(traces, approvals, receipts, selfCheck)
                }
            }
        }
    }

    private fun renderDebug(
        traces: List<dev.androidagent.harness.sdk.AgentEvent>,
        approvals: List<AgentApprovalRecord>,
        receipts: List<DispatchReceipt>,
        selfCheck: dev.androidagent.harness.feedback.SelfCheckReport
    ) {
        content.removeAllViews()
        val replay = AgentTraceReplayEvaluator().evaluate(traces)
        addInfoCard(
            "Self Check",
            (if (selfCheck.healthy) "HEALTHY" else "NEEDS ATTENTION") + "\n" +
                selfCheck.checks.entries.joinToString("\n") { (key, passed) ->
                    "${if (passed) "✓" else "×"} $key"
                },
            listOf(
                Action("导出脱敏 Replay") {
                    shareText("Agent debug replay", debugExport(traces, approvals, receipts))
                },
                Action("清除 Trace") {
                    val count = SampleRuntime.clearTraces()
                    Toast.makeText(this, "已清除 $count 条 trace", Toast.LENGTH_SHORT).show()
                    render()
                }
            )
        )
        addInfoCard(
            "Replay / Eval",
            buildString {
                append(
                    if (replay.healthy) {
                        "PASS"
                    } else {
                        "NEEDS ATTENTION"
                    }
                )
                append(" · ${replay.completeRunCount}/${replay.runs.size} complete runs")
                append(" · ${replay.issues.size} issues")
                replay.issues.take(8).forEach { issue ->
                    append("\n${issue.severity} ${issue.code}: ${issue.summary}")
                }
                if (replay.runs.isEmpty()) {
                    append("\n发送一次对话或手动运行 Automation 后可验证 lifecycle replay。")
                }
            },
            listOf(
                Action("导出评估") {
                    shareText("Agent replay evaluation", replayExport(replay))
                }
            )
        )
        addInfoCard(
            "Run / Context / Tool Timeline",
            "${traces.size} events · ${SampleRuntime.activeRunSnapshot().size} active\n" +
                "包含 route、context selected/dropped、provider、tool envelope、checkpoint 与终态；" +
                "不包含 hidden reasoning。"
        )
        traces.takeLast(30).reversed().forEach { event ->
            addInfoCard(
                event::class.simpleName.orEmpty(),
                redact(event.toString()).take(700)
            )
        }
        addInfoCard(
            "Approvals",
            if (approvals.isEmpty()) "暂无审批记录" else approvals.takeLast(12)
                .joinToString("\n") { record ->
                    "${record.decision} · ${record.request?.effectSummary ?: "policy decision"}"
                }
        )
    }

    private fun loadData() {
        worker.execute {
            val inventory = DataInventory(
                sessions = SampleRuntime.sessions(this).listSessions().size,
                todo = SampleRuntime.todo(this).exportSnapshot(),
                state = SampleRuntime.state(this).exportSnapshot(),
                house = SampleRuntime.house(this).snapshot(),
                schedules = SampleRuntime.schedules(this).list(),
                checkpoints = SampleRuntime.checkpoints(this).list().size,
                traces = SampleRuntime.traceSnapshot().size,
                approvals = SampleRuntime.approvalRecords().size,
                occurrences = SampleRuntime.occurrenceSnapshot().size,
                feedback = SampleRuntime.signals(this).query().size +
                    SampleRuntime.outcomes(this).query().size
            )
            runOnUiThreadSafe {
                if (section == Section.DATA) renderData(inventory)
            }
        }
    }

    private fun renderData(inventory: DataInventory) {
        content.removeAllViews()
        addInfoCard(
            "Data & Retention",
            "每个域独立处理：清除会话不会删除 House，清除 Trace 不会破坏 Session，" +
                "Schedule 删除后不会继续创建 occurrence。导出由用户显式分享，凭据永不进入导出。",
            listOf(
                Action("导出目录") {
                    shareText("Agent data catalog", dataCatalogExport(inventory))
                }
            )
        )
        addInfoCard(
            "Sessions",
            "${inventory.sessions} 个会话 · 应用私有明文 adapter · 失败/停止轮次不提交",
            listOf(
                Action("导出索引") {
                    shareText(
                        "Agent sessions",
                        SampleRuntime.sessions(this).listSessions().joinToString("\n") { value ->
                            "${value.id}\t${value.updatedAtEpochMillis}\t" +
                                "${value.messageCount}\t${value.title}"
                        }
                    )
                },
                Action("删除会话") {
                    confirmDestructive(
                        "删除全部会话？",
                        "只删除聊天历史，不删除 House、State、Todo、Schedule 或凭据。",
                        ::deleteSessionDomain
                    )
                }
            )
        )
        addInfoCard(
            "Todo",
            "${inventory.todo.items.size} records · ${inventory.todo.effects.size} effect records",
            listOf(
                Action("导出 Todo") {
                    shareText("Agent Todo", todoExport(inventory.todo))
                },
                Action("删除 Todo") {
                    confirmDestructive(
                        "删除全部 Todo？",
                        "会绑定当前 Todo revision 清单并要求一次精确审批。",
                        ::deleteTodoDomain
                    )
                }
            )
        )
        addInfoCard(
            "State Vault / Obsidian",
            "${inventory.state.recordCount()} records · schema/hash envelope · " +
                "approved assets and rollback history are excluded from automatic retention",
            listOf(
                Action("完整导出") {
                    shareText("Agent State Vault", fullStateExport(inventory.state))
                },
                Action("安全整理") { applyStateRetention() },
                Action("删除 State") {
                    confirmDestructive(
                        "删除整个 State Vault？",
                        "会删除候选、评估、审批引用、资产 revision 与回滚历史；House 和会话不受影响。",
                        ::deleteStateDomain
                    )
                }
            )
        )
        addInfoCard(
            "Agent House",
            "${inventory.house.coreFiles.size} core · ${inventory.house.skills.size} skills · " +
                "${inventory.house.dailyMemories.size} legacy journal entries",
            listOf(
                Action("导出 House") {
                    shareText("Agent House", houseExport(inventory.house))
                },
                Action("恢复默认") {
                    confirmDestructive(
                        "重置 Agent House？",
                        "会精确审批并删除自定义核心内容、技能和 legacy journal；State Vault 不受影响。",
                        ::deleteHouseDomain
                    )
                }
            )
        )
        addInfoCard(
            "Automation",
            "${inventory.schedules.size} schedules · ${inventory.checkpoints} checkpoints · " +
                "${inventory.occurrences} process-local receipts",
            listOf(
                Action("导出调度") {
                    shareText("Agent automation", automationExport(inventory))
                },
                Action("删除调度") {
                    confirmDestructive(
                        "删除全部调度数据？",
                        "会精确审批、取消未来 WorkManager work，并清除 LongTask checkpoints。",
                        ::deleteAutomationDomain
                    )
                }
            )
        )
        addInfoCard(
            "Operational journals",
            "${inventory.traces} trace · ${inventory.approvals} approvals · " +
                "${inventory.feedback} feedback records\n" +
                "Trace/approval/feedback/voice transcript 默认仅保存在当前进程；原始音频不保存。",
            listOf(
                Action("导出 Replay") {
                    val traces = SampleRuntime.traceSnapshot()
                    shareText(
                        "Agent replay",
                        debugExport(
                            traces,
                            SampleRuntime.approvalRecords(),
                            SampleRuntime.occurrenceSnapshot()
                        )
                    )
                },
                Action("清除日志") {
                    confirmDestructive(
                        "清除运行日志？",
                        "只清除 Trace、审批、occurrence、feedback 和可选语音文本，不删除 Session。",
                        ::deleteOperationalDomain
                    )
                }
            )
        )
        addInfoCard(
            "Provider credentials",
            "API Key / Codex token 使用 Android Keystore-backed storage，永不进入 " +
                "House、State、Trace、Prompt 或上述导出。请在模型配置中按 provider 单独清除。",
            listOf(
                Action("模型配置") {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_OPEN_PROVIDER_SETTINGS, true)
                    )
                }
            )
        )
    }

    private fun applyStateRetention() {
        worker.execute {
            val result = runCatching {
                SampleRuntime.state(this).applyRetention(
                    AgentStateRetentionPolicy()
                )
            }.fold(
                onSuccess = { report ->
                    "整理完成：删除 ${report.removedRecords} 条过期/超限观察记录；" +
                        "保留 ${report.afterRecords} 条。"
                },
                onFailure = { error -> "整理失败：${error.message}" }
            )
            runOnUiThreadSafe {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun deleteSessionDomain() = runDataMutation {
        SampleRuntime.stopAllRuns("用户删除会话数据。")
        "已删除 ${SampleRuntime.sessions(this).clearSessions()} 个会话"
    }

    private fun deleteTodoDomain() = runDataMutation {
        val result = SampleRuntime.todo(this).deleteAll(
            "data-todo-${UUID.randomUUID()}",
            "product-center",
            SampleRuntime.approvalCoordinator()
        )
        if (result.applied) {
            "已删除 ${result.deletedItems} 个 Todo 和 ${result.deletedEffects} 条 effect"
        } else {
            result.reason
        }
    }

    private fun deleteStateDomain() = runDataMutation {
        val result = GovernedAgentStateMaintenance(
            SampleRuntime.state(this),
            SampleRuntime.approvalCoordinator()
        ).deleteAll(
            "data-state-${UUID.randomUUID()}",
            "product-center"
        )
        if (result.applied) {
            "已删除 ${result.report?.deletedRecords ?: 0} 条 State 记录"
        } else {
            result.reason
        }
    }

    private fun deleteHouseDomain() = runDataMutation {
        val result = GovernedAgentHouseMaintenance(
            SampleRuntime.house(this),
            SampleRuntime.approvalCoordinator()
        ).deleteUserData(
            "data-house-${UUID.randomUUID()}",
            "product-center"
        )
        if (result.applied) "Agent House 已恢复默认" else result.reason
    }

    private fun deleteAutomationDomain() = runDataMutation {
        val result = GovernedScheduleService(
            repository = SampleRuntime.schedules(this),
            backend = AndroidSchedulerBackend(this),
            approvals = SampleRuntime.approvalCoordinator()
        ).deleteAll(
            "data-schedules-${UUID.randomUUID()}",
            "product-center"
        )
        if (result.applied) {
            val checkpoints = SampleRuntime.checkpoints(this).clear()
            "已删除 ${result.deletedSchedules} 个 schedule 和 $checkpoints 个 checkpoint"
        } else {
            result.reason
        }
    }

    private fun deleteOperationalDomain() = runDataMutation {
        val operational = SampleRuntime.clearOperationalJournals()
        val feedback = SampleRuntime.clearFeedback(this)
        val voice = SampleRuntime.voiceSessions().clear()
        "已清除 $operational 条运行日志、$feedback 条反馈、$voice 条语音文本"
    }

    private fun runDataMutation(operation: () -> String) {
        worker.execute {
            val result = runCatching(operation).getOrElse { error ->
                "操作失败：${error.message ?: error::class.java.simpleName}"
            }
            runOnUiThreadSafe {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun confirmDestructive(
        title: String,
        message: String,
        operation: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("继续") { _, _ -> operation() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addInfoCard(
        title: String,
        body: String,
        actions: List<Action> = emptyList()
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_surface_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            clipChildren = false
            clipToPadding = false
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.textPrimary))
        })
        card.addView(TextView(this).apply {
            text = body
            textSize = 12.5f
            setTextColor(getColor(R.color.textSecondary))
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(6), 0, 0)
            setTextIsSelectable(true)
        })
        if (actions.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(10), 0, 0)
            }
            actions.take(3).forEachIndexed { index, action ->
                row.addView(
                    Button(this).apply {
                        removeClippedShadow()
                        text = action.label
                        textSize = 11.5f
                        minWidth = 0
                        background = getDrawable(R.drawable.bg_secondary_button)
                        setTextColor(getColor(R.color.textPrimary))
                        setPadding(dp(12), 0, dp(12), 0)
                        setOnClickListener { action.invoke() }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(38)
                    ).apply {
                        if (index > 0) marginStart = dp(7)
                    }
                )
            }
            card.addView(row)
        }
        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        )
    }

    private fun addSectionLabel(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.textPrimary))
            setPadding(dp(2), dp(6), dp(2), dp(8))
        })
    }

    private fun addWorkbenchRow(
        title: String,
        body: String,
        actionLabel: String,
        action: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_settings_row)
            setPadding(dp(16), dp(13), dp(12), dp(13))
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.textPrimary))
        })
        copy.addView(TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(getColor(R.color.textSecondary))
            setLineSpacing(0f, 1.08f)
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(
            copy,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(
            Button(this).apply {
                removeClippedShadow()
                text = actionLabel
                textSize = 11.5f
                minWidth = 0
                background = getDrawable(R.drawable.bg_secondary_button)
                setTextColor(getColor(R.color.textPrimary))
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { action() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginStart = dp(10)
            }
        )
        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
    }

    private fun cadence(target: ScheduleTargetType, now: Long): ScheduleCadence =
        when (target) {
            ScheduleTargetType.HEARTBEAT -> ScheduleCadence.interval(15 * 60_000L)
            ScheduleTargetType.DREAM -> ScheduleCadence.daily(LocalTime.of(3, 0))
            ScheduleTargetType.PROACTIVE -> ScheduleCadence.interval(60 * 60_000L)
            ScheduleTargetType.CRON -> ScheduleCadence.daily(LocalTime.of(9, 0))
            ScheduleTargetType.LONG_TASK -> ScheduleCadence.interval(6 * 60 * 60_000L)
        }

    private fun scheduleId(target: ScheduleTargetType) =
        "sample-${target.name.lowercase().replace('_', '-')}"

    private fun targetLabel(target: ScheduleTargetType) = when (target) {
        ScheduleTargetType.HEARTBEAT -> "Heartbeat"
        ScheduleTargetType.DREAM -> "Dream"
        ScheduleTargetType.PROACTIVE -> "Proactive Jobs"
        ScheduleTargetType.CRON -> "Cron"
        ScheduleTargetType.LONG_TASK -> "LongTask"
    }

    private fun scheduleDescription(target: ScheduleTargetType) = when (target) {
        ScheduleTargetType.HEARTBEAT -> "每 15 分钟检查 typed finding；只读，不静默改长期状态。"
        ScheduleTargetType.DREAM -> "每日反思结果并生成候选；默认要求充电，不直接晋升。"
        ScheduleTargetType.PROACTIVE -> "每小时评估机会，受主动性档位、勿扰和每日上限约束。"
        ScheduleTargetType.CRON -> "每日 09:00 执行已批准的只读例行任务。"
        ScheduleTargetType.LONG_TASK -> "按 checkpoint 分 burst 继续，支持 Stop、deadline 和失败熔断。"
    }

    private fun providerCredentialAvailable(): Boolean {
        val profile = ProviderSettingsRepository(this).profile()
        return when (profile.kind.credentialMode) {
            ProviderCredentialMode.NONE -> true
            ProviderCredentialMode.API_KEY -> !profile.secret.isNullOrBlank()
            ProviderCredentialMode.CODEX_LOGIN -> CodexAuthRepository(this).getProfile() != null
        }
    }

    private fun stateExport(snapshot: AgentStateSnapshot): String = buildString {
        appendLine("Agent State export (summary only)")
        appendLine("documents=${snapshot.documents.size}")
        appendLine("evidence=${snapshot.evidence.size}")
        appendLine("effects=${snapshot.effects.size}")
        appendLine("candidates=${snapshot.candidates.size}")
        appendLine("revisions=${snapshot.revisions.size}")
        snapshot.candidates.forEach { candidate ->
            appendLine("${candidate.id}\t${candidate.kind}\t${candidate.status}\t${candidate.title}")
        }
    }

    private fun fullStateExport(snapshot: AgentStateSnapshot): String = buildString {
        appendLine("Agent State Vault export")
        appendLine("records=${snapshot.recordCount()}")
        snapshot.documents.forEach { value ->
            appendLine("\n[document ${value.collection}/${value.id} r${value.revision}]")
            appendLine(redact(value.title))
            appendLine(redact(value.content))
        }
        snapshot.candidates.forEach { value ->
            appendLine("\n[candidate ${value.id} ${value.kind} ${value.status}]")
            appendLine("hash=${value.candidateHash}")
            appendLine(redact(value.proposedContent))
        }
        snapshot.evalReports.forEach { value ->
            appendLine("\n[eval ${value.id} ${value.verdict}] ${redact(value.summary)}")
        }
        snapshot.revisions.forEach { value ->
            appendLine("\n[revision ${value.id} ${value.assetKey} ${value.status}]")
            appendLine("candidateHash=${value.candidateHash}")
            appendLine(redact(value.content))
        }
        snapshot.events.forEach { value ->
            appendLine("\n[event ${value.id}] ${redact(value.summary)}")
        }
        snapshot.evidence.forEach { value ->
            appendLine("\n[evidence ${value.id}] ${redact(value.summary)}")
        }
        snapshot.effects.forEach { value ->
            appendLine("\n[effect ${value.id} ${value.status}] ${redact(value.summary)}")
        }
    }

    private fun todoExport(snapshot: TodoDataSnapshot): String = buildString {
        appendLine("Agent Todo export")
        snapshot.items.forEach { value ->
            appendLine(
                "${value.id}\tr${value.revision}\t${value.state}\t" +
                    "${redact(value.title)}\t${redact(value.note)}"
            )
        }
        snapshot.effects.forEach { value ->
            appendLine(
                "effect\t${value.id}\t${value.todoId}\t${value.operation}\t" +
                    "${value.argumentHash}\t${value.approvalId}"
            )
        }
    }

    private fun houseExport(snapshot: AgentHouseSnapshot): String = buildString {
        appendLine("Agent House export")
        appendLine("name=${redact(snapshot.profile.name)}")
        snapshot.coreFiles.forEach { value ->
            appendLine("\n[core ${value.key}]")
            appendLine(redact(value.content))
        }
        snapshot.skills.forEach { value ->
            appendLine("\n[skill ${value.id} enabled=${value.enabled}]")
            appendLine(redact(value.content))
        }
        snapshot.dailyMemories.forEach { value ->
            appendLine("\n[legacy-journal ${value.date}]")
            appendLine(redact(value.content))
        }
    }

    private fun automationExport(inventory: DataInventory): String = buildString {
        appendLine("Agent Automation export")
        inventory.schedules.forEach { value ->
            appendLine(
                "${value.id}\t${value.targetType}\tr${value.revision}\t" +
                    "enabled=${value.enabled}\t${value.cadence}\t${value.timezone}"
            )
        }
        appendLine("checkpoints=${inventory.checkpoints}")
        appendLine("occurrenceReceipts=${inventory.occurrences}")
    }

    private fun dataCatalogExport(inventory: DataInventory): String = buildString {
        appendLine("Agent Harness data catalog")
        appendLine("sessions=${inventory.sessions}")
        appendLine("todoRecords=${inventory.todo.items.size}")
        appendLine("todoEffects=${inventory.todo.effects.size}")
        appendLine("stateRecords=${inventory.state.recordCount()}")
        appendLine("houseCore=${inventory.house.coreFiles.size}")
        appendLine("houseSkills=${inventory.house.skills.size}")
        appendLine("houseLegacyJournal=${inventory.house.dailyMemories.size}")
        appendLine("schedules=${inventory.schedules.size}")
        appendLine("checkpoints=${inventory.checkpoints}")
        appendLine("trace=${inventory.traces}")
        appendLine("approvals=${inventory.approvals}")
        appendLine("occurrences=${inventory.occurrences}")
        appendLine("feedback=${inventory.feedback}")
        appendLine("voicePersistence=${SampleRuntime.voiceSessions().persistenceEnabled}")
        appendLine("credentials=excluded")
    }

    private fun debugExport(
        traces: List<dev.androidagent.harness.sdk.AgentEvent>,
        approvals: List<AgentApprovalRecord>,
        receipts: List<DispatchReceipt>
    ): String = buildString {
        appendLine("Agent Harness replay export (redacted)")
        appendLine("events=${traces.size} approvals=${approvals.size} receipts=${receipts.size}")
        traces.forEach { event ->
            appendLine("${event.occurredAtEpochMillis}\t${event::class.simpleName}\t${event.runId}")
        }
        approvals.forEach { record ->
            appendLine("approval\t${record.decision}\t${record.request?.capabilityId.orEmpty()}")
        }
        receipts.forEach { receipt ->
            appendLine("occurrence\t${receipt.status}\t${receipt.occurrenceId}")
        }
    }

    private fun replayExport(report: AgentReplayReport): String = buildString {
        appendLine("Agent Harness replay evaluation")
        appendLine(
            "healthy=${report.healthy} events=${report.totalEvents} " +
                "completeRuns=${report.completeRunCount}/${report.runs.size}"
        )
        report.runs.forEach { run ->
            appendLine(
                "run\t${run.runId}\t${run.terminalState}\tevents=${run.eventCount}\t" +
                    "provider=${run.providerSteps}\ttools=${run.toolCalls}\t" +
                    "approvals=${run.approvalRequests}\tcomplete=${run.complete}"
            )
        }
        report.issues.forEach { issue ->
            appendLine(
                "issue\t${issue.severity}\t${issue.code}\t${issue.runId}\t${issue.summary}"
            )
        }
    }

    private fun shareText(subject: String, text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "导出"
            )
        )
    }

    private fun redact(value: String): String {
        return value
            .replace(Regex("(?i)(api[_-]?key|access[_-]?token|secret|password)=[^, )]+"), "$1=<redacted>")
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~-]+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
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

    private fun runOnUiThreadSafe(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    data class Action(val label: String, val invoke: () -> Unit)

    data class DataInventory(
        val sessions: Int,
        val todo: TodoDataSnapshot,
        val state: AgentStateSnapshot,
        val house: AgentHouseSnapshot,
        val schedules: List<ScheduleSpec>,
        val checkpoints: Int,
        val traces: Int,
        val approvals: Int,
        val occurrences: Int,
        val feedback: Int
    )

    enum class Section(
        val id: String,
        val label: String,
        val title: String,
        val subtitle: String
    ) {
        OVERVIEW("workbench", "总览", "工作台", "数据源、周期任务与运行记录"),
        STATS("stats", "统计", "使用统计", "聚合概览、明细边界与数据披露"),
        TODO("todo", "待办", "待办事项", "草稿、审批、变更记录与快速完成"),
        STATE("state", "状态", "候选与版本", "记忆、技能、人格候选与回滚资产"),
        AUTOMATION(
            "automation",
            "自动任务",
            "自动任务",
            "Heartbeat、Dream、Proactive、Cron、LongTask"
        ),
        PERMISSIONS("permissions", "权限", "权限与能力", "能力状态、用途、数据披露与系统设置"),
        DEBUG("debug", "调试", "运行与回放", "运行、上下文、工具、审批与检查点"),
        DATA("data", "数据", "本地数据", "按域导出、保留、删除与凭据边界");

        companion object {
            fun fromId(id: String?): Section =
                entries.firstOrNull { it.id == id } ?: OVERVIEW
        }
    }

    companion object {
        const val EXTRA_SECTION = "product_section"
        val PENDING_CANDIDATE_STATES = setOf(
            AgentCandidateStatus.PROPOSED,
            AgentCandidateStatus.VALIDATED,
            AgentCandidateStatus.EVALUATED,
            AgentCandidateStatus.WAITING_APPROVAL
        )
    }
}
