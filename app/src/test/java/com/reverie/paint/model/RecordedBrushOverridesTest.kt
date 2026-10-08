/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedBrushOverridesTest {
    @Test fun `legacy contexts keep their original behavior`() {
        assertFalse(RecordedBrushOverrides.applyShape(7, 0))
        assertFalse(RecordedBrushOverrides.applySpacing(7, 0))
        assertTrue(RecordedBrushOverrides.applyShape(7, 1))
        assertTrue(RecordedBrushOverrides.applySpacing(7, 1))
    }

    @Test fun `pressure only customization preserves native spacing`() {
        val flags = RecordedBrushOverrides.encode(customized = true, spacingCustomized = false)
        assertEquals(3, flags)
        assertTrue(RecordedBrushOverrides.applyShape(7, flags))
        assertFalse(RecordedBrushOverrides.applySpacing(7, flags))
    }

    @Test fun `explicit spacing edit is replayed`() {
        val flags = RecordedBrushOverrides.encode(customized = true, spacingCustomized = true)
        assertEquals(1, flags)
        assertTrue(RecordedBrushOverrides.applySpacing(7, flags))
    }

    @Test fun `uncustomized preset never acquires an override`() {
        for (spacing in listOf(false, true)) {
            val flags = RecordedBrushOverrides.encode(false, spacing)
            assertEquals(0, flags)
            assertFalse(RecordedBrushOverrides.applyShape(7, flags))
        }
    }

    @Test fun `without a preset recorded shape and spacing remain authoritative`() {
        for (flags in listOf(0, 1, 3)) {
            assertTrue(RecordedBrushOverrides.applyShape(-1, flags))
            assertTrue(RecordedBrushOverrides.applySpacing(-1, flags))
        }
    }
}
