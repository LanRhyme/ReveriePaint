/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingEvents.CONTEXT
import com.reverie.paint.model.RecordingEvents.CONTEXT_EXT
import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.FILTER_LUT
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.MAGIC
import com.reverie.paint.model.RecordingEvents.STROKE_CANCEL
import com.reverie.paint.model.RecordingEvents.STROKE_END
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.STROKE_START
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import com.reverie.paint.model.RecordingEvents.VERSION
import com.reverie.paint.model.RecordingReader
import java.io.File

/** Parsed recording blob (events + optional initial-document snapshot). */
class ParsedRecording(
    val events: ByteArray,
    val docW: Int,
    val docH: Int,
    val snapshot: ByteArray?,
    val eventCount: Int,
    val totalMs: Long,
)

/**
 * Drawing-session recorder: captures strokes, brush context and document
 * operations as a compact binary event stream, plus an optional snapshot of
 * the initial document so playback can rebuild the pre-session state.
 *
 * Memory: events append straight into a growable byte array (no per-event
 * objects); a typical full painting session stays well under a few MB. The
 * snapshot is kept as a file on disk, never in RAM.
 */
class PaintRecorder {
    private var buffer: RecordingBuffer? = null
    // Guards buffer/eventCount/lastEventMs between main-thread emit() and
    // render-thread serialize(); snapshotBytes carries the copied event
    // region out of the lock.
    private val ioLock = Any()
    private var snapshotBytes: ByteArray? = null
    private var lastEventMs = 0L
    private var sessionStartMs = 0L

    /** Written on the render thread (beginSession), read on the main
     *  thread (touch hooks and op hooks) - must be volatile for
     *  cross-thread visibility, otherwise strokes could silently miss
     *  recording. */
    @Volatile
    var recording = false
        private set
    var sessionW = 0
        private set
    var sessionH = 0
        private set
    var snapshotFile: File? = null
        private set
    var eventCount = 0
        private set

    // Context diff state (sentinel values = unknown / not yet captured)
    private var lastToolMode = -2
    private var lastPreset = -2
    private var lastSize = Double.NaN
    private var lastOpacity = Double.NaN
    private var lastFlow = Double.NaN
    private var lastCompositeOp: String? = null
    private var lastColor: String? = null
    private var lastLayer = -2

