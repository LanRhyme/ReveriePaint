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
    fun `pruning clamps to default max snapshots 8 when exceeding limit`() {
        val existing = (1..8).map { i ->
            createSnapshot("$i", "作品$i", timestamp = i * 1000L, strokeCount = i * 10)
        }

        val newSnapshot = createSnapshot("9", "作品9", timestamp = 9000L, strokeCount = 90)
        val (retained, evicted) = AutoSaveSnapshotPolicy.prune(existing, newSnapshot)

        assertEquals(AutoSaveSnapshotPolicy.DEFAULT_MAX_SNAPSHOTS, retained.size)
        assertEquals(8, retained.size)
        assertEquals(1, evicted.size)
        // Oldest snapshot (timestamp = 1000L) should be evicted
        assertEquals("1", evicted.first().id)
        // Newest snapshot should be at head
        assertEquals("9", retained.first().id)
    }

    @Test
    fun `pruning respects custom max snapshots limit`() {
        val existing = (1..5).map { i ->
            createSnapshot("$i", "作品$i", timestamp = i * 1000L, strokeCount = i * 10)
        }

        val newSnapshot = createSnapshot("6", "作品6", timestamp = 6000L, strokeCount = 60)
        // Custom limit = 5
        val (retained, evicted) = AutoSaveSnapshotPolicy.prune(existing, newSnapshot, maxSnapshots = 5)

        assertEquals(5, retained.size)
        assertEquals(1, evicted.size)
        assertEquals("1", evicted.first().id)
    }

    @Test
    fun `pruning prioritizes keeping emergency snapshots`() {
        val list = mutableListOf(
            createSnapshot("1", "作品A", timestamp = 1000L, strokeCount = 10, isEmergency = true),
            createSnapshot("2", "作品B", timestamp = 2000L, strokeCount = 20, isEmergency = false),
            createSnapshot("3", "作品C", timestamp = 3000L, strokeCount = 30, isEmergency = false),
            createSnapshot("4", "作品D", timestamp = 4000L, strokeCount = 40, isEmergency = false),
            createSnapshot("5", "作品E", timestamp = 5000L, strokeCount = 50, isEmergency = false),
            createSnapshot("6", "作品F", timestamp = 6000L, strokeCount = 60, isEmergency = false),
            createSnapshot("7", "作品G", timestamp = 7000L, strokeCount = 70, isEmergency = false),
            createSnapshot("8", "作品H", timestamp = 8000L, strokeCount = 80, isEmergency = false),
        )

        val newSnapshot = createSnapshot("9", "作品I", timestamp = 9000L, strokeCount = 90, isEmergency = false)
        val (retained, evicted) = AutoSaveSnapshotPolicy.prune(list, newSnapshot)

        assertEquals(8, retained.size)
        assertEquals(1, evicted.size)
        // Evicted must be "2" (the oldest non-emergency), while "1" (emergency) must be preserved
        assertEquals("2", evicted.first().id)
        assertTrue(retained.any { it.id == "1" && it.isEmergency })
    }
}
