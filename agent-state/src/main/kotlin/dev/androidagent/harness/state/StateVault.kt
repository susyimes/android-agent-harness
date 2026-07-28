// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.SystemAgentClock

class AgentStateConflictException(message: String) : IllegalStateException(message)

interface AgentStateView {
    fun document(collection: AgentStateCollection, id: String): AgentStateDocument?

    fun documents(collection: AgentStateCollection? = null): List<AgentStateDocument>

    fun events(): List<AgentStateEvent>

    fun evidence(id: String): AgentStateEvidence?

    fun evidence(): List<AgentStateEvidence>

    fun effects(): List<AgentStateEffect>

    fun briefs(): List<AgentBrief>

    fun psycheObservations(): List<PsycheObservation>

    fun candidate(id: String): AgentAssetCandidate?

    fun candidates(
        kind: AgentAssetKind? = null,
        statuses: Set<AgentCandidateStatus> = emptySet()
    ): List<AgentAssetCandidate>

    fun evalReport(id: String): AgentEvalReport?

    fun evalReports(candidateId: String? = null): List<AgentEvalReport>

    fun revision(id: String): AgentAssetRevision?

    fun revisions(assetKey: String? = null): List<AgentAssetRevision>

    fun activeRevision(assetKey: String): AgentAssetRevision? =
        revisions(assetKey).singleOrNull { revision ->
            revision.status == AgentAssetRevisionStatus.ACTIVE
        }

    fun snapshot(): AgentStateSnapshot = AgentStateSnapshot(
        documents = documents(),
        events = events(),
        evidence = evidence(),
        effects = effects(),
        briefs = briefs(),
        psycheObservations = psycheObservations(),
        candidates = candidates(),
        evalReports = evalReports(),
        revisions = revisions()
    )
}

interface AgentStateTransaction : AgentStateView {
    fun writeDocument(write: AgentStateDocumentWrite): AgentStateDocument

    fun appendEvent(event: AgentStateEvent)

    fun putEvidence(evidence: AgentStateEvidence)

    fun putEffect(effect: AgentStateEffect)

    fun putBrief(brief: AgentBrief)

    fun putPsycheObservation(observation: PsycheObservation)

    fun putCandidate(candidate: AgentAssetCandidate, expectedVersion: Long? = null)

    fun putEvalReport(report: AgentEvalReport)

    fun putRevision(revision: AgentAssetRevision)

    fun updateRevisionStatus(id: String, status: AgentAssetRevisionStatus)
}

interface AgentStateVault {
    fun <T> read(block: AgentStateView.() -> T): T

    fun <T> transaction(block: AgentStateTransaction.() -> T): T

    fun snapshot(): AgentStateSnapshot = read { snapshot() }
}

/**
 * Thread-safe reference store. Transactions use copy-on-write, so an exception
 * leaves the prior snapshot unchanged.
 */
