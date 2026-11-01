/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import kotlin.random.Random

/**
 * 3D 网格边缘工具与 OBJ 解析的回归测试。
 *
 * 背景: 1.6.3 线上出现 onDraw 里 `computeEdgeTriangles` 卡死主线程 (O(边×面)) 与
 * 大模型解析 OOM。这里把"边 -> 邻接三角面"的语义、线框去重、OBJ 解析的内存友好实现
 * 都钉住, 防止再次退回暴力实现。
 */
class Mesh3DWireframeTest {

    @Test
    fun `computeEdgeTriangles matches brute force on grid meshes`() {
        val random = Random(7)
        repeat(8) { case ->
            val quads = 2 + case
            val (positions, triangles) = buildGrid(quads, quads)
            val wire = Mesh3D.extractWireframeIndices(triangles)
            val expected = bruteForceEdgeTriangles(triangles, wire)
            val actual = Mesh3D.computeEdgeTriangles(triangles, wire)
            assertArrayEquals("case $case positions=${positions.size}", expected, actual)
        }
    }

    @Test
    fun `computeEdgeTriangles keeps first two triangles per edge in index order`() {
        // 四条三角面共享同一条边 (顶点 0-1), 旧实现只记前两个三角面
        val triangles = intArrayOf(
            0, 1, 2,
            0, 1, 3,
            0, 1, 4,
            0, 1, 5,
        )
        val wire = intArrayOf(0, 1)
        val result = Mesh3D.computeEdgeTriangles(triangles, wire)
        assertArrayEquals(intArrayOf(0, 1), result)

        // 边在里面出现时顺序相反也能匹配 (顶点对无序)
        val reversed = Mesh3D.computeEdgeTriangles(triangles, intArrayOf(1, 0))
        assertArrayEquals(intArrayOf(0, 1), reversed)
    }

    @Test
    fun `extractWireframeIndices deduplicates shared edges`() {
        // 一个四边形 = 两个三角面: 4 条边界 + 1 条对角线 = 5 条边
        val triangles = intArrayOf(0, 1, 2, 0, 2, 3)
        val wire = Mesh3D.extractWireframeIndices(triangles)
        assertEquals(10, wire.size)
        val edges = wire.toList().chunked(2).map { it[0] to it[1] }.toSet()
        assertEquals(5, edges.size)
        assertTrue((0 to 1) in edges)
        assertTrue((0 to 2) in edges) // 共用的对角线只出现一次
        assertTrue((2 to 3) in edges)
    }

    @Test
    fun `extractWireframeIndices normalizes each edge to min-max order`() {
        val wire = Mesh3D.extractWireframeIndices(intArrayOf(3, 1, 2, 1, 0, 2))
        wire.toList().chunked(2).forEach { (a, b) -> assertTrue("$a <= $b", a <= b) }
    }

    @Test
    fun `parseObj reads cube geometry and deduplicates vertices`() {
        val obj = """
            # unit cube, quads
            v -1 -1 -1
            v  1 -1 -1
            v  1  1 -1
            v -1  1 -1
            v -1 -1  1
            v  1 -1  1
            v  1  1  1
            v -1  1  1
            f 1 4 3 2
            f 5 6 7 8
            f 1 2 6 5
            f 2 3 7 6
            f 3 4 8 7
            f 4 1 5 8
        """.trimIndent()
        val mesh = Mesh3D.parseObj(BufferedReader(StringReader(obj)))
        assertEquals(8, mesh.vertexCount)
        assertEquals(12, mesh.triangleCount)
        // 立方体三角化后有 12 条棱 + 6 条面对角线 = 18 条线框边
        assertEquals(18, mesh.wireframeLineCount)
        assertEquals(36, mesh.wireframeEdgeTriangles.size)

        val positions = mesh.positions.toList().chunked(3).map { Triple(it[0], it[1], it[2]) }.toSet()
        assertTrue(Triple(-1f, -1f, -1f) in positions)
        assertTrue(Triple(1f, 1f, 1f) in positions)
    }

    @Test
    fun `parseObj handles slash normal indices and fan triangulation`() {
        val obj = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vn 0 0 1
            f 1//1 2//1 3//1 4//1
        """.trimIndent()
        val mesh = Mesh3D.parseObj(BufferedReader(StringReader(obj)))
        assertEquals(4, mesh.vertexCount)
        assertEquals(2, mesh.triangleCount)
        // 法线来自 vn 1 -> (0,0,1): 每个顶点的 z 分量都是 1
        assertEquals(1f, mesh.normals[2], 0.0001f)
        assertEquals(1f, mesh.normals[5], 0.0001f)
    }

    @Test
    fun `parseObj parses a single triangle`() {
        // 上限常量 (30 万顶点 / 60 万面) 没法用单测造真实超限数据, 这里只做基本解析冒烟;
        // 超限路径由 buildStaticMesh / 导入链路的 catch 负责提示用户。
        val obj = buildString {
            appendLine("v 0 0 0")
            appendLine("v 1 0 0")
            appendLine("v 0 1 0")
            appendLine("f 1 2 3")
        }
        val mesh = Mesh3D.parseObj(BufferedReader(StringReader(obj)))
        assertEquals(1, mesh.triangleCount)
    }

    private fun buildGrid(nx: Int, ny: Int): Pair<FloatArray, IntArray> {
        val positions = ArrayList<Float>()
        for (y in 0..ny) {
            for (x in 0..nx) {
                positions.add(x.toFloat())
                positions.add(y.toFloat())
                positions.add(0f)
            }
        }
        val triangles = ArrayList<Int>()
        val row = nx + 1
        for (y in 0 until ny) {
            for (x in 0 until nx) {
                val i0 = y * row + x
                val i1 = i0 + 1
                val i2 = i0 + row
                val i3 = i2 + 1
                triangles.add(i0); triangles.add(i1); triangles.add(i3)
                triangles.add(i0); triangles.add(i3); triangles.add(i2)
            }
        }
        return positions.toFloatArray() to triangles.toIntArray()
    }

    /** 旧实现的直译, 用来对拍新实现 */
    private fun bruteForceEdgeTriangles(triangleIndices: IntArray, wireIndices: IntArray): IntArray {
        val lineCount = wireIndices.size / 2
        val result = IntArray(lineCount * 2) { -1 }
        val triCount = triangleIndices.size / 3
        for (k in 0 until lineCount) {
            val e0 = wireIndices[k * 2]
            val e1 = wireIndices[k * 2 + 1]
            var found = 0
            for (t in 0 until triCount) {
                val a = triangleIndices[t * 3]
                val b = triangleIndices[t * 3 + 1]
                val c = triangleIndices[t * 3 + 2]
                val hasE0 = a == e0 || b == e0 || c == e0
                val hasE1 = a == e1 || b == e1 || c == e1
                if (hasE0 && hasE1) {
                    if (found == 0) {
                        result[k * 2] = t
                        found = 1
                    } else {
                        result[k * 2 + 1] = t
                        break
                    }
                }
            }
        }
        return result
    }
}
