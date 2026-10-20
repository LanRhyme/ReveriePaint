package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeDispatchTest {
    @Test
    fun `toolForMove returns current when not started`() {
        assertEquals(
            Tool.LIQUIFY,
            StrokeDispatch.toolForMove(strokeStarted = false, strokeTool = null, current = Tool.LIQUIFY)
        )
    }

    @Test
    fun `toolForMove returns strokeTool when started and not null`() {
        assertEquals(
            Tool.BRUSH,
            StrokeDispatch.toolForMove(strokeStarted = true, strokeTool = Tool.BRUSH, current = Tool.LIQUIFY)
        )
    }

    @Test
    fun `finish commits when brush switches to eraser and not cancel`() {
        assertEquals(
            StrokeFinish.COMMIT,
            StrokeDispatch.finish(strokeStarted = true, strokeTool = Tool.BRUSH, current = Tool.ERASER, isCancel = false)
        )
    }

    @Test
    fun `finish cancels when brush switches to liquify`() {
        assertEquals(
            StrokeFinish.CANCEL,
            StrokeDispatch.finish(strokeStarted = true, strokeTool = Tool.BRUSH, current = Tool.LIQUIFY, isCancel = false)
        )
    }

    @Test
    fun `finish cancels when brush is cancelled`() {
        assertEquals(
            StrokeFinish.CANCEL,
            StrokeDispatch.finish(strokeStarted = true, strokeTool = Tool.BRUSH, current = Tool.BRUSH, isCancel = true)
        )
    }

    @Test
    fun `finish returns NONE when strokeTool is null`() {
        assertEquals(
            StrokeFinish.NONE,
            StrokeDispatch.finish(strokeStarted = true, strokeTool = null, current = Tool.BRUSH, isCancel = false)
        )
        assertEquals(
            StrokeFinish.NONE,
            StrokeDispatch.finish(strokeStarted = false, strokeTool = null, current = Tool.BRUSH, isCancel = false)
        )
    }

    @Test
    fun `sameFamily checks`() {
        assertTrue(StrokeDispatch.sameFamily(Tool.BRUSH, Tool.ERASER))
        assertTrue(StrokeDispatch.sameFamily(Tool.BRUSH, Tool.SMUDGE))
        assertTrue(StrokeDispatch.sameFamily(Tool.ERASER, Tool.SMUDGE))
        assertFalse(StrokeDispatch.sameFamily(Tool.BRUSH, Tool.LIQUIFY))
    }
}
