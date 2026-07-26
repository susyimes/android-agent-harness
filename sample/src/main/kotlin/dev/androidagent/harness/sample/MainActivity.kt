// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Insets
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentHarnessLimitException
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentProvider
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
import dev.androidagent.harness.provider.openai.HttpTransportException
import dev.androidagent.harness.provider.openai.OpenAiCompatibleConfig
import dev.androidagent.harness.provider.openai.OpenAiCompatibleProvider
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Installable demo of the agent harness.
 *
 * The user pastes an OpenAI-compatible API credential, chats with a live
 * model through the harness (uppercase / current_time / word_count tools),
 * and can optionally switch to phone mode where the same loop drives this
 * device through the accessibility service, with a human approval dialog
 * gating every high-risk action. Without a stored credential, Send runs a
 * deterministic offline scripted provider so the APK works with zero setup.
 */
class MainActivity : Activity() {

    private lateinit var mainHandler: Handler
    private lateinit var executor: ExecutorService
    private lateinit var dialogGate: DialogApprovalGate
    private lateinit var approvalGate: PhoneApprovalGate

    private lateinit var settingsToggle: Button
    private lateinit var newChatButton: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var editBaseUrl: EditText
    private lateinit var editModel: EditText
    private lateinit var editCredential: EditText
    private lateinit var phoneSwitch: Switch
    private lateinit var transcriptScroll: ScrollView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    private val deviceSurface by lazy {
        AccessibilityDeviceSurface(
            serviceProvider = { HarnessAccessibilityService.connectedInstance() }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainHandler = Handler(Looper.getMainLooper())
        executor = Executors.newSingleThreadExecutor()
        dialogGate = DialogApprovalGate(this, mainHandler, ::onApprovalWaitingChanged)
        approvalGate = PhoneApprovalGate(
            overlay = OverlayApprovalGate(
                serviceProvider = { HarnessAccessibilityService.connectedInstance() },
                onWaitingChanged = ::onApprovalWaitingChanged
            ),
            dialog = dialogGate
        )
        setContentView(buildLayout())
        loadSettings()
        appendLine(LineKind.INFO, getString(R.string.msg_welcome))
    }

    override fun onDestroy() {
        // Resolve any approval prompt first: a worker parked on the latch would
        // otherwise hold the executor until the timeout elapses.
        dialogGate.cancelPending()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    /**
     * Keeps the chat usable under the system bars and the keyboard.
     *
     * From Android 15 (API 35) an app targeting 35+ always draws edge to edge and
     * `adjustResize` no longer moves the window, so without this the title sits
     * under the status bar, the input row sits under the navigation bar, and the
     * keyboard covers the very field being typed into. Insets are applied as
     * padding on the root: system bars always, plus the IME when it is up (the
     * two overlap, so take the larger bottom rather than their sum).
     */
    private fun applyWindowInsets(root: View) {
        val basePadding = dp(16)
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
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        applyWindowInsets(root)

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        settingsToggle = Button(this).apply {
            setOnClickListener { toggleSettings() }
        }
        newChatButton = Button(this).apply {
            text = getString(R.string.btn_new_chat)
            setOnClickListener { startNewChat() }
        }
        headerRow.addView(settingsToggle, weightedRowParams())
        headerRow.addView(newChatButton, weightedRowParams())
        root.addView(headerRow)

        settingsPanel = buildSettingsPanel()
        root.addView(settingsPanel)

        phoneSwitch = Switch(this).apply {
            text = getString(R.string.phone_mode_label)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    onPhoneModeRequested()
                }
            }
        }
        root.addView(phoneSwitch)
        root.addView(smallGrayText(getString(R.string.phone_mode_note)))

        transcriptContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        transcriptScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(transcriptContainer)
        }
        root.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        statusView = smallGrayText(getString(R.string.status_ready))
        root.addView(statusView)

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        inputField = EditText(this).apply {
            hint = getString(R.string.hint_message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        sendButton = Button(this).apply {
            text = getString(R.string.btn_send)
            setOnClickListener { onSend() }
        }
        inputRow.addView(inputField, weightedRowParams())
        inputRow.addView(sendButton)
        root.addView(inputRow)

        return root
    }

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editBaseUrl = EditText(this).apply {
            hint = getString(R.string.hint_base_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        editModel = EditText(this).apply {
            hint = getString(R.string.hint_model)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        editCredential = EditText(this).apply {
            hint = getString(R.string.hint_credential)
            // Programmatic equivalent of an XML textPassword input type.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        panel.addView(editBaseUrl)
        panel.addView(editModel)
        panel.addView(editCredential)
        panel.addView(smallGrayText(getString(R.string.credential_notice)))

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(
            Button(this).apply {
                text = getString(R.string.btn_save_settings)
                setOnClickListener { persistSettings(showConfirmation = true) }
            },
            weightedRowParams()
        )
        buttonRow.addView(
            Button(this).apply {
                text = getString(R.string.btn_clear_credential)
                setOnClickListener { clearCredential() }
            },
            weightedRowParams()
        )
        panel.addView(buttonRow)
        return panel
    }

    private fun smallGrayText(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(0xFF616161.toInt())
        }
    }

    private fun weightedRowParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------- settings

    private fun preferences(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadSettings() {
        val prefs = preferences()
        editBaseUrl.setText(prefs.getString(PREF_BASE_URL, OpenAiCompatibleConfig.DEFAULT_BASE_URL))
        editModel.setText(prefs.getString(PREF_MODEL, OpenAiCompatibleConfig.DEFAULT_MODEL))
        val storedCredential = prefs.getString(PREF_CREDENTIAL, "").orEmpty()
        editCredential.setText(storedCredential)
        applySettingsVisibility(expanded = storedCredential.isBlank())
    }

    private fun persistSettings(showConfirmation: Boolean) {
        preferences().edit()
            .putString(PREF_BASE_URL, editBaseUrl.text.toString().trim())
            .putString(PREF_MODEL, editModel.text.toString().trim())
            .putString(PREF_CREDENTIAL, editCredential.text.toString().trim())
            .apply()
        if (showConfirmation) {
            appendLine(LineKind.INFO, getString(R.string.msg_settings_saved))
        }
    }

    private fun clearCredential() {
        preferences().edit().remove(PREF_CREDENTIAL).apply()
        editCredential.setText("")
        appendLine(LineKind.INFO, getString(R.string.msg_credential_cleared))
    }

    private fun toggleSettings() {
        applySettingsVisibility(expanded = settingsPanel.visibility != View.VISIBLE)
    }

    private fun applySettingsVisibility(expanded: Boolean) {
        settingsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        settingsToggle.text = getString(
            if (expanded) R.string.btn_hide_settings else R.string.btn_show_settings
        )
    }

    // ------------------------------------------------------------ phone mode

    private fun onPhoneModeRequested() {
        if (HarnessAccessibilityService.connectedInstance() != null) {
            return
        }
        phoneSwitch.isChecked = false
        if (AccessibilityAvailability.isServiceEnabled(this)) {
            appendLine(LineKind.INFO, getString(R.string.msg_service_enabled_not_connected))
        } else {
            showAccessibilityDialog()
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_dialog_title)
            .setMessage(R.string.accessibility_dialog_message)
            .setPositiveButton(R.string.btn_open_accessibility_settings) { _, _ ->
                startActivity(AccessibilityAvailability.settingsIntent())
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ------------------------------------------------------------- transcript

    private fun appendLine(kind: LineKind, message: String) {
        val line = TextView(this).apply {
            text = kind.prefix + message
            setTextIsSelectable(true)
            setTextColor(kind.color)
            textSize = if (kind.monospace) 12f else 14f
            if (kind.monospace) {
                typeface = Typeface.MONOSPACE
            }
            setPadding(0, dp(2), 0, dp(2))
        }
        transcriptContainer.addView(line)
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun postError(message: String) {
        mainHandler.post { appendLine(LineKind.ERROR, message) }
    }

    private fun onApprovalWaitingChanged(waiting: Boolean) {
        mainHandler.post {
            statusView.text = getString(
                if (waiting) R.string.status_waiting_approval else R.string.status_running
            )
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        inputField.isEnabled = !busy
        newChatButton.isEnabled = !busy
        phoneSwitch.isEnabled = !busy
        statusView.text = getString(if (busy) R.string.status_running else R.string.status_ready)
    }

    private fun startNewChat() {
        sessionOrdinal += 1
        currentSessionId = "chat-$sessionOrdinal"
        transcriptContainer.removeAllViews()
        appendLine(LineKind.INFO, getString(R.string.msg_new_chat))
    }

    // ------------------------------------------------------------------ turn

    private fun onSend() {
        val userText = inputField.text.toString().trim()
        if (userText.isEmpty()) {
            return
        }
        persistSettings(showConfirmation = false)
        val phoneMode = phoneSwitch.isChecked
        val credentialValue = editCredential.text.toString().trim()
        val baseUrl = editBaseUrl.text.toString().trim().trimEnd('/')
            .ifEmpty { OpenAiCompatibleConfig.DEFAULT_BASE_URL }
        val model = editModel.text.toString().trim()
            .ifEmpty { OpenAiCompatibleConfig.DEFAULT_MODEL }

        if (phoneMode && credentialValue.isEmpty()) {
            appendLine(LineKind.ERROR, getString(R.string.msg_phone_needs_credential))
            return
        }
        if (phoneMode && HarnessAccessibilityService.connectedInstance() == null) {
            appendLine(LineKind.ERROR, getString(R.string.msg_service_not_connected))
            phoneSwitch.isChecked = false
            return
        }

        val provider: AgentProvider = if (credentialValue.isEmpty()) {
            appendLine(LineKind.INFO, getString(R.string.msg_no_credential_scripted))
            ScriptedChatProvider()
        } else {
            OpenAiCompatibleProvider(
                OpenAiCompatibleConfig(baseUrl = baseUrl, model = model, keyValue = credentialValue)
            )
        }

        appendLine(LineKind.USER, userText)
        inputField.setText("")
        setBusy(true)
        val harness = buildHarness(phoneMode, provider)
        val request = AgentHarnessRequest(currentSessionId, userText)
        executor.execute {
            try {
                val result = harness.run(request)
                mainHandler.post { appendLine(LineKind.ASSISTANT, result.output) }
            } catch (error: HttpTransportException) {
                postError(error.message ?: "HTTP ${error.statusCode}")
            } catch (error: AgentHarnessLimitException) {
                postError("Turn stopped: ${error.message}")
            } catch (error: IllegalStateException) {
                postError(error.message ?: error.javaClass.simpleName)
            } catch (error: IOException) {
                postError("Network error: ${error.message}")
            } catch (error: RuntimeException) {
                postError("${error.javaClass.simpleName}: ${error.message}")
            } finally {
                mainHandler.post { setBusy(false) }
            }
        }
    }

    private fun buildHarness(phoneMode: Boolean, provider: AgentProvider): AgentHarnessRunner {
        val onToolLine: (String) -> Unit = { line ->
            mainHandler.post { appendLine(LineKind.TOOL, line) }
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
            config = AgentHarnessConfig(maxProviderSteps = 16, maxToolCallsPerStep = 1)
            guidance = "You operate this Android device through the device tools. " +
                "Call device_observe first to see the current screen, perform exactly one " +
                "device_act per step, and observe again after every action. Refer to controls " +
                "by the id shown in the observation, and pass expected_label so a screen that " +
                "changed under you is caught instead of mis-tapped. Finish with device_finish, " +
                "supplying evidence text that is visible on the screen you are finishing on. " +
                "High-risk actions are shown to the user for approval: DENIED_BY_USER means " +
                "the user refused — do not retry that action, choose another approach or " +
                "finish. APPROVAL_TIMEOUT means the user never answered; report that instead " +
                "of retrying. A failure line starts with its error type: TARGET_NOT_FOUND " +
                "lists candidate controls to pick from, STALE_TARGET means observe again."
        } else {
            tools = listOf(UppercaseTool(), CurrentTimeTool(), WordCountTool())
            profile = AgentToolProfile.only(
                id = "sample-chat",
                toolNames = setOf("uppercase", "current_time", "word_count")
            )
            config = AgentHarnessConfig(maxProviderSteps = 8, maxToolCallsPerStep = 4)
            guidance = "You are a helpful assistant inside the Agent Harness sample app. " +
                "Tools available this turn: uppercase, current_time, word_count. " +
                "Use them when they fit the request; otherwise answer directly."
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
            sessionStore = SESSION_STORE,
            config = config,
            toolProfile = profile
        )
    }

    private companion object {
        const val PREFS_NAME = "agent_harness_sample_prefs"
        const val PREF_BASE_URL = "openai_base_url"
        const val PREF_MODEL = "openai_model"
        const val PREF_CREDENTIAL = "openai_credential"

        /** One store and session id per app process, so history survives re-creation. */
        val SESSION_STORE = InMemoryAgentSessionStore()
        var sessionOrdinal = 1
        var currentSessionId = "chat-1"
    }
}

/** Visual style of one transcript line. */
private enum class LineKind(val prefix: String, val color: Int, val monospace: Boolean) {
    USER("You: ", 0xFF1565C0.toInt(), false),
    ASSISTANT("Agent: ", 0xFF2E7D32.toInt(), false),
    TOOL("TOOL ", 0xFF6A1B9A.toInt(), true),
    INFO("* ", 0xFF616161.toInt(), false),
    ERROR("! ", 0xFFC62828.toInt(), false)
}

/** Decorates a tool so every execution is echoed into the chat transcript. */
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
