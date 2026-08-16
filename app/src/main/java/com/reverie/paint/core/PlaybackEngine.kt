package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.model.RecordingEvents.CONTEXT
import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.L_ADD
import com.reverie.paint.model.RecordingEvents.L_ADD_GROUP
import com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE
import com.reverie.paint.model.RecordingEvents.L_ADD_MASK
import com.reverie.paint.model.RecordingEvents.L_ALPHA_LOCKED
import com.reverie.paint.model.RecordingEvents.L_APPLY_FILTER
import com.reverie.paint.model.RecordingEvents.L_BLEND
import com.reverie.paint.model.RecordingEvents.L_CLEAR
import com.reverie.paint.model.RecordingEvents.L_CLIPPED
import com.reverie.paint.model.RecordingEvents.L_COLOR_LABEL
import com.reverie.paint.model.RecordingEvents.L_COPY
import com.reverie.paint.model.RecordingEvents.L_FLATTEN_GROUP
import com.reverie.paint.model.RecordingEvents.L_FLIP_H
import com.reverie.paint.model.RecordingEvents.L_FLIP_V
import com.reverie.paint.model.RecordingEvents.L_LOCKED
import com.reverie.paint.model.RecordingEvents.L_MERGE_DOWN
import com.reverie.paint.model.RecordingEvents.L_MOVE
import com.reverie.paint.model.RecordingEvents.L_MOVE_ABOVE
import com.reverie.paint.model.RecordingEvents.L_MOVE_DOWN
import com.reverie.paint.model.RecordingEvents.L_MOVE_OUT
import com.reverie.paint.model.RecordingEvents.L_MOVE_RELATIVE
import com.reverie.paint.model.RecordingEvents.L_MOVE_TO_GROUP
import com.reverie.paint.model.RecordingEvents.L_MOVE_UP
import com.reverie.paint.model.RecordingEvents.L_OPACITY
import com.reverie.paint.model.RecordingEvents.L_PASS_THROUGH
import com.reverie.paint.model.RecordingEvents.L_RASTERIZE
import com.reverie.paint.model.RecordingEvents.L_REMOVE
import com.reverie.paint.model.RecordingEvents.L_REMOVE_MASK
import com.reverie.paint.model.RecordingEvents.L_RENAME
import com.reverie.paint.model.RecordingEvents.L_SET_BG
import com.reverie.paint.model.RecordingEvents.L_SET_CURRENT
import com.reverie.paint.model.RecordingEvents.L_SOLO
import com.reverie.paint.model.RecordingEvents.L_STAMP
import com.reverie.paint.model.RecordingEvents.L_VISIBLE
import com.reverie.paint.model.RecordingEvents.STROKE_CANCEL
import com.reverie.paint.model.RecordingEvents.STROKE_END
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.STROKE_START
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import com.reverie.paint.model.RecordingEvents.T_CLEAR_SELECTION
import com.reverie.paint.model.RecordingEvents.T_CONTIGUOUS
import com.reverie.paint.model.RecordingEvents.T_CONTRACT
import com.reverie.paint.model.RecordingEvents.T_CROP
import com.reverie.paint.model.RecordingEvents.T_EXPAND
import com.reverie.paint.model.RecordingEvents.T_FEATHER
import com.reverie.paint.model.RecordingEvents.T_FILL
import com.reverie.paint.model.RecordingEvents.T_GRADIENT
import com.reverie.paint.model.RecordingEvents.T_INVERT_SELECTION
import com.reverie.paint.model.RecordingEvents.T_LASSO
import com.reverie.paint.model.RecordingEvents.T_LASSO_CLEAR
import com.reverie.paint.model.RecordingEvents.T_LASSO_FILL
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_SIZE
import com.reverie.paint.model.RecordingEvents.T_MOVE_CONTENT
import com.reverie.paint.model.RecordingEvents.T_PERSPECTIVE
import com.reverie.paint.model.RecordingEvents.T_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL
import com.reverie.paint.model.RecordingEvents.T_SELECT_MODE
import com.reverie.paint.model.RecordingEvents.T_SELECT_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE_STROKE_WIDTH
import com.reverie.paint.model.RecordingEvents.T_SIMILAR
import com.reverie.paint.model.RecordingEvents.T_SMOOTH
import com.reverie.paint.model.RecordingEvents.T_TEXT
import com.reverie.paint.model.RecordingEvents.T_TRANSFORM
import com.reverie.paint.model.RecordingEvents.T_WARP
import com.reverie.paint.model.RecordingReader
import java.io.File
import kotlin.math.roundToInt

