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
    const val MAX_TIMESTAMP_MS = 86_399_999L
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

    fun parse(text: String): List<SrtCue> = parseInternal(text, allowEmptyCaptions = false)

    /** Project-owned storyboard assets may contain a blank cue while the user is splitting text. */
    fun parseStored(text: String): List<SrtCue> = parseInternal(text, allowEmptyCaptions = true)

    private fun parseInternal(text: String, allowEmptyCaptions: Boolean): List<SrtCue> {
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
            if (end <= start || (caption.isBlank() && !allowEmptyCaptions)) {
                fail(SrtImportException.Reason.INVALID)
            }
            SrtCue(start, end, caption)
        }.sortedBy { it.startMs }
        if (cues.zipWithNext().any { (a, b) -> a.endMs > b.startMs }) {
            fail(SrtImportException.Reason.OVERLAP)
        }
        return cues
    }

    /** SRT is recognized by its timing delimiter; otherwise each non-empty text line is one shot. */
    fun parseImport(text: String): List<SrtCue> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return if (normalized.contains("-->")) parse(normalized) else parsePlainText(normalized)
    }

    /** Plain text import: each non-empty line becomes one one-frame caption. */
    fun parsePlainText(text: String): List<SrtCue> {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(SrtImportException.Reason.TOO_LARGE)
        val lines = text.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (lines.isEmpty()) fail(SrtImportException.Reason.INVALID)
        if (lines.size > MAX_CUES) fail(SrtImportException.Reason.TOO_LARGE)
        return lines.mapIndexed { index, line ->
            val start = index * 1000L
            SrtCue(start, start + 1000L, line)
        }
    }

    /** Allocate a valid reference timestamp for a newly appended single-frame storyboard cue. */
    fun appendedCue(cues: List<SrtCue>, text: String): SrtCue? {
        val caption = text.trim()
        if (caption.isEmpty() || cues.size >= MAX_CUES) return null
        val start = cues.lastOrNull()?.endMs ?: 0L
        if (start >= MAX_TIMESTAMP_MS) return null
        return SrtCue(start, (start + 1000L).coerceAtMost(MAX_TIMESTAMP_MS), caption)
    }

    fun replaceCue(cues: List<SrtCue>, index: Int, text: String): List<SrtCue>? {
        val caption = text.trim()
        if (index !in cues.indices || caption.isEmpty()) return null
        return cues.toMutableList().also { it[index] = it[index].copy(text = caption) }
    }

    /**
     * 在 [index] 处按编辑光标拆帧: 光标左侧留在当前帧, 右侧进入下一帧。
     * 边界只去除空白, 绝不丢字符; 两侧可以暂时为空, 等用户继续输入。
     */
    fun splitCue(cues: List<SrtCue>, index: Int, leftText: String, rightText: String): List<SrtCue>? {
        if (index !in cues.indices) return null
        val left = leftText.trimEnd()
        val right = rightText.trimStart()
        if (left.isBlank() && right.isBlank()) return null
        val shortened = cues.toMutableList().also { it[index] = it[index].copy(text = left) }
        return insertCue(shortened, index + 1, right)
    }

    /**
     * Insert a cue at [index]. Existing source times after the insertion are shifted by one second;
     * single-frame storyboard placement is independent of these reference-only timestamps.
     */
    fun insertCue(cues: List<SrtCue>, index: Int, text: String): List<SrtCue>? {
        if (index !in 0..cues.size || cues.size >= MAX_CUES) return null
        val start = cues.getOrNull(index - 1)?.endMs ?: 0L
        if (start >= MAX_TIMESTAMP_MS) return null
        if (cues.drop(index).any { it.endMs > MAX_TIMESTAMP_MS - 1000L }) return null

        val inserted = SrtCue(start, (start + 1000L).coerceAtMost(MAX_TIMESTAMP_MS), text)
        val shifted = cues.mapIndexed { cueIndex, cue ->
            if (cueIndex < index) cue else cue.copy(startMs = cue.startMs + 1000L, endMs = cue.endMs + 1000L)
        }
        return shifted.toMutableList().apply { add(index, inserted) }
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

    /** Binary search for the active frame index at [currentTime]; returns -1 if in a gap or out of range. */
    fun findActiveFrameIndex(frames: List<StoryboardFrame>, currentTime: Int): Int {
        var low = 0
        var high = frames.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val frame = frames[mid]
            when {
                currentTime < frame.start -> high = mid - 1
                currentTime >= frame.endExclusive -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }
}
