/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** Self-contained pattern operation; a zero byte count reuses the preceding pattern in the stream. */
data class PatternFillEvent(
    val layer: Int,
    val wholeLayer: Boolean,
    val x: Int = 0,
    val y: Int = 0,
    val tolerance: Int = 16,
    val sampleMerged: Boolean = true,
    val expand: Int = 0,
    val feather: Int = 0,
    val closeGap: Int = 4,
    val opacity: Double = 1.0,
    val compositeOp: String = "normal",
    val png: ByteArray,
) {
    init {
        require(layer >= 0 && opacity.isFinite() && opacity in 0.0..1.0)
        require(png.isNotEmpty() && png.size <= MAX_PATTERN_BYTES)
        require(compositeOp.length in 1..64)
    }

    fun writeTo(buffer: RecordingBuffer, previous: ByteArray?) {
        buffer.u32(layer)
        buffer.u8(if (wholeLayer) 1 else 0)
        buffer.u32(x)
        buffer.u32(y)
        buffer.u8(tolerance.coerceIn(1, 100))
        buffer.u8(if (sampleMerged) 1 else 0)
        buffer.u32(expand.coerceIn(-32, 64))
        buffer.u8(feather.coerceIn(0, 32))
        buffer.u8(closeGap.coerceIn(0, 32))
        buffer.f64(opacity)
        buffer.str(compositeOp)
        val reuse = previous != null && png.contentEquals(previous)
        buffer.u32(if (reuse) 0 else png.size)
        if (!reuse) buffer.writeBytes(png)
    }

    companion object {
        const val MAX_PATTERN_BYTES = 16 * 1024 * 1024
        const val MAX_PATTERN_DIMENSION = 2048

        fun readFrom(reader: RecordingReader, previous: ByteArray?): PatternFillEvent {
            val layer = reader.u32()
            val wholeLayer = reader.u8() != 0
            val x = reader.u32()
            val y = reader.u32()
            val tolerance = reader.u8()
            val merged = reader.u8() != 0
            val expand = reader.u32()
            val feather = reader.u8()
            val closeGap = reader.u8()
            val opacity = reader.f64()
            val nameLength = reader.varint()
            require(nameLength in 1..64 && nameLength <= reader.remaining())
            val compositeOp = String(reader.readBytes(nameLength), Charsets.UTF_8)
            val count = reader.u32()
            require(count in 0..MAX_PATTERN_BYTES && count <= reader.remaining())
            val png = if (count == 0) requireNotNull(previous) else reader.readBytes(count)
            return PatternFillEvent(layer, wholeLayer, x, y, tolerance, merged, expand, feather,
                closeGap, opacity, compositeOp, png)
        }
    }
}