/**
 * Playback session: the parsed recording for one project. All playback
 * state is mutated only on the render thread (except UI-facing Compose
 * state, which is safe to write from any thread via the snapshot system).
 */
class ReplaySession(
    val events: ByteArray,
    val docW: Int,
    val docH: Int,
    val snapshotFile: File?,
    val totalMs: Long,
    val eventCount: Int,
) {
    var isPlaying by mutableStateOf(false)
        internal set
    var progress by mutableFloatStateOf(0f)
        internal set
    var elapsedMs by mutableLongStateOf(0L)
        internal set
    var speed by mutableFloatStateOf(1f)
        internal set

    internal val reader = RecordingReader(events)
    internal var currentMs = 0L
    internal var pendingStep: Runnable? = null
    internal var lastProgressWallMs = 0L

    /** Stop playback and free the session's temp snapshot file. The vm
     *  removes any pending step callback first (see [PaintViewModel.pauseReplay]). */
    fun stop() {
        pendingStep = null
        isPlaying = false
        snapshotFile?.delete()
    }

    companion object {
        /** Read the "recording" entry from a .revp and parse it. */
        fun load(
            projectFile: File,
            tempDir: File,
        ): ReplaySession? {
            var data: ByteArray? = null
            try {
                val zip = java.util.zip.ZipFile(projectFile)
                try {
                    val entry = zip.getEntry("recording") ?: return null
                    data = zip.getInputStream(entry).use { it.readBytes() }
                } finally {
                    zip.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "replay entry read failed", e)
                return null
            }
            val parsed = PaintRecorder.parse(data!!) ?: return null
            var temp: File? = null
            if (parsed.snapshot != null) {
                try {
                    tempDir.mkdirs()
                    // Sniff the source format: PNG sources flatten on load, so
                    // the temp file must keep the .png extension for loadPng
                    val snap = parsed.snapshot
                    val isPng =
                        snap.size >= 8 &&
                            snap[0] == 0x89.toByte() &&
                            snap[1] == 'P'.code.toByte() &&
                            snap[2] == 'N'.code.toByte() &&
                            snap[3] == 'G'.code.toByte()
                    temp = File(tempDir, if (isPng) "replay_snapshot.png" else "replay_snapshot.revp")
                    temp.writeBytes(snap)
                } catch (e: Exception) {
                    android.util.Log.e("ReveriePaint", "replay snapshot write failed", e)
                    temp = null
                }
            }
            return ReplaySession(
                events = parsed.events,
                docW = parsed.docW,
                docH = parsed.docH,
                snapshotFile = temp,
                totalMs = parsed.totalMs,
                eventCount = parsed.eventCount,
            )
        }
    }
}

// ---- Playback control (UI thread entry points) ----

/** Toggle play/resume; restarts from the beginning when finished. */
internal fun PaintViewModel.playReplay() {
    val s = replaySession ?: return
    if (s.progress >= 1f) {
        seekReplay(0f)
        s.isPlaying = true
        scheduleReplayStep(s)
    } else if (!s.isPlaying) {
        s.isPlaying = true
        scheduleReplayStep(s)
    }
}

internal fun PaintViewModel.pauseReplay() {
    val s = replaySession ?: return
    s.isPlaying = false
    s.pendingStep?.let { renderHandler?.removeCallbacks(it) }
    s.pendingStep = null
}

internal fun PaintViewModel.setReplaySpeed(v: Float) {
    replaySession?.let { it.speed = v.coerceIn(0.5f, 8f) }
}

/** Scrub to a fraction (0..1) of the playback; resets and fast-forwards. */
internal fun PaintViewModel.seekReplay(fraction: Float) {
    val s = replaySession ?: return
    pauseReplay()
    val h = renderHandler ?: return
    h.post { seekLocked(s, fraction.coerceIn(0f, 1f)) }
}

