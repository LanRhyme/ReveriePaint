/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import com.reverie.paint.model.CelSteps
import com.reverie.paint.model.ShadingAssistConfig
import com.reverie.paint.model.ShadingMode
import com.reverie.paint.model.ShadingPreviewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 智能阴影辅助推理与光照计算核心引擎。
 * 支持 ONNX 神经网络法线预测与内置 2.5D 距离场几何法线双轨工作流，
 * 并在全分辨率下执行高精度光照着色与线稿遮挡运算。
 */
object ShadingAssistEngine {

    private const val TAG = "ShadingAssistEngine"

    /**
     * 法线向量场数据容器
     * normals 数组按 [x, y, z] 交错存储，长度为 width * height * 3
     */
    class NormalField(val width: Int, val height: Int, val normals: FloatArray) {
        fun getNormal(x: Int, y: Int, outVec: FloatArray) {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            val idx = (cy * width + cx) * 3
            outVec[0] = normals[idx]
            outVec[1] = normals[idx + 1]
            outVec[2] = normals[idx + 2]
        }
    }

    /**
     * 神经网络输出的原始深度与法线数据缓存容器
     */
    data class RawDepthData(
        val width: Int,
        val height: Int,
        val rawDepth: FloatArray,
        val isDirectNormals: Boolean = false,
        val rawNormals: FloatArray? = null
    )

