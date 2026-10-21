package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontBufferQuietTest {
    @Test
    fun `alreadyPainting returns existingUntilMs unchanged`() {
        val result = FrontBufferQuiet.untilMs(
            alreadyPainting = true,
            nowUptimeMs = 1000L,
            quietMs = 3200L,
            existingUntilMs = 2000L,
        )
        assertEquals(2000L, result)
    }

    @Test
    fun `entering painting computes max of existing and now plus quiet`() {
        val result = FrontBufferQuiet.untilMs(
            alreadyPainting = false,
            nowUptimeMs = 1000L,
            quietMs = 3200L,
            existingUntilMs = 2000L,
        )
        assertEquals(4200L, result)
    }

    @Test
    fun `does not shorten if existing is later`() {
        val result = FrontBufferQuiet.untilMs(
            alreadyPainting = false,
            nowUptimeMs = 1000L,
            quietMs = 3200L,
            existingUntilMs = 5000L,
        )
        assertEquals(5000L, result)
    }
}