/** Leave the replay page (cancels playback, frees the session). */
internal fun PaintViewModel.exitReplay() {
    replaySession?.stop()
    replaySession = null
    goHome()
}

// ---- Render-thread playback engine ----

internal fun PaintViewModel.scheduleReplayStep(s: ReplaySession) {
    val h = renderHandler ?: return
    val r = Runnable { replayStepLocked(s) }
    s.pendingStep = r
    h.post(r)
}

private fun PaintViewModel.replayStepLocked(s: ReplaySession) {
    s.pendingStep = null
    if (!s.isPlaying) return
    val r = s.reader
    if (r.remaining() <= 0) {
        finishReplayLocked(s)
        return
    }
    val type = r.u8()
    val dt = r.varint()
    s.currentMs += dt
    dispatchReplayLocked(type, r)
    if (r.remaining() <= 0) {
        finishReplayLocked(s)
        return
    }
    updateReplayProgress(s)
    val h = renderHandler ?: return
    val d = (dt / s.speed).toLong().coerceIn(1L, 500L)
    val next = Runnable { replayStepLocked(s) }
    s.pendingStep = next
    h.postDelayed(next, d)
}

private fun PaintViewModel.finishReplayLocked(s: ReplaySession) {
    s.pendingStep = null
    s.isPlaying = false
    s.progress = 1f
    s.elapsedMs = s.totalMs
    scheduleRender(immediate = true)
    refreshLayerThumbs()
}

private fun PaintViewModel.updateReplayProgress(s: ReplaySession) {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - s.lastProgressWallMs >= 32) {
        s.lastProgressWallMs = now
        s.elapsedMs = s.currentMs
        s.progress =
            if (s.totalMs > 0) (s.currentMs.toFloat() / s.totalMs).coerceIn(0f, 1f) else 1f
    }
}

/** Runs inside a runCore op (render thread, before any replay dispatch). */
internal fun PaintViewModel.resetReplayDocLocked(s: ReplaySession) {
    val snap = s.snapshotFile
    val ok =
        if (snap != null && snap.exists()) {
            if (snap.extension.equals("png", ignoreCase = true)) {
                ReverieCoreBridge.loadPng(snap.absolutePath)
            } else {
                ReverieCoreBridge.loadRevp(snap.absolutePath)
            }
        } else {
            ReverieCoreBridge.newDocument(s.docW, s.docH)
        }
    if (ok) {
        coreW = ReverieCoreBridge.docWidth()
        coreH = ReverieCoreBridge.docHeight()
        renderW = coreW
        renderH = coreH
        displayBufferInvalid = true
        // Paint the initial frame right away so the canvas isn't stale
        // while the first stroke event is still queued
        scheduleRender(immediate = true)
    }
}

/** Fast-forward on the render thread: dispatch events with no pacing. */
private fun PaintViewModel.seekLocked(
    s: ReplaySession,
    fraction: Float,
) {
    val target = (fraction * s.totalMs).toLong()
    resetReplayDocLocked(s)
    val r = s.reader
    r.pos = 0
    s.currentMs = 0
    while (r.remaining() > 0 && s.currentMs < target) {
        val type = r.u8()
        val dt = r.varint()
        s.currentMs += dt
        dispatchReplayLocked(type, r)
    }
    s.elapsedMs = target
    s.progress = fraction
    s.lastProgressWallMs = android.os.SystemClock.elapsedRealtime()
    scheduleRender(immediate = true)
}

// ---- Event dispatch (render thread; direct bridge calls, no re-recording) ----

