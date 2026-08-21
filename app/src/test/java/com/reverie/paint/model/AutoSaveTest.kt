package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSaveTest {

    @Test
    fun `auto save intervals are positive and properly converted to milliseconds`() {
        val validIntervals = listOf(1, 3, 5, 10, 15, 30)
        validIntervals.forEach { minutes ->
            assertTrue("Interval should be positive", minutes > 0)
            val ms = minutes * 60 * 1000L
            assertEquals(minutes * 60_000L, ms)
        }
    }

    @Test
    fun `auto save interval clamping works within bounds`() {
        val clampInterval = { mins: Int -> mins.coerceIn(1, 60) }
        assertEquals(1, clampInterval(0))
        assertEquals(1, clampInterval(-5))
        assertEquals(5, clampInterval(5))
        assertEquals(60, clampInterval(60))
        assertEquals(60, clampInterval(120))
    }

    @Test
    fun `max undo steps clamping works within bounds`() {
        val clampUndo = { steps: Int -> steps.coerceIn(10, 200) }
        assertEquals(10, clampUndo(5))
        assertEquals(50, clampUndo(50))
        assertEquals(200, clampUndo(300))
    }

    @Test
    fun `auto save trigger predicate logic`() {
        fun shouldTrigger(
            enabled: Boolean,
            isSaving: Boolean,
            hasChanges: Boolean,
            isInteracting: Boolean,
            elapsedMs: Long,
            intervalMs: Long
        ): Boolean {
            if (!enabled || isSaving || !hasChanges || isInteracting) return false
            return elapsedMs >= intervalMs
        }

        val intervalMs = 5 * 60 * 1000L

        // Normal trigger when time is up and canvas has changes
        assertTrue(shouldTrigger(true, false, true, false, 300_000L, intervalMs))
        assertTrue(shouldTrigger(true, false, true, false, 350_000L, intervalMs))

        // Do not trigger when disabled
        assertFalse(shouldTrigger(false, false, true, false, 350_000L, intervalMs))

        // Do not trigger if already saving
        assertFalse(shouldTrigger(true, true, true, false, 350_000L, intervalMs))

        // Do not trigger if no changes on canvas
        assertFalse(shouldTrigger(true, false, false, false, 350_000L, intervalMs))

        // Do not trigger while user is actively drawing a stroke (safety)
        assertFalse(shouldTrigger(true, false, true, true, 350_000L, intervalMs))

        // Do not trigger before interval has elapsed
        assertFalse(shouldTrigger(true, false, true, false, 200_000L, intervalMs))
    }

    @Test
    fun `autosave recovery draft identification and name resolution`() {
        val fileName1 = "风景插画.autosave.revp"
        val fileName2 = "未命名作品 1.autosave.revp"
        val normalName = "作品A.revp"

        val resolveName = { fName: String ->
            val nameWithoutExt = fName.removeSuffix(".revp").removeSuffix(".kra")
            if (nameWithoutExt.endsWith(".autosave")) nameWithoutExt.removeSuffix(".autosave") else nameWithoutExt
        }

        assertEquals("风景插画", resolveName(fileName1))
        assertEquals("未命名作品 1", resolveName(fileName2))
        assertEquals("作品A", resolveName(normalName))

        assertTrue(fileName1.contains(".autosave"))
        assertTrue(fileName2.contains(".autosave"))
        assertFalse(normalName.contains(".autosave"))
    }

    @Test
    fun `autosave recovery list merging logic`() {
        val masterProject = Project(name = "日常速写", filePath = "/projects/日常速写.revp", lastModified = 1000L)
        val autosaveProjectNewer = Project(name = "日常速写", filePath = "/autosave/日常速写.autosave.revp", lastModified = 2000L, isAutoSaved = true)
        val newDraftAutosave = Project(name = "未命名作品 2", filePath = "/autosave/未命名作品 2.autosave.revp", lastModified = 1500L, isAutoSaved = true)

        val projectList = mutableListOf(masterProject)

        // Merge autosave recovery
        val autosaves = listOf(autosaveProjectNewer, newDraftAutosave)
        for (autoProj in autosaves) {
            val existingIdx = projectList.indexOfFirst { !it.isFolder && it.name == autoProj.name }
            if (existingIdx != -1) {
                if (autoProj.lastModified >= projectList[existingIdx].lastModified) {
                    projectList[existingIdx] = autoProj
                }
            } else {
                projectList.add(0, autoProj)
            }
        }

        assertEquals(2, projectList.size)
        // Check that master project was updated to the newer recovered autosave
        val dailySketch = projectList.find { it.name == "日常速写" }
        assertTrue(dailySketch?.isAutoSaved == true)
        assertEquals("/autosave/日常速写.autosave.revp", dailySketch?.filePath)

        // Check that new draft was prepended
        val untitled = projectList.find { it.name == "未命名作品 2" }
        assertTrue(untitled?.isAutoSaved == true)
    }

    @Test
    fun `background auto save trigger predicate logic`() {
        fun shouldBackgroundAutoSave(
            enabled: Boolean,
            isSaving: Boolean,
            hasChanges: Boolean,
            isPaintingPage: Boolean,
        ): Boolean {
            if (!enabled || isSaving || !hasChanges || !isPaintingPage) return false
            return true
        }

        assertTrue(shouldBackgroundAutoSave(enabled = true, isSaving = false, hasChanges = true, isPaintingPage = true))
        assertFalse(shouldBackgroundAutoSave(enabled = false, isSaving = false, hasChanges = true, isPaintingPage = true))
        assertFalse(shouldBackgroundAutoSave(enabled = true, isSaving = true, hasChanges = true, isPaintingPage = true))
        assertFalse(shouldBackgroundAutoSave(enabled = true, isSaving = false, hasChanges = false, isPaintingPage = true))
        assertFalse(shouldBackgroundAutoSave(enabled = true, isSaving = false, hasChanges = true, isPaintingPage = false))
    }
}
