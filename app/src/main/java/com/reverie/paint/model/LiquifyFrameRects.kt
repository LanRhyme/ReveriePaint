// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/** Caller serializes access. Matches consumed SurfaceTexture timestamps, not latest submissions. */
class LiquifyFrameRects {
    private val times = LongArray(8)
    private val gestures = LongArray(8)
    private val rectangles = IntArray(32)
    private var next = 0
    fun clear() {
        times.fill(0L)
        gestures.fill(0L)
        next = 0
    }
    fun stage(time: Long, gesture: Long, x: Int, y: Int, w: Int, h: Int) {
        val slot = next
        next = (next + 1) % times.size
        times[slot] = time
        gestures[slot] = gesture
        val b = slot * 4
        rectangles[b] = x; rectangles[b + 1] = y
        rectangles[b + 2] = w; rectangles[b + 3] = h
    }
    fun copyPresented(time: Long, gesture: Long, out: IntArray): Boolean {
        if (time <= 0 || out.size < 4) return false
        for (slot in times.indices) {
            if (times[slot] != time || gestures[slot] != gesture) continue
            val b = slot * 4
            if (rectangles[b + 2] <= 0 || rectangles[b + 3] <= 0) return false
            rectangles.copyInto(out, 0, b, b + 4)
            return true
        }
        return false
    }
}
