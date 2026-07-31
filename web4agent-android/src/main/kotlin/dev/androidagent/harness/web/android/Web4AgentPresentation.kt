// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Host-visible lifecycle of one visible Web4Agent presentation generation. */
enum class Web4AgentPresentationStatus {
    PREPARED,
    LAUNCHED,
    ATTACHED,
    CANCELLED,
    REJECTED,
    DETACHED
}

/** First acknowledgement of a presentation request. */
data class Web4AgentPresentationAcknowledgement(
    val presentationId: String,
    val sessionId: String,
    val generation: Long,
    val hostGeneration: String?,
    val status: Web4AgentPresentationStatus,
    val reasonCode: String?
) {
    init {
        require(
            status == Web4AgentPresentationStatus.ATTACHED ||
                status == Web4AgentPresentationStatus.CANCELLED ||
                status == Web4AgentPresentationStatus.REJECTED
        ) { "Presentation acknowledgement must be attached, cancelled, or rejected." }
    }
}

/** Completion of all visible work admitted through one presentation lease. */
data class Web4AgentPresentationQuiescence(
    val presentationId: String,
    val sessionId: String,
    val generation: Long,
    val hostGeneration: String?,
    val status: Web4AgentPresentationStatus,
    val reasonCode: String?
) {
    init {
        require(
            status == Web4AgentPresentationStatus.CANCELLED ||
                status == Web4AgentPresentationStatus.REJECTED ||
                status == Web4AgentPresentationStatus.DETACHED
        ) { "Presentation quiescence must be cancelled, rejected, or detached." }
    }
}

/**
 * One-shot capability for launching a visible BrowserActivity generation.
 *
 * [acknowledgement] completes when the Activity attaches, or when the request
 * is rejected/cancelled before attachment. [quiescence] completes only after
 * an attached Activity has detached, or immediately when attachment was never
 * admitted. Neither stage contains page or DOM content.
 */
class Web4AgentPresentationLease internal constructor(
    val presentationId: String,
    val sessionId: String,
    val generation: Long,
    val hostGeneration: String?,
    internal val ownerToken: Any
) {
    private val acknowledgementFuture =
        CompletableFuture<Web4AgentPresentationAcknowledgement>()
    private val quiescenceFuture = CompletableFuture<Web4AgentPresentationQuiescence>()

    @Volatile
    private var currentStatus = Web4AgentPresentationStatus.PREPARED

    @Volatile
    private var currentReasonCode: String? = null

    val status: Web4AgentPresentationStatus
        get() = currentStatus

    val reasonCode: String?
        get() = currentReasonCode

    val acknowledgement: CompletionStage<Web4AgentPresentationAcknowledgement>
        get() = acknowledgementFuture.thenApplyAsync { value -> value }

    val quiescence: CompletionStage<Web4AgentPresentationQuiescence>
        get() = quiescenceFuture.thenApplyAsync { value -> value }

    internal fun markLaunched(): Boolean {
        if (currentStatus != Web4AgentPresentationStatus.PREPARED) return false
        currentStatus = Web4AgentPresentationStatus.LAUNCHED
        return true
    }

    internal fun markAttached(): Boolean {
        if (
            currentStatus != Web4AgentPresentationStatus.PREPARED &&
            currentStatus != Web4AgentPresentationStatus.LAUNCHED
        ) {
            return false
        }
        currentStatus = Web4AgentPresentationStatus.ATTACHED
        acknowledgementFuture.complete(acknowledgement(Web4AgentPresentationStatus.ATTACHED, null))
        return true
    }

    internal fun markCancelled(reasonCode: String): Boolean {
        if (isQuiescent()) return false
        val attached = currentStatus == Web4AgentPresentationStatus.ATTACHED
        currentStatus = Web4AgentPresentationStatus.CANCELLED
        currentReasonCode = reasonCode
        acknowledgementFuture.complete(
            acknowledgement(Web4AgentPresentationStatus.CANCELLED, reasonCode)
        )
        if (!attached) {
            quiescenceFuture.complete(
                quiescence(Web4AgentPresentationStatus.CANCELLED, reasonCode)
            )
        }
        return true
    }

    internal fun markRejected(reasonCode: String): Boolean {
        if (isQuiescent()) return false
        currentStatus = Web4AgentPresentationStatus.REJECTED
        currentReasonCode = reasonCode
        acknowledgementFuture.complete(
            acknowledgement(Web4AgentPresentationStatus.REJECTED, reasonCode)
        )
        quiescenceFuture.complete(
            quiescence(Web4AgentPresentationStatus.REJECTED, reasonCode)
        )
        return true
    }

    internal fun markDetached(reasonCode: String): Boolean {
        if (quiescenceFuture.isDone) return false
        val terminalStatus = if (currentStatus == Web4AgentPresentationStatus.CANCELLED) {
            Web4AgentPresentationStatus.CANCELLED
        } else {
            currentStatus = Web4AgentPresentationStatus.DETACHED
            currentReasonCode = reasonCode
            Web4AgentPresentationStatus.DETACHED
        }
        quiescenceFuture.complete(quiescence(terminalStatus, currentReasonCode))
        return true
    }

    internal fun isQuiescent(): Boolean = quiescenceFuture.isDone

    private fun acknowledgement(
        status: Web4AgentPresentationStatus,
        reasonCode: String?
    ) = Web4AgentPresentationAcknowledgement(
        presentationId = presentationId,
        sessionId = sessionId,
        generation = generation,
        hostGeneration = hostGeneration,
        status = status,
        reasonCode = reasonCode
    )

    private fun quiescence(
        status: Web4AgentPresentationStatus,
        reasonCode: String?
    ) = Web4AgentPresentationQuiescence(
        presentationId = presentationId,
        sessionId = sessionId,
        generation = generation,
        hostGeneration = hostGeneration,
        status = status,
        reasonCode = reasonCode
    )
}

/** Aggregate outcome for a Stop/close request over visible presentations. */
data class Web4AgentPresentationStopOutcome(
    val sessionId: String,
    val requestedPresentationId: String?,
    val sessionClosed: Boolean,
    val presentations: List<Web4AgentPresentationQuiescence>
)

/** Non-blocking Stop result whose stage completes at presentation quiescence. */
class Web4AgentPresentationStopHandle internal constructor(
    val sessionId: String,
    val requestedPresentationId: String?,
    val hadWork: Boolean,
    val quiescence: CompletionStage<Web4AgentPresentationStopOutcome>
)

internal fun requireWeb4AgentPresentationReasonCode(reasonCode: String) {
    require(PRESENTATION_REASON_CODE.matches(reasonCode)) {
        "Web4Agent presentation reason must be a bounded opaque code."
    }
}

private val PRESENTATION_REASON_CODE = Regex("[a-z0-9][a-z0-9._-]{0,63}")