    /**
     * 生成表面法线/梯度向量场：若开启 useAiModel 且模型就绪则尝试 ONNX 推理，默认使用 CSP 宏观平滑体积梯度场
     */
    suspend fun generateNormalField(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        modelFile: File?,
        useAiModel: Boolean = false,
        volumeSmoothness: Float = 30.0f,
        targetResolution: Int = 1024,
        rollingBallContour: Boolean = true,
        rollingBallRadius: Int = 8
    ): NormalField = withContext(Dispatchers.Default) {
        if (useAiModel && modelFile != null && modelFile.exists() && modelFile.length() > 0) {
            try {
                val rawDepth = estimateRawDepth(baseBitmap, lineBitmap, modelFile, targetResolution)
                if (rawDepth != null) {
                    return@withContext computeNormalFieldFromDepth(rawDepth, volumeSmoothness)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ONNX inference failed, smoothly falling back to CSP normal field", e)
            }
        }
        generateCspNormalField(baseBitmap, volumeSmoothness, targetResolution, rollingBallContour, rollingBallRadius)
    }

    /**
     * 执行端侧神经网络推理并提取原始深度或法线数据 (仅在图像内容改变时执行一次)
     */
    suspend fun estimateRawDepth(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        modelFile: File,
        targetResolution: Int = 1024
    ): RawDepthData? = withContext(Dispatchers.Default) {
        if (baseBitmap.isRecycled) return@withContext null
        if (!modelFile.exists() || modelFile.length() <= 0) return@withContext null

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        try {
            opts.setIntraOpNumThreads(4)
            env.createSession(modelFile.absolutePath, opts).use { sess ->
                val inputInfo = sess.inputInfo
                if (inputInfo.isEmpty()) return@withContext null
                val inputName = inputInfo.keys.first()

                val isDepthAnything = modelFile.name.contains("depth_anything", ignoreCase = true)
                val patchSize = if (isDepthAnything) 14 else 32
                val maxRes = if (isDepthAnything) 518 else targetResolution

                // 等比例缩放并对齐为 patchSize 的整数倍 (Depth Anything V2 严格要求宽高为 14 的倍数)
                val scale = min(maxRes.toFloat() / baseBitmap.width, maxRes.toFloat() / baseBitmap.height).coerceAtMost(1.0f)
                val alignW = (((baseBitmap.width * scale).toInt() / patchSize) * patchSize).coerceAtLeast(patchSize)
                val alignH = (((baseBitmap.height * scale).toInt() / patchSize) * patchSize).coerceAtLeast(patchSize)

                // 读取模型期望的固定尺寸 (如有)
                val inTensorInfo = inputInfo.values.first().info as? ai.onnxruntime.TensorInfo
                val inShape = inTensorInfo?.shape
                val modelReqH = if (inShape != null && inShape.size >= 4 && inShape[2] > 0) inShape[2].toInt() else -1
                val modelReqW = if (inShape != null && inShape.size >= 4 && inShape[3] > 0) inShape[3].toInt() else -1

                val targetW = if (modelReqW > 0) modelReqW else alignW
                val targetH = if (modelReqH > 0) modelReqH else alignH

                val scaledBase = if (baseBitmap.width == targetW && baseBitmap.height == targetH) {
                    baseBitmap
                } else {
                    Bitmap.createScaledBitmap(baseBitmap, targetW, targetH, true)
                }

                // 合成底色与线稿 (白底 + 底色 + 线稿)，确保神经网络能完整感知发丝、五官面部与衣褶细节
                val compBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val compCanvas = android.graphics.Canvas(compBmp)
                compCanvas.drawColor(android.graphics.Color.WHITE)
                compCanvas.drawBitmap(scaledBase, 0f, 0f, null)
                if (scaledBase != baseBitmap) scaledBase.recycle()

                if (lineBitmap != null && !lineBitmap.isRecycled) {
                    val scaledLine = if (lineBitmap.width == targetW && lineBitmap.height == targetH) {
                        lineBitmap
                    } else {
                        Bitmap.createScaledBitmap(lineBitmap, targetW, targetH, true)
                    }
                    compCanvas.drawBitmap(scaledLine, 0f, 0f, null)
                    if (scaledLine != lineBitmap) scaledLine.recycle()
                }

                val scaledPixels = IntArray(targetW * targetH)
                compBmp.getPixels(scaledPixels, 0, targetW, 0, 0, targetW, targetH)
                compBmp.recycle()

                // NCHW: 1 x 3 x H x W
                val floatBuffer = FloatBuffer.allocate(1 * 3 * targetH * targetW)
                val channelSize = targetW * targetH

                val isImageNet = (modelReqH == 256 && modelReqW == 256) ||
                    modelFile.name.contains("midas", ignoreCase = true) ||
                    isDepthAnything
                val mean = if (isImageNet) floatArrayOf(0.485f, 0.456f, 0.406f) else floatArrayOf(0f, 0f, 0f)
                val std = if (isImageNet) floatArrayOf(0.229f, 0.224f, 0.225f) else floatArrayOf(1f, 1f, 1f)

                // R channel
                for (i in 0 until channelSize) {
                    val r = ((scaledPixels[i] shr 16) and 0xFF) / 255.0f
                    floatBuffer.put(i, (r - mean[0]) / std[0])
                }
                // G channel
                for (i in 0 until channelSize) {
                    val g = ((scaledPixels[i] shr 8) and 0xFF) / 255.0f
                    floatBuffer.put(channelSize + i, (g - mean[1]) / std[1])
                }
                // B channel
                for (i in 0 until channelSize) {
                    val b = (scaledPixels[i] and 0xFF) / 255.0f
                    floatBuffer.put(channelSize * 2 + i, (b - mean[2]) / std[2])
                }

                val tensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
                tensor.use { inTensor ->
                    sess.run(mapOf(inputName to inTensor)).use { res ->
                        if (res.size() == 0) return@withContext null
                        val outTensor = res.get(0) as? OnnxTensor ?: return@withContext null
                        val fb = outTensor.floatBuffer
                        val shape = outTensor.info.shape
                        val channels = if (shape.size >= 4) shape[1].toInt() else if (shape.size == 3 && shape[0] == 1L) 1 else 3
                        val outH = if (shape.size >= 4) shape[2].toInt() else if (shape.size >= 3) shape[1].toInt() else targetH
                        val outW = if (shape.size >= 4) shape[3].toInt() else if (shape.size >= 3) shape[2].toInt() else targetW
                        val planeSize = outH * outW

                        if (channels >= 3) {
                            // 3 通道直接法线图输出 (如 AnimeNormal)
                            val normalArray = FloatArray(outW * outH * 3)
                            var sumX = 0f
                            var sumY = 0f
                            val checkCount = min(1000, planeSize)
                            for (k in 0 until checkCount) {
                                sumX += fb.get(k)
                                sumY += fb.get(planeSize + k)
                            }
                            val meanX = sumX / checkCount
                            val meanY = sumY / checkCount
                            val isUnitEncoding = meanX in 0.20f..0.80f && meanY in 0.20f..0.80f

                            var idx = 0
                            for (y in 0 until outH) {
                                val yOffset = y * outW
                                for (x in 0 until outW) {
                                    val pIdx = yOffset + x
                                    val rawX = fb.get(pIdx)
                                    val rawY = fb.get(planeSize + pIdx)
                                    val rawZ = fb.get(planeSize * 2 + pIdx)

                                    val nx = if (isUnitEncoding) rawX * 2.0f - 1.0f else rawX
                                    // OpenGL 标准切线法线图中 +Y 朝上，Android 屏幕坐标系中 +Y 朝下，故反转 Y 轴
                                    val ny = if (isUnitEncoding) -(rawY * 2.0f - 1.0f) else -rawY
                                    val nz = (if (isUnitEncoding) max(rawZ * 2.0f - 1.0f, 0.05f) else max(rawZ, 0.05f))

                                    val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
                                    normalArray[idx++] = nx / len
                                    normalArray[idx++] = ny / len
                                    normalArray[idx++] = nz / len
                                }
                            }
                            return@withContext RawDepthData(outW, outH, FloatArray(0), isDirectNormals = true, rawNormals = normalArray)
                        } else {
                            // 1 通道深度图输出 (Depth Anything V2 / MiDaS)
                            val depthMap = FloatArray(planeSize)
                            for (k in 0 until planeSize) {
                                depthMap[k] = fb.get(k)
                            }
                            return@withContext RawDepthData(outW, outH, depthMap, isDirectNormals = false)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "estimateRawDepth failed with model: ${modelFile.name}", e)
            return@withContext null
        } finally {
            try {
                opts.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 根据缓存的原始深度图快速重算法线场 (耗时约 3ms，无须重新执行 ONNX 模型推理)
     */
    fun computeNormalFieldFromDepth(
        rawDepthData: RawDepthData,
        volumeSmoothness: Float = 30.0f
    ): NormalField {
        val outW = rawDepthData.width
        val outH = rawDepthData.height
        val planeSize = outW * outH

        if (rawDepthData.isDirectNormals && rawDepthData.rawNormals != null) {
            return NormalField(outW, outH, rawDepthData.rawNormals)
        }

        val depthMap = rawDepthData.rawDepth.clone()
        val tempBuffer = FloatArray(planeSize)

        // 1. 深度图多轮滤波，消除 ViT 14x14 Patch 的离散量化阶梯
        val blurRadiusDepth = ((volumeSmoothness * outW) / 512.0f).toInt().coerceIn(4, 16)
        repeat(3) {
            boxBlurHorizontal(depthMap, tempBuffer, outW, outH, blurRadiusDepth)
            boxBlurVertical(tempBuffer, depthMap, outW, outH, blurRadiusDepth)
        }

        // 2. 梯度计算 (中心差分)
        val gradX = FloatArray(planeSize)
        val gradY = FloatArray(planeSize)
        for (y in 0 until outH) {
            val yPrev = max(0, y - 1) * outW
            val yCurr = y * outW
            val yNext = min(outH - 1, y + 1) * outW

            for (x in 0 until outW) {
                val xPrev = max(0, x - 1)
                val xNext = min(outW - 1, x + 1)

                gradX[yCurr + x] = (depthMap[yCurr + xNext] - depthMap[yCurr + xPrev]) * 0.5f
                gradY[yCurr + x] = (depthMap[yNext + x] - depthMap[yPrev + x]) * 0.5f
            }
        }

        // 3. 梯度微平滑消除导数毛刺
        boxBlurHorizontal(gradX, tempBuffer, outW, outH, 2)
        boxBlurVertical(tempBuffer, gradX, outW, outH, 2)
        boxBlurHorizontal(gradY, tempBuffer, outW, outH, 2)
        boxBlurVertical(tempBuffer, gradY, outW, outH, 2)

        // 4. 自适应梯度归一化：使得物体轮廓与球形弧面具有饱满的 [-1, 1] 2D 偏转
        var sumMag = 0.0
        var nonZeroCount = 0
        for (k in 0 until planeSize) {
            val gx = gradX[k]
            val gy = gradY[k]
            val mag = sqrt(gx * gx + gy * gy)
            if (mag > 1e-4f) {
                sumMag += mag
                nonZeroCount++
            }
        }
        val meanMag = if (nonZeroCount > 0) (sumMag / nonZeroCount).toFloat() else 1e-3f
        val normalScale = 1.0f / (meanMag * 2.4f).coerceAtLeast(1e-4f)

        var idx = 0
        val normalArray = FloatArray(planeSize * 3)
        for (i in 0 until planeSize) {
            // 深度图中数值越大离相机越近 (视差)，表面法线向外指，梯度必须取反 (-dz/dx, -dz/dy)
            val nx = -gradX[i] * normalScale
            val ny = -gradY[i] * normalScale
            val nz = 0.45f

            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
            normalArray[idx++] = nx / len
            normalArray[idx++] = ny / len
            normalArray[idx++] = nz / len
        }

        return NormalField(outW, outH, normalArray)
    }

    /** 对法线场进行轻度各向同性盒状平滑并重新归一化 */
    private fun smoothNormalField(normals: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val count = w * h
        val temp = FloatArray(count * 3)
        val span = (radius * 2 + 1)
        val ispan = 1.0f / span

        // 水平平滑
        for (y in 0 until h) {
            val row = y * w
            for (c in 0..2) {
                var sum = 0f
                for (kx in -radius..radius) {
                    val px = kx.coerceIn(0, w - 1)
                    sum += normals[(row + px) * 3 + c]
                }
                temp[(row + 0) * 3 + c] = sum * ispan

                for (x in 1 until w) {
                    val addX = min(w - 1, x + radius)
                    val subX = max(0, x - radius - 1)
                    sum += normals[(row + addX) * 3 + c] - normals[(row + subX) * 3 + c]
                    temp[(row + x) * 3 + c] = sum * ispan
                }
            }
        }

        // 垂直平滑并重新归一化
        for (x in 0 until w) {
            for (c in 0..2) {
                var sum = 0f
                for (ky in -radius..radius) {
                    val py = ky.coerceIn(0, h - 1)
                    sum += temp[(py * w + x) * 3 + c]
                }
                normals[(0 * w + x) * 3 + c] = sum * ispan

                for (y in 1 until h) {
                    val addY = min(h - 1, y + radius)
                    val subY = max(0, y - radius - 1)
                    sum += temp[(addY * w + x) * 3 + c] - temp[(subY * w + x) * 3 + c]
                    normals[(y * w + x) * 3 + c] = sum * ispan
                }
            }
        }

        // 重新单位化
        for (i in 0 until count) {
            val baseIdx = i * 3
            val nx = normals[baseIdx]
            val ny = normals[baseIdx + 1]
            val nz = normals[baseIdx + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
            normals[baseIdx] = nx / len
            normals[baseIdx + 1] = ny / len
            normals[baseIdx + 2] = nz / len
        }
    }

    /**
     * CSP 风格宏观体积曲面梯度场生成器 (Clip Studio Paint 纯净赛璐珞核心算法)
     * 仅基于底色外轮廓距离场与大半径平滑，彻底消除局部斑块、噪点与凹陷
     */
    fun generateCspNormalField(
        baseBitmap: Bitmap,
        volumeSmoothness: Float = 30.0f,
        targetResolution: Int = 1024,
        rollingBallContour: Boolean = true,
        rollingBallRadius: Int = 8
    ): NormalField {
        val w = min(baseBitmap.width, targetResolution).coerceAtLeast(64)
        val h = min(baseBitmap.height, targetResolution).coerceAtLeast(64)

        val scaledBase = if (baseBitmap.width == w && baseBitmap.height == h) {
            baseBitmap
        } else {
            Bitmap.createScaledBitmap(baseBitmap, w, h, true)
        }

        val basePixels = IntArray(w * h)
        scaledBase.getPixels(basePixels, 0, w, 0, 0, w, h)
        if (scaledBase != baseBitmap) scaledBase.recycle()

        // 1. 构建底色外部边界并求解欧氏距离场 (从画布透明/背景处向内扩散)
        val isForeground = BooleanArray(w * h)
        var hasTransparent = false
        for (p in 0 until w * h) {
            val a = (basePixels[p] ushr 24) and 0xFF
            if (a < 25) {
                hasTransparent = true
            } else {
                isForeground[p] = true
            }
        }

        // 若整张画布全不透明 (如在白底平铺背景上绘制角色)，通过亮度区分亮背景与角色前景
        if (!hasTransparent) {
            var hasWhiteBg = false
            for (p in 0 until w * h) {
                val pix = basePixels[p]
                val r = (pix shr 16) and 0xFF
                val g = (pix shr 8) and 0xFF
                val b = pix and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= 245) {
                    isForeground[p] = false
                    hasWhiteBg = true
                } else {
                    isForeground[p] = true
                }
            }
            if (!hasWhiteBg) {
                // 若全图都是深色无背景，降级为将画布最外圈边缘作为背景边界
                for (x in 0 until w) {
                    isForeground[0 * w + x] = false
                    isForeground[(h - 1) * w + x] = false
                }
                for (y in 0 until h) {
                    isForeground[y * w + 0] = false
                    isForeground[y * w + (w - 1)] = false
                }
            }
        }

        // 原理 1: 轮廓滚球闭合 (Rolling Ball Silhouette Closure)
        // 通过形态学闭合运算 (膨胀后腐蚀) 自动闭合断裂线稿或发丝缝隙，杜绝距离场向内漏气
        val closedForeground = if (rollingBallContour) {
            val scaledRad = ((rollingBallRadius * w) / 512.0f).toInt().coerceIn(2, 24)
            morphologicalClose(isForeground, w, h, scaledRad)
        } else {
            isForeground
        }

        val dist = FloatArray(w * h) { if (closedForeground[it]) 1e6f else 0.0f }
        computeDistanceField(dist, w, h)

        // 2. Sobel 梯度
        val gradX = FloatArray(w * h)
        val gradY = FloatArray(w * h)
        for (y in 0 until h) {
            val yPrev = max(0, y - 1) * w
            val yCurr = y * w
            val yNext = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xPrev = max(0, x - 1)
                val xNext = min(w - 1, x + 1)

                val dx = (dist[yPrev + xNext] + 2f * dist[yCurr + xNext] + dist[yNext + xNext]) -
                         (dist[yPrev + xPrev] + 2f * dist[yCurr + xPrev] + dist[yNext + xPrev])
                val dy = (dist[yNext + xPrev] + 2f * dist[yNext + x] + dist[yNext + xNext]) -
                         (dist[yPrev + xPrev] + 2f * dist[yPrev + x] + dist[yPrev + xNext])

                val p = yCurr + x
                gradX[p] = dx
                gradY[p] = dy
            }
        }

        // 3. 宏观高阶平滑 (利用 3 轮可分离盒状平滑近似高斯滤波)
        val blurRadius = ((volumeSmoothness * w) / 512.0f).toInt().coerceIn(6, 60)
        val tempMap = FloatArray(w * h)
        repeat(3) {
            boxBlurHorizontal(gradX, tempMap, w, h, blurRadius)
            boxBlurVertical(tempMap, gradX, w, h, blurRadius)
            boxBlurHorizontal(gradY, tempMap, w, h, blurRadius)
            boxBlurVertical(tempMap, gradY, w, h, blurRadius)
        }

        // 4. 归一化为 2.5D 平面法线场 (Nz=0.35f 拱起微体积)
        val normals = FloatArray(w * h * 3)
        val nzVal = 0.35f
        for (i in 0 until w * h) {
            val gx = gradX[i]
            val gy = gradY[i]
            val len = sqrt(gx * gx + gy * gy + nzVal * nzVal).coerceAtLeast(1e-4f)
            val baseIdx = i * 3
            normals[baseIdx] = gx / len
            normals[baseIdx + 1] = gy / len
            normals[baseIdx + 2] = nzVal / len
        }

        return NormalField(w, h, normals)
    }

    /** 兼容历史签名的合成法线入口 */
    fun generateSyntheticNormalField(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        targetResolution: Int = 1024
    ): NormalField = generateCspNormalField(baseBitmap, 30.0f, targetResolution)

    private fun computeDistanceField(dist: FloatArray, w: Int, h: Int) {
        // 前向扫描
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                var d = dist[idx]
                if (d > 0f) {
                    if (x > 0) d = min(d, dist[idx - 1] + 1.0f)
                    if (y > 0) {
                        d = min(d, dist[idx - w] + 1.0f)
                        if (x > 0) d = min(d, dist[idx - w - 1] + 1.414f)
                        if (x < w - 1) d = min(d, dist[idx - w + 1] + 1.414f)
                    }
                    dist[idx] = d
                }
            }
        }
        // 反向扫描
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val idx = row + x
                var d = dist[idx]
                if (d > 0f) {
                    if (x < w - 1) d = min(d, dist[idx + 1] + 1.0f)
                    if (y < h - 1) {
                        d = min(d, dist[idx + w] + 1.0f)
                        if (x > 0) d = min(d, dist[idx + w - 1] + 1.414f)
                        if (x < w - 1) d = min(d, dist[idx + w + 1] + 1.414f)
                    }
                    dist[idx] = d
                }
            }
        }
    }

    private fun boxBlurHorizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val iarr = 1.0f / (r + r + 1)
        for (y in 0 until h) {
            val offset = y * w
            var sum = 0.0f
            for (x in -r..r) {
                sum += src[offset + x.coerceIn(0, w - 1)]
            }
            dst[offset] = sum * iarr
            for (x in 1 until w) {
                sum += src[offset + min(w - 1, x + r)] - src[offset + max(0, x - r - 1)]
                dst[offset + x] = sum * iarr
            }
        }
    }

    private fun boxBlurVertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val iarr = 1.0f / (r + r + 1)
        for (x in 0 until w) {
            var sum = 0.0f
            for (y in -r..r) {
                sum += src[y.coerceIn(0, h - 1) * w + x]
            }
            dst[x] = sum * iarr
            for (y in 1 until h) {
                sum += src[min(h - 1, y + r) * w + x] - src[max(0, y - r - 1) * w + x]
                dst[y * w + x] = sum * iarr
            }
        }
    }

