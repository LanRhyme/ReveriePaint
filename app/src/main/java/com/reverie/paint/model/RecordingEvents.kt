/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Compact binary encoding for the drawing-process recording (录制回放).
 *
 * The recording is stored as a single "recording" entry inside a .revp
 * project file (a ZIP container). Blob layout:
 *
 *   magic      8 bytes   "REVPREC1"
 *   version    u16       1
 *   docW       u16       document width at session start
 *   docH       u16       document height at session start
 *   flags      u8        bit0 = a snapshot (initial document) is embedded
 *   eventCount u32
 *   totalMs    u32       sum of all event deltas (playback duration)
 *   eventsLen  u32
 *   events     bytes     event stream, see below
 *   [snapshotLen u64]    only when flags.bit0 is set
 *   [snapshot  bytes]    raw source document (a .revp / .kra / .png file)
 *
 * Event stream: each event = [type u8][dt varint ms][payload]; dt is the
 * delta from the previous event (0 at session start). All multi-byte
 * integers are little-endian; floats are IEEE-754.
 *
 * Design goals: zero per-event object allocation on the hot path (the
 * recorder appends straight into a growable byte array) and zero-allocation
 * playback (a cursor walks the same byte array). A full painting session
 * typically stays well under a few MB.
 */
object RecordingEvents {
    const val MAGIC = "REVPREC1"
    const val VERSION = 1

    // Stroke events
    const val STROKE_START = 0x01
    const val STROKE_MOVE = 0x02
    const val STROKE_END = 0x03
    const val STROKE_CANCEL = 0x04

    // Brush/tool/layer context snapshot, emitted right before a stroke when
    // the context changed since the previous stroke (diff-based, so slider
    // tweaks between strokes are captured without hooking every setter)
    const val CONTEXT = 0x10

    // Layer structural operations (payload: op u8, index u16, arg string)
    const val LAYER_OP = 0x20

    // Tool operations: shapes, fills, text, transform, selection, ...
    const val TOOL_OP = 0x30

    // Filter commit (payload: index u16, type u8, p1..p4 f64, name str;
    // type 0xFF marks a LUT-based filter that cannot be rebuilt from scalars)
    const val FILTER = 0x40 // index u16, type u8(0xFF=LUT), p1..p4 f64, name str
    const val FILTER_LUT = 0x41 // index u16, kind u8(0=curves RGB 3x256B, 1=gradientMap 256 int LE), len u32, bytes, name str

    // ---- Layer op codes (LAYER_OP) ----
    const val L_ADD = 0
    const val L_REMOVE = 1
    const val L_SET_CURRENT = 2
    const val L_BLEND = 3
    const val L_VISIBLE = 4
    const val L_OPACITY = 5
    const val L_LOCKED = 6
    const val L_ALPHA_LOCKED = 7
    const val L_CLIPPED = 8
    const val L_RENAME = 9
    const val L_CLEAR = 10
    const val L_COPY = 11
    const val L_MOVE = 12
    const val L_MOVE_ABOVE = 13
    const val L_MOVE_TO_GROUP = 14
    const val L_MOVE_RELATIVE = 15
    const val L_MOVE_UP = 16
    const val L_MOVE_DOWN = 17
    const val L_MOVE_OUT = 18
    const val L_MERGE_DOWN = 19
    const val L_FLIP_H = 20
    const val L_FLIP_V = 21
    const val L_STAMP = 22
    const val L_ADD_GROUP = 23
    const val L_SET_BG = 24
    const val L_COLOR_LABEL = 25
    const val L_PASS_THROUGH = 26
    const val L_RASTERIZE = 27
    const val L_FLATTEN_GROUP = 28
    const val L_ADD_MASK = 29
    const val L_REMOVE_MASK = 30
    const val L_ADD_LAYER_TYPE = 31
    const val L_SOLO = 32
    const val L_APPLY_FILTER = 33

