package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealInkHoldPolicyTest {
    @Test
    fun `reject while held releases, accept does not`() {
        assertTrue(RealInkHoldPolicy.shouldReleaseOnReject(held = true, wantsHold = false))
        assertFalse(RealInkHoldPolicy.shouldReleaseOnReject(held = true, wantsHold = true))
        assertFalse(RealInkHoldPolicy.shouldReleaseOnReject(held = false, wantsHold = false))
    }

    @Test
    fun `epoch bumps only after the overlay accepts the frame`() {
        assertTrue(RealInkHoldPolicy.shouldBumpEpoch(shown = true))
        assertFalse(RealInkHoldPolicy.shouldBumpEpoch(shown = false))
    }
}
