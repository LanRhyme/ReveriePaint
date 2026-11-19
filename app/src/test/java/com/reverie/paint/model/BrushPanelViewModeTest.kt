/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushPanelViewModeTest {

    @Test
    fun `fromPrefs defaults to STROKE_ICON_NAME when no preference stored`() {
        assertEquals(BrushPanelViewMode.STROKE_ICON_NAME, BrushPanelViewMode.fromPrefs(null, false))
        assertEquals(BrushPanelViewMode.STROKE_ICON_NAME, BrushPanelViewMode.fromPrefs("", false))
    }

    @Test
    fun `fromPrefs migrates legacy grid flag when mode key is absent`() {
        assertEquals(BrushPanelViewMode.GRID, BrushPanelViewMode.fromPrefs(null, true))
        assertEquals(BrushPanelViewMode.GRID, BrushPanelViewMode.fromPrefs("unknown", true))
    }

    @Test
    fun `fromPrefs restores all stored modes accurately`() {
        for (mode in BrushPanelViewMode.entries) {
            assertEquals(mode, BrushPanelViewMode.fromPrefs(mode.prefValue, false))
            assertEquals(mode, BrushPanelViewMode.fromPrefs(mode.prefValue, true))
        }
    }

    @Test
    fun `mode capability flags reflect layout contracts`() {
        assertTrue(BrushPanelViewMode.STROKE_ICON_NAME.showsStrokePreview)
        assertTrue(BrushPanelViewMode.STROKE_ICON_NAME.showsPresetIcon)
        assertFalse(BrushPanelViewMode.STROKE_ICON_NAME.isGrid)

        assertTrue(BrushPanelViewMode.STROKE_NAME.showsStrokePreview)
        assertFalse(BrushPanelViewMode.STROKE_NAME.showsPresetIcon)
        assertFalse(BrushPanelViewMode.STROKE_NAME.isGrid)

        assertTrue(BrushPanelViewMode.TWO_COLUMN.showsStrokePreview)
        assertTrue(BrushPanelViewMode.TWO_COLUMN.showsPresetIcon)
        assertTrue(BrushPanelViewMode.TWO_COLUMN.isGrid)
        assertEquals(2, BrushPanelViewMode.TWO_COLUMN.gridColumns)

        assertFalse(BrushPanelViewMode.LIST.showsStrokePreview)
        assertTrue(BrushPanelViewMode.LIST.showsPresetIcon)
        assertFalse(BrushPanelViewMode.LIST.isGrid)

        assertFalse(BrushPanelViewMode.GRID.showsStrokePreview)
        assertTrue(BrushPanelViewMode.GRID.showsPresetIcon)
        assertTrue(BrushPanelViewMode.GRID.isGrid)
        assertEquals(3, BrushPanelViewMode.GRID.gridColumns)
    }
}