private fun PaintViewModel.dispatchReplayLocked(
    type: Int,
    r: RecordingReader,
) {
    when (type) {
        STROKE_START -> {
            val x = r.f32()
            val y = r.f32()
            val p = r.f32()
            ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), p.toDouble())
        }

        STROKE_MOVE -> {
            val x = r.f32()
            val y = r.f32()
            val p = r.f32()
            ReverieCoreBridge.touchStrokeMove(x.toDouble(), y.toDouble(), p.toDouble())
        }

        STROKE_END -> {
            ReverieCoreBridge.touchStrokeEnd()
            scheduleRender(immediate = true)
        }

        STROKE_CANCEL -> {
            ReverieCoreBridge.touchStrokeCancel()
        }

        CONTEXT -> {
            applyReplayContextLocked(
                com.reverie.paint.model.ReplayContext(
                    toolMode = r.u8(),
                    preset = r.u16(),
                    size = r.f32(),
                    opacity = r.f32(),
                    flow = r.f32(),
                    compositeOp = r.str(),
                    color = r.str(),
                    layer = r.u16(),
                ),
            )
        }

        LAYER_OP -> {
            dispatchLayerOpLocked(r.u8(), r.u16(), r.str())
        }

        TOOL_OP -> {
            dispatchToolOpLocked(r.u8(), r)
        }

        FILTER -> {
            val index = r.u16()
            val filterType = r.u8()
            val p1 = r.f64()
            val p2 = r.f64()
            val p3 = r.f64()
            val p4 = r.f64()
            val name = r.str()
            replayFilterLocked(index, filterType, p1, p2, p3, p4, name)
        }
    }
}

private fun PaintViewModel.applyReplayContextLocked(c: com.reverie.paint.model.ReplayContext) {
    ReverieCoreBridge.setToolMode(c.toolMode)
    if (c.preset >= 0) {
        ReverieCoreBridge.loadBrushPreset(c.preset)
        ReverieCoreBridge.setBrushSize(c.size.toDouble())
        ReverieCoreBridge.setBrushOpacity(c.opacity.toDouble())
        ReverieCoreBridge.setBrushFlow(c.flow.toDouble())
    }
    ReverieCoreBridge.setBrushCompositeOp(c.compositeOp)
    ReverieCoreBridge.setBrushColor(c.color)
    ReverieCoreBridge.setCurrentLayer(c.layer)
}

private fun PaintViewModel.dispatchLayerOpLocked(
    op: Int,
    i: Int,
    arg: String,
) {
    when (op) {
        L_ADD -> {
            ReverieCoreBridge.addLayer("")
        }

        L_REMOVE -> {
            ReverieCoreBridge.removeLayer(i)
        }

        L_SET_CURRENT -> {
            ReverieCoreBridge.setCurrentLayer(i)
        }

        L_BLEND -> {
            ReverieCoreBridge.setLayerBlendMode(i, arg)
        }

        L_VISIBLE -> {
            ReverieCoreBridge.setLayerVisible(i, !ReverieCoreBridge.layerVisible(i))
        }

        L_OPACITY -> {
            ReverieCoreBridge.setLayerOpacity(i, arg.toDoubleOrNull() ?: 1.0)
        }

        L_LOCKED -> {
            ReverieCoreBridge.setLayerLocked(i, arg == "1")
        }

        L_ALPHA_LOCKED -> {
            ReverieCoreBridge.setLayerAlphaLocked(i, arg == "1")
        }

        L_CLIPPED -> {
            ReverieCoreBridge.setLayerClipped(i, arg == "1")
        }

        L_RENAME -> {
            ReverieCoreBridge.setLayerName(i, arg)
        }

        L_CLEAR -> {
            ReverieCoreBridge.clearLayer(i)
        }

        L_COPY -> {
            ReverieCoreBridge.copyLayer(i)
        }

        L_MOVE -> {
            ReverieCoreBridge.moveLayer(i, arg.toIntOrNull() ?: i)
        }

        L_MOVE_ABOVE -> {
            ReverieCoreBridge.moveLayerAbove(i, arg.toIntOrNull() ?: i)
        }

        L_MOVE_TO_GROUP -> {
            ReverieCoreBridge.moveLayerToGroup(i, arg.toIntOrNull() ?: i)
        }

        L_MOVE_RELATIVE -> {
            val parts = arg.split(":")
            val target = parts.getOrNull(0)?.toIntOrNull() ?: i
            val above = parts.getOrNull(1) == "1"
            ReverieCoreBridge.moveLayerRelative(i, target, above)
        }

        L_MOVE_UP -> {
            ReverieCoreBridge.moveLayerUp(i)
        }

        L_MOVE_DOWN -> {
            ReverieCoreBridge.moveLayerDown(i)
        }

        L_MOVE_OUT -> {
            ReverieCoreBridge.moveLayerOut(i)
        }

        L_MERGE_DOWN -> {
            ReverieCoreBridge.mergeDown(i)
        }

        L_FLIP_H -> {
            ReverieCoreBridge.flipLayerHorizontal(i)
        }

        L_FLIP_V -> {
            ReverieCoreBridge.flipLayerVertical(i)
        }

        L_STAMP -> {
            ReverieCoreBridge.stampVisibleLayers()
        }

        L_ADD_GROUP -> {
            ReverieCoreBridge.addGroupLayer("")
        }

        L_SET_BG -> {
            ReverieCoreBridge.setBackgroundColor(arg.toIntOrNull() ?: 0, true)
        }

        L_COLOR_LABEL -> {
            ReverieCoreBridge.setLayerColorLabel(i, arg.toIntOrNull() ?: 0)
        }

        L_PASS_THROUGH -> {
            ReverieCoreBridge.setGroupPassThrough(i, arg == "1")
        }

        L_RASTERIZE -> {
            ReverieCoreBridge.rasterizeLayer(i)
        }

        L_FLATTEN_GROUP -> {
            ReverieCoreBridge.flattenGroup(i)
        }

        L_ADD_MASK -> {
            ReverieCoreBridge.addMaskToLayer(i, arg.toIntOrNull() ?: 0)
        }

        L_REMOVE_MASK -> {
            ReverieCoreBridge.removeMask(i)
        }

        L_ADD_LAYER_TYPE -> {
            val p = arg.split("|")
            ReverieCoreBridge.addLayerWithType(
                p.getOrNull(0) ?: "",
                p.getOrNull(1)?.toIntOrNull() ?: 0,
                p.getOrNull(2)?.toLongOrNull()?.toInt() ?: 0xFFFFFFFF.toInt(),
            )
        }

        L_SOLO -> {
            ReverieCoreBridge.soloLayer(i)
        }

        L_APPLY_FILTER -> {
            ReverieCoreBridge.applyFilter(i, arg.toIntOrNull() ?: 0)
        }
    }
}

