/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** Caller holds the preview lock. Counts refer to one gesture and source generation. */
class LiquifyCommitFence {
    var submitted = 0L
        private set
    var applied = 0L
        private set
    fun reset() { submitted = 0; applied = 0 }
    fun enqueue() { submitted++ }
    fun acknowledge(through: Long) { applied = maxOf(applied, through.coerceAtMost(submitted)) }
    fun canCommit(through: Long): Boolean = through <= applied
}
