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
}
