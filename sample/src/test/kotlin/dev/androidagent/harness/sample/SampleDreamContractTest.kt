// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDreamContractTest {
    @Test
    fun `common no proposal responses do not create dream candidates`() {
        assertTrue("无建议".isNoDreamProposalText())
        assertTrue("可审查建议：无。".isNoDreamProposalText())
        assertTrue("本轮建议为暂无。".isNoDreamProposalText())
        assertTrue("No proposal.".isNoDreamProposalText())
    }

    @Test
    fun `an actual proposal remains eligible for review`() {
        assertFalse("建议把用户偏好写入候选记忆。".isNoDreamProposalText())
    }
}
