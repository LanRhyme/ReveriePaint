/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.export

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 高性能纯 Kotlin GIF89a 动图编码器。
 * 针对移动端大图优化 NeuQuant 采样步长与 15-bit 颜色查找缓存，支持实时取消与透明通道。
 */
internal class AnimatedGifEncoder {
    private var width: Int = 0
    private var height: Int = 0
    private var transparentColor: Int? = null
    private var transparentIndex: Int = -1
    private var repeatCount: Int = 0 // 0 = 无限循环
    private var delayCentiseconds: Int = 8 // 100 / 12fps ≈ 8 (单位: 1/100 秒)
    private var isStarted: Boolean = false
    private var out: OutputStream? = null

    /** 设定帧率 (根据 fps 自动换算百分之一秒延迟) */
    fun setFramerate(fps: Int) {
        val f = fps.coerceIn(1, 100)
        delayCentiseconds = max(1, (100.0 / f).roundToInt())
    }

    /** 设定循环次数 (0 = 循环播放, -1 = 不循环, >0 = 指定次数) */
    fun setRepeat(repeat: Int) {
        repeatCount = repeat
    }

    /** 设定透明色匹配 (ARGB 颜色值, null 表示不启用透明背景) */
    fun setTransparent(color: Int?) {
        transparentColor = color
    }

    /** 开始写入 GIF 流 */
    fun start(outputStream: OutputStream): Boolean {
        out = outputStream
        isStarted = false
        transparentIndex = -1
        return true
    }

    /**
     * 添加单帧位图。
     * @param bitmap 待添加帧位图
     * @param isCancelled 取消状态回调，被触发时立即中断并返回 false
     */
    fun addFrame(bitmap: Bitmap, isCancelled: () -> Boolean = { false }): Boolean {
        val stream = out ?: return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        if (isCancelled()) return false

        if (!isStarted) {
            width = w
            height = h
            writeHeader(stream)
            writeLogicalScreenDescriptor(stream, w, h)
            if (repeatCount >= 0) {
                writeNetscapeApplicationExtension(stream, repeatCount)
            }
            isStarted = true
        }

        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (isCancelled()) return false

        // 分析透明像素
        var hasAlpha = false
        val checkAlpha = transparentColor != null
        if (checkAlpha) {
            for (p in pixels) {
                if ((p ushr 24) < 128) {
                    hasAlpha = true
                    break
                }
            }
        }

        if (isCancelled()) return false

        // 颜色量化：自适应采样步长，控制总样本数在 3000 左右，兼顾高质量与毫秒级速度
        val sampleFactor = max(1, totalPixels / 3000)
        val quantizer = NeuQuant(pixels, totalPixels, sampleFactor)
        val palette = quantizer.process(hasAlpha, isCancelled)
        if (isCancelled()) return false

        // 索引映射 (使用 15-bit RGB 缓存极大加速大图查找)
        val indexedPixels = ByteArray(totalPixels)
        if (hasAlpha) {
            transparentIndex = 0
            for (i in pixels.indices) {
                if (i % 32768 == 0 && isCancelled()) return false
                val p = pixels[i]
                if ((p ushr 24) < 128) {
                    indexedPixels[i] = 0
                } else {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    indexedPixels[i] = quantizer.lookup(r, g, b, hasAlpha = true).toByte()
                }
            }
        } else {
            transparentIndex = -1
            for (i in pixels.indices) {
                if (i % 32768 == 0 && isCancelled()) return false
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                indexedPixels[i] = quantizer.lookup(r, g, b, hasAlpha = false).toByte()
            }
        }

        if (isCancelled()) return false

        writeGraphicControlExtension(stream, hasAlpha, delayCentiseconds, transparentIndex)
        writeImageDescriptor(stream, w, h)
        writePalette(stream, palette)
        writePixels(stream, indexedPixels, w, h)
        stream.flush()
        return true
    }

