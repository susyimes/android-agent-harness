// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.context.RuleBasedContextNeedAnalyzer
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoAdapterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun draftIsInertAndCommitRequiresApprovalAndPersistsEffect() {
        val root = temporaryFolder.newFolder("todo")
        val clock = FixedAgentClock(100L)
        val ids = SequentialAgentIdGenerator("todo")
        val repository = FileTodoRepository(root, clock, ids)
        val draft = repository.createDraft(
            title = "Review release",
            dueDate = "2026-07-28",
            source = "agent:run-1"
        )
        assertTrue(context(repository).body.contains("Open: 0"))

        val denied = repository.commitDraft(
            draft.id,
            "run-1",
            "session-1",
            approvals(AgentApprovalDecision.DENIED, clock, ids)
        )
        assertTrue(denied is TodoMutationResult.Rejected)
        assertEquals(TodoState.DRAFT, repository.read(draft.id)!!.state)

        val committed = repository.commitDraft(
            draft.id,
            "run-1",
            "session-1",
            approvals(AgentApprovalDecision.APPROVED, clock, ids)
        )
        assertTrue(committed is TodoMutationResult.Applied)
        assertEquals(1, repository.effects().size)
        assertTrue(context(repository).body.contains("Review release"))

        val reopened = FileTodoRepository(root, clock, ids)
        assertEquals(TodoState.COMMITTED, reopened.read(draft.id)!!.state)
        assertEquals(1, reopened.effects().size)
    }

    @Test
    fun optimisticRevisionPreventsStaleTodoUpdate() {
        val root = temporaryFolder.newFolder("todo")
        val clock = FixedAgentClock(100L)
        val ids = SequentialAgentIdGenerator("todo")
        val repository = FileTodoRepository(root, clock, ids)
        val draft = repository.createDraft("One", source = "user")
        val committed = repository.commitDraft(
            draft.id,
            "run",
            "session",
            approvals(AgentApprovalDecision.APPROVED, clock, ids)
        ) as TodoMutationResult.Applied

        val stale = repository.updateCommitted(
            id = draft.id,
            expectedRevision = draft.revision,
            title = "Changed",
            note = "",
            tags = emptySet(),
            dueDate = null,
            completed = false,
            runId = "run",
            sessionId = "session",
            approvals = approvals(AgentApprovalDecision.APPROVED, clock, ids)
        )

        assertTrue(stale is TodoMutationResult.Rejected)
        assertEquals(committed.item.title, repository.read(draft.id)!!.title)
    }

    @Test
    fun dataExportAndBulkDeleteRequireExactApproval() {
        val root = temporaryFolder.newFolder("todo-maintenance")
        val clock = FixedAgentClock(1_000L)
        val ids = SequentialAgentIdGenerator("todo")
        val repository = FileTodoRepository(root, clock, ids)
        repository.createDraft("Keep until approved deletion", source = "test")

        val denied = repository.deleteAll(
            "run-denied",
            "session",
            AgentApprovalCoordinator(
                gate = AgentApprovalGate { AgentApprovalDecision.DENIED },
                clock = clock,
                idGenerator = ids
            )
        )
        assertTrue(!denied.applied)
        assertEquals(1, repository.exportSnapshot().items.size)

        val deleted = repository.deleteAll(
            "run-approved",
            "session",
            AgentApprovalCoordinator(
                gate = AgentApprovalGate { AgentApprovalDecision.APPROVED },
                clock = clock,
                idGenerator = ids
            )
        )
        assertTrue(deleted.applied)
        assertEquals(1, deleted.deletedItems)
        assertTrue(repository.exportSnapshot().items.isEmpty())
        assertTrue(FileTodoRepository(root, clock, ids).exportSnapshot().items.isEmpty())
    }

    private fun approvals(
        decision: AgentApprovalDecision,
        clock: FixedAgentClock,
        ids: SequentialAgentIdGenerator
    ) = AgentApprovalCoordinator(
        gate = AgentApprovalGate { decision },
        clock = clock,
        idGenerator = ids
    )

    private fun context(repository: TodoRepository) =
        TodoContextSource(repository, today = { LocalDate.parse("2026-07-28") }).collect(
            request(),
            RuleBasedContextNeedAnalyzer().analyze(request())
        ).single()

    private fun request() = ContextEngineRequest(
        session = AgentSession("session", 1L, 1L),
        userInput = "what is due",
        taskType = ContextTaskType.CHAT,
        nowEpochMillis = 100L
    )
}
