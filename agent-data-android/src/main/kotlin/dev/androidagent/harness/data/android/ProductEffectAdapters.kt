// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.AgentPrivacyLabel
import dev.androidagent.harness.AgentRetryAdvice
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolEffectRecord
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.UuidAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Result returned by a narrow Android effect port.
 *
 * Ports never decide whether an effect is allowed. They run only after the
 * common approval layer has bound and consumed an exact token.
 */
sealed interface AndroidProductEffectResult {
    data class Applied(val summary: String) : AndroidProductEffectResult {
        init {
            require(summary.isNotBlank())
            require(summary.length <= MAX_REASON_CHARS)
        }
    }

    data class Unavailable(
        val reason: String,
        val retryable: Boolean = false
    ) : AndroidProductEffectResult {
        init {
            require(reason.isNotBlank())
            require(reason.length <= MAX_REASON_CHARS)
        }
    }

    companion object {
        const val MAX_REASON_CHARS = 2_048
    }
}

object DocumentContentHasher {
    fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * A host-owned, URI-scoped document effect boundary.
 *
 * Implementations must compare [expectedContentHash] immediately before the
 * effect. This prevents an approval for one observed document revision from
 * silently applying to a later revision.
 */
interface SelectedDocumentEffectPort {
    fun overwrite(
        document: SelectedDocument,
        expectedContentHash: String,
        content: ByteArray
    ): AndroidProductEffectResult

    fun delete(
        document: SelectedDocument,
        expectedContentHash: String
    ): AndroidProductEffectResult
}

/**
 * Storage Access Framework implementation. It requests no broad storage
 * permission; the host must first obtain and retain an exact URI grant.
 */
class AndroidSafDocumentEffectPort(
    context: Context,
    private val enabled: () -> Boolean = { false },
    private val maxBytes: Int = DEFAULT_MAX_BYTES
) : SelectedDocumentEffectPort {
    private val appContext = context.applicationContext

    init {
        require(maxBytes in 1..MAX_MAX_BYTES)
    }

    override fun overwrite(
        document: SelectedDocument,
        expectedContentHash: String,
        content: ByteArray
    ): AndroidProductEffectResult {
        if (!enabled()) return disabled()
        if (content.size > maxBytes) {
            return AndroidProductEffectResult.Unavailable(
                "Replacement document exceeds the configured size limit."
            )
        }
        return verifyCurrent(document, expectedContentHash) {
            val uri = Uri.parse(document.uri)
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(content)
                output.flush()
            } ?: error("Content resolver returned no writable document stream.")
            AndroidProductEffectResult.Applied("Selected document was overwritten.")
        }
    }

    override fun delete(
        document: SelectedDocument,
        expectedContentHash: String
    ): AndroidProductEffectResult {
        if (!enabled()) return disabled()
        return verifyCurrent(document, expectedContentHash) {
            val uri = Uri.parse(document.uri)
            val deleted = if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(appContext.contentResolver, uri)
            } else {
                appContext.contentResolver.delete(uri, null, null) > 0
            }
            if (deleted) {
                AndroidProductEffectResult.Applied("Selected document was deleted.")
            } else {
                AndroidProductEffectResult.Unavailable(
                    "Document provider did not delete the selected document."
                )
            }
        }
    }

    private fun verifyCurrent(
        document: SelectedDocument,
        expectedContentHash: String,
        effect: () -> AndroidProductEffectResult
    ): AndroidProductEffectResult {
        if (expectedContentHash.isBlank()) {
            return AndroidProductEffectResult.Unavailable(
                "An observed document content hash is required."
            )
        }
        return runCatching {
            val current = readBounded(Uri.parse(document.uri))
            if (DocumentContentHasher.sha256(current) != expectedContentHash) {
                AndroidProductEffectResult.Unavailable(
                    "Document changed after it was observed; approval is stale."
                )
            } else {
                effect()
            }
        }.getOrElse { error ->
            AndroidProductEffectResult.Unavailable(
                error.message?.take(AndroidProductEffectResult.MAX_REASON_CHARS)
                    ?: "Document effect is unavailable."
            )
        }
    }

    private fun readBounded(uri: Uri): ByteArray {
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) {
                    "Observed document exceeds the configured size limit."
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("Content resolver returned no readable document stream.")
    }

    private fun disabled() = AndroidProductEffectResult.Unavailable(
        "Document effects are disabled until the user grants an exact URI."
    )

    companion object {
        const val DEFAULT_MAX_BYTES = 512 * 1024
        const val MAX_MAX_BYTES = 16 * 1024 * 1024
    }
}

