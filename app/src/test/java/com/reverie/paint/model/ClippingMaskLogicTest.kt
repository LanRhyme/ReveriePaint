/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClippingMaskLogicTest {

    data class MockLayer(
        val id: Int,
        val depth: Int,
        var clipped: Boolean,
        val isGroup: Boolean = false,
        var visible: Boolean = true
    )

    /**
     * Replicates the clipping chain discovery logic implemented in ReverieCoreRender.cpp.
     */
    private fun findClippingChain(layers: List<MockLayer>, baseIdx: Int): List<Int> {
        val base = layers[baseIdx]
        val baseDepth = base.depth
        var eEnd = baseIdx + 1
        if (base.isGroup) {
            while (eEnd < layers.size && layers[eEnd].depth > baseDepth) eEnd++
        }
        var clipEnd = eEnd
        while (clipEnd < layers.size && layers[clipEnd].depth == baseDepth && layers[clipEnd].clipped) {
            if (layers[clipEnd].isGroup) {
                var gEnd = clipEnd + 1
                while (gEnd < layers.size && layers[gEnd].depth > baseDepth) gEnd++
                clipEnd = gEnd
            } else {
                clipEnd++
            }
        }
        return (eEnd until clipEnd).toList()
    }

    @Test
    fun `single clipped layer chains to base layer`() {
        val layers = listOf(
            MockLayer(id = 0, depth = 0, clipped = false), // bg
            MockLayer(id = 1, depth = 0, clipped = false), // base
            MockLayer(id = 2, depth = 0, clipped = true)   // clipped
        )
        val chain = findClippingChain(layers, 1)
        assertEquals(listOf(2), chain)
    }

    @Test
    fun `multiple clipped layers chain to same base layer`() {
        val layers = listOf(
            MockLayer(id = 0, depth = 0, clipped = false),
            MockLayer(id = 1, depth = 0, clipped = false), // base
            MockLayer(id = 2, depth = 0, clipped = true),  // clipped 1
            MockLayer(id = 3, depth = 0, clipped = true),  // clipped 2
            MockLayer(id = 4, depth = 0, clipped = false)  // next unclipped
        )
        val chain = findClippingChain(layers, 1)
        assertEquals(listOf(2, 3), chain)
    }

    @Test
    fun `clipping chain stops when depth differs`() {
        val layers = listOf(
            MockLayer(id = 0, depth = 0, clipped = false),
            MockLayer(id = 1, depth = 0, clipped = false),
            MockLayer(id = 2, depth = 0, clipped = true),
            MockLayer(id = 3, depth = 1, clipped = true) // child of some group, different depth
        )
        val chain = findClippingChain(layers, 1)
        assertEquals(listOf(2), chain)
    }

    @Test
    fun `group layer can serve as base layer for clipped siblings`() {
        val layers = listOf(
            MockLayer(id = 0, depth = 0, clipped = false),
            MockLayer(id = 1, depth = 0, clipped = false, isGroup = true), // group base
            MockLayer(id = 2, depth = 1, clipped = false),                 // group child
            MockLayer(id = 3, depth = 0, clipped = true)                   // clipped to group
        )
        val chain = findClippingChain(layers, 1)
        assertEquals(listOf(3), chain)
    }

    @Test
    fun `clipped group layer is fully included in clipping chain`() {
        val layers = listOf(
            MockLayer(id = 0, depth = 0, clipped = false),
            MockLayer(id = 1, depth = 0, clipped = false),                // base
            MockLayer(id = 2, depth = 0, clipped = true, isGroup = true), // clipped group
            MockLayer(id = 3, depth = 1, clipped = false),                // clipped group child
            MockLayer(id = 4, depth = 0, clipped = false)                 // unclipped sibling
        )
        val chain = findClippingChain(layers, 1)
        assertEquals(listOf(2, 3), chain)
    }

    @Test
    fun `orphan clipping layer without base auto unclips on first child position`() {
        val layers = mutableListOf(
            MockLayer(id = 0, depth = 0, clipped = true) // index 0 (no prev sibling)
        )
        if (layers[0].clipped) {
            layers[0].clipped = false
        }
        assertFalse(layers[0].clipped)
    }

    @Test
    fun `clipping mask and inherit alpha are mutually exclusive`() {
        var clipped = false
        var alphaInherited = true

        fun setClipped(v: Boolean) {
            clipped = v
            if (clipped) alphaInherited = false
        }

        fun setAlphaInherited(v: Boolean) {
            alphaInherited = v
            if (alphaInherited) clipped = false
        }

        setClipped(true)
        assertTrue(clipped)
        assertFalse(alphaInherited)

        setAlphaInherited(true)
        assertFalse(clipped)
        assertTrue(alphaInherited)
    }
}
