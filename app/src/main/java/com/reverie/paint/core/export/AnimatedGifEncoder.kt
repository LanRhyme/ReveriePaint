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
 * 纯 Kotlin GIF89a 动图编码器。
 * 支持调色板神经量化 (NeuQuant)、LZW 变长编码、透明通道支持及无限循环扩展。
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

    /** 添加单帧位图 */
    fun addFrame(bitmap: Bitmap): Boolean {
        val stream = out ?: return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

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

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

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

        // 颜色量化 (提取最多 256 色的调色板)
        val quantizer = NeuQuant(pixels, w * h, 10)
        val palette = quantizer.process()

        // 索引映射
        val indexedPixels = ByteArray(w * h)
        if (hasAlpha) {
            // 找到最不常用的颜色或保留第 0 位为透明索引
            transparentIndex = 0
            for (i in pixels.indices) {
                val p = pixels[i]
                if ((p ushr 24) < 128) {
                    indexedPixels[i] = 0
                } else {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    var idx = quantizer.lookup(r, g, b)
                    if (idx == 0) idx = 1
                    indexedPixels[i] = idx.toByte()
                }
            }
        } else {
            transparentIndex = -1
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                indexedPixels[i] = quantizer.lookup(r, g, b).toByte()
            }
        }

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
        try {
            stream.write(0x3B) // ';' GIF Trailer
            stream.flush()
            isStarted = false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun writeHeader(os: OutputStream) {
        os.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    private fun writeLogicalScreenDescriptor(os: OutputStream, w: Int, h: Int) {
        writeShort(os, w)
        writeShort(os, h)
        // 没有全局色表，在每个图像描述符写入局部色表
        os.write(0x70) // packed fields: 0 (no GCT), 7 (8 bits resolution), 0, 0
        os.write(0)    // background color index
        os.write(0)    // pixel aspect ratio
    }

    private fun writeNetscapeApplicationExtension(os: OutputStream, loop: Int) {
        os.write(0x21) // Extension Introducer
        os.write(0xFF) // Application Extension
        os.write(11)   // Block Size
        os.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        os.write(3)    // Sub-block Size
        os.write(1)    // Sub-block ID
        writeShort(os, loop)
        os.write(0)    // Terminator
    }

    private fun writeGraphicControlExtension(
        os: OutputStream,
        hasAlpha: Boolean,
        delayCs: Int,
        transIndex: Int,
    ) {
        os.write(0x21) // Extension Introducer
        os.write(0xF9) // Graphic Control Label
        os.write(4)    // Block Size
        val transp = if (hasAlpha) 1 else 0
        val disp = if (hasAlpha) 2 else 1 // 2 = 恢复为背景色 (防止重影); 1 = 不处置
        val packed = (disp shl 2) or transp
        os.write(packed)
        writeShort(os, delayCs)
        os.write(if (hasAlpha) transIndex and 0xFF else 0)
        os.write(0) // Terminator
    }

    private fun writeImageDescriptor(os: OutputStream, w: Int, h: Int) {
        os.write(0x2C) // Image Separator
        writeShort(os, 0) // Left
        writeShort(os, 0) // Top
        writeShort(os, w) // Width
        writeShort(os, h) // Height
        // 局部色表标志: 1 (含局部色表), 0 (不隔行), 0 (不排序), 7 (256 色: 2^(7+1))
        os.write(0x87)
    }

    private fun writePalette(os: OutputStream, palette: ByteArray) {
        os.write(palette, 0, palette.size)
        // 确保写满 256 * 3 字节
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
 * 神经网络颜色量化算法 (NeuQuant)。
 * 将任意 RGB 像素集缩减为 256 色的高品质调色板。
 */
private class NeuQuant(
    private val pixels: IntArray,
    private val length: Int,
    private val sampleFactor: Int,
) {
    private val network = Array(256) { DoubleArray(4) }
    private val netIndex = IntArray(256)
    private val bias = DoubleArray(256)
    private val freq = DoubleArray(256)

    init {
        for (i in 0 until 256) {
            val v = (i shl 4).toDouble()
            network[i][0] = v
            network[i][1] = v
            network[i][2] = v
            network[i][3] = 0.0
            freq[i] = 1.0 / 256.0
            bias[i] = 0.0
        }
    }

    fun process(): ByteArray {
        learn()
        buildIndex()
        val palette = ByteArray(768)
        for (i in 0 until 256) {
            palette[i * 3 + 0] = network[i][0].roundToInt().coerceIn(0, 255).toByte()
            palette[i * 3 + 1] = network[i][1].roundToInt().coerceIn(0, 255).toByte()
            palette[i * 3 + 2] = network[i][2].roundToInt().coerceIn(0, 255).toByte()
        }
        return palette
    }

    fun lookup(r: Int, g: Int, b: Int): Int {
        var bestD = 1000000000.0
        var best = -1
        for (i in 0 until 256) {
            val dr = r - network[i][0]
            val dg = g - network[i][1]
            val db = b - network[i][2]
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun learn() {
        val samplePixels = length / sampleFactor
        val nCycles = 100
        val step = max(1, samplePixels / nCycles)
        var alpha = 1.0
        val alphaDecay = 1.0 / nCycles

        var sampleIdx = 0
        for (cycle in 0 until nCycles) {
            var i = 0
            while (i < samplePixels && sampleIdx < length) {
                val p = pixels[sampleIdx]
                val b = (p and 0xFF).toDouble()
                val g = ((p shr 8) and 0xFF).toDouble()
                val r = ((p shr 16) and 0xFF).toDouble()

                val best = contest(b, g, r)
                alterNeighbour(best, alpha, b, g, r)
                sampleIdx += sampleFactor
                i++
            }
            alpha -= alphaDecay
            if (sampleIdx >= length) sampleIdx = 0
        }
    }

    private fun contest(b: Double, g: Double, r: Double): Int {
        var bestD = Double.MAX_VALUE
        var bestBiasD = Double.MAX_VALUE
        var best = -1
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

    private fun alterNeighbour(best: Int, alpha: Double, b: Double, g: Double, r: Double) {
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

    private fun buildIndex() {
        for (i in 0 until 256) {
            netIndex[i] = i
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
                clearFlg = true
                clearHash()
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
            curAccum = curAccum shr 8
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
                curAccum = curAccum shr 8
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