private fun PaintViewModel.dispatchToolOpLocked(
    op: Int,
    r: RecordingReader,
) {
    when (op) {
        T_SHAPE -> {
            val kind = r.u8()
            val x1 = r.f32()
            val y1 = r.f32()
            val x2 = r.f32()
            val y2 = r.f32()
            val filled = r.u8() == 1
            ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), filled)
        }

        T_POLYGON -> {
            val closed = r.u8() == 1
            val pts = readPointsLocked(r)
            val xs = IntArray(pts.size) { pts[it].first }
            val ys = IntArray(pts.size) { pts[it].second }
            ReverieCoreBridge.drawPolygon(xs, ys, pts.size, closed)
        }

        T_FILL -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            ReverieCoreBridge.floodFillAt(x, y, tol)
        }

        T_GRADIENT -> {
            val x1 = r.f32().toInt()
            val y1 = r.f32().toInt()
            val x2 = r.f32().toInt()
            val y2 = r.f32().toInt()
            val t = r.u8()
            ReverieCoreBridge.gradientFill(x1, y1, x2, y2, t)
        }

        T_TEXT -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val fs = r.f32().toDouble()
            val txt = r.str()
            ReverieCoreBridge.drawText(x, y, txt, fs)
        }

        T_LIQUIFY -> {
            val fx = r.f32().toInt()
            val fy = r.f32().toInt()
            val tx = r.f32().toInt()
            val ty = r.f32().toInt()
            val mode = r.u8()
            val strength = r.f32().toDouble()
            ReverieCoreBridge.liquify(fx, fy, tx, ty, strength, mode)
        }

        T_MOVE_CONTENT -> {
            val dx = r.f32().toInt()
            val dy = r.f32().toInt()
            ReverieCoreBridge.cancelTransformPreview()
            ReverieCoreBridge.moveLayerContent(dx, dy)
        }

        T_TRANSFORM -> {
            val a = DoubleArray(9) { r.f64() }
            ReverieCoreBridge.applyTransform(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8])
        }

        T_PERSPECTIVE -> {
            val a = DoubleArray(12) { r.f64() }
            ReverieCoreBridge.applyPerspectiveTransform(
                a[0],
                a[1],
                a[2],
                a[3],
                a[4],
                a[5],
                a[6],
                a[7],
                a[8],
                a[9],
                a[10],
                a[11],
            )
        }

        T_WARP -> {
            val n = r.u16()
            val orig = ArrayList<Offset>(n)
            for (k in 0 until n) {
                orig.add(Offset(r.f32(), r.f32()))
            }
            val trans = ArrayList<Offset>(n)
            for (k in 0 until n) {
                trans.add(Offset(r.f32(), r.f32()))
            }
            val ox = r.f64()
            val oy = r.f64()
            val ow = r.f64()
            val oh = r.f64()
            ReverieCoreBridge.applyWarpMeshTransform(
                DoubleArray(n) { orig[it].x.toDouble() },
                DoubleArray(n) { orig[it].y.toDouble() },
                DoubleArray(n) { trans[it].x.toDouble() },
                DoubleArray(n) { trans[it].y.toDouble() },
                n,
                ox,
                oy,
                ow,
                oh,
            )
        }

        T_CROP -> {
            val x = r.u16()
            val y = r.u16()
            val w = r.u16()
            val h = r.u16()
            ReverieCoreBridge.cropCanvas(x, y, w, h)
            coreW = ReverieCoreBridge.docWidth()
            coreH = ReverieCoreBridge.docHeight()
            renderW = coreW
            renderH = coreH
            displayBufferInvalid = true
        }

        T_SELECT_SHAPE -> {
            val kind = r.u8()
            val x1 = r.f32().toInt()
            val y1 = r.f32().toInt()
            val x2 = r.f32().toInt()
            val y2 = r.f32().toInt()
            ReverieCoreBridge.selectShape(kind, x1, y1, x2, y2)
        }

        T_SELECT_POLYGON -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.selectPolygon(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_LASSO -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.lassoSelect(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_CONTIGUOUS -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            selectionTolerance = tol
            ReverieCoreBridge.selectContiguousAt(x, y, tol)
        }

        T_SIMILAR -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            selectionTolerance = tol
            ReverieCoreBridge.selectSimilarAt(x, y, tol)
        }

        T_CLEAR_SELECTION -> {
            ReverieCoreBridge.clearSelection()
        }

        T_SELECT_ALL -> {
            val layer = r.u16()
            ReverieCoreBridge.selectionFromLayer(layer)
        }

        T_INVERT_SELECTION -> {
            ReverieCoreBridge.invertSelection()
        }

        T_FEATHER -> {
            ReverieCoreBridge.featherSelection(r.u16())
        }

        T_EXPAND -> {
            ReverieCoreBridge.expandSelection(r.u16())
        }

        T_CONTRACT -> {
            ReverieCoreBridge.contractSelection(r.u16())
        }

        T_SMOOTH -> {
            ReverieCoreBridge.smoothSelection(r.u16())
        }

        T_SELECT_MODE -> {
            ReverieCoreBridge.setSelectionMode(r.u8())
        }

        T_LASSO_FILL -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.lassoFill(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_LASSO_CLEAR -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.lassoClear(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_LIQUIFY_SIZE -> {
            ReverieCoreBridge.setLiquifyBrushSize(r.f32().toDouble())
        }

        T_SHAPE_STROKE_WIDTH -> {
            ReverieCoreBridge.setShapeStrokeWidth(r.f32().toDouble())
        }
    }
}

private fun PaintViewModel.replayFilterLocked(
    index: Int,
    filterType: Int,
    p1: Double,
    p2: Double,
    p3: Double,
    p4: Double,
    name: String,
) {
    if (filterType == 0xFF) return // LUT-based filter, not rebuildable from scalars
    ReverieCoreBridge.beginFilterPreview(index)
    ReverieCoreBridge.applyFilterPreview(index, filterType, p1, p2, p3, p4)
    ReverieCoreBridge.commitFilter(index, name)
}

private fun readPointsLocked(r: RecordingReader): List<Pair<Int, Int>> {
    val n = r.u16()
    val out = ArrayList<Pair<Int, Int>>(n)
    for (i in 0 until n) {
        val x = r.f32().roundToInt()
        val y = r.f32().roundToInt()
        out.add(x to y)
    }
    return out
}
