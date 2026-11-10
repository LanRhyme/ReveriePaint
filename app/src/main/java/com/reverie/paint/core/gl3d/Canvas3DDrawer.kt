/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.gl3d

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.reverie.paint.model.*
import kotlin.math.*

/**
 * 极速 120Hz 原生 Canvas 3D 实时投影渲染器
 *
 * 专门用于触摸手势期间提供 0 延迟、0 丢帧、0 GC 内存分配的视口预览。
 * 彻底消除 glReadPixels 在 UI 线程上的 GPU 阻塞停顿。
 */
class Canvas3DDrawer {

    private var screenX = FloatArray(1024)
    private var screenY = FloatArray(1024)
    private var depthZ = FloatArray(1024)
    private var linePts = FloatArray(4096)

    private val triPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 深度排序: 用计数排序 (O(n + 桶数)) 取代插入排序 (9k 面时约 2x10^7 次比较, 每帧都在 UI 线程上跑)
    private var visibleTris = IntArray(1024)
    private var visibleDepths = FloatArray(1024)
    private var orderedTris = IntArray(1024)
    private val bucketCounts = IntArray(DEPTH_BUCKETS)
    private val bucketEnds = IntArray(DEPTH_BUCKETS)
    private var triangleIsFront = BooleanArray(1024)

    // 相机矩阵复用 (每帧 0 分配)
    private val projMat = FloatArray(16)
    private val viewMat = FloatArray(16)
    private val modelMat = FloatArray(16)
    private val rotationA = FloatArray(16)
    private val rotationB = FloatArray(16)
    private val mvMat = FloatArray(16)
    private val mvpMat = FloatArray(16)
    private var cachedAspect = -1f
    private var cachedDist = Float.NaN

    private fun ensureCapacity(vertCount: Int, lineCount: Int, triCount: Int) {
        if (screenX.size < vertCount) {
            val newCap = maxOf(vertCount * 2, 1024)
            screenX = FloatArray(newCap)
            screenY = FloatArray(newCap)
            depthZ = FloatArray(newCap)
        }
        val neededLineFloats = lineCount * 4
        if (linePts.size < neededLineFloats) {
            linePts = FloatArray(maxOf(neededLineFloats * 2, 4096))
        }
        if (visibleTris.size < triCount) {
            val newCap = maxOf(triCount * 2, 1024)
            visibleTris = IntArray(newCap)
            visibleDepths = FloatArray(newCap)
            orderedTris = IntArray(newCap)
            triangleIsFront = BooleanArray(newCap)
        }
    }

    /**
     * 复用的相机矩阵: 投影 (仅宽高比变化时重算) + 视图 (仅缩放变化时重算) + 模型变换
     */
    private fun computeMatrices(state: Model3DState, docW: Float, docH: Float) {
        val aspect = (docW / docH.coerceAtLeast(1f)).coerceAtLeast(0.01f)
        if (aspect != cachedAspect) {
            System.arraycopy(Math3D.perspective(45f, aspect, 0.1f, 100f), 0, projMat, 0, 16)
            cachedAspect = aspect
        }
        val dist = 3.5f / state.zoom.coerceIn(0.1f, 10f)
        if (dist != cachedDist) {
            System.arraycopy(Math3D.lookAt(0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f), 0, viewMat, 0, 16)
            cachedDist = dist
        }

        // model = T * Ry * Rx * Rz
        Math3D.rotationMatrixInto(rotationA, 0, state.rotY, 0f, 1f, 0f)
        Math3D.rotationMatrixInto(rotationB, 0, state.rotX, 1f, 0f, 0f)
        Math3D.multiplyMMInto(modelMat, 0, rotationA, 0, rotationB, 0)
        Math3D.rotationMatrixInto(rotationB, 0, state.rotZ, 0f, 0f, 1f)
        Math3D.multiplyMMInto(rotationA, 0, modelMat, 0, rotationB, 0)
        System.arraycopy(rotationA, 0, modelMat, 0, 16)
        // 平移项在旋转矩阵里恒为 0, 直接写入第四列等价于左乘平移矩阵
        modelMat[12] = state.panX * 2f
        modelMat[13] = state.panY * 2f
        modelMat[14] = 0f
        modelMat[15] = 1f

        Math3D.multiplyMMInto(mvMat, 0, viewMat, 0, modelMat, 0)
        Math3D.multiplyMMInto(mvpMat, 0, projMat, 0, mvMat, 0)
    }

