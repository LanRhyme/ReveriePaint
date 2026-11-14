/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSpotlightTest {

    @Test
    fun `computeCardPosition handles narrow screen where margin exceeds remaining space without crashing`() {
        // 模拟 vivo V2359A 崩溃日志中的真实极端数值：
        // marginPx = 36.023563, screenWidth - cardWidthPx - marginPx = 35.19885
        val screenWidth = 1260f
        val marginPx = 36.023563f
        val cardWidthPx = screenWidth - marginPx - 35.19885f
        val cardHeightPx = 800f
        val screenHeight = 2800f

        for (step in 0..6) {
            val (x, y) = computeCardPosition(
                step = step,
                targetTop = 100f,
                targetBottom = 500f,
                targetLeft = 0f,
                targetRight = 200f,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                cardWidthPx = cardWidthPx,
                cardHeightPx = cardHeightPx,
                marginPx = marginPx,
                isCompact = true,
            )
            assertNotNull(x)
            assertNotNull(y)
            assertTrue("x should be at least marginPx", x >= marginPx)
            assertTrue("y should be at least marginPx", y >= marginPx)
        }
    }

    @Test
    fun `computeCardPosition handles ultra narrow screen where card is wider than screen`() {
        val screenWidth = 320f
        val marginPx = 20f
        val cardWidthPx = 350f
        val cardHeightPx = 400f
        val screenHeight = 640f

        val (x, y) = computeCardPosition(
            step = 0,
            targetTop = 50f,
            targetBottom = 200f,
            targetLeft = 0f,
            targetRight = 100f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            cardWidthPx = cardWidthPx,
            cardHeightPx = cardHeightPx,
            marginPx = marginPx,
            isCompact = true,
        )
        assertNotNull(x)
        assertNotNull(y)
        assertTrue(x >= marginPx)
        assertTrue(y >= marginPx)
    }

    @Test
    fun `computeCompactCardPosition strictly stays within screen bounds in phone portrait`() {
        val screenWidth = 390f
        val screenHeight = 844f
        val marginPx = 16f
        val cardWidthPx = 350f
        val cardHeightPx = 140f

        val stepTargets = listOf(
            // Step 0: ToolRail (top-left)
            listOf(100f, 400f, 0f, 60f),
            // Step 1: SliderRail (bottom-left)
            listOf(450f, 750f, 0f, 60f),
            // Step 2: View tools (top-left)
            listOf(120f, 320f, 50f, 250f),
            // Step 3: TopBar (top)
            listOf(0f, 60f, 0f, 390f),
            // Step 4: LayerPanel (top-right)
            listOf(60f, 400f, 150f, 390f),
            // Step 5: Gestures (center)
            listOf(350f, 500f, 120f, 270f),
            // Step 6: Workspace setup (top)
            listOf(0f, 60f, 0f, 390f),
        )

        for (step in 0..6) {
            val t = stepTargets[step]
            val (x, y) = computeCompactCardPosition(
                step = step,
                targetTop = t[0],
                targetBottom = t[1],
                targetLeft = t[2],
                targetRight = t[3],
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                cardWidthPx = cardWidthPx,
                cardHeightPx = cardHeightPx,
                marginPx = marginPx,
                isLandscape = false,
            )

            assertTrue("Step $step: x ($x) must be >= marginPx ($marginPx)", x >= marginPx - 0.01f)
            assertTrue("Step $step: x+w (${x + cardWidthPx}) must be <= screenWidth - margin (${screenWidth - marginPx})", x + cardWidthPx <= screenWidth - marginPx + 0.01f)
            assertTrue("Step $step: y ($y) must be >= marginPx ($marginPx)", y >= marginPx - 0.01f)
            assertTrue("Step $step: y+h (${y + cardHeightPx}) must be <= screenHeight - margin (${screenHeight - marginPx})", y + cardHeightPx <= screenHeight - marginPx + 0.01f)

            // Step 1 target is at bottom, so card should dock at top
            if (step == 1) {
                assertTrue("Step 1 card should be in upper half of screen", y < t[0])
            }
            // Step 3 target is at top, so card should dock at bottom
            if (step == 3) {
                assertTrue("Step 3 card should be in lower half of screen", y > t[1])
            }
        }
    }

    @Test
    fun `computeCompactCardPosition strictly stays within screen bounds in phone landscape`() {
        val screenWidth = 844f
        val screenHeight = 390f
        val marginPx = 16f
        val cardWidthPx = 360f
        val cardHeightPx = 125f

        // Left target (ToolRail)
        val (xLeft, yLeft) = computeCompactCardPosition(
            step = 0,
            targetTop = 50f,
            targetBottom = 300f,
            targetLeft = 0f,
            targetRight = 60f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            cardWidthPx = cardWidthPx,
            cardHeightPx = cardHeightPx,
            marginPx = marginPx,
            isLandscape = true,
        )
        assertTrue(xLeft >= marginPx)
        assertTrue(xLeft + cardWidthPx <= screenWidth - marginPx + 0.01f)
        assertTrue(yLeft >= marginPx)
        assertTrue(yLeft + cardHeightPx <= screenHeight - marginPx + 0.01f)
        assertTrue("Card should be on the right side when target is on the left", xLeft > 400f)

        // Right target (LayerPanel)
        val (xRight, yRight) = computeCompactCardPosition(
            step = 4,
            targetTop = 40f,
            targetBottom = 340f,
            targetLeft = 600f,
            targetRight = 844f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            cardWidthPx = cardWidthPx,
            cardHeightPx = cardHeightPx,
            marginPx = marginPx,
            isLandscape = true,
        )
        assertTrue(xRight >= marginPx)
        assertTrue(xRight + cardWidthPx <= screenWidth - marginPx + 0.01f)
        assertTrue(yRight >= marginPx)
        assertTrue(yRight + cardHeightPx <= screenHeight - marginPx + 0.01f)
        assertTrue("Card should be on the left side when target is on the right", xRight < 200f)
    }
}