    /**
     * 形态学闭合运算 (Dilation followed by Erosion)
     * 滚球闭合断裂线稿或缝隙，使距离场不向内漏气
     */
    private fun morphologicalClose(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask
        val r = radius.coerceIn(1, 24)
        val temp = BooleanArray(w * h)
        val dilated = BooleanArray(w * h)

        // 水平膨胀 (滑动窗口 O(1))
        for (y in 0 until h) {
            val row = y * w
            var activeCount = 0
            for (x in 0 until min(w, r + 1)) {
                if (mask[row + x]) activeCount++
            }
            for (x in 0 until w) {
                temp[row + x] = (activeCount > 0)
                val removeX = x - r
                if (removeX >= 0 && mask[row + removeX]) activeCount--
                val addX = x + r + 1
                if (addX < w && mask[row + addX]) activeCount++
            }
        }

        // 垂直膨胀
        for (x in 0 until w) {
            var activeCount = 0
            for (y in 0 until min(h, r + 1)) {
                if (temp[y * w + x]) activeCount++
            }
            for (y in 0 until h) {
                dilated[y * w + x] = (activeCount > 0)
                val removeY = y - r
                if (removeY >= 0 && temp[removeY * w + x]) activeCount--
                val addY = y + r + 1
                if (addY < h && temp[addY * w + x]) activeCount++
            }
        }

        // 腐蚀 (Erosion)
        val closed = BooleanArray(w * h)

        // 水平腐蚀
        for (y in 0 until h) {
            val row = y * w
            var activeCount = 0
            for (x in 0 until min(w, r + 1)) {
                if (dilated[row + x]) activeCount++
            }
            for (x in 0 until w) {
                val winLeft = max(0, x - r)
                val winRight = min(w - 1, x + r)
                val winSize = winRight - winLeft + 1
                temp[row + x] = (activeCount >= winSize)

                val removeX = x - r
                if (removeX >= 0 && dilated[row + removeX]) activeCount--
                val addX = x + r + 1
                if (addX < w && dilated[row + addX]) activeCount++
            }
        }

        // 垂直腐蚀
        for (x in 0 until w) {
            var activeCount = 0
            for (y in 0 until min(h, r + 1)) {
                if (temp[y * w + x]) activeCount++
            }
            for (y in 0 until h) {
                val winTop = max(0, y - r)
                val winBottom = min(h - 1, y + r)
                val winSize = winBottom - winTop + 1
                closed[y * w + x] = (activeCount >= winSize)

                val removeY = y - r
                if (removeY >= 0 && temp[removeY * w + x]) activeCount--
                val addY = y + r + 1
                if (addY < h && temp[addY * w + x]) activeCount++
            }
        }

        return closed
    }

