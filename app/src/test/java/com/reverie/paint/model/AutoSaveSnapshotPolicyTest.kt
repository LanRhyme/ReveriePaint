/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSaveSnapshotPolicyTest {

    private fun createSnapshot(
        id: String,
        displayName: String,
        masterPath: String = "",
        timestamp: Long,
        strokeCount: Int,
        isEmergency: Boolean = false,
    ): AutoSaveSnapshot = AutoSaveSnapshot(
        id = id,
        fileName = "$id.revp",
        displayName = displayName,
        masterPath = masterPath,
        timestamp = timestamp,
        strokeCount = strokeCount,
        layerCount = 1,
        fileSize = 1024L,
        thumbPath = "",
        isEmergency = isEmergency,
    )

    @Test
    fun `project key resolves masterPath if present or displayName if blank`() {
        val s1 = createSnapshot("1", "未命名作品", masterPath = "/path/art.revp", timestamp = 1000L, strokeCount = 10)
        val s2 = createSnapshot("2", "未命名作品 2", masterPath = "", timestamp = 1000L, strokeCount = 10)

        assertEquals("/path/art.revp", AutoSaveSnapshotPolicy.projectKey(s1))
        assertEquals("未命名作品 2", AutoSaveSnapshotPolicy.projectKey(s2))
    }

    @Test
    fun `skip record throttles duplicate snapshots within 90 seconds`() {
        val existing = listOf(
            createSnapshot("1", "草稿", timestamp = 100_000L, strokeCount = 50),
        )

        // Same strokes, 30s elapsed -> skip
        assertTrue(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 50, newTimestamp = 130_000L, isEmergency = false))

        // Same strokes, 100s elapsed -> do not skip
        assertFalse(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 50, newTimestamp = 210_000L, isEmergency = false))

        // Strokes changed -> do not skip
        assertFalse(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 55, newTimestamp = 130_000L, isEmergency = false))

        // Emergency save -> never skip
        assertFalse(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 50, newTimestamp = 130_000L, isEmergency = true))
    }

    @Test
    fun `skip record does not skip when layer count changes within 90 seconds`() {
        val existing = listOf(
            AutoSaveSnapshot(
                id = "1",
                fileName = "1.revp",
                displayName = "多图层作品",
                masterPath = "",
                timestamp = 100_000L,
                strokeCount = 50,
                layerCount = 2,
                fileSize = 1024L,
                thumbPath = "",
                isEmergency = false,
            ),
        )

        // Same strokes and same layers within 90s -> skip
        assertTrue(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 50, newTimestamp = 130_000L, isEmergency = false, newLayerCount = 2))

        // Same strokes, but new layer added -> do not skip
        assertFalse(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 50, newTimestamp = 130_000L, isEmergency = false, newLayerCount = 3))

        // Same strokes, but a layer deleted -> do not skip
        assertFalse(AutoSaveSnapshotPolicy.shouldSkipRecord(existing, newStrokeCount = 50, newTimestamp = 130_000L, isEmergency = false, newLayerCount = 1))
    }

    @Test
    fun `skip record protects rich project from sudden blank canvas overwrite`() {
        val richHistory = listOf(
            createSnapshot("1", "大型作品", timestamp = 100_000L, strokeCount = 3000),
        )

        // Sudden 0-stroke save after 30s -> skipped to protect historical drawing
        assertTrue(AutoSaveSnapshotPolicy.shouldSkipRecord(richHistory, newStrokeCount = 0, newTimestamp = 130_000L, isEmergency = false))

        // But emergency save with 0 strokes is still recorded
        assertFalse(AutoSaveSnapshotPolicy.shouldSkipRecord(richHistory, newStrokeCount = 0, newTimestamp = 130_000L, isEmergency = true))
    }

    @Test
    fun `per-project isolation ensures other projects are never evicted by active project`() {
        // Project A has 3 snapshots
        val projectASnaps = (1..3).map { i ->
            createSnapshot("a$i", "作品A", timestamp = 1000L + i * 10, strokeCount = i * 100)
        }

        var currentList = projectASnaps

        // Project B records 10 snapshots sequentially
        for (i in 1..10) {
            val newB = createSnapshot("b$i", "作品B", timestamp = 2000L + i * 10, strokeCount = i * 20)
            val (retained, _) = AutoSaveSnapshotPolicy.prune(currentList, newB)
            currentList = retained
        }

        // Project A's all 3 snapshots MUST be completely intact!
        val retainedA = currentList.filter { it.displayName == "作品A" }
        assertEquals(3, retainedA.size)

        // Project B must be clamped to MAX_SNAPSHOTS_PER_PROJECT (5)
        val retainedB = currentList.filter { it.displayName == "作品B" }
        assertEquals(AutoSaveSnapshotPolicy.MAX_SNAPSHOTS_PER_PROJECT, retainedB.size)
    }

    @Test
    fun `per-project pruning prioritizes keeping emergency snapshots`() {
        val list = mutableListOf(
            createSnapshot("1", "作品A", timestamp = 1000L, strokeCount = 10, isEmergency = true),
            createSnapshot("2", "作品A", timestamp = 2000L, strokeCount = 20, isEmergency = false),
            createSnapshot("3", "作品A", timestamp = 3000L, strokeCount = 30, isEmergency = false),
            createSnapshot("4", "作品A", timestamp = 4000L, strokeCount = 40, isEmergency = false),
            createSnapshot("5", "作品A", timestamp = 5000L, strokeCount = 50, isEmergency = false),
        )

        val newSnapshot = createSnapshot("6", "作品A", timestamp = 6000L, strokeCount = 60, isEmergency = false)
        val (retained, evicted) = AutoSaveSnapshotPolicy.prune(list, newSnapshot)

        assertEquals(5, retained.size)
        assertEquals(1, evicted.size)
        // Evicted must be "2" (the oldest non-emergency), while "1" (emergency) must be preserved
        assertEquals("2", evicted.first().id)
        assertTrue(retained.any { it.id == "1" && it.isEmergency })
    }

    @Test
    fun `global capacity pruning never evicts sole snapshot of distinct project`() {
        // Create 29 snapshots across 10 projects
        // Project 1 has 5, Project 2 has 5, Project 3 has 5, Project 4 has 5, Project 5 has 5 (total 25)
        // Projects 6, 7, 8, 9, 10 each have 1 snapshot (total 5, grand total = 30)
        val list = mutableListOf<AutoSaveSnapshot>()
        for (p in 1..5) {
            for (s in 1..5) {
                list.add(createSnapshot("p${p}_s${s}", "工程$p", timestamp = (p * 100 + s).toLong(), strokeCount = s * 10))
            }
        }
        for (p in 6..10) {
            list.add(createSnapshot("p${p}_s1", "孤立工程$p", timestamp = (p * 100).toLong(), strokeCount = 5))
        }

        assertEquals(30, list.size)

        // Now add a 6th snapshot to Project 1
        val newSnapshot = createSnapshot("p1_s6", "工程1", timestamp = 9999L, strokeCount = 60)
        val (retained, evicted) = AutoSaveSnapshotPolicy.prune(list, newSnapshot)

        assertEquals(AutoSaveSnapshotPolicy.MAX_GLOBAL_SNAPSHOTS, retained.size)

        // All single-snapshot projects (6..10) MUST still exist!
        for (p in 6..10) {
            assertTrue("Isolated project $p must not be evicted", retained.any { it.displayName == "孤立工程$p" })
        }
    }
}
