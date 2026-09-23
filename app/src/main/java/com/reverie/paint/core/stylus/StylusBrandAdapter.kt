/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Common contract for vendor-specific stylus drivers and hardware adaptations.
 * Implement this interface to add support for any stylus brand (e.g. OPPO, Samsung, Huawei, Xiaomi).
 */
interface StylusBrandAdapter {
    val brand: StylusBrand

    /**
     * Inspects system environment (Bluetooth devices, Build properties, system settings,
     * input device manager) to detect whether this brand's stylus is supported and connected.
     */
    fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected?

    /**
     * Lifecycle: initialize broadcast receivers, content observers, or AIDL clients.
     */
    fun register(context: Context, vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {}

    /**
     * Lifecycle: unregister receivers and listeners.
     */
    fun unregister(context: Context) {}

    /**
     * Lifecycle: dispatched when host activity window focus changes.
     * Crucial for brands (like HUAWEI M-Pencil) that enforce window focus binding for gesture broadcasts.
     */
    fun onWindowFocusChanged(activity: android.app.Activity, hasFocus: Boolean) {}

    /**
     * Lifecycle: dispatched on host activity onResume.
     */
    fun onActivityResume(activity: android.app.Activity) {}

    /**
     * Lifecycle: dispatched on host activity onPause.
     */
    fun onActivityPause(activity: android.app.Activity) {}

    /**
     * Synchronize settings mirror when settings are modified.
     */
    fun syncSettings(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {}

    /**
     * Intercept or process stylus physical key events (e.g. barrel button, Bluetooth remote).
     * @return true if consumed.
     */
    fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean = false

    /**
     * Intercept or process stylus motion events (e.g. side button state, barrel touch, hover).
     * @return true if consumed.
     */
    fun onStylusMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean = false

    /**
     * Notifies the adapter that the stylus left the hover field (ACTION_HOVER_EXIT).
     * Adapters tracking side-button state across hover events must reset it here,
     * otherwise a button held down during exit stays latched until the next hover move.
     */
    fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {}

    /**
     * Intercept or process generic motion events (e.g. ACTION_SCROLL barrel slide).
     * @return true if consumed.
     */
    fun onGenericMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean = false

    /**
     * Release any bound service connections or hardware resources.
     */
    fun release() {}
}
