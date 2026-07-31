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
                if (presenter is Web4AgentAcknowledgedPresenter) {
                    val acknowledgement = presenter.showAndAwait(
                        invocation.sessionId,
                        request.waitTimeoutMillis
                    )
                    check(acknowledgement.status == Web4AgentPresentationStatus.ATTACHED) {
                        "Visible Web4Agent browser did not attach."
                    }
                } else {
                    presenter.show(invocation.sessionId)
                }
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
                "The script executes only when observation_id and expected_page_epoch still match; " +
                "use return to send a value back.",
            requiredArguments = setOf(
                "script",
                "purpose",
                "observation_id",
                "expected_page_epoch"
            ),
            optionalArguments = setOf("timeout_ms"),
            argumentSchemas = mapOf(
                "script" to stringSchema("JavaScript function body to execute."),
                "purpose" to stringSchema("Short human-readable reason for this script."),
                "observation_id" to stringSchema("Host observation id from observe or inspect."),
                "expected_page_epoch" to integerSchema(
                    "Host page epoch from the same observe or inspect result."
                ),
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
            val binding = invocation.exactBinding()
                ?: return exactBindingFailure(
                    invocation,
                    spec,
                    "web-session:${invocation.sessionId}",
                    "web4agent_eval requires a valid observation_id and expected_page_epoch."
                )
            val exactSession = exactSession(invocation)
                ?: return exactUnsupportedFailure(invocation, spec, binding)
            val lease = when (
                val preparation = exactSession.prepareExactEffect(
                    Web4AgentEffectKind.EVALUATE,
                    binding,
                    requireTarget = false
                )
            ) {
                is Web4AgentEffectPreparation.Ready -> preparation.lease
                is Web4AgentEffectPreparation.Rejected -> {
                    return exactPreparationFailure(invocation, spec, binding, preparation)
                }
            }
            return executeGoverned(
                invocation = invocation,
                spec = spec,
                targetRef = lease.targetRef(),
                summary = "Run Web4Agent JavaScript: ${request.purpose.take(500)}",
                occurredOnFailure = true
            ) {
                val backend = exactSession.evaluatePrepared(lease, request)
                BackendResult(
                    backend.result.ok,
                    backend.result.summary,
                    backend.result.dataJson,
                    backend.occurred
                )
            }
        }
    }

    private inner class ActTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "web4agent_act",
            description = "Performs one host-approved page action: click, type, scroll, back, forward, " +
                "reload, wait_for_selector, or wait_for_text. Every action binds an observation and " +
                "page epoch; click/type also bind target_fingerprint. Observe again after a change.",
            requiredArguments = setOf("action", "observation_id", "expected_page_epoch"),
            optionalArguments = setOf(
                "element_id",
                "selector",
                "xpath",
                "text",
                "value",
                "direction",
                "distance_px",
                "timeout_ms",
                "target_fingerprint"
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
                "observation_id" to stringSchema("Host observation id from observe or inspect."),
                "expected_page_epoch" to integerSchema(
                    "Host page epoch from the same observe or inspect result."
                ),
                "target_fingerprint" to stringSchema(
                    "Exact target fingerprint returned for a click/type target."
                ),
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
            val binding = invocation.exactBinding()
                ?: return exactBindingFailure(
                    invocation,
                    spec,
                    "web:${invocation.sessionId}:${target.take(500)}",
                    "web4agent_act requires a valid observation_id and expected_page_epoch."
                )
            val exactSession = exactSession(invocation)
                ?: return exactUnsupportedFailure(invocation, spec, binding)
            val lease = when (
                val preparation = exactSession.prepareExactEffect(
                    Web4AgentEffectKind.ACTION,
                    binding,
                    requireTarget = action.type == "click" || action.type == "type"
                )
            ) {
                is Web4AgentEffectPreparation.Ready -> preparation.lease
                is Web4AgentEffectPreparation.Rejected -> {
                    return exactPreparationFailure(invocation, spec, binding, preparation)
                }
            }
            return executeGoverned(
                invocation = invocation,
                spec = spec,
                targetRef = lease.targetRef(),
                summary = "Run Web4Agent ${action.type} on ${target.take(500)}.",
                occurredOnFailure = false
            ) {
                val backend = exactSession.actPrepared(lease, action)
                BackendResult(
                    backend.result.ok,
                    backend.result.summary,
                    backend.result.dataJson,
                    backend.occurred
                )
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

    private fun AgentToolInvocation.exactBinding(): Web4AgentExpectedBinding? {
        val epoch = arguments["expected_page_epoch"]?.toLongOrNull()
            ?.takeIf { value -> value > 0L }
            ?: return null
        val observationId = nonBlank("observation_id")
            ?.takeIf { value -> value.length <= 256 }
            ?: return null
        val targetFingerprint = nonBlank("target_fingerprint")
        if (
            targetFingerprint != null &&
            !Regex("[0-9a-f]{64}").matches(targetFingerprint)
        ) {
            return null
        }
        return Web4AgentExpectedBinding(epoch, observationId, targetFingerprint)
    }

    private fun exactSession(
        invocation: AgentToolInvocation
    ): Web4AgentExactEffectSession? {
        return runCatching { sessions.session(invocation.sessionId) }
            .getOrNull() as? Web4AgentExactEffectSession
    }

    private fun exactBindingFailure(
        invocation: AgentToolInvocation,
        spec: AgentToolSpec,
        targetRef: String,
        summary: String
    ): AgentToolResult {
        val intent = effectIntent(invocation, spec, targetRef, summary)
        return effectResult(
            intent = intent,
            status = AgentToolResultStatus.FAILURE,
            summary = summary,
            dataJson = Web4AgentExactEffectErrors.json(
                Web4AgentExactEffectErrors.EXACT_BINDING_REQUIRED,
                summary
            ),
            occurred = false
        )
    }

    private fun exactUnsupportedFailure(
        invocation: AgentToolInvocation,
        spec: AgentToolSpec,
        binding: Web4AgentExpectedBinding
    ): AgentToolResult {
        val summary = "The Web4Agent session provider cannot atomically revalidate exact effects."
        val intent = effectIntent(invocation, spec, binding.targetRef(invocation.sessionId), summary)
        return effectResult(
            intent = intent,
            status = AgentToolResultStatus.UNAVAILABLE,
            summary = summary,
            dataJson = Web4AgentExactEffectErrors.json(
                Web4AgentExactEffectErrors.UNSUPPORTED_SESSION,
                summary
            ),
            occurred = false
        )
    }

    private fun exactPreparationFailure(
        invocation: AgentToolInvocation,
        spec: AgentToolSpec,
        binding: Web4AgentExpectedBinding,
        rejection: Web4AgentEffectPreparation.Rejected
    ): AgentToolResult {
        val intent = effectIntent(
            invocation,
            spec,
            binding.targetRef(invocation.sessionId),
            rejection.summary
        )
        val status = if (rejection.code == Web4AgentExactEffectErrors.SESSION_CLOSED) {
            AgentToolResultStatus.UNAVAILABLE
        } else {
            AgentToolResultStatus.FAILURE
        }
        return effectResult(
            intent = intent,
            status = status,
            summary = rejection.summary,
            dataJson = Web4AgentExactEffectErrors.json(rejection.code, rejection.summary),
            occurred = false
        )
    }

    private fun Web4AgentExpectedBinding.targetRef(sessionId: String): String = buildString {
        append("web:").append(sessionId)
        append(":epoch:").append(pageEpoch)
        append(":observation:").append(observationId.take(256))
        targetFingerprint?.let { fingerprint ->
            append(":target:").append(fingerprint)
        }
    }

    private fun Web4AgentPreparedEffect.targetRef(): String = buildString {
        append("web:").append(sessionId)
        append(":epoch:").append(pageEpoch)
        append(":observation:").append(observationId.take(256))
        append(":document:").append(documentFingerprint)
        targetFingerprint?.let { fingerprint ->
            append(":target:").append(fingerprint)
        }
    }

    private fun effectIntent(
        invocation: AgentToolInvocation,
        spec: AgentToolSpec,
        targetRef: String,
        summary: String
    ): AgentEffectIntent = AgentEffectIntent(
        runId = invocation.runId,
        sessionId = invocation.sessionId,
        toolCallId = invocation.callId,
        toolName = spec.name,
        capability = spec.capability,
        targetRef = targetRef,
        argumentHash = AgentEffectHasher.hash(spec.name, invocation.arguments),
        summary = summary
    )

    private fun executeGoverned(
        invocation: AgentToolInvocation,
        spec: AgentToolSpec,
        targetRef: String,
        summary: String,
        occurredOnFailure: Boolean,
        operation: () -> BackendResult
    ): AgentToolResult {
        val intent = effectIntent(invocation, spec, targetRef, summary)
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
            occurred = backend.occurred ?: (backend.ok || occurredOnFailure)
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
        val dataJson: String,
        val occurred: Boolean? = null
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
