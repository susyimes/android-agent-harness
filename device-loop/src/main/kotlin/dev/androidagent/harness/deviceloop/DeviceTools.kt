// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolArgumentSchema
import dev.androidagent.harness.AgentToolArgumentType
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolEffectRecord
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.approval.AgentApprovalAwareTool
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision as EffectApprovalDecision
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent
import java.util.Locale

/**
 * Renders the current screen of any [DeviceSurface] as deterministic semantic text.
 *
 * One line per node: "[id] role label", plus "(text=...)" and "(view_id=...)"
 * when the surface reports them and a "[disabled]" marker for nodes that cannot
 * be acted on. The view id is worth the characters: it is the one label-like
 * value that survives localization, so it is what a model should quote in
 * expected_label-style re-targeting and what [RiskPolicy] can match reliably.
 *
 * Surface failures become structured failures rather than exceptions, so a
 * missing accessibility permission reads as PERMISSION_NOT_GRANTED instead of a
 * stack trace.
 */
class DeviceObserveTool(
    private val surface: DeviceSurface,
    private val protocol: StrictDeviceProtocol? = null
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "device_observe",
        description = "Returns a semantic text rendering of the current device screen."
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val screen = try {
            surface.snapshot()
        } catch (unsupported: UnsupportedOperationException) {
            return DeviceText.failure(
                DeviceErrorType.UNSUPPORTED_ACTION,
                "This device surface cannot be observed: ${unsupported.readableMessage()}"
            )
        } catch (structured: DeviceActionException) {
            return DeviceText.failure(structured.errorType, structured.readableMessage())
        } catch (failed: RuntimeException) {
            return DeviceText.failure(
                DeviceErrorType.ACTION_FAILED,
                "Could not observe the device: ${failed.readableMessage()}"
            )
        }
        val binding = try {
            protocol?.recordObservation(screen)
        } catch (error: DeviceProtocolException) {
            return DeviceText.protocolFailure(error)
        }
        val rendered = DeviceText.renderScreen(screen)
        return AgentToolResult.success(
            if (binding == null) rendered else "snapshot_id=${binding.snapshotId}\n$rendered"
        )
    }
}

/**
 * Executes exactly one device action.
 *
 * Actions: tap, set_text, back, home, swipe, scroll_to_text, launch_app,
 * wait_stable. Anything a surface does not implement comes back as an
 * UNSUPPORTED_ACTION failure naming the action instead of an exception, so one
 * tool serves a rich real device and a two-screen fake alike.
 *
 * GOVERNANCE. High-risk targets (per [RiskPolicy], evaluated WITH the current
 * screen so confirmation dialogs are covered) are never executed unless the
 * [ApprovalGate] approves; otherwise the tool returns the gate's decision
 * message and touches nothing.
 *
 * SECURITY: the gate is the approval boundary for dangerous actions. The
 * default [ArgumentApprovalGate] trusts the model-supplied confirmed=true
 * argument and is suitable ONLY for scripted demos and tests. In any real
 * deployment the gate must be backed by a human decision (a dialog), never by a
 * model-supplied argument, because a model could otherwise approve its own
 * dangerous action. See [ApprovalGate].
 *
 * HOME. [allowHome] defaults to false and the tool refuses "home" with an
 * instructive failure: pressing home leaves the app, and every id in the
 * agent's working memory dies with it. Enable it only for tasks that really
 * need the launcher.
 *
 * SETTLING. Every successful action is followed by a best-effort
 * [DeviceSurface.waitForStable] (an unsupported or failing wait is ignored), so
 * the reported screen id and the next observation describe a settled UI.
 *
 * ARGUMENTS. Only "action" is structurally required; each action states what
 * else it needs and reports missing or malformed values as INVALID_ARGUMENT.
 * Requiring "node" for every action would force meaningless placeholders on
 * back, home, swipe, launch_app and wait_stable.
 */