class InMemoryAgentStateVault(
    private val clock: AgentClock = SystemAgentClock,
    initialSnapshot: AgentStateSnapshot? = null
) : AgentStateVault {
    private var state = initialSnapshot?.toVaultState() ?: VaultState()

    @Synchronized
    override fun <T> read(block: AgentStateView.() -> T): T {
        return block(StateView(state))
    }

    @Synchronized
    override fun <T> transaction(block: AgentStateTransaction.() -> T): T {
        val working = state.copyMutable()
        val transaction = TransactionView(working, clock)
        val result = block(transaction)
        state = working.freeze()
        return result
    }

    private data class VaultState(
        val documents: Map<String, AgentStateDocument> = emptyMap(),
        val events: Map<String, AgentStateEvent> = emptyMap(),
        val evidence: Map<String, AgentStateEvidence> = emptyMap(),
        val effects: Map<String, AgentStateEffect> = emptyMap(),
        val briefs: Map<String, AgentBrief> = emptyMap(),
        val psyche: Map<String, PsycheObservation> = emptyMap(),
        val candidates: Map<String, AgentAssetCandidate> = emptyMap(),
        val evalReports: Map<String, AgentEvalReport> = emptyMap(),
        val revisions: Map<String, AgentAssetRevision> = emptyMap()
    ) {
        fun copyMutable() = MutableVaultState(
            documents.toMutableMap(),
            events.toMutableMap(),
            evidence.toMutableMap(),
            effects.toMutableMap(),
            briefs.toMutableMap(),
            psyche.toMutableMap(),
            candidates.toMutableMap(),
            evalReports.toMutableMap(),
            revisions.toMutableMap()
        )
    }

    private fun AgentStateSnapshot.toVaultState(): VaultState {
        fun <T> unique(values: List<T>, id: (T) -> String, label: String): Map<String, T> {
            val result = values.associateBy(id)
            require(result.size == values.size) { "$label snapshot ids must be unique." }
            return result
        }
        val documentMap = documents.associateBy { document ->
            "${document.collection.name}:${document.id}"
        }
        require(documentMap.size == documents.size) { "Document snapshot ids must be unique." }
        val revisionMap = unique(revisions, AgentAssetRevision::id, "Revision")
        val activeKeys = revisions
            .filter { revision -> revision.status == AgentAssetRevisionStatus.ACTIVE }
            .groupingBy(AgentAssetRevision::assetKey)
            .eachCount()
        require(activeKeys.values.none { count -> count > 1 }) {
            "Snapshot cannot contain multiple active revisions for one asset."
        }
        return VaultState(
            documents = documentMap,
            events = unique(events, AgentStateEvent::id, "Event"),
            evidence = unique(evidence, AgentStateEvidence::id, "Evidence"),
            effects = unique(effects, AgentStateEffect::id, "Effect"),
            briefs = unique(briefs, AgentBrief::id, "Brief"),
            psyche = unique(psycheObservations, PsycheObservation::id, "Psyche"),
            candidates = unique(candidates, AgentAssetCandidate::id, "Candidate"),
            evalReports = unique(evalReports, AgentEvalReport::id, "Eval report"),
            revisions = revisionMap
        )
    }

    private data class MutableVaultState(
        val documents: MutableMap<String, AgentStateDocument>,
        val events: MutableMap<String, AgentStateEvent>,
        val evidence: MutableMap<String, AgentStateEvidence>,
        val effects: MutableMap<String, AgentStateEffect>,
        val briefs: MutableMap<String, AgentBrief>,
        val psyche: MutableMap<String, PsycheObservation>,
        val candidates: MutableMap<String, AgentAssetCandidate>,
        val evalReports: MutableMap<String, AgentEvalReport>,
        val revisions: MutableMap<String, AgentAssetRevision>
    ) {
        fun freeze() = VaultState(
            documents.toMap(),
            events.toMap(),
            evidence.toMap(),
            effects.toMap(),
            briefs.toMap(),
            psyche.toMap(),
            candidates.toMap(),
            evalReports.toMap(),
            revisions.toMap()
        )
    }

    private open class StateView(
        protected val data: VaultState
    ) : AgentStateView {
        constructor(data: MutableVaultState) : this(data.freeze())

        override fun document(
            collection: AgentStateCollection,
            id: String
        ): AgentStateDocument? = data.documents[documentKey(collection, id)]

        override fun documents(collection: AgentStateCollection?): List<AgentStateDocument> {
            return data.documents.values
                .filter { document -> collection == null || document.collection == collection }
                .sortedWith(
                    compareBy<AgentStateDocument> { document -> document.collection.ordinal }
                        .thenBy { document -> document.id }
                )
        }

        override fun events(): List<AgentStateEvent> =
            data.events.values.sortedWith(
                compareBy<AgentStateEvent> { event -> event.createdAtEpochMillis }
                    .thenBy { event -> event.id }
            )

        override fun evidence(id: String): AgentStateEvidence? = data.evidence[id]

        override fun evidence(): List<AgentStateEvidence> =
            data.evidence.values.sortedWith(
                compareBy<AgentStateEvidence> { evidence -> evidence.observedAtEpochMillis }
                    .thenBy { evidence -> evidence.id }
            )

        override fun effects(): List<AgentStateEffect> =
            data.effects.values.sortedWith(
                compareBy<AgentStateEffect> { effect -> effect.createdAtEpochMillis }
                    .thenBy { effect -> effect.id }
            )

        override fun briefs(): List<AgentBrief> =
            data.briefs.values.sortedWith(
                compareBy<AgentBrief> { brief -> brief.createdAtEpochMillis }
                    .thenBy { brief -> brief.id }
            )

        override fun psycheObservations(): List<PsycheObservation> =
            data.psyche.values.sortedWith(
                compareBy<PsycheObservation> { observation -> observation.observedAtEpochMillis }
                    .thenBy { observation -> observation.id }
            )

        override fun candidate(id: String): AgentAssetCandidate? = data.candidates[id]

        override fun candidates(
            kind: AgentAssetKind?,
            statuses: Set<AgentCandidateStatus>
        ): List<AgentAssetCandidate> {
            return data.candidates.values
                .filter { candidate -> kind == null || candidate.kind == kind }
                .filter { candidate -> statuses.isEmpty() || candidate.status in statuses }
                .sortedWith(
                    compareByDescending<AgentAssetCandidate> { candidate ->
                        candidate.createdAtEpochMillis
                    }.thenBy { candidate -> candidate.id }
                )
        }

        override fun evalReport(id: String): AgentEvalReport? = data.evalReports[id]

        override fun evalReports(candidateId: String?): List<AgentEvalReport> =
            data.evalReports.values
                .filter { report -> candidateId == null || report.candidateId == candidateId }
                .sortedWith(
                    compareBy<AgentEvalReport> { report -> report.createdAtEpochMillis }
                        .thenBy { report -> report.id }
                )

        override fun revision(id: String): AgentAssetRevision? = data.revisions[id]

        override fun revisions(assetKey: String?): List<AgentAssetRevision> =
            data.revisions.values
                .filter { revision -> assetKey == null || revision.assetKey == assetKey }
                .sortedWith(
                    compareBy<AgentAssetRevision> { revision -> revision.assetKey }
                        .thenBy { revision -> revision.revision }
                        .thenBy { revision -> revision.id }
                )

        protected companion object {
            fun documentKey(collection: AgentStateCollection, id: String): String =
                "${collection.name}:$id"
        }
    }

    private class TransactionView(
        private val mutable: MutableVaultState,
        private val clock: AgentClock
    ) : AgentStateTransaction {
        private fun view() = StateView(mutable)

        override fun document(
            collection: AgentStateCollection,
            id: String
        ): AgentStateDocument? = view().document(collection, id)

        override fun documents(collection: AgentStateCollection?) =
            view().documents(collection)

        override fun events() = view().events()

        override fun evidence(id: String) = view().evidence(id)

        override fun evidence() = view().evidence()

        override fun effects() = view().effects()

        override fun briefs() = view().briefs()

        override fun psycheObservations() = view().psycheObservations()

        override fun candidate(id: String) = view().candidate(id)

        override fun candidates(kind: AgentAssetKind?, statuses: Set<AgentCandidateStatus>) =
            view().candidates(kind, statuses)

        override fun evalReport(id: String) = view().evalReport(id)

        override fun evalReports(candidateId: String?) = view().evalReports(candidateId)

        override fun revision(id: String) = view().revision(id)

        override fun revisions(assetKey: String?) = view().revisions(assetKey)

        override fun writeDocument(write: AgentStateDocumentWrite): AgentStateDocument {
            val key = "${write.collection.name}:${write.id}"
            val current = mutable.documents[key]
            if (write.expectedRevision != null && (current?.revision ?: 0L) != write.expectedRevision) {
                throw AgentStateConflictException(
                    "Document '${write.id}' expected revision ${write.expectedRevision} but was " +
                        "${current?.revision ?: 0L}."
                )
            }
            val now = clock.nowEpochMillis()
            val document = AgentStateDocument(
                id = write.id,
                collection = write.collection,
                revision = (current?.revision ?: 0L) + 1L,
                title = write.title,
                content = write.content,
                source = write.source,
                privacy = write.privacy,
                evidenceRefs = write.evidenceRefs.distinct(),
                metadata = write.metadata.toSortedMap(),
                createdAtEpochMillis = current?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                tombstone = write.tombstone
            )
            mutable.documents[key] = document
            return document
        }

        override fun appendEvent(event: AgentStateEvent) {
            putUnique(mutable.events, event.id, event, "State event")
        }

        override fun putEvidence(evidence: AgentStateEvidence) {
            val current = mutable.evidence[evidence.id]
            if (current != null && current != evidence) {
                throw AgentStateConflictException("Evidence '${evidence.id}' is immutable.")
            }
            mutable.evidence[evidence.id] = evidence
        }

        override fun putEffect(effect: AgentStateEffect) {
            val current = mutable.effects[effect.id]
            if (current != null) {
                require(current.kind == effect.kind) { "Effect kind cannot change." }
                require(current.candidateId == effect.candidateId) {
                    "Effect candidate cannot change."
                }
                require(current.candidateHash == effect.candidateHash) {
                    "Effect candidate hash cannot change."
                }
            }
            mutable.effects[effect.id] = effect
        }

        override fun putBrief(brief: AgentBrief) {
            putUnique(mutable.briefs, brief.id, brief, "Agent brief")
        }

        override fun putPsycheObservation(observation: PsycheObservation) {
            putUnique(mutable.psyche, observation.id, observation, "Psyche observation")
        }

        override fun putCandidate(candidate: AgentAssetCandidate, expectedVersion: Long?) {
            val current = mutable.candidates[candidate.id]
            if (expectedVersion != null && current?.version != expectedVersion) {
                throw AgentStateConflictException(
                    "Candidate '${candidate.id}' expected version $expectedVersion but was " +
                        "${current?.version ?: 0L}."
                )
            }
            if (current != null) {
                require(current.candidateHash == candidate.candidateHash) {
                    "Editing candidate content requires a new candidate revision."
                }
                require(current.assetKey == candidate.assetKey && current.kind == candidate.kind) {
                    "Candidate identity cannot change."
                }
                require(candidate.version == current.version + 1L) {
                    "Candidate status update must increment its version once."
                }
            } else {
                require(candidate.version == 1L) { "New candidate version must be 1." }
            }
            mutable.candidates[candidate.id] = candidate
        }

        override fun putEvalReport(report: AgentEvalReport) {
            putUnique(mutable.evalReports, report.id, report, "Eval report")
        }

        override fun putRevision(revision: AgentAssetRevision) {
            require(mutable.revisions[revision.id] == null) {
                "Asset revision '${revision.id}' already exists."
            }
            require(
                mutable.revisions.values.none { existing ->
                    existing.assetKey == revision.assetKey &&
                        existing.revision == revision.revision
                }
            ) {
                "Asset '${revision.assetKey}' revision ${revision.revision} already exists."
            }
            if (revision.status == AgentAssetRevisionStatus.ACTIVE) {
                require(
                    mutable.revisions.values.none { existing ->
                        existing.assetKey == revision.assetKey &&
                            existing.status == AgentAssetRevisionStatus.ACTIVE
                    }
                ) { "Asset '${revision.assetKey}' already has an active revision." }
            }
            mutable.revisions[revision.id] = revision
        }

        override fun updateRevisionStatus(id: String, status: AgentAssetRevisionStatus) {
            val current = requireNotNull(mutable.revisions[id]) {
                "Unknown asset revision '$id'."
            }
            if (status == AgentAssetRevisionStatus.ACTIVE) {
                require(
                    mutable.revisions.values.none { existing ->
                        existing.id != id &&
                            existing.assetKey == current.assetKey &&
                            existing.status == AgentAssetRevisionStatus.ACTIVE
                    }
                ) { "Asset '${current.assetKey}' already has an active revision." }
            }
            mutable.revisions[id] = current.copy(status = status)
        }

        private fun <T> putUnique(
            target: MutableMap<String, T>,
            id: String,
            value: T,
            label: String
        ) {
            val current = target[id]
            if (current != null && current != value) {
                throw AgentStateConflictException("$label '$id' already exists.")
            }
            target[id] = value
        }
    }
}
