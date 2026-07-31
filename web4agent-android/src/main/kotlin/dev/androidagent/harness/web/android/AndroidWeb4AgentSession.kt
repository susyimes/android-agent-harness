// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt

internal data class Web4AgentPageState(
    val url: String,
    val title: String,
    val loading: Boolean,
    val error: String?
)

internal class AndroidWeb4AgentSession(
    private val applicationContext: Context,
    override val sessionId: String,
    private val configuration: Web4AgentConfiguration,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val onClosed: (String) -> Unit = {}
) : Web4AgentSession {
    private val main = Handler(Looper.getMainLooper())
    private val loadMonitor = Object()
    private val consoleEntries = ArrayDeque<Web4AgentConsoleEntry>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var contextWrapper: MutableContextWrapper? = null

    @Volatile
    private var loading = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var hostActivity = WeakReference<Activity>(null)

    @Volatile
    private var stateObserver: ((Web4AgentPageState) -> Unit)? = null

    override fun open(request: Web4AgentOpenRequest): Web4AgentActionResult {
        val view = requireWebView()
        val resolved = resolve(request)
        synchronized(loadMonitor) {
            loading = true
            lastError = null
        }
        onMain {
            when (resolved) {
                is ResolvedOpen.Html -> view.loadDataWithBaseURL(
                    INLINE_BASE_URL,
                    resolved.html,
                    "text/html",
                    StandardCharsets.UTF_8.name(),
                    null
                )
                is ResolvedOpen.Url -> view.loadUrl(resolved.url)
            }
        }
        awaitLoad(request.waitTimeoutMillis)
        val state = pageState()
        val ok = state.error == null
        val summary = when {
            state.error != null -> "Web4Agent could not open the page: ${state.error}"
            state.loading -> "Web4Agent opened the page; it is still loading."
            else -> "Web4Agent opened ${state.url.ifBlank { "the inline page" }}."
        }
        return Web4AgentActionResult(
            ok = ok,
            summary = summary,
            dataJson = stateJson(ok, state, summary)
        )
    }

    override fun observe(request: Web4AgentObservationRequest): Web4AgentObservation {
        val json = evaluateJavascript(
            Web4AgentScripts.observe(request, configuration.maxResultChars),
            configuration.defaultTimeoutMillis
        )
            .take(configuration.maxResultChars)
        val state = pageState()
        return Web4AgentObservation(
            url = state.url,
            title = state.title,
            loading = state.loading,
            dataJson = json
        )
    }

    override fun read(request: Web4AgentReadRequest): Web4AgentJsonResult {
        return jsonResult(
            evaluateJavascript(
                Web4AgentScripts.read(request, configuration.maxResultChars),
                configuration.defaultTimeoutMillis
            ),
            "Read page ${request.mode}."
        )
    }

    override fun inspect(request: Web4AgentInspectRequest): Web4AgentJsonResult {
        return jsonResult(
            evaluateJavascript(
                Web4AgentScripts.inspect(request, configuration.maxResultChars),
                configuration.defaultTimeoutMillis
            ),
            "Inspected matching DOM content."
        )
    }

    override fun evaluate(request: Web4AgentEvalRequest): Web4AgentJsonResult {
        require(request.script.length <= configuration.maxScriptChars) {
            "JavaScript exceeds ${configuration.maxScriptChars} characters."
        }
        appendConsole(
            Web4AgentConsoleEntry(
                level = "agent",
                message = request.purpose.take(1_000),
                sourceId = "web4agent_eval",
                lineNumber = 0,
                createdAtEpochMillis = nowEpochMillis()
            )
        )
        return jsonResult(
            evaluateJavascript(
                Web4AgentScripts.evaluate(request, configuration.maxResultChars),
                request.timeoutMillis
            ),
            "Executed Web4Agent JavaScript for ${request.purpose}."
        )
    }

    override fun act(action: Web4AgentAction): Web4AgentActionResult {
        return when (action.type) {
            "back" -> nativeNavigation(action.type) { view ->
                if (view.canGoBack()) view.goBack() else error("No back history.")
            }
            "forward" -> nativeNavigation(action.type) { view ->
                if (view.canGoForward()) view.goForward() else error("No forward history.")
            }
            "reload" -> nativeNavigation(action.type) { view -> view.reload() }
            "wait_for_selector", "wait_for_text" -> waitFor(action)
            else -> {
                val json = evaluateJavascript(
                    Web4AgentScripts.action(action),
                    action.timeoutMillis
                ).take(configuration.maxResultChars)
                val ok = isSuccessful(json)
                Web4AgentActionResult(
                    ok = ok,
                    summary = if (ok) {
                        "Web4Agent completed ${action.type}."
                    } else {
                        "Web4Agent ${action.type} failed."
                    },
                    dataJson = json
                )
            }
        }
    }

    override fun console(limit: Int): List<Web4AgentConsoleEntry> {
        require(limit in 1..200)
        return synchronized(consoleEntries) {
            consoleEntries.toList().takeLast(limit)
        }
    }

    override fun capture(): Web4AgentCapture {
        val view = requireWebView()
        return onMain {
            val sourceWidth = view.width.takeIf { width -> width > 0 } ?: DEFAULT_CAPTURE_WIDTH
            val sourceHeight = view.height.takeIf { height -> height > 0 } ?: DEFAULT_CAPTURE_HEIGHT
            val scale = minOf(
                1.0,
                MAX_CAPTURE_WIDTH.toDouble() / sourceWidth,
                MAX_CAPTURE_HEIGHT.toDouble() / sourceHeight
            )
            val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            if (view.width <= 0 || view.height <= 0) {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(sourceWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sourceHeight, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, sourceWidth, sourceHeight)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (scale != 1.0) canvas.scale(scale.toFloat(), scale.toFloat())
            view.draw(canvas)
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "WebView screenshot encoding failed."
            }
            bitmap.recycle()
            val created = nowEpochMillis()
            Web4AgentCapture(
                id = "web-capture-${UUID.randomUUID()}",
                bytes = output.toByteArray(),
                width = width,
                height = height,
                createdAtEpochMillis = created,
                expiresAtEpochMillis = created + CAPTURE_TTL_MILLIS
            )
        }
    }

    override fun finish(keepSession: Boolean): Web4AgentActionResult {
        if (keepSession) {
            val state = pageState()
            return Web4AgentActionResult(
                ok = true,
                summary = "Web4Agent session remains visible.",
                dataJson = stateJson(true, state, "session kept")
            )
        }
        val activity = hostActivity.get()
        onMain {
            webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.loadUrl("about:blank")
                view.removeAllViews()
                view.destroy()
            }
            webView = null
            contextWrapper = null
            stateObserver = null
            hostActivity = WeakReference(null)
            activity?.finish()
        }
        onClosed(sessionId)
        return Web4AgentActionResult(
            ok = true,
            summary = "Web4Agent session closed.",
            dataJson = """{"ok":true,"closed":true}"""
        )
    }

    internal fun attach(
        activity: Activity,
        container: ViewGroup,
        observer: (Web4AgentPageState) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val view = requireWebView()
        contextWrapper?.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        hostActivity = WeakReference(activity)
        stateObserver = observer
        observer(pageStateOnMain())
    }

    internal fun detach(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            onMain { detach(activity) }
            return
        }
        if (hostActivity.get() !== activity) return
        webView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        contextWrapper?.baseContext = applicationContext
        hostActivity = WeakReference(null)
        stateObserver = null
    }

    internal fun currentState(): Web4AgentPageState = pageState()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        val wrapper = MutableContextWrapper(applicationContext)
        contextWrapper = wrapper
        return WebView(wrapper).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = configuration.allowFileAccess
            settings.allowContentAccess = configuration.allowContentAccess
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = if (configuration.allowMixedContent) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            settings.safeBrowsingEnabled = true
            settings.userAgentString = buildString {
                append(settings.userAgentString.orEmpty())
                if (isNotEmpty()) append(' ')
                append(configuration.userAgentSuffix)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                this,
                configuration.acceptThirdPartyCookies
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val allowed = isAllowedUrl(request.url.toString())
                    if (!allowed) {
                        appendConsole(
                            Web4AgentConsoleEntry(
                                level = "warning",
                                message = "Blocked navigation to ${request.url}",
                                sourceId = "navigation-policy",
                                lineNumber = 0,
                                createdAtEpochMillis = nowEpochMillis()
                            )
                        )
                    }
                    return !allowed
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    markLoading(true, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    markLoading(false, null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        markLoading(false, error.description?.toString() ?: "WebView load failed.")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    appendConsole(
                        Web4AgentConsoleEntry(
                            level = consoleMessage.messageLevel().name.lowercase(),
                            message = consoleMessage.message().orEmpty().take(4_000),
                            sourceId = consoleMessage.sourceId().orEmpty().take(2_048),
                            lineNumber = consoleMessage.lineNumber().coerceAtLeast(0),
                            createdAtEpochMillis = nowEpochMillis()
                        )
                    )
                    return true
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress >= 100 && loading) {
                        markLoading(false, lastError)
                    } else {
                        notifyState()
                    }
                }
            }
        }
    }

    private fun requireWebView(): WebView {
        return webView ?: onMain {
            webView ?: createWebView().also { created -> webView = created }
        }
    }

    private fun resolve(request: Web4AgentOpenRequest): ResolvedOpen {
        request.html?.takeIf(String::isNotBlank)?.let { html ->
            require(html.length <= configuration.maxInlineHtmlChars) {
                "Inline HTML exceeds ${configuration.maxInlineHtmlChars} characters."
            }
            return ResolvedOpen.Html(html)
        }
        request.query?.takeIf(String::isNotBlank)?.let { query ->
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val searchUrl = configuration.defaultSearchUrl + encoded
            require(searchUrl.length <= MAX_URL_CHARS) {
                "Encoded search URL is too long."
            }
            return ResolvedOpen.Url(searchUrl)
        }
        val url = normalizeAddress(requireNotNull(request.url))
        require(isAllowedUrl(url)) { "URL scheme is blocked by Web4Agent policy." }
        return ResolvedOpen.Url(url)
    }

    private fun normalizeAddress(value: String): String {
        val trimmed = value.trim()
        require(trimmed.length <= MAX_URL_CHARS) { "URL is too long." }
        if (trimmed == "about:blank") return trimmed
        if ("://" !in trimmed) return "https://$trimmed"
        return trimmed
    }

    private fun isAllowedUrl(value: String): Boolean {
        if (value == "about:blank" || value.startsWith(INLINE_BASE_URL)) return true
        val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
        return scheme == "https" || (scheme == "http" && configuration.allowCleartextHttp)
    }

    private fun evaluateJavascript(script: String, timeoutMillis: Long): String {
        val view = requireWebView()
        val result = CompletableFuture<String>()
        main.post {
            if (webView !== view) {
                result.completeExceptionally(IllegalStateException("Web4Agent session is closed."))
            } else {
                view.evaluateJavascript(script) { encoded ->
                    result.complete(Web4AgentJson.decodeJavascriptString(encoded))
                }
            }
        }
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("Web4Agent JavaScript timed out after ${timeoutMillis}ms.")
        }.take(configuration.maxResultChars)
    }

    private fun nativeNavigation(
        action: String,
        operation: (WebView) -> Unit
    ): Web4AgentActionResult {
        return runCatching {
            onMain { operation(requireWebView()) }
            Web4AgentActionResult(
                ok = true,
                summary = "Web4Agent completed $action.",
                dataJson = """{"ok":true,"action":${Web4AgentJson.quote(action)}}"""
            )
        }.getOrElse { error ->
            Web4AgentActionResult(
                ok = false,
                summary = error.message ?: "Web4Agent $action failed.",
                dataJson = """{"ok":false,"action":${Web4AgentJson.quote(action)},"error":${
                    Web4AgentJson.quote(error.message ?: "failed")
                }}"""
            )
        }
    }

    private fun waitFor(action: Web4AgentAction): Web4AgentActionResult {
        require(
            (action.type != "wait_for_selector" || !action.selector.isNullOrBlank()) &&
                (action.type != "wait_for_text" || !action.text.isNullOrBlank())
        ) { "${action.type} requires its matching selector or text argument." }
        val deadline = SystemClock.elapsedRealtime() + action.timeoutMillis
        do {
            val matched = evaluateJavascript(
                Web4AgentScripts.waitPredicate(action),
                minOf(action.timeoutMillis, 2_000L)
            ) == "true"
            if (matched) {
                return Web4AgentActionResult(
                    ok = true,
                    summary = "Web4Agent ${action.type} condition matched.",
                    dataJson = """{"ok":true,"action":${Web4AgentJson.quote(action.type)}}"""
                )
            }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return Web4AgentActionResult(
            ok = false,
            summary = "Web4Agent ${action.type} timed out.",
            dataJson = """{"ok":false,"action":${Web4AgentJson.quote(action.type)},"error":"timeout"}"""
        )
    }

    private fun jsonResult(json: String, successSummary: String): Web4AgentJsonResult {
        val bounded = json.take(configuration.maxResultChars)
        val ok = isSuccessful(bounded)
        return Web4AgentJsonResult(
            ok = ok,
            dataJson = bounded,
            summary = if (ok) successSummary else "Web4Agent returned an error."
        )
    }

    private fun isSuccessful(json: String): Boolean {
        return Regex("""["']?ok["']?\s*:\s*true""").containsMatchIn(json)
    }

    private fun awaitLoad(timeoutMillis: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(loadMonitor) {
            while (loading) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return
                loadMonitor.wait(minOf(remaining, 250L))
            }
        }
    }

    private fun markLoading(value: Boolean, error: String?) {
        synchronized(loadMonitor) {
            loading = value
            if (value || error != null) lastError = error
            loadMonitor.notifyAll()
        }
        notifyState()
    }

    private fun appendConsole(entry: Web4AgentConsoleEntry) {
        synchronized(consoleEntries) {
            consoleEntries.addLast(entry)
            while (consoleEntries.size > MAX_CONSOLE_ENTRIES) {
                consoleEntries.removeFirst()
            }
        }
    }

    private fun notifyState() {
        val observer = stateObserver ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            observer(pageStateOnMain())
        } else {
            main.post {
                stateObserver?.invoke(pageStateOnMain())
            }
        }
    }

    private fun pageState(): Web4AgentPageState = onMain(::pageStateOnMain)

    private fun pageStateOnMain(): Web4AgentPageState {
        val view = webView
        return Web4AgentPageState(
            url = view?.url.orEmpty(),
            title = view?.title.orEmpty(),
            loading = loading,
            error = lastError
        )
    }

    private fun stateJson(
        ok: Boolean,
        state: Web4AgentPageState,
        summary: String
    ): String = buildString {
        append('{')
        append("\"ok\":").append(ok).append(',')
        append("\"url\":").append(Web4AgentJson.quote(state.url)).append(',')
        append("\"title\":").append(Web4AgentJson.quote(state.title)).append(',')
        append("\"loading\":").append(state.loading).append(',')
        append("\"summary\":").append(Web4AgentJson.quote(summary))
        state.error?.let { error ->
            append(",\"error\":").append(Web4AgentJson.quote(error))
        }
        append('}')
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = CompletableFuture<T>()
        main.post {
            runCatching(block)
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return try {
            result.get(MAIN_THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("Web4Agent main-thread operation timed out.")
        }
    }

    private sealed interface ResolvedOpen {
        data class Url(val url: String) : ResolvedOpen
        data class Html(val html: String) : ResolvedOpen
    }

    private companion object {
        const val INLINE_BASE_URL = "https://web4agent.invalid/"
        const val MAX_URL_CHARS = 4_096
        const val MAX_CONSOLE_ENTRIES = 200
        const val WAIT_POLL_MILLIS = 100L
        const val MAIN_THREAD_TIMEOUT_MILLIS = 15_000L
        const val CAPTURE_TTL_MILLIS = 5 * 60_000L
        const val DEFAULT_CAPTURE_WIDTH = 1_080
        const val DEFAULT_CAPTURE_HEIGHT = 1_920
        const val MAX_CAPTURE_WIDTH = 1_440
        const val MAX_CAPTURE_HEIGHT = 2_560
    }
}