class DeviceActTool(
    private val surface: DeviceSurface,
    private val riskPolicy: RiskPolicy,
    private val approvalGate: ApprovalGate = ArgumentApprovalGate,
    private val allowHome: Boolean = false,
    private val stableTimeoutMs: Long = DEFAULT_STABLE_TIMEOUT_MS,
    private val protocol: StrictDeviceProtocol? = null,
    private val effectApprovals: AgentApprovalCoordinator? = null
) : AgentApprovalAwareTool {
    override val spec = AgentToolSpec(
        name = "device_act",
        description = buildString {
            append("Performs one device action (")
            append(if (allowHome) ACTION_LIST_WITH_HOME else ACTION_LIST_WITHOUT_HOME)
            append("). Required by action: tap=node; set_text=node,text; ")
            append("swipe=direction; scroll_to_text=text; launch_app=app (display name or package). ")
            append("expected_label guards tap/set_text against stale nodes; distance_px, duration_ms, ")
            append("max_scrolls, direction and timeout_ms are optional tuning arguments. ")
            if (!allowHome) {
                append("The home action is disabled; use back, launch_app, or in-app navigation. ")
            }
            append("High-risk targets pause for user confirmation.")
        },
        requiredArguments = setOf("action"),
        optionalArguments = setOf(
            "node",
            "expected_label",
            "text",
            "direction",
            "distance_px",
            "duration_ms",
            "max_scrolls",
            "app",
            "timeout_ms"
        ) + if (protocol == null) emptySet() else setOf("snapshot_id"),
        argumentSchemas = mapOf(
            "action" to AgentToolArgumentSchema(
                description = "Exactly one device action to perform.",
                enumValues = if (allowHome) {
                    listOf(
                        ACTION_TAP,
                        ACTION_SET_TEXT,
                        ACTION_BACK,
                        ACTION_HOME,
                        ACTION_SWIPE,
                        ACTION_SCROLL_TO_TEXT,
                        ACTION_LAUNCH_APP,
                        ACTION_WAIT_STABLE
                    )
                } else {
                    listOf(
                        ACTION_TAP,
                        ACTION_SET_TEXT,
                        ACTION_BACK,
                        ACTION_SWIPE,
                        ACTION_SCROLL_TO_TEXT,
                        ACTION_LAUNCH_APP,
                        ACTION_WAIT_STABLE
                    )
                }
            ),
            "direction" to AgentToolArgumentSchema(
                enumValues = DIRECTIONS.sorted()
            ),
            "distance_px" to AgentToolArgumentSchema(
                type = AgentToolArgumentType.INTEGER
            ),
            "duration_ms" to AgentToolArgumentSchema(
                type = AgentToolArgumentType.INTEGER
            ),
            "max_scrolls" to AgentToolArgumentSchema(
                type = AgentToolArgumentType.INTEGER
            ),
            "timeout_ms" to AgentToolArgumentSchema(
                type = AgentToolArgumentType.INTEGER
            )
        ) + if (protocol == null) {
            emptyMap()
        } else {
            mapOf(
                "snapshot_id" to AgentToolArgumentSchema(
                    description = "Exact snapshot_id returned by the latest device_observe."
                )
            )
        },
        capability = AgentToolCapability(
            sideEffect = AgentToolSideEffect.DEVICE_ACTION,
            risk = AgentToolRisk.CONTEXTUAL,
            dataScopes = setOf("android-device"),
            requiresForeground = true,
            idempotency = AgentToolIdempotency.UNKNOWN,
            supportsCancellation = true,
            targetArgumentNames = setOf("action", "node", "app", "expected_label")
        )
    )

    override fun bindApprovalCoordinator(
        approvals: AgentApprovalCoordinator
    ): AgentTool = DeviceActTool(
        surface = surface,
        riskPolicy = riskPolicy,
        approvalGate = approvalGate,
        allowHome = allowHome,
        stableTimeoutMs = stableTimeoutMs,
        protocol = protocol,
        effectApprovals = approvals
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val arguments = invocation.arguments
        val action = arguments.getValue("action").trim()
        if (protocol != null) {
            val currentScreen = try {
                surface.snapshot()
            } catch (structured: DeviceActionException) {
                return DeviceText.failure(structured.errorType, structured.readableMessage())
            } catch (failed: RuntimeException) {
                return DeviceText.failure(
                    DeviceErrorType.ACTION_FAILED,
                    "Could not validate the current device snapshot: ${failed.readableMessage()}"
                )
            }
            try {
                protocol.requireAction(arguments["snapshot_id"], currentScreen)
            } catch (error: DeviceProtocolException) {
                return DeviceText.protocolFailure(error)
            }
        }
        val result = try {
            when (action) {
                ACTION_TAP, ACTION_SET_TEXT -> nodeAction(invocation, action, arguments)
                ACTION_BACK -> backAction(invocation)
                ACTION_HOME -> homeAction(invocation)
                ACTION_SWIPE -> swipeAction(invocation, arguments)
                ACTION_SCROLL_TO_TEXT -> scrollAction(invocation, arguments)
                ACTION_LAUNCH_APP -> launchAction(invocation, arguments)
                ACTION_WAIT_STABLE -> waitAction(invocation, arguments)
                else -> DeviceText.failure(
                    DeviceErrorType.UNSUPPORTED_ACTION,
                    "Unknown action '$action'. Use one of: ${
                        if (allowHome) ACTION_LIST_WITH_HOME else ACTION_LIST_WITHOUT_HOME
                    }."
                )
            }
        } catch (unsupported: UnsupportedOperationException) {
            DeviceText.failure(
                DeviceErrorType.UNSUPPORTED_ACTION,
                "This device surface does not support '$action': ${unsupported.readableMessage()}"
            )
        } catch (structured: DeviceActionException) {
            DeviceText.failure(structured.errorType, structured.readableMessage())
        } catch (unknownTarget: IllegalArgumentException) {
            // DeviceSurface contract: IllegalArgumentException means unknown node id.
            DeviceText.targetNotFound(unknownTarget.readableMessage(), snapshotOrNull())
        } catch (failed: RuntimeException) {
            DeviceText.failure(
                DeviceErrorType.ACTION_FAILED,
                "Action '$action' failed: ${failed.readableMessage()}"
            )
        }
        if (protocol != null && !isApprovalPause(result)) {
            protocol.recordActionAttempt("device_act:$action")
        }
        return result
    }

    private fun isApprovalPause(result: AgentToolResult): Boolean {
        return (
            result.content.contains(DeviceErrorType.NEEDS_CONFIRMATION.name) ||
                result.content.contains("DENIED_BY_USER") ||
                result.content.contains("APPROVAL_TIMEOUT") ||
                result.content.contains("APPROVAL_DENIED") ||
                result.content.contains("APPROVAL_UNAVAILABLE") ||
                result.content.contains("APPROVAL_TOKEN_MISMATCH") ||
                result.content.contains("PAUSED_HIGH_RISK")
            )
    }

    /** tap and set_text: resolve the node, guard it, then act. */
    private fun nodeAction(
        invocation: AgentToolInvocation,
        action: String,
        arguments: Map<String, String>
    ): AgentToolResult {
        val nodeId = arguments["node"]
            ?: return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Action $action requires a 'node' argument naming an id from the latest device_observe."
            )
        val text = arguments["text"]
        if (action == ACTION_SET_TEXT && text == null) {
            return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Action $ACTION_SET_TEXT requires a 'text' argument."
            )
        }

        val screen = surface.snapshot()
        val node = screen.nodes.firstOrNull { candidate -> candidate.id == nodeId }
            ?: return DeviceText.targetNotFound(
                "Unknown node '$nodeId' on screen '${screen.id}'.",
                screen
            )

        val staleFailure = staleTargetFailure(arguments, node, screen)
        if (staleFailure != null) {
            return staleFailure
        }
        if (!node.enabled) {
            return DeviceText.failure(
                DeviceErrorType.ACTION_FAILED,
                "Node '$nodeId' ('${node.label}') is disabled on screen '${screen.id}'; " +
                    "acting on it would silently do nothing. Satisfy its precondition first."
            )
        }
        val risk = if (riskPolicy.isHighRisk(node, screen)) {
            AgentToolRisk.HIGH
        } else {
            AgentToolRisk.LOW
        }
        val authorization = authorizeAction(
            invocation = invocation,
            action = action,
            risk = risk,
            targetRef = "node:${node.id}:${node.label.take(MAX_TARGET_LABEL_CHARS)}",
            legacyNode = node,
            expectedScreen = screen
        )
        authorization.rejection?.let { rejection -> return rejection }

        when (action) {
            ACTION_TAP -> surface.tap(nodeId)
            else -> surface.setText(nodeId, requireNotNull(text))
        }
        settle()
        return recordEffect(
            authorization.intent,
            AgentToolResult.success(
                "OK: $action $nodeId -> screen=${surface.snapshot().id}"
            ),
            occurred = true
        )
    }

    private fun authorizeAction(
        invocation: AgentToolInvocation,
        action: String,
        risk: AgentToolRisk,
        targetRef: String,
        legacyNode: DeviceNode? = null,
        expectedScreen: DeviceScreen? = null
    ): DeviceAuthorization {
        val intent = AgentEffectIntent(
            runId = invocation.runId,
            sessionId = invocation.sessionId,
            toolCallId = invocation.callId,
            toolName = spec.name,
            capability = spec.capability.copy(risk = risk),
            targetRef = targetRef,
            argumentHash = AgentEffectHasher.hash(spec.name, invocation.arguments),
            summary = "Perform Android '$action' on $targetRef.",
            evidenceRefs = invocation.arguments["snapshot_id"]
                ?.takeIf(String::isNotBlank)
                ?.let { snapshotId -> listOf("device-snapshot:$snapshotId") }
                .orEmpty()
        )
        val approvals = effectApprovals
        if (approvals != null) {
            val waitFailure = enterApprovalWait(action)
            if (waitFailure != null) {
                return DeviceAuthorization(
                    intent,
                    recordEffect(intent, waitFailure, occurred = false)
                )
            }
            val authorization = try {
                approvals.authorize(intent)
            } finally {
                leaveApprovalWait()
            }
            when (authorization) {
                is AgentEffectAuthorization.Rejected -> {
                    val status = when (authorization.decision) {
                        EffectApprovalDecision.DENIED,
                        EffectApprovalDecision.TIMEOUT -> AgentToolResultStatus.DENIED
                        EffectApprovalDecision.UNAVAILABLE -> AgentToolResultStatus.UNAVAILABLE
                        EffectApprovalDecision.APPROVED ->
                            error("Approved authorization cannot be rejected.")
                    }
                    val summary =
                        "APPROVAL_${authorization.decision.name}: ${authorization.message}"
                    val rejection = AgentToolResult.failure(
                        summary,
                        AgentToolResultEnvelope(
                            status = status,
                            summary = summary,
                            createdAtEpochMillis = approvals.nowEpochMillis()
                        )
                    )
                    return DeviceAuthorization(
                        intent,
                        recordEffect(intent, rejection, occurred = false)
                    )
                }
                is AgentEffectAuthorization.Allowed -> {
                    val token = authorization.token
                    if (token != null && !approvals.consume(token, intent)) {
                        val summary =
                            "APPROVAL_TOKEN_MISMATCH: approval expired, changed, or was already consumed."
                        val rejection = AgentToolResult.failure(
                            summary,
                            AgentToolResultEnvelope(
                                status = AgentToolResultStatus.DENIED,
                                summary = summary,
                                createdAtEpochMillis = approvals.nowEpochMillis()
                            )
                        )
                        return DeviceAuthorization(
                            intent,
                            recordEffect(intent, rejection, occurred = false)
                        )
                    }
                }
            }
        } else if (risk == AgentToolRisk.HIGH && legacyNode != null) {
            val waitFailure = enterApprovalWait(action)
            if (waitFailure != null) {
                return DeviceAuthorization(
                    intent,
                    recordEffect(intent, waitFailure, occurred = false)
                )
            }
            val decision = try {
                approvalGate.decide(legacyNode, action, invocation.arguments)
            } finally {
                leaveApprovalWait()
            }
            if (decision != ApprovalDecision.APPROVED) {
                val pause = AgentToolResult.success(
                    approvalGate.pauseMessage(legacyNode, action, decision)
                )
                return DeviceAuthorization(
                    intent,
                    recordEffect(intent, pause, occurred = false)
                )
            }
        }

        val revalidation = revalidateAfterApproval(
            invocation.arguments["snapshot_id"],
            expectedScreen
        )
        return DeviceAuthorization(
            intent,
            revalidation?.let { failure ->
                recordEffect(intent, failure, occurred = false)
            }
        )
    }

    private fun enterApprovalWait(action: String): AgentToolResult? {
        val controller = protocol ?: return null
        return try {
            controller.recordWaitingApproval("device_act:$action")
            null
        } catch (error: DeviceProtocolException) {
            DeviceText.protocolFailure(error)
        } catch (error: IllegalStateException) {
            DeviceText.failure(
                DeviceErrorType.PROTOCOL_VIOLATION,
                error.message ?: "Phone Use could not enter approval state."
            )
        }
    }

    private fun leaveApprovalWait() {
        val controller = protocol ?: return
        if (controller.state() == DeviceProtocolState.WAITING_APPROVAL) {
            controller.resumeObservedAfterApprovalDecision()
        }
    }

    private fun revalidateAfterApproval(
        snapshotId: String?,
        expectedScreen: DeviceScreen?
    ): AgentToolResult? {
        if (expectedScreen == null && protocol == null) return null
        val fresh = try {
            surface.snapshot()
        } catch (structured: DeviceActionException) {
            return DeviceText.failure(structured.errorType, structured.readableMessage())
        } catch (failed: RuntimeException) {
            return DeviceText.failure(
                DeviceErrorType.ACTION_FAILED,
                "Could not revalidate the approved device target: ${failed.readableMessage()}"
            )
        }
        if (
            expectedScreen != null &&
            snapshotBinding(expectedScreen) != snapshotBinding(fresh)
        ) {
            return DeviceText.failure(
                DeviceErrorType.STALE_TARGET,
                "The device screen changed while approval was pending; observe again before acting."
            )
        }
        val controller = protocol ?: return null
        return try {
            controller.requireAction(snapshotId, fresh)
            null
        } catch (error: DeviceProtocolException) {
            DeviceText.protocolFailure(error)
        }
    }

    private fun recordEffect(
        intent: AgentEffectIntent,
        result: AgentToolResult,
        occurred: Boolean
    ): AgentToolResult {
        val now = effectApprovals?.nowEpochMillis() ?: System.currentTimeMillis()
        val envelope = AgentToolResultEnvelope.fromLegacy(result, now).copy(
            effect = AgentToolEffectRecord(
                effectId = "device-effect:${intent.runId}:${intent.toolCallId}",
                sideEffect = AgentToolSideEffect.DEVICE_ACTION,
                targetRef = intent.targetRef,
                argumentHash = intent.argumentHash,
                idempotencyKey = null,
                occurred = occurred
            )
        )
        return result.copy(envelope = envelope)
    }

    private data class DeviceAuthorization(
        val intent: AgentEffectIntent,
        val rejection: AgentToolResult?
    )

    /**
     * STALE-TARGET GUARD.
     *
     * Node ids are positional on a real device ("n7" is the seventh reported
     * node of the last snapshot), and the tool must re-snapshot before acting.
     * When the app moved on its own between the model's observation and this
     * call — a banner appeared, a list finished loading, a dialog opened — the
     * same id silently re-points at a DIFFERENT node, and the agent taps
     * something nobody reviewed. Optional expected_label makes that visible:
     * the resolved node must still carry the label the model believed it saw.
     *
     * The comparison is case-insensitive, whitespace-collapsed and tolerant of
     * display truncation in either direction, so a model quoting the rendered
     * "Transfer all funds to acc..." still matches the full label.
     */
    private fun staleTargetFailure(
        arguments: Map<String, String>,
        node: DeviceNode,
        screen: DeviceScreen
    ): AgentToolResult? {
        val expectedLabel = arguments["expected_label"] ?: return null
        if (expectedLabel.isBlank()) {
            return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Argument 'expected_label' must not be blank; omit it or quote the observed label."
            )
        }
        if (labelStillMatches(expectedLabel, node.label)) {
            return null
        }
        return DeviceText.failure(
            DeviceErrorType.STALE_TARGET,
            "Node '${node.id}' is now labelled '${node.label}' but the call expected " +
                "'${expectedLabel.trim()}' on screen '${screen.id}'. The screen changed after " +
                "your observation; call device_observe again and re-target before acting."
        )
    }

    private fun backAction(invocation: AgentToolInvocation): AgentToolResult {
        val authorization = authorizeAction(
            invocation,
            ACTION_BACK,
            AgentToolRisk.LOW,
            "device:back"
        )
        authorization.rejection?.let { rejection -> return rejection }
        surface.back()
        settle()
        return recordEffect(
            authorization.intent,
            AgentToolResult.success("OK: $ACTION_BACK -> screen=${surface.snapshot().id}"),
            occurred = true
        )
    }

    private fun homeAction(invocation: AgentToolInvocation): AgentToolResult {
        if (!allowHome) {
            return DeviceText.failure(
                DeviceErrorType.UNSUPPORTED_ACTION,
                "Action '$ACTION_HOME' is refused: leaving the app breaks the task chain and " +
                    "invalidates every node id you observed; use $ACTION_BACK or in-app " +
                "navigation instead. Do not retry '$ACTION_HOME'."
            )
        }
        val authorization = authorizeAction(
            invocation,
            ACTION_HOME,
            AgentToolRisk.HIGH,
            "device:home"
        )
        authorization.rejection?.let { rejection -> return rejection }
        surface.home()
        settle()
        return recordEffect(
            authorization.intent,
            AgentToolResult.success("OK: $ACTION_HOME -> screen=${surface.snapshot().id}"),
            occurred = true
        )
    }

    private fun swipeAction(
        invocation: AgentToolInvocation,
        arguments: Map<String, String>
    ): AgentToolResult {
        val direction = arguments["direction"]
            ?: return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Action $ACTION_SWIPE requires a 'direction' argument ($DIRECTION_LIST)."
            )
        val normalizedDirection = normalizeDirection(direction)
            ?: return invalidDirection(direction)
        val distancePx = positiveInt(arguments, "distance_px", DEFAULT_SWIPE_DISTANCE_PX)
            ?: return invalidNumber(arguments, "distance_px", "pixels")
        val durationMs = positiveInt(arguments, "duration_ms", DEFAULT_SWIPE_DURATION_MS)
            ?: return invalidNumber(arguments, "duration_ms", "milliseconds")

        val authorization = authorizeAction(
            invocation,
            ACTION_SWIPE,
            AgentToolRisk.LOW,
            "device:swipe:$normalizedDirection"
        )
        authorization.rejection?.let { rejection -> return rejection }
        surface.swipe(normalizedDirection, distancePx, durationMs)
        settle()
        return recordEffect(
            authorization.intent,
            AgentToolResult.success(
                "OK: $ACTION_SWIPE $normalizedDirection ${distancePx}px/${durationMs}ms " +
                    "-> screen=${surface.snapshot().id}"
            ),
            occurred = true
        )
    }

    private fun scrollAction(
        invocation: AgentToolInvocation,
        arguments: Map<String, String>
    ): AgentToolResult {
        val text = arguments["text"]
            ?: return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Action $ACTION_SCROLL_TO_TEXT requires a 'text' argument to look for."
            )
        if (text.isBlank()) {
            return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Argument 'text' must not be blank for $ACTION_SCROLL_TO_TEXT."
            )
        }
        val rawDirection = arguments["direction"] ?: DEFAULT_SCROLL_DIRECTION
        val direction = normalizeDirection(rawDirection) ?: return invalidDirection(rawDirection)
        val maxScrolls = positiveInt(arguments, "max_scrolls", DEFAULT_MAX_SCROLLS)
            ?: return invalidNumber(arguments, "max_scrolls", "scroll attempts")

        val authorization = authorizeAction(
            invocation,
            ACTION_SCROLL_TO_TEXT,
            AgentToolRisk.LOW,
            "device:scroll:$direction"
        )
        authorization.rejection?.let { rejection -> return rejection }
        val found = surface.scrollToText(text, direction, maxScrolls)
        settle()
        val screen = surface.snapshot()
        if (!found) {
            return recordEffect(
                authorization.intent,
                DeviceText.targetNotFound(
                    "Did not find '$text' after $maxScrolls $direction scroll attempts; " +
                        "screen '${screen.id}' does not contain it.",
                    screen
                ),
                occurred = true
            )
        }
        return recordEffect(
            authorization.intent,
            AgentToolResult.success(
                "OK: $ACTION_SCROLL_TO_TEXT '$text' $direction -> screen=${screen.id}"
            ),
            occurred = true
        )
    }

    private fun launchAction(
        invocation: AgentToolInvocation,
        arguments: Map<String, String>
    ): AgentToolResult {
        val app = arguments["app"]
            ?: return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Action $ACTION_LAUNCH_APP requires an 'app' argument (display name or package)."
            )
        if (app.isBlank()) {
            return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Argument 'app' must not be blank for $ACTION_LAUNCH_APP."
            )
        }
        val authorization = authorizeAction(
            invocation,
            ACTION_LAUNCH_APP,
            AgentToolRisk.LOW,
            "app:${app.trim().take(MAX_TARGET_LABEL_CHARS)}"
        )
        authorization.rejection?.let { rejection -> return rejection }
        val reached = surface.launchApp(app.trim())
        settle()
        val screen = surface.snapshot()
        // Launching by package is exact, so landing elsewhere means the app never
        // reached the foreground (disambiguation dialog, crash, permission screen).
        if (looksLikePackage(app) && !reached.equals(app.trim(), ignoreCase = true)) {
            return recordEffect(
                authorization.intent,
                DeviceText.failure(
                    DeviceErrorType.FOREGROUND_TIMEOUT,
                    "$ACTION_LAUNCH_APP '${app.trim()}' ended up in package '$reached' on screen " +
                        "'${screen.id}'. Observe the screen before assuming the app is open."
                ),
                occurred = true
            )
        }
        return recordEffect(
            authorization.intent,
            AgentToolResult.success(
                "OK: $ACTION_LAUNCH_APP ${app.trim()} -> package=$reached screen=${screen.id}"
            ),
            occurred = true
        )
    }

    private fun waitAction(
        invocation: AgentToolInvocation,
        arguments: Map<String, String>
    ): AgentToolResult {
        val timeoutMs = positiveLong(arguments, "timeout_ms", stableTimeoutMs)
            ?: return invalidNumber(arguments, "timeout_ms", "milliseconds")
        val authorization = authorizeAction(
            invocation,
            ACTION_WAIT_STABLE,
            AgentToolRisk.LOW,
            "device:wait-stable"
        )
        authorization.rejection?.let { rejection -> return rejection }
        val stable = surface.waitForStable(timeoutMs)
        val screen = surface.snapshot()
        if (!stable) {
            return recordEffect(
                authorization.intent,
                DeviceText.failure(
                    DeviceErrorType.WAIT_TIMEOUT,
                    "Screen '${screen.id}' was still changing after ${timeoutMs}ms. " +
                        "Observe again before acting on any node id."
                ),
                occurred = true
            )
        }
        return recordEffect(
            authorization.intent,
            AgentToolResult.success(
                "OK: $ACTION_WAIT_STABLE ${timeoutMs}ms -> screen=${screen.id}"
            ),
            occurred = true
        )
    }

    /** Best-effort settle after a successful action; never turns a success into a failure. */
    private fun settle() {
        try {
            surface.waitForStable(stableTimeoutMs)
        } catch (_: UnsupportedOperationException) {
            // Surface cannot wait; the caller's next observation will show the truth.
        } catch (_: RuntimeException) {
            // A failed settle must not mask the action that already went through.
        }
    }

    private fun snapshotOrNull(): DeviceScreen? {
        return try {
            surface.snapshot()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun invalidDirection(direction: String): AgentToolResult {
        return DeviceText.failure(
            DeviceErrorType.INVALID_ARGUMENT,
            "Unknown direction '$direction'. Use one of: $DIRECTION_LIST."
        )
    }

    private fun invalidNumber(
        arguments: Map<String, String>,
        name: String,
        unit: String
    ): AgentToolResult {
        return DeviceText.failure(
            DeviceErrorType.INVALID_ARGUMENT,
            "Argument '$name' must be a positive whole number of $unit; got '${arguments[name]}'."
        )
    }

    private fun positiveInt(arguments: Map<String, String>, name: String, fallback: Int): Int? {
        val raw = arguments[name] ?: return fallback
        val parsed = raw.trim().toIntOrNull() ?: return null
        return parsed.takeIf { value -> value > 0 }
    }

    private fun positiveLong(arguments: Map<String, String>, name: String, fallback: Long): Long? {
        val raw = arguments[name] ?: return fallback
        val parsed = raw.trim().toLongOrNull() ?: return null
        return parsed.takeIf { value -> value > 0L }
    }

    private fun normalizeDirection(direction: String): String? {
        val normalized = direction.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { value -> value in DIRECTIONS }
    }

    private fun looksLikePackage(app: String): Boolean {
        val trimmed = app.trim()
        return trimmed.contains('.') && trimmed.none { character -> character.isWhitespace() }
    }

    private companion object {
        const val ACTION_TAP = "tap"
        const val ACTION_SET_TEXT = "set_text"
        const val ACTION_BACK = "back"
        const val ACTION_HOME = "home"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_SCROLL_TO_TEXT = "scroll_to_text"
        const val ACTION_LAUNCH_APP = "launch_app"
        const val ACTION_WAIT_STABLE = "wait_stable"

        const val ACTION_LIST_WITH_HOME =
            "tap | set_text | back | home | swipe | scroll_to_text | launch_app | wait_stable"
        const val ACTION_LIST_WITHOUT_HOME = "tap | set_text | back | swipe | scroll_to_text | " +
            "launch_app | wait_stable"
        const val DIRECTION_LIST = "up | down | left | right"

        val DIRECTIONS = setOf("up", "down", "left", "right")

        const val DEFAULT_STABLE_TIMEOUT_MS = 2_000L
        const val DEFAULT_SWIPE_DISTANCE_PX = 600
        const val DEFAULT_SWIPE_DURATION_MS = 300
        const val DEFAULT_SCROLL_DIRECTION = "down"
        const val DEFAULT_MAX_SCROLLS = 8
        const val MAX_TARGET_LABEL_CHARS = 120
    }
}

/**
 * Declares the device task complete — with proof when a [surface] is supplied.
 *
 * A finish tool that only echoes a summary lets a model declare victory from
 * any screen, and models do exactly that when a flow gets hard. With a surface
 * the tool requires an "evidence" argument that must be visible in the CURRENT
 * observation (screen title, or any node label, text or view id), compared
 * case-insensitively and ignoring whitespace so quoting differences do not
 * matter. When the evidence is absent the tool FAILS and attaches the current
 * screen, which is exactly the information the model needs to keep working.
 *
 * The optional "expected_app" argument is checked against
 * [DeviceSurface.foregroundPackage] first: evidence read from the wrong app is
 * not evidence.
 *
 * The no-surface constructor keeps scripted demos and pure-protocol tests
 * simple; it accepts a summary and echoes it.
 */
class DeviceFinishTool(
    private val surface: DeviceSurface? = null,
    private val protocol: StrictDeviceProtocol? = null
) : AgentTool {
    override val spec = AgentToolSpec(
        name = "device_finish",
        description = if (surface == null) {
            "Marks the device task as finished with a summary of what was done."
        } else {
            "Marks the device task as finished; requires evidence visible on the current screen."
        },
        requiredArguments = if (surface == null) {
            setOf("summary")
        } else {
            setOf("summary", "evidence")
        },
        optionalArguments = if (surface == null) {
            emptySet()
        } else {
            setOf("expected_app") +
                if (protocol == null) emptySet() else setOf("snapshot_id")
        }
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val summary = invocation.arguments.getValue("summary")
        val device = surface ?: return AgentToolResult.success("FINISHED: $summary")

        val evidence = invocation.arguments["evidence"].orEmpty()
        if (evidence.isBlank()) {
            return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Argument 'evidence' must not be blank: quote text that is visible on the " +
                    "screen that proves the task is done."
            )
        }

        val screen = try {
            device.snapshot()
        } catch (unsupported: UnsupportedOperationException) {
            return DeviceText.failure(
                DeviceErrorType.UNSUPPORTED_ACTION,
                "This device surface cannot be observed, so completion cannot be proven: " +
                    unsupported.readableMessage()
            )
        } catch (structured: DeviceActionException) {
            return DeviceText.failure(structured.errorType, structured.readableMessage())
        } catch (failed: RuntimeException) {
            return DeviceText.failure(
                DeviceErrorType.ACTION_FAILED,
                "Could not observe the device to verify completion: ${failed.readableMessage()}"
            )
        }

        val appFailure = foregroundFailure(device, invocation.arguments, screen)
        if (appFailure != null) {
            return appFailure
        }
        val evidenceVisible = evidenceIsVisible(evidence, screen)
        if (protocol != null) {
            try {
                protocol.requireFinish(
                    snapshotId = invocation.arguments["snapshot_id"],
                    currentScreen = screen,
                    evidenceVisible = evidenceVisible
                )
            } catch (error: DeviceProtocolException) {
                return DeviceText.protocolFailure(error)
            }
        }
        if (!evidenceVisible) {
            return DeviceText.failure(
                DeviceErrorType.ACTION_FAILED,
                "Evidence '${evidence.trim()}' is not visible on screen '${screen.id}', so the " +
                    "task is not proven complete. Keep working, or finish with evidence that is " +
                    "actually on screen.",
                listOf("current screen:") + DeviceText.renderScreen(screen).lines()
            )
        }
        protocol?.recordFinished()
        return AgentToolResult.success(
            "FINISHED: $summary (evidence '${evidence.trim()}' verified on screen '${screen.id}')"
        )
    }

    private fun foregroundFailure(
        device: DeviceSurface,
        arguments: Map<String, String>,
        screen: DeviceScreen
    ): AgentToolResult? {
        val expectedApp = arguments["expected_app"]?.trim() ?: return null
        if (expectedApp.isEmpty()) {
            return DeviceText.failure(
                DeviceErrorType.INVALID_ARGUMENT,
                "Argument 'expected_app' must not be blank; omit it or name a package."
            )
        }
        val foreground = try {
            device.foregroundPackage()
        } catch (unsupported: UnsupportedOperationException) {
            return DeviceText.failure(
                DeviceErrorType.UNSUPPORTED_ACTION,
                "This device surface cannot report the foreground package, so 'expected_app' " +
                    "cannot be verified: ${unsupported.readableMessage()}"
            )
        }
        if (foreground != null && foreground.equals(expectedApp, ignoreCase = true)) {
            return null
        }
        return DeviceText.failure(
            DeviceErrorType.FOREGROUND_TIMEOUT,
            "Expected app '$expectedApp' is not in the foreground (foreground=" +
                "${foreground ?: "unknown"}) on screen '${screen.id}'. Evidence read from " +
                "another app does not prove this task."
        )
    }

    /** Whitespace- and case-insensitive containment over title, labels, texts and view ids. */
    private fun evidenceIsVisible(evidence: String, screen: DeviceScreen): Boolean {
        val needle = compact(evidence)
        if (needle.isEmpty()) {
            return false
        }
        val haystacks = buildList {
            add(screen.title)
            screen.nodes.forEach { node ->
                add(node.label)
                node.text?.let(::add)
                node.viewId?.let(::add)
            }
        }
        return haystacks.any { value -> compact(value).contains(needle) }
    }

    private fun compact(value: String): String {
        return value.filterNot { character -> character.isWhitespace() }.lowercase(Locale.ROOT)
    }
}