    /** Start a recording session. [snapshotSource] is the document file the
     *  session started from (copied to [snapshotTempDir]); null for a blank
     *  new canvas. A previous session is discarded first. */
    fun beginSession(
        w: Int,
        h: Int,
        snapshotSource: File?,
        snapshotTempDir: File,
    ) {
        endSession()
        buffer = RecordingBuffer()
        recording = true
        sessionW = w
        sessionH = h
        eventCount = 0
        lastEventMs = android.os.SystemClock.elapsedRealtime()
        sessionStartMs = lastEventMs
        android.util.Log.d("ReverieRec", "beginSession w=$w h=$h snap=${snapshotSource != null}")
        lastToolMode = -2
        lastPreset = -2
        lastSize = Double.NaN
        lastOpacity = Double.NaN
        lastFlow = Double.NaN
        lastCompositeOp = null
        lastColor = null
        lastLayer = -2
        snapshotFile = null
        if (snapshotSource != null && snapshotSource.exists() && snapshotSource.length() > 0) {
            try {
                snapshotTempDir.mkdirs()
                val ext = snapshotSource.extension
                val target = File(snapshotTempDir, "initial" + if (ext.isEmpty()) "" else ".$ext")
                snapshotSource.inputStream().use { i ->
                    target.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
                }
                snapshotFile = target
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "recording snapshot copy failed", e)
                snapshotFile = null
            }
        }
    }

    /** Stop and discard the current session (temp snapshot file removed). */
    fun endSession() {
        synchronized(ioLock) {
            recording = false
            buffer = null
            eventCount = 0
            snapshotBytes = null
        }
        snapshotFile?.delete()
        snapshotFile = null
    }

    /** Serialize the session into the "recording" blob; null if empty.
     *  Thread-safety: emit() writes the buffer on the main thread while this
     *  runs on the render thread (autosave); ioLock guards the shared state
     *  snapshot so a concurrent stroke can't tear the serialized stream. */
    fun serialize(): ByteArray? {
        val b: RecordingBuffer
        val count: Int
        val durationMs: Int
        val snap: java.io.File?
        synchronized(ioLock) {
            val buf = buffer ?: run {
                android.util.Log.d("ReverieRec", "serialize: no session")
                return null
            }
            count = eventCount
            if (count == 0) {
                android.util.Log.d("ReverieRec", "serialize: zero events")
                return null
            }
            b = buf
            // Snapshot exactly the used region: emit() may append concurrently.
            val used = ByteArray(buf.size)
            System.arraycopy(buf.data, 0, used, 0, buf.size)
            snapshotBytes = used
            durationMs =
                ((lastEventMs - sessionStartMs).coerceAtLeast(0L)).toInt().coerceAtMost(Int.MAX_VALUE)
            snap = snapshotFile
        }
        val snapBytes =
            if (snap != null) {
                try {
                    snap.readBytes()
                } catch (e: Exception) {
                    android.util.Log.e("ReveriePaint", "recording snapshot read failed", e)
                    null
                }
            } else {
                null
            }
        val used = snapshotBytes ?: return null
        snapshotBytes = null
        val out = RecordingBuffer(64 + used.size + (snapBytes?.size ?: 0))
        out.writeBytes(MAGIC.toByteArray(Charsets.US_ASCII))
        out.u16(VERSION)
        out.u16(sessionW)
        out.u16(sessionH)
        out.u8(if (snapBytes != null) 1 else 0)
        out.u32(count)
        out.u32(durationMs)
        out.u32(used.size)
        out.writeBytes(used, 0, used.size)
        if (snapBytes != null) {
            out.u64(snapBytes.size.toLong())
            out.writeBytes(snapBytes)
        }
        return out.data.copyOf(out.size)
    }

    // ---- Event emission (main thread; ignored while not recording) ----

    private fun emit(
        type: Int,
        writePayload: (RecordingBuffer) -> Unit,
    ) {
        synchronized(ioLock) {
            val b = buffer ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val dt = (now - lastEventMs).coerceAtLeast(0L)
            lastEventMs = now
            b.u8(type)
            b.varint(dt.toInt().coerceAtMost(Int.MAX_VALUE))
            writePayload(b)
            eventCount++
        }
    }

    fun strokeStart(
        x: Float,
        y: Float,
        pressure: Float,
    ) = emit(STROKE_START) {
        it.f32(x)
        it.f32(y)
        it.f32(pressure)
    }

    fun strokeMove(
        x: Float,
        y: Float,
        pressure: Float,
    ) = emit(STROKE_MOVE) {
        it.f32(x)
        it.f32(y)
        it.f32(pressure)
    }

    fun strokeEnd() = emit(STROKE_END) {}

    fun strokeCancel() = emit(STROKE_CANCEL) {}

    /** Diff-based context capture; emits a CONTEXT event only on change. */
    fun captureContext(
        toolMode: Int,
        preset: Int,
        size: Double,
        opacity: Double,
        flow: Double,
        compositeOp: String,
        color: String,
        layer: Int,
    ) {
        if (toolMode == lastToolMode &&
            preset == lastPreset &&
            size == lastSize &&
            opacity == lastOpacity &&
            flow == lastFlow &&
            compositeOp == lastCompositeOp &&
            color == lastColor &&
            layer == lastLayer
        ) {
            return
        }
        lastToolMode = toolMode
        lastPreset = preset
        lastSize = size
        lastOpacity = opacity
        lastFlow = flow
        lastCompositeOp = compositeOp
        lastColor = color
        lastLayer = layer
        emit(CONTEXT) {
            it.u8(toolMode.coerceIn(0, 255))
            // 0xFFFF sentinel encodes "no preset / no layer" (-1); 0xFFFE cap
            // keeps the sentinel unambiguous.
            it.u16(if (preset < 0) 0xFFFF else preset.coerceIn(0, 0xFFFE))
            it.f32(size.toFloat())
            it.f32(opacity.toFloat())
            it.f32(flow.toFloat())
            it.str(compositeOp)
            it.str(color)
            it.u16(if (layer < 0) 0xFFFF else layer.coerceIn(0, 0xFFFE))
        }
    }

    fun layerOp(
        op: Int,
        i: Int = 0,
        arg: String = "",
    ) = emit(LAYER_OP) {
        it.u8(op)
        it.u16(i.coerceIn(0, 65535))
        it.str(arg)
    }

    fun toolOp(
        op: Int,
        writePayload: (RecordingBuffer) -> Unit = {},
    ) = emit(TOOL_OP) {
        it.u8(op)
        writePayload(it)
    }

    fun pointsOp(
        op: Int,
        points: List<Pair<Int, Int>>,
    ) = toolOp(op) {
        it.u16(points.size.coerceIn(0, 65535))
        for ((x, y) in points) {
            it.f32(x.toFloat())
            it.f32(y.toFloat())
        }
    }

    fun filterCommit(
        index: Int,
        filterType: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
        name: String,
    ) = emit(FILTER) {
        it.u16(index.coerceIn(0, 65535))
        it.u8(if (filterType < 0) 0xFF else filterType.coerceIn(0, 255))
        it.f64(p1)
        it.f64(p2)
        it.f64(p3)
        it.f64(p4)
        it.str(name)
    }

    /** Commit a LUT-based filter (curves / gradient map) with its full lookup table. */
    fun filterLutCommit(
        index: Int,
        kind: Int,
        bytes: ByteArray,
        name: String,
    ) = emit(FILTER_LUT) {
        it.u16(index.coerceIn(0, 65535))
        it.u8(kind.coerceIn(0, 255))
        it.u32(bytes.size)
        it.writeBytes(bytes)
        it.str(name)
    }

    /**
     * Extended brush context emitted right after every [CONTEXT] event.
     * Carries the shape/dynamics parameters that CONTEXT v1 omits so replays
     * reproduce softness/spacing/scatter/smudge/airbrush behaviour.
     */
    fun captureContextExt(
        softness: Double,
        spacing: Double,
        angle: Double,
        scatter: Double,
        rotation: Double,
        ratio: Double,
        sharpness: Double,
        smudgeRate: Double,
        smudgeLength: Double,
        secondaryColor: String,
        airbrushEnabled: Boolean,
        airbrushRate: Double,
    ) = emit(CONTEXT_EXT) {
        it.f32(softness.toFloat())
        it.f32(spacing.toFloat())
        it.f32(angle.toFloat())
        it.f32(scatter.toFloat())
        it.f32(rotation.toFloat())
        it.f32(ratio.toFloat())
        it.f32(sharpness.toFloat())
        it.f32(smudgeRate.toFloat())
        it.f32(smudgeLength.toFloat())
        it.str(secondaryColor)
        it.u8(if (airbrushEnabled) 1 else 0)
        it.f32(airbrushRate.toFloat())
    }

    companion object {        /** Parse a recording blob; null on any error. */
        fun parse(data: ByteArray): ParsedRecording? {
            val r = RecordingReader(data)
            return try {
                val magic = String(r.readBytes(8), Charsets.US_ASCII)
                if (magic != MAGIC) return null
                if (r.u16() != VERSION) return null
                val w = r.u16()
                val h = r.u16()
                val flags = r.u8()
                val eventCount = r.u32()
                val totalMs = r.u32().toLong()
                val eventsLen = r.u32()
                val events = r.readBytes(eventsLen)
                val snapshot = if (flags and 1 != 0) r.readBytes(r.u64().toInt()) else null
                ParsedRecording(events, w, h, snapshot, eventCount, totalMs)
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "recording parse failed", e)
                null
            }
        }
    }
}
