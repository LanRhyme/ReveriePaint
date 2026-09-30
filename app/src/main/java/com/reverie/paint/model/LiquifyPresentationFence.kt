// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/** Retirement requires the committed bitmap to have reached the drawing consumer. */
class LiquifyPresentationFence<T : Any> {
    @Volatile private var gesture = -1L
    private var frame: T? = null
    @Synchronized fun arm(id: Long) { gesture = id; frame = null }
    fun isPending(id: Long): Boolean = gesture == id
    @Synchronized fun cancel() { gesture = -1; frame = null }
    @Synchronized fun publish(id: Long, value: T) { if (gesture == id) frame = value }
    @Synchronized fun consume(id: Long, value: T): Boolean {
        if (gesture != id) return false
        if (frame !== value) return false
        gesture = -1
        frame = null
        return true
    }
}
