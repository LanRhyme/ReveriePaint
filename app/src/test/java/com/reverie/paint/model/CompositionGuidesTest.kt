/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionGuidesTest {
    private data class Line(
        val guide: CompositionGuide,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val dashed: Boolean,
    )

    private fun lines(
        enabled: Set<CompositionGuide>,
        horizon: Float = 0.5f,
        width: Float = 300f,
        height: Float = 200f,
    ): List<Line> = buildList {
        CompositionGuideGeometry.forEachLine(enabled, horizon, width, height) { guide, x1, y1, x2, y2, dashed ->
            add(Line(guide, x1, y1, x2, y2, dashed))
        }
    }

    @Test fun `codec round trips known guides and ignores unknown ids`() {
        val enabled = setOf(CompositionGuide.NINE_GRID, CompositionGuide.SAFE_FRAME)
        assertEquals(enabled, CompositionGuidesCodec.decode(CompositionGuidesCodec.encode(enabled)))
        assertEquals(emptySet<CompositionGuide>(), CompositionGuidesCodec.decode(null))
        assertEquals(emptySet<CompositionGuide>(), CompositionGuidesCodec.decode(""))
        assertEquals(setOf(CompositionGuide.CENTER), CompositionGuidesCodec.decode("center, unknown"))
    }

    @Test fun `nine grid and center use thirds and midpoint`() {
        val grid = lines(setOf(CompositionGuide.NINE_GRID))
        assertEquals(4, grid.size)
        assertEquals(listOf(100f, 200f), grid.filter { it.x1 == it.x2 }.map { it.x1 })
        assertEquals(listOf(200f / 3f, 400f / 3f), grid.filter { it.y1 == it.y2 }.map { it.y1 })

        val center = lines(setOf(CompositionGuide.CENTER))
        assertEquals(2, center.size)
        assertTrue(center.any { it.x1 == 150f && it.x2 == 150f })
        assertTrue(center.any { it.y1 == 100f && it.y2 == 100f })
    }

    @Test fun `golden ratio and diagonals expose expected geometry`() {
        val golden = lines(setOf(CompositionGuide.GOLDEN_RATIO))
        assertEquals(4, golden.size)
        assertTrue(golden.any { it.x1 == 300f * CompositionGuideGeometry.GOLDEN_LOW })
        assertTrue(golden.any { it.y1 == 200f * CompositionGuideGeometry.GOLDEN_HIGH })

        val diagonals = lines(setOf(CompositionGuide.DIAGONALS))
        assertEquals(2, diagonals.size)
        assertTrue(diagonals.any { it.x1 == 0f && it.y1 == 0f && it.x2 == 300f && it.y2 == 200f })
        assertTrue(diagonals.any { it.x1 == 300f && it.y1 == 0f && it.x2 == 0f && it.y2 == 200f })
    }

    @Test fun `safe frame draws solid outer and dashed inner rectangles`() {
        val safe = lines(setOf(CompositionGuide.SAFE_FRAME))
        assertEquals(8, safe.size)
        assertEquals(4, safe.count { !it.dashed })
        assertEquals(4, safe.count { it.dashed })
        assertTrue(safe.any { !it.dashed && it.x1 == 15f && it.y1 == 10f })
        assertTrue(safe.any { it.dashed && it.x1 == 30f && it.y1 == 20f })
    }

    @Test fun `horizon follows slider and is clamped`() {
        val mid = lines(setOf(CompositionGuide.HORIZON), horizon = 0.5f)
        assertEquals(1, mid.size)
        assertTrue(mid.single().dashed)
        assertEquals(100f, mid.single().y1, 0.001f)

        val top = lines(setOf(CompositionGuide.HORIZON), horizon = -1f)
        assertEquals(0f, top.single().y1, 0.001f)

        val bottom = lines(setOf(CompositionGuide.HORIZON), horizon = 2f)
        assertEquals(200f, bottom.single().y1, 0.001f)
    }

    @Test fun `zero-size canvases emit no lines`() {
        assertTrue(lines(setOf(CompositionGuide.NINE_GRID), width = 0f).isEmpty())
        assertTrue(lines(setOf(CompositionGuide.CENTER), height = 0f).isEmpty())
    }
}
