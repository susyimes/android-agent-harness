// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Process-local WebView session and visible-presentation owner.
 *
 * Sessions are isolated by the Agent session id. Web content remains in memory
 * unless the host's normal WebView cookie/storage implementation persists it.
 */
class Web4AgentRuntime private constructor(
    context: Context,
    val configuration: Web4AgentConfiguration
) : Web4AgentSessionProvider, Web4AgentAcknowledgedPresenter {
    private val applicationContext = context.applicationContext
    private val lifecycleLock = Any()
    private val ownerToken = Any()
    private val sessions = ConcurrentHashMap<String, AndroidWeb4AgentSession>()
    private val presentations = linkedMapOf<String, PresentationState>()
    private val latestPresentationBySession = mutableMapOf<String, String>()
    private var nextPresentationGeneration = 0L

    @Volatile
    internal var presentationTestHooks: Web4AgentPresentationTestHooks =
        Web4AgentPresentationTestHooks.NONE

    override fun session(sessionId: String): Web4AgentSession {
        requireSessionId(sessionId)
        return controller(sessionId)
    }

    /**
     * Compatibility entry point. New strict hosts should retain the lease from
     * [preparePresentation], call [show] with it, and fence with
     * [closeAndAwaitQuiescence].
     */
    override fun show(sessionId: String) {
        requireSessionId(sessionId)
        val existing = attachedPresentation(sessionId)
        if (existing != null) {
            applicationContext.startActivity(
                Web4AgentBrowserActivity.intent(applicationContext, sessionId)
            )
        } else {
            show(preparePresentation(sessionId))
        }
    }

    override fun showAndAwait(
        sessionId: String,
        timeoutMillis: Long
    ): Web4AgentPresentationAcknowledgement {
        require(timeoutMillis in 100L..30_000L) {
            "Web4Agent presentation timeout must be from 100 to 30000 milliseconds."
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Web4Agent presentation acknowledgement cannot block Android main."
        }
        val existing = attachedPresentation(sessionId)
        val lease = if (existing != null) {
            applicationContext.startActivity(
                Web4AgentBrowserActivity.intent(applicationContext, sessionId)
            )
            existing
        } else {
            show(preparePresentation(sessionId))
        }
        val acknowledgement = try {
            lease.acknowledgement.toCompletableFuture()
                .get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cancelPresentation(lease, "attach.timeout")
            throw IllegalStateException("Web4Agent visible presentation timed out.")
        } catch (failure: InterruptedException) {
            cancelPresentation(lease, "attach.interrupted")
            Thread.currentThread().interrupt()
            throw IllegalStateException("Web4Agent visible presentation was interrupted.", failure)
        } catch (failure: ExecutionException) {
            cancelPresentation(lease, "attach.failed")
            throw IllegalStateException("Web4Agent visible presentation failed.", failure.cause)
        }
        check(acknowledgement.status == Web4AgentPresentationStatus.ATTACHED) {
            "Web4Agent visible presentation was ${acknowledgement.status.name.lowercase()}."
        }
        return acknowledgement
    }

    fun preparePresentation(sessionId: String): Web4AgentPresentationLease =
        preparePresentationInternal(sessionId, hostGeneration = null)

    /** Binds a host-owned opaque generation to the Harness-issued lease. */
    fun preparePresentation(
        sessionId: String,
        hostGeneration: String
    ): Web4AgentPresentationLease {
        require(hostGeneration.isNotBlank() && hostGeneration.length <= 256) {
            "Web4Agent host generation must contain 1 to 256 characters."
        }
        return preparePresentationInternal(sessionId, hostGeneration)
    }

    /** Launches exactly the supplied one-shot presentation generation. */
    fun show(lease: Web4AgentPresentationLease): Web4AgentPresentationLease {
        requireOwnedLease(lease)
        try {
            synchronized(lifecycleLock) {
                val state = presentations[lease.presentationId]
                check(state?.lease === lease && lease.markLaunched()) {
                    "Web4Agent presentation lease is stale or already used."
                }
                applicationContext.startActivity(
                    Web4AgentBrowserActivity.presentationIntent(applicationContext, lease)
                )
            }
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                presentations[lease.presentationId]
                    ?.takeIf { state -> state.lease === lease }
                    ?.let { state ->
                        lease.markRejected("launch.failed")
                        removeQuiescentPresentationLocked(state)
                    }
            }
            throw failure
        }
        return lease
    }

    fun activeSessionIds(): Set<String> = sessions.keys.toSet()

    fun activePresentationIds(): Set<String> = synchronized(lifecycleLock) {
        presentations.values
            .filterNot { state -> state.lease.isQuiescent() }
            .mapTo(linkedSetOf()) { state -> state.lease.presentationId }
    }

    /**
     * Cancels only this visible generation. It does not close the underlying
     * session, so a newer same-session generation cannot be affected.
     */
    fun cancelPresentation(
        lease: Web4AgentPresentationLease,
        reasonCode: String = "host.cancel"
    ): Web4AgentPresentationStopHandle {
        requireOwnedLease(lease)
        requireWeb4AgentPresentationReasonCode(reasonCode)
        return stopPresentations(
            sessionId = lease.sessionId,
            requestedLease = lease,
            reasonCode = reasonCode,
            closeSession = false,
            exactGeneration = true
        )
    }

    /**
     * Closes the session only when [lease] is still its latest generation.
     * A stale same-session ABA cleanup therefore cannot close a newer surface.
     */
    fun closeAndAwaitQuiescence(
        lease: Web4AgentPresentationLease,
        reasonCode: String = "host.close"
    ): Web4AgentPresentationStopHandle {
        requireOwnedLease(lease)
        requireWeb4AgentPresentationReasonCode(reasonCode)
        return stopPresentations(
            sessionId = lease.sessionId,
            requestedLease = lease,
            reasonCode = reasonCode,
            closeSession = true,
            exactGeneration = true
        )
    }

    /** Cancels every generation for [sessionId] and closes its WebView session. */
    fun closeAndAwaitQuiescence(
        sessionId: String,
        reasonCode: String = "host.close"
    ): Web4AgentPresentationStopHandle {
        requireSessionId(sessionId)
        requireWeb4AgentPresentationReasonCode(reasonCode)
        return stopPresentations(
            sessionId = sessionId,
            requestedLease = null,
            reasonCode = reasonCode,
            closeSession = true,
            exactGeneration = false
        )
    }

    /**
     * Compatibility close. It synchronously fences pending admission and
     * session effects, but strict hosts should await the returned handle from
     * [closeAndAwaitQuiescence] before publishing terminal UI state.
     */
    fun close(sessionId: String): Boolean =
        closeAndAwaitQuiescence(sessionId).hadWork

    fun closeAll(): Int {
        val sessionIds = synchronized(lifecycleLock) {
            (sessions.keys + presentations.values.map { state -> state.lease.sessionId })
                .toSet()
        }
        return sessionIds.count { sessionId -> close(sessionId) }
    }

    internal fun controller(sessionId: String): AndroidWeb4AgentSession {
        requireSessionId(sessionId)
        return synchronized(lifecycleLock) { controllerLocked(sessionId) }
    }

    /**
     * Runs Activity attachment under the same lock as cancellation. Either the
     * entire controller attach wins, or cancellation wins and no controller is
     * created/recreated for this presentation.
     */
    internal fun attachBrowserActivity(
        activity: Activity,
        sessionId: String,
        presentationId: String?,
        presentationGeneration: Long?,
        attach: (AndroidWeb4AgentSession, Web4AgentPresentationLease) -> Unit
    ): Web4AgentPresentationLease? {
        requireSessionId(sessionId)
        presentationTestHooks.beforeControllerAdmission(
            sessionId,
            presentationId,
            presentationGeneration
        )
        var rejectedId: String? = null
        var rejectedController: AndroidWeb4AgentSession? = null
        val displacedActivities = linkedSetOf<Activity>()
        val admitted = synchronized(lifecycleLock) {
            val state = if (presentationId == null) {
                legacyPresentationLocked(sessionId)
            } else {
                presentations[presentationId]?.takeIf { candidate ->
                    candidate.lease.sessionId == sessionId &&
                        candidate.lease.generation == presentationGeneration
                }
            }
            if (
                state == null ||
                (state.lease.status != Web4AgentPresentationStatus.PREPARED &&
                    state.lease.status != Web4AgentPresentationStatus.LAUNCHED)
            ) {
                rejectedId = presentationId
                null
            } else {
                try {
                    val controller = controllerLocked(sessionId)
                    presentations.values
                        .filter { previous ->
                            previous !== state &&
                                previous.lease.sessionId == sessionId &&
                                previous.activity?.get() != null
                        }
                        .toList()
                        .forEach { previous ->
                            previous.activity?.get()?.let { previousActivity ->
                                controller.detach(previousActivity)
                                previous.activity = null
                                previous.lease.markDetached("activity.replaced")
                                removeQuiescentPresentationLocked(previous)
                                if (previousActivity !== activity) {
                                    displacedActivities += previousActivity
                                }
                            }
                        }
                    attach(controller, state.lease)
                    state.activity = WeakReference(activity)
                    check(state.lease.markAttached()) {
                        "Web4Agent presentation was cancelled during Activity attachment."
                    }
                    state.lease
                } catch (failure: Throwable) {
                    state.lease.markRejected("attach.failed")
                    removeQuiescentPresentationLocked(state)
                    rejectedId = state.lease.presentationId
                    sessions.remove(sessionId)?.let { controller ->
                        rejectedController = controller
                    }
                    null
                }
            }
        }
        displacedActivities.forEach(::finishActivity)
        if (admitted == null) {
            rejectedController?.finish(keepSession = false)
            presentationTestHooks.afterPresentationRejected(
                sessionId,
                rejectedId,
                presentationGeneration
            )
        } else {
            presentationTestHooks.afterPresentationAttached(
                sessionId,
                admitted.presentationId,
                admitted.generation
            )
        }
        return admitted
    }

    internal fun detachBrowserActivity(
        activity: Activity,
        lease: Web4AgentPresentationLease,
        reasonCode: String = "activity.detached"
    ) {
        requireWeb4AgentPresentationReasonCode(reasonCode)
        synchronized(lifecycleLock) {
            val state = presentations[lease.presentationId] ?: return
            if (state.lease !== lease || state.activity?.get() !== activity) return
            state.activity = null
            lease.markDetached(reasonCode)
            removeQuiescentPresentationLocked(state)
        }
    }

    private fun preparePresentationInternal(
        sessionId: String,
        hostGeneration: String?
    ): Web4AgentPresentationLease {
        requireSessionId(sessionId)
        return synchronized(lifecycleLock) {
            val lease = Web4AgentPresentationLease(
                presentationId = "web-presentation-${UUID.randomUUID()}",
                sessionId = sessionId,
                generation = ++nextPresentationGeneration,
                hostGeneration = hostGeneration,
                ownerToken = ownerToken
            )
            presentations[lease.presentationId] = PresentationState(lease)
            latestPresentationBySession[sessionId] = lease.presentationId
            lease
        }
    }

    private fun attachedPresentation(sessionId: String): Web4AgentPresentationLease? =
        synchronized(lifecycleLock) {
            presentations.values
                .asSequence()
                .filter { state ->
                    state.lease.sessionId == sessionId &&
                        state.lease.status == Web4AgentPresentationStatus.ATTACHED &&
                        state.activity?.get()?.let { activity ->
                            !activity.isFinishing && !activity.isDestroyed
                        } == true
                }
                .maxByOrNull { state -> state.lease.generation }
                ?.lease
        }

    private fun legacyPresentationLocked(sessionId: String): PresentationState {
        val lease = Web4AgentPresentationLease(
            presentationId = "web-presentation-${UUID.randomUUID()}",
            sessionId = sessionId,
            generation = ++nextPresentationGeneration,
            hostGeneration = null,
            ownerToken = ownerToken
        )
        check(lease.markLaunched())
        return PresentationState(lease).also { state ->
            presentations[lease.presentationId] = state
            latestPresentationBySession[sessionId] = lease.presentationId
        }
    }

    private fun controllerLocked(sessionId: String): AndroidWeb4AgentSession {
        sessions[sessionId]?.let { current -> return current }
        presentationTestHooks.beforeControllerCreated(sessionId)
        lateinit var created: AndroidWeb4AgentSession
        created = AndroidWeb4AgentSession(
            applicationContext,
            sessionId,
            configuration,
            onClosed = { closedId -> sessions.remove(closedId, created) }
        )
        sessions[sessionId] = created
        return created
    }

    private fun stopPresentations(
        sessionId: String,
        requestedLease: Web4AgentPresentationLease?,
        reasonCode: String,
        closeSession: Boolean,
        exactGeneration: Boolean
    ): Web4AgentPresentationStopHandle {
        var session: AndroidWeb4AgentSession? = null
        val activities = linkedSetOf<Activity>()
        val affected: List<Web4AgentPresentationLease>
        val shouldCloseSession: Boolean
        synchronized(lifecycleLock) {
            val requestedIsLatest = requestedLease == null ||
                latestPresentationBySession[sessionId] == requestedLease.presentationId
            shouldCloseSession = closeSession && (!exactGeneration || requestedIsLatest)
            affected = if (shouldCloseSession || !exactGeneration) {
                presentations.values
                    .filter { state -> state.lease.sessionId == sessionId }
                    .map { state -> state.lease }
            } else {
                listOfNotNull(
                    requestedLease?.takeIf { lease ->
                        presentations[lease.presentationId]?.lease === lease
                    }
                )
            }
            affected.forEach { lease ->
                val state = presentations[lease.presentationId] ?: return@forEach
                lease.markCancelled(reasonCode)
                state.activity?.get()?.let(activities::add)
                removeQuiescentPresentationLocked(state)
            }
            if (shouldCloseSession) {
                session = sessions.remove(sessionId)
            }
        }

        session?.finish(keepSession = false)
        activities.forEach(::finishActivity)

        val stages = affected.map { lease -> lease.quiescence.toCompletableFuture() }
        val outcome = CompletableFuture<Web4AgentPresentationStopOutcome>()
        if (stages.isEmpty()) {
            outcome.complete(
                Web4AgentPresentationStopOutcome(
                    sessionId = sessionId,
                    requestedPresentationId = requestedLease?.presentationId,
                    sessionClosed = session != null,
                    presentations = emptyList()
                )
            )
        } else {
            CompletableFuture.allOf(*stages.toTypedArray()).whenComplete { _, failure ->
                if (failure != null) {
                    outcome.completeExceptionally(failure)
                } else {
                    outcome.complete(
                        Web4AgentPresentationStopOutcome(
                            sessionId = sessionId,
                            requestedPresentationId = requestedLease?.presentationId,
                            sessionClosed = session != null,
                            presentations = stages.map(CompletableFuture<Web4AgentPresentationQuiescence>::join)
                        )
                    )
                }
            }
        }
        return Web4AgentPresentationStopHandle(
            sessionId = sessionId,
            requestedPresentationId = requestedLease?.presentationId,
            hadWork = affected.isNotEmpty() || session != null,
            quiescence = outcome.thenApply { value -> value }
        )
    }

    private fun removeQuiescentPresentationLocked(state: PresentationState) {
        if (!state.lease.isQuiescent()) return
        presentations.remove(state.lease.presentationId, state)
        if (latestPresentationBySession[state.lease.sessionId] == state.lease.presentationId) {
            presentations.values
                .asSequence()
                .map { candidate -> candidate.lease }
                .filter { lease -> lease.sessionId == state.lease.sessionId }
                .maxByOrNull { lease -> lease.generation }
                ?.let { remaining ->
                    latestPresentationBySession[state.lease.sessionId] =
                        remaining.presentationId
                }
            // With no active predecessor, retain the removed id as a tombstone.
            // A delayed cleanup for an older generation must never become latest.
        }
    }

    private fun finishActivity(activity: Activity) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!activity.isFinishing) activity.finish()
        } else {
            Handler(Looper.getMainLooper()).post {
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    private fun requireOwnedLease(lease: Web4AgentPresentationLease) {
        require(lease.ownerToken === ownerToken) {
            "Web4Agent presentation lease belongs to a different runtime."
        }
    }

    private fun requireSessionId(sessionId: String) {
        require(sessionId.isNotBlank()) { "Web4Agent session id must not be blank." }
    }

    private class PresentationState(
        val lease: Web4AgentPresentationLease,
        var activity: WeakReference<Activity>? = null
    )

    companion object {
        @Volatile
        private var instance: Web4AgentRuntime? = null

        fun getInstance(context: Context): Web4AgentRuntime {
            return instance ?: synchronized(this) {
                instance ?: Web4AgentRuntime(
                    context,
                    Web4AgentConfiguration.secureDefault()
                ).also { created -> instance = created }
            }
        }

        /**
         * Installs a host policy before the first session is created.
         * Re-installing a different policy in the same process is rejected.
         */
        fun install(
            context: Context,
            configuration: Web4AgentConfiguration
        ): Web4AgentRuntime = synchronized(this) {
            instance?.let { current ->
                require(current.configuration == configuration) {
                    "Web4AgentRuntime is already installed with a different configuration."
                }
                return current
            }
            return Web4AgentRuntime(context, configuration)
                .also { created -> instance = created }
        }
    }
}

internal interface Web4AgentPresentationTestHooks {
    fun beforeControllerAdmission(
        sessionId: String,
        presentationId: String?,
        generation: Long?
    ) = Unit

    fun beforeControllerCreated(sessionId: String) = Unit

    fun afterPresentationAttached(
        sessionId: String,
        presentationId: String,
        generation: Long
    ) = Unit

    fun afterPresentationRejected(
        sessionId: String,
        presentationId: String?,
        generation: Long?
    ) = Unit

    companion object {
        val NONE: Web4AgentPresentationTestHooks = object : Web4AgentPresentationTestHooks {}
    }
}