/**
 * Applies selected-document writes only after an exact common approval.
 */
class GovernedDocumentService(
    private val port: SelectedDocumentEffectPort,
    private val approvals: AgentApprovalCoordinator,
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator(),
    private val maxWriteBytes: Int = AndroidSafDocumentEffectPort.MAX_MAX_BYTES
) {
    init {
        require(maxWriteBytes in 1..AndroidSafDocumentEffectPort.MAX_MAX_BYTES)
    }

    fun overwrite(
        document: SelectedDocument,
        expectedContentHash: String,
        content: ByteArray,
        runId: String,
        sessionId: String,
        toolCallId: String
    ): AgentToolResult {
        require(expectedContentHash.isNotBlank())
        require(content.size <= maxWriteBytes) {
            "Replacement document exceeds $maxWriteBytes bytes."
        }
        val replacement = content.copyOf()
        val replacementHash = DocumentContentHasher.sha256(replacement)
        val intent = documentIntent(
            operation = "overwrite",
            document = document,
            expectedContentHash = expectedContentHash,
            runId = runId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            extraArguments = mapOf(
                "replacementHash" to replacementHash,
                "replacementBytes" to replacement.size.toString()
            ),
            summary = "Overwrite selected document '${document.displayName}'."
        )
        return authorizeAndExecute(intent) {
            port.overwrite(document, expectedContentHash, replacement)
        }
    }

    fun delete(
        document: SelectedDocument,
        expectedContentHash: String,
        runId: String,
        sessionId: String,
        toolCallId: String
    ): AgentToolResult {
        require(expectedContentHash.isNotBlank())
        val intent = documentIntent(
            operation = "delete",
            document = document,
            expectedContentHash = expectedContentHash,
            runId = runId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            extraArguments = emptyMap(),
            summary = "Delete selected document '${document.displayName}'."
        )
        return authorizeAndExecute(intent) {
            port.delete(document, expectedContentHash)
        }
    }

    private fun documentIntent(
        operation: String,
        document: SelectedDocument,
        expectedContentHash: String,
        runId: String,
        sessionId: String,
        toolCallId: String,
        extraArguments: Map<String, String>,
        summary: String
    ): AgentEffectIntent {
        val arguments = mapOf(
            "documentId" to document.id,
            "documentUri" to document.uri,
            "displayName" to document.displayName,
            "mediaType" to document.mediaType,
            "expectedContentHash" to expectedContentHash
        ) + extraArguments
        return AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            toolName = "document_$operation",
            capability = DOCUMENT_EFFECT_CAPABILITY,
            targetRef = "document:${document.id}",
            argumentHash = AgentEffectHasher.hash("document_$operation", arguments),
            summary = summary,
            evidenceRefs = listOf("document-hash:$expectedContentHash")
        )
    }

    private fun authorizeAndExecute(
        intent: AgentEffectIntent,
        effect: () -> AndroidProductEffectResult
    ): AgentToolResult {
        val authorization = approvals.authorize(intent)
        val token = (authorization as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            val rejection = authorization as? AgentEffectAuthorization.Rejected
            return effectResult(
                intent = intent,
                status = if (rejection?.decision == AgentApprovalDecision.UNAVAILABLE) {
                    AgentToolResultStatus.UNAVAILABLE
                } else {
                    AgentToolResultStatus.DENIED
                },
                summary = rejection?.message ?: "Exact document approval was not granted.",
                occurred = false
            )
        }
        if (!approvals.consume(token, intent)) {
            return effectResult(
                intent,
                AgentToolResultStatus.DENIED,
                "Document approval expired, changed, or was already consumed.",
                occurred = false
            )
        }
        val executed = runCatching(effect).getOrElse { error ->
            AndroidProductEffectResult.Unavailable(
                error.message?.take(AndroidProductEffectResult.MAX_REASON_CHARS)
                    ?: "Document effect failed."
            )
        }
        return when (executed) {
            is AndroidProductEffectResult.Applied -> effectResult(
                intent,
                AgentToolResultStatus.SUCCESS,
                executed.summary,
                occurred = true
            )
            is AndroidProductEffectResult.Unavailable -> effectResult(
                intent,
                AgentToolResultStatus.UNAVAILABLE,
                executed.reason,
                occurred = false,
                retryable = executed.retryable
            )
        }
    }

    private fun effectResult(
        intent: AgentEffectIntent,
        status: AgentToolResultStatus,
        summary: String,
        occurred: Boolean,
        retryable: Boolean = false
    ): AgentToolResult {
        val envelope = AgentToolResultEnvelope(
            status = status,
            summary = summary,
            effect = AgentToolEffectRecord(
                effectId = idGenerator.nextId("document-effect"),
                sideEffect = DOCUMENT_EFFECT_CAPABILITY.sideEffect,
                targetRef = intent.targetRef,
                argumentHash = intent.argumentHash,
                idempotencyKey = "${intent.runId}:${intent.toolCallId}",
                occurred = occurred
            ),
            retryAdvice = if (status == AgentToolResultStatus.SUCCESS) {
                null
            } else {
                AgentRetryAdvice(
                    retryable = retryable,
                    reason = if (retryable) {
                        "Re-observe the selected document before retrying."
                    } else {
                        "Do not retry without a new exact approval."
                    }
                )
            },
            privacy = AgentPrivacyLabel.SENSITIVE,
            createdAtEpochMillis = clock.nowEpochMillis()
        )
        return AgentToolResult(
            content = summary,
            isError = envelope.isError,
            envelope = envelope
        )
    }

    companion object {
        val DOCUMENT_EFFECT_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.EXTERNAL_WRITE,
            risk = AgentToolRisk.HIGH,
            dataScopes = setOf("selected-document-content"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
            targetArgumentNames = setOf("documentId", "documentUri")
        )
    }
}