    // ---- Tool op codes (TOOL_OP; payload layout is op-specific) ----
    const val T_SHAPE = 0 // kind u8, x1..y2 f32, filled u8
    const val T_POLYGON = 1 // closed u8, count u16, x/y f32 pairs
    const val T_FILL = 2 // x f32, y f32, tolerance u16
    const val T_GRADIENT = 3 // x1..y2 f32, type u8
    const val T_TEXT = 4 // x f32, y f32, fontSize f32, text str
    const val T_LIQUIFY = 5 // fx..ty f32, mode u8, strength f32
    const val T_MOVE_CONTENT = 6 // dx f32, dy f32
    const val T_TRANSFORM = 7 // 7 f64
    const val T_PERSPECTIVE = 8 // 12 f64
    const val T_WARP = 9 // count u16, orig pairs, transf pairs, 4 f64
    const val T_CROP = 10 // x y w h u16
    const val T_SELECT_SHAPE = 11 // kind u8, x1..y2 f32
    const val T_SELECT_POLYGON = 12 // count u16, x/y f32 pairs
    const val T_LASSO = 13 // count u16, x/y f32 pairs
    const val T_CONTIGUOUS = 14 // x f32, y f32, tolerance u16
    const val T_SIMILAR = 15 // x f32, y f32, tolerance u16
    const val T_CLEAR_SELECTION = 16 // -
    const val T_SELECT_ALL = 17 // layer u16
    const val T_INVERT_SELECTION = 18 // -
    const val T_FEATHER = 19 // radius u16
    const val T_EXPAND = 20 // px u16
    const val T_CONTRACT = 21 // px u16
    const val T_SMOOTH = 22 // radius u16
    const val T_SELECT_MODE = 23 // mode u8
    const val T_LASSO_FILL = 24 // count u16, x/y f32 pairs
    const val T_LASSO_CLEAR = 25 // count u16, x/y f32 pairs
    const val T_LIQUIFY_SIZE = 26 // size f32
    const val T_SHAPE_STROKE_WIDTH = 27 // width f32
    const val T_LIQUIFY_BEGIN = 28 // - (one undo transaction per drag)
    const val T_LIQUIFY_END = 29 // -
    const val T_LIQUIFY_CANCEL = 30 // -
    const val T_LIQUIFY_LAYERS = 31 // count u16, layer indexes u16[] (before BEGIN)
    const val T_MOVE_CONTENT_LAYERS = 32 // count u16, layer indexes u16[] (before MOVE_CONTENT)
    const val T_TRANSFORM_LAYERS = 33 // count u16, layer indexes u16[] (before TRANSFORM)
    const val T_FILL_V2 = 34 // x/y f32, tolerance u16, sampleMerged u8
    const val T_GRADIENT_V2 = 35 // x1..y2 f32, type u8, repeat u8, reverse u8
}

/** Growable byte sink with little-endian primitive writers. */
class RecordingBuffer(
    initial: Int = 1 shl 15,
) {
    var data = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var cap = data.size * 2
        while (cap < size + extra) cap *= 2
        data = data.copyOf(cap)
    }

    fun u8(v: Int) {
        ensure(1)
        data[size++] = v.toByte()
    }

    fun u16(v: Int) {
        ensure(2)
        data[size++] = (v and 0xff).toByte()
        data[size++] = ((v ushr 8) and 0xff).toByte()
    }

    fun u32(v: Int) {
        ensure(4)
        for (i in 0 until 4) data[size++] = ((v ushr (i * 8)) and 0xff).toByte()
    }

    fun u64(v: Long) {
        ensure(8)
        for (i in 0 until 8) data[size++] = ((v ushr (i * 8)) and 0xff).toByte()
    }

    fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))

    fun f64(v: Double) = u64(java.lang.Double.doubleToLongBits(v))

    /** Unsigned LEB128-ish varint (7 bits per byte). Values must be >= 0. */
    fun varint(v: Int) {
        var x = v.coerceAtLeast(0)
        while (x and -128 != 0) {
            u8(x and 0x7f or 0x80)
            x = x ushr 7
        }
        u8(x)
    }

    fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varint(b.size)
        ensure(b.size)
        System.arraycopy(b, 0, data, size, b.size)
        size += b.size
    }

    fun writeBytes(
        src: ByteArray,
        off: Int = 0,
        len: Int = src.size,
    ) {
        ensure(len)
        System.arraycopy(src, off, data, size, len)
        size += len
    }
}

/** Cursor-based reader over a recording blob (zero-allocation). */
class RecordingReader(
    val data: ByteArray,
) {
    var pos = 0
    private val limit = data.size

    fun remaining() = limit - pos

    fun u8(): Int {
        if (pos >= limit) throw IndexOutOfBoundsException("recording truncated")
        return data[pos++].toInt() and 0xff
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or (u8() shl (i * 8))
        return v
    }

    fun u64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (u8().toLong() shl (i * 8))
        return v
    }

    fun f32(): Float = java.lang.Float.intBitsToFloat(u32())

    fun f64(): Double = java.lang.Double.longBitsToDouble(u64())

    fun varint(): Int {
        var x = 0
        var shift = 0
        while (true) {
            val b = u8()
            x = x or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IllegalStateException("bad varint")
        }
        return x
    }

    fun str(): String {
        val n = varint()
        if (n == 0) return ""
        if (pos + n > limit) throw IndexOutOfBoundsException("recording string truncated")
        val s = String(data, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun readBytes(n: Int): ByteArray {
        if (pos + n > limit) throw IndexOutOfBoundsException("recording truncated")
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

/** Parsed CONTEXT event: brush/tool/layer snapshot at a stroke start. */
data class ReplayContext(
    val toolMode: Int,
    val preset: Int,
    val size: Float,
    val opacity: Float,
    val flow: Float,
    val compositeOp: String,
    val color: String,
    val layer: Int,
)
