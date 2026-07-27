// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessLimitException
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentSessionStore
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.InMemoryAgentSessionStore
import dev.androidagent.harness.StaticAgentContextProvider
import dev.androidagent.harness.deviceloop.DeviceActTool
import dev.androidagent.harness.deviceloop.DeviceFinishTool
import dev.androidagent.harness.deviceloop.DeviceLoopProfile
import dev.androidagent.harness.deviceloop.DeviceObserveTool
import dev.androidagent.harness.deviceloop.android.AccessibilityAvailability
import dev.androidagent.harness.deviceloop.android.AccessibilityDeviceSurface
import dev.androidagent.harness.deviceloop.android.HarnessAccessibilityService
import dev.androidagent.harness.deviceloop.android.OverlayApprovalGate
import dev.androidagent.harness.provider.openai.CodexCredential
import dev.androidagent.harness.provider.openai.CodexCredentialProvider
import dev.androidagent.harness.provider.openai.CodexResponsesConfig
import dev.androidagent.harness.provider.openai.CodexResponsesProvider
import dev.androidagent.harness.provider.openai.HttpRequestCancelledException
import dev.androidagent.harness.provider.openai.HttpTransportException
import dev.androidagent.harness.provider.openai.OpenAiCompatibleConfig
import dev.androidagent.harness.provider.openai.OpenAiCompatibleProvider
import dev.androidagent.harness.provider.openai.UrlConnectionHttpTransport
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Productized Android sample for the bounded Agent Harness runtime. */
class MainActivity : Activity() {

    private lateinit var mainHandler: Handler
    private lateinit var executor: ExecutorService
    private lateinit var authExecutor: ExecutorService
    private lateinit var dialogGate: DialogApprovalGate
    private lateinit var approvalGate: PhoneApprovalGate
    private lateinit var providerSettings: ProviderSettingsRepository
    private lateinit var codexRepository: CodexAuthRepository
    private lateinit var codexAuth: CodexAuthService

    private lateinit var contentRoot: LinearLayout
    private lateinit var sessionSubtitle: TextView
    private lateinit var newChatButton: Button
    private lateinit var settingsButton: Button
    private lateinit var providerSelector: Button
    private lateinit var phoneSwitch: Switch
    private lateinit var transcriptScroll: ScrollView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyDescription: TextView
    private lateinit var emptySetupButton: Button
    private lateinit var statusDot: TextView
    private lateinit var statusView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    @Volatile
    private var authInProgress = false

    @Volatile
    private var activeBrowserSession: CodexBrowserSession? = null

    @Volatile
    private var authTask: Future<*>? = null

    @Volatile
    private var authAttempt = 0L

    private var turnSequence = 0L
    private var activeTurn: ActiveTurn? = null

