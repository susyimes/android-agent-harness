// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import dev.androidagent.harness.sdk.house.AgentHouseCoreFile
import dev.androidagent.harness.sdk.house.AgentHouseDailyMemory
import dev.androidagent.harness.sdk.house.AgentHouseSkill
import dev.androidagent.harness.sdk.house.AgentHouseSnapshot
import java.util.concurrent.Executors

class AgentHouseActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private val repository by lazy { SampleRuntime.house(this) }

    private lateinit var houseName: TextView
    private lateinit var houseMeta: TextView
    private lateinit var entries: LinearLayout
    private var lastSnapshot: AgentHouseSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_house)
        applySampleInsets(findViewById(R.id.houseRoot))
        houseName = findViewById(R.id.houseName)
        houseMeta = findViewById(R.id.houseMeta)
        entries = findViewById(R.id.houseEntries)
        bindSampleNavigation(SampleTab.AGENT)
        findViewById<Button>(R.id.houseBackButton).apply {
            removeClippedShadow()
            setOnClickListener {
                startActivity(Intent(this@AgentHouseActivity, SettingsActivity::class.java))
            }
        }
        findViewById<Button>(R.id.renameHouseButton).apply {
            removeClippedShadow()
            setOnClickListener { showRenameDialog() }
        }
        findViewById<Button>(R.id.houseReviewButton).apply {
            removeClippedShadow()
            setOnClickListener {
            startActivity(
                Intent(this@AgentHouseActivity, ProductCenterActivity::class.java)
                    .putExtra(ProductCenterActivity.EXTRA_SECTION, "state")
            )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadHouse()
    }

    override fun onDestroy() {
        diskExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadHouse() {
        houseMeta.text = "正在读取本地文件…"
        onDisk({ repository.snapshot() }, ::renderHouse)
    }

    private fun renderHouse(snapshot: AgentHouseSnapshot) {
        lastSnapshot = snapshot
        houseName.text = snapshot.profile.name
        houseMeta.text = "${snapshot.coreFiles.size} 个核心文件 · " +
            "${snapshot.skills.count { skill -> skill.enabled }}/${snapshot.skills.size} 个技能启用 · " +
            "${snapshot.dailyMemories.size} 篇每日记忆"
        entries.removeAllViews()

        addSection("核心设定", "身份、人格、协作方式与长期上下文")
        snapshot.coreFiles.forEach(::addCoreFile)

        addSection("技能", "Agent 提出的技能先进入候选评估，批准后才会启用")
        if (snapshot.skills.isEmpty()) addEmpty("还没有技能草案。Agent 会在确有复用价值时创建。")
        snapshot.skills.forEach(::addSkill)

        addSection("记忆", "保留旧版每日记忆；新记忆统一进入候选审核")
        if (snapshot.dailyMemories.isEmpty()) addEmpty("还没有每日记忆。Agent 会按需沉淀长期有用的信息。")
        snapshot.dailyMemories.forEach(::addMemory)
    }

    private fun addSection(title: String, subtitle: String) {
        entries.addView(
            TextView(this).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.textPrimary))
                setPadding(dp(2), dp(18), dp(2), 0)
            }
        )
        entries.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(getColor(R.color.textSecondary))
                setPadding(dp(2), dp(3), dp(2), dp(8))
            }
        )
    }

    private fun addCoreFile(file: AgentHouseCoreFile) {
        entries.addView(
            entryCard(
                title = file.title,
                subtitle = file.description,
                badge = if (file.isDefault) "默认" else "已修改",
                primaryAction = "编辑" to {
                    openEditor(AgentHouseEditorActivity.TYPE_CORE, file.key, file.title)
                },
                secondaryAction = if (file.isDefault) null else {
                    "恢复" to { confirmRestoreCore(file) }
                }
            ),
            cardParams()
        )
    }

    private fun addSkill(skill: AgentHouseSkill) {
        entries.addView(
            entryCard(
                title = skill.name,
                subtitle = buildString {
                    append(skill.description.ifBlank { skill.id })
                    if (skill.origin == dev.androidagent.harness.sdk.house.AgentHouseOrigin.AGENT) {
                        append(" · Agent 创建")
                    }
                },
                badge = if (skill.enabled) "已审核启用" else "待审核",
                primaryAction = (if (skill.enabled) "停用" else "审核") to {
                    if (skill.enabled) {
                        onDisk(
                            { repository.setSkillEnabled(skill.id, false) },
                            { loadHouse() }
                        )
                    } else {
                        startActivity(
                            Intent(this, ProductCenterActivity::class.java)
                                .putExtra(ProductCenterActivity.EXTRA_SECTION, "state")
                        )
                    }
                },
                secondaryAction = "查看" to {
                    showReadOnlyAsset(
                        title = skill.name,
                        metadata = "${skill.reviewStatus} · ${skill.origin} · ${skill.source}",
                        content = skill.content
                    )
                },
                destructiveAction = "删除" to { confirmDeleteSkill(skill) }
            ),
            cardParams()
        )
    }

    private fun addMemory(memory: AgentHouseDailyMemory) {
        val summary = memory.content.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .take(80)
        entries.addView(
            entryCard(
                title = memory.date,
                subtitle = summary.ifBlank { "每日记忆" },
                badge = if (
                    memory.origin == dev.androidagent.harness.sdk.house.AgentHouseOrigin.AGENT
                ) {
                    "Agent 写入"
                } else {
                    "用户"
                },
                primaryAction = "查看" to {
                    showReadOnlyAsset(
                        title = memory.date,
                        metadata = "${memory.reviewStatus} · ${memory.origin} · ${memory.source}",
                        content = memory.content
                    )
                },
                destructiveAction = "删除" to { confirmDeleteMemory(memory) }
            ),
            cardParams()
        )
    }

    private fun entryCard(
        title: String,
        subtitle: String,
        badge: String,
        primaryAction: Pair<String, () -> Unit>,
        secondaryAction: Pair<String, () -> Unit>? = null,
        destructiveAction: Pair<String, () -> Unit>? = null
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_surface_card)
            setPadding(dp(15), dp(13), dp(15), dp(12))

            val titleRow = LinearLayout(this@AgentHouseActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            titleRow.addView(
                TextView(this@AgentHouseActivity).apply {
                    text = title
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.textPrimary))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            titleRow.addView(
                TextView(this@AgentHouseActivity).apply {
                    text = badge
                    textSize = 11f
                    setTextColor(getColor(R.color.primary))
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    background = roundedBackground(
                        getColor(R.color.primarySoft),
                        Color.TRANSPARENT,
                        12
                    )
                }
            )
            addView(titleRow)
            addView(
                TextView(this@AgentHouseActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(getColor(R.color.textSecondary))
                    setPadding(0, dp(5), 0, dp(9))
                }
            )
            val actions = LinearLayout(this@AgentHouseActivity).apply {
                gravity = Gravity.END
                orientation = LinearLayout.HORIZONTAL
            }
            listOfNotNull(primaryAction, secondaryAction, destructiveAction)
                .forEachIndexed { index, action ->
                    if (index > 0) actions.addView(Space(this@AgentHouseActivity), LinearLayout.LayoutParams(dp(7), 1))
                    actions.addView(
                        smallButton(
                            label = action.first,
                            danger = destructiveAction === action,
                            onClick = action.second
                        )
                    )
                }
            addView(actions)
        }
    }

    private fun smallButton(label: String, danger: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            removeClippedShadow()
            text = label
            textSize = 12f
            minWidth = 0
            minimumHeight = 0
            setPadding(dp(11), 0, dp(11), 0)
            setTextColor(getColor(if (danger) R.color.danger else R.color.textPrimary))
            background = getDrawable(R.drawable.bg_secondary_button)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
        }
    }

    private fun addEmpty(message: String) {
        entries.addView(
            TextView(this).apply {
                text = message
                textSize = 12f
                setTextColor(getColor(R.color.textSecondary))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = getDrawable(R.drawable.bg_surface_soft)
            },
            cardParams()
        )
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(lastSnapshot?.profile?.name.orEmpty())
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("修改 Agent 名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                onDisk(
                    { repository.renameHouse(input.text.toString()) },
                    { loadHouse() }
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showReadOnlyAsset(
        title: String,
        metadata: String,
        content: String
    ) {
        val view = TextView(this).apply {
            text = "$metadata\n\n$content"
            textSize = 13f
            setTextColor(getColor(R.color.textPrimary))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmRestoreCore(file: AgentHouseCoreFile) {
        AlertDialog.Builder(this)
            .setTitle("恢复 ${file.title}？")
            .setMessage("当前内容会替换为 SDK 提供的通用默认值。")
            .setPositiveButton("恢复") { _, _ ->
                onDisk({ repository.restoreCoreFile(file.key) }, { loadHouse() })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSkill(skill: AgentHouseSkill) {
        AlertDialog.Builder(this)
            .setTitle("删除技能“${skill.name}”？")
            .setMessage("本地 SKILL.md 与启用状态会一起删除。")
            .setPositiveButton("删除") { _, _ ->
                onDisk({ repository.deleteSkill(skill.id) }, { loadHouse() })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteMemory(memory: AgentHouseDailyMemory) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${memory.date} 的记忆？")
            .setPositiveButton("删除") { _, _ ->
                onDisk({ repository.deleteDailyMemory(memory.date) }, { loadHouse() })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openEditor(type: String, id: String, title: String) {
        startActivity(AgentHouseEditorActivity.intent(this, type, id, title))
    }

    private fun cardParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun <T> onDisk(block: () -> T, success: (T) -> Unit) {
        diskExecutor.execute {
            val result = runCatching(block)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess(success).onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "本地文件操作失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
