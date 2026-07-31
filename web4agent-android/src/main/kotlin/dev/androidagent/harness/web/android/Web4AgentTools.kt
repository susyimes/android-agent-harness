// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import dev.androidagent.harness.AgentArtifactRef
import dev.androidagent.harness.AgentPrivacyLabel
import dev.androidagent.harness.AgentRawPayload
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentRawPayloadStore
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolArgumentSchema
import dev.androidagent.harness.AgentToolArgumentType
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolEffectRecord
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent
import java.util.UUID

/**
 * Model-visible Web4Agent capability bundle.
 *
 * Each invocation is bound to AgentToolInvocation.sessionId, so two chats never
 * share a WebView by accident. Mutating actions and free-form JavaScript use
 * the host's exact approval coordinator before they execute.
 */
class Web4AgentToolSet(
    private val sessions: Web4AgentSessionProvider,
    private val presenter: Web4AgentPresenter,
    private val approvals: AgentApprovalCoordinator = AgentApprovalCoordinator(),
    private val rawPayloadStore: AgentRawPayloadStore? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    constructor(
        runtime: Web4AgentRuntime,
        approvals: AgentApprovalCoordinator = AgentApprovalCoordinator(),
        rawPayloadStore: AgentRawPayloadStore? = null,
        nowEpochMillis: () -> Long = System::currentTimeMillis
    ) : this(runtime, runtime, approvals, rawPayloadStore, nowEpochMillis)

    val toolNames: Set<String> = Web4AgentGuidance.toolNames

    fun tools(): List<AgentTool> = listOf(
        OpenTool(),
        ObserveTool(),
        ReadTool(),
        InspectTool(),
        EvalTool(),
        ActTool(),
        ConsoleTool(),
        CaptureTool(),
        FinishTool()
    )

    private inner class OpenTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_open",
            description = "Opens one HTTPS URL, search query, or inline HTML document in a visible " +
                "Web4Agent WebView bound to the current chat session.",
            optionalArguments = setOf("url", "query", "html", "timeout_ms"),
            argumentSchemas = mapOf(
                "url" to stringSchema("HTTPS URL or hostname to open."),
                "query" to stringSchema("Search query to open with the host-configured search URL."),
                "html" to stringSchema("Inline HTML document for a local Web4Agent task."),
                "timeout_ms" to integerSchema("Load wait from 500 to 30000 milliseconds.")
            ),
            capability = READ_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val timeout = invocation.longArg("timeout_ms", 8_000L, 500L..30_000L)
                ?: return invalid("timeout_ms must be an integer from 500 to 30000.")
            val request = runCatching {
                Web4AgentOpenRequest(
                    url = invocation.nonBlank("url"),
                    query = invocation.nonBlank("query"),
                    html = invocation.nonBlank("html"),
                    waitTimeoutMillis = timeout
                )
            }.getOrElse { error -> return invalid(error.message ?: "Invalid open request.") }
            return runCatching {
                presenter.show(invocation.sessionId)
                sessions.session(invocation.sessionId).open(request)
            }.fold(
                onSuccess = { result ->
                    result(result.ok, result.summary, result.dataJson, AgentPrivacyLabel.SENSITIVE)
                },
                onFailure = { error ->
                    unavailable(error.message ?: "Visible Web4Agent browser is unavailable.")
                }
            )
        }
    }

    private inner class ObserveTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_observe",
            description = "Observes URL, title, ready state, bounded readable text, framework hints, " +
                "and visible interactive DOM elements in the current Web4Agent page.",
            optionalArguments = setOf("max_chars", "max_elements"),
            argumentSchemas = mapOf(
                "max_chars" to integerSchema("Maximum page text characters, 256 to 49152."),
                "max_elements" to integerSchema("Maximum interactive elements, 1 to 100.")
            ),
            capability = READ_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val maxChars = invocation.intArg("max_chars", 12_000, 256..48 * 1024)
                ?: return invalid("max_chars must be an integer from 256 to 49152.")
            val maxElements = invocation.intArg("max_elements", 50, 1..100)
                ?: return invalid("max_elements must be an integer from 1 to 100.")
            return runCatching {
                sessions.session(invocation.sessionId).observe(
                    Web4AgentObservationRequest(maxChars, maxElements)
                )
            }.fold(
                onSuccess = { observation ->
                    result(
                        ok = isSuccessful(observation.dataJson),
                        summary = "Observed ${observation.title.ifBlank { observation.url }}.",
                        dataJson = observation.dataJson,
                        privacy = AgentPrivacyLabel.SENSITIVE
                    )
                },
                onFailure = { error -> unavailable(error.message ?: "Web page observation failed.") }
            )
        }
    }

    private inner class ReadTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_read",
            description = "Reads bounded page content as text, HTML, links, forms, tables, or metadata. " +
                "Use selector to scope the read.",
            optionalArguments = setOf("mode", "selector", "max_chars"),
            argumentSchemas = mapOf(
                "mode" to AgentToolArgumentSchema(
                    type = AgentToolArgumentType.STRING,
                    description = "Content representation.",
                    enumValues = Web4AgentReadRequest.MODES.sorted()
                ),
                "selector" to stringSchema("Optional CSS selector that scopes the read."),
                "max_chars" to integerSchema("Maximum result characters, 256 to 49152.")
            ),
            capability = READ_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val maxChars = invocation.intArg("max_chars", 16_000, 256..48 * 1024)
                ?: return invalid("max_chars must be an integer from 256 to 49152.")
            val request = runCatching {
                Web4AgentReadRequest(
                    mode = invocation.nonBlank("mode") ?: "text",
                    selector = invocation.nonBlank("selector"),
                    maxChars = maxChars
                )
            }.getOrElse { error -> return invalid(error.message ?: "Invalid read request.") }
            return backendJson(invocation) { session -> session.read(request) }
        }
    }

    private inner class InspectTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_inspect",
            description = "Inspects precise DOM matches by CSS selector, XPath, or visible text and " +
                "returns bounded attributes, text, HTML, and bounds.",
            optionalArguments = setOf(
                "selector",
                "xpath",
                "text",
                "max_elements",
                "max_chars"
            ),
            argumentSchemas = mapOf(
                "selector" to stringSchema("CSS selector."),
                "xpath" to stringSchema("XPath expression."),
                "text" to stringSchema("Case-insensitive visible-text query."),
                "max_elements" to integerSchema("Maximum matching elements, 1 to 50."),
                "max_chars" to integerSchema("Maximum result characters, 256 to 49152.")
            ),
            capability = READ_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val maxElements = invocation.intArg("max_elements", 20, 1..50)
                ?: return invalid("max_elements must be an integer from 1 to 50.")
            val maxChars = invocation.intArg("max_chars", 16_000, 256..48 * 1024)
                ?: return invalid("max_chars must be an integer from 256 to 49152.")
            val request = runCatching {
                Web4AgentInspectRequest(
                    selector = invocation.nonBlank("selector"),
                    xpath = invocation.nonBlank("xpath"),
                    text = invocation.nonBlank("text"),
                    maxElements = maxElements,
                    maxChars = maxChars
                )
            }.getOrElse { error -> return invalid(error.message ?: "Invalid inspect request.") }
            return backendJson(invocation) { session -> session.inspect(request) }
        }
    }

    private inner class EvalTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_eval",
            description = "Runs host-approved free-form JavaScript in the current Web4Agent page. " +
                "The script executes as a function body; use return to send a value back.",
            requiredArguments = setOf("script", "purpose"),
            optionalArguments = setOf("timeout_ms"),
            argumentSchemas = mapOf(
                "script" to stringSchema("JavaScript function body to execute."),
                "purpose" to stringSchema("Short human-readable reason for this script."),
                "timeout_ms" to integerSchema("Execution timeout from 100 to 30000 milliseconds.")
            ),
            capability = EVAL_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val timeout = invocation.longArg("timeout_ms", 8_000L, 100L..30_000L)
                ?: return invalid("timeout_ms must be an integer from 100 to 30000.")
            val request = runCatching {
                Web4AgentEvalRequest(
                    script = invocation.arguments.getValue("script"),
                    purpose = invocation.arguments.getValue("purpose"),
                    timeoutMillis = timeout
                )
            }.getOrElse { error -> return invalid(error.message ?: "Invalid eval request.") }
            return executeGoverned(
                invocation = invocation,
                spec = spec,
                targetRef = "web-session:${invocation.sessionId}",
                summary = "Run Web4Agent JavaScript: ${request.purpose.take(500)}",
                occurredOnFailure = true
            ) {
                val backend = sessions.session(invocation.sessionId).evaluate(request)
                BackendResult(backend.ok, backend.summary, backend.dataJson)
            }
        }
    }

    private inner class ActTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_act",
            description = "Performs one host-approved page action: click, type, scroll, back, forward, " +
                "reload, wait_for_selector, or wait_for_text. Observe again after a change.",
            requiredArguments = setOf("action"),
            optionalArguments = setOf(
                "element_id",
                "selector",
                "xpath",
                "text",
                "value",
                "direction",
                "distance_px",
                "timeout_ms"
            ),
            argumentSchemas = mapOf(
                "action" to AgentToolArgumentSchema(
                    type = AgentToolArgumentType.STRING,
                    description = "One Web4Agent action.",
                    enumValues = Web4AgentAction.TYPES.sorted()
                ),
                "element_id" to stringSchema("Stable element id from web4agent_observe."),
                "selector" to stringSchema("CSS selector target."),
                "xpath" to stringSchema("XPath target."),
                "text" to stringSchema("Visible-text target or wait text."),
                "value" to stringSchema("Text inserted by the type action."),
                "direction" to AgentToolArgumentSchema(
                    type = AgentToolArgumentType.STRING,
                    description = "Scroll direction.",
                    enumValues = listOf("up", "down", "left", "right")
                ),
                "distance_px" to integerSchema("Scroll distance from 1 to 10000 pixels."),
                "timeout_ms" to integerSchema("Action/wait timeout from 100 to 30000 milliseconds.")
            ),
            capability = ACT_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val distance = invocation.intArg("distance_px", 600, 1..10_000)
                ?: return invalid("distance_px must be an integer from 1 to 10000.")
            val timeout = invocation.longArg("timeout_ms", 8_000L, 100L..30_000L)
                ?: return invalid("timeout_ms must be an integer from 100 to 30000.")
            val action = runCatching {
                Web4AgentAction(
                    type = invocation.arguments.getValue("action"),
                    elementId = invocation.nonBlank("element_id"),
                    selector = invocation.nonBlank("selector"),
                    xpath = invocation.nonBlank("xpath"),
                    text = invocation.nonBlank("text"),
                    value = invocation.arguments["value"],
                    direction = invocation.nonBlank("direction"),
                    distancePixels = distance,
                    timeoutMillis = timeout
                )
            }.getOrElse { error -> return invalid(error.message ?: "Invalid action.") }
            val target = action.elementId ?: action.selector ?: action.xpath ?: action.text
                ?: action.type
            return executeGoverned(
                invocation = invocation,
                spec = spec,
                targetRef = "web:${invocation.sessionId}:${target.take(500)}",
                summary = "Run Web4Agent ${action.type} on ${target.take(500)}.",
                occurredOnFailure = false
            ) {
                val backend = sessions.session(invocation.sessionId).act(action)
                BackendResult(backend.ok, backend.summary, backend.dataJson)
            }
        }
    }

    private inner class ConsoleTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_console",
            description = "Reads bounded recent WebView console messages and Web4Agent eval notes.",
            optionalArguments = setOf("limit"),
            argumentSchemas = mapOf(
                "limit" to integerSchema("Maximum entries, 1 to 200.")
            ),
            capability = READ_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val limit = invocation.intArg("limit", 50, 1..200)
                ?: return invalid("limit must be an integer from 1 to 200.")
            return runCatching {
                Web4AgentJson.console(sessions.session(invocation.sessionId).console(limit))
            }.fold(
                onSuccess = { json ->
                    result(
                        ok = true,
                        summary = "Read Web4Agent console.",
                        dataJson = json,
                        privacy = AgentPrivacyLabel.SENSITIVE
                    )
                },
                onFailure = { error ->
                    unavailable(error.message ?: "Web4Agent console is unavailable.")
                }
            )
        }
    }

    private inner class CaptureTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_capture",
            description = "Captures the visible WebView into a short-lived, host-scoped PNG payload. " +
                "Pixels are never inserted into provider-visible text.",
            capability = CAPTURE_CAPABILITY
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val store = rawPayloadStore
                ?: return unavailable("The host did not configure a Web4Agent capture store.")
            return runCatching {
                val capture = sessions.session(invocation.sessionId).capture()
                val scope = AgentRawPayloadScope(
                    runId = invocation.runId,
                    sessionId = invocation.sessionId,
                    toolCallId = invocation.callId
                )
                val ref = "web4agent:${capture.id}:${UUID.randomUUID()}"
                store.put(
                    AgentRawPayload(
                        ref = ref,
                        content = capture.bytes,
                        mediaType = "image/png",
                        privacy = AgentPrivacyLabel.RESTRICTED,
                        scope = scope,
                        createdAtEpochMillis = capture.createdAtEpochMillis,
                        expiresAtEpochMillis = capture.expiresAtEpochMillis
                    )
                )
                val summary = "Captured temporary Web4Agent image " +
                    "${capture.width}x${capture.height}."
                AgentToolResult.success(
                    summary,
                    AgentToolResultEnvelope(
                        status = AgentToolResultStatus.SUCCESS,
                        summary = summary,
                        artifacts = listOf(
                            AgentArtifactRef(
                                id = capture.id,
                                mediaType = "image/png",
                                displayName = "web4agent-capture",
                                byteSize = capture.bytes.size.toLong()
                            )
                        ),
                        rawPayloadRef = ref,
                        privacy = AgentPrivacyLabel.RESTRICTED,
                        createdAtEpochMillis = capture.createdAtEpochMillis,
                        expiresAtEpochMillis = capture.expiresAtEpochMillis
                    )
                )
            }.getOrElse { error ->
                unavailable(error.message ?: "Web4Agent capture failed.")
            }
        }
    }

    private inner class FinishTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_finish",
            description = "Finishes the current web task and either closes or keeps its visible session.",
            optionalArguments = setOf("keep_session"),
            argumentSchemas = mapOf(
                "keep_session" to AgentToolArgumentSchema(
                    type = AgentToolArgumentType.BOOLEAN,
                    description = "Keep the visible WebView session open for the user."
                )
            )
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            val keep = invocation.booleanArg("keep_session", false)
                ?: return invalid("keep_session must be true or false.")
            return runCatching {
                sessions.session(invocation.sessionId).finish(keep)
            }.fold(
                onSuccess = { backend ->
                    result(backend.ok, backend.summary, backend.dataJson)
                },
                onFailure = { error ->
                    unavailable(error.message ?: "Web4Agent finish failed.")
                }
            )
        }
    }

    private fun backendJson(
        invocation: AgentToolInvocation,
        operation: (Web4AgentSession) -> Web4AgentJsonResult
    ): AgentToolResult {
        return runCatching {
            operation(sessions.session(invocation.sessionId))
        }.fold(
            onSuccess = { backend ->
                result(
                    backend.ok,
                    backend.summary,
                    backend.dataJson,
                    AgentPrivacyLabel.SENSITIVE
                )
            },
            onFailure = { error ->
                unavailable(error.message ?: "Web4Agent operation failed.")
            }
        )
    }

    private fun executeGoverned(
        invocation: AgentToolInvocation,
        spec: AgentToolSpec,
        targetRef: String,
        summary: String,
        occurredOnFailure: Boolean,
        operation: () -> BackendResult
    ): AgentToolResult {
        val intent = AgentEffectIntent(
            runId = invocation.runId,
            sessionId = invocation.sessionId,
            toolCallId = invocation.callId,
            toolName = spec.name,
            capability = spec.capability,
            targetRef = targetRef,
            argumentHash = AgentEffectHasher.hash(spec.name, invocation.arguments),
            summary = summary
        )
        val authorization = approvals.authorize(intent)
        val token = (authorization as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            val rejection = authorization as? AgentEffectAuthorization.Rejected
            val status = if (rejection?.decision == AgentApprovalDecision.UNAVAILABLE) {
                AgentToolResultStatus.UNAVAILABLE
            } else {
                AgentToolResultStatus.DENIED
            }
            return effectResult(
                intent,
                status,
                rejection?.message ?: "Exact Web4Agent approval was not granted.",
                occurred = false
            )
        }
        if (!approvals.consume(token, intent)) {
            return effectResult(
                intent,
                AgentToolResultStatus.DENIED,
                "Web4Agent approval expired, changed, or was already consumed.",
                occurred = false
            )
        }
        val backend = runCatching(operation).getOrElse { error ->
            return effectResult(
                intent,
                AgentToolResultStatus.UNAVAILABLE,
                error.message ?: "Web4Agent operation failed.",
                occurred = occurredOnFailure
            )
        }
        return effectResult(
            intent = intent,
            status = if (backend.ok) {
                AgentToolResultStatus.SUCCESS
            } else {
                AgentToolResultStatus.FAILURE
            },
            summary = backend.summary,
            dataJson = backend.dataJson,
            occurred = backend.ok || occurredOnFailure
        )
    }

    private fun effectResult(
        intent: AgentEffectIntent,
        status: AgentToolResultStatus,
        summary: String,
        dataJson: String? = null,
        occurred: Boolean
    ): AgentToolResult {
        val envelope = AgentToolResultEnvelope(
            status = status,
            summary = summary,
            dataJson = dataJson,
            effect = AgentToolEffectRecord(
                effectId = "web-effect-${UUID.randomUUID()}",
                sideEffect = intent.capability.sideEffect,
                targetRef = intent.targetRef,
                argumentHash = intent.argumentHash,
                idempotencyKey = "${intent.runId}:${intent.toolCallId}",
                occurred = occurred
            ),
            privacy = AgentPrivacyLabel.SENSITIVE,
            createdAtEpochMillis = nowEpochMillis()
        )
        return if (status == AgentToolResultStatus.SUCCESS) {
            AgentToolResult.success(dataJson ?: summary, envelope)
        } else {
            AgentToolResult.failure(summary, envelope)
        }
    }

    private fun result(
        ok: Boolean,
        summary: String,
        dataJson: String,
        privacy: AgentPrivacyLabel = AgentPrivacyLabel.INTERNAL
    ): AgentToolResult {
        val status = if (ok) AgentToolResultStatus.SUCCESS else AgentToolResultStatus.FAILURE
        val envelope = AgentToolResultEnvelope(
            status = status,
            summary = summary,
            dataJson = dataJson,
            privacy = privacy,
            createdAtEpochMillis = nowEpochMillis()
        )
        return if (ok) {
            AgentToolResult.success(dataJson, envelope)
        } else {
            AgentToolResult.failure(summary, envelope)
        }
    }

    private fun invalid(message: String): AgentToolResult = failure(
        AgentToolResultStatus.FAILURE,
        message
    )

    private fun unavailable(message: String): AgentToolResult = failure(
        AgentToolResultStatus.UNAVAILABLE,
        message
    )

    private fun failure(status: AgentToolResultStatus, message: String): AgentToolResult {
        return AgentToolResult.failure(
            message,
            AgentToolResultEnvelope(
                status = status,
                summary = message,
                createdAtEpochMillis = nowEpochMillis()
            )
        )
    }

    private fun AgentToolInvocation.nonBlank(name: String): String? {
        return arguments[name]?.takeIf(String::isNotBlank)
    }

    private fun AgentToolInvocation.intArg(
        name: String,
        fallback: Int,
        range: IntRange
    ): Int? {
        val value = arguments[name]?.toIntOrNull() ?: return if (name in arguments) null else fallback
        return value.takeIf { candidate -> candidate in range }
    }

    private fun AgentToolInvocation.longArg(
        name: String,
        fallback: Long,
        range: LongRange
    ): Long? {
        val value = arguments[name]?.toLongOrNull()
            ?: return if (name in arguments) null else fallback
        return value.takeIf { candidate -> candidate in range }
    }

    private fun AgentToolInvocation.booleanArg(name: String, fallback: Boolean): Boolean? {
        return when (arguments[name]?.lowercase()) {
            null -> fallback
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun isSuccessful(json: String): Boolean {
        return Regex("""["']?ok["']?\s*:\s*true""").containsMatchIn(json)
    }

    private data class BackendResult(
        val ok: Boolean,
        val summary: String,
        val dataJson: String
    )

    private companion object {
        val READ_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_READ,
            risk = AgentToolRisk.LOW,
            dataScopes = setOf("web-page"),
            requiresForeground = true,
            idempotency = AgentToolIdempotency.IDEMPOTENT
        )
        val CAPTURE_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_READ,
            risk = AgentToolRisk.LOW,
            dataScopes = setOf("web-page-image"),
            requiresForeground = true,
            idempotency = AgentToolIdempotency.IDEMPOTENT
        )
        val ACT_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.EXTERNAL_WRITE,
            risk = AgentToolRisk.CONTEXTUAL,
            dataScopes = setOf("web-page"),
            requiresForeground = true,
            idempotency = AgentToolIdempotency.UNKNOWN,
            targetArgumentNames = setOf("element_id", "selector", "xpath", "text")
        )
        val EVAL_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.EXTERNAL_WRITE,
            risk = AgentToolRisk.HIGH,
            dataScopes = setOf("web-page", "web-javascript"),
            requiresForeground = true,
            idempotency = AgentToolIdempotency.NON_IDEMPOTENT,
            targetArgumentNames = setOf("purpose")
        )

        fun stringSchema(description: String) = AgentToolArgumentSchema(
            type = AgentToolArgumentType.STRING,
            description = description
        )

        fun integerSchema(description: String) = AgentToolArgumentSchema(
            type = AgentToolArgumentType.INTEGER,
            description = description
        )
    }
}
