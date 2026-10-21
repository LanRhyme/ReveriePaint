package com.reverie.paint.model

internal object FrontBufferQuiet {
    fun untilMs(alreadyPainting: Boolean, nowUptimeMs: Long, quietMs: Long, existingUntilMs: Long): Long {
        if (alreadyPainting) return existingUntilMs
        return maxOf(existingUntilMs, nowUptimeMs + quietMs)
    }
}