    /**
     * 将像素归类为 16 种材质色彩族 (4 种无彩色明度族 + 12 种有彩色色相族)
     */
    private fun classifyPixelMaterial(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val lum = (maxC + minC) / (2f * 255f)
        val sat = if (delta == 0) 0f else delta.toFloat() / (if (lum <= 0.5f) (maxC + minC).toFloat() else (510f - maxC - minC).toFloat())

        if (sat < 0.12f) {
            return (lum * 3.99f).toInt().coerceIn(0, 3)
        }

        var hue = if (delta == 0) 0f else when (maxC) {
            r -> ((g - b).toFloat() / delta) % 6f
            g -> ((b - r).toFloat() / delta) + 2f
            else -> ((r - g).toFloat() / delta) + 4f
        } * 60f
        if (hue < 0f) hue += 360f

        val sector = (hue / 30f).toInt().coerceIn(0, 11)
        return 4 + sector
    }

    /**
     * 原理 7: 连通域清碎渣与闭塞孔洞填充 (BFS 过滤小于 threshold 的孤立阴影斑点与针孔)
     */
    private fun cleanIslandsAndHoles(
        mask: ByteArray,
        scores: FloatArray,
        w: Int,
        h: Int,
        threshold: Int
    ) {
        val count = w * h
        if (count == 0 || threshold <= 1) return
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        val compPixels = IntArray(threshold + 1)

        // 1. 清除孤立微小阴影碎渣 (mask > 0 的小连通域清零)
        for (i in 0 until count) {
            if (visited[i] || mask[i].toInt() == 0 || scores[i] <= -900f) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true

            var compSize = 0
            var isSmall = true

            while (head < tail) {
                val curr = queue[head++]
                if (compSize < threshold) {
                    compPixels[compSize] = curr
                }
                compSize++
                if (compSize > threshold) {
                    isSmall = false
                }

                val cx = curr % w
                val cy = curr / w

                if (cx > 0) {
                    val n = curr - 1
                    if (!visited[n] && mask[n].toInt() > 0 && scores[n] > -900f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cx < w - 1) {
                    val n = curr + 1
                    if (!visited[n] && mask[n].toInt() > 0 && scores[n] > -900f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy > 0) {
                    val n = curr - w
                    if (!visited[n] && mask[n].toInt() > 0 && scores[n] > -900f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy < h - 1) {
                    val n = curr + w
                    if (!visited[n] && mask[n].toInt() > 0 && scores[n] > -900f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }

            if (isSmall && compSize <= threshold) {
                for (k in 0 until compSize) {
                    mask[compPixels[k]] = 0
                }
            }
        }

        // 2. 闭塞孔洞填充 (角色内部 mask == 0 的微小孔洞填补为 1)
        visited.fill(false)
        for (i in 0 until count) {
            if (visited[i] || mask[i].toInt() != 0 || scores[i] <= -900f) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true

            var compSize = 0
            var isSmall = true
            var touchesBackground = false

            while (head < tail) {
                val curr = queue[head++]
                if (compSize < threshold) {
                    compPixels[compSize] = curr
                }
                compSize++
                if (compSize > threshold) {
                    isSmall = false
                }

                val cx = curr % w
                val cy = curr / w

                if (cx == 0 || cx == w - 1 || cy == 0 || cy == h - 1) {
                    touchesBackground = true
                }

                if (cx > 0) {
                    val n = curr - 1
                    if (!visited[n]) {
                        if (scores[n] <= -900f) {
                            touchesBackground = true
                        } else if (mask[n].toInt() == 0) {
                            visited[n] = true
                            queue[tail++] = n
                        }
                    }
                }
                if (cx < w - 1) {
                    val n = curr + 1
                    if (!visited[n]) {
                        if (scores[n] <= -900f) {
                            touchesBackground = true
                        } else if (mask[n].toInt() == 0) {
                            visited[n] = true
                            queue[tail++] = n
                        }
                    }
                }
                if (cy > 0) {
                    val n = curr - w
                    if (!visited[n]) {
                        if (scores[n] <= -900f) {
                            touchesBackground = true
                        } else if (mask[n].toInt() == 0) {
                            visited[n] = true
                            queue[tail++] = n
                        }
                    }
                }
                if (cy < h - 1) {
                    val n = curr + w
                    if (!visited[n]) {
                        if (scores[n] <= -900f) {
                            touchesBackground = true
                        } else if (mask[n].toInt() == 0) {
                            visited[n] = true
                            queue[tail++] = n
                        }
                    }
                }
            }

            if (isSmall && compSize <= threshold && !touchesBackground) {
                for (k in 0 until compSize) {
                    mask[compPixels[k]] = 1
                }
            }
        }
    }

    /**
     * 动漫面部、颈部与下巴轮廓分析数据容器 (用于面部光照保洁、下颌阴影与立体线框)
     */
    data class AnimeFaceInfo(
        val isSkin: BooleanArray,
        val isFace: BooleanArray,
        val isNeck: BooleanArray,
        val chinPixels: IntArray,
        val chinCount: Int,
        val hasFace: Boolean
    )

    /**
     * 原理 2 & 8: 动漫脸与下巴/颈部几何探测
     */
    private fun detectAnimeFaceAndNeck(
        basePixels: IntArray,
        linePixels: IntArray?,
        w: Int,
        h: Int
    ): AnimeFaceInfo {
        val count = w * h
        val isSkin = BooleanArray(count)
        var totalSkin = 0

        // 1. 肤色阈值探测 (动漫肤色色相在 8°~52° 之间且饱和度、明度适中)
        for (i in 0 until count) {
            val p = basePixels[i]
            val a = (p ushr 24) and 0xFF
            if (a < 30) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            if (r > g && g >= b) {
                val maxC = r
                val minC = b
                val delta = maxC - minC
                if (delta > 8) {
                    val lum = (maxC + minC) / 510f
                    if (lum in 0.50f..0.98f) {
                        val sat = delta.toFloat() / (if (lum <= 0.5f) (maxC + minC).toFloat() else (510f - maxC - minC).toFloat())
                        if (sat in 0.08f..0.65f) {
                            val hue = ((g - b).toFloat() / delta) * 60f
                            if (hue in 8f..52f) {
                                isSkin[i] = true
                                totalSkin++
                            }
                        }
                    }
                }
            }
        }

        if (totalSkin < 40) {
            return AnimeFaceInfo(isSkin, BooleanArray(count), BooleanArray(count), IntArray(0), 0, false)
        }

        // 2. 寻找最大的连通肤色区域 (主面部与颈部区域)
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        var maxCompSize = 0
        val maxCompPixels = IntArray(count)

        for (i in 0 until count) {
            if (visited[i] || !isSkin[i]) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true

            while (head < tail) {
                val curr = queue[head++]
                val cx = curr % w
                val cy = curr / w

                if (cx > 0) {
                    val n = curr - 1
                    if (!visited[n] && isSkin[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cx < w - 1) {
                    val n = curr + 1
                    if (!visited[n] && isSkin[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy > 0) {
                    val n = curr - w
                    if (!visited[n] && isSkin[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy < h - 1) {
                    val n = curr + w
                    if (!visited[n] && isSkin[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }

            if (tail > maxCompSize) {
                maxCompSize = tail
                System.arraycopy(queue, 0, maxCompPixels, 0, tail)
            }
        }

        if (maxCompSize < 80) {
            return AnimeFaceInfo(isSkin, BooleanArray(count), BooleanArray(count), IntArray(0), 0, false)
        }

        // 3. 在主肤色区域内分析纵向轮廓宽度分布，定位下巴 V 型拐点
        var minY = h
        var maxY = 0
        for (k in 0 until maxCompSize) {
            val y = maxCompPixels[k] / w
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val rowMinX = IntArray(h) { w }
        val rowMaxX = IntArray(h) { -1 }
        for (k in 0 until maxCompSize) {
            val idx = maxCompPixels[k]
            val x = idx % w
            val y = idx / w
            if (x < rowMinX[y]) rowMinX[y] = x
            if (x > rowMaxX[y]) rowMaxX[y] = x
        }

        // 寻找面部宽度收窄处 (下巴通常位于面部高度的 45%~85% 处)
        val faceHeight = maxY - minY + 1
        val searchStart = minY + (faceHeight * 0.45f).toInt()
        val searchEnd = minY + (faceHeight * 0.88f).toInt()

        var minWidth = w
        var chinY = (minY + maxY) / 2
        for (y in searchStart..searchEnd) {
            if (rowMaxX[y] >= rowMinX[y]) {
                val width = rowMaxX[y] - rowMinX[y] + 1
                if (width < minWidth) {
                    minWidth = width
                    chinY = y
                }
            }
        }

        // 若线稿层在 chinY 附近存在深色线 (下颌下巴轮廓线)，精准对齐
        if (linePixels != null) {
            var bestLineDarkness = 0
            var alignedY = chinY
            for (dy in -6..6) {
                val checkY = (chinY + dy).coerceIn(0, h - 1)
                val checkRow = checkY * w
                var lineCount = 0
                for (x in rowMinX[checkY]..rowMaxX[checkY]) {
                    val lp = linePixels[checkRow + x]
                    if (((lp ushr 24) and 0xFF) > 30) {
                        lineCount++
                    }
                }
                if (lineCount > bestLineDarkness) {
                    bestLineDarkness = lineCount
                    alignedY = checkY
                }
            }
            if (bestLineDarkness > 5) {
                chinY = alignedY
            }
        }

        val isFace = BooleanArray(count)
        val isNeck = BooleanArray(count)
        val chinList = IntArray(maxCompSize)
        var chinCount = 0

        for (k in 0 until maxCompSize) {
            val idx = maxCompPixels[k]
            val y = idx / w
            if (y <= chinY) {
                isFace[idx] = true
                if (y >= chinY - 3) {
                    chinList[chinCount++] = idx
                }
            } else {
                isNeck[idx] = true
            }
        }

        return AnimeFaceInfo(
            isSkin = isSkin,
            isFace = isFace,
            isNeck = isNeck,
            chinPixels = chinList.copyOf(chinCount),
            chinCount = chinCount,
            hasFace = true
        )
    }

    /**
     * 实时渲染阴影位图 (可在后台并发线程或协程中极速执行)
     * 输出的 Bitmap 可直接交由 Compose 60fps 实时预览或盖印进图层
     */
    suspend fun renderShadingBitmap(
        normalField: NormalField,
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        config: ShadingAssistConfig,
        outWidth: Int = baseBitmap.width,
        outHeight: Int = baseBitmap.height
    ): Bitmap? = withContext(Dispatchers.Default) {
        val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val lightVec = config.computeLightVector()
        val lx = lightVec[0]
        val ly = lightVec[1]
        val lz = lightVec[2]

        // 2D 平面朝向光源单位向量 (用于光线逆向投射检测遮挡物)
        val len2D = sqrt(lx * lx + ly * ly).coerceAtLeast(1e-4f)
        val rayDx = lx / len2D
        val rayDy = ly / len2D

        val castDistPx = (config.lineCastDistance * outWidth / 512f).coerceIn(0f, 60f)
        val castSteps = castDistPx.toInt()
        val castIntensity = config.lineCastIntensity.coerceIn(0f, 3.0f)

        val shadowArgb = config.shadowColor.toInt()
        val shadowR = (shadowArgb shr 16) and 0xFF
        val shadowG = (shadowArgb shr 8) and 0xFF
        val shadowB = shadowArgb and 0xFF
        val baseOpacity = config.shadowOpacity.coerceIn(0f, 1f)

        val threshold1 = config.threshold1.coerceIn(0.01f, 0.99f)
        val threshold2 = config.threshold2.coerceIn(0.01f, threshold1)
        val softness = config.softness.coerceIn(0.0f, 1.0f)
        val isCel = config.mode == ShadingMode.CEL
        val isTwoStep = config.celSteps == CelSteps.TWO_STEP
        val isAi = config.useAiModel

        if (baseBitmap.isRecycled) return@withContext null

        // 采样缩放比例
        val scaleNormX = normalField.width.toFloat() / outWidth
        val scaleNormY = normalField.height.toFloat() / outHeight

        // 读取底色像素 (若尺寸不同进行缩放采样)
        val basePixels = IntArray(outWidth * outHeight)
        if (baseBitmap.width == outWidth && baseBitmap.height == outHeight) {
            baseBitmap.getPixels(basePixels, 0, outWidth, 0, 0, outWidth, outHeight)
        } else {
            val scaled = Bitmap.createScaledBitmap(baseBitmap, outWidth, outHeight, true)
            scaled.getPixels(basePixels, 0, outWidth, 0, 0, outWidth, outHeight)
            if (scaled != baseBitmap) {
                scaled.recycle()
            }
        }

        // 读取线稿像素 (用于光线投射自遮挡与阴影消隐)
        val linePixels = if (lineBitmap != null && !lineBitmap.isRecycled) {
            val arr = IntArray(outWidth * outHeight)
            if (lineBitmap.width == outWidth && lineBitmap.height == outHeight) {
                lineBitmap.getPixels(arr, 0, outWidth, 0, 0, outWidth, outHeight)
            } else {
                val scaled = Bitmap.createScaledBitmap(lineBitmap, outWidth, outHeight, true)
                scaled.getPixels(arr, 0, outWidth, 0, 0, outWidth, outHeight)
                if (scaled != lineBitmap) {
                    scaled.recycle()
                }
            }
            arr
        } else {
            null
        }

        val outPixels = IntArray(outWidth * outHeight)

        // 动漫面部与颈部解构探测 (用于面部保洁、下颌投影与立体线框)
        val faceInfo = if (config.animeFaceCleanLighting || config.chinShadowOnNeck || config.clothingFoldEnhancement || config.previewMode == ShadingPreviewMode.WIREFRAME) {
            detectAnimeFaceAndNeck(basePixels, linePixels, outWidth, outHeight)
        } else {
            AnimeFaceInfo(BooleanArray(0), BooleanArray(0), BooleanArray(0), IntArray(0), 0, false)
        }

        // 原理 5: 预览模式 - 3D 表面法线彩图
        if (config.previewMode == ShadingPreviewMode.NORMALS) {
            val normalVec = FloatArray(3)
            for (y in 0 until outHeight) {
                val rowOffset = y * outWidth
                val normY = (y * scaleNormY).toInt().coerceIn(0, normalField.height - 1)
                for (x in 0 until outWidth) {
                    val pIdx = rowOffset + x
                    val baseAlpha = (basePixels[pIdx] ushr 24) and 0xFF
                    if (baseAlpha < 5) {
                        outPixels[pIdx] = 0
                        continue
                    }
                    val normX = (x * scaleNormX).toInt().coerceIn(0, normalField.width - 1)
                    normalField.getNormal(normX, normY, normalVec)
                    val nr = ((normalVec[0] * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                    val ng = ((normalVec[1] * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                    val nb = ((normalVec[2] * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                    outPixels[pIdx] = (baseAlpha shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            outBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight)
            return@withContext outBitmap
        }

        // 原理 5: 预览模式 - 立体线框与解构部位可视化
        if (config.previewMode == ShadingPreviewMode.WIREFRAME) {
            for (y in 0 until outHeight) {
                val rowOffset = y * outWidth
                for (x in 0 until outWidth) {
                    val pIdx = rowOffset + x
                    val basePixel = basePixels[pIdx]
                    val baseAlpha = (basePixel ushr 24) and 0xFF
                    if (baseAlpha < 5) {
                        outPixels[pIdx] = 0
                        continue
                    }
                    val r = (basePixel shr 16) and 0xFF
                    val g = (basePixel shr 8) and 0xFF
                    val b = basePixel and 0xFF

                    val isGrid = (x % 14 == 0 || y % 14 == 0)
                    val wireR: Int
                    val wireG: Int
                    val wireB: Int

                    if (faceInfo.isFace.isNotEmpty() && faceInfo.isFace[pIdx]) {
                        // 面部：浅蜜桃色高亮 + 白色网格
                        wireR = if (isGrid) 255 else (r * 0.75f + 60f).toInt().coerceIn(0, 255)
                        wireG = if (isGrid) 240 else (g * 0.75f + 40f).toInt().coerceIn(0, 255)
                        wireB = if (isGrid) 200 else (b * 0.75f + 20f).toInt().coerceIn(0, 255)
                    } else if (faceInfo.isNeck.isNotEmpty() && faceInfo.isNeck[pIdx]) {
                        // 颈部：暖琥珀色高亮 + 金色网格
                        wireR = if (isGrid) 255 else (r * 0.75f + 70f).toInt().coerceIn(0, 255)
                        wireG = if (isGrid) 200 else (g * 0.75f + 30f).toInt().coerceIn(0, 255)
                        wireB = if (isGrid) 100 else (b * 0.75f).toInt().coerceIn(0, 255)
                    } else {
                        // 身体/衣物/发丝：3D 立体青蓝网格
                        wireR = if (isGrid) 90 else (r * 0.60f).toInt().coerceIn(0, 255)
                        wireG = if (isGrid) 230 else (g * 0.60f + 40f).toInt().coerceIn(0, 255)
                        wireB = if (isGrid) 255 else (b * 0.60f + 80f).toInt().coerceIn(0, 255)
                    }
                    outPixels[pIdx] = (baseAlpha shl 24) or (wireR shl 16) or (wireG shl 8) or wireB
                }
            }
            outBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight)
            return@withContext outBitmap
        }

        // 原理 8: 下巴向颈部投射阴影评分预计算
        val chinShadowScores = FloatArray(outWidth * outHeight)
        if (config.chinShadowOnNeck && faceInfo.hasFace && faceInfo.chinCount > 0) {
            val chinDistPx = (config.chinShadowDistance * outWidth / 512f).coerceIn(4f, 48f)
            val chinSteps = chinDistPx.toInt()
            for (i in 0 until faceInfo.chinCount) {
                val cIdx = faceInfo.chinPixels[i]
                val cx = cIdx % outWidth
                val cy = cIdx / outWidth
                for (step in 1..chinSteps) {
                    val sx = (cx + rayDx * step + 0.5f).toInt()
                    val sy = (cy + rayDy * step + 0.5f).toInt()
                    if (sx !in 0 until outWidth || sy !in 0 until outHeight) break
                    val sIdx = sy * outWidth + sx
                    if (faceInfo.isNeck.isNotEmpty() && faceInfo.isNeck[sIdx]) {
                        val falloff = (1.0f - step.toFloat() / chinSteps).coerceIn(0f, 1f)
                        val sScore = falloff * 0.85f
                        if (sScore > chinShadowScores[sIdx]) {
                            chinShadowScores[sIdx] = sScore
                        }
                    }
                }
            }
        }

        val rawScores = FloatArray(outWidth * outHeight)
        val lineAlphas = FloatArray(outWidth * outHeight)
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val rowsPerChunk = (outHeight + cores - 1) / cores

        val cutoff = (0.50f - threshold1) * 1.6f
        val cutoff2 = cutoff + (threshold1 - threshold2).coerceAtLeast(0.1f) * 1.4f
        val aoIntensity = config.ambientOcclusionIntensity.coerceIn(0f, 1f)

        // 材质色彩分族极值统计 (用于原理 9 相对测光)
        val clusterMinLum = FloatArray(16) { 1.0f }
        val clusterMaxLum = FloatArray(16) { 0.0f }
        if (config.materialAdaptiveMetering) {
            for (i in 0 until outWidth * outHeight) {
                val p = basePixels[i]
                if (((p ushr 24) and 0xFF) < 5) continue
                val cId = classifyPixelMaterial(p)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 255000f
                if (lum < clusterMinLum[cId]) clusterMinLum[cId] = lum
                if (lum > clusterMaxLum[cId]) clusterMaxLum[cId] = lum
            }
        }

        // Pass 1: 计算基础阴影评分 (体积法线三维点积 + 定向投射遮挡 + 下颌阴影 + 线稿深缝接触 AO)
        coroutineScope {
            val jobs = (0 until cores).map { coreIdx ->
                async {
                    val startY = coreIdx * rowsPerChunk
                    val endY = min(outHeight, startY + rowsPerChunk)
                    val normalVec = FloatArray(3)

                    for (y in startY until endY) {
                        val rowOffset = y * outWidth
                        val normY = (y * scaleNormY).toInt().coerceIn(0, normalField.height - 1)

                        for (x in 0 until outWidth) {
                            val pixelIdx = rowOffset + x
                            val basePixel = basePixels[pixelIdx]
                            val baseAlpha = (basePixel ushr 24) and 0xFF

                            if (baseAlpha < 5) {
                                rawScores[pixelIdx] = -999f
                                lineAlphas[pixelIdx] = 0f
                                continue
                            }

                            val normX = (x * scaleNormX).toInt().coerceIn(0, normalField.width - 1)
                            normalField.getNormal(normX, normY, normalVec)

                            // 1. 本体体积阴影评分
                            // 2D 主平面光源方向 (rayDx, rayDy) 主导二次元赛璐珞侧光截断
                            val dot2D = normalVec[0] * rayDx + normalVec[1] * rayDy
                            // Z 轴仰角贡献作为微调调制 (elevation modulation)，不淹没主方向
                            val rawFormDot = dot2D * (1.0f - lz * 0.35f) + (normalVec[2] * lz - 0.40f) * 0.25f

                            // 原理 6: 画师经验打光比例映射 (顺光保留约 15% 边缘转折阴影，逆光覆盖约 85%-90%)
                            val formDot = if (config.artistExperienceCurve) {
                                if (rawFormDot >= 0f) {
                                    rawFormDot.toDouble().pow(0.75).toFloat()
                                } else {
                                    -( (-rawFormDot).toDouble().pow(1.18).toFloat() )
                                }
                            } else {
                                rawFormDot
                            }
                            var formScore = -formDot

                            // 原理 2: 面部动漫保洁 (弱化面部球状杂乱阴影，保持二次元通透纯净)
                            if (config.animeFaceCleanLighting && faceInfo.isFace.isNotEmpty() && faceInfo.isFace[pixelIdx]) {
                                formScore *= 0.35f
                            }

                            // 原理 9: 材质色彩分族相对测光 (同一材质族内相对测光，防止黑衣服泛黑死黑)
                            if (config.materialAdaptiveMetering) {
                                val cId = classifyPixelMaterial(basePixel)
                                val cMin = clusterMinLum[cId]
                                val cMax = clusterMaxLum[cId]
                                val range = (cMax - cMin).coerceAtLeast(0.08f)
                                val r = (basePixel shr 16) and 0xFF
                                val g = (basePixel shr 8) and 0xFF
                                val b = basePixel and 0xFF
                                val lum = (r * 299 + g * 587 + b * 114) / 255000f
                                val relLum = ((lum - cMin) / range).coerceIn(0f, 1f)
                                val materialBias = (0.5f - relLum) * (config.materialMeteringStrength.coerceIn(0f, 1f) * 0.35f)
                                formScore += materialBias
                            }

                            // 2. 线稿接触与投射阴影 + 死角闭塞 AO
                            var lineCast = 0.0f
                            var currentLineAlpha = 0.0f
                            var creviceAo = 0.0f

                            if (linePixels != null) {
                                val lp = linePixels[pixelIdx]
                                val la = (lp ushr 24) and 0xFF
                                if (la > 10) {
                                    val r = (lp shr 16) and 0xFF
                                    val g = (lp shr 8) and 0xFF
                                    val b = lp and 0xFF
                                    val darkness = (255 - (r * 299 + g * 587 + b * 114) / 1000) / 255.0f
                                    currentLineAlpha = (la / 255.0f) * darkness
                                }

                                // 定向光投射阴影 (如刘海投射在额头、下巴投射在脖子)
                                if (config.lineArtOcclusion && castSteps > 0 && currentLineAlpha < 0.25f) {
                                    var maxCast = 0.0f
                                    for (step in 1..castSteps) {
                                        val sx = (x + rayDx * step + 0.5f).toInt()
                                        val sy = (y + rayDy * step + 0.5f).toInt()
                                        if (sx !in 0 until outWidth || sy !in 0 until outHeight) break

                                        val sampleLp = linePixels[sy * outWidth + sx]
                                        val sampleLa = (sampleLp ushr 24) and 0xFF
                                        if (sampleLa > 25) {
                                            val r = (sampleLp shr 16) and 0xFF
                                            val g = (sampleLp shr 8) and 0xFF
                                            val b = sampleLp and 0xFF
                                            val darkness = (255 - (r * 299 + g * 587 + b * 114) / 1000) / 255.0f
                                            val sampleStr = (sampleLa / 255.0f) * darkness
                                            if (sampleStr > 0.20f) {
                                                val falloff = 1.0f - (step.toFloat() / castSteps) * 0.50f
                                                val castVal = sampleStr * falloff
                                                if (castVal > maxCast) {
                                                    maxCast = castVal
                                                }
                                            }
                                        }
                                    }
                                    lineCast = maxCast * castIntensity
                                }

                                // 接触死角 AO (发根深缝、衣褶死角)
                                if (aoIntensity > 0.01f && currentLineAlpha < 0.35f) {
                                    var lineNeighbors = 0
                                    val offsets = intArrayOf(-3, 0, 3, 0, 0, -3, 0, 3, -2, -2, 2, 2, -2, 2, 2, -2)
                                    for (oi in 0 until offsets.size step 2) {
                                        val ox = x + offsets[oi]
                                        val oy = y + offsets[oi + 1]
                                        if (ox in 0 until outWidth && oy in 0 until outHeight) {
                                            val nlp = linePixels[oy * outWidth + ox]
                                            if (((nlp ushr 24) and 0xFF) > 40) {
                                                lineNeighbors++
                                            }
                                        }
                                    }
                                    if (lineNeighbors >= 2) {
                                        creviceAo = (lineNeighbors / 8.0f) * aoIntensity * 0.5f
                                    }
                                }
                            }

                            // 原理 4: 衣服褶皱起伏 (对非肤色衣物区域增强褶皱阴影)
                            if (config.clothingFoldEnhancement && (faceInfo.isSkin.isEmpty() || !faceInfo.isSkin[pixelIdx])) {
                                if (creviceAo > 0.05f) {
                                    creviceAo += config.clothingFoldStrength * 0.35f
                                }
                            }

                            // 原理 8: 下巴向颈部专属阴影投影
                            val chinScore = if (chinShadowScores.isNotEmpty()) chinShadowScores[pixelIdx] else 0f
                            val maxOcclusion = max(lineCast, chinScore)

                            rawScores[pixelIdx] = max(formScore, maxOcclusion) + creviceAo
                            lineAlphas[pixelIdx] = currentLineAlpha
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        if (!isActive) {
            outBitmap.recycle()
            return@withContext null
        }

        // Pass 2: 色块边缘引导吸附 (Joint Bilateral Edge Snapping)
        // 确保赛璐珞阴影交界线严格贴合色块边缘 (皮肤/头发/衣服边界)，防止跨色块产生毛边与渗色
        val snappedScores = if (config.colorEdgeSnapping && isCel) {
            val processed = FloatArray(outWidth * outHeight)
            val snapTol = config.colorEdgeSnapTolerance.coerceIn(0.1f, 1.0f)
            val colorThresh = 40f + (1.0f - snapTol) * 50f

            coroutineScope {
                val jobs = (0 until cores).map { coreIdx ->
                    async {
                        val startY = coreIdx * rowsPerChunk
                        val endY = min(outHeight, startY + rowsPerChunk)

                        for (y in startY until endY) {
                            val rowOffset = y * outWidth
                            for (x in 0 until outWidth) {
                                val pIdx = rowOffset + x
                                val curScore = rawScores[pIdx]
                                if (curScore <= -900f) {
                                    processed[pIdx] = curScore
                                    continue
                                }

                                // 仅对处于明暗临界带 (|score - cutoff| < 0.25f) 的像素进行同色域双边平滑吸附
                                val distToCutoff = abs(curScore - cutoff)
                                if (distToCutoff > 0.25f) {
                                    processed[pIdx] = curScore
                                    continue
                                }

                                val c0 = basePixels[pIdx]
                                val r0 = (c0 shr 16) and 0xFF
                                val g0 = (c0 shr 8) and 0xFF
                                val b0 = c0 and 0xFF

                                var weightSum = 1.0f
                                var valSum = curScore

                                for (dy in -1..1) {
                                    val ny = y + dy
                                    if (ny !in 0 until outHeight) continue
                                    val nRow = ny * outWidth
                                    for (dx in -1..1) {
                                        if (dx == 0 && dy == 0) continue
                                        val nx = x + dx
                                        if (nx !in 0 until outWidth) continue
                                        val nIdx = nRow + nx
                                        val nScore = rawScores[nIdx]
                                        if (nScore <= -900f) continue

                                        val cn = basePixels[nIdx]
                                        val rn = (cn shr 16) and 0xFF
                                        val gn = (cn shr 8) and 0xFF
                                        val bn = cn and 0xFF
                                        val diff = abs(r0 - rn) + abs(g0 - gn) + abs(b0 - bn)

                                        // 若邻域属于不同底色块 (发丝 vs 皮肤)，则权重归零，坚决不向异色渗透
                                        if (diff < colorThresh) {
                                            val w = 1.0f - (diff / colorThresh) * 0.8f
                                            weightSum += w
                                            valSum += nScore * w
                                        }
                                    }
                                }
                                processed[pIdx] = valSum / weightSum
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
            processed
        } else {
            rawScores
        }

        if (!isActive) {
            outBitmap.recycle()
            return@withContext null
        }

        // Pass 3: 赛璐珞二分掩码生成、连通域清碎渣与形态学平滑 (Sub-pixel Morphological Smoothing)
        // 消除离散像素阶梯锯齿与孤立噪点，获得动画工作室级别光洁明暗交界线
        val finalMask = if (isCel && (config.morphologicalSmoothRadius > 0 || config.cleanSmallIslands)) {
            val initialMask = ByteArray(outWidth * outHeight)
            for (i in 0 until outWidth * outHeight) {
                val sc = snappedScores[i]
                if (sc <= -900f) {
                    initialMask[i] = 0
                } else if (isTwoStep) {
                    initialMask[i] = if (sc > cutoff2) 2 else if (sc > cutoff) 1 else 0
                } else {
                    initialMask[i] = if (sc > cutoff) 1 else 0
                }
            }

            // 原理 7: 连通域清碎渣与闭塞孔洞填充 (消除小于 islandThreshold 像素的孤立噪点与针孔)
            if (config.cleanSmallIslands) {
                val scaledThreshold = (config.islandThreshold * (outWidth / 512.0f) * (outHeight / 512.0f))
                    .toInt().coerceIn(4, 150)
                cleanIslandsAndHoles(initialMask, snappedScores, outWidth, outHeight, scaledThreshold)
            }

            if (config.morphologicalSmoothRadius > 0) {
                val smoothMask = ByteArray(outWidth * outHeight)
                val rad = config.morphologicalSmoothRadius.coerceIn(1, 3)

                coroutineScope {
                    val jobs = (0 until cores).map { coreIdx ->
                        async {
                            val startY = coreIdx * rowsPerChunk
                            val endY = min(outHeight, startY + rowsPerChunk)

                            for (y in startY until endY) {
                                val rowOffset = y * outWidth
                                for (x in 0 until outWidth) {
                                    val pIdx = rowOffset + x
                                    if (snappedScores[pIdx] <= -900f) {
                                        smoothMask[pIdx] = 0
                                        continue
                                    }

                                    var ones = 0
                                    var twos = 0
                                    var total = 0

                                    for (dy in -rad..rad) {
                                        val ny = y + dy
                                        if (ny !in 0 until outHeight) continue
                                        val nRow = ny * outWidth
                                        for (dx in -rad..rad) {
                                            val nx = x + dx
                                            if (nx !in 0 until outWidth) continue
                                            val v = initialMask[nRow + nx].toInt()
                                            if (v >= 1) ones++
                                            if (v >= 2) twos++
                                            total++
                                        }
                                    }

                                    val majThreshold = total / 2
                                    smoothMask[pIdx] = if (isTwoStep && twos > majThreshold) {
                                        2
                                    } else if (ones > majThreshold) {
                                        1
                                    } else {
                                        0
                                    }
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                smoothMask
            } else {
                initialMask
            }
        } else {
            null
        }

        if (!isActive) {
            outBitmap.recycle()
            return@withContext null
        }

        // Pass 4: 像素合成输出 (应用阴影颜色、线稿避让 lineFade 与不透明度)
        coroutineScope {
            val jobs = (0 until cores).map { coreIdx ->
                async {
                    val startY = coreIdx * rowsPerChunk
                    val endY = min(outHeight, startY + rowsPerChunk)

                    for (y in startY until endY) {
                        val rowOffset = y * outWidth
                        for (x in 0 until outWidth) {
                            val pixelIdx = rowOffset + x
                            val basePixel = basePixels[pixelIdx]
                            val baseAlpha = (basePixel ushr 24) and 0xFF

                            if (baseAlpha < 5) {
                                outPixels[pixelIdx] = 0
                                continue
                            }

                            val shadowFactor: Float
                            if (isCel) {
                                if (finalMask != null) {
                                    val m = finalMask[pixelIdx].toInt()
                                    shadowFactor = if (m == 2) 1.0f else if (m == 1) (if (isTwoStep) 0.55f else 1.0f) else 0.0f
                                } else {
                                    val sc = snappedScores[pixelIdx]
                                    if (isTwoStep) {
                                        shadowFactor = if (sc > cutoff2) 1.0f else if (sc > cutoff) 0.55f else 0.0f
                                    } else {
                                        val halfSoft = (softness * 0.12f).coerceAtLeast(0.005f)
                                        shadowFactor = ((sc - cutoff + halfSoft) / (2f * halfSoft)).coerceIn(0.0f, 1.0f)
                                    }
                                }
                            } else {
                                val sc = snappedScores[pixelIdx]
                                val span = (softness * 0.5f).coerceAtLeast(0.12f)
                                shadowFactor = ((sc - cutoff + span) / (2f * span)).coerceIn(0.0f, 1.0f)
                            }

                            val curLineAlpha = lineAlphas[pixelIdx]
                            val lineFade = (1.0f - curLineAlpha * 2.5f).coerceIn(0.0f, 1.0f)

                            val finalAlpha = (shadowFactor * baseOpacity * (baseAlpha / 255.0f) * lineFade * 255.0f + 0.5f)
                                .toInt().coerceIn(0, 255)

                            outPixels[pixelIdx] = if (finalAlpha > 0) {
                                (finalAlpha shl 24) or (shadowR shl 16) or (shadowG shl 8) or shadowB
                            } else {
                                0
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        if (!isActive) {
            outBitmap.recycle()
            return@withContext null
        }
        outBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight)
        outBitmap
    }
}
