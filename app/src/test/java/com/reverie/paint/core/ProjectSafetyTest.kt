/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSafetyTest {

    @Test
    fun `estimateProjectSaveBytes for existing artwork reserves double size plus safety margin`() {
        val existingSize = 25L * 1024 * 1024 // 25MB
        val estimated = estimateProjectSaveBytes(
            w = 2048,
            h = 2048,
            layerCount = 5,
            currentFileSize = existingSize,
        )
        // 25MB * 2 + 50MB buffer = 100MB
        val expected = existingSize * 2 + 50L * 1024 * 1024
        assertEquals(expected, estimated)
        assertTrue(estimated > existingSize)
    }

    @Test
    fun `estimateProjectSaveBytes for new artwork estimates based on raw resolution and layers`() {
        val w = 1920
        val h = 1080
        val layers = 3
        val estimated = estimateProjectSaveBytes(
            w = w,
            h = h,
            layerCount = layers,
            currentFileSize = 0L,
        )
        val rawBytes = w.toLong() * h * 4 * layers
        val expected = (rawBytes * 35 / 100) + 50L * 1024 * 1024
        assertEquals(expected, estimated)
        assertTrue(estimated > 50L * 1024 * 1024)
    }

    @Test
    fun `estimateProjectSaveBytes coerces layer count to at least 1`() {
        val estimatedWithZero = estimateProjectSaveBytes(1000, 1000, 0, 0L)
        val estimatedWithOne = estimateProjectSaveBytes(1000, 1000, 1, 0L)
        assertEquals(estimatedWithOne, estimatedWithZero)
    }
}
