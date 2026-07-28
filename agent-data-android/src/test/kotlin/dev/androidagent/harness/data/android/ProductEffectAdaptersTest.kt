// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.approval.AgentApprovalPolicy
import dev.androidagent.harness.approval.AgentApprovalRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductEffectAdaptersTest {
    @Test
    fun `document overwrite binds observed hash and replacement before applying`() {
        val port = RecordingDocumentPort()
        val requests = mutableListOf<dev.androidagent.harness.approval.AgentApprovalRequest>()
        val service = GovernedDocumentService(
            port = port,
            approvals = approvingCoordinator(requests),
            clock = AgentClock { 10L },
            idGenerator = sequenceIds()
        )
        val document = document()
        val replacement = "updated".toByteArray()

        val result = service.overwrite(
            document = document,
            expectedContentHash = "observed-hash",
            content = replacement,
            runId = "run",
            sessionId = "session",
            toolCallId = "call"
        )

        assertFalse(result.isError)
        assertEquals(AgentToolResultStatus.SUCCESS, result.envelope?.status)
        assertTrue(requireNotNull(result.envelope?.effect).occurred)
        assertEquals("document:doc", requests.single().targetRef)
        assertEquals(listOf("document-hash:observed-hash"), requests.single().evidenceRefs)
        assertEquals("observed-hash", port.expectedHash)
        assertEquals(replacement.toList(), port.content?.toList())
    }

    @Test
    fun `denied document deletion never reaches Android port`() {
        val port = RecordingDocumentPort()
        val service = GovernedDocumentService(
            port = port,
            approvals = AgentApprovalCoordinator(
                gate = AgentApprovalGate { AgentApprovalDecision.DENIED },
                policy = AgentApprovalPolicy { AgentApprovalRequirement.REQUIRED },
                clock = AgentClock { 10L },
                idGenerator = sequenceIds()
            ),
            clock = AgentClock { 10L },
            idGenerator = sequenceIds()
        )

        val result = service.delete(
            document(),
            expectedContentHash = "observed-hash",
            runId = "run",
            sessionId = "session",
            toolCallId = "delete"
        )

        assertTrue(result.isError)
        assertEquals(AgentToolResultStatus.DENIED, result.envelope?.status)
        assertFalse(requireNotNull(result.envelope?.effect).occurred)
        assertEquals(0, port.deleteCount)
    }

    @Test
    fun `notification content is hash-bound and unavailable UI fails closed`() {
        var postCount = 0
        val requests = mutableListOf<dev.androidagent.harness.approval.AgentApprovalRequest>()
        val service = GovernedNotificationService(
            port = NotificationEffectPort {
                postCount += 1
                AndroidProductEffectResult.Applied("posted")
            },
            approvals = approvingCoordinator(requests),
            clock = AgentClock { 20L },
            idGenerator = sequenceIds()
        )
        val draft = AgentNotificationDraft(
            logicalId = "heartbeat-1",
            notificationId = 7,
            channelId = "agent-findings",
            title = "Agent finding",
            body = "A bounded proactive finding is ready."
        )

        val result = service.post(draft, "run", "session", "notify")

        assertFalse(result.isError)
        assertEquals(1, postCount)
        assertEquals("notification:heartbeat-1", requests.single().targetRef)
        assertNotNull(result.envelope?.effect?.argumentHash)

        val unavailable = GovernedNotificationService(
            port = NotificationEffectPort {
                error("Port must not be called when approval UI is unavailable.")
            },
            approvals = AgentApprovalCoordinator(
                gate = AgentApprovalGate {
                    AgentApprovalDecision.UNAVAILABLE
                },
                policy = AgentApprovalPolicy { AgentApprovalRequirement.REQUIRED },
                clock = AgentClock { 20L },
                idGenerator = sequenceIds()
            ),
            clock = AgentClock { 20L },
            idGenerator = sequenceIds()
        ).post(draft, "run-2", "session", "notify-2")

        assertTrue(unavailable.isError)
        assertEquals(AgentToolResultStatus.UNAVAILABLE, unavailable.envelope?.status)
        assertFalse(requireNotNull(unavailable.envelope?.effect).occurred)
    }

    private fun document() = SelectedDocument(
        id = "doc",
        uri = "content://example/doc",
        displayName = "notes.md",
        mediaType = "text/markdown"
    )

    private fun approvingCoordinator(
        requests: MutableList<dev.androidagent.harness.approval.AgentApprovalRequest>
    ) = AgentApprovalCoordinator(
        gate = AgentApprovalGate { request ->
            requests += request
            AgentApprovalDecision.APPROVED
        },
        policy = AgentApprovalPolicy { AgentApprovalRequirement.REQUIRED },
        clock = AgentClock { 10L },
        idGenerator = sequenceIds()
    )

    private fun sequenceIds(): AgentIdGenerator {
        var value = 0
        return AgentIdGenerator { prefix -> "$prefix-${++value}" }
    }

    private class RecordingDocumentPort : SelectedDocumentEffectPort {
        var expectedHash: String? = null
        var content: ByteArray? = null
        var deleteCount = 0

        override fun overwrite(
            document: SelectedDocument,
            expectedContentHash: String,
            content: ByteArray
        ): AndroidProductEffectResult {
            expectedHash = expectedContentHash
            this.content = content.copyOf()
            return AndroidProductEffectResult.Applied("overwritten")
        }

        override fun delete(
            document: SelectedDocument,
            expectedContentHash: String
        ): AndroidProductEffectResult {
            deleteCount += 1
            return AndroidProductEffectResult.Applied("deleted")
        }
    }
}
