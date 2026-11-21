/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistortionWaveMathTest {

    @Test
    fun `cubic bezier easing respects endpoints and is monotonically increasing`() {
        val curves = listOf(
            DistortionWaveEasing.FastOutVelvetStop,
            DistortionWaveEasing.SmoothInOut,
        )
        for (curve in curves) {
            assertEquals(0f, curve(0f), 1e-4f)
            assertEquals(1f, curve(1f), 1e-4f)
            assertEquals(0f, curve(-0.5f), 1e-4f)
            assertEquals(1f, curve(1.5f), 1e-4f)
            var prev = -1f
            for (i in 0..100) {
                val v = curve(i / 100f)
                assertTrue("curve must be non-decreasing at $i: $prev -> $v", v >= prev - 1e-5f)
                assertTrue("curve must stay finite", v.isFinite())
                prev = v
            }
        }
    }

    @Test
    fun `morandi palette derivation handles normal, gray and high-saturation accents in both themes`() {
        val samples = listOf(
            WaveRgb(0.53f, 0.62f, 0.82f), // Morandi misty blue
            WaveRgb(0.5f, 0.5f, 0.5f),    // pure gray
            WaveRgb(1.0f, 0.0f, 0.2f),    // vivid crimson
            WaveRgb(0.0f, 0.0f, 0.0f),    // black
            WaveRgb(1.0f, 1.0f, 1.0f),    // white
        )
        for (accent in samples) {
            for (isDark in listOf(true, false)) {
                val palette = DistortionWavePalette.fromMorandiAccent(accent, isDark)
                assertEquals(7, palette.colors.size)
                val packed = palette.packOklab()
                assertEquals(41, packed.size)
                assertEquals(7f, packed[40], 1e-5f)
                for (f in packed) {
                    assertTrue("packed OKLab value must be finite", f.isFinite())
                }
                // OKLab L channel should stay in [0, 1]
                for (k in 0 until 10) {
                    val l = packed[k * 4]
                    assertTrue("OKLab L out of range: $l", l in 0f..1.05f)
                }
            }
        }
    }

    @Test
    fun `gathering to sweeping handoff is C1 continuous at anticipation and after long hold`() {
        val style = DistortionWaveStyle.StudioNormal
        val size = WaveSize(412f, 915f)
        val card = WaveRect(24f, 180f, 196f, 380f)
        val focus = WaveOffset(110f, 270f)

        // Exact anticipation boundary
        val gatherEnd = WaveFront.gathering(style.anticipation, card, size, style, focus)
        val sweepStart = WaveFront.sweeping(0f, style.anticipation, card, size, style, focus)

        assertFalse(gatherEnd.launched)
        assertTrue(sweepStart.launched)
        assertEquals(gatherEnd.originX, sweepStart.originX, 1e-4f)
        assertEquals(gatherEnd.originY, sweepStart.originY, 1e-4f)
        assertEquals(gatherEnd.boxHalfX, sweepStart.boxHalfX, 1e-4f)
        assertEquals(gatherEnd.boxHalfY, sweepStart.boxHalfY, 1e-4f)
        assertEquals(gatherEnd.radius, sweepStart.radius, 1e-4f)
        assertEquals(gatherEnd.gravity, sweepStart.gravity, 1e-4f)
        assertEquals(gatherEnd.glow, sweepStart.glow, 1e-4f)
        assertEquals(gatherEnd.energy, sweepStart.energy, 1e-4f)
        assertEquals(gatherEnd.time, sweepStart.time, 1e-4f)

        // Long loading hold (e.g. 2.0s) still keeps shader clock continuous and card crisp
        val longHold = WaveFront.gathering(2.0f, card, size, style, focus)
        val sweepAfterHold = WaveFront.sweeping(0f, 2.0f, card, size, style, focus)
        assertEquals(0f, longHold.holdRelease, 1e-5f)
        assertEquals(0f, longHold.energy, 1e-5f)
        assertEquals(longHold.time, sweepAfterHold.time, 1e-4f)
        assertTrue(longHold.glow in 0.1f..0.6f)
    }

    @Test
    fun `sweeping clears both phone portrait and tablet landscape within studio cadence`() {
        val viewports = listOf(
            WaveSize(412f, 915f),   // phone portrait
            WaveSize(1280f, 800f),  // tablet landscape
        )
        for (viewport in viewports) {
            val card = WaveRect(24f, 140f, 200f, 340f)
            val normal = DistortionWaveStyle.StudioNormal
            val atStart = WaveFront.sweeping(0.05f, normal.anticipation, card, viewport, normal)
            assertFalse("should not have cleared at 50ms", atStart.hasCleared)

            val atClearTarget = WaveFront.sweeping(0.85f, normal.anticipation, card, viewport, normal)
            assertTrue("normal sweep should clear screen by 0.85s on $viewport", atClearTarget.hasCleared)
            assertEquals(0f, atClearTarget.boxHalfX, 1e-3f)
            assertEquals(0f, atClearTarget.boxHalfY, 1e-3f)

            val atEnd = WaveFront.sweeping(normal.postLaunchDuration, normal.anticipation, card, viewport, normal)
            assertTrue(atEnd.hasCleared)
            assertEquals(0f, atEnd.rim, 1e-4f)
            assertEquals(0f, atEnd.glow, 1e-4f)

            val fast = DistortionWaveStyle.StudioFast
            val fastClear = WaveFront.sweeping(0.45f, fast.anticipation, card, viewport, fast)
            assertTrue("fast sweep should clear screen by 0.45s on $viewport", fastClear.hasCleared)
        }
    }

    @Test
    fun `returning wave starts full-screen and collapses smoothly back into card contour`() {
        val style = DistortionWaveStyle.StudioNormal
        val size = WaveSize(412f, 915f)
        val card = WaveRect(210f, 200f, 388f, 400f)

        val start = WaveFront.returning(0f, card, size, style)
        val mid = WaveFront.returning(0.45f, card, size, style)
        val end = WaveFront.returning(1f, card, size, style)

        assertTrue(start.reversing)
        assertFalse(start.hasCleared)
        assertEquals(1f, start.outgoingCoverMul, 1e-4f)
        assertEquals(0f, start.energy, 1e-4f)

        assertTrue("radius must shrink monotonically", mid.radius < start.radius && end.radius < mid.radius)
        assertTrue("mid-return should have active lens energy and band glow", mid.energy > 0.2f && mid.glow > 0.2f)

        assertTrue(end.hasCleared)
        assertEquals(0f, end.outgoingCoverMul, 1e-4f)
        assertEquals(0f, end.glow, 1e-4f)
        assertEquals(0f, end.rim, 1e-4f)
        // Final boxHalf restores the card's actual half extents
        assertEquals(end.cardHalfX, end.boxHalfX, 1e-3f)
        assertEquals(end.cardHalfY, end.boxHalfY, 1e-3f)
    }

    @Test
    fun `degenerate viewport and null or zero source rect never produce NaN`() {
        val style = DistortionWaveStyle.StudioNormal
        val zeroViewport = WaveSize(0f, 0f)
        val zeroCard = WaveRect(10f, 10f, 10f, 10f)

        val f1 = WaveFront.gathering(0.1f, null, zeroViewport, style)
        val f2 = WaveFront.sweeping(0.3f, 0.2f, zeroCard, WaveSize(400f, 800f), style)
        val f3 = WaveFront.returning(0.5f, null, zeroViewport, style)
        for (f in listOf(f1, f2, f3)) {
            assertTrue(f.originX.isFinite() && f.originY.isFinite())
            assertTrue(f.radius.isFinite() && f.glow.isFinite() && f.energy.isFinite())
        }

        val lumps = FloatArray(WaveRim.COUNT * 3)
        WaveRim.computeLumps(lumps, time = 0.5f, size = zeroViewport, cornerRadius = 36f, pulse = 0.5f)
        for (v in lumps) assertTrue(v.isFinite())

        val pos = FloatArray(2)
        val particle = WaveParticle(0, style)
        for (f in listOf(f1, f2, f3)) {
            particle.positionInto(f, pos)
            val op = particle.opacity(f)
            assertTrue(pos[0].isFinite() && pos[1].isFinite())
            assertTrue(op in 0f..1f)
        }
    }
}
