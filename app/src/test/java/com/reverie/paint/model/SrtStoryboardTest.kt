/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class SrtStoryboardTest {
    private fun cue(start: String = "00:00:01,000", end: String = "00:00:02,000", text: String = "字幕") =
        "1\n$start --> $end\n$text"

    private fun rejects(reason: SrtImportException.Reason, action: () -> Unit) {
        try {
            action()
            fail("Expected $reason")
        } catch (e: SrtImportException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun `BOM CRLF and multiline Chinese captions keep exact milliseconds`() {
        val text = "\uFEFF" + cue("00:00:01,125", "00:00:02,875", "第一行\n第二行").replace("\n", "\r\n")
        val parsed = SrtStoryboard.parse(text).single()
        assertEquals(SrtCue(1125, 2875, "第一行\n第二行"), parsed)
    }

    @Test fun `index is optional dot decimal and unsorted cues are accepted`() {
        val text = cue("00:00:03.000", "00:00:04.000") + "\n\n" +
            "00:00:01,000 --> 00:00:02,000\nfirst"
        assertEquals(listOf(1000L, 3000L), SrtStoryboard.parse(text).map { it.startMs })
    }

    @Test fun `multiple whitespace separators are accepted`() {
        val text = cue() + "\n \n\t\n" + cue("00:00:03,000", "00:00:04,000")
        assertEquals(2, SrtStoryboard.parse(text).size)
    }

    @Test fun `timestamps round down on original timeline and retain gaps`() {
        val cues = SrtStoryboard.parse(cue("00:00:01,125", "00:00:02,875") + "\n\n" +
            cue("00:00:04,000", "00:00:05,000"))
        val frames = SrtStoryboard.frames(cues, 12)
        assertEquals(listOf(13, 48), frames.map { it.start })
        assertEquals(listOf(34, 60), frames.map { it.endExclusive })
        assertEquals(1125L, frames.first().cue.startMs)
    }

    @Test fun `adjacent cues share an end boundary without overlap`() {
        val frames = SrtStoryboard.frames(SrtStoryboard.parse(cue() + "\n\n" +
            cue("00:00:02,000", "00:00:03,000")), 24)
        assertEquals(frames[0].endExclusive, frames[1].start)
    }

    @Test fun `subframe shots receive one frame but collisions are rejected`() {
        val first = cue("00:00:00,001", "00:00:00,002")
        assertEquals(1, SrtStoryboard.frames(SrtStoryboard.parse(first), 12).single().endExclusive)
        rejects(SrtImportException.Reason.FRAME_COLLISION) {
            SrtStoryboard.frames(SrtStoryboard.parse(first + "\n\n" +
                cue("00:00:00,003", "00:00:00,004")), 12)
        }
    }

    @Test fun `overlap is rejected instead of losing a storyboard`() {
        rejects(SrtImportException.Reason.OVERLAP) {
            SrtStoryboard.parse(cue() + "\n\n" + cue("00:00:01,500", "00:00:03,000"))
        }
    }

    @Test fun `invalid block fails whole import instead of partially importing`() {
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse(cue() + "\n\n2\nbroken\ntext") }
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse("") }
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse(cue(text = "")) }
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse(cue(end = "00:00:01,000")) }
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse(cue(start = "00:60:01,000")) }
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse(cue(end = "24:00:00,000")) }
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.parse(cue(end = "99999999999999999999:00:00,000")) }
    }

    @Test fun `oversized files and too many cues are rejected`() {
        rejects(SrtImportException.Reason.TOO_LARGE) { SrtStoryboard.decode(ByteArray(SrtStoryboard.MAX_BYTES + 1)) }
        rejects(SrtImportException.Reason.TOO_LARGE) {
            SrtStoryboard.parse(List(SrtStoryboard.MAX_CUES + 1) { cue() }.joinToString("\n\n"))
        }
    }

    @Test fun `strict UTF8 and BOM UTF16 decoding`() {
        val text = cue(text = "你好\n世界")
        assertEquals(text, SrtStoryboard.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, SrtStoryboard.decode(text.toByteArray(Charsets.UTF_16)))
        rejects(SrtImportException.Reason.INVALID) { SrtStoryboard.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun `project asset round trip preserves time and text`() {
        val cues = SrtStoryboard.parse(cue(text = "<i>画面参考</i>\n第二行"))
        assertEquals(cues, SrtStoryboard.parse(SrtStoryboard.decode(SrtStoryboard.encode(cues))))
        assertEquals("01:02:03,004", SrtStoryboard.timestamp(3_723_004))
    }

    @Test fun `plain text import makes one one-frame caption per non-empty line`() {
        val cues = SrtStoryboard.parseImport("\uFEFF第一句\r\n\r\n第二句\r第三句")
        assertEquals(listOf("第一句", "第二句", "第三句"), cues.map { it.text })
        assertEquals(listOf(0L, 1000L, 2000L), cues.map { it.startMs })
        assertEquals(listOf(1, 2, 3), SrtStoryboard.oneFrameShots(cues).map { it.endExclusive })
    }

    @Test fun `import keeps SRT parsing and rejects malformed SRT instead of treating it as plain text`() {
        assertEquals("字幕", SrtStoryboard.parseImport(cue()).single().text)
        rejects(SrtImportException.Reason.INVALID) {
            SrtStoryboard.parseImport("00:00:01,000 --> broken\n字幕")
        }
    }

    @Test fun `appended and edited cues preserve order and reject blank or over-limit entries`() {
        val cues = SrtStoryboard.parse(cue(text = "第一句"))
        val appended = SrtStoryboard.appendedCue(cues, " 第二句 ")
        assertEquals(SrtCue(2000, 3000, "第二句"), appended)
        assertEquals(
            listOf("改好", "第二句"),
            SrtStoryboard.replaceCue(cues + appended!!, 0, "改好")!!.map { it.text },
        )
        assertNull(SrtStoryboard.replaceCue(cues, 0, "  "))
        assertNull(SrtStoryboard.appendedCue(cues, "  "))
        assertNull(SrtStoryboard.appendedCue(
            listOf(SrtCue(SrtStoryboard.MAX_TIMESTAMP_MS - 999, SrtStoryboard.MAX_TIMESTAMP_MS, "末句")),
            "超时字幕",
        ))
    }

    @Test fun `splitting a cue preserves every character on both sides of the caret`() {
        val original = "cf week is the best time the best"
        val cues = listOf(SrtCue(0, 1000, original))
        val split = SrtStoryboard.splitCue(cues, 0, "cf wee", "k is the best time the best")

        assertEquals("cf wee", split!![0].text)
        assertEquals("k is the best time the best", split[1].text)
        assertEquals(
            original.filterNot(Char::isWhitespace),
            split.joinToString("") { it.text }.filterNot(Char::isWhitespace),
        )
    }

    @Test fun `splitting at the start or end keeps the complete text in the other frame`() {
        val original = "第一句话"
        val cues = listOf(SrtCue(0, 1000, original))
        assertEquals(listOf("", original), SrtStoryboard.splitCue(cues, 0, "", original)!!.map { it.text })
        assertEquals(listOf(original, ""), SrtStoryboard.splitCue(cues, 0, original, "")!!.map { it.text })
        assertEquals(
            listOf("", original),
            SrtStoryboard.parseStored(
                SrtStoryboard.decode(SrtStoryboard.encode(SrtStoryboard.splitCue(cues, 0, "", original)!!)),
            ).map { it.text },
        )
    }

    @Test fun `insert cue shifts reference times and permits a persisted empty draft`() {
        val cues = SrtStoryboard.parse(
            cue("00:00:01,000", "00:00:02,000", "first") + "\n\n" +
                cue("00:00:02,000", "00:00:03,000", "second"),
        )
        val inserted = SrtStoryboard.insertCue(cues, 1, "")

        assertEquals(listOf("first", "", "second"), inserted!!.map { it.text })
        assertEquals(listOf(1000L, 2000L, 3000L), inserted.map { it.startMs })
        assertEquals(inserted, SrtStoryboard.parseStored(SrtStoryboard.decode(SrtStoryboard.encode(inserted))))
        rejects(SrtImportException.Reason.INVALID) {
            SrtStoryboard.parse(SrtStoryboard.decode(SrtStoryboard.encode(inserted)))
        }
    }

    @Test fun `subtitle assets cannot be mistaken for audio`() {
        assertEquals(listOf("voice.wav"), AnimationLayerOps.audioAssetsOf(
            listOf(SrtStoryboard.ASSET, SrtStoryboard.ASSET + ".fps", "voice.wav"),
        ))
    }
    @Test fun `one frame per caption ignores long source gaps and fractional times`() {
        val cues = listOf(SrtCue(1000, 99999, "a"), SrtCue(3600000, 3600001, "b"))
        val shots = SrtStoryboard.oneFrameShots(cues)
        assertEquals(listOf(0, 1), shots.map { it.start })
        assertEquals(listOf(1, 2), shots.map { it.endExclusive })
        assertEquals(cues, shots.map { it.cue })
    }

    @Test fun `resizing and undo follow native exposure boundaries while preserving caption order`() {
        val cues = listOf(SrtCue(0, 1000, "a"), SrtCue(3000, 5000, "b"), SrtCue(5000, 9000, "c"))
        val resized = SrtStoryboard.resizedShots(cues, listOf(0, 5, 6), 4)
        assertEquals(listOf(0, 5, 6), resized.map { it.start })
        assertEquals(listOf(5, 6, 10), resized.map { it.endExclusive })
        assertEquals(cues[0], resized.single { 4 >= it.start && 4 < it.endExclusive }.cue)
        assertEquals(cues[1], resized.single { 5 >= it.start && 5 < it.endExclusive }.cue)
        assertEquals(SrtStoryboard.oneFrameShots(cues), SrtStoryboard.resizedShots(cues, listOf(0, 1, 2), 1))
    }

    @Test fun `changed frame counts and invalid keys do not mislabel captions`() {
        val cues = listOf(SrtCue(0, 1000, "a"), SrtCue(2000, 3000, "b"))
        assertTrue(SrtStoryboard.resizedShots(cues, listOf(0), 1).isEmpty())
        assertTrue(SrtStoryboard.resizedShots(cues, listOf(0, 1, 2), 1).isEmpty())
        assertTrue(SrtStoryboard.resizedShots(cues, listOf(0, 0), 1).isEmpty())
        assertTrue(SrtStoryboard.resizedShots(cues, listOf(-1, 1), 1).isEmpty())
    }

    @Test fun `binary search finds active frame index correctly across gaps and boundaries`() {
        val cueA = SrtCue(0, 1000, "a")
        val cueB = SrtCue(2000, 3000, "b")
        val cueC = SrtCue(5000, 8000, "c")
        val frames = listOf(
            StoryboardFrame(cueA, 0, 5),
            StoryboardFrame(cueB, 10, 15),
            StoryboardFrame(cueC, 20, 25),
        )
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, -1))
        assertEquals(0, SrtStoryboard.findActiveFrameIndex(frames, 0))
        assertEquals(0, SrtStoryboard.findActiveFrameIndex(frames, 4))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, 5))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, 9))
        assertEquals(1, SrtStoryboard.findActiveFrameIndex(frames, 10))
        assertEquals(1, SrtStoryboard.findActiveFrameIndex(frames, 14))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, 15))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, 19))
        assertEquals(2, SrtStoryboard.findActiveFrameIndex(frames, 20))
        assertEquals(2, SrtStoryboard.findActiveFrameIndex(frames, 24))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, 25))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(frames, 100))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(emptyList(), 0))

        val singleFrame = listOf(StoryboardFrame(cueA, 2, 7))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(singleFrame, 1))
        assertEquals(0, SrtStoryboard.findActiveFrameIndex(singleFrame, 2))
        assertEquals(0, SrtStoryboard.findActiveFrameIndex(singleFrame, 6))
        assertEquals(-1, SrtStoryboard.findActiveFrameIndex(singleFrame, 7))

        val contiguous = listOf(StoryboardFrame(cueA, 0, 3), StoryboardFrame(cueB, 3, 6))
        assertEquals(0, SrtStoryboard.findActiveFrameIndex(contiguous, 2))
        assertEquals(1, SrtStoryboard.findActiveFrameIndex(contiguous, 3))
    }
}
