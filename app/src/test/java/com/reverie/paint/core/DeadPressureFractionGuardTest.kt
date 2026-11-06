/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadPressureFractionGuardTest {

    @Test
    fun deadFunctionIsGone() {
        val root = File("src/main/java")
        assertTrue("run from the :app module dir, got ${root.absolutePath}", root.isDirectory)
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("computeStrokePressureFraction") }
            .map { it.path }.toList()
        assertTrue("dead function still present in: $hits", hits.isEmpty())
    }

    @Test
    fun liveJniPressureFractionIsKept() {
        val bridge = File("src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt")
        assertTrue(bridge.isFile)
        assertTrue(bridge.readText().contains("brushPressureFraction"))
    }
}
