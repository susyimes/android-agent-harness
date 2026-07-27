// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class AgentHouseEditorActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private val repository by lazy { SampleRuntime.house(this) }
    private val editorType by lazy { intent.getStringExtra(EXTRA_TYPE).orEmpty() }
    private val entryId by lazy { intent.getStringExtra(EXTRA_ID).orEmpty() }
    private val entryTitle by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty() }

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var metadataPanel: LinearLayout
    private lateinit var skillName: EditText
    private lateinit var skillDescription: EditText
    private lateinit var skillEnabled: Switch
    private lateinit var markdown: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_house_editor)
        applySampleInsets(findViewById(R.id.editorRoot))
        titleView = findViewById(R.id.editorTitle)
        subtitleView = findViewById(R.id.editorSubtitle)
        metadataPanel = findViewById(R.id.skillMetadataPanel)
        skillName = findViewById(R.id.skillNameInput)
        skillDescription = findViewById(R.id.skillDescriptionInput)
        skillEnabled = findViewById(R.id.skillEnabledSwitch)
        markdown = findViewById(R.id.markdownInput)
        findViewById<Button>(R.id.editorCancelButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.editorSaveButton).setOnClickListener { save() }
        load()
    }

    override fun onDestroy() {
        diskExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun load() {
        titleView.text = entryTitle.ifBlank { "编辑 House" }
        subtitleView.text = when (editorType) {
            TYPE_CORE -> "core/$entryId"
            TYPE_SKILL -> "skills/$entryId/SKILL.md"
            TYPE_MEMORY -> "memory/$entryId.md"
            else -> entryId
        }
        metadataPanel.visibility = if (editorType == TYPE_SKILL) View.VISIBLE else View.GONE
        onDisk {
            when (editorType) {
                TYPE_CORE -> repository.readCoreFile(entryId)?.let { file ->
                    EditorValue(content = file.content)
                }
                TYPE_SKILL -> repository.readSkill(entryId)?.let { skill ->
                    EditorValue(
                        content = skill.content,
                        name = skill.name,
                        description = skill.description,
                        enabled = skill.enabled
                    )
                }
                TYPE_MEMORY -> repository.readDailyMemory(entryId)?.let { memory ->
                    EditorValue(content = memory.content)
                }
                else -> null
            }
        }
    }

    private fun onDisk(load: () -> EditorValue?) {
        diskExecutor.execute {
            val result = runCatching(load)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess { value ->
                    if (value == null) {
                        Toast.makeText(this, "House 条目不存在", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        markdown.setText(value.content)
                        if (editorType == TYPE_SKILL) {
                            skillName.setText(value.name)
                            skillDescription.setText(value.description)
                            skillEnabled.isChecked = value.enabled
                        }
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "无法读取 House 条目",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun save() {
        val content = markdown.text.toString()
        if (content.isBlank()) {
            markdown.error = "内容不能为空"
            return
        }
        val name = skillName.text.toString()
        val description = skillDescription.text.toString()
        findViewById<Button>(R.id.editorSaveButton).isEnabled = false
        diskExecutor.execute {
            val result = runCatching {
                when (editorType) {
                    TYPE_CORE -> repository.updateCoreFile(entryId, content)
                    TYPE_SKILL -> repository.saveSkill(
                        id = entryId,
                        name = name,
                        description = description,
                        content = content,
                        enabled = skillEnabled.isChecked
                    )
                    TYPE_MEMORY -> repository.updateDailyMemory(entryId, content)
                    else -> error("Unknown House entry type.")
                }
            }
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess {
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { error ->
                    findViewById<Button>(R.id.editorSaveButton).isEnabled = true
                    Toast.makeText(
                        this,
                        error.message ?: "保存失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private data class EditorValue(
        val content: String,
        val name: String = "",
        val description: String = "",
        val enabled: Boolean = false
    )

    companion object {
        const val TYPE_CORE = "core"
        const val TYPE_SKILL = "skill"
        const val TYPE_MEMORY = "memory"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, type: String, id: String, title: String): Intent {
            return Intent(context, AgentHouseEditorActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }
}
