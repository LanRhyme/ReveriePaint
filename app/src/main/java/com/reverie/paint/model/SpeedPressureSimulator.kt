/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.exp

/**
 * Pure Kotlin, allocation-free speed-to-pressure simulator for finger and capacitive stylus input.
 * Uses a sliding time window for velocity estimation and asymmetric time+distance gated filtering
 * to prevent start blobs, corner/pause swelling, and high-frequency jitter.
 */
class SpeedPressureSimulator {

    private val ringDistDp = DoubleArray(WINDOW_CAPACITY)
    private val ringDtMs = LongArray(WINDOW_CAPACITY)
    private var ringHead = 0
    private var ringCount = 0
    private var lastTimeMs = 0L
    private var traveledDp = 0.0
    private var currentSimP = START_SIM_P

    /** Resets per-stroke state and returns the initial modulated pressure. */
    fun beginStroke(influence: Double, eventTimeMs: Long = 0L): Double {
        ringHead = 0
        ringCount = 0
        lastTimeMs = if (eventTimeMs > 0L) eventTimeMs else 0L
        traveledDp = 0.0
        currentSimP = START_SIM_P
        return applyInfluence(currentSimP, influence)
    }

    /** Advances simulation with a single move delta in dp and returns modulated pressure in [0.02, 1.0]. */
    fun onMove(distDp: Double, eventTimeMs: Long, influence: Double): Double {
        val safeDist = if (distDp.isFinite() && distDp > 0.0) distDp else 0.0
        val dtMs = when {
            eventTimeMs <= 0L -> {
                val fallback = 8L
                lastTimeMs = if (lastTimeMs > 0L) lastTimeMs + fallback else fallback
                fallback
            }
            lastTimeMs <= 0L -> {
                lastTimeMs = eventTimeMs
                8L
            }
            eventTimeMs > lastTimeMs -> {
                val delta = (eventTimeMs - lastTimeMs).coerceIn(1L, 120L)
                lastTimeMs = eventTimeMs
                delta
            }
            else -> 1L
        }

        ringDistDp[ringHead] = safeDist
        ringDtMs[ringHead] = dtMs
        ringHead = (ringHead + 1) % WINDOW_CAPACITY
        if (ringCount < WINDOW_CAPACITY) {
            ringCount++
        }

        var sumDist = 0.0
        var sumTime = 0L
        var idx = ringHead
        for (i in 0 until ringCount) {
            idx = if (idx == 0) WINDOW_CAPACITY - 1 else idx - 1
            sumDist += ringDistDp[idx]
            sumTime += ringDtMs[idx]
            if (sumTime >= SPEED_WINDOW_MS) break
        }

        val effectiveTimeSec = maxOf(sumTime, MIN_WINDOW_TIME_MS).toDouble() / 1000.0
        val speedDpPerSec = sumDist / effectiveTimeSec

        val u = ((speedDpPerSec - SPEED_LOW_DP_S) / (SPEED_HIGH_DP_S - SPEED_LOW_DP_S)).coerceIn(0.0, 1.0)
        val s = u * u * (3.0 - 2.0 * u)
        val speedTargetSimP = 1.0 - s * (1.0 - MIN_SIM_P)

        traveledDp += safeDist
        val targetSimP = if (traveledDp < LEAD_IN_DP) {
            val tLead = (traveledDp / LEAD_IN_DP).coerceIn(0.0, 1.0)
            val sLead = tLead * tLead * (3.0 - 2.0 * tLead)
            START_SIM_P + (speedTargetSimP - START_SIM_P) * sLead
        } else {
            speedTargetSimP
        }

        val dtSec = dtMs.toDouble() / 1000.0
        val alpha = if (targetSimP < currentSimP) {
            val alphaTime = 1.0 - exp(-dtSec / FALL_TIME_SEC)
            val alphaDist = 1.0 - exp(-safeDist / FALL_DISTANCE_DP)
            minOf(alphaTime, alphaDist)
        } else {
            val alphaTime = 1.0 - exp(-dtSec / RISE_TIME_SEC)
            val alphaDist = 1.0 - exp(-safeDist / RISE_DISTANCE_DP)
            minOf(alphaTime, alphaDist)
        }

        val updated = currentSimP + (targetSimP - currentSimP) * alpha
        currentSimP = if (updated.isFinite()) updated.coerceIn(MIN_SIM_P, 1.0) else currentSimP
        return applyInfluence(currentSimP, influence)
    }

    private fun applyInfluence(simP: Double, influence: Double): Double {
        val k = if (influence.isFinite()) influence.coerceIn(0.05, 1.0) else 0.7
        val modulated = 1.0 - (1.0 - simP) * k
        return if (modulated.isFinite()) modulated.coerceIn(0.02, 1.0) else 1.0
    }

    companion object {
        const val WINDOW_CAPACITY = 16
        const val SPEED_WINDOW_MS = 45L
        const val MIN_WINDOW_TIME_MS = 16L
        const val START_SIM_P = 0.28
        const val MIN_SIM_P = 0.12
        const val SPEED_LOW_DP_S = 150.0
        const val SPEED_HIGH_DP_S = 1450.0
        const val LEAD_IN_DP = 8.0
        const val FALL_TIME_SEC = 0.040
        const val FALL_DISTANCE_DP = 6.0
        const val RISE_TIME_SEC = 0.120
        const val RISE_DISTANCE_DP = 22.0
    }
}
