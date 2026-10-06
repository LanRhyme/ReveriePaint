/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuickShapeEnhancementsTest {
    private fun arc(radius: Float, sweep: Float, start: Float = 0f) = (0..160).map {
        val a = start + sweep * it / 160
        Point2D(300 + radius * cos(a), 250 + radius * sin(a))
    }
    private fun rectangle() = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(300f, 250f), 100f, 60f, .6f)

    @Test fun `arc recognition stays disabled by default`() {
        val path = arc(100f, PI.toFloat())
        assertNull(QuickShapeFitter.fit(path))
        assertEquals(QuickShapeType.ARC, QuickShapeFitter.fit(path, true)!!.type)
    }

    @Test fun `arcs preserve direction endpoints and radius at different scales`() {
        for (r in listOf(5f, 100f, 1500f)) for (sweep in listOf(-270f, -180f, -90f, 90f, 180f, 270f)) {
            val input = arc(r, sweep * PI.toFloat() / 180, 2.8f)
            val shape = QuickShapeFitter.fit(input, true)!!
            assertEquals(QuickShapeType.ARC, shape.type)
            assertEquals(r, shape.radiusX, r * .03f)
            assertEquals(sign(sweep), sign(shape.arcSweepRad), 0f)
            val outline = QuickShapeGeometry.outline(shape)
            assertTrue(outline.first().distanceTo(input.first()) < r * .01f)
            assertTrue(outline.last().distanceTo(input.last()) < r * .01f)
            assertTrue(outline.first().distanceTo(outline.last()) > r * .5f)
        }
    }

    @Test fun `arc rejects corners backtracking ellipses and invalid samples`() {
        val v = (0..80).map { Point2D(it.toFloat(), it.toFloat()) } +
            (1..80).map { Point2D(80f + it, 80f - it) }
        assertNull(QuickShapeFitter.fit(v, true))
        val good = arc(100f, PI.toFloat())
        assertNull(QuickShapeFitter.fit(good.take(90) + good.take(90).reversed() + good, true))
        assertNull(QuickShapeFitter.fit(good.map { Point2D(it.x * 2, it.y) }, true))
        assertNull(QuickShapeFitter.fit(good + Point2D(Float.NaN, 0f), true))
        // The old permissive fallback accepted the V; retain this as a concrete comparison.
        assertNotNull(LegacyQuickShapeFitter.fit(v))
        assertEquals(QuickShapeType.ARC, LegacyQuickShapeFitter.fit(good)!!.type)
    }

    @Test fun `arc tolerates uneven sampling and mild hand jitter`() {
        val input = arc(100f, PI.toFloat()).mapIndexed { i, p -> p + Point2D(sin(i.toFloat()), cos(i.toFloat())) }
        val fit = QuickShapeFitter.fit(input.take(30) + List(60) { input[29] } + input.drop(30), true)
        assertEquals(QuickShapeType.ARC, fit!!.type)
    }

    @Test fun `arc handles reshape without losing the curve or jumping on offset touch`() {
        val shape = QuickShapeFitter.fit(arc(100f, PI.toFloat()), true)!!
        for (i in 0..2) {
            val handle = QuickShapeGeometry.handles(shape)[i]; val delta = Point2D(3f, 5f)
            val offset = Point2D(8f, -3f)
            val edited = QuickShapeGeometry.drag(shape, i, handle + offset, handle + offset + delta)
            assertEquals(QuickShapeType.ARC, edited.type)
            assertTrue(edited.points[i].distanceTo(handle + delta) < .001f)
            assertTrue(QuickShapeGeometry.outline(edited).size > 10)
        }
        val middle = shape.points[1]
        assertEquals(shape, QuickShapeGeometry.drag(shape, 1, middle, (shape.points[0] + shape.points[2]) / 2f))
    }

    @Test fun `line snapping is opt in preserves anchor length and wraps around zero`() {
        for (degrees in listOf(-2f, 2f, 28f, 47f, 88f, 182f, 268f, 358f)) {
            val a = Point2D(12f, 30f); val angle = degrees * PI.toFloat() / 180
            val b = a + Point2D(100f * cos(angle), 100f * sin(angle))
            val line = QuickShapeResult(QuickShapeType.LINE, listOf(a, b))
            assertEquals(line, QuickShapeGeometry.snapLine(line, false))
            val snapped = QuickShapeGeometry.snapLine(line, true)
            assertEquals(a, snapped.points[0])
            assertEquals(100f, snapped.points[0].distanceTo(snapped.points[1]), .001f)
            val reverse = QuickShapeGeometry.snapLine(line, true, 1)
            assertEquals(b, reverse.points[1])
        }
        val angle = 12f * PI.toFloat() / 180
        val line = QuickShapeResult(QuickShapeType.LINE, listOf(Point2D(0f,0f), Point2D(100*cos(angle),100*sin(angle))))
        assertEquals(line, QuickShapeGeometry.snapLine(line, true))
    }

    @Test fun `eight handles resize rotated rectangle with opposite side fixed`() {
        val shape = rectangle()
        assertEquals(2, QuickShapeGeometry.handles(shape).size)
        val handles = QuickShapeGeometry.handles(shape, true)
        assertEquals(9, handles.size)
        for (i in 0..7) {
            val from = handles[i]; val to = from + Point2D(9f, 4f)
            val edited = QuickShapeGeometry.drag(shape, i, from, to, boxHandles = true)
            assertEquals(QuickShapeType.RECTANGLE, edited.type)
            assertEquals(shape.rotationRad, edited.rotationRad, 0f)
            val after = QuickShapeGeometry.handles(edited, true)
            assertTrue("opposite handle $i", after[(i+4)%8].distanceTo(handles[(i+4)%8]) < .001f)
            if (i%2==0) assertTrue(after[i].distanceTo(to)<.001f)
            else if (i==1 || i==5) assertEquals(shape.radiusX,edited.radiusX,.001f)
            else assertEquals(shape.radiusY,edited.radiusY,.001f)
            val offset = QuickShapeGeometry.drag(shape,i,from+Point2D(5f,7f),to+Point2D(5f,7f),boxHandles=true)
            assertTrue(edited.center.distanceTo(offset.center)<.001f)
            assertEquals(edited.radiusX,offset.radiusX,.001f)
            assertEquals(edited.radiusY,offset.radiusY,.001f)
        }
    }

    @Test fun `curved reshape follows corner motion without pulling the opposite curve backwards`() {
        val shape = rectangle(); val handles = QuickShapeGeometry.handles(shape,true)
        for(i in 0..7) {
            val delta=Point2D(15f,11f)
            val edited=QuickShapeGeometry.drag(shape,i,handles[i],handles[i]+delta,reshape=true,boxHandles=true,curvedContour=true)
            assertEquals(QuickShapeType.CONTOUR,edited.type)
            for(j in 0..7) {
                val motion = when {
                    j == i -> delta
                    i % 2 == 0 && (j == (i + 7) % 8 || j == (i + 1) % 8) -> delta / 4f
                    else -> Point2D(0f, 0f)
                }
                assertTrue(edited.points[j].distanceTo(handles[j] + motion) < .001f)
            }
            val path=QuickShapeGeometry.outline(edited)
            assertEquals(path.first(),path.last())
            assertTrue(path.size<=2049)
            assertTrue(path.all{it.x.isFinite()&&it.y.isFinite()})
            assertTrue(path.any{it.distanceTo(edited.points[i])<.001f})
        }
    }

    @Test fun `contour stays editable after resizing rotating and translating`() {
        val shape=rectangle();val h=QuickShapeGeometry.handles(shape,true)
        val curved=QuickShapeGeometry.drag(shape,3,h[3],h[3]+Point2D(30f,0f),reshape=true,boxHandles=true,curvedContour=true)
        val from=QuickShapeGeometry.handles(curved,true)[4]
        val resized=QuickShapeGeometry.drag(curved,4,from,from+Point2D(20f,10f),boxHandles=true)
        assertEquals(QuickShapeType.CONTOUR,resized.type)
        val rot=QuickShapeGeometry.handles(resized,true)[8]
        val rotated=QuickShapeGeometry.drag(resized,8,rot,rot+Point2D(30f,0f),boxHandles=true)
        assertNotEquals(resized.rotationRad,rotated.rotationRad)
        for(i in 0..7) assertEquals(resized.points[i].distanceTo(resized.center),rotated.points[i].distanceTo(rotated.center),.001f)
        val moved=QuickShapeGeometry.drag(rotated,-1,Point2D(0f,0f),Point2D(12f,19f),boxHandles=true)
        assertEquals(rotated.points[0]+Point2D(12f,19f),moved.points[0])
    }

    @Test fun `straight contour corner moves only that vertex and edge handle moves its endpoints`() {
        val shape = rectangle(); val handles = QuickShapeGeometry.handles(shape, true)
        val delta = Point2D(-10f, 30f)
        for (i in 0..7) {
            val moved = QuickShapeGeometry.drag(shape, i, handles[i], handles[i] + delta,
                reshape = true, boxHandles = true)
            assertFalse(moved.contourCurved)
            for (j in 0..6 step 2) {
                val affected = j == i || (i % 2 == 1 && (j == i - 1 || j == (i + 1) % 8))
                assertTrue(moved.points[j].distanceTo(handles[j] + if (affected) delta else Point2D(0f,0f)) < .001f)
            }
            for (j in 1..7 step 2) assertEquals((moved.points[j-1] + moved.points[(j+1)%8])/2f, moved.points[j])
            assertEquals(5, QuickShapeGeometry.outline(moved).size)
        }
    }

    @Test fun `dragging bottom left in curve mode matches a smooth connection without overshoot`() {
        val shape = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(50f,50f),50f,50f)
        val h = QuickShapeGeometry.handles(shape,true)
        val curved = QuickShapeGeometry.drag(shape,6,h[6],Point2D(-20f,150f),
            reshape=true,boxHandles=true,curvedContour=true)
        assertEquals(Point2D(-20f,150f),curved.points[6])
        assertEquals(h[4],curved.points[4])
        assertEquals(Point2D(45f,112.5f),curved.points[5])
        val path = QuickShapeGeometry.outline(curved)
        assertTrue(path.all { it.x >= -20.001f && it.x <= 100.001f && it.y >= -.001f && it.y <= 150.001f })
        val straight = QuickShapeGeometry.setContourCurved(curved,false)
        assertFalse(straight.contourCurved)
        assertEquals(curved.points[6],straight.points[6])
        assertEquals(Point2D(40f,125f),straight.points[5])
        assertEquals(5,QuickShapeGeometry.outline(straight).size)
        assertTrue(QuickShapeGeometry.setContourCurved(straight,true).contourCurved)
    }

    @Test fun `resizing deformed corners follows the pointer and fixes the actual opposite corner`() {
        val shape = rectangle(); val h = QuickShapeGeometry.handles(shape,true)
        val contour = QuickShapeGeometry.drag(shape,6,h[6],h[6]+Point2D(-20f,50f),
            reshape=true,boxHandles=true,curvedContour=true)
        val before = QuickShapeGeometry.handles(contour,true)
        val to = before[6] + Point2D(-10f,20f)
        val resized = QuickShapeGeometry.drag(contour,6,before[6],to,boxHandles=true)
        val after = QuickShapeGeometry.handles(resized,true)
        assertTrue(after[6].distanceTo(to) < .001f)
        assertTrue(after[2].distanceTo(before[2]) < .001f)
    }

    @Test fun `resize crossing opposite edge clamps without inversion`() {
        val shape=rectangle();val h=QuickShapeGeometry.handles(shape,true)
        val edited=QuickShapeGeometry.drag(shape,0,h[0],h[4]+Point2D(1000f,1000f),boxHandles=true)
        assertTrue(edited.radiusX>=1f&&edited.radiusY>=1f)
        assertEquals(shape,QuickShapeGeometry.drag(shape,0,h[0],Point2D(Float.NaN,0f),boxHandles=true))
    }
}
