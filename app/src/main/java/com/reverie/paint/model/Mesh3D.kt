/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.io.BufferedReader
import kotlin.math.*

/**
 * OBJ 每行的空白分隔符。必须提到文件级常量:
 * 原先 `l.split("\\s+".toRegex())` 在解析循环里逐行编译正则, 大模型会持续触发 ICU
 * 编译, 主线程导入时直接 ANR (1.6.2 线上 ANR 栈就在 Pattern.compile)。
 */
private val OBJ_TOKEN_SEPARATOR = Regex("\\s+")

/**
 * 3D 模型规模上限。超过这个量级, 顶点/索引/线框三份数组加起来的峰值内存会顶到 Android
 * 的 Java 堆上限 (线上 3bde27f5cc 在 2480x3508 画布下解析大模型 OOM)。
 * 宁可给用户一条明确提示, 也不要静默 OOM 或把首帧画到 ANR。
 */
const val MAX_3D_VERTICES = 200_000
const val MAX_3D_TRIANGLES = 400_000
const val MAX_WIREFRAME_TRIANGLES = 120_000

private const val MAX_OBJ_VERTICES = MAX_3D_VERTICES
private const val MAX_OBJ_TRIANGLES = MAX_3D_TRIANGLES

/** 模型规模超出内存/交互预算。调用方应提示用户模型过大, 而不是静默兜底成方块。 */
class MeshTooLargeException(message: String) : IllegalStateException(message)

/** 不装箱的 Float 动态数组 (parseObj 里顶点/法线用, 避免 ArrayList<Float> 的百万级装箱) */
private class FloatGrow(capacity: Int = 1024) {
    var data = FloatArray(if (capacity > 0) capacity else 1024)
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = v
    }

    fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
}

/** 不装箱的 Int 动态数组 (索引/线框用) */
private class IntGrow(capacity: Int = 1024) {
    var data = IntArray(if (capacity > 0) capacity else 1024)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = v
    }

    fun toArray(): IntArray = if (size == data.size) data else data.copyOf(size)
}

/**
 * 开放寻址的 long -> long 映射, 键值为非负 (顶点键 / 边键), 用 -1 表示"不存在"。
 * OBJ 顶点去重与"边 -> 邻接三角面"两个热路径都靠它避开 HashMap 的装箱开销。
 */
private class LongLongMap(expected: Int = 1024) {
    private var keys: LongArray
    private var values: LongArray
    private var mask: Int
    private var count = 0

    init {
        var cap = 16
        val want = expected.coerceAtLeast(16)
        while (cap < want * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        values = LongArray(cap)
        mask = cap - 1
    }

    /** 不存在返回 -1 (调用方只写入非负值)。 */
    fun get(key: Long): Long {
        var idx = index(key)
        while (true) {
            val k = keys[idx]
            if (k == EMPTY) return -1L
            if (k == key) return values[idx]
            idx = (idx + 1) and mask
        }
    }

    fun put(key: Long, value: Long) {
        var idx = index(key)
        while (true) {
            val k = keys[idx]
            if (k == EMPTY) {
                keys[idx] = key
                values[idx] = value
                count++
                if (count * 3 > keys.size * 2) grow()
                return
            }
            if (k == key) {
                values[idx] = value
                return
            }
            idx = (idx + 1) and mask
        }
    }

    private fun index(key: Long): Int = (key xor (key ushr 32)).toInt() and mask

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val cap = keys.size shl 1
        keys = LongArray(cap) { EMPTY }
        values = LongArray(cap)
        mask = cap - 1
        count = 0
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k != EMPTY) put(k, oldValues[i])
        }
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}

/**
 * 开放寻址的 long 集合 (无装箱开销), 用 EMPTY (Long.MIN_VALUE) 表示槽位为空。
 * 专供 extractWireframeIndices 等边去重场景, 相比 LongLongMap 减半内存占用。
 */
private class LongSet(expected: Int = 1024) {
    private var keys: LongArray
    private var mask: Int
    private var count = 0

