/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.util.Locale

enum class MemoryPressureLevel {
    NORMAL,
    ELEVATED,
    CRITICAL,
}

data class RuntimeMemorySnapshot(
    val appRssBytes: Long = 0L,
    val sysAvailBytes: Long = 0L,
    val sysTotalBytes: Long = 0L,
    val sysLowMemory: Boolean = false,
    val kritaTotalBytes: Long = 0L,
    val kritaRealBytes: Long = 0L,
    val kritaHistoryBytes: Long = 0L,
    val kritaPoolBytes: Long = 0L,
    val kritaSwapBytes: Long = 0L,
    val kritaTilesInMemory: Long = 0L,
    val canClearHistory: Boolean = false,
) {
    val pressureLevel: MemoryPressureLevel
        get() = RuntimeMemoryPolicy.classifyPressure(appRssBytes, sysAvailBytes, sysTotalBytes, sysLowMemory)
}

/** Pure runtime memory budget, pressure classification, and display formatting rules. */
object RuntimeMemoryPolicy {
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    fun classifyPressure(
        appRssBytes: Long,
        sysAvailBytes: Long,
        sysTotalBytes: Long,
        sysLowMemory: Boolean,
    ): MemoryPressureLevel {
        if (sysLowMemory ||
            (sysAvailBytes in 1L until 512L * MB) ||
            (sysTotalBytes > 0L && appRssBytes * 100L >= sysTotalBytes * 30L)
        ) {
            return MemoryPressureLevel.CRITICAL
        }
        if ((sysAvailBytes in 1L until 1024L * MB) ||
            (sysTotalBytes > 0L && appRssBytes * 100L >= sysTotalBytes * 20L)
        ) {
            return MemoryPressureLevel.ELEVATED
        }
        return MemoryPressureLevel.NORMAL
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        if (bytes < MB) return "< 1 MB"
        if (bytes >= GB) {
            return String.format(Locale.US, "%.1f GB", bytes.toDouble() / GB.toDouble())
        }
        return "${bytes / MB} MB"
    }

    fun parseStatmRssBytes(statmContent: String, pageSizeBytes: Long): Long {
        if (pageSizeBytes <= 0L) return 0L
        val parts = statmContent.trim().split(Regex("""\s+"""))
        if (parts.size < 2) return 0L
        val residentPages = parts[1].toLongOrNull() ?: return 0L
        if (residentPages < 0L) return 0L
        return residentPages * pageSizeBytes
    }

    /**
     * Mirrors C++ `reverieTuneTileMemory` tiered budget calculation so unit tests
     * verify small-RAM OOM guardrails and large-RAM swap-jitter ceilings together.
     *
     * @return Triple(softMb, hardMb, poolMb)
     */
    fun computeTileBudgetMb(totalMb: Long, availMb: Long): Triple<Double, Double, Double> {
        val hardCapMb = when {
            totalMb <= 0L -> 1024.0
            totalMb <= 4096L -> 512.0
            totalMb <= 6144L -> 768.0
            totalMb <= 8192L -> 1152.0
            totalMb <= 12288L -> 1600.0
            else -> 2048.0
        }
        val effectiveAvail = if (availMb > 0L) availMb.toDouble() else (totalMb.toDouble() / 3.0).coerceAtLeast(1024.0)
        val hardMb = (effectiveAvail * 0.45).coerceIn(256.0, hardCapMb)
        val softMb = (hardMb * 0.72).coerceIn(192.0, (hardMb - 64.0).coerceAtLeast(192.0))
        val poolMb = (hardMb * 0.06).coerceIn(32.0, 96.0)
        return Triple(softMb, hardMb, poolMb)
    }
}
