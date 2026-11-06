/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeTiltPolicyTest {

    @Test
    fun zeroTiltPassesThroughAsZero() {
        assertEquals(0.0, StrokeTiltPolicy.component(0.0), 0.0)
        assertEquals(0f, StrokeTiltPolicy.component(0f), 0f)
    }

    @Test
    fun hardwareTiltPassesThroughUnchanged() {
        assertEquals(23.5, StrokeTiltPolicy.component(23.5), 0.0)
        assertEquals(-41.0, StrokeTiltPolicy.component(-41.0), 0.0)
        assertEquals(12.25f, StrokeTiltPolicy.component(12.25f), 0f)
    }

    @Test
    fun clampsToSixtyDegrees() {
        assertEquals(60.0, StrokeTiltPolicy.component(75.0), 0.0)
        assertEquals(-60.0, StrokeTiltPolicy.component(-90.0), 0.0)
        assertEquals(60f, StrokeTiltPolicy.component(61f), 0f)
    }

    @Test
    fun nonFiniteBecomesZero() {
        assertEquals(0.0, StrokeTiltPolicy.component(Double.NaN), 0.0)
        assertEquals(0.0, StrokeTiltPolicy.component(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0f, StrokeTiltPolicy.component(Float.NaN), 0f)
    }

    /** 守卫: 伪倾角状态机及其常量不得再出现在主源码里 (等价于 git grep 零命中) */
    @Test
    fun syntheticTiltIsRetiredFromMainSources() {
        val root = File("src/main/java")
        assertTrue("run from the :app module dir, got ${root.absolutePath}", root.isDirectory)
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                t.contains("SyntheticTiltState") || t.contains("SYNTH_TILT_") || t.contains("synthTilt")
            }
            .map { it.path }
            .toList()
        assertTrue("synthetic tilt still referenced in: $hits", hits.isEmpty())
    }
}