    init {
        var cap = 16
        val want = expected.coerceAtLeast(16)
        while (cap < want * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        mask = cap - 1
    }

    /** 插入 key。若之前不存在返回 true, 若已存在返回 false。 */
    fun add(key: Long): Boolean {
        var idx = (key xor (key ushr 32)).toInt() and mask
        while (true) {
            val k = keys[idx]
            if (k == EMPTY) {
                keys[idx] = key
                count++
                if (count * 3 > keys.size * 2) grow()
                return true
            }
            if (k == key) return false
            idx = (idx + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val cap = oldKeys.size shl 1
        keys = LongArray(cap) { EMPTY }
        mask = cap - 1
        count = 0
        for (k in oldKeys) {
            if (k != EMPTY) add(k)
        }
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}

/**
 * 3D 几何网格数据 (顶点位置、法线、三角面索引与线框线段索引)
 */
class Mesh3D(
    val positions: FloatArray,        // [x0, y0, z0, x1, y1, z1, ...]
    val normals: FloatArray,          // [nx0, ny0, nz0, nx1, ny1, nz1, ...]
    val indices: IntArray,            // 三角形顶点索引
    val wireframeIndices: IntArray,   // 线框线段顶点对索引 [e0_a, e0_b, e1_a, e1_b, ...]
    val wireframeEdgeTriangles: IntArray = computeEdgeTriangles(indices, wireframeIndices),
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val wireframeLineCount: Int get() = wireframeIndices.size / 2

    /** 返回平移后的副本 (法线/索引不变), 用于姿态预设的整体升降 */
    fun translated(dx: Float, dy: Float, dz: Float): Mesh3D {
        if (dx == 0f && dy == 0f && dz == 0f) return this
        val p = positions.copyOf()
        for (i in p.indices step 3) {
            p[i] += dx; p[i + 1] += dy; p[i + 2] += dz
        }
        return Mesh3D(p, normals, indices, wireframeIndices, wireframeEdgeTriangles)
    }

    val flatPositions: FloatArray by lazy {
        val triCount = indices.size / 3
        val result = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val i0 = indices[t * 3]; val i1 = indices[t * 3 + 1]; val i2 = indices[t * 3 + 2]
            val b = t * 9
            result[b] = positions[i0 * 3]; result[b + 1] = positions[i0 * 3 + 1]; result[b + 2] = positions[i0 * 3 + 2]
            result[b + 3] = positions[i1 * 3]; result[b + 4] = positions[i1 * 3 + 1]; result[b + 5] = positions[i1 * 3 + 2]
            result[b + 6] = positions[i2 * 3]; result[b + 7] = positions[i2 * 3 + 1]; result[b + 8] = positions[i2 * 3 + 2]
        }
        result
    }

    val flatNormals: FloatArray by lazy {
        val triCount = indices.size / 3
        val result = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val i0 = indices[t * 3]; val i1 = indices[t * 3 + 1]; val i2 = indices[t * 3 + 2]
            val b = t * 9
            val p0x = positions[i0 * 3]; val p0y = positions[i0 * 3 + 1]; val p0z = positions[i0 * 3 + 2]
            val p1x = positions[i1 * 3]; val p1y = positions[i1 * 3 + 1]; val p1z = positions[i1 * 3 + 2]
            val p2x = positions[i2 * 3]; val p2y = positions[i2 * 3 + 1]; val p2z = positions[i2 * 3 + 2]
            val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
            val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
            var fnx = e1y * e2z - e1z * e2y
            var fny = e1z * e2x - e1x * e2z
            var fnz = e1x * e2y - e1y * e2x
            val len = kotlin.math.hypot(kotlin.math.hypot(fnx, fny), fnz).coerceAtLeast(1e-6f)
            fnx /= len; fny /= len; fnz /= len
            result[b] = fnx; result[b + 1] = fny; result[b + 2] = fnz
            result[b + 3] = fnx; result[b + 4] = fny; result[b + 5] = fnz
            result[b + 6] = fnx; result[b + 7] = fny; result[b + 8] = fnz
        }
        result
    }

    companion object {

        /**
         * 预计算每条线框线段对应的邻接三角面索引 (最多关联 2 个三角面，用于正面边缘剔除)
         */
        fun computeEdgeTriangles(triangleIndices: IntArray, wireIndices: IntArray): IntArray {
            val lineCount = wireIndices.size / 2
            val result = IntArray(lineCount * 2) { -1 }
            if (lineCount == 0 || wireIndices.isEmpty()) return result
            val triCount = triangleIndices.size / 3
            if (triCount == 0) return result

            return try {
                // 旧实现对每条线框边全量扫一遍三角面 (O(边 × 面)): 大模型在 onDraw 里能把
                // 主线程卡到 ANR (线上 9fc2dbca2e)。这里只遍历一次三角面, 用开放寻址的
                // long->long 表存 "边 -> (三角面0, 三角面1)", 两个槽位打包进一个 long。
                val edgeTri = LongLongMap(triCount * 3 / 2)
                var t = 0
                while (t < triCount) {
                    val a = triangleIndices[t * 3]
                    val b = triangleIndices[t * 3 + 1]
                    val c = triangleIndices[t * 3 + 2]
                    registerEdgeTriangle(edgeTri, a, b, t)
                    registerEdgeTriangle(edgeTri, b, c, t)
                    registerEdgeTriangle(edgeTri, c, a, t)
                    t++
                }

                var k = 0
                while (k < lineCount) {
                    val packed = edgeTri.get(objEdgeKey(wireIndices[k * 2], wireIndices[k * 2 + 1]))
                    if (packed >= 0) {
                        result[k * 2] = (packed and 0xFFFFFFFFL).toInt() - 1
                        val second = (packed ushr 32).toInt() - 1
                        if (second >= 0) result[k * 2 + 1] = second
                    }
                    k++
                }
                result
            } catch (_: OutOfMemoryError) {
                result
            }
        }

        /** 顶点对 -> 无向边键 (低 32 位小索引, 高 32 位大索引) */
        private fun objEdgeKey(i1: Int, i2: Int): Long {
            val lo = min(i1, i2)
            val hi = max(i1, i2)
            return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
        }

        private fun registerEdgeTriangle(edgeTri: LongLongMap, i1: Int, i2: Int, triangle: Int) {
            val key = objEdgeKey(i1, i2)
            val previous = edgeTri.get(key)
            val slot = (triangle + 1).toLong()
            if (previous < 0) {
                edgeTri.put(key, slot)
            } else if ((previous ushr 32) == 0L) {
                // 每条边最多记前两个三角面, 与旧实现的 found<2 语义一致
                edgeTri.put(key, previous or (slot shl 32))
            }
        }

        /**
         * 提取三角形网格中不重复的边缘线段，构造线框索引
         */
        fun extractWireframeIndices(triangleIndices: IntArray): IntArray {
            val triCount = triangleIndices.size / 3
            if (triCount == 0) return IntArray(0)
            // 超过上限的大模型跳过线框提取，避免连续大数组申请导致 JVM 堆 OOM
            if (triCount > MAX_WIREFRAME_TRIANGLES) {
                return IntArray(0)
            }

            return try {
                // 使用不装箱的 LongSet 开放寻址集合单遍插入去重，相比 LongLongMap 减半内存
                val seen = LongSet(triCount * 3 / 2)
                val lineIndices = IntGrow(triCount * 3)

                var i = 0
                while (i + 2 < triangleIndices.size) {
                    addWireEdge(seen, lineIndices, triangleIndices[i], triangleIndices[i + 1])
                    addWireEdge(seen, lineIndices, triangleIndices[i + 1], triangleIndices[i + 2])
                    addWireEdge(seen, lineIndices, triangleIndices[i + 2], triangleIndices[i])
                    i += 3
                }
                lineIndices.toArray()
            } catch (_: OutOfMemoryError) {
                IntArray(0)
            }
        }

        private fun addWireEdge(seen: LongSet, out: IntGrow, i1: Int, i2: Int) {
            val lo = min(i1, i2)
            val hi = max(i1, i2)
            val key = (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
            if (seen.add(key)) {
                out.add(lo)
                out.add(hi)
            }
        }
        /**
         * 创建标准透视方块 (带面法线与棱边)
         */
        fun createCube(size: Float = 1.0f): Mesh3D {
            val h = size * 0.5f

            // 6 个面，每个面 4 个顶点，独立法线以呈现硬朗折角
            val p = floatArrayOf(
                // 前面 (z = +h)
                -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
                // 后面 (z = -h)
                 h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
                // 上面 (y = +h)
                -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
                // 下面 (y = -h)
                -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
                // 右面 (x = +h)
                 h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
                // 左面 (x = -h)
                -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
            )

            val n = floatArrayOf(
                // 前
                0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,
                // 后
                0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f,
                // 上
                0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,
                // 下
                0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f,
                // 右
                1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,
                // 左
                -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f,
            )

            val indices = IntArray(36)
            var idx = 0
            for (face in 0..5) {
                val base = face * 4
                indices[idx++] = base
                indices[idx++] = base + 1
                indices[idx++] = base + 2
                indices[idx++] = base
                indices[idx++] = base + 2
                indices[idx++] = base + 3
            }

            // 立方体的 12 条棱边
            val wire = intArrayOf(
                0, 1, 1, 2, 2, 3, 3, 0, // 前
                4, 5, 5, 6, 6, 7, 7, 4, // 后
                0, 5, 1, 4, 2, 7, 3, 6  // 连线
            )

            return Mesh3D(p, n, indices, wire)
        }

        /**
         * 创建经纬球体网格
         */
        fun createSphere(radius: Float = 1.0f, stacks: Int = 16, slices: Int = 24): Mesh3D {
            val positions = ArrayList<Float>()
            val normals = ArrayList<Float>()
            val indices = ArrayList<Int>()

            for (i in 0..stacks) {
                val phi = (PI * i / stacks).toFloat()
                val y = cos(phi) * radius
                val r = sin(phi) * radius

                for (j in 0..slices) {
                    val theta = (2.0 * PI * j / slices).toFloat()
                    val x = r * sin(theta)
                    val z = r * cos(theta)

                    positions.add(x)
                    positions.add(y)
                    positions.add(z)

                    val len = hypot(hypot(x, y), z).coerceAtLeast(1e-6f)
                    normals.add(x / len)
                    normals.add(y / len)
                    normals.add(z / len)
                }
            }

            for (i in 0 until stacks) {
                for (j in 0 until slices) {
                    val first = i * (slices + 1) + j
                    val second = first + slices + 1

                    indices.add(first)
                    indices.add(second)
                    indices.add(first + 1)

                    indices.add(second)
                    indices.add(second + 1)
                    indices.add(first + 1)
                }
            }

            // 结构线框：纯净的主经纬圆环 (赤道、±45° 纬线、4 条主要子午线)，不含任何杂乱对角线
            val wireList = ArrayList<Int>()
            val stride = slices + 1

            // 纬线圈：赤道与上下两条纬线圈
            val keyStacks = intArrayOf(stacks / 4, stacks / 2, (stacks * 3) / 4)
            for (st in keyStacks) {
                for (j in 0 until slices) {
                    val v1 = st * stride + j
                    val v2 = st * stride + (j + 1)
                    wireList.add(v1)
                    wireList.add(v2)
                }
            }

            // 4 条主要子午线：0°, 90°, 180°, 270°
            val keySlices = intArrayOf(0, slices / 4, slices / 2, (slices * 3) / 4)
            for (sl in keySlices) {
                for (i in 0 until stacks) {
                    val v1 = i * stride + sl
                    val v2 = (i + 1) * stride + sl
                    wireList.add(v1)
                    wireList.add(v2)
                }
            }

            val pArray = positions.toFloatArray()
            val nArray = normals.toFloatArray()
            val iArray = indices.toIntArray()
            val wArray = wireList.toIntArray()
            return Mesh3D(pArray, nArray, iArray, wArray)
        }

        /**
         * 创建标准圆柱体
         */
        fun createCylinder(radius: Float = 0.8f, height: Float = 1.8f, slices: Int = 24): Mesh3D {
            val positions = ArrayList<Float>()
            val normals = ArrayList<Float>()
            val indices = ArrayList<Int>()
            val halfH = height * 0.5f

            // 侧面顶点 (上圈与下圈)
            for (j in 0..slices) {
                val theta = (2.0 * PI * j / slices).toFloat()
                val nx = sin(theta)
                val nz = cos(theta)
                val x = radius * nx
                val z = radius * nz

                // 上顶点
                positions.add(x); positions.add(halfH); positions.add(z)
                normals.add(nx); normals.add(0f); normals.add(nz)

                // 下顶点
                positions.add(x); positions.add(-halfH); positions.add(z)
                normals.add(nx); normals.add(0f); normals.add(nz)
            }

            for (j in 0 until slices) {
                val top1 = j * 2
                val bot1 = top1 + 1
                val top2 = (j + 1) * 2
                val bot2 = top2 + 1

                indices.add(top1); indices.add(bot1); indices.add(top2)
                indices.add(bot1); indices.add(bot2); indices.add(top2)
            }

            // 顶盖与底盖中心点
            val topCenterIdx = positions.size / 3
            positions.add(0f); positions.add(halfH); positions.add(0f)
            normals.add(0f); normals.add(1f); normals.add(0f)

            val botCenterIdx = positions.size / 3
            positions.add(0f); positions.add(-halfH); positions.add(0f)
            normals.add(0f); normals.add(-1f); normals.add(0f)

            // 顶底面三角形
            for (j in 0 until slices) {
                val top1 = j * 2
                val top2 = (j + 1) * 2
                indices.add(topCenterIdx); indices.add(top2); indices.add(top1)

                val bot1 = j * 2 + 1
                val bot2 = (j + 1) * 2 + 1
                indices.add(botCenterIdx); indices.add(bot1); indices.add(bot2)
            }

            // 结构线框：顶圈、底圈与 4 根主母线，完全去除对角交叉杂线
            val wireList = ArrayList<Int>()
            for (j in 0 until slices) {
                wireList.add(j * 2)
                wireList.add((j + 1) * 2)
                wireList.add(j * 2 + 1)
                wireList.add((j + 1) * 2 + 1)
            }
            val keySlices = intArrayOf(0, slices / 4, slices / 2, (slices * 3) / 4)
            for (sl in keySlices) {
                wireList.add(sl * 2)
                wireList.add(sl * 2 + 1)
            }

            val pArray = positions.toFloatArray()
            val nArray = normals.toFloatArray()
            val iArray = indices.toIntArray()
            val wArray = wireList.toIntArray()
            return Mesh3D(pArray, nArray, iArray, wArray)
        }

        /**
         * 基础头部比例素模 (具有清晰的面部结构体块、下颌、鼻梁、颧骨、额头平面)
         */
        fun createHeadMannequin(): Mesh3D {
            // 精心设计的折面头部体块网格 (30+ 个特征关键控制点，覆盖三庭五眼基础比例)
            val v = floatArrayOf(
                // 额头与头顶
                 0.0f,  1.10f,  0.0f,   // 0 头顶
                -0.45f, 0.85f, -0.2f,   // 1 后脑左上
                 0.45f, 0.85f, -0.2f,   // 2 后脑右上
                -0.35f, 0.75f,  0.35f,  // 3 额头左上
                 0.35f, 0.75f,  0.35f,  // 4 额头右上
                 0.0f,  0.78f,  0.45f,  // 5 额头正中
                // 眉弓与太阳穴
                -0.45f, 0.45f,  0.25f,  // 6 左太阳穴
                 0.45f, 0.45f,  0.25f,  // 7 右太阳穴
                -0.20f, 0.45f,  0.48f,  // 8 左眉弓
                 0.20f, 0.45f,  0.48f,  // 9 右眉弓
                 0.0f,  0.45f,  0.50f,  // 10 眉心 (山根上部)
                // 眼睛与颧骨
                -0.42f, 0.20f,  0.35f,  // 11 左颧骨
                 0.42f, 0.20f,  0.35f,  // 12 右颧骨
                -0.15f, 0.25f,  0.38f,  // 13 左眼窝
                 0.15f, 0.25f,  0.38f,  // 14 右眼窝
                // 鼻子
                 0.0f,  0.22f,  0.58f,  // 15 鼻根/鼻梁
                 0.0f,  0.05f,  0.68f,  // 16 鼻尖
                -0.10f, 0.02f,  0.55f,  // 17 左鼻翼
                 0.10f, 0.02f,  0.55f,  // 18 右鼻翼
                // 嘴唇与人中
                 0.0f, -0.10f,  0.54f,  // 19 上唇结节
                 0.0f, -0.22f,  0.52f,  // 20 下唇中央
                -0.20f,-0.15f,  0.42f,  // 21 左嘴角
                 0.20f,-0.15f,  0.42f,  // 22 右嘴角
                // 下巴与下颌角
                 0.0f, -0.45f,  0.52f,  // 23 下巴正中前突
                -0.12f,-0.48f,  0.46f,  // 24 下巴左侧
                 0.12f,-0.48f,  0.46f,  // 25 下巴右侧
                -0.38f,-0.20f, -0.05f,  // 26 左下颌角
                 0.38f,-0.20f, -0.05f,  // 27 右下颌角
                 0.0f, -0.58f,  0.25f,  // 28 颈底正中
                // 后脑与枕骨
                 0.0f,  0.30f, -0.55f,  // 29 枕骨隆突
                 0.0f, -0.25f, -0.40f,  // 30 后颈窝
            )

            val rawTriangles = intArrayOf(
                // 额头折面
                0, 3, 5,   0, 5, 4,   0, 1, 3,   0, 4, 2,
                3, 6, 8,   4, 9, 7,   5, 8, 10,  5, 10, 9,  3, 8, 5,   4, 5, 9,
                // 眼窝与鼻梁
                8, 6, 11,  8, 11, 13, 9, 14, 12, 9, 12, 7,
                10, 8, 13, 10, 14, 9, 10, 13, 15, 10, 15, 14,
                // 鼻子
                15, 13, 17, 15, 18, 14, 15, 17, 16, 15, 16, 18,
                // 颧骨到面颊
                11, 21, 13, 12, 14, 22, 13, 21, 17, 14, 18, 22,
                16, 17, 19, 16, 19, 18, 17, 21, 19, 18, 19, 22,
                // 嘴巴与下巴
                19, 21, 20, 19, 20, 22,
                20, 21, 24, 20, 25, 22, 20, 24, 23, 20, 23, 25,
                // 脸颊与下颌轮廓
                11, 26, 21, 12, 22, 27,
                21, 26, 24, 22, 25, 27,
                23, 24, 28, 23, 28, 25,
                24, 26, 28, 25, 28, 27,
                // 后脑与枕骨闭合
                1, 29, 3,   2, 4, 29,   6, 26, 11,  7, 12, 27,
                1, 29, 26,  2, 27, 29,  26, 30, 28, 27, 28, 30,
                29, 30, 26, 29, 27, 30, 0, 2, 29,   0, 29, 1
            )

            // 自动计算顶点法线 (每个三角形贡献面法线并归一化)
            val numVertices = v.size / 3
            val normals = FloatArray(v.size)
            var t = 0
            while (t + 2 < rawTriangles.size) {
                val i0 = rawTriangles[t]
                val i1 = rawTriangles[t + 1]
                val i2 = rawTriangles[t + 2]

                val x0 = v[i0 * 3]; val y0 = v[i0 * 3 + 1]; val z0 = v[i0 * 3 + 2]
                val x1 = v[i1 * 3]; val y1 = v[i1 * 3 + 1]; val z1 = v[i1 * 3 + 2]
                val x2 = v[i2 * 3]; val y2 = v[i2 * 3 + 1]; val z2 = v[i2 * 3 + 2]

                val e1x = x1 - x0; val e1y = y1 - y0; val e1z = z1 - z0
                val e2x = x2 - x0; val e2y = y2 - y0; val e2z = z2 - z0

                val fnx = e1y * e2z - e1z * e2y
                val fny = e1z * e2x - e1x * e2z
                val fnz = e1x * e2y - e1y * e2x

                normals[i0 * 3] += fnx; normals[i0 * 3 + 1] += fny; normals[i0 * 3 + 2] += fnz
                normals[i1 * 3] += fnx; normals[i1 * 3 + 1] += fny; normals[i1 * 3 + 2] += fnz
                normals[i2 * 3] += fnx; normals[i2 * 3 + 1] += fny; normals[i2 * 3 + 2] += fnz
                t += 3
            }

            for (i in 0 until numVertices) {
                val nx = normals[i * 3]; val ny = normals[i * 3 + 1]; val nz = normals[i * 3 + 2]
                val len = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-6f)
                normals[i * 3] = nx / len
                normals[i * 3 + 1] = ny / len
                normals[i * 3 + 2] = nz / len
            }

            // 头部解剖与体块关键结构基准线 (严谨定义三庭五眼与面部折面，消除网格对角线杂乱)
            val wire = intArrayOf(
                // 头部中轴线 (三庭五眼主线)
                0, 5,   5, 10,   10, 15,   15, 16,   16, 19,   19, 20,   20, 23,   23, 28,
                // 额头发际线与头顶轮廓
                1, 3,   3, 5,    5, 4,     4, 2,     0, 1,     0, 2,
                // 眉弓横线 (左右贯通)
                6, 8,   8, 10,   10, 9,    9, 7,
                // 眼眶与颧骨结构
                8, 13,  13, 11,  9, 14,    14, 12,   10, 13,   10, 14,
                11, 6,  12, 7,
                // 鼻梁与鼻部体块
                13, 17, 14, 18,  15, 17,   15, 18,   17, 16,   18, 16,
                // 嘴唇轮廓与嘴角
                21, 19, 19, 22,  21, 20,   20, 22,   17, 21,   18, 22,
                // 下颌与下巴 (下颌骨边缘)
                26, 24, 24, 23,  23, 25,   25, 27,
                26, 21, 27, 22,  21, 24,   22, 25,
                // 侧脸与耳前垂直分界
                6, 26,  7, 27,   11, 26,   12, 27,
                // 颈部与后脑轮廓
                24, 28, 25, 28,  26, 30,   27, 30,   28, 30,
                1, 29,  2, 29,   29, 30,   0, 29
            )
            return Mesh3D(v, normals, rawTriangles, wire)
        }

        /**
         * 经典人体比例体块生成器 (八头身伯里曼/卢米斯解剖体块)
         */
        private class LimbBuilder {
            val v = ArrayList<Float>()
            val tri = ArrayList<Int>()
            val wire = ArrayList<Int>()

            fun addSegment(
                x1: Float, y1: Float, z1: Float,
                x2: Float, y2: Float, z2: Float,
                topW: Float, topD: Float,
                botW: Float, botD: Float,
            ) {
                val baseIdx = v.size / 3
                val dx = x2 - x1
                val dy = y2 - y1
                val dz = z2 - z1
                val len = hypot(hypot(dx, dy), dz).coerceAtLeast(1e-5f)
                val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

                var upX = 0f; var upY = 1f; var upZ = 0f
                if (abs(dirY) > 0.95f) {
                    upX = 0f; upY = 0f; upZ = 1f
                }

                var sideX = dirY * upZ - dirZ * upY
                var sideY = dirZ * upX - dirX * upZ
                var sideZ = dirX * upY - dirY * upX
                val sLen = hypot(hypot(sideX, sideY), sideZ).coerceAtLeast(1e-5f)
                sideX /= sLen; sideY /= sLen; sideZ /= sLen

                val fwdX = sideY * dirZ - sideZ * dirY
                val fwdY = sideZ * dirX - sideX * dirZ
                val fwdZ = sideX * dirY - sideY * dirX

                val hw1 = topW * 0.5f; val hd1 = topD * 0.5f
                val hw2 = botW * 0.5f; val hd2 = botD * 0.5f

                v.add(x1 + sideX * hw1 + fwdX * hd1); v.add(y1 + sideY * hw1 + fwdY * hd1); v.add(z1 + sideZ * hw1 + fwdZ * hd1)
                v.add(x1 - sideX * hw1 + fwdX * hd1); v.add(y1 - sideY * hw1 + fwdY * hd1); v.add(z1 - sideZ * hw1 + fwdZ * hd1)
                v.add(x1 - sideX * hw1 - fwdX * hd1); v.add(y1 - sideY * hw1 - fwdY * hd1); v.add(z1 - sideZ * hw1 - fwdZ * hd1)
                v.add(x1 + sideX * hw1 - fwdX * hd1); v.add(y1 + sideY * hw1 - fwdY * hd1); v.add(z1 + sideZ * hw1 - fwdZ * hd1)

                v.add(x2 + sideX * hw2 + fwdX * hd2); v.add(y2 + sideY * hw2 + fwdY * hd2); v.add(z2 + sideZ * hw2 + fwdZ * hd2)
                v.add(x2 - sideX * hw2 + fwdX * hd2); v.add(y2 - sideY * hw2 + fwdY * hd2); v.add(z2 - sideZ * hw2 + fwdZ * hd2)
                v.add(x2 - sideX * hw2 - fwdX * hd2); v.add(y2 - sideY * hw2 - fwdY * hd2); v.add(z2 - sideZ * hw2 - fwdZ * hd2)
                v.add(x2 + sideX * hw2 - fwdX * hd2); v.add(y2 + sideY * hw2 - fwdY * hd2); v.add(z2 + sideZ * hw2 - fwdZ * hd2)

                val i0 = baseIdx; val i1 = baseIdx + 1; val i2 = baseIdx + 2; val i3 = baseIdx + 3
                val i4 = baseIdx + 4; val i5 = baseIdx + 5; val i6 = baseIdx + 6; val i7 = baseIdx + 7

                tri.add(i0); tri.add(i2); tri.add(i1)
                tri.add(i0); tri.add(i3); tri.add(i2)
                tri.add(i4); tri.add(i5); tri.add(i6)
                tri.add(i4); tri.add(i6); tri.add(i7)
                tri.add(i0); tri.add(i1); tri.add(i5)
                tri.add(i0); tri.add(i5); tri.add(i4)
                tri.add(i2); tri.add(i3); tri.add(i7)
                tri.add(i2); tri.add(i7); tri.add(i6)
                tri.add(i1); tri.add(i2); tri.add(i6)
                tri.add(i1); tri.add(i6); tri.add(i5)
                tri.add(i3); tri.add(i0); tri.add(i4)
                tri.add(i3); tri.add(i4); tri.add(i7)

                wire.add(i0); wire.add(i1);  wire.add(i1); wire.add(i2)
                wire.add(i2); wire.add(i3);  wire.add(i3); wire.add(i0)
                wire.add(i4); wire.add(i5);  wire.add(i5); wire.add(i6)
                wire.add(i6); wire.add(i7);  wire.add(i7); wire.add(i4)
                wire.add(i0); wire.add(i4);  wire.add(i1); wire.add(i5)
                wire.add(i2); wire.add(i6);  wire.add(i3); wire.add(i7)
            }

            fun build(): Mesh3D {
                val numVerts = v.size / 3
                val vertArr = FloatArray(v.size) { v[it] }
                val triArr = IntArray(tri.size) { tri[it] }
                val wireArr = IntArray(wire.size) { wire[it] }

                val normArr = FloatArray(v.size)
                var t = 0
                while (t + 2 < triArr.size) {
                    val i0 = triArr[t]; val i1 = triArr[t + 1]; val i2 = triArr[t + 2]
                    val p0x = vertArr[i0 * 3]; val p0y = vertArr[i0 * 3 + 1]; val p0z = vertArr[i0 * 3 + 2]
                    val p1x = vertArr[i1 * 3]; val p1y = vertArr[i1 * 3 + 1]; val p1z = vertArr[i1 * 3 + 2]
                    val p2x = vertArr[i2 * 3]; val p2y = vertArr[i2 * 3 + 1]; val p2z = vertArr[i2 * 3 + 2]
                    val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
                    val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
                    val fnx = e1y * e2z - e1z * e2y
                    val fny = e1z * e2x - e1x * e2z
                    val fnz = e1x * e2y - e1y * e2x
                    normArr[i0 * 3] += fnx; normArr[i0 * 3 + 1] += fny; normArr[i0 * 3 + 2] += fnz
                    normArr[i1 * 3] += fnx; normArr[i1 * 3 + 1] += fny; normArr[i1 * 3 + 2] += fnz
                    normArr[i2 * 3] += fnx; normArr[i2 * 3 + 1] += fny; normArr[i2 * 3 + 2] += fnz
                    t += 3
                }
                for (i in 0 until numVerts) {
                    val nx = normArr[i * 3]; val ny = normArr[i * 3 + 1]; val nz = normArr[i * 3 + 2]
                    val len = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-6f)
                    normArr[i * 3] = nx / len
                    normArr[i * 3 + 1] = ny / len
                    normArr[i * 3 + 2] = nz / len
                }

                return Mesh3D(vertArr, normArr, triArr, wireArr)
            }
        }

        /**
         * 骨骼运动链正向运动学节点 (Hierarchical Forward Kinematics Node)
         */
        private class BoneNode(
            val m: FloatArray = Math3D.identity()
        ) {
            fun child(
                tx: Float = 0f, ty: Float = 0f, tz: Float = 0f,
                pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f
            ): BoneNode {
                var res = Math3D.translate(m, tx, ty, tz)
                if (yaw != 0f) res = Math3D.rotate(res, yaw, 0f, 1f, 0f)
                if (pitch != 0f) res = Math3D.rotate(res, pitch, 1f, 0f, 0f)
                if (roll != 0f) res = Math3D.rotate(res, roll, 0f, 0f, 1f)
                return BoneNode(res)
            }

            fun pt(x: Float = 0f, y: Float = 0f, z: Float = 0f): FloatArray {
                val px = m[0] * x + m[4] * y + m[8] * z + m[12]
                val py = m[1] * x + m[5] * y + m[9] * z + m[13]
                val pz = m[2] * x + m[6] * y + m[10] * z + m[14]
                return floatArrayOf(px, py, pz)
            }
        }

        /**
         * 二次元人体素模 (支持男体/女体比例切换、姿态预设与骨骼关节自由调节)
         */
        fun createAnimeMannequin(
            isFemale: Boolean = false,
            posePreset: String = "stand",
            angles: Map<String, Float> = emptyMap(),
        ): Mesh3D {
            val b = LimbBuilder()

            fun getAngle(key: String, base: Float = 0f): Float {
                return base + (angles[key] ?: 0f)
            }

            var spinePitchBase = 0f
            var spineYawBase = 0f
            var headPitchBase = 0f
            var headYawBase = 0f
            var lArmPitchBase = 0f
            var lArmYawBase = -15f
            var lElbowBase = 10f
            var rArmPitchBase = 0f
            var rArmYawBase = 15f
            var rElbowBase = 10f
            var lThighPitchBase = 0f
            var lThighYawBase = -3f
            var lKneeBase = 0f
            var rThighPitchBase = 0f
            var rThighYawBase = 3f
            var rKneeBase = 0f

            when (posePreset.lowercase()) {
                "sit" -> {
                    lThighPitchBase = -85f; lKneeBase = 85f
                    rThighPitchBase = -85f; rKneeBase = 85f
                    lArmPitchBase = -25f; lArmYawBase = -10f; lElbowBase = 55f
                    rArmPitchBase = -25f; rArmYawBase = 10f; rElbowBase = 55f
                }
                "action" -> {
                    spinePitchBase = 18f; spineYawBase = -15f
                    headYawBase = 15f; headPitchBase = -10f
                    lArmPitchBase = -50f; lArmYawBase = -20f; lElbowBase = 70f
                    rArmPitchBase = 40f; rArmYawBase = 20f; rElbowBase = 30f
                    lThighPitchBase = -40f; lThighYawBase = -5f; lKneeBase = 55f
                    rThighPitchBase = 35f; rThighYawBase = 5f; rKneeBase = 25f
                }
                "crossed" -> {
                    spinePitchBase = -4f
                    lArmPitchBase = -32f; lArmYawBase = 40f; lElbowBase = 95f
                    rArmPitchBase = -32f; rArmYawBase = -40f; rElbowBase = 95f
                    lThighYawBase = -5f; rThighYawBase = 5f
                }
                "hips" -> {
                    lArmPitchBase = 12f; lArmYawBase = -40f; lElbowBase = 95f
                    rArmPitchBase = 12f; rArmYawBase = 40f; rElbowBase = 95f
                    lThighYawBase = -6f; rThighYawBase = 6f
                }
                "kneel" -> {
                    spinePitchBase = 20f
                    lThighPitchBase = 15f; lKneeBase = 90f
                    rThighPitchBase = -75f; rKneeBase = 80f
                    rArmPitchBase = -45f; rElbowBase = 50f
                }
            }

            val spinePitch = getAngle("spine_pitch", spinePitchBase)
            val spineYaw = getAngle("spine_yaw", spineYawBase)
            val headPitch = getAngle("head_pitch", headPitchBase)
            val headYaw = getAngle("head_yaw", headYawBase)

            val lArmPitch = getAngle("l_arm_pitch", lArmPitchBase)
            val lArmYaw = getAngle("l_arm_yaw", lArmYawBase)
            val lElbow = getAngle("l_elbow", lElbowBase).coerceIn(0f, 150f)

            val rArmPitch = getAngle("r_arm_pitch", rArmPitchBase)
            val rArmYaw = getAngle("r_arm_yaw", rArmYawBase)
            val rElbow = getAngle("r_elbow", rElbowBase).coerceIn(0f, 150f)

            val lThighPitch = getAngle("l_thigh_pitch", lThighPitchBase)
            val lThighYaw = getAngle("l_thigh_yaw", lThighYawBase)
            val lKnee = getAngle("l_knee", lKneeBase).coerceIn(0f, 150f)

            val rThighPitch = getAngle("r_thigh_pitch", rThighPitchBase)
            val rThighYaw = getAngle("r_thigh_yaw", rThighYawBase)
            val rKnee = getAngle("r_knee", rKneeBase).coerceIn(0f, 150f)

            val shoulderX = if (isFemale) 0.28f else 0.36f
            val chestTopW = if (isFemale) 0.46f else 0.60f
            val chestTopD = if (isFemale) 0.28f else 0.34f
            val chestBotW = if (isFemale) 0.32f else 0.42f
            val chestBotD = if (isFemale) 0.24f else 0.26f
            val waistTopW = chestBotW
            val waistTopD = chestBotD
            val waistBotW = if (isFemale) 0.36f else 0.40f
            val waistBotD = if (isFemale) 0.24f else 0.25f
            val pelvisW = if (isFemale) 0.46f else 0.42f
            val pelvisD = if (isFemale) 0.28f else 0.28f
            val pelvisBotW = if (isFemale) 0.38f else 0.36f
            val pelvisBotD = 0.24f
            val hipX = if (isFemale) 0.16f else 0.17f

            val headW = if (isFemale) 0.26f else 0.28f
            val headD = if (isFemale) 0.28f else 0.30f
            val chinW = if (isFemale) 0.14f else 0.16f
            val chinD = if (isFemale) 0.18f else 0.20f
            val neckW = if (isFemale) 0.12f else 0.15f

            val uArmW = if (isFemale) 0.11f else 0.14f
            val uArmD = uArmW
            val uArmBotW = if (isFemale) 0.09f else 0.11f
            val uArmBotD = uArmBotW
            val fArmW = uArmBotW
            val fArmD = uArmBotD
            val fArmBotW = if (isFemale) 0.07f else 0.09f
            val fArmBotD = fArmBotW

            val thighW = if (isFemale) 0.21f else 0.20f
            val thighD = thighW
            val thighBotW = if (isFemale) 0.14f else 0.15f
            val thighBotD = thighBotW
            val calfW = thighBotW
            val calfD = thighBotD
            val calfBotW = if (isFemale) 0.08f else 0.10f
            val calfBotD = calfBotW

            val upperArmLen = 0.42f
            val forearmLen = 0.38f
            val handLen = 0.16f
            val thighLen = 0.58f
            val calfLen = 0.58f

            val root = BoneNode()

            // 1. 骨盆体块 (Pelvis)
            val pPelvisTop = root.pt(0f, 0.30f, 0f)
            val pPelvisBot = root.pt(0f, 0.04f, 0f)
            b.addSegment(pPelvisTop[0], pPelvisTop[1], pPelvisTop[2], pPelvisBot[0], pPelvisBot[1], pPelvisBot[2], pelvisW, pelvisD, pelvisBotW, pelvisBotD)

            // 2. 脊椎与腹部/腰节 (Spine & Waist)
            val spineNode = root.child(0f, 0.30f, 0f, pitch = spinePitch, yaw = spineYaw)
            val pWaistEnd = spineNode.pt(0f, 0.14f, 0f)
            b.addSegment(pPelvisTop[0], pPelvisTop[1], pPelvisTop[2], pWaistEnd[0], pWaistEnd[1], pWaistEnd[2], waistBotW, waistBotD, waistTopW, waistTopD)

            // 3. 胸腔体块 (Chest)
            val chestNode = spineNode.child(0f, 0.14f, 0f)
            val pChestEnd = chestNode.pt(0f, 0.44f, 0f)
            b.addSegment(pWaistEnd[0], pWaistEnd[1], pWaistEnd[2], pChestEnd[0], pChestEnd[1], pChestEnd[2], chestBotW, chestBotD, chestTopW, chestTopD)

            // 4. 颈部与头部 (Neck & Anime Head)
            val neckNode = chestNode.child(0f, 0.44f, 0f, pitch = headPitch, yaw = headYaw)
            val pNeckEnd = neckNode.pt(0f, 0.12f, 0f)
            b.addSegment(pChestEnd[0], pChestEnd[1], pChestEnd[2], pNeckEnd[0], pNeckEnd[1], pNeckEnd[2], neckW, neckW, neckW * 0.9f, neckW * 0.9f)

            val headNode = neckNode.child(0f, 0.12f, 0f)
            val pHeadMid = headNode.pt(0f, 0.22f, 0.02f)
            val pHeadTop = headNode.pt(0f, 0.42f, 0.03f)
            // 二次元收窄下颌
            b.addSegment(pNeckEnd[0], pNeckEnd[1], pNeckEnd[2] + 0.03f, pHeadMid[0], pHeadMid[1], pHeadMid[2], chinW, chinD, headW, headD)
            // 颅骨
            b.addSegment(pHeadMid[0], pHeadMid[1], pHeadMid[2], pHeadTop[0], pHeadTop[1], pHeadTop[2], headW, headD, headW * 0.85f, headD * 0.85f)

            // 5. 左臂 (Left Arm)
            val lArmNode = chestNode.child(-shoulderX, 0.38f, 0f, pitch = lArmPitch, yaw = lArmYaw)
            val pLShoulder = lArmNode.pt(0f, 0f, 0f)
            val pLElbow = lArmNode.pt(0f, -upperArmLen, 0f)
            b.addSegment(pLShoulder[0], pLShoulder[1], pLShoulder[2], pLElbow[0], pLElbow[1], pLElbow[2], uArmW, uArmD, uArmBotW, uArmBotD)

            val lElbowNode = lArmNode.child(0f, -upperArmLen, 0f, pitch = lElbow)
            val pLWrist = lElbowNode.pt(0f, -forearmLen, 0f)
            b.addSegment(pLElbow[0], pLElbow[1], pLElbow[2], pLWrist[0], pLWrist[1], pLWrist[2], fArmW, fArmD, fArmBotW, fArmBotD)
            val pLHand = lElbowNode.pt(0f, -forearmLen - handLen, 0.01f)
            b.addSegment(pLWrist[0], pLWrist[1], pLWrist[2], pLHand[0], pLHand[1], pLHand[2], fArmBotW, fArmBotD * 0.7f, fArmBotW * 0.6f, fArmBotD * 0.4f)

            // 6. 右臂 (Right Arm)
            val rArmNode = chestNode.child(shoulderX, 0.38f, 0f, pitch = rArmPitch, yaw = rArmYaw)
            val pRShoulder = rArmNode.pt(0f, 0f, 0f)
            val pRElbow = rArmNode.pt(0f, -upperArmLen, 0f)
            b.addSegment(pRShoulder[0], pRShoulder[1], pRShoulder[2], pRElbow[0], pRElbow[1], pRElbow[2], uArmW, uArmD, uArmBotW, uArmBotD)

            val rElbowNode = rArmNode.child(0f, -upperArmLen, 0f, pitch = rElbow)
            val pRWrist = rElbowNode.pt(0f, -forearmLen, 0f)
            b.addSegment(pRElbow[0], pRElbow[1], pRElbow[2], pRWrist[0], pRWrist[1], pRWrist[2], fArmW, fArmD, fArmBotW, fArmBotD)
            val pRHand = rElbowNode.pt(0f, -forearmLen - handLen, 0.01f)
            b.addSegment(pRWrist[0], pRWrist[1], pRWrist[2], pRHand[0], pRHand[1], pRHand[2], fArmBotW, fArmBotD * 0.7f, fArmBotW * 0.6f, fArmBotD * 0.4f)

            // 7. 左腿 (Left Leg)
            val lLegNode = root.child(-hipX, 0.04f, 0f, pitch = lThighPitch, yaw = lThighYaw)
            val pLHip = lLegNode.pt(0f, 0f, 0f)
            val pLKnee = lLegNode.pt(0f, -thighLen, 0f)
            b.addSegment(pLHip[0], pLHip[1], pLHip[2], pLKnee[0], pLKnee[1], pLKnee[2], thighW, thighD, thighBotW, thighBotD)

            val lKneeNode = lLegNode.child(0f, -thighLen, 0f, pitch = lKnee)
            val pLAnkle = lKneeNode.pt(0f, -calfLen, 0f)
            b.addSegment(pLKnee[0], pLKnee[1], pLKnee[2], pLAnkle[0], pLAnkle[1], pLAnkle[2], calfW, calfD, calfBotW, calfBotD)
            val pLToe = lKneeNode.pt(0f, -calfLen - 0.08f, 0.18f)
            b.addSegment(pLAnkle[0], pLAnkle[1], pLAnkle[2], pLToe[0], pLToe[1], pLToe[2], calfBotW * 1.1f, calfBotD, calfBotW * 0.8f, calfBotD * 0.5f)

            // 8. 右腿 (Right Leg)
            val rLegNode = root.child(hipX, 0.04f, 0f, pitch = rThighPitch, yaw = rThighYaw)
            val pRHip = rLegNode.pt(0f, 0f, 0f)
            val pRKnee = rLegNode.pt(0f, -thighLen, 0f)
            b.addSegment(pRHip[0], pRHip[1], pRHip[2], pRKnee[0], pRKnee[1], pRKnee[2], thighW, thighD, thighBotW, thighBotD)

            val rKneeNode = rLegNode.child(0f, -thighLen, 0f, pitch = rKnee)
            val pRAnkle = rKneeNode.pt(0f, -calfLen, 0f)
            b.addSegment(pRKnee[0], pRKnee[1], pRKnee[2], pRAnkle[0], pRAnkle[1], pRAnkle[2], calfW, calfD, calfBotW, calfBotD)
            val pRToe = rKneeNode.pt(0f, -calfLen - 0.08f, 0.18f)
            b.addSegment(pRAnkle[0], pRAnkle[1], pRAnkle[2], pRToe[0], pRToe[1], pRToe[2], calfBotW * 1.1f, calfBotD, calfBotW * 0.8f, calfBotD * 0.5f)

            return b.build()
        }

        /**
         * 3D 猫猫模型 (带猫耳、口吻、四肢、猫爪与多节灵动尾巴，支持四种姿态与骨骼调节)
         */
        fun createCatModel(
            posePreset: String = "stand",
            angles: Map<String, Float> = emptyMap(),
        ): Mesh3D {
            val b = LimbBuilder()

            fun getAngle(key: String, base: Float = 0f): Float {
                return base + (angles[key] ?: 0f)
            }

            var catHeadPitchBase = 0f
            var catHeadYawBase = 0f
            var catSpineArchBase = 0f
            var catFlLegBase = 0f
            var catFrLegBase = 0f
            var catHlLegBase = 0f
            var catHrLegBase = 0f
            var catTailPitchBase = 35f
            var catTailYawBase = 10f

            when (posePreset.lowercase()) {
                "sit" -> {
                    catSpineArchBase = -15f
                    catHeadPitchBase = 12f
                    catFlLegBase = -5f; catFrLegBase = -5f
                    catHlLegBase = -70f; catHrLegBase = -70f
                    catTailPitchBase = -45f; catTailYawBase = 35f
                }
                "loaf" -> {
                    catSpineArchBase = 8f
                    catFlLegBase = -75f; catFrLegBase = -75f
                    catHlLegBase = -80f; catHrLegBase = -80f
                    catTailPitchBase = -60f; catTailYawBase = 50f
                }
                "stretch" -> {
                    catSpineArchBase = 28f
                    catHeadPitchBase = -15f
                    catFlLegBase = 45f; catFrLegBase = 45f
                    catHlLegBase = 20f; catHrLegBase = 20f
                    catTailPitchBase = 65f; catTailYawBase = 5f
                }
            }

            val headPitch = getAngle("cat_head_pitch", catHeadPitchBase)
            val headYaw = getAngle("cat_head_yaw", catHeadYawBase)
            val spineArch = getAngle("cat_spine_arch", catSpineArchBase)
            val flLeg = getAngle("cat_fl_leg", catFlLegBase)
            val frLeg = getAngle("cat_fr_leg", catFrLegBase)
            val hlLeg = getAngle("cat_hl_leg", catHlLegBase)
            val hrLeg = getAngle("cat_hr_leg", catHrLegBase)
            val tailPitch = getAngle("cat_tail_pitch", catTailPitchBase)
            val tailYaw = getAngle("cat_tail_yaw", catTailYawBase)

            val root = BoneNode()

            // 1. 后躯/骨盆 (Pelvis & Rear Torso)
            val rearHip = root.child(0f, 0.32f, -0.22f)
            val pRearFront = rearHip.pt(0f, 0f, 0.18f)
            val pRearBack = rearHip.pt(0f, 0f, -0.18f)
            b.addSegment(pRearFront[0], pRearFront[1], pRearFront[2], pRearBack[0], pRearBack[1], pRearBack[2], 0.32f, 0.28f, 0.28f, 0.26f)

            // 2. 前躯/胸腔 (Chest & Front Torso)
            val chest = root.child(0f, 0.32f, 0.15f, pitch = spineArch)
            val pChestBack = chest.pt(0f, 0f, -0.15f)
            val pChestFront = chest.pt(0f, 0f, 0.20f)
            b.addSegment(pChestBack[0], pChestBack[1], pChestBack[2], pChestFront[0], pChestFront[1], pChestFront[2], 0.32f, 0.28f, 0.34f, 0.30f)

            // 3. 头部与面部 (Head, Snout & Ears)
            val headNode = chest.child(0f, 0.16f, 0.22f, pitch = headPitch, yaw = headYaw)
            val pHeadBack = headNode.pt(0f, 0.05f, 0.0f)
            val pHeadFront = headNode.pt(0f, 0.05f, 0.22f)
            b.addSegment(pHeadBack[0], pHeadBack[1], pHeadBack[2], pHeadFront[0], pHeadFront[1], pHeadFront[2], 0.28f, 0.25f, 0.22f, 0.18f)

            // 口吻鼻翼 (Snout)
            val pSnoutTip = headNode.pt(0f, 0.02f, 0.30f)
            b.addSegment(pHeadFront[0], pHeadFront[1], pHeadFront[2], pSnoutTip[0], pSnoutTip[1], pSnoutTip[2], 0.14f, 0.10f, 0.05f, 0.04f)

            // 左猫耳 (Triangular Left Ear)
            val pLEarBase = headNode.pt(-0.09f, 0.16f, 0.08f)
            val pLEarTip = headNode.pt(-0.13f, 0.32f, 0.06f)
            b.addSegment(pLEarBase[0], pLEarBase[1], pLEarBase[2], pLEarTip[0], pLEarTip[1], pLEarTip[2], 0.11f, 0.06f, 0.02f, 0.02f)

            // 右猫耳 (Triangular Right Ear)
            val pREarBase = headNode.pt(0.09f, 0.16f, 0.08f)
            val pREarTip = headNode.pt(0.13f, 0.32f, 0.06f)
            b.addSegment(pREarBase[0], pREarBase[1], pREarBase[2], pREarTip[0], pREarTip[1], pREarTip[2], 0.11f, 0.06f, 0.02f, 0.02f)

            // 4. 左前肢与猫爪 (Front Left Paw)
            val flNode = chest.child(-0.13f, -0.05f, 0.14f, pitch = flLeg)
            val pFLStart = flNode.pt(0f, 0f, 0f)
            val pFLElbow = flNode.pt(0f, -0.20f, 0f)
            b.addSegment(pFLStart[0], pFLStart[1], pFLStart[2], pFLElbow[0], pFLElbow[1], pFLElbow[2], 0.10f, 0.10f, 0.08f, 0.08f)
            val pFLPaw = flNode.pt(0f, -0.38f, 0.03f)
            b.addSegment(pFLElbow[0], pFLElbow[1], pFLElbow[2], pFLPaw[0], pFLPaw[1], pFLPaw[2], 0.08f, 0.08f, 0.09f, 0.11f)

            // 5. 右前肢与猫爪 (Front Right Paw)
            val frNode = chest.child(0.13f, -0.05f, 0.14f, pitch = frLeg)
            val pFRStart = frNode.pt(0f, 0f, 0f)
            val pFRElbow = frNode.pt(0f, -0.20f, 0f)
            b.addSegment(pFRStart[0], pFRStart[1], pFRStart[2], pFRElbow[0], pFRElbow[1], pFRElbow[2], 0.10f, 0.10f, 0.08f, 0.08f)
            val pFRPaw = frNode.pt(0f, -0.38f, 0.03f)
            b.addSegment(pFRElbow[0], pFRElbow[1], pFRElbow[2], pFRPaw[0], pFRPaw[1], pFRPaw[2], 0.08f, 0.08f, 0.09f, 0.11f)

            // 6. 左后肢与猫爪 (Hind Left Leg & Paw)
            val hlNode = rearHip.child(-0.13f, -0.02f, -0.08f, pitch = hlLeg)
            val pHLStart = hlNode.pt(0f, 0f, 0f)
            val pHLKnee = hlNode.pt(0f, -0.20f, -0.04f)
            b.addSegment(pHLStart[0], pHLStart[1], pHLStart[2], pHLKnee[0], pHLKnee[1], pHLKnee[2], 0.14f, 0.15f, 0.10f, 0.10f)
            val pHLPaw = hlNode.pt(0f, -0.38f, 0.02f)
            b.addSegment(pHLKnee[0], pHLKnee[1], pHLKnee[2], pHLPaw[0], pHLPaw[1], pHLPaw[2], 0.09f, 0.09f, 0.10f, 0.12f)

            // 7. 右后肢与猫爪 (Hind Right Leg & Paw)
            val hrNode = rearHip.child(0.13f, -0.02f, -0.08f, pitch = hrLeg)
            val pHRStart = hrNode.pt(0f, 0f, 0f)
            val pHRKnee = hrNode.pt(0f, -0.20f, -0.04f)
            b.addSegment(pHRStart[0], pHRStart[1], pHRStart[2], pHRKnee[0], pHRKnee[1], pHRKnee[2], 0.14f, 0.15f, 0.10f, 0.10f)
            val pHRPaw = hrNode.pt(0f, -0.38f, 0.02f)
            b.addSegment(pHRKnee[0], pHRKnee[1], pHRKnee[2], pHRPaw[0], pHRPaw[1], pHRPaw[2], 0.09f, 0.09f, 0.10f, 0.12f)

            // 8. 灵动三节猫尾 (3-Segment Articulated Tail)
            val tail0 = rearHip.child(0f, 0.08f, -0.16f, pitch = tailPitch, yaw = tailYaw)
            val pTail0Start = tail0.pt(0f, 0f, 0f)
            val pTail0End = tail0.pt(0f, 0.15f, -0.14f)
            b.addSegment(pTail0Start[0], pTail0Start[1], pTail0Start[2], pTail0End[0], pTail0End[1], pTail0End[2], 0.07f, 0.07f, 0.06f, 0.06f)

            val tail1 = tail0.child(0f, 0.15f, -0.14f, pitch = 22f, yaw = 12f)
            val pTail1End = tail1.pt(0f, 0.16f, -0.10f)
            b.addSegment(pTail0End[0], pTail0End[1], pTail0End[2], pTail1End[0], pTail1End[1], pTail1End[2], 0.06f, 0.06f, 0.05f, 0.05f)

            val tail2 = tail1.child(0f, 0.16f, -0.10f, pitch = 22f, yaw = 8f)
            val pTail2End = tail2.pt(0f, 0.14f, 0.02f)
            b.addSegment(pTail1End[0], pTail1End[1], pTail1End[2], pTail2End[0], pTail2End[1], pTail2End[2], 0.05f, 0.05f, 0.03f, 0.03f)

            return b.build()
        }

        /**
         * 全身人体经典站姿体块素模 (八头身严谨比例，适用于全身透视与起稿打形)
         */
        fun createBodyMannequin(): Mesh3D {
            return createAnimeMannequin(isFemale = false, posePreset = "stand")
        }

        /**
         * 全身人体经典坐姿体块素模 (躯干与大腿呈直角折屈，便于推敲坐姿纵深透视)
         */
        fun createSittingMannequin(): Mesh3D {
            return createAnimeMannequin(isFemale = false, posePreset = "sit")
        }

        /**
         * 解析标准 Wavefront OBJ 文件流
         */
        fun parseObj(reader: BufferedReader): Mesh3D {
            // 全部改用不装箱的 primitive 动态数组: 旧实现用 ArrayList<Float>/<Int> +
            // HashMap<String, Int> (面 token 字符串当键), 大模型直接 OOM。
            val rawPositions = FloatGrow(4096)
            val rawNormals = FloatGrow(1024)
            val finalPositions = FloatGrow(4096)
            val finalNormals = FloatGrow(1024)
            val indices = IntGrow(8192)
            // (vIdx, vnIdx) -> 最终顶点下标; 不再把 "1/2/3" 这种字符串当键
            val vertexMap = LongLongMap(4096)

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.isEmpty() || l.startsWith("#")) continue

                val tokens = l.split(OBJ_TOKEN_SEPARATOR)
                if (tokens.isEmpty()) continue

                when (tokens[0]) {
                    "v" -> {
                        if (tokens.size >= 4) {
                            rawPositions.add(tokens[1].toFloat())
                            rawPositions.add(tokens[2].toFloat())
                            rawPositions.add(tokens[3].toFloat())
                        }
                    }
                    "vn" -> {
                        if (tokens.size >= 4) {
                            rawNormals.add(tokens[1].toFloat())
                            rawNormals.add(tokens[2].toFloat())
                            rawNormals.add(tokens[3].toFloat())
                        }
                    }
                    "f" -> {
                        if (tokens.size < 4) continue
                        val faceVerts = IntArray(tokens.size - 1)
                        for (k in 1 until tokens.size) {
                            val parts = tokens[k].split("/")
                            val vRaw = parts[0].toInt()
                            val rawVertexCount = rawPositions.size / 3
                            val vIdx = if (vRaw < 0) rawVertexCount + vRaw else vRaw - 1
                            val vnIdx = if (parts.size >= 3 && parts[2].isNotEmpty()) {
                                val vnRaw = parts[2].toInt()
                                val rawNormalCount = rawNormals.size / 3
                                if (vnRaw < 0) rawNormalCount + vnRaw else vnRaw - 1
                            } else {
                                -1
                            }
                            // 越界索引统一归一成 -1, 避免把负值塞进键里
                            val vSafe = if (vIdx in 0 until rawVertexCount) vIdx else -1
                            val vnSafe = if (vnIdx in 0 until rawNormals.size / 3) vnIdx else -1
                            val key = (vSafe.toLong() shl 32) or (vnSafe.toLong() and 0xFFFFFFFFL)

                            val cached = vertexMap.get(key)
                            if (cached >= 0) {
                                faceVerts[k - 1] = cached.toInt()
                            } else {
                                val newIdx = finalPositions.size / 3
                                if (newIdx >= MAX_OBJ_VERTICES) {
                                    throw MeshTooLargeException("模型顶点数超过上限 ($MAX_OBJ_VERTICES)")
                                }
                                if (vSafe >= 0) {
                                    finalPositions.add(rawPositions.data[vSafe * 3])
                                    finalPositions.add(rawPositions.data[vSafe * 3 + 1])
                                    finalPositions.add(rawPositions.data[vSafe * 3 + 2])
                                } else {
                                    finalPositions.add(0f); finalPositions.add(0f); finalPositions.add(0f)
                                }
                                if (vnSafe >= 0) {
                                    finalNormals.add(rawNormals.data[vnSafe * 3])
                                    finalNormals.add(rawNormals.data[vnSafe * 3 + 1])
                                    finalNormals.add(rawNormals.data[vnSafe * 3 + 2])
                                } else {
                                    finalNormals.add(0f); finalNormals.add(1f); finalNormals.add(0f)
                                }
                                vertexMap.put(key, newIdx.toLong())
                                faceVerts[k - 1] = newIdx
                            }
                        }

                        // 三角化扇面 (Fan triangulation)
                        for (k in 1 until faceVerts.size - 1) {
                            if (indices.size / 3 >= MAX_OBJ_TRIANGLES) {
                                throw MeshTooLargeException("模型三角面数超过上限 ($MAX_OBJ_TRIANGLES)")
                            }
                            indices.add(faceVerts[0])
                            indices.add(faceVerts[k])
                            indices.add(faceVerts[k + 1])
                        }
                    }
                }
            }

            val pArr = finalPositions.toArray()
            val nArr = finalNormals.toArray()
            val iArr = indices.toArray()
            val wArr = extractWireframeIndices(iArr)

            return Mesh3D(pArr, nArr, iArr, wArr)
        }    }
}
