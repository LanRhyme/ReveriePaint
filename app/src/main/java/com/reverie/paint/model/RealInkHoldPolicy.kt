package com.reverie.paint.model

internal object RealInkHoldPolicy {
    fun shouldReleaseOnReject(held: Boolean, wantsHold: Boolean): Boolean = held && !wantsHold

    fun shouldBumpEpoch(shown: Boolean): Boolean = shown
}
