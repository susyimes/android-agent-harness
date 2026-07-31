// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Visible browser surface shared by the Agent and the user. */
class Web4AgentBrowserActivity : Activity() {
    private lateinit var sessionId: String
    private lateinit var controller: AndroidWeb4AgentSession
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_MANUAL_SESSION
        controller = Web4AgentRuntime.getInstance(this).controller(sessionId)
        setContentView(buildContent())
        controller.attach(this, container, ::renderState)
    }

    override fun onDestroy() {
        controller.detach(this)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        navigation.addView(button(getString(R.string.web4agent_back)) {
            runAction(Web4AgentAction(type = "back"))
        })
        navigation.addView(button(getString(R.string.web4agent_forward)) {
            runAction(Web4AgentAction(type = "forward"))
        })
        navigation.addView(button(getString(R.string.web4agent_reload)) {
            runAction(Web4AgentAction(type = "reload"))
        })
        navigation.addView(
            TextView(this),
            LinearLayout.LayoutParams(0, 0, 1f)
        )
        navigation.addView(button(getString(R.string.web4agent_close)) {
            worker.execute { controller.finish(keepSession = false) }
        })
        root.addView(
            navigation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        address = EditText(this).apply {
            id = R.id.web4agent_url
            hint = getString(R.string.web4agent_address_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.rgb(32, 37, 43))
            setHintTextColor(Color.rgb(110, 118, 129))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openAddress()
                    true
                } else {
                    false
                }
            }
        }
        addressBar.addView(
            address,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addressBar.addView(button(getString(R.string.web4agent_go), R.id.web4agent_go) {
            openAddress()
        })
        root.addView(
            addressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        )
        status = TextView(this).apply {
            id = R.id.web4agent_status
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(Color.rgb(75, 85, 99))
            textSize = 12f
            text = "Session: $sessionId"
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container = FrameLayout(this).apply {
            id = R.id.web4agent_webview_container
        }
        root.addView(
            container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return root
    }

    private fun button(
        label: String,
        viewId: Int? = null,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        viewId?.let { id = it }
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    private fun openAddress() {
        val value = address.text.toString().trim()
        if (value.isBlank()) return
        status.text = "Opening…"
        worker.execute {
            runCatching {
                controller.open(Web4AgentOpenRequest(url = value))
            }.onFailure { error ->
                runOnUiThread {
                    status.text = error.message ?: "Web4Agent open failed."
                }
            }
        }
    }

    private fun runAction(action: Web4AgentAction) {
        worker.execute {
            runCatching { controller.act(action) }
                .onFailure { error ->
                    runOnUiThread {
                        status.text = error.message ?: "Web4Agent action failed."
                    }
                }
        }
    }

    private fun renderState(state: Web4AgentPageState) {
        progress.visibility = if (state.loading) ProgressBar.VISIBLE else ProgressBar.GONE
        if (state.url.isNotBlank() && !address.hasFocus()) {
            address.setText(state.url)
        }
        status.text = when {
            state.error != null -> state.error
            state.loading -> "Loading ${state.url}"
            state.title.isNotBlank() -> state.title
            state.url.isNotBlank() -> state.url
            else -> "Session: $sessionId"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SESSION_ID =
            "dev.androidagent.harness.web.android.extra.SESSION_ID"
        const val DEFAULT_MANUAL_SESSION = "web4agent-workbench"

        fun intent(context: Context, sessionId: String = DEFAULT_MANUAL_SESSION): Intent {
            require(sessionId.isNotBlank())
            return Intent(context, Web4AgentBrowserActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
    }
}
