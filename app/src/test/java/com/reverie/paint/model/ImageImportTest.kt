/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.core.ImageImportHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageImportTest {

    @Test
    fun `computeSampleSize maintains dimension within limit`() {
        // Under limit: no downsampling
        assertEquals(1, ImageImportHelper.computeSampleSize(4000, 3000, 8192))
        assertEquals(1, ImageImportHelper.computeSampleSize(8192, 8192, 8192))

        // Exceeding limit: power of 2 downsampling
        assertEquals(2, ImageImportHelper.computeSampleSize(12000, 8000, 8192))
        assertEquals(2, ImageImportHelper.computeSampleSize(8193, 1000, 8192))
        assertEquals(4, ImageImportHelper.computeSampleSize(20000, 30000, 8192))
    }

    @Test
    fun `calculateFitPlacement keeps original size and centers if within maxRatio`() {
        val docW = 1000
        val docH = 1000
        // 80% boundary is 800 x 800
        val imgW = 600
        val imgH = 400

        val placement = ImageImportHelper.calculateFitPlacement(docW, docH, imgW, imgH, 0.8f)
        assertEquals(600, placement.targetW)
        assertEquals(400, placement.targetH)
        assertEquals(200, placement.x) // (1000 - 600) / 2
        assertEquals(300, placement.y) // (1000 - 400) / 2
    }

    @Test
    fun `calculateFitPlacement scales down proportionally and centers if oversized`() {
        val docW = 1000
        val docH = 1000
        // 80% boundary is 800 x 800
        val imgW = 1600
        val imgH = 800

        val placement = ImageImportHelper.calculateFitPlacement(docW, docH, imgW, imgH, 0.8f)
        // Scaled by 800 / 1600 = 0.5
        assertEquals(800, placement.targetW)
        assertEquals(400, placement.targetH)
        assertEquals(100, placement.x) // (1000 - 800) / 2
        assertEquals(300, placement.y) // (1000 - 400) / 2
    }

    @Test
    fun `calculateFitPlacement handles tall vertical image`() {
        val docW = 1000
        val docH = 1000
        // 80% boundary is 800 x 800
        val imgW = 500
        val imgH = 2000

        val placement = ImageImportHelper.calculateFitPlacement(docW, docH, imgW, imgH, 0.8f)
        // Scaled by 800 / 2000 = 0.4
        assertEquals(200, placement.targetW)
        assertEquals(800, placement.targetH)
        assertEquals(400, placement.x) // (1000 - 200) / 2
        assertEquals(100, placement.y) // (1000 - 800) / 2
    }
}
