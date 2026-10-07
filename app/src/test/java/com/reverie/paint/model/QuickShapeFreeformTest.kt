/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuickShapeFreeformTest {
    private fun polygon(vertices: List<Point2D>) = vertices.indices.flatMap { i ->
        (0 until 40).map { vertices[i] + (vertices[(i + 1) % vertices.size] - vertices[i]) * (it / 40f) }
    } + vertices.first()
    private fun bow(scale: Float = 1f, angle: Float = 0f) = (0..160).map { i ->
        val t = i / 160f; val u = 1f - t
        val p = Point2D(-100f,40f) * (u*u) + Point2D(10f,-180f)*(2*u*t) + Point2D(150f,-20f)*(t*t)
        Point2D(300 + scale*(p.x*cos(angle)-p.y*sin(angle)),250 + scale*(p.x*sin(angle)+p.y*cos(angle)))
    }

    @Test fun `general quadrilateral recognition is opt in and preserves slanted corners`() {
        val vertices=listOf(Point2D(0f,0f),Point2D(120f,0f),Point2D(210f,110f),Point2D(-50f,90f))
        assertNull(QuickShapeFitter.fit(polygon(vertices)))
        val shape=QuickShapeFitter.fit(polygon(vertices),recognizeQuadrilaterals=true)!!
        assertEquals(QuickShapeType.QUADRILATERAL,shape.type)
        val handles=QuickShapeGeometry.handles(shape)
        assertEquals(4,handles.size)
        for(v in vertices) assertTrue(handles.minOf { it.distanceTo(v) } < 5f)
        assertEquals(9,QuickShapeGeometry.handles(shape,true).size)
        val moved=QuickShapeGeometry.drag(shape,2,handles[2],handles[2]+Point2D(10f,15f))
        val after=QuickShapeGeometry.handles(moved)
        for(i in 0..3) assertTrue(after[i].distanceTo(handles[i]+if(i==2)Point2D(10f,15f) else Point2D(0f,0f))<.001f)
    }

    @Test fun `quadrilateral accepts simple concavity but rejects a bow tie and a star`() {
        val concave=listOf(Point2D(0f,0f),Point2D(140f,0f),Point2D(55f,50f),Point2D(0f,140f))
        assertEquals(QuickShapeType.QUADRILATERAL,
            QuickShapeFitter.fit(polygon(concave),recognizeQuadrilaterals=true)?.type)
        val crossed=listOf(Point2D(0f,0f),Point2D(140f,140f),Point2D(0f,140f),Point2D(140f,0f))
        val star=(0..9).map { val r=if(it%2==0)100f else 35f;Point2D(r*cos(it*PI.toFloat()/5),r*sin(it*PI.toFloat()/5)) }
        for(p in listOf(crossed,star)) assertNull(QuickShapeFitter.fit(polygon(p),relaxed=true,recognizeQuadrilaterals=true))
    }

    @Test fun `curve recognition is opt in and keeps both measured endpoints`() {
        for(scale in listOf(.1f,1f,10f)) for(angle in listOf(0f,.7f,2.1f)) {
            val points=bow(scale,angle)
            assertNull(QuickShapeFitter.fit(points))
            val shape=QuickShapeFitter.fit(points,recognizeCurves=true)!!
            assertEquals(QuickShapeType.CURVE,shape.type)
            assertTrue(shape.points.first().distanceTo(points.first())<.01f)
            assertTrue(shape.points.last().distanceTo(points.last())<.01f)
            assertEquals(3,QuickShapeGeometry.handles(shape).size)
        }
    }

    @Test fun `on curve handle stays on curve while either endpoint remains fixed`() {
        val shape=QuickShapeFitter.fit(bow(),recognizeCurves=true)!!
        for(i in 0..2) {
            val from=shape.points[i]+Point2D(4f,-3f);val delta=Point2D(8f,20f)
            val moved=QuickShapeGeometry.drag(shape,i,from,from+delta)
            for(j in 0..2) assertEquals(shape.points[j]+if(i==j)delta else Point2D(0f,0f),moved.points[j])
            val path=QuickShapeGeometry.outline(moved)
            assertTrue(path.minOf { it.distanceTo(moved.points[1]) }<.001f)
            assertEquals(moved.points[0],path.first());assertEquals(moved.points[2],path.last())
            assertTrue(path.size<=2049)
        }
    }

    @Test fun `three point curves reject sharp corners S curves and retraced paths`() {
        val v=(0..80).map { Point2D(it.toFloat(),it.toFloat()) }+(1..80).map { Point2D(80f+it,80f-it) }
        val s=(0..160).map { Point2D(it.toFloat(),40*sin(it*2*PI.toFloat()/160)) }
        val arc=bow()
        for(points in listOf(v,s,arc.take(90)+arc.take(90).reversed()+arc))
            assertNull(QuickShapeFitter.fit(points,relaxed=true,recognizeCurves=true))
    }

    @Test fun `wobbly asymmetrical curve tolerates pauses and scaled sampling`() {
        val points=bow().mapIndexed { i,p -> p+Point2D(2f*sin(i*.4f),2f*cos(i*.35f)) }
        val paused=points.take(60)+List(50){points[59]}+points.drop(60)
        assertEquals(QuickShapeType.CURVE,QuickShapeFitter.fit(paused,recognizeCurves=true)?.type)
    }

    @Test fun `four corners do not depend on stroke direction or an edge starting point`() {
        val points=polygon(listOf(Point2D(0f,0f),Point2D(120f,0f),Point2D(210f,110f),Point2D(-50f,90f))).dropLast(1)
        for(reverse in listOf(false,true)) for(start in listOf(0,10,40,55,80,99,120,145)) {
            val ordered=if(reverse)points.reversed() else points
            val loop=ordered.drop(start)+ordered.take(start)+ordered[start]
            assertEquals("start=$start reverse=$reverse",QuickShapeType.QUADRILATERAL,
                QuickShapeFitter.fit(loop,recognizeQuadrilaterals=true)?.type)
        }
    }

    @Test fun `opt in four sided recognition does not swallow circles and triangles`() {
        val circle=(0..160).map { Point2D(100*cos(it*2*PI.toFloat()/160),100*sin(it*2*PI.toFloat()/160)) }
        assertEquals(QuickShapeType.CIRCLE,
            QuickShapeFitter.fit(circle,relaxed=true,recognizeQuadrilaterals=true,recognizeCurves=true)?.type)
        val triangle=polygon(listOf(Point2D(0f,0f),Point2D(100f,0f),Point2D(50f,100f)))
        assertEquals(QuickShapeType.TRIANGLE,
            QuickShapeFitter.fit(triangle,recognizeQuadrilaterals=true)?.type)
    }
}
