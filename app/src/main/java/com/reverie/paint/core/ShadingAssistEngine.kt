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
     * 生成表面法线向量场：若开启 useAiModel 且模型就绪则尝试 ONNX 推理，默认使用零噪点、饱满圆润的 2.5D 高性能合成曲面
     */
    suspend fun generateNormalField(
        baseBitmap: Bitmap,
        lineBitmap: Bitmap?,
        modelFile: File?,
        useAiModel: Boolean = false,
        targetResolution: Int = 1024
    ): NormalField = withContext(Dispatchers.Default) {
        if (useAiModel && modelFile != null && modelFile.exists() && modelFile.length() > 0) {
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

        // 1. 构建整体外轮廓边界标记 (仅以画布透明底 a < 25 为界，彻底杜绝颜色微小波动带来的噪点与斑块)
        val boundary = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val a = (basePixels[i] ushr 24) and 0xFF
            if (a < 25) {
                boundary[i] = true
            }
        }

        // 2. 连续欧氏距离场双向快速推进
        val dist = FloatArray(w * h) { if (boundary[it]) 0.0f else 1e6f }

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

        // 3. 仿立体气囊饱满隆起高度场 (抛物线/球体过渡，不截断 plateau)
        val maxDist = (min(w, h) * 0.18f).coerceIn(60.0f, 150.0f)
        val heightMap = FloatArray(w * h)
        for (i in 0 until w * h) {
            val d = dist[i]
            if (d > 0f) {
                val normD = (d / maxDist).coerceIn(0.0f, 1.0f)
                heightMap[i] = sqrt(normD)
            }
        }

        // 4. 三重盒状滤波 (数学等价于大半径高斯平滑，彻底消除 Voronoi 中轴棱角与沟壑)
        val blurRadius = (min(w, h) / 45).coerceIn(8, 20)
        val tempMap = FloatArray(w * h)
        repeat(3) {
            boxBlurHorizontal(heightMap, tempMap, w, h, blurRadius)
            boxBlurVertical(tempMap, heightMap, w, h, blurRadius)
        }

        // 5. 梯度偏导数计算连续空间法线场
        val normals = FloatArray(w * h * 3)
        val normalScale = 12.0f

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

                            // 2D 平面定向光照：dot2D = Nx * Lx + Ny * Ly
                            // 受光面 dot2D > 0, 背光面 dot2D < 0, 平坦正面 dot2D = 0
                            val dot2D = normalVec[0] * lx + normalVec[1] * ly

                            // 阴影覆盖范围滑块映射：0.50 (50%) 居中时，背光面落入阴影，平坦正面受光
                            val cutoff = (threshold1 - 0.50f) * 1.6f

                            // 本体体积阴影因子
                            val formShadow: Float = if (isCel) {
                                if (isTwoStep) {
                                    val cutoff2 = cutoff - 0.25f
                                    if (dot2D < cutoff2) {
                                        1.0f
                                    } else if (dot2D < cutoff) {
                                        0.55f
                                    } else {
                                        0.0f
                                    }
                                } else {
                                    val halfSoft = softness * 0.15f
                                    if (dot2D < cutoff - halfSoft) {
                                        1.0f
                                    } else if (dot2D < cutoff + halfSoft && halfSoft > 0.001f) {
                                        (cutoff + halfSoft - dot2D) / (2f * halfSoft)
                                    } else {
                                        0.0f
                                    }
                                }
                            } else {
                                val span = (softness * 0.5f).coerceAtLeast(0.05f)
                                ((cutoff - dot2D) / span + 0.5f).coerceIn(0.0f, 1.0f)
                            }

                            // 线稿单向投射接触微阴影 (仅在用户显式开启时生效，步长限制在 1..3 像素微接触，避免拉出长条斑块)
                            var lineCastShadow = 0.0f
                            if (linePixels != null && config.lineArtOcclusion) {
                                val maxSteps = 3
                                for (step in 1..maxSteps) {
                                    val sx = (x + lx * step).toInt()
                                    val sy = (y + ly * step).toInt()
                                    if (sx !in 0 until outWidth || sy !in 0 until outHeight) break

                                    val lp = linePixels[sy * outWidth + sx]
                                    val la = (lp ushr 24) and 0xFF
                                    if (la > 30) {
                                        val r = (lp shr 16) and 0xFF
                                        val g = (lp shr 8) and 0xFF
                                        val b = lp and 0xFF
                                        val darkness = (255 - (r * 299 + g * 587 + b * 114) / 1000) / 255.0f
                                        val lineStr = (la / 255.0f) * darkness
                                        if (lineStr > 0.35f) {
                                            val falloff = 1.0f - (step.toFloat() / (maxSteps + 1))
                                            val occ = lineStr * falloff * 0.5f
                                            if (occ > lineCastShadow) {
                                                lineCastShadow = occ
                                            }
                                        }
                                    }
                                }
                            }

                            // 阴影融合 (取本体阴影与投射阴影并集，自然平滑无二次叠黑)
                            val combinedShadow = max(formShadow, lineCastShadow)

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

                            val finalAlpha = (combinedShadow * baseOpacity * (baseAlpha / 255.0f) * lineFade * 255.0f + 0.5f)
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
