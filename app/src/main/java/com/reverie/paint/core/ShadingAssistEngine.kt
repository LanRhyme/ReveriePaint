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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
     * 生成表面法线向量场：优先尝试 ONNX 模型推理，若无模型或模型格式不匹配则自动无缝回退至 2.5D 高性能合成法线
     */
    suspend fun generateNormalField(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        modelFile: File?,
        targetResolution: Int = 1024
    ): NormalField = withContext(Dispatchers.Default) {
        if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
            try {
                val onnxResult = runOnnxInference(baseBitmap, lineBitmap, modelFile, targetResolution)
                if (onnxResult != null) {
                    return@withContext onnxResult
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ONNX inference failed, smoothly falling back to synthetic normal field", e)
            }
        }
        generateSyntheticNormalField(baseBitmap, lineBitmap, targetResolution)
    }

    /**
     * 执行 ONNX Runtime Mobile 端侧推理
     */
    private fun runOnnxInference(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        modelFile: File,
        targetResolution: Int
    ): NormalField? {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        val session = env.createSession(modelFile.absolutePath, opts)
        session.use { sess ->
            val inputInfo = sess.inputInfo
            if (inputInfo.isEmpty()) return null
            val inputName = inputInfo.keys.first()

            // UNet 下采样通常要求输入尺寸为 32 的整数倍
            val alignW = (((min(baseBitmap.width, targetResolution).coerceAtLeast(64)) / 32) * 32).coerceAtLeast(64)
            val alignH = (((min(baseBitmap.height, targetResolution).coerceAtLeast(64)) / 32) * 32).coerceAtLeast(64)

            val scaledBase = if (baseBitmap.width == alignW && baseBitmap.height == alignH) {
                baseBitmap
            } else {
                Bitmap.createScaledBitmap(baseBitmap, alignW, alignH, true)
            }

            val scaledPixels = IntArray(alignW * alignH)
            scaledBase.getPixels(scaledPixels, 0, alignW, 0, 0, alignW, alignH)

            // NCHW: 1 x 3 x H x W (归一化至 [0, 1] 适应现代残差法线网络)
            val floatBuffer = FloatBuffer.allocate(1 * 3 * alignH * alignW)
            val channelSize = alignW * alignH

            // R channel
            for (i in 0 until channelSize) {
                val r = (scaledPixels[i] shr 16) and 0xFF
                floatBuffer.put(i, r / 255.0f)
            }
            // G channel
            for (i in 0 until channelSize) {
                val g = (scaledPixels[i] shr 8) and 0xFF
                floatBuffer.put(channelSize + i, g / 255.0f)
            }
            // B channel
            for (i in 0 until channelSize) {
                val b = scaledPixels[i] and 0xFF
                floatBuffer.put(channelSize * 2 + i, b / 255.0f)
            }

            val tensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, alignH.toLong(), alignW.toLong()))
            tensor.use { inTensor ->
                val results = sess.run(mapOf(inputName to inTensor))
                results.use { res ->
                    if (res.size() == 0) return null
                    val outTensor = res.get(0) as? OnnxTensor
                    if (outTensor != null) {
                        val fb = outTensor.floatBuffer
                        val shape = outTensor.info.shape
                        val outH = if (shape.size >= 4) shape[2].toInt() else alignH
                        val outW = if (shape.size >= 4) shape[3].toInt() else alignW
                        val planeSize = outH * outW

                        // 自动判别模型输出格式：[0, 1] 切线图编码 vs [-1, 1] 单位法线
                        var sampleMin = Float.MAX_VALUE
                        var sampleMax = -Float.MAX_VALUE
                        val checkCount = min(1000, planeSize)
                        for (k in 0 until checkCount) {
                            val v = fb.get(k)
                            if (v < sampleMin) sampleMin = v
                            if (v > sampleMax) sampleMax = v
                        }
                        // 若存在负数 (如 -0.05f 以下)，则肯定直接是 [-1, 1] 矢量输出 (如 sprite2normal)
                        val isUnitEncoding = sampleMin >= -0.02f && sampleMax <= 1.05f && sampleMax > 0.6f

                        val normalArray = FloatArray(outW * outH * 3)
                        var idx = 0
                        for (y in 0 until outH) {
                            val yOffset = y * outW
                            for (x in 0 until outW) {
                                val pIdx = yOffset + x
                                val rawX = fb.get(pIdx)
                                val rawY = fb.get(planeSize + pIdx)
                                val rawZ = fb.get(planeSize * 2 + pIdx)

                                val nx = if (isUnitEncoding) rawX * 2.0f - 1.0f else rawX
                                val ny = if (isUnitEncoding) rawY * 2.0f - 1.0f else rawY
                                val nz = (if (isUnitEncoding) rawZ * 2.0f - 1.0f else rawZ).coerceAtLeast(0.01f)

                                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
                                normalArray[idx++] = nx / len
                                normalArray[idx++] = ny / len
                                normalArray[idx++] = nz / len
                            }
                        }
                        return NormalField(outW, outH, normalArray)
                    }
                }
            }
        }
        return null
    }

    /**
     * 高性能 2.5D 几何曲面法线生成器 (无需神经网络，瞬时零延迟生成)
     * 利用局部色块岛屿距离场与全局外轮廓融合，生成细腻饱满的发丝、面部与衣褶立体体积，彻底消除沟壑与变形
     */
    fun generateSyntheticNormalField(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        targetResolution: Int = 1024
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

        val linePixels = if (lineBitmap != null) {
            val scaledLine = if (lineBitmap.width == w && lineBitmap.height == h) {
                lineBitmap
            } else {
                Bitmap.createScaledBitmap(lineBitmap, w, h, true)
            }
            val arr = IntArray(w * h)
            scaledLine.getPixels(arr, 0, w, 0, 0, w, h)
            arr
        } else {
            null
        }

        // 1. 构建局部色块边界与线稿边界标记 (局部立体感) 与全局外轮廓 (全局大体块)
        val localBoundary = BooleanArray(w * h)
        val macroBoundary = BooleanArray(w * h)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val p = basePixels[idx]
                val a = (p ushr 24) and 0xFF
                if (a < 25) {
                    macroBoundary[idx] = true
                    localBoundary[idx] = true
                    continue
                }

                // 检查是否为线稿像素
                if (linePixels != null) {
                    val lp = linePixels[idx]
                    val la = (lp ushr 24) and 0xFF
                    if (la > 30) {
                        val lr = (lp shr 16) and 0xFF
                        val lg = (lp shr 8) and 0xFF
                        val lb = lp and 0xFF
                        val dark = (255 - (lr * 299 + lg * 587 + lb * 114) / 1000) / 255f
                        if ((la / 255f) * dark > 0.25f) {
                            localBoundary[idx] = true
                            continue
                        }
                    }
                }

                // 检查与相邻像素的色彩跳变 (检测发束、肤色、服饰分块边缘)
                if (x > 0) {
                    val pLeft = basePixels[idx - 1]
                    val aLeft = (pLeft ushr 24) and 0xFF
                    if (aLeft >= 25) {
                        val diff = abs(((p shr 16) and 0xFF) - ((pLeft shr 16) and 0xFF)) +
                                abs(((p shr 8) and 0xFF) - ((pLeft shr 8) and 0xFF)) +
                                abs((p and 0xFF) - (pLeft and 0xFF))
                        if (diff > 42) {
                            localBoundary[idx] = true
                            continue
                        }
                    }
                }
                if (y > 0) {
                    val pUp = basePixels[idx - w]
                    val aUp = (pUp ushr 24) and 0xFF
                    if (aUp >= 25) {
                        val diff = abs(((p shr 16) and 0xFF) - ((pUp shr 16) and 0xFF)) +
                                abs(((p shr 8) and 0xFF) - ((pUp shr 8) and 0xFF)) +
                                abs((p and 0xFF) - (pUp and 0xFF))
                        if (diff > 42) {
                            localBoundary[idx] = true
                            continue
                        }
                    }
                }
            }
        }

        // 2. 双轨距离场计算：localDist (局部圆柱/球体隆起) + macroDist (躯体整体隆起)
        val localDist = FloatArray(w * h) { if (localBoundary[it]) 0.0f else 1e6f }
        val macroDist = FloatArray(w * h) { if (macroBoundary[it]) 0.0f else 1e6f }

        // 前向扫描
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                var dL = localDist[idx]
                var dM = macroDist[idx]
                if (dL > 0f) {
                    if (x > 0) dL = min(dL, localDist[idx - 1] + 1.0f)
                    if (y > 0) {
                        dL = min(dL, localDist[idx - w] + 1.0f)
                        if (x > 0) dL = min(dL, localDist[idx - w - 1] + 1.414f)
                        if (x < w - 1) dL = min(dL, localDist[idx - w + 1] + 1.414f)
                    }
                    localDist[idx] = dL
                }
                if (dM > 0f) {
                    if (x > 0) dM = min(dM, macroDist[idx - 1] + 1.0f)
                    if (y > 0) {
                        dM = min(dM, macroDist[idx - w] + 1.0f)
                        if (x > 0) dM = min(dM, macroDist[idx - w - 1] + 1.414f)
                        if (x < w - 1) dM = min(dM, macroDist[idx - w + 1] + 1.414f)
                    }
                    macroDist[idx] = dM
                }
            }
        }

        // 反向扫描
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val idx = row + x
                var dL = localDist[idx]
                var dM = macroDist[idx]
                if (dL > 0f) {
                    if (x < w - 1) dL = min(dL, localDist[idx + 1] + 1.0f)
                    if (y < h - 1) {
                        dL = min(dL, localDist[idx + w] + 1.0f)
                        if (x > 0) dL = min(dL, localDist[idx + w - 1] + 1.414f)
                        if (x < w - 1) dL = min(dL, localDist[idx + w + 1] + 1.414f)
                    }
                    localDist[idx] = dL
                }
                if (dM > 0f) {
                    if (x < w - 1) dM = min(dM, macroDist[idx + 1] + 1.0f)
                    if (y < h - 1) {
                        dM = min(dM, macroDist[idx + w] + 1.0f)
                        if (x > 0) dM = min(dM, macroDist[idx + w - 1] + 1.414f)
                        if (x < w - 1) dM = min(dM, macroDist[idx + w + 1] + 1.414f)
                    }
                    macroDist[idx] = dM
                }
            }
        }

        // 3. 高度图融合：局部发丝/面颊隆起半径 20px，全局大体块半径 100px
        val heightMap = FloatArray(w * h)
        val maxLocalR = 20.0f
        val maxMacroR = 100.0f
        for (i in 0 until w * h) {
            val dL = localDist[i]
            val dM = macroDist[i]
            if (dM > 0f) {
                val hLocal = sqrt(min(dL, maxLocalR) / maxLocalR)
                val hMacro = sqrt(min(dM, maxMacroR) / maxMacroR)
                heightMap[i] = hLocal * 0.70f + hMacro * 0.30f
            }
        }

        // 4. 双重平滑以消除棱角
        val tempMap = FloatArray(w * h)
        boxBlurHorizontal(heightMap, tempMap, w, h, 4)
        boxBlurVertical(tempMap, heightMap, w, h, 4)

        // 5. 计算梯度偏导数并归一化为连续法线向量场
        val normals = FloatArray(w * h * 3)
        val normalScale = 2.2f

        for (y in 0 until h) {
            val yPrev = max(0, y - 1) * w
            val yCurr = y * w
            val yNext = min(h - 1, y + 1) * w

            for (x in 0 until w) {
                val xPrev = max(0, x - 1)
                val xNext = min(w - 1, x + 1)

                val dx = (heightMap[yCurr + xNext] - heightMap[yCurr + xPrev]) * normalScale
                val dy = (heightMap[yNext + x] - heightMap[yPrev + x]) * normalScale
                val dz = 1.0f

                val len = sqrt(dx * dx + dy * dy + dz * dz)
                val idx = (yCurr + x) * 3
                normals[idx] = -dx / len
                normals[idx + 1] = -dy / len
                normals[idx + 2] = dz / len
            }
        }

        return NormalField(w, h, normals)
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
    ): Bitmap = withContext(Dispatchers.Default) {
        val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val lightVec = config.computeLightVector()
        val lx = lightVec[0]
        val ly = lightVec[1]
        val lz = lightVec[2]

        val shadowArgb = config.shadowColor.toInt()
        val shadowR = (shadowArgb shr 16) and 0xFF
        val shadowG = (shadowArgb shr 8) and 0xFF
        val shadowB = shadowArgb and 0xFF
        val baseOpacity = config.shadowOpacity.coerceIn(0f, 1f)

        val threshold1 = config.threshold1.coerceIn(0.01f, 0.99f)
        val threshold2 = config.threshold2.coerceIn(0.01f, threshold1)
        val softness = config.softness.coerceIn(0.01f, 1.0f)
        val isCel = config.mode == ShadingMode.CEL
        val isTwoStep = config.celSteps == CelSteps.TWO_STEP

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
        }

        // 读取线稿像素 (用于光线投射自遮挡与阴影消隐)
        val linePixels = if (lineBitmap != null) {
            val arr = IntArray(outWidth * outHeight)
            if (lineBitmap.width == outWidth && lineBitmap.height == outHeight) {
                lineBitmap.getPixels(arr, 0, outWidth, 0, 0, outWidth, outHeight)
            } else {
                val scaled = Bitmap.createScaledBitmap(lineBitmap, outWidth, outHeight, true)
                scaled.getPixels(arr, 0, outWidth, 0, 0, outWidth, outHeight)
            }
            arr
        } else {
            null
        }

        val outPixels = IntArray(outWidth * outHeight)
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val rowsPerChunk = (outHeight + cores - 1) / cores

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
                                outPixels[pixelIdx] = 0
                                continue
                            }

                            val normX = (x * scaleNormX).toInt().coerceIn(0, normalField.width - 1)
                            normalField.getNormal(normX, normY, normalVec)

                            // 朗伯光照 dot(N, L)
                            val dot = normalVec[0] * lx + normalVec[1] * ly + normalVec[2] * lz
                            // 映射到 [0, 1]: 1 为受光面，0 为背光面
                            var illumination = (dot + 1.0f) * 0.5f

                            // 线稿单向投射阴影 (顺着线稿走，向光侧完全无阴影，杜绝双侧沟壑)
                            if (linePixels != null && config.lineArtOcclusion) {
                                val len2D = sqrt(lx * lx + ly * ly)
                                if (len2D > 0.05f) {
                                    val dirX = lx / len2D
                                    val dirY = ly / len2D
                                    val maxSteps = 16
                                    var maxOcclusion = 0.0f

                                    // 沿光源入射方向回溯检查遮挡 (从当前像素向光源方向探测是否有线稿遮挡)
                                    for (step in 1..maxSteps) {
                                        val sx = (x + dirX * step).toInt()
                                        val sy = (y + dirY * step).toInt()
                                        if (sx !in 0 until outWidth || sy !in 0 until outHeight) break

                                        val lp = linePixels[sy * outWidth + sx]
                                        val la = (lp ushr 24) and 0xFF
                                        if (la > 25) {
                                            val r = (lp shr 16) and 0xFF
                                            val g = (lp shr 8) and 0xFF
                                            val b = lp and 0xFF
                                            val darkness = (255 - (r * 299 + g * 587 + b * 114) / 1000) / 255.0f
                                            val lineStr = (la / 255.0f) * darkness
                                            if (lineStr > 0.2f) {
                                                val falloff = 1.0f - (step.toFloat() / (maxSteps + 1))
                                                val occ = lineStr * falloff
                                                if (occ > maxOcclusion) {
                                                    maxOcclusion = occ
                                                }
                                            }
                                        }
                                    }
                                    if (maxOcclusion > 0.0f) {
                                        illumination = min(illumination, 1.0f - maxOcclusion * 0.65f)
                                    }
                                }
                            }

                            // 阴影强度 S in [0, 1]
                            val shadowFactor: Float = if (isCel) {
                                if (isTwoStep) {
                                    if (illumination < threshold2) {
                                        1.0f // 深阶阴影
                                    } else if (illumination < threshold1) {
                                        0.55f // 浅阶阴影
                                    } else {
                                        0.0f
                                    }
                                } else {
                                    if (illumination < threshold1) {
                                        1.0f
                                    } else if (illumination < threshold1 + softness * 0.15f) {
                                        // 边缘微量平滑过渡
                                        val t = (illumination - threshold1) / (softness * 0.15f)
                                        1.0f - t
                                    } else {
                                        0.0f
                                    }
                                }
                            } else {
                                // Smooth 柔和过渡
                                val t = (threshold1 - illumination) / (softness * 0.8f) + 0.5f
                                t.coerceIn(0.0f, 1.0f)
                            }

                            // 线稿自身处阴影完全消隐：杜绝在线条正上方重复叠加深色阴影导致线条脏化变粗
                            var lineFade = 1.0f
                            if (linePixels != null) {
                                val lp = linePixels[pixelIdx]
                                val la = (lp ushr 24) and 0xFF
                                if (la > 10) {
                                    val r = (lp shr 16) and 0xFF
                                    val g = (lp shr 8) and 0xFF
                                    val b = lp and 0xFF
                                    val darkness = (255 - (r * 299 + g * 587 + b * 114) / 1000) / 255.0f
                                    val lineStrength = (la / 255.0f) * darkness
                                    if (lineStrength > 0.08f) {
                                        // 快速截止，当线稿强度达到 0.4 时阴影完全归零 (fade = 0.0)
                                        lineFade = (1.0f - lineStrength * 2.5f).coerceIn(0.0f, 1.0f)
                                    }
                                }
                            }

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

        outBitmap.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight)
        outBitmap
    }
}
