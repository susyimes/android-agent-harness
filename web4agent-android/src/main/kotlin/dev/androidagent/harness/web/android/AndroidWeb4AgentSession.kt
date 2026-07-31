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
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt
import org.json.JSONObject

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
) : Web4AgentExactEffectSession {
    private val main = Handler(Looper.getMainLooper())
    private val loadMonitor = Object()
    private val consoleEntries = ArrayDeque<Web4AgentConsoleEntry>()
    private val exactEffectLock = Object()
    private val observations = LinkedHashMap<String, ExactObservation>()
    private val preparedEffects = LinkedHashMap<String, Web4AgentPreparedEffect>()

    @Volatile
    private var closed = false

    @Volatile
    private var pageEpoch = 1L

    @Volatile
    private var activeDocumentToken = UUID.randomUUID().toString()

    @Volatile
    private var installedObserverToken: String? = null

    @Volatile
    private var activeEffectLeaseId: String? = null

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
            beginDocumentTransition()
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
        ensureEpochObserver()
        val json = bindObservation(
            evaluateJavascript(
            Web4AgentScripts.observe(request, configuration.maxResultChars),
            configuration.defaultTimeoutMillis
            )
        )
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
        ensureEpochObserver()
        return jsonResult(
            bindObservation(
                evaluateJavascript(
                    Web4AgentScripts.inspect(request, configuration.maxResultChars),
                    configuration.defaultTimeoutMillis
                )
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

    override fun prepareExactEffect(
        kind: Web4AgentEffectKind,
        binding: Web4AgentExpectedBinding,
        requireTarget: Boolean
    ): Web4AgentEffectPreparation = synchronized(exactEffectLock) {
        if (closed) {
            return@synchronized Web4AgentEffectPreparation.Rejected(
                Web4AgentExactEffectErrors.SESSION_CLOSED,
                "Web4Agent session is closed."
            )
        }
        if (binding.pageEpoch != pageEpoch) {
            return@synchronized stalePreparation("The Web4Agent page epoch changed.")
        }
        val observation = observations[binding.observationId]
            ?: return@synchronized stalePreparation(
                "The Web4Agent observation is missing, expired, or belongs to another page epoch."
            )
        if (
            observation.observationId != binding.observationId ||
            observation.pageEpoch != binding.pageEpoch ||
            nowEpochMillis() - observation.createdAtEpochMillis > EXACT_LEASE_TTL_MILLIS
        ) {
            return@synchronized stalePreparation("The Web4Agent observation is stale.")
        }
        val targetMaterial = binding.targetFingerprint?.let { fingerprint ->
            observation.targetMaterials[fingerprint]
                ?: return@synchronized stalePreparation(
                    "The approved Web4Agent target fingerprint is not part of this observation."
                )
        }
        if (requireTarget && targetMaterial == null) {
            return@synchronized Web4AgentEffectPreparation.Rejected(
                Web4AgentExactEffectErrors.EXACT_BINDING_REQUIRED,
                "This Web4Agent action requires a target_fingerprint from observe or inspect."
            )
        }
        val prepared = Web4AgentPreparedEffect(
            leaseId = "web-lease-${UUID.randomUUID()}",
            sessionId = sessionId,
            kind = kind,
            pageEpoch = binding.pageEpoch,
            observationId = binding.observationId,
            documentFingerprint = observation.documentFingerprint,
            targetFingerprint = binding.targetFingerprint,
            documentMaterial = observation.documentMaterial,
            targetMaterial = targetMaterial,
            createdAtEpochMillis = nowEpochMillis()
        )
        preparedEffects[prepared.leaseId] = prepared
        trimExactLedgers()
        Web4AgentEffectPreparation.Ready(prepared)
    }

    override fun evaluatePrepared(
        lease: Web4AgentPreparedEffect,
        request: Web4AgentEvalRequest
    ): Web4AgentExactJsonExecution {
        require(request.script.length <= configuration.maxScriptChars) {
            "JavaScript exceeds ${configuration.maxScriptChars} characters."
        }
        consumePreparedEffect(lease, Web4AgentEffectKind.EVALUATE)?.let { rejected ->
            return Web4AgentExactJsonExecution(
                result = exactJsonFailure(rejected.code, rejected.summary),
                occurred = false
            )
        }
        val json = try {
            evaluateExactJavascript(
                lease = lease,
                script = Web4AgentScripts.evaluate(
                    request,
                    configuration.maxResultChars,
                    lease.documentMaterial
                ),
                timeoutMillis = request.timeoutMillis
            )
        } catch (error: IllegalStateException) {
            if (closed) {
                return Web4AgentExactJsonExecution(
                    exactJsonFailure(
                        Web4AgentExactEffectErrors.SESSION_CLOSED,
                        "Web4Agent session closed before JavaScript execution."
                    ),
                    occurred = false
                )
            }
            throw error
        }
        val stale = isStaleTarget(json)
        if (!stale) {
            appendConsole(
                Web4AgentConsoleEntry(
                    level = "agent",
                    message = request.purpose.take(1_000),
                    sourceId = "web4agent_eval",
                    lineNumber = 0,
                    createdAtEpochMillis = nowEpochMillis()
                )
            )
        }
        val backend = if (stale) {
            exactJsonFailure(
                Web4AgentExactEffectErrors.STALE_TARGET,
                "Web4Agent page changed after approval; JavaScript was not executed."
            )
        } else {
            jsonResult(json, "Executed Web4Agent JavaScript for ${request.purpose}.")
        }
        return Web4AgentExactJsonExecution(backend, occurred = !stale)
    }

    override fun actPrepared(
        lease: Web4AgentPreparedEffect,
        action: Web4AgentAction
    ): Web4AgentExactActionExecution {
        consumePreparedEffect(lease, Web4AgentEffectKind.ACTION)?.let { rejected ->
            return Web4AgentExactActionExecution(
                result = exactActionFailure(rejected.code, rejected.summary),
                occurred = false
            )
        }
        return try {
            when (action.type) {
            "back" -> navigatePrepared(lease, action.type) { view ->
                if (view.canGoBack()) view.goBack() else error("No back history.")
            }
            "forward" -> navigatePrepared(lease, action.type) { view ->
                if (view.canGoForward()) view.goForward() else error("No forward history.")
            }
            "reload" -> navigatePrepared(lease, action.type) { view -> view.reload() }
            "wait_for_selector", "wait_for_text" -> waitPrepared(lease, action)
            else -> {
                val json = evaluateExactJavascript(
                    lease = lease,
                    script = Web4AgentScripts.action(
                        action,
                        lease.documentMaterial,
                        lease.targetMaterial
                    ),
                    timeoutMillis = action.timeoutMillis
                ).take(configuration.maxResultChars)
                val stale = isStaleTarget(json)
                if (stale) {
                    Web4AgentExactActionExecution(
                        exactActionFailure(
                            Web4AgentExactEffectErrors.STALE_TARGET,
                            "Web4Agent page or target changed after approval; the action was not executed."
                        ),
                        occurred = false
                    )
                } else {
                    val ok = isSuccessful(json)
                    Web4AgentExactActionExecution(
                        Web4AgentActionResult(
                            ok = ok,
                            summary = if (ok) {
                                "Web4Agent completed ${action.type}."
                            } else {
                                "Web4Agent ${action.type} failed."
                            },
                            dataJson = json
                        ),
                        occurred = ok
                    )
                }
            }
            }
        } catch (error: IllegalStateException) {
            if (closed) {
                Web4AgentExactActionExecution(
                    exactActionFailure(
                        Web4AgentExactEffectErrors.SESSION_CLOSED,
                        "Web4Agent session closed before the action executed."
                    ),
                    occurred = false
                )
            } else {
                throw error
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
            if (closed) {
                return exactActionFailure(
                    Web4AgentExactEffectErrors.SESSION_CLOSED,
                    "Web4Agent session is already closed."
                )
            }
            val state = pageState()
            return Web4AgentActionResult(
                ok = true,
                summary = "Web4Agent session remains visible.",
                dataJson = stateJson(true, state, "session kept")
            )
        }
        val shouldClose = synchronized(exactEffectLock) {
            if (closed) {
                false
            } else {
                closed = true
                invalidateExactStateLocked()
                true
            }
        }
        if (!shouldClose) {
            return Web4AgentActionResult(
                ok = true,
                summary = "Web4Agent session was already closed.",
                dataJson = """{"ok":true,"closed":true}"""
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
        invalidateExactState()
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
        invalidateExactState()
    }

    internal fun currentState(): Web4AgentPageState = pageState()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        val wrapper = MutableContextWrapper(applicationContext)
        contextWrapper = wrapper
        return WebView(wrapper).apply {
            addJavascriptInterface(PageEpochBridge(), EPOCH_BRIDGE_NAME)
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
                    beginDocumentTransition()
                    markLoading(true, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    installEpochObserverOnMain(view)
                    markLoading(false, null)
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean
                ) {
                    invalidateExactState()
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
                override fun onJsAlert(
                    view: WebView,
                    url: String?,
                    message: String?,
                    result: JsResult
                ): Boolean = rejectJavaScriptDialog("alert", url, result)

                override fun onJsConfirm(
                    view: WebView,
                    url: String?,
                    message: String?,
                    result: JsResult
                ): Boolean = rejectJavaScriptDialog("confirm", url, result)

                override fun onJsPrompt(
                    view: WebView,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult
                ): Boolean = rejectJavaScriptDialog("prompt", url, result)

                override fun onJsBeforeUnload(
                    view: WebView,
                    url: String?,
                    message: String?,
                    result: JsResult
                ): Boolean = rejectJavaScriptDialog("beforeunload", url, result)

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
        check(!closed) { "Web4Agent session is closed." }
        return webView ?: onMain {
            check(!closed) { "Web4Agent session is closed." }
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

    private fun evaluateExactJavascript(
        lease: Web4AgentPreparedEffect,
        script: String,
        timeoutMillis: Long
    ): String {
        val view = requireWebView()
        val result = CompletableFuture<String>()
        main.post {
            val rejection = beginExactExecutionOnMain(lease, view)
            if (rejection != null) {
                result.complete(Web4AgentExactEffectErrors.json(rejection.code, rejection.summary))
                return@post
            }
            view.evaluateJavascript(script) { encoded ->
                val decoded = Web4AgentJson.decodeJavascriptString(encoded)
                    .take(configuration.maxResultChars)
                endExactExecution(lease)
                result.complete(decoded)
            }
        }
        return try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            endExactExecution(lease)
            throw IllegalStateException("Web4Agent JavaScript timed out after ${timeoutMillis}ms.")
        }
    }

    private fun navigatePrepared(
        lease: Web4AgentPreparedEffect,
        action: String,
        operation: (WebView) -> Unit
    ): Web4AgentExactActionExecution {
        val view = requireWebView()
        val result = CompletableFuture<Web4AgentExactActionExecution>()
        main.post {
            val rejection = beginExactExecutionOnMain(lease, view)
            if (rejection != null) {
                result.complete(
                    Web4AgentExactActionExecution(
                        exactActionFailure(rejection.code, rejection.summary),
                        occurred = false
                    )
                )
                return@post
            }
            view.evaluateJavascript(Web4AgentScripts.guard(lease.documentMaterial)) { encoded ->
                val guard = Web4AgentJson.decodeJavascriptString(encoded)
                if (!isSuccessful(guard)) {
                    endExactExecution(lease)
                    result.complete(
                        Web4AgentExactActionExecution(
                            exactActionFailure(
                                Web4AgentExactEffectErrors.STALE_TARGET,
                                "Web4Agent page changed after approval; $action was not executed."
                            ),
                            occurred = false
                        )
                    )
                    return@evaluateJavascript
                }
                val navigation = runCatching {
                    operation(view)
                    Web4AgentExactActionExecution(
                        Web4AgentActionResult(
                            ok = true,
                            summary = "Web4Agent completed $action.",
                            dataJson = """{"ok":true,"action":${Web4AgentJson.quote(action)}}"""
                        ),
                        occurred = true
                    )
                }.getOrElse { error ->
                    Web4AgentExactActionExecution(
                        Web4AgentActionResult(
                            ok = false,
                            summary = error.message ?: "Web4Agent $action failed.",
                            dataJson = """{"ok":false,"action":${Web4AgentJson.quote(action)},"error":${
                                Web4AgentJson.quote(error.message ?: "failed")
                            }}"""
                        ),
                        occurred = false
                    )
                }
                endExactExecution(lease)
                result.complete(navigation)
            }
        }
        return try {
            result.get(leaseTimeoutMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            endExactExecution(lease)
            throw IllegalStateException("Web4Agent $action timed out.")
        }
    }

    private fun waitPrepared(
        lease: Web4AgentPreparedEffect,
        action: Web4AgentAction
    ): Web4AgentExactActionExecution {
        val guard = evaluateExactJavascript(
            lease,
            Web4AgentScripts.guard(lease.documentMaterial),
            minOf(action.timeoutMillis, configuration.defaultTimeoutMillis)
        )
        if (!isSuccessful(guard)) {
            return Web4AgentExactActionExecution(
                exactActionFailure(
                    Web4AgentExactEffectErrors.STALE_TARGET,
                    "Web4Agent page changed after approval; ${action.type} was not started."
                ),
                occurred = false
            )
        }
        return Web4AgentExactActionExecution(waitFor(action), occurred = false)
    }

    private fun beginExactExecutionOnMain(
        lease: Web4AgentPreparedEffect,
        view: WebView
    ): Web4AgentEffectPreparation.Rejected? = synchronized(exactEffectLock) {
        when {
            closed || webView !== view -> Web4AgentEffectPreparation.Rejected(
                Web4AgentExactEffectErrors.SESSION_CLOSED,
                "Web4Agent session is closed or was replaced."
            )
            pageEpoch != lease.pageEpoch -> stalePreparation(
                "Web4Agent page changed after approval."
            )
            activeEffectLeaseId != null -> stalePreparation(
                "Another exact Web4Agent effect already owns this page epoch."
            )
            else -> {
                activeEffectLeaseId = lease.leaseId
                null
            }
        }
    }

    private fun endExactExecution(lease: Web4AgentPreparedEffect) {
        synchronized(exactEffectLock) {
            if (activeEffectLeaseId == lease.leaseId) {
                activeEffectLeaseId = null
                invalidateExactStateLocked()
            }
        }
    }

    private fun consumePreparedEffect(
        lease: Web4AgentPreparedEffect,
        expectedKind: Web4AgentEffectKind
    ): Web4AgentEffectPreparation.Rejected? = synchronized(exactEffectLock) {
        if (closed) {
            return@synchronized Web4AgentEffectPreparation.Rejected(
                Web4AgentExactEffectErrors.SESSION_CLOSED,
                "Web4Agent session is closed."
            )
        }
        val stored = preparedEffects.remove(lease.leaseId)
        if (stored != lease || lease.sessionId != sessionId || lease.kind != expectedKind) {
            return@synchronized stalePreparation(
                "The one-use Web4Agent effect lease is stale or does not match this operation."
            )
        }
        if (
            pageEpoch != lease.pageEpoch ||
            nowEpochMillis() - lease.createdAtEpochMillis > EXACT_LEASE_TTL_MILLIS
        ) {
            return@synchronized stalePreparation(
                "The Web4Agent page changed or its exact-effect lease expired."
            )
        }
        null
    }

    private fun bindObservation(rawJson: String): String {
        val payload = runCatching { JSONObject(rawJson) }.getOrElse {
            return Web4AgentExactEffectErrors.json(
                Web4AgentExactEffectErrors.EXACT_BINDING_REQUIRED,
                "Web4Agent could not bind this observation to a host page epoch."
            )
        }
        if (!payload.optBoolean("ok", false)) return rawJson.take(configuration.maxResultChars)
        val documentMaterial = payload.optString(INTERNAL_DOCUMENT_MATERIAL, "")
        payload.remove(INTERNAL_DOCUMENT_MATERIAL)
        if (documentMaterial.isBlank()) {
            return Web4AgentExactEffectErrors.json(
                Web4AgentExactEffectErrors.EXACT_BINDING_REQUIRED,
                "Web4Agent document fingerprint is unavailable."
            )
        }
        val targetMaterials = linkedMapOf<String, String>()
        val elements = payload.optJSONArray("elements")
        if (elements != null) {
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                val material = element.optString(INTERNAL_TARGET_MATERIAL, "")
                element.remove(INTERNAL_TARGET_MATERIAL)
                if (material.isNotBlank()) {
                    val fingerprint = sha256(material)
                    element.put("targetFingerprint", fingerprint)
                    targetMaterials.putIfAbsent(fingerprint, material)
                }
            }
        }
        val observationId = "web-observation-${UUID.randomUUID()}"
        val documentFingerprint = sha256(documentMaterial)
        val boundEpoch = synchronized(exactEffectLock) {
            if (closed) {
                return Web4AgentExactEffectErrors.json(
                    Web4AgentExactEffectErrors.SESSION_CLOSED,
                    "Web4Agent session closed before the observation was bound."
                )
            }
            val epoch = pageEpoch
            observations[observationId] = ExactObservation(
                observationId = observationId,
                pageEpoch = epoch,
                documentFingerprint = documentFingerprint,
                documentMaterial = documentMaterial,
                targetMaterials = targetMaterials,
                createdAtEpochMillis = nowEpochMillis()
            )
            trimExactLedgers()
            epoch
        }
        payload.put("pageEpoch", boundEpoch)
        payload.put("observationId", observationId)
        payload.put("documentFingerprint", documentFingerprint)
        val encoded = payload.toString()
        return if (encoded.length <= configuration.maxResultChars) {
            encoded
        } else {
            synchronized(exactEffectLock) { observations.remove(observationId) }
            Web4AgentExactEffectErrors.json(
                Web4AgentExactEffectErrors.EXACT_BINDING_REQUIRED,
                "Bound Web4Agent observation exceeds the configured result limit."
            )
        }
    }

    private fun exactJsonFailure(code: String, summary: String): Web4AgentJsonResult {
        return Web4AgentJsonResult(
            ok = false,
            dataJson = Web4AgentExactEffectErrors.json(code, summary),
            summary = summary
        )
    }

    private fun exactActionFailure(code: String, summary: String): Web4AgentActionResult {
        return Web4AgentActionResult(
            ok = false,
            summary = summary,
            dataJson = Web4AgentExactEffectErrors.json(code, summary)
        )
    }

    private fun isStaleTarget(json: String): Boolean {
        return runCatching {
            JSONObject(json).optString("code") == Web4AgentExactEffectErrors.STALE_TARGET
        }.getOrDefault(false)
    }

    private fun stalePreparation(summary: String): Web4AgentEffectPreparation.Rejected {
        return Web4AgentEffectPreparation.Rejected(
            Web4AgentExactEffectErrors.STALE_TARGET,
            summary
        )
    }

    private fun trimExactLedgers() {
        while (observations.size > MAX_EXACT_OBSERVATIONS) {
            observations.remove(observations.keys.first())
        }
        while (preparedEffects.size > MAX_PREPARED_EFFECTS) {
            preparedEffects.remove(preparedEffects.keys.first())
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun leaseTimeoutMillis(): Long = minOf(
        MAIN_THREAD_TIMEOUT_MILLIS,
        configuration.defaultTimeoutMillis
    )

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

    private fun rejectJavaScriptDialog(
        type: String,
        url: String?,
        result: JsResult
    ): Boolean {
        val source = runCatching {
            Uri.parse(url.orEmpty()).let { uri ->
                val scheme = uri.scheme?.lowercase().orEmpty().take(16)
                val host = uri.host.orEmpty().take(253)
                when {
                    scheme.isNotBlank() && host.isNotBlank() -> "$scheme://$host"
                    scheme.isNotBlank() -> "$scheme:"
                    else -> "unknown origin"
                }
            }
        }.getOrDefault("unknown origin")
        appendConsole(
            Web4AgentConsoleEntry(
                level = "warning",
                message = "Blocked untrusted JavaScript $type dialog from $source.",
                sourceId = "javascript-dialog-policy",
                lineNumber = 0,
                createdAtEpochMillis = nowEpochMillis()
            )
        )
        result.cancel()
        return true
    }

    private fun ensureEpochObserver() {
        val view = requireWebView()
        onMain { installEpochObserverOnMain(view) }
    }

    private fun installEpochObserverOnMain(view: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || webView !== view) return
        val token = activeDocumentToken
        if (installedObserverToken == token) return
        installedObserverToken = token
        view.evaluateJavascript(
            Web4AgentScripts.installEpochObserver(token, EPOCH_BRIDGE_NAME)
        ) { encoded ->
            if (Web4AgentJson.decodeJavascriptString(encoded) != "true" &&
                installedObserverToken == token) {
                installedObserverToken = null
            }
        }
    }

    private fun beginDocumentTransition() {
        synchronized(exactEffectLock) {
            if (closed) return
            activeDocumentToken = UUID.randomUUID().toString()
            installedObserverToken = null
            invalidateExactStateLocked()
        }
    }

    private fun invalidateExactState() {
        synchronized(exactEffectLock) {
            if (!closed) invalidateExactStateLocked()
        }
    }

    private fun invalidateExactStateLocked() {
        pageEpoch = if (pageEpoch == Long.MAX_VALUE) 1L else pageEpoch + 1L
        observations.clear()
        preparedEffects.clear()
    }

    private inner class PageEpochBridge {
        @JavascriptInterface
        fun changed(documentToken: String?) {
            if (documentToken.isNullOrBlank() || documentToken.length > 128) return
            synchronized(exactEffectLock) {
                if (!closed && documentToken == activeDocumentToken) {
                    invalidateExactStateLocked()
                }
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

    private data class ExactObservation(
        val observationId: String,
        val pageEpoch: Long,
        val documentFingerprint: String,
        val documentMaterial: String,
        val targetMaterials: Map<String, String>,
        val createdAtEpochMillis: Long
    )

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
        const val EPOCH_BRIDGE_NAME = "__AndroidAgentPageEpochHost"
        const val INTERNAL_DOCUMENT_MATERIAL = "__androidAgentDocumentMaterial"
        const val INTERNAL_TARGET_MATERIAL = "__androidAgentTargetMaterial"
        const val EXACT_LEASE_TTL_MILLIS = 5 * 60_000L
        const val MAX_EXACT_OBSERVATIONS = 32
        const val MAX_PREPARED_EFFECTS = 32
    }
}
