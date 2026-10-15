/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Original timestamps are kept independently of the document's integer frame grid. */
data class SrtCue(val startMs: Long, val endMs: Long, val text: String)
data class StoryboardFrame(val cue: SrtCue, val start: Int, val endExclusive: Int)

class SrtImportException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { INVALID, TOO_LARGE, OVERLAP, FRAME_COLLISION }
}

object SrtStoryboard {
    const val ASSET = "reverie.anim.storyboard.srt"
    const val MAX_BYTES = 1_048_576
    const val MAX_CUES = 1000
    private val timing = Regex(
        """^(\d{2,}):([0-5]\d):([0-5]\d)[,.](\d{3})\s+-->\s+(\d{2,}):([0-5]\d):([0-5]\d)[,.](\d{3})\s*$""",
    )

    private fun fail(reason: SrtImportException.Reason): Nothing = throw SrtImportException(reason)

    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) fail(SrtImportException.Reason.TOO_LARGE)
        val utf16 = bytes.size >= 2 &&
            ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))
        return try {
            val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail(SrtImportException.Reason.INVALID)
        }
    }

    fun parse(text: String): List<SrtCue> {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(SrtImportException.Reason.TOO_LARGE)
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) fail(SrtImportException.Reason.INVALID)
        val blocks = normalized.split(Regex("\n(?:[ \\t]*\n)+"))
        if (blocks.size > MAX_CUES) fail(SrtImportException.Reason.TOO_LARGE)
        val cues = blocks.map { block ->
            val lines = block.trim().lines()
            val timeIndex = if (lines.first().trim().all { it.isDigit() }) 1 else 0
            val match = lines.getOrNull(timeIndex)?.trim()?.let(timing::matchEntire)
                ?: fail(SrtImportException.Reason.INVALID)
            fun millis(offset: Int): Long {
                val h = match.groupValues[offset].toLongOrNull()
                    ?: fail(SrtImportException.Reason.INVALID)
                if (h > 23) fail(SrtImportException.Reason.INVALID)
                return ((h * 60 + match.groupValues[offset + 1].toLong()) * 60 +
                    match.groupValues[offset + 2].toLong()) * 1000 + match.groupValues[offset + 3].toLong()
            }
            val start = millis(1)
            val end = millis(5)
            val caption = lines.drop(timeIndex + 1).joinToString("\n").trim()
            if (end <= start || caption.isBlank()) fail(SrtImportException.Reason.INVALID)
            SrtCue(start, end, caption)
        }.sortedBy { it.startMs }
        if (cues.zipWithNext().any { (a, b) -> a.endMs > b.startMs }) {
            fail(SrtImportException.Reason.OVERLAP)
        }
        return cues
    }

    fun frames(cues: List<SrtCue>, fps: Int): List<StoryboardFrame> {
        require(fps in 1..240)
        val result = cues.map { cue ->
            val start = (cue.startMs * fps / 1000).toInt()
            val end = (cue.endMs * fps / 1000).toInt().coerceAtLeast(start + 1)
            StoryboardFrame(cue, start, end)
        }
        if (result.zipWithNext().any { (a, b) -> a.endExclusive > b.start }) {
            fail(SrtImportException.Reason.FRAME_COLLISION)
        }
        return result
    }

    /** One imported shot per keyframe; source timestamps remain reference-only. */
    fun oneFrameShots(cues: List<SrtCue>): List<StoryboardFrame> =
        cues.mapIndexed { index, cue -> StoryboardFrame(cue, index, index + 1) }

    /** Follow the current exposure lengths after a ripple resize or undo, without changing the script order. */
    fun resizedShots(cues: List<SrtCue>, keyTimes: List<Int>, lastHold: Int): List<StoryboardFrame> {
        val times = keyTimes.sorted()
        if (times.size != cues.size || times.any { it < 0 } || times.distinct().size != times.size) return emptyList()
        return cues.mapIndexed { index, cue ->
            val end = times.getOrNull(index + 1) ?: (times[index] + lastHold.coerceAtLeast(1))
            StoryboardFrame(cue, times[index], end)
        }
    }

    fun timestamp(ms: Long): String = java.lang.String.format(
        java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000,
    )

    fun encode(cues: List<SrtCue>): ByteArray = cues.mapIndexed { index, cue ->
        "${index + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text}"
    }.joinToString("\n\n").toByteArray(Charsets.UTF_8)
}