/**
 * Tool profile for the device-operation loop.
 *
 * Recommended runner configuration: maxToolCallsPerStep = 1, so each provider step is
 * observe, then ONE action, then observe again before deciding on the next action.
 * That keeps every state change attributable to a single reviewed step.
 */
object DeviceLoopProfile {
    fun profile(): AgentToolProfile = AgentToolProfile.only(
        id = "device-loop",
        toolNames = setOf("device_observe", "device_act", "device_finish")
    )
}

/**
 * Case-insensitive, whitespace-collapsed label comparison that tolerates the
 * display truncation real surfaces apply ("Send 1,200.00 to Ada Love...").
 *
 * Either side may be the truncated one, so a prefix match in either direction
 * counts. A three-character floor keeps a stub like "..." from matching
 * everything.
 */
internal fun labelStillMatches(expected: String, actual: String): Boolean {
    val expectedLabel = compactLabel(expected)
    val actualLabel = compactLabel(actual)
    if (expectedLabel.isEmpty() || actualLabel.isEmpty()) {
        return false
    }
    if (expectedLabel == actualLabel) {
        return true
    }
    val shorter = minOf(expectedLabel.length, actualLabel.length)
    if (shorter < MIN_LABEL_PREFIX) {
        return false
    }
    return expectedLabel.startsWith(actualLabel) || actualLabel.startsWith(expectedLabel)
}

private const val MIN_LABEL_PREFIX = 3

private val LABEL_WHITESPACE = Regex("\\s+")

private fun compactLabel(value: String): String {
    return value.lowercase(Locale.ROOT)
        .replace(LABEL_WHITESPACE, " ")
        .trim()
        .removeSuffix("...")
        .removeSuffix("…")
        .trim()
}

private fun Throwable.readableMessage(): String {
    return message?.takeIf { text -> text.isNotBlank() } ?: this::class.simpleName.orEmpty()
}
