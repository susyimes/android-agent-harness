// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.feedback

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.sdk.AgentRunTrigger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileFeedbackJournalsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun signalJournalSurvivesReopenAndPersistsClear() {
        val root = temporaryFolder.newFolder("signals")
        val clock = AgentClock { 10_000L }
        FileSignalJournal(root, clock = clock).append(signal("one", 9_000L))

        assertEquals(
            listOf("one"),
            FileSignalJournal(root, clock = clock).query().map(FeedbackSignal::id)
        )
        assertEquals(1, FileSignalJournal(root, clock = clock).clear())
        assertEquals(
            emptyList<FeedbackSignal>(),
            FileSignalJournal(root, clock = clock).query()
        )
    }

    @Test
    fun outcomeJournalRetainsOnlyNewestBoundedEntriesAcrossReopen() {
        val root = temporaryFolder.newFolder("outcomes")
        val clock = AgentClock { 10_000L }
        val journal = FileOutcomeJournal(
            root,
            maxEntries = 2,
            retentionMillis = 10_000L,
            clock = clock
        )

        journal.append(outcome("one", 7_000L))
        journal.append(outcome("two", 8_000L))
        journal.append(outcome("three", 9_000L))

        assertEquals(
            listOf("two", "three"),
            FileOutcomeJournal(
                root,
                maxEntries = 2,
                retentionMillis = 10_000L,
                clock = clock
            ).query().map(RunOutcomeRecord::id)
        )
    }

    private fun signal(id: String, createdAt: Long) = FeedbackSignal(
        id = id,
        type = FeedbackSignalType.ACTIVATION_EMITTED,
        source = FeedbackSignalSource.HOST,
        summary = "Activation emitted.",
        importance = 80,
        evidenceRefs = listOf("evidence:$id"),
        createdAtEpochMillis = createdAt
    )

    private fun outcome(id: String, createdAt: Long) = RunOutcomeRecord(
        id = id,
        runId = "run-$id",
        trigger = AgentRunTrigger.PROACTIVE,
        goalSummary = "Evaluate an opportunity.",
        resultSummary = "Completed.",
        status = OutcomeStatus.COMPLETED,
        createdAtEpochMillis = createdAt
    )
}