data class AgentNotificationDraft(
    val logicalId: String,
    val notificationId: Int,
    val channelId: String,
    val tag: String? = null,
    val title: String,
    val body: String
) {
    init {
        require(logicalId.isNotBlank())
        require(channelId.isNotBlank())
        require(tag == null || tag.isNotBlank())
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(body.isNotBlank() && body.length <= MAX_BODY_CHARS)
    }

    companion object {
        const val MAX_TITLE_CHARS = 200
        const val MAX_BODY_CHARS = 4_000
    }
}

fun interface NotificationEffectPort {
    fun post(draft: AgentNotificationDraft): AndroidProductEffectResult
}

fun interface AndroidNotificationFactory {
    fun create(draft: AgentNotificationDraft): Notification
}

/**
 * Optional host-enabled notification effect. The host owns channel creation,
 * content styling, permission onboarding, and the notification factory.
 */
class AndroidNotificationEffectPort(
    context: Context,
    private val factory: AndroidNotificationFactory,
    private val enabled: () -> Boolean = { false }
) : NotificationEffectPort {
    private val appContext = context.applicationContext

    override fun post(draft: AgentNotificationDraft): AndroidProductEffectResult {
        if (!enabled()) {
            return AndroidProductEffectResult.Unavailable(
                "Notification effects are disabled by the user."
            )
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
            ?: return AndroidProductEffectResult.Unavailable(
                "Android NotificationManager is unavailable."
            )
        if (!manager.areNotificationsEnabled()) {
            return AndroidProductEffectResult.Unavailable(
                "Android notifications are disabled for this app."
            )
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AndroidProductEffectResult.Unavailable(
                "POST_NOTIFICATIONS has not been granted."
            )
        }
        return runCatching {
            manager.notify(draft.tag, draft.notificationId, factory.create(draft))
            AndroidProductEffectResult.Applied("Notification was posted.")
        }.getOrElse { error ->
            AndroidProductEffectResult.Unavailable(
                error.message?.take(AndroidProductEffectResult.MAX_REASON_CHARS)
                    ?: "Notification could not be posted."
            )
        }
    }
}

/**
 * Binds notification target and content hash to the shared approval protocol.
 */
class GovernedNotificationService(
    private val port: NotificationEffectPort,
    private val approvals: AgentApprovalCoordinator,
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator()
) {
    fun post(
        draft: AgentNotificationDraft,
        runId: String,
        sessionId: String,
        toolCallId: String
    ): AgentToolResult {
        val arguments = mapOf(
            "logicalId" to draft.logicalId,
            "notificationId" to draft.notificationId.toString(),
            "channelId" to draft.channelId,
            "tag" to (draft.tag ?: ""),
            "titleHash" to DocumentContentHasher.sha256(draft.title.toByteArray()),
            "bodyHash" to DocumentContentHasher.sha256(draft.body.toByteArray())
        )
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            toolName = "notification_post",
            capability = NOTIFICATION_EFFECT_CAPABILITY,
            targetRef = "notification:${draft.logicalId}",
            argumentHash = AgentEffectHasher.hash("notification_post", arguments),
            summary = "Post notification '${draft.title}'."
        )
        val authorization = approvals.authorize(intent)
        val token = (authorization as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            val rejection = authorization as? AgentEffectAuthorization.Rejected
            return result(
                intent,
                if (rejection?.decision == AgentApprovalDecision.UNAVAILABLE) {
                    AgentToolResultStatus.UNAVAILABLE
                } else {
                    AgentToolResultStatus.DENIED
                },
                rejection?.message ?: "Exact notification approval was not granted.",
                occurred = false
            )
        }
        if (!approvals.consume(token, intent)) {
            return result(
                intent,
                AgentToolResultStatus.DENIED,
                "Notification approval expired, changed, or was already consumed.",
                occurred = false
            )
        }
        return when (val applied = runCatching { port.post(draft) }.getOrElse { error ->
            AndroidProductEffectResult.Unavailable(
                error.message?.take(AndroidProductEffectResult.MAX_REASON_CHARS)
                    ?: "Notification effect failed."
            )
        }) {
            is AndroidProductEffectResult.Applied -> result(
                intent,
                AgentToolResultStatus.SUCCESS,
                applied.summary,
                occurred = true
            )
            is AndroidProductEffectResult.Unavailable -> result(
                intent,
                AgentToolResultStatus.UNAVAILABLE,
                applied.reason,
                occurred = false,
                retryable = applied.retryable
            )
        }
    }

    private fun result(
        intent: AgentEffectIntent,
        status: AgentToolResultStatus,
        summary: String,
        occurred: Boolean,
        retryable: Boolean = false
    ): AgentToolResult {
        val envelope = AgentToolResultEnvelope(
            status = status,
            summary = summary,
            effect = AgentToolEffectRecord(
                effectId = idGenerator.nextId("notification-effect"),
                sideEffect = NOTIFICATION_EFFECT_CAPABILITY.sideEffect,
                targetRef = intent.targetRef,
                argumentHash = intent.argumentHash,
                occurred = occurred
            ),
            retryAdvice = if (status == AgentToolResultStatus.SUCCESS) {
                null
            } else {
                AgentRetryAdvice(
                    retryable = retryable,
                    reason = if (retryable) {
                        "Retry only after the notification surface is available."
                    } else {
                        "Do not retry without a new exact approval."
                    }
                )
            },
            privacy = AgentPrivacyLabel.SENSITIVE,
            createdAtEpochMillis = clock.nowEpochMillis()
        )
        return AgentToolResult(summary, envelope.isError, envelope)
    }

    companion object {
        val NOTIFICATION_EFFECT_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.EXTERNAL_WRITE,
            risk = AgentToolRisk.MEDIUM,
            dataScopes = setOf("notification-content"),
            idempotency = AgentToolIdempotency.UNKNOWN,
            targetArgumentNames = setOf("logicalId", "notificationId")
        )
    }
}