    /**
     * 将 3D 网格直接以满帧率渲染至硬件加速的 Canvas 上 (在文档中心坐标系下)
     */
    fun draw(
        canvas: Canvas,
        mesh: Mesh3D,
        state: Model3DState,
        docW: Float,
        docH: Float,
        layerAlpha: Float = 1.0f,
    ) {
        val vertCount = mesh.vertexCount
        val triCount = mesh.triangleCount
        val lineCount = mesh.wireframeLineCount
        ensureCapacity(vertCount, lineCount, triCount)
        computeMatrices(state, docW, docH)

        val mv = mvMat
        val mvp = mvpMat

        // 提取 3x3 法线旋转矩阵
        val nm0 = mv[0]; val nm1 = mv[1]; val nm2 = mv[2]
        val nm4 = mv[4]; val nm5 = mv[5]; val nm6 = mv[6]
        val nm8 = mv[8]; val nm9 = mv[9]; val nm10 = mv[10]

        // 顶点投影变换 (对齐 0.5f 视口半长，与底层离屏光栅渲染尺寸保持 100% 绝对一致)
        val positions = mesh.positions
        val halfW = docW * 0.5f
        val halfH = docH * 0.5f

        for (i in 0 until vertCount) {
            val vx = positions[i * 3]
            val vy = positions[i * 3 + 1]
            val vz = positions[i * 3 + 2]

            val cx = mvp[0] * vx + mvp[4] * vy + mvp[8] * vz + mvp[12]
            val cy = mvp[1] * vx + mvp[5] * vy + mvp[9] * vz + mvp[13]
            val cz = mvp[2] * vx + mvp[6] * vy + mvp[10] * vz + mvp[14]
            val cw = mvp[3] * vx + mvp[7] * vy + mvp[11] * vz + mvp[15]

            val invW = if (abs(cw) > 1e-6f) 1f / cw else 1f
            screenX[i] = (cx * invW) * halfW
            screenY[i] = (-cy * invW) * halfH
            depthZ[i] = cz * invW
        }

        val baseCol = state.baseColor
        val baseRed = ((baseCol shr 16) and 0xFF).toInt()
        val baseGreen = ((baseCol shr 8) and 0xFF).toInt()
        val baseBlue = (baseCol and 0xFF).toInt()

        val lightDir = Math3D.calculateLightDirection(state.lightYaw, state.lightPitch)

        when (state.renderMode) {
            RenderMode3D.WIREFRAME -> {
                val indices = mesh.indices
                val edgeTris = mesh.wireframeEdgeTriangles
                val vertexCount = positions.size / 3

                // 标记正面朝向的三角形并收集深度
                var visibleCount = 0
                for (t in 0 until triCount) {
                    val i0 = indices[t * 3]
                    val i1 = indices[t * 3 + 1]
                    val i2 = indices[t * 3 + 2]
                    if (i0 !in 0 until vertexCount || i1 !in 0 until vertexCount || i2 !in 0 until vertexCount) {
                        continue
                    }

                    val x0 = screenX[i0]; val y0 = screenY[i0]
                    val x1 = screenX[i1]; val y1 = screenY[i1]
                    val x2 = screenX[i2]; val y2 = screenY[i2]

                    val cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
                    val isFront = cross < 0f
                    triangleIsFront[t] = isFront
                    if (isFront) {
                        visibleTris[visibleCount] = t
                        visibleDepths[visibleCount] = (depthZ[i0] + depthZ[i1] + depthZ[i2]) * 0.3333f
                        visibleCount++
                    }
                }
                sortVisible(visibleCount)

                // 1. 实心浅色底面遮挡背面杂线 (形成纯净易辨的实体参考)
                val baseA = (255 * layerAlpha.coerceIn(0f, 1f)).toInt()
                fillPaint.color = (baseA shl 24) or 0x00F5F6F8
                for (k in 0 until visibleCount) {
                    val t = orderedTris[k]
                    val i0 = indices[t * 3]
                    val i1 = indices[t * 3 + 1]
                    val i2 = indices[t * 3 + 2]
                    if (i0 !in 0 until vertexCount || i1 !in 0 until vertexCount || i2 !in 0 until vertexCount) {
                        continue
                    }

                    triPath.rewind()
                    triPath.moveTo(screenX[i0], screenY[i0])
                    triPath.lineTo(screenX[i1], screenY[i1])
                    triPath.lineTo(screenX[i2], screenY[i2])
                    triPath.close()
                    canvas.drawPath(triPath, fillPaint)
                }

                // 2. 筛选并绘制正面特征结构线段
                val wireBaseA = ((state.wireframeColor shr 24) and 0xFFL).toInt().let { if (it == 0) 255 else it }
                val wireFinalA = (wireBaseA * layerAlpha.coerceIn(0f, 1f)).toInt()
                wirePaint.color = (wireFinalA shl 24) or (state.wireframeColor.toInt() and 0x00FFFFFF)
                var pIdx = 0
                val wireIndices = mesh.wireframeIndices
                for (k in 0 until lineCount) {
                    val t0 = if (k * 2 < edgeTris.size) edgeTris[k * 2] else -1
                    val t1 = if (k * 2 + 1 < edgeTris.size) edgeTris[k * 2 + 1] else -1
                    val isVisible = (t0 in 0 until triCount && triangleIsFront[t0]) ||
                                    (t1 in 0 until triCount && triangleIsFront[t1]) ||
                                    (t0 < 0 && t1 < 0)

                    if (isVisible) {
                        val i1 = wireIndices[k * 2]
                        val i2 = wireIndices[k * 2 + 1]
                        if (i1 in 0 until vertexCount && i2 in 0 until vertexCount) {
                            linePts[pIdx++] = screenX[i1]
                            linePts[pIdx++] = screenY[i1]
                            linePts[pIdx++] = screenX[i2]
                            linePts[pIdx++] = screenY[i2]
                        }
                    }
                }
                if (pIdx > 0) {
                    canvas.drawLines(linePts, 0, pIdx, wirePaint)
                }
            }

            RenderMode3D.CLAY, RenderMode3D.SHADING -> {
                val indices = mesh.indices
                val normals = mesh.normals
                val hasNormals = normals.isNotEmpty()
                val isSphere = (state.modelType == "sphere" && hasNormals)
                val vertexCount = positions.size / 3

                // 背面剔除与深度排序收集
                var visibleCount = 0
                for (t in 0 until triCount) {
                    val i0 = indices[t * 3]
                    val i1 = indices[t * 3 + 1]
                    val i2 = indices[t * 3 + 2]
                    if (i0 !in 0 until vertexCount || i1 !in 0 until vertexCount || i2 !in 0 until vertexCount) {
                        continue
                    }

                    val x0 = screenX[i0]; val y0 = screenY[i0]
                    val x1 = screenX[i1]; val y1 = screenY[i1]
                    val x2 = screenX[i2]; val y2 = screenY[i2]

                    // 2D 叉积背面剔除 (Backface culling)
                    val cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
                    if (cross < 0f) {
                        visibleTris[visibleCount] = t
                        visibleDepths[visibleCount] = (depthZ[i0] + depthZ[i1] + depthZ[i2]) * 0.3333f
                        visibleCount++
                    }
                }

                // 画家算法按深度排序 (远到近)
                sortVisible(visibleCount)

                // 逐三角面着色绘制
                for (k in 0 until visibleCount) {
                    val t = orderedTris[k]
                    val i0 = indices[t * 3]
                    val i1 = indices[t * 3 + 1]
                    val i2 = indices[t * 3 + 2]
                    if (i0 !in 0 until vertexCount || i1 !in 0 until vertexCount || i2 !in 0 until vertexCount) {
                        continue
                    }

                    var nx = 0f; var ny = 0f; var nz = 1f

                    if (isSphere && i0 * 3 + 2 < normals.size) {
                        val rawNx = (normals[i0 * 3] + normals[i1 * 3] + normals[i2 * 3]) * 0.3333f
                        val rawNy = (normals[i0 * 3 + 1] + normals[i1 * 3 + 1] + normals[i2 * 3 + 1]) * 0.3333f
                        val rawNz = (normals[i0 * 3 + 2] + normals[i1 * 3 + 2] + normals[i2 * 3 + 2]) * 0.3333f

                        nx = nm0 * rawNx + nm4 * rawNy + nm8 * rawNz
                        ny = nm1 * rawNx + nm5 * rawNy + nm9 * rawNz
                        nz = nm2 * rawNx + nm6 * rawNy + nm10 * rawNz
                        val len = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-6f)
                        nx /= len; ny /= len; nz /= len
                    } else {
                        // 真实几何三角面法线 (硬朗分面，消除平滑模糊)
                        val p0x = positions[i0 * 3]; val p0y = positions[i0 * 3 + 1]; val p0z = positions[i0 * 3 + 2]
                        val p1x = positions[i1 * 3]; val p1y = positions[i1 * 3 + 1]; val p1z = positions[i1 * 3 + 2]
                        val p2x = positions[i2 * 3]; val p2y = positions[i2 * 3 + 1]; val p2z = positions[i2 * 3 + 2]

                        val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
                        val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z

                        var rawNx = e1y * e2z - e1z * e2y
                        var rawNy = e1z * e2x - e1x * e2z
                        var rawNz = e1x * e2y - e1y * e2x
                        val rlen = hypot(hypot(rawNx, rawNy), rawNz).coerceAtLeast(1e-6f)
                        rawNx /= rlen; rawNy /= rlen; rawNz /= rlen

                        nx = nm0 * rawNx + nm4 * rawNy + nm8 * rawNz
                        ny = nm1 * rawNx + nm5 * rawNy + nm9 * rawNz
                        nz = nm2 * rawNx + nm6 * rawNy + nm10 * rawNz
                        val nlen = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-6f)
                        nx /= nlen; ny /= nlen; nz /= nlen
                    }

                    val color = if (state.renderMode == RenderMode3D.CLAY) {
                        // 素模半球光
                        val hemi = (ny * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val skyR = 1.0f; val skyG = 0.98f; val skyB = 0.94f
                        val gndR = 0.68f; val gndG = 0.70f; val gndB = 0.76f
                        val ambR = gndR + (skyR - gndR) * hemi
                        val ambG = gndG + (skyG - gndG) * hemi
                        val ambB = gndB + (skyB - gndB) * hemi

                        // 主光使用用户的动态 lightDir
                        val diff = max(nx * lightDir[0] + ny * lightDir[1] + nz * lightDir[2], 0f)
                        val rim = (1.0f - max(nz, 0f)).coerceIn(0f, 1f).pow(2) * 0.22f

                        val r = (baseRed * (ambR * 0.5f + diff * 0.55f) + rim * 255f).toInt().coerceIn(0, 255)
                        val g = (baseGreen * (ambG * 0.5f + diff * 0.55f) + rim * 255f).toInt().coerceIn(0, 255)
                        val b = (baseBlue * (ambB * 0.5f + diff * 0.55f) + rim * 255f).toInt().coerceIn(0, 255)
                        val a = (255 * layerAlpha.coerceIn(0f, 1f)).toInt()
                        Color.argb(a, r, g, b)
                    } else {
                        // 光影明暗切面 (明确受光与背光体积)
                        val ndotl = nx * lightDir[0] + ny * lightDir[1] + nz * lightDir[2]
                        val diff = if (ndotl > 0.05f) 1.0f else 0.35f
                        val r = (baseRed * diff).toInt().coerceIn(0, 255)
                        val g = (baseGreen * diff).toInt().coerceIn(0, 255)
                        val b = (baseBlue * diff).toInt().coerceIn(0, 255)
                        val a = (255 * layerAlpha.coerceIn(0f, 1f)).toInt()
                        Color.argb(a, r, g, b)
                    }

                    fillPaint.color = color
                    triPath.rewind()
                    triPath.moveTo(screenX[i0], screenY[i0])
                    triPath.lineTo(screenX[i1], screenY[i1])
                    triPath.lineTo(screenX[i2], screenY[i2])
                    triPath.close()
                    canvas.drawPath(triPath, fillPaint)
                }
            }
        }
    }

    /**
     * 按三角形平均深度做"远 -> 近"排序。
     *
     * 旧实现是插入排序: 猫猫模型 9.5k 面时每帧约 2x10^7 次比较, 全部压在 UI 线程上。
     * 这里改成深度分桶计数排序: O(n + 桶数), 只做一次浮点量化 + 一次整数扫描。
     */
    private fun sortVisible(count: Int) {
        if (count <= 1) {
            if (count == 1) orderedTris[0] = visibleTris[0]
            return
        }

        var minD = Float.MAX_VALUE
        var maxD = -Float.MAX_VALUE
        for (i in 0 until count) {
            val d = visibleDepths[i]
            if (d < minD) minD = d
            if (d > maxD) maxD = d
        }

        val range = maxD - minD
        if (range < 1e-6f) {
            for (i in 0 until count) orderedTris[i] = visibleTris[i]
            return
        }

        java.util.Arrays.fill(bucketCounts, 0)
        val scale = (DEPTH_BUCKETS - 1).toFloat() / range
        for (i in 0 until count) {
            val b = ((maxD - visibleDepths[i]) * scale).toInt().coerceIn(0, DEPTH_BUCKETS - 1)
            bucketCounts[b]++
        }
        // 前缀和得到每个桶的结束位置 (桶 0 = 最远, 先画)
        var acc = 0
        for (b in 0 until DEPTH_BUCKETS) {
            acc += bucketCounts[b]
            bucketEnds[b] = acc
        }
        // 倒序回填保证同桶内相对次序稳定 (与插入排序一致)
        for (i in count - 1 downTo 0) {
            val b = ((maxD - visibleDepths[i]) * scale).toInt().coerceIn(0, DEPTH_BUCKETS - 1)
            bucketEnds[b]--
            orderedTris[bucketEnds[b]] = visibleTris[i]
        }
    }

    // ------------------------------------------------------------------
    // 骨架可视化与关节拾取
    // ------------------------------------------------------------------

    private var jointScreenX = FloatArray(64)
    private var jointScreenY = FloatArray(64)
    private var jointDepth = FloatArray(64)
    private var jointValid = BooleanArray(64)
    private var projectedCount = 0
    private var projectedJointIndices = IntArray(64)
    private var projectedJointNames: Array<String?> = arrayOfNulls(64)
    private var projectedParentJoint: IntArray = IntArray(64)
    private var projectedX = FloatArray(64)
    private var projectedY = FloatArray(64)
    private var projectedDepth = FloatArray(64)

    private val boneLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = 0xCC5B9BD5.toInt() // Morandi cyan
        strokeCap = Paint.Cap.ROUND
    }
    private val boneLineActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        color = 0xFFE07A5F.toInt() // Morandi terracotta accent
        strokeCap = Paint.Cap.ROUND
    }
    private val jointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val jointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = 0xFF2A2B2D.toInt()
    }
    private val gizmoRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xFFE07A5F.toInt() // Morandi terracotta accent
    }
    // ---- 选中骨骼的 X/Y 旋转轴环 (gizmo), 全程零分配 ----
    private val ringPitchPts = FloatArray(RING_SEG * 2)
    private val ringYawPts = FloatArray(RING_SEG * 2)
    private var ringPitchVisible = false
    private var ringYawVisible = false
    private val ringPath = Path()
    private val ringPitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = 0xFFC97B5A.toInt() // 俯仰环: 陶土色
    }
    private val ringYawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = 0xFF5B9BD5.toInt() // 偏转环: 莫兰迪蓝
    }

    private fun ensureJointCapacity(n: Int) {
        if (jointScreenX.size >= n) return
        val cap = maxOf(n * 2, 64)
        jointScreenX = FloatArray(cap)
        jointScreenY = FloatArray(cap)
        jointDepth = FloatArray(cap)
        jointValid = BooleanArray(cap)
        projectedJointIndices = IntArray(cap)
        projectedJointNames = arrayOfNulls(cap)
        projectedParentJoint = IntArray(cap)
        projectedX = FloatArray(cap)
        projectedY = FloatArray(cap)
        projectedDepth = FloatArray(cap)
    }

    /**
     * 画选中骨骼的两条旋转环 (点环即可锁定该轴再拖):
     * - 俯仰环 (绕局部 X, 陶土色): 由"骨骼方向 × 前后"张成的平面
     * - 偏转环 (绕骨轴 Y, 蓝色): 由"侧向 × 前后"张成的平面
     *
     * 轴向由当前姿态下的 骨 -> 子关节 方向反推, 环会跟着姿势一起转, 不依赖绑定姿态, 导入模型同样适用。
     */
    private fun drawAxisRings(
        canvas: Canvas,
        skeleton: Skeleton3D,
        worldJoints: FloatArray,
        selectedIndex: Int,
        mvp: FloatArray,
        halfW: Float,
        halfH: Float,
        lockedAxis: Int,
    ) {
        ringPitchVisible = false
        ringYawVisible = false
        if (selectedIndex < 0 || selectedIndex >= skeleton.count) return
        val jointCount = skeleton.count
        if (worldJoints.size < jointCount * 3) return

        val ox = worldJoints[selectedIndex * 3]
        val oy = worldJoints[selectedIndex * 3 + 1]
        val oz = worldJoints[selectedIndex * 3 + 2]

        var ax = 0f; var ay = 1f; var az = 0f
        val child = skeleton.firstChildJoint(selectedIndex)
        if (child >= 0) {
            ax = worldJoints[child * 3] - ox
            ay = worldJoints[child * 3 + 1] - oy
            az = worldJoints[child * 3 + 2] - oz
        } else {
            val parent = skeleton.joints[selectedIndex].parentIndex
            if (parent in 0 until jointCount) {
                ax = ox - worldJoints[parent * 3]
                ay = oy - worldJoints[parent * 3 + 1]
                az = oz - worldJoints[parent * 3 + 2]
            }
        }
        var len = hypot(hypot(ax, ay), az)
        if (len < 1e-5f) { ax = 0f; ay = 1f; az = 0f; len = 1f }
        ax /= len; ay /= len; az /= len

        // 侧向轴 = 骨轴 × 世界上方 (退化时退回 X 轴)
        var sx = -az
        var sy = 0f
        var sz = ax
        var slen = hypot(hypot(sx, sy), sz)
        if (slen < 1e-4f) { sx = 1f; sy = 0f; sz = 0f; slen = 1f }
        sx /= slen; sy /= slen; sz /= slen
        // 前后轴 = 侧向 × 骨轴
        val fx = sy * az - sz * ay
        val fy = sz * ax - sx * az
        val fz = sx * ay - sy * ax

        // 世界半径: 放大到 0.14 (原 0.085 在平板上偏小, 不好点)
        val r = 0.14f
        ringPitchVisible = buildRing(
            ox, oy, oz, ax * r, ay * r, az * r, fx * r, fy * r, fz * r,
            mvp, halfW, halfH, ringPitchPts,
        )
        ringYawVisible = buildRing(
            ox, oy, oz, sx * r, sy * r, sz * r, fx * r, fy * r, fz * r,
            mvp, halfW, halfH, ringYawPts,
        )

        drawRing(canvas, ringPitchPts, ringPitchVisible, ringPitchPaint, lockedAxis == 0, lockedAxis == 1)
        drawRing(canvas, ringYawPts, ringYawVisible, ringYawPaint, lockedAxis == 1, lockedAxis == 0)
    }

    private fun buildRing(
        cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        mvp: FloatArray, halfW: Float, halfH: Float,
        out: FloatArray,
    ): Boolean {
        var visible = true
        for (i in 0 until RING_SEG) {
            val ang = i * TWO_PI / RING_SEG
            val c = cos(ang)
            val sn = sin(ang)
            val wx = cx + ux * c + vx * sn
            val wy = cy + uy * c + vy * sn
            val wz = cz + uz * c + vz * sn
            val px = mvp[0] * wx + mvp[4] * wy + mvp[8] * wz + mvp[12]
            val py = mvp[1] * wx + mvp[5] * wy + mvp[9] * wz + mvp[13]
            val pw = mvp[3] * wx + mvp[7] * wy + mvp[11] * wz + mvp[15]
            if (pw <= 0.05f) {
                visible = false
            } else {
                out[i * 2] = (px / pw) * halfW
                out[i * 2 + 1] = (-py / pw) * halfH
            }
        }
        return visible
    }

    private fun drawRing(
        canvas: Canvas,
        pts: FloatArray,
        visible: Boolean,
        paint: Paint,
        locked: Boolean,
        dimmed: Boolean,
    ) {
        if (!visible) return
        paint.strokeWidth = if (locked) 4.0f else 2.2f
        paint.alpha = if (dimmed) 70 else 255
        ringPath.rewind()
        ringPath.moveTo(pts[0], pts[1])
        for (i in 1 until RING_SEG) ringPath.lineTo(pts[i * 2], pts[i * 2 + 1])
        ringPath.close()
        canvas.drawPath(ringPath, paint)
    }

    /**
     * 触摸点是否落在某条轴环上: 0 = 俯仰环, 1 = 偏转环, -1 = 都没点中
     */
    fun hitTestAxisRing(docX: Float, docY: Float, tolerance: Float): Int {
        var bestAxis = -1
        var best = tolerance
        if (ringPitchVisible) {
            val d = distanceToRing(ringPitchPts, docX, docY)
            if (d < best) { best = d; bestAxis = 0 }
        }
        if (ringYawVisible) {
            val d = distanceToRing(ringYawPts, docX, docY)
            if (d < best) { best = d; bestAxis = 1 }
        }
        return bestAxis
    }

    private fun distanceToRing(pts: FloatArray, px: Float, py: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until RING_SEG) {
            val d = hypot(px - pts[i * 2], py - pts[i * 2 + 1])
            if (d < best) best = d
        }
        return best
    }

    /**
     * 按屏幕距离拾取关节; 距离接近时优先选择离相机更近 (深度更小) 的关节,
     * 避免多个关节重叠时总是选中先遍历到的那个。
     *
     * 关节圆点没打中时退化为"骨骼线段"拾取: 沿骨头拖动即可选中该骨 (子关节), 平板上更好点。
     */
    fun hitTestJoint(docX: Float, docY: Float, maxDist: Float = 36f): String? {
        var bestIdx = -1
        var bestDist = maxDist
        var bestDepth = Float.MAX_VALUE
        for (k in 0 until projectedCount) {
            val d = hypot(docX - projectedX[k], docY - projectedY[k])
            if (d > maxDist) continue
            val depth = projectedDepth[k]
            val better = when {
                bestIdx < 0 -> true
                d < bestDist - 3f -> true
                abs(d - bestDist) <= 3f && depth < bestDepth -> true
                else -> false
            }
            if (better) {
                bestIdx = k
                bestDist = d
                bestDepth = depth
            }
        }
        if (bestIdx >= 0) return projectedJointNames[bestIdx]

        // 骨段拾取: 点到线段距离, 阈值放宽到 1.7 倍
        val segmentMax = maxDist * 1.7f
        var bestSegmentDist = segmentMax
        for (k in 0 until projectedCount) {
            val parent = projectedParentJoint[k]
            if (parent !in jointValid.indices || !jointValid[parent]) continue
            val d = pointSegmentDistance(
                docX, docY,
                jointScreenX[parent], jointScreenY[parent],
                projectedX[k], projectedY[k],
            )
            if (d < bestSegmentDist) {
                bestSegmentDist = d
                bestIdx = k
            }
        }
        return if (bestIdx >= 0) projectedJointNames[bestIdx] else null
    }

    private fun pointSegmentDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val lenSq = vx * vx + vy * vy
        if (lenSq < 1e-4f) return hypot(px - ax, py - ay)
        var t = ((px - ax) * vx + (py - ay) * vy) / lenSq
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    /**
     * 在视口模型上方绘制半透明 3D 骨架手柄与旋转环
     */
    fun drawBones(
        canvas: Canvas,
        skeleton: Skeleton3D?,
        worldJoints: FloatArray?,
        selectedJointName: String?,
        docW: Float,
        docH: Float,
        state: Model3DState,
        lockedAxis: Int = -1,
    ) {
        projectedCount = 0
        if (skeleton == null || worldJoints == null || skeleton.count == 0) return
        if (worldJoints.size < skeleton.count * 3) return

        computeMatrices(state, docW, docH)
        val mvp = mvpMat
        val halfW = docW * 0.5f
        val halfH = docH * 0.5f
        val jointCount = skeleton.count
        ensureJointCapacity(jointCount)

        var selectedIndex = -1
        for (j in 0 until jointCount) {
            val wx = worldJoints[j * 3]
            val wy = worldJoints[j * 3 + 1]
            val wz = worldJoints[j * 3 + 2]

            val cx = mvp[0] * wx + mvp[4] * wy + mvp[8] * wz + mvp[12]
            val cy = mvp[1] * wx + mvp[5] * wy + mvp[9] * wz + mvp[13]
            val cz = mvp[2] * wx + mvp[6] * wy + mvp[10] * wz + mvp[14]
            val cw = mvp[3] * wx + mvp[7] * wy + mvp[11] * wz + mvp[15]

            val jointName = skeleton.joints[j].name
            jointValid[j] = false
            if (jointName.equals("neutral_bone", ignoreCase = true) || jointName.startsWith("neutral", ignoreCase = true)) {
                continue
            }
            if (cw > 0.05f) {
                jointScreenX[j] = (cx / cw) * halfW
                jointScreenY[j] = (-cy / cw) * halfH
                jointDepth[j] = cz / cw
                jointValid[j] = true
                projectedJointIndices[projectedCount] = j
                projectedJointNames[projectedCount] = jointName
                projectedParentJoint[projectedCount] = skeleton.joints[j].parentIndex
                projectedX[projectedCount] = jointScreenX[j]
                projectedY[projectedCount] = jointScreenY[j]
                projectedDepth[projectedCount] = jointDepth[j]
                projectedCount++
                if (selectedIndex < 0 && selectedJointName != null &&
                    jointName.equals(selectedJointName, ignoreCase = true)
                ) {
                    selectedIndex = j
                }
            }
        }

        // 1. 绘制骨架连接线 (选中关节的父子链高亮)
        for (j in 0 until jointCount) {
            if (!jointValid[j]) continue
            val pIdx = skeleton.joints[j].parentIndex
            if (pIdx in 0 until jointCount && jointValid[pIdx]) {
                val active = selectedIndex >= 0 && (j == selectedIndex || pIdx == selectedIndex)
                canvas.drawLine(
                    jointScreenX[pIdx], jointScreenY[pIdx],
                    jointScreenX[j], jointScreenY[j],
                    if (active) boneLineActivePaint else boneLinePaint,
                )
            }
        }

        // 2. 绘制关节点交互圆圈
        val selectedRadius = 10.0f
        for (k in 0 until projectedCount) {
            val j = projectedJointIndices[k]
            val x = projectedX[k]
            val y = projectedY[k]
            val isSelected = j == selectedIndex

            if (isSelected) {
                canvas.drawCircle(x, y, 26.0f, gizmoRingPaint)
                jointFillPaint.color = 0xFFE07A5F.toInt() // Accent
            } else {
                jointFillPaint.color = 0xFFF4F1DE.toInt() // Morandi warm white
            }
            val radius = if (isSelected) selectedRadius else 7.0f
            canvas.drawCircle(x, y, radius, jointFillPaint)
            canvas.drawCircle(x, y, radius, jointStrokePaint)
        }

        // 3. 选中关节的 X/Y 旋转轴环: 点环锁定该轴, 拖动只改该轴
        drawAxisRings(canvas, skeleton, worldJoints, selectedIndex, mvp, halfW, halfH, lockedAxis)

    }

    private companion object {
        /** 深度分桶数量: 1k 桶 = 深度精度 ~0.2% 视深差, 画面上完全不可分辨 */
        const val DEPTH_BUCKETS = 1024
        /** 轴环采样段数 */
        const val RING_SEG = 40
        const val TWO_PI = 6.2831855f
    }
}
