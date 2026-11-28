/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun PaintViewModel.toggleCountdownWindow() {
    countdownWindowOpen = !countdownWindowOpen
    persistCountdownWindowState()
}

internal fun PaintViewModel.persistCountdownWindowState() {
    if (!isAppContextReady()) return
    runCatching {
        prefs.edit()
            .putBoolean("countdown_window_open", countdownWindowOpen)
            .putFloat("countdown_window_x", countdownWindowX)
            .putFloat("countdown_window_y", countdownWindowY)
            .putFloat(
                if (isLandscapeMode) "countdown_window_x_land" else "countdown_window_x_port",
                countdownWindowX,
            )
            .putFloat(
                if (isLandscapeMode) "countdown_window_y_land" else "countdown_window_y_port",
                countdownWindowY,
            )
            .apply()
    }
}

internal fun PaintViewModel.startCountdown(minutes: Long) {
    if (minutes !in 1L..999L) return
    countdownJob?.cancel()
    countdownRemainingMs = minutes * 60_000L
    countdownDeadlineElapsedMs = SystemClock.elapsedRealtime() + countdownRemainingMs
    countdownRunning = true
    launchCountdownTicker()
}

internal fun PaintViewModel.pauseCountdown() {
    if (!countdownRunning) return
    countdownRemainingMs = (countdownDeadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    countdownRunning = false
    countdownJob?.cancel()
    countdownJob = null
}

internal fun PaintViewModel.resumeCountdown() {
    if (countdownRunning || countdownRemainingMs <= 0L) return
    countdownDeadlineElapsedMs = SystemClock.elapsedRealtime() + countdownRemainingMs
    countdownRunning = true
    launchCountdownTicker()
}

internal fun PaintViewModel.resetCountdown() {
    countdownJob?.cancel()
    countdownJob = null
    countdownRunning = false
    countdownRemainingMs = 0L
    countdownDeadlineElapsedMs = 0L
}

private fun PaintViewModel.launchCountdownTicker() {
    countdownJob?.cancel()
    countdownJob = viewModelScope.launch {
        while (isActive) {
            val remaining = (countdownDeadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            countdownRemainingMs = remaining
            if (remaining == 0L) {
                countdownRunning = false
                countdownJob = null
                showActionToast(R.string.countdown_finished)
                break
            }
            delay(250L)
        }
    }
}
