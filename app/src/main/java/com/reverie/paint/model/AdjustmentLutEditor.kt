/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 编辑节点附在渲染 LUT 后面：前 768/1024 字节保持不变，旧版引擎仍可读取。
 * layers.xml、配置快照和录制均保存整个字节数组，无需另建一套持久化状态。
 */
object AdjustmentLutEditor {
    data class Point(val x: Float, val y: Float)
    data class Curves(val channels: List<List<Point>>)
    data class Stop(val position: Float, val argb: Int)
    data class Gradient(val stops: List<Stop>, val reverse: Boolean)

    private const val MAGIC = 0x52564C31 // RVL1, big endian editor trailer
    private const val CURVES_SIZE = 768
    private const val GRADIENT_SIZE = 1024
    private const val MAX_POINTS = 4096
    private val identity = listOf(Point(0f, 0f), Point(255f, 255f))

    fun encodeCurves(lut: ByteArray, curves: Curves): ByteArray {
        require(lut.size >= CURVES_SIZE && valid(curves))
        val buffer = ByteBuffer.allocate(CURVES_SIZE + 4 + 16 + curves.channels.sumOf { it.size } * 8)
        buffer.put(lut, 0, CURVES_SIZE).putInt(MAGIC)
        curves.channels.forEach { points ->
            buffer.putInt(points.size)
            points.forEach { buffer.putFloat(it.x).putFloat(it.y) }
        }
        return buffer.array()
    }

    fun encodeGradient(lut: ByteArray, gradient: Gradient): ByteArray {
        require(lut.size >= GRADIENT_SIZE && valid(gradient))
        val buffer = ByteBuffer.allocate(GRADIENT_SIZE + 12 + gradient.stops.size * 8)
        buffer.put(lut, 0, GRADIENT_SIZE).putInt(MAGIC)
        buffer.putInt(if (gradient.reverse) 1 else 0).putInt(gradient.stops.size)
        gradient.stops.forEach { buffer.putFloat(it.position).putInt(it.argb) }
        return buffer.array()
    }

    fun decodeCurves(lut: ByteArray): Curves? {
        if (lut.size < CURVES_SIZE) return null
        readTrailer(lut, CURVES_SIZE) { buffer ->
            Curves(List(4) {
                List(readCount(buffer)) { Point(buffer.float, buffer.float) }
            }).takeIf { valid(it) && !buffer.hasRemaining() }
        }?.let { return it }

        // 老作品只有合成后的 LUT，无法逆推出主曲线与各通道原始控制点。
        // 从实际采样恢复可编辑曲线；调用方在未编辑时继续使用原始 LUT。
        val channels = List(3) { c ->
            val values = Array(256) { floatArrayOf((lut[c * 256 + it].toInt() and 255).toFloat()) }
            simplify(values).map { Point(it.toFloat(), values[it][0]) }
        }
        return if (channels[0] == channels[1] && channels[1] == channels[2]) {
            Curves(listOf(channels[0], identity, identity, identity))
        } else {
            Curves(listOf(identity) + channels)
        }
    }

    fun decodeGradient(lut: ByteArray): Gradient? {
        if (lut.size < GRADIENT_SIZE) return null
        readTrailer(lut, GRADIENT_SIZE) { buffer ->
            val reverse = buffer.int
            val stops = List(readCount(buffer)) { Stop(buffer.float, buffer.int) }
            Gradient(stops, reverse == 1).takeIf { reverse in 0..1 && valid(it) && !buffer.hasRemaining() }
        }?.let { return it }

        val values = Array(256) { i -> FloatArray(4) { (lut[i * 4 + it].toInt() and 255).toFloat() } }
        val stops = simplify(values).map { i ->
            var argb = 0
            for (c in 0..3) argb = argb or (values[i][c].toInt() shl (c * 8))
            Stop(i / 255f, argb)
        }
        return Gradient(stops, false)
    }

    private fun valid(curves: Curves): Boolean = curves.channels.size == 4 && curves.channels.all { points ->
        points.size in 2..MAX_POINTS && points.all { it.x in 0f..255f && it.y in 0f..255f }
    }

    private fun valid(gradient: Gradient): Boolean = gradient.stops.size in 1..MAX_POINTS &&
        gradient.stops.all { it.position in 0f..1f }

    private fun readCount(buffer: ByteBuffer): Int = buffer.int.also {
        require(it in 1..MAX_POINTS && it <= buffer.remaining() / 8)
    }

    private fun <T> readTrailer(lut: ByteArray, offset: Int, read: (ByteBuffer) -> T?): T? {
        if (lut.size < offset + 4) return null
        return try {
            val buffer = ByteBuffer.wrap(lut, offset, lut.size - offset)
            if (buffer.int == MAGIC) read(buffer) else null
        } catch (_: java.nio.BufferUnderflowException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 保留端点及变化位置，避免为旧 LUT 在面板上创建 256 个重叠手柄。 */
    private fun simplify(values: Array<FloatArray>): List<Int> {
        val kept = sortedSetOf(0, 255)
        fun split(start: Int, end: Int) {
            var worst = 0.75f // 小于一个 8-bit 色阶；原始渲染值由会话保留
            var index = -1
            for (i in start + 1 until end) {
                val t = (i - start).toFloat() / (end - start)
                for (c in values[i].indices) {
                    val predicted = values[start][c] + t * (values[end][c] - values[start][c])
                    val error = abs(values[i][c] - predicted)
                    if (error > worst) { worst = error; index = i }
                }
            }
            if (index >= 0) {
                kept.add(index)
                split(start, index)
                split(index, end)
            }
        }
        split(0, 255)
        return kept.toList()
    }
}