    /** 结束并写入文件尾部标识符 */
    fun finish(): Boolean {
        val stream = out ?: return false
        return try {
            stream.write(0x3B) // ';' GIF Trailer
            stream.flush()
            isStarted = false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeHeader(os: OutputStream) {
        os.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    private fun writeLogicalScreenDescriptor(os: OutputStream, w: Int, h: Int) {
        writeShort(os, w)
        writeShort(os, h)
        os.write(0x70) // packed fields: 0 (no GCT), 7 (8 bits resolution), 0, 0
        os.write(0)    // background color index
        os.write(0)    // pixel aspect ratio
    }

    private fun writeNetscapeApplicationExtension(os: OutputStream, loop: Int) {
        os.write(0x21)
        os.write(0xFF)
        os.write(11)
        os.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        os.write(3)
        os.write(1)
        writeShort(os, loop)
        os.write(0)
    }

    private fun writeGraphicControlExtension(
        os: OutputStream,
        hasAlpha: Boolean,
        delayCs: Int,
        transIndex: Int,
    ) {
        os.write(0x21)
        os.write(0xF9)
        os.write(4)
        val transp = if (hasAlpha) 1 else 0
        val disp = if (hasAlpha) 2 else 1 // 2 = 恢复为背景色; 1 = 不处置
        val packed = (disp shl 2) or transp
        os.write(packed)
        writeShort(os, delayCs)
        os.write(if (hasAlpha) transIndex and 0xFF else 0)
        os.write(0)
    }

    private fun writeImageDescriptor(os: OutputStream, w: Int, h: Int) {
        os.write(0x2C)
        writeShort(os, 0)
        writeShort(os, 0)
        writeShort(os, w)
        writeShort(os, h)
        os.write(0x87)
    }

    private fun writePalette(os: OutputStream, palette: ByteArray) {
        os.write(palette, 0, palette.size)
        val remaining = 768 - palette.size
        if (remaining > 0) {
            os.write(ByteArray(remaining))
        }
    }

    private fun writePixels(os: OutputStream, pixels: ByteArray, w: Int, h: Int) {
        val encoder = LzwEncoder(w, h, pixels, 8)
        encoder.encode(os)
    }

    private fun writeShort(os: OutputStream, value: Int) {
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
    }
}

/**
 * 神经网络颜色量化算法 (NeuQuant) 带快速查找缓存。
 */
private class NeuQuant(
    private val pixels: IntArray,
    private val length: Int,
    private val sampleFactor: Int,
) {
    private val network = Array(256) { DoubleArray(4) }
    private val bias = DoubleArray(256)
    private val freq = DoubleArray(256)

    // 15-bit RGB 颜色索引高速缓存 (32768 项)
    private val colorCache = ShortArray(32768) { -1 }

    init {
        for (i in 0 until 256) {
            val v = i.toDouble()
            network[i][0] = v
            network[i][1] = v
            network[i][2] = v
            network[i][3] = 0.0
            freq[i] = 1.0 / 256.0
            bias[i] = 0.0
        }
    }

    fun process(hasAlpha: Boolean = false, isCancelled: () -> Boolean = { false }): ByteArray {
        learn(isCancelled)
        val palette = ByteArray(768)
        val startIdx = if (hasAlpha) 1 else 0
        if (hasAlpha) {
            palette[0] = 0
            palette[1] = 0
            palette[2] = 0
        }
        for (i in startIdx until 256) {
            palette[i * 3 + 0] = network[i][0].roundToInt().coerceIn(0, 255).toByte()
            palette[i * 3 + 1] = network[i][1].roundToInt().coerceIn(0, 255).toByte()
            palette[i * 3 + 2] = network[i][2].roundToInt().coerceIn(0, 255).toByte()
        }
        return palette
    }

    fun lookup(r: Int, g: Int, b: Int, hasAlpha: Boolean = false): Int {
        val cacheKey = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val cached = colorCache[cacheKey].toInt()
        if (cached >= 0) return cached

        var bestD = 1000000000.0
        val startIdx = if (hasAlpha) 1 else 0
        var best = startIdx
        for (i in startIdx until 256) {
            val dr = r - network[i][0]
            val dg = g - network[i][1]
            val db = b - network[i][2]
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        colorCache[cacheKey] = best.toShort()
        return best
    }

    private fun learn(isCancelled: () -> Boolean) {
        val totalSamples = kotlin.math.min(length, 10000)
        if (totalSamples <= 0) return
        val step = kotlin.math.max(1, length / totalSamples)
        var alpha = 1.0
        val alphaDecay = 1.0 / totalSamples

        var sampleIdx = 0
        for (i in 0 until totalSamples) {
            if (i % 1024 == 0 && isCancelled()) return
            val p = pixels[sampleIdx]
            val r = ((p shr 16) and 0xFF).toDouble()
            val g = ((p shr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()

            val best = contest(r, g, b)
            alterNeighbour(best, alpha, r, g, b)
            sampleIdx = (sampleIdx + step) % length
            alpha -= alphaDecay
        }
    }

    private fun contest(r: Double, g: Double, b: Double): Int {
        var bestD = Double.MAX_VALUE
        var bestBiasD = Double.MAX_VALUE
        var best = 0
        var bestBias = -1

        for (i in 0 until 256) {
            val dist = abs(network[i][0] - r) + abs(network[i][1] - g) + abs(network[i][2] - b)
            if (dist < bestD) {
                bestD = dist
                best = i
            }
            val biasDist = dist - bias[i]
            if (biasDist < bestBiasD) {
                bestBiasD = biasDist
                bestBias = i
            }
            val beta = 1.0 / 1024.0
            freq[i] -= beta * freq[i]
            bias[i] += beta * (1.0 / 256.0 - freq[i])
        }
        freq[best] += 1.0 / 1024.0
        bias[best] -= 1.0 / 1024.0
        return if (bestBias != -1) bestBias else best
    }

    private fun alterNeighbour(best: Int, alpha: Double, r: Double, g: Double, b: Double) {
        val lo = max(0, best - 8)
        val hi = min(255, best + 8)
        for (j in lo..hi) {
            val dist = abs(j - best)
            val a = alpha / (dist * dist + 1)
            network[j][0] += a * (r - network[j][0])
            network[j][1] += a * (g - network[j][1])
            network[j][2] += a * (b - network[j][2])
        }
    }
}

/**
 * GIF 专用 LZW 压缩器。
 */
private class LzwEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixAry: ByteArray,
    private val initCodeSize: Int,
) {
    private var curPixel = 0
    private var maxbits = 12
    private var maxmaxcode = 1 shl maxbits
    private var htab = IntArray(5003)
    private var codetab = IntArray(5003)
    private var hsize = 5003
    private var freeEnt = 0
    private var clearFlg = false
    private var nBits = 0
    private var maxcode = 0
    private var clearCode = 0
    private var eofCode = 0

    private var curAccum = 0
    private var curBits = 0
    private val accum = ByteArray(256)
    private var aCount = 0

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0) // 块结束
    }

    private fun nextPixel(): Int {
        if (curPixel >= pixAry.size) return -1
        return pixAry[curPixel++].toInt() and 0xFF
    }

    private fun compress(initBits: Int, os: OutputStream) {
        nBits = initBits
        maxcode = (1 shl nBits) - 1
        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        aCount = 0

        var ent = nextPixel()
        var hshift = 0
        var fcode = hsize
        while (fcode < 65536) {
            hshift++
            fcode *= 2
        }
        hshift = 8 - hshift
        clearHash()
        output(clearCode, os)

        var c = nextPixel()
        while (c != -1) {
            fcode = (c shl maxbits) + ent
            var i = (c shl hshift) xor ent
            if (htab[i] == fcode) {
                ent = codetab[i]
                c = nextPixel()
                continue
            } else if (htab[i] >= 0) {
                var disp = hsize - i
                if (i == 0) disp = 1
                var hit = false
                while (true) {
                    i -= disp
                    if (i < 0) i += hsize
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        hit = true
                        break
                    }
                    if (htab[i] < 0) break
                }
                if (hit) {
                    c = nextPixel()
                    continue
                }
            }
            output(ent, os)
            ent = c
            if (freeEnt < maxmaxcode) {
                codetab[i] = freeEnt++
                htab[i] = fcode
            } else {
                clearHash()
                freeEnt = clearCode + 2
                clearFlg = true
                output(clearCode, os)
            }
            c = nextPixel()
        }
        output(ent, os)
        output(eofCode, os)
    }

    private fun clearHash() {
        for (i in 0 until hsize) htab[i] = -1
    }

    private fun output(code: Int, os: OutputStream) {
        curAccum = curAccum or (code shl curBits)
        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xFF).toByte(), os)
            curAccum = curAccum ushr 8
            curBits -= 8
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                nBits = initCodeSize + 1
                maxcode = (1 shl nBits) - 1
                clearFlg = false
            } else {
                nBits++
                maxcode = if (nBits == maxbits) maxmaxcode else (1 shl nBits) - 1
            }
        }

        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xFF).toByte(), os)
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            flushChar(os)
        }
    }

    private fun charOut(c: Byte, os: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) {
            flushChar(os)
        }
    }

    private fun flushChar(os: OutputStream) {
        if (aCount > 0) {
            os.write(aCount)
            os.write(accum, 0, aCount)
            aCount = 0
        }
    }
}
