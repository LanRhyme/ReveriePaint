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

            val w = min(baseBitmap.width, targetResolution).coerceAtLeast(64)
            val h = min(baseBitmap.height, targetResolution).coerceAtLeast(64)

            val scaledBase = if (baseBitmap.width == w && baseBitmap.height == h) {
                baseBitmap
            } else {
                Bitmap.createScaledBitmap(baseBitmap, w, h, true)
            }

            val scaledPixels = IntArray(w * h)
            scaledBase.getPixels(scaledPixels, 0, w, 0, 0, w, h)

            // NCHW: 1 x 3 x H x W, 归一化到 [-1, 1]
            val floatBuffer = FloatBuffer.allocate(1 * 3 * h * w)
            val channelSize = w * h

            // R channel
            for (i in 0 until channelSize) {
                val r = (scaledPixels[i] shr 16) and 0xFF
                floatBuffer.put(i, (r / 127.5f) - 1.0f)
            }
            // G channel
            for (i in 0 until channelSize) {
                val g = (scaledPixels[i] shr 8) and 0xFF
                floatBuffer.put(channelSize + i, (g / 127.5f) - 1.0f)
            }
            // B channel
            for (i in 0 until channelSize) {
                val b = scaledPixels[i] and 0xFF
                floatBuffer.put(channelSize * 2 + i, (b / 127.5f) - 1.0f)
            }

            val tensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, h.toLong(), w.toLong()))
            tensor.use { inTensor ->
                val results = sess.run(mapOf(inputName to inTensor))
                results.use { res ->
                    if (res.size() == 0) return null
                    val outValue = res.get(0).value

                    // 解析输出为 NormalField
                    val normalArray = FloatArray(w * h * 3)
                    if (outValue is Array<*>) {
                        // 常见输出: [1, 3, H, W]
                        @Suppress("UNCHECKED_CAST")
                        val out4D = outValue as? Array<Array<Array<FloatArray>>>
                        if (out4D != null && out4D.isNotEmpty() && out4D[0].size >= 3) {
                            val rPlane = out4D[0][0]
                            val gPlane = out4D[0][1]
                            val bPlane = out4D[0][2]
                            var idx = 0
                            for (y in 0 until h) {
                                for (x in 0 until w) {
                                    val nx = rPlane[y][x]
                                    val ny = gPlane[y][x]
                                    val nz = bPlane[y][x].coerceAtLeast(0.01f)
                                    val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
                                    normalArray[idx++] = nx / len
                                    normalArray[idx++] = ny / len
                                    normalArray[idx++] = nz / len
                                }
                            }
                            return NormalField(w, h, normalArray)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 高性能 2.5D 几何曲面法线生成器 (无需神经网络，瞬时零延迟生成)
     * 利用多级距离场近似生成圆润饱满的肢体/衣物体积表面，同时结合线稿生成压折凹陷法线
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

        // 1. 构建初始高度图 H(x, y): 基于底色 Alpha
        val heightMap = FloatArray(w * h)
        for (i in 0 until w * h) {
            val a = (basePixels[i] ushr 24) and 0xFF
            heightMap[i] = a / 255.0f
        }

        // 2. 双重平滑以生成圆柱/球体隆起体积感 (模拟高斯模糊 / 距离场)
        val tempMap = FloatArray(w * h)
        boxBlurHorizontal(heightMap, tempMap, w, h, 8)
        boxBlurVertical(tempMap, heightMap, w, h, 8)

        // 3. 叠加线稿凹陷深度 (Lineart Creases)
        if (linePixels != null) {
            for (i in 0 until w * h) {
                val lp = linePixels[i]
                val la = (lp ushr 24) and 0xFF
                if (la > 10) {
                    val r = (lp shr 16) and 0xFF
                    val g = (lp shr 8) and 0xFF
                    val b = lp and 0xFF
                    val darkness = (255 - (r * 299 + g * 587 + b * 114) / 1000) / 255.0f
                    // 线稿处高度向下凹陷
                    heightMap[i] = max(0f, heightMap[i] - (la / 255f) * darkness * 0.45f)
                }
            }
        }

        // 4. 计算梯度偏导数并归一化为法线向量
        val normals = FloatArray(w * h * 3)
        val normalScale = 3.5f

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

        // 读取线稿像素 (用于光线投射自遮挡)
        val linePixels = if (config.lineArtOcclusion && lineBitmap != null) {
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

                            // 线稿光线遮挡 (自投影)
                            if (linePixels != null && illumination > 0.05f) {
                                // 沿光源入射反方向步进采样线稿
                                val stepX = (lx * 3.5f).toInt()
                                val stepY = (ly * 3.5f).toInt()
                                val sampleX = x - stepX
                                val sampleY = y - stepY
                                if (sampleX in 0 until outWidth && sampleY in 0 until outHeight) {
                                    val lp = linePixels[sampleY * outWidth + sampleX]
                                    val la = (lp ushr 24) and 0xFF
                                    if (la > 40) {
                                        illumination *= 0.35f
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

                            val finalAlpha = (shadowFactor * baseOpacity * (baseAlpha / 255.0f) * 255.0f + 0.5f)
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