    private val deviceSurface by lazy {
        AccessibilityDeviceSurface(
            serviceProvider = { HarnessAccessibilityService.connectedInstance() }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainHandler = Handler(Looper.getMainLooper())
        executor = Executors.newSingleThreadExecutor()
        authExecutor = Executors.newSingleThreadExecutor()
        providerSettings = ProviderSettingsRepository(this)
        codexRepository = CodexAuthRepository(this)
        codexAuth = CodexAuthService(codexRepository)
        dialogGate = DialogApprovalGate(this, mainHandler, ::onApprovalWaitingChanged)
        approvalGate = PhoneApprovalGate(
            overlay = OverlayApprovalGate(
                serviceProvider = { HarnessAccessibilityService.connectedInstance() },
                onWaitingChanged = ::onApprovalWaitingChanged
            ),
            dialog = dialogGate
        )

        setContentView(R.layout.activity_main)
        bindViews()
        applyWindowInsets(contentRoot)
        setupInteractions()
        updateProviderUi()
        updateEmptyState()
    }

    override fun onResume() {
        super.onResume()
        if (::providerSelector.isInitialized) updateProviderUi()
    }

    override fun onDestroy() {
        activeTurn?.let { turn ->
            activeTurn = null
            turn.sessionStore.discard()
            runCatching(turn.cancelProvider)
            turn.future?.cancel(true)
        }
        dialogGate.cancelPending()
        authAttempt += 1
        activeBrowserSession?.callback?.close()
        activeBrowserSession = null
        authTask?.cancel(true)
        authTask = null
        authInProgress = false
        executor.shutdownNow()
        authExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindViews() {
        contentRoot = findViewById(R.id.contentRoot)
        sessionSubtitle = findViewById(R.id.sessionSubtitle)
        newChatButton = findViewById(R.id.newChatButton)
        settingsButton = findViewById(R.id.settingsButton)
        providerSelector = findViewById(R.id.providerSelector)
        phoneSwitch = findViewById(R.id.phoneSwitch)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        transcriptContainer = findViewById(R.id.transcriptContainer)
        emptyState = findViewById(R.id.emptyState)
        emptyDescription = findViewById(R.id.emptyDescription)
        emptySetupButton = findViewById(R.id.emptySetupButton)
        statusDot = findViewById(R.id.statusDot)
        statusView = findViewById(R.id.statusView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
    }

    private fun setupInteractions() {
        newChatButton.setOnClickListener { startNewChat(showToast = true) }
        settingsButton.setOnClickListener { showProviderDialog() }
        providerSelector.setOnClickListener { showProviderDialog() }
        emptySetupButton.setOnClickListener { showProviderDialog() }
        sendButton.setOnClickListener {
            if (isBusy()) stopActiveTurn() else onSend()
        }
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (!isBusy()) onSend()
                true
            } else {
                false
            }
        }
        phoneSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) onPhoneModeRequested()
        }
    }

    /**
     * Android 15 forces edge-to-edge for target 35+, so merge system-bar and
     * IME insets into the layout's authored padding.
     */
    private fun applyWindowInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars: Insets
            val ime: Insets
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bars = insets.getInsets(WindowInsets.Type.systemBars())
                ime = insets.getInsets(WindowInsets.Type.ime())
            } else {
                @Suppress("DEPRECATION")
                bars = Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                ime = Insets.NONE
            }
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
        root.requestApplyInsets()
    }

    // -------------------------------------------------------------- providers

    private fun showProviderDialog() {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(10))
        }
        scroll.addView(content)

        content.addView(
            bodyText(
                "为每个提供商分别保存模型与凭据。切换提供商会开始一段新会话，" +
                    "避免把不同模型的工具历史混在一起。"
            )
        )

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(radioGroup)

        val configPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(configPanel)

        var selectedKind = providerSettings.activeKind()
        var fields = ProviderDialogFields()
        val radioIds = linkedMapOf<Int, ProviderKind>()
        ProviderKind.entries.forEach { kind ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${kind.title}\n${kind.subtitle}"
                textSize = 14f
                setTextColor(getColor(R.color.textPrimary))
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(getColor(R.color.primary), getColor(R.color.textSecondary))
                )
                background = roundedDrawable(
                    getColor(R.color.surface),
                    dp(16).toFloat(),
                    getColor(R.color.cardStroke)
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            radioIds[button.id] = kind
            radioGroup.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            )
            if (kind == selectedKind) button.isChecked = true
        }

        fun rebuildConfiguration(kind: ProviderKind) {
            selectedKind = kind
            configPanel.removeAllViews()
            fields = buildProviderConfiguration(kind, configPanel)
        }
        rebuildConfiguration(selectedKind)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            radioIds[checkedId]?.let(::rebuildConfiguration)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择模型提供商")
            .setView(scroll)
            .setPositiveButton("保存并使用", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val oldProfile = providerSettings.profile()
                val model = normalizedModel(fields.modelValue?.invoke(), selectedKind)
                val baseUrl = fields.baseUrl?.text?.toString().orEmpty()
                    .ifBlank { selectedKind.defaultBaseUrl }
                if (selectedKind.credentialMode == ProviderCredentialMode.API_KEY) {
                    if (!baseUrl.startsWith("https://")) {
                        fields.baseUrl?.error = "请输入 HTTPS 地址"
                        return@setOnClickListener
                    }
                    if (!providerSettings.hasSecret(selectedKind) &&
                        fields.secret?.text?.toString().isNullOrBlank()
                    ) {
                        fields.secret?.error = "请填写 ${selectedKind.title} API Key"
                        return@setOnClickListener
                    }
                }
                val saved = providerSettings.saveAndSelect(
                    kind = selectedKind,
                    model = model,
                    baseUrl = baseUrl,
                    replacementSecret = fields.secret?.text?.toString()
                )
                if (oldProfile.kind != saved.kind || oldProfile.model != saved.model) {
                    startNewChat(showToast = false)
                }
                updateProviderUi()
                updateEmptyState()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun buildProviderConfiguration(
        kind: ProviderKind,
        panel: LinearLayout
    ): ProviderDialogFields {
        val profile = providerSettings.profile(kind)
        var modelValue: (() -> String)? = null
        var baseField: EditText? = null
        var secretField: EditText? = null

        if (kind != ProviderKind.OFFLINE) {
            panel.addView(sectionLabel("模型"))
            if (kind == ProviderKind.CUSTOM) {
                val customModelField = EditText(this).apply {
                    setText(profile.model)
                    hint = kind.defaultModel
                    inputType = InputType.TYPE_CLASS_TEXT
                    styleInput(this)
                }
                modelValue = { customModelField.text.toString() }
                panel.addView(customModelField, matchWrapParams(bottom = 10))
            } else {
                val spinner = Spinner(this, Spinner.MODE_DROPDOWN).apply {
                    val choices = kind.models.map { preset ->
                        "${preset.id} — ${preset.label}"
                    }
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_item,
                        choices
                    ).also { choiceAdapter ->
                        choiceAdapter.setDropDownViewResource(
                            android.R.layout.simple_spinner_dropdown_item
                        )
                    }
                    background = getDrawable(R.drawable.bg_surface_card)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    minimumHeight = dp(50)
                    val selectedIndex = kind.models.indexOfFirst { preset ->
                        preset.id == profile.model
                    }.coerceAtLeast(0)
                    setSelection(selectedIndex)
                }
                modelValue = {
                    kind.models.getOrElse(spinner.selectedItemPosition) {
                        kind.models.first()
                    }.id
                }
                panel.addView(spinner, matchWrapParams(bottom = 10))
            }
        }

        when (kind.credentialMode) {
            ProviderCredentialMode.NONE -> {
                panel.addView(
                    infoCard(
                        "离线演示使用确定性脚本 Provider，可验证会话、上下文与工具调用，" +
                            "不会访问网络，也不能启用 Phone Mode。"
                    )
                )
            }

            ProviderCredentialMode.CODEX_LOGIN -> {
                val authProfile = codexRepository.getProfile()
                val status = when {
                    authInProgress -> "正在等待浏览器授权…"
                    codexRepository.hasStorageFailure() ->
                        "本机加密登录数据无法读取，请退出登录后重新授权"
                    authProfile == null -> "尚未登录"
                    authProfile.isExpired() -> "已登录 · token 将在发送时刷新"
                    authProfile.email.isNotBlank() ->
                        "已登录 · ${authProfile.email}\n到期：${formatTime(authProfile.expiresAtMs)}"
                    else -> "已登录 · 到期：${formatTime(authProfile.expiresAtMs)}"
                }
                panel.addView(sectionLabel("ChatGPT 登录"))
                panel.addView(
                    infoCard(
                        "$status\n\nCodex 登录是实验能力。浏览器登录使用 PKCE，" +
                            "设备码登录可作为 localhost 回调失败时的备用方案。"
                    )
                )
                panel.addView(
                    actionButton("使用 ChatGPT 登录", primary = true).apply {
                        isEnabled = !authInProgress
                        setOnClickListener { startCodexBrowserLogin() }
                    },
                    matchWrapParams(top = 10)
                )
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(
                    actionButton("设备码登录").apply {
                        isEnabled = !authInProgress
                        setOnClickListener { startCodexDeviceLogin() }
                    },
                    weightedParams()
                )
                row.addView(Space(this), LinearLayout.LayoutParams(dp(8), 1))
                row.addView(
                    actionButton("退出登录").apply {
                        isEnabled = (authProfile != null || codexRepository.hasStorageFailure()) &&
                            !authInProgress
                        setOnClickListener { confirmCodexLogout() }
                    },
                    weightedParams()
                )
                panel.addView(row, matchWrapParams(top = 8))
            }

            ProviderCredentialMode.API_KEY -> {
                panel.addView(sectionLabel("Base URL"))
                baseField = EditText(this).apply {
                    setText(profile.baseUrl)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    styleInput(this)
                }
                panel.addView(baseField, matchWrapParams(bottom = 10))

                panel.addView(sectionLabel("${kind.title} API Key"))
                secretField = EditText(this).apply {
                    hint = if (profile.secret.isNullOrBlank()) {
                        "输入密钥"
                    } else {
                        "已加密保存 · 留空保持不变"
                    }
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    styleInput(this)
                }
                panel.addView(secretField, matchWrapParams(bottom = 8))
                panel.addView(
                    bodyText(
                        "密钥使用 Android Keystore 加密后保存在本机，只会发送到上面的端点。"
                    )
                )
                if (!profile.secret.isNullOrBlank() ||
                    providerSettings.hasStorageFailure(kind)
                ) {
                    panel.addView(
                        actionButton(
                            if (providerSettings.hasStorageFailure(kind)) {
                                "清除损坏的本机凭据"
                            } else {
                                "清除已保存的密钥"
                            }
                        ).apply {
                            setOnClickListener {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("清除 ${kind.title} 密钥？")
                                    .setMessage("清除后，该提供商需要重新填写密钥才能使用。")
                                    .setPositiveButton("清除") { _, _ ->
                                        providerSettings.clearSecret(kind)
                                        secretField.hint = "输入密钥"
                                        updateProviderUi()
                                        Toast.makeText(
                                            this@MainActivity,
                                            "密钥已清除",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        },
                        matchWrapParams(top = 8)
                    )
                }
            }
        }
        return ProviderDialogFields(modelValue, baseField, secretField)
    }

    private fun normalizedModel(raw: String?, kind: ProviderKind): String {
        return raw.orEmpty()
            .substringBefore(" — ")
            .trim()
            .ifBlank { kind.defaultModel }
    }

    private fun updateProviderUi() {
        val profile = providerSettings.profile()
        val readiness = providerReadiness(profile.kind)
        providerSelector.text = buildString {
            append(if (readiness.ready) "●  " else "○  ")
            append(profile.kind.title)
            append("  ·  ")
            append(profile.kind.modelLabel(profile.model))
            append('\n')
            append(readiness.detail)
        }
        sessionSubtitle.text = "会话 $currentSessionId · ${profile.kind.title}"
        if (!isBusy()) {
            statusView.text = readiness.detail
            statusDot.setTextColor(readiness.color)
        }
        updateEmptyState()
    }

    private fun providerReadiness(kind: ProviderKind): ProviderReadiness {
        if (authInProgress && kind == ProviderKind.CODEX) {
            return ProviderReadiness(false, "正在等待 Codex 登录…", getColor(R.color.warning))
        }
        return when (kind) {
            ProviderKind.OFFLINE ->
                ProviderReadiness(true, "离线演示已就绪", getColor(R.color.success))
            ProviderKind.CODEX -> {
                val profile = codexRepository.getProfile()
                when {
                    codexRepository.hasStorageFailure() ->
                        ProviderReadiness(
                            false,
                            "本机加密登录数据无法读取，请重新登录",
                            getColor(R.color.danger)
                        )
                    profile == null ->
                        ProviderReadiness(false, "尚未登录 ChatGPT", getColor(R.color.warning))
                    profile.isExpired() ->
                        ProviderReadiness(true, "已登录 · 发送时自动刷新", getColor(R.color.warning))
                    profile.email.isNotBlank() ->
                        ProviderReadiness(true, "已登录 · ${profile.email}", getColor(R.color.success))
                    else ->
                        ProviderReadiness(true, "Codex 已登录", getColor(R.color.success))
                }
            }
            else -> {
                val hasSecret = providerSettings.hasSecret(kind)
                when {
                    providerSettings.hasStorageFailure(kind) ->
                        ProviderReadiness(
                            false,
                            "本机加密凭据无法读取，请清除后重填",
                            getColor(R.color.danger)
                        )
                    hasSecret ->
                        ProviderReadiness(
                            true,
                            "API Key 已加密保存",
                            getColor(R.color.success)
                        )
                    else ->
                        ProviderReadiness(
                            false,
                            "尚未配置 API Key",
                            getColor(R.color.warning)
                        )
                }
            }
        }
    }

    private fun updateEmptyState() {
        if (transcriptContainer.childCount > 0) {
            emptyState.visibility = View.GONE
            return
        }
        emptyState.visibility = View.VISIBLE
        val kind = providerSettings.activeKind()
        emptyDescription.text = when (kind) {
            ProviderKind.OFFLINE ->
                "当前是离线演示，可直接发送消息验证工具循环；选择在线提供商后可进行真实模型对话。"
            ProviderKind.CODEX ->
                "使用 Codex 回答并调用 Harness 工具；首次使用请完成 ChatGPT 登录。"
            ProviderKind.KIMI_PLAN ->
                "使用 Kimi Plan 的 Coding API 与 K3 / K2 系列模型。"
            ProviderKind.ARK_PLAN ->
                "使用 Ark Plan 的统一端点，在 Doubao、GLM、MiniMax、DeepSeek 间选择。"
            ProviderKind.CUSTOM ->
                "使用你配置的 OpenAI Chat Completions 兼容端点。"
        }
        emptySetupButton.text = if (kind == ProviderKind.OFFLINE) {
            "选择在线提供商"
        } else {
            "查看提供商设置"
        }
    }

    // ------------------------------------------------------------- Codex auth

    private fun startCodexBrowserLogin() {
        if (authInProgress) return
        authInProgress = true
        updateProviderUi()
        val attempt = ++authAttempt
        authTask = authExecutor.submit {
            try {
                val session = codexAuth.startBrowserOAuth()
                if (attempt != authAttempt) {
                    session.callback.close()
                    return@submit
                }
                activeBrowserSession = session
                postToUi {
                    showBrowserLoginDialog(session)
                    openUrl(session.authorizationUrl)
                }
                codexAuth.completeBrowserOAuth(session)
                if (attempt != authAttempt) return@submit
                postToUi {
                    Toast.makeText(this, "Codex 登录完成", Toast.LENGTH_SHORT).show()
                }
            } catch (error: RuntimeException) {
                if (attempt == authAttempt) {
                    postToUi {
                        Toast.makeText(
                            this,
                            error.message ?: "Codex 登录失败",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                if (attempt == authAttempt) {
                    activeBrowserSession?.callback?.close()
                    activeBrowserSession = null
                    authTask = null
                    authInProgress = false
                    postToUi(::updateProviderUi)
                }
            }
        }
    }

    private fun showBrowserLoginDialog(session: CodexBrowserSession) {
        AlertDialog.Builder(this)
            .setTitle("使用 ChatGPT 登录")
            .setMessage(
                "浏览器将完成 OpenAI 登录，并回到手机本机 localhost 回调。" +
                    "如果浏览器显示成功但应用没有收到结果，可以手动粘贴回调链接。"
            )
            .setPositiveButton("重新打开浏览器") { _, _ -> openUrl(session.authorizationUrl) }
            .setNeutralButton("粘贴回调") { _, _ -> showManualCallbackDialog(session) }
            .setNegativeButton("取消登录") { _, _ -> cancelCodexLogin() }
            .show()
    }

    private fun showManualCallbackDialog(session: CodexBrowserSession) {
        val input = EditText(this).apply {
            hint = "粘贴 http://localhost:1455/auth/callback?code=…&state=…"
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("粘贴 OAuth 回调")
            .setView(input)
            .setPositiveButton("提交") { _, _ ->
                runCatching {
                    session.callback.submitManualInput(input.text.toString())
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "回调内容无效",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCodexDeviceLogin() {
        if (authInProgress) return
        authInProgress = true
        updateProviderUi()
        val attempt = ++authAttempt
        authTask = authExecutor.submit {
            try {
                val prompt = codexAuth.requestDeviceCode()
                if (attempt != authAttempt) return@submit
                postToUi {
                    showDeviceCodeDialog(prompt)
                    openUrl(prompt.verificationUrl)
                }
                codexAuth.pollAndExchange(prompt)
                if (attempt != authAttempt) return@submit
                postToUi {
                    Toast.makeText(this, "Codex 登录完成", Toast.LENGTH_SHORT).show()
                }
            } catch (error: RuntimeException) {
                if (attempt == authAttempt) {
                    postToUi {
                        Toast.makeText(
                            this,
                            error.message ?: "Codex 设备码登录失败",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                if (attempt == authAttempt) {
                    authTask = null
                    authInProgress = false
                    postToUi(::updateProviderUi)
                }
            }
        }
    }

    private fun showDeviceCodeDialog(prompt: CodexDevicePrompt) {
        AlertDialog.Builder(this)
            .setTitle("Codex 设备码登录")
            .setMessage(
                "验证码：${prompt.userCode}\n\n浏览器会打开 ${prompt.verificationUrl}。" +
                    "输入验证码并授权，应用会在后台等待结果。"
            )
            .setPositiveButton("打开登录页") { _, _ -> openUrl(prompt.verificationUrl) }
            .setNeutralButton("复制验证码") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Codex verification code", prompt.userCode)
                )
                Toast.makeText(this, "验证码已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消登录") { _, _ -> cancelCodexLogin() }
            .show()
    }

    private fun cancelCodexLogin() {
        authAttempt += 1
        activeBrowserSession?.callback?.close()
        activeBrowserSession = null
        authTask?.cancel(true)
        authTask = null
        authInProgress = false
        updateProviderUi()
        Toast.makeText(this, "Codex 登录已取消", Toast.LENGTH_SHORT).show()
    }

    private fun confirmCodexLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出 Codex？")
            .setMessage("本机保存的 Codex token 将被清除，其他提供商密钥不受影响。")
            .setPositiveButton("退出登录") { _, _ ->
                codexAuth.logout()
                updateProviderUi()
                Toast.makeText(this, "已退出 Codex", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "无法打开浏览器，请复制链接后手动访问。", Toast.LENGTH_LONG)
                .show()
        }
    }

    // ------------------------------------------------------------ phone mode

    private fun onPhoneModeRequested() {
        val profile = providerSettings.profile()
        if (!providerReadiness(profile.kind).ready || profile.kind == ProviderKind.OFFLINE) {
            phoneSwitch.isChecked = false
            appendLine(LineKind.ERROR, getString(R.string.msg_phone_needs_live_provider))
            showProviderDialog()
            return
        }
        if (HarnessAccessibilityService.connectedInstance() != null) return
        phoneSwitch.isChecked = false
        if (AccessibilityAvailability.isServiceEnabled(this)) {
            appendLine(LineKind.INFO, getString(R.string.msg_service_enabled_not_connected))
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_dialog_title)
                .setMessage(R.string.accessibility_dialog_message)
                .setPositiveButton(R.string.btn_open_accessibility_settings) { _, _ ->
                    startActivity(AccessibilityAvailability.settingsIntent())
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ turn

    private fun onSend() {
        val userText = inputField.text.toString().trim()
        if (userText.isEmpty() || isBusy()) return

        val profile = providerSettings.profile()
        val readiness = providerReadiness(profile.kind)
        val phoneMode = phoneSwitch.isChecked
        if (!readiness.ready) {
            appendLine(LineKind.ERROR, readiness.detail)
            showProviderDialog()
            return
        }
        if (phoneMode && profile.kind == ProviderKind.OFFLINE) {
            appendLine(LineKind.ERROR, getString(R.string.msg_phone_needs_live_provider))
            return
        }
        if (phoneMode && HarnessAccessibilityService.connectedInstance() == null) {
            appendLine(LineKind.ERROR, getString(R.string.msg_service_not_connected))
            phoneSwitch.isChecked = false
            return
        }

        val providerHandle = try {
            buildProvider(profile, phoneMode)
        } catch (error: RuntimeException) {
            appendLine(LineKind.ERROR, error.message ?: "提供商配置无效")
            return
        }

        if (profile.kind == ProviderKind.OFFLINE) {
            appendLine(LineKind.INFO, getString(R.string.msg_no_credential_scripted))
        }
        appendLine(LineKind.USER, userText)
        inputField.setText("")
        setBusy(true)
        val turnId = ++turnSequence
        val turnStore = TurnSessionStore(SESSION_STORE, currentSessionId)
        val turn = ActiveTurn(
            id = turnId,
            sessionStore = turnStore,
            cancelProvider = providerHandle.cancel
        )
        activeTurn = turn
        val harness = buildHarness(
            phoneMode = phoneMode,
            provider = providerHandle.provider,
            sessionStore = turnStore,
            turnId = turnId
        )
        val request = AgentHarnessRequest(currentSessionId, userText)
        turn.future = executor.submit {
            val outcome = try {
                val result = harness.run(request)
                TurnOutcome.Success(result.output)
            } catch (_: HttpRequestCancelledException) {
                TurnOutcome.Stopped
            } catch (_: CancellationException) {
                TurnOutcome.Stopped
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                TurnOutcome.Stopped
            } catch (error: CodexAuthException) {
                TurnOutcome.Failure(error.message ?: "Codex 登录失效")
            } catch (error: HttpTransportException) {
                TurnOutcome.Failure(error.message ?: "HTTP ${error.statusCode}")
            } catch (_: AgentHarnessLimitException) {
                val stepLimit = if (phoneMode) PHONE_MAX_PROVIDER_STEPS else CHAT_MAX_PROVIDER_STEPS
                TurnOutcome.Failure(
                    "本轮达到 $stepLimit 步安全上限，已停止；" +
                        "模型可能在重复调用工具，或没有及时调用完成工具。"
                )
            } catch (error: IllegalStateException) {
                TurnOutcome.Failure(error.message ?: error.javaClass.simpleName)
            } catch (error: IOException) {
                TurnOutcome.Failure("网络错误：${error.message}")
            } catch (error: RuntimeException) {
                TurnOutcome.Failure("${error.javaClass.simpleName}: ${error.message}")
            }
            postToUi { completeTurn(turnId, outcome) }
        }
    }

    private fun completeTurn(turnId: Long, outcome: TurnOutcome) {
        val turn = activeTurn?.takeIf { candidate -> candidate.id == turnId } ?: return
        activeTurn = null
        turn.future = null
        when (outcome) {
            is TurnOutcome.Success -> {
                if (turn.sessionStore.commit()) {
                    appendLine(LineKind.ASSISTANT, outcome.output)
                }
            }
            is TurnOutcome.Failure -> {
                turn.sessionStore.discard()
                appendLine(LineKind.ERROR, outcome.message)
            }
            TurnOutcome.Stopped -> turn.sessionStore.discard()
        }
        setBusy(false)
    }

    private fun stopActiveTurn() {
        val turn = activeTurn ?: return
        activeTurn = null
        turn.sessionStore.discard()
        runCatching(turn.cancelProvider)
        turn.future?.cancel(true)
        dialogGate.cancelPending()

        val stoppedExecutor = executor
        stoppedExecutor.shutdownNow()
        executor = Executors.newSingleThreadExecutor()

        setBusy(false)
        appendLine(LineKind.INFO, getString(R.string.msg_turn_stopped))
    }

    private fun buildProvider(profile: ProviderProfile, phoneMode: Boolean): TurnProvider {
        val historyBudget = if (phoneMode) PHONE_HISTORY_BUDGET else null
        return when (profile.kind) {
            ProviderKind.OFFLINE -> TurnProvider(ScriptedChatProvider())
            ProviderKind.CODEX -> {
                val config = CodexResponsesConfig(
                    model = profile.model,
                    baseUrl = profile.baseUrl,
                    historyCharBudget = historyBudget,
                    originator = "openclaw",
                    clientVersion = "android-agent-harness"
                )
                val transport = UrlConnectionHttpTransport(config.requestTimeout)
                TurnProvider(
                    provider = CodexResponsesProvider(
                        config = config,
                        credentials = CodexCredentialProvider { forceRefresh ->
                            codexAuth.requireProfile(forceRefresh).let { auth ->
                                CodexCredential(auth.accessToken, auth.accountId)
                            }
                        },
                        transport = transport
                    ),
                    cancel = transport::cancel
                )
            }
            ProviderKind.KIMI_PLAN -> openAiTurnProvider(
                config = OpenAiCompatibleConfig(
                    baseUrl = profile.baseUrl,
                    model = profile.model,
                    keyValue = requireNotNull(profile.secret),
                    parallelToolCalls = false,
                    historyCharBudget = historyBudget,
                    extraHeaders = mapOf(
                        "User-Agent" to "AgentHarness/0.3 coding-agent"
                    ),
                    extraBodyFields = if (profile.model == "k3") {
                        mapOf("reasoning_effort" to "high")
                    } else {
                        emptyMap()
                    }
                )
            )
            ProviderKind.ARK_PLAN -> openAiTurnProvider(
                config = OpenAiCompatibleConfig(
                    baseUrl = profile.baseUrl,
                    model = profile.model,
                    keyValue = requireNotNull(profile.secret),
                    parallelToolCalls = false,
                    historyCharBudget = historyBudget
                )
            )
            ProviderKind.CUSTOM -> openAiTurnProvider(
                config = OpenAiCompatibleConfig(
                    baseUrl = profile.baseUrl,
                    model = profile.model,
                    keyValue = requireNotNull(profile.secret),
                    parallelToolCalls = false,
                    historyCharBudget = historyBudget
                )
            )
        }
    }

    private fun openAiTurnProvider(config: OpenAiCompatibleConfig): TurnProvider {
        val transport = UrlConnectionHttpTransport(config.requestTimeout)
        return TurnProvider(
            provider = OpenAiCompatibleProvider(config, transport),
            cancel = transport::cancel
        )
    }

    private fun buildHarness(
        phoneMode: Boolean,
        provider: AgentProvider,
        sessionStore: AgentSessionStore,
        turnId: Long
    ): AgentHarnessRunner {
        val onToolLine: (String) -> Unit = { line ->
            postTurnUi(turnId) { appendLine(LineKind.TOOL, line) }
        }
        val tools: List<AgentTool>
        val profile: AgentToolProfile
        val config: AgentHarnessConfig
        val guidance: String
        if (phoneMode) {
            tools = listOf(
                DeviceObserveTool(deviceSurface),
                DeviceActTool(deviceSurface, SampleRiskPolicy.policy(), approvalGate),
                DeviceFinishTool(deviceSurface)
            )
            profile = DeviceLoopProfile.profile()
            config = AgentHarnessConfig(
                maxProviderSteps = PHONE_MAX_PROVIDER_STEPS,
                maxToolCallsPerStep = 1
            )
            guidance = "You operate this Android device through the device tools. " +
                "Call device_observe first, perform exactly one device_act per step, and " +
                "observe again after every action. Refer to controls by the shown id and pass " +
                "expected_label. The home action is unavailable. launch_app must include the " +
                "app display name or package in the app argument. Finish with device_finish " +
                "and evidence visible on screen. " +
                "If a high-risk action is denied or times out, do not retry it."
        } else {
            tools = listOf(UppercaseTool(), CurrentTimeTool(), WordCountTool())
            profile = AgentToolProfile.only(
                id = "sample-chat",
                toolNames = setOf("uppercase", "current_time", "word_count")
            )
            config = AgentHarnessConfig(
                maxProviderSteps = CHAT_MAX_PROVIDER_STEPS,
                maxToolCallsPerStep = 4
            )
            guidance = "You are a helpful assistant inside the Agent Harness sample app. " +
                "Use uppercase, current_time, or word_count when they fit; otherwise answer directly."
        }
        return AgentHarnessRunner(
            provider = provider,
            contextProviders = listOf(
                StaticAgentContextProvider(
                    listOf(
                        AgentContextItem(
                            id = "sample-guidance",
                            source = "sample-app",
                            content = guidance,
                            trust = AgentContextTrust.APPLICATION,
                            priority = 100
                        )
                    )
                )
            ),
            tools = tools.map { tool -> TranscriptTool(tool, onToolLine) },
            sessionStore = sessionStore,
            config = config,
            toolProfile = profile
        )
    }

    // ------------------------------------------------------------- transcript

    private fun appendLine(kind: LineKind, message: String) {
        emptyState.visibility = View.GONE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (kind.alignEnd) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        if (kind.alignEnd) {
            row.addView(Space(this), weightedParams())
        }
        val bubble = TextView(this).apply {
            text = "${kind.prefix}\n$message"
            setTextIsSelectable(true)
            setTextColor(kind.textColor(this@MainActivity))
            textSize = if (kind.monospace) 12f else 14f
            if (kind.monospace) typeface = Typeface.MONOSPACE
            maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = roundedDrawable(
                kind.backgroundColor(this@MainActivity),
                dp(if (kind.compact) 12 else 18).toFloat(),
                kind.strokeColor(this@MainActivity)
            )
        }
        row.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        if (!kind.alignEnd) {
            row.addView(Space(this), weightedParams())
        }
        transcriptContainer.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startNewChat(showToast: Boolean) {
        sessionOrdinal += 1
        currentSessionId = "chat-$sessionOrdinal"
        transcriptContainer.removeAllViews()
        updateProviderUi()
        updateEmptyState()
        if (showToast) {
            Toast.makeText(this, getString(R.string.msg_new_chat), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onApprovalWaitingChanged(waiting: Boolean) {
        postToUi {
            if (!isBusy()) {
                updateProviderUi()
                return@postToUi
            }
            statusView.text = getString(
                if (waiting) R.string.status_waiting_approval else R.string.status_running
            )
            statusDot.setTextColor(getColor(R.color.warning))
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = true
        inputField.isEnabled = !busy
        newChatButton.isEnabled = !busy
        settingsButton.isEnabled = !busy
        providerSelector.isEnabled = !busy
        phoneSwitch.isEnabled = !busy
        sendButton.alpha = 1f
        sendButton.text = getString(if (busy) R.string.btn_stop else R.string.btn_send)
        sendButton.background = getDrawable(
            if (busy) R.drawable.bg_stop_button else R.drawable.bg_primary_button
        )
        contentRoot.tag = busy
        if (busy) {
            statusView.text = getString(R.string.status_running)
            statusDot.setTextColor(getColor(R.color.warning))
        } else {
            updateProviderUi()
        }
    }

    private fun isBusy(): Boolean = contentRoot.tag == true

    private fun postToUi(action: () -> Unit) {
        mainHandler.post {
            if (!isDestroyed && !isFinishing) action()
        }
    }

    private fun postTurnUi(turnId: Long, action: () -> Unit) {
        postToUi {
            if (activeTurn?.id == turnId) action()
        }
    }

    // ---------------------------------------------------------------- styling

    private fun sectionLabel(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.textPrimary))
            setPadding(dp(2), dp(4), dp(2), dp(6))
        }
    }

    private fun bodyText(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(getColor(R.color.textSecondary))
            setLineSpacing(0f, 1.16f)
        }
    }

    private fun infoCard(message: String): TextView {
        return bodyText(message).apply {
            background = roundedDrawable(
                getColor(R.color.surfaceSoft),
                dp(16).toFloat(),
                Color.TRANSPARENT
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    private fun styleInput(input: EditText) {
        input.background = getDrawable(R.drawable.bg_surface_card)
        input.setTextColor(getColor(R.color.textPrimary))
        input.setHintTextColor(getColor(R.color.textSecondary))
        input.textSize = 14f
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.minHeight = dp(50)
        input.isSingleLine = true
    }

    private fun actionButton(text: String, primary: Boolean = false): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            minHeight = dp(44)
            minWidth = 0
            elevation = 0f
            translationZ = 0f
            stateListAnimator = null
            background = getDrawable(
                if (primary) R.drawable.bg_primary_button else R.drawable.bg_secondary_button
            )
            setTextColor(getColor(if (primary) R.color.white else R.color.textPrimary))
        }
    }

    private fun roundedDrawable(fill: Int, radius: Float, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun weightedParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun matchWrapParams(
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }
    }

    private fun formatTime(epochMs: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ProviderDialogFields(
        val modelValue: (() -> String)? = null,
        val baseUrl: EditText? = null,
        val secret: EditText? = null
    )

    private data class ProviderReadiness(
        val ready: Boolean,
        val detail: String,
        val color: Int
    )

    private data class TurnProvider(
        val provider: AgentProvider,
        val cancel: () -> Unit = {}
    )

    private data class ActiveTurn(
        val id: Long,
        val sessionStore: TurnSessionStore,
        val cancelProvider: () -> Unit,
        var future: Future<*>? = null
    )

    private sealed interface TurnOutcome {
        data class Success(val output: String) : TurnOutcome
        data class Failure(val message: String) : TurnOutcome
        data object Stopped : TurnOutcome
    }

    private companion object {
        const val CHAT_MAX_PROVIDER_STEPS = 8
        const val PHONE_MAX_PROVIDER_STEPS = 80
        const val PHONE_HISTORY_BUDGET = 24_000

        /** One store and session id per app process, so history survives re-creation. */
        val SESSION_STORE = InMemoryAgentSessionStore()
        var sessionOrdinal = 1
        var currentSessionId = "chat-1"
    }
}

private enum class LineKind(
    val prefix: String,
    val monospace: Boolean,
    val alignEnd: Boolean,
    val compact: Boolean
) {
    USER("你", false, true, false),
    ASSISTANT("Agent", false, false, false),
    TOOL("工具", true, false, true),
    INFO("提示", false, false, true),
    ERROR("错误", false, false, true);

    fun textColor(context: Context): Int = context.getColor(
        when (this) {
            USER -> R.color.textPrimary
            ASSISTANT -> R.color.textPrimary
            TOOL -> R.color.toolText
            INFO -> R.color.textSecondary
            ERROR -> R.color.danger
        }
    )

    fun backgroundColor(context: Context): Int = context.getColor(
        when (this) {
            USER -> R.color.primarySoft
            ASSISTANT -> R.color.surface
            TOOL -> R.color.toolSurface
            INFO -> R.color.surfaceSoft
            ERROR -> R.color.surface
        }
    )

    fun strokeColor(context: Context): Int = context.getColor(
        when (this) {
            USER -> R.color.primarySoft
            ASSISTANT -> R.color.cardStroke
            TOOL -> R.color.toolSurface
            INFO -> R.color.surfaceSoft
            ERROR -> R.color.danger
        }
    )
}

/** Decorates a tool so every execution is echoed into the transcript. */
private class TranscriptTool(
    private val inner: AgentTool,
    private val onToolLine: (String) -> Unit
) : AgentTool {

    override val spec: AgentToolSpec = inner.spec

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val arguments = invocation.arguments.entries
            .joinToString(", ") { entry -> "${entry.key}=${abbreviate(entry.value)}" }
        val result = try {
            inner.execute(invocation)
        } catch (error: RuntimeException) {
            onToolLine("${spec.name}($arguments) failed: ${abbreviate(error.message.orEmpty())}")
            throw error
        }
        val marker = if (result.isError) "ERROR " else ""
        onToolLine("${spec.name}($arguments) -> $marker${abbreviate(result.content)}")
        return result
    }

    private fun abbreviate(value: String): String {
        val singleLine = value.replace(WHITESPACE, " ").trim()
        return if (singleLine.length <= MAX_LINE_CHARS) {
            singleLine
        } else {
            singleLine.take(MAX_LINE_CHARS - 3) + "..."
        }
    }

    private companion object {
        const val MAX_LINE_CHARS = 200
        val WHITESPACE = Regex("\\s+")
    }
}
