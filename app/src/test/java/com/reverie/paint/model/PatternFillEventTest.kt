package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class PatternFillEventTest {
    private val png = byteArrayOf(1, 2, 3, 4)
    private fun event() = PatternFillEvent(3, false, 42, 17, 25, false, -8, 4, 6, 0.37, "multiply", png)

    @Test fun `round trip preserves target geometry and composition`() {
        val source = event()
        val buffer = RecordingBuffer()
        source.writeTo(buffer, null)
        val reader = RecordingReader(buffer.data.copyOf(buffer.size))
        val result = PatternFillEvent.readFrom(reader, null)
        assertEquals(source.copy(png = result.png), result)
        assertArrayEquals(png, result.png)
        assertEquals(0, reader.remaining())
    }

    @Test fun `repeated pattern is referenced without repeating image bytes`() {
        val buffer = RecordingBuffer()
        event().writeTo(buffer, null)
        val fullSize = buffer.size
        event().copy(wholeLayer = true).writeTo(buffer, png)
        assertEquals(fullSize - png.size, buffer.size - fullSize)
        val reader = RecordingReader(buffer.data.copyOf(buffer.size))
        val first = PatternFillEvent.readFrom(reader, null)
        val second = PatternFillEvent.readFrom(reader, first.png)
        assertSame(first.png, second.png)
        assertTrue(second.wholeLayer)
    }

    @Test fun `reference cannot be read without a preceding image`() {
        val buffer = RecordingBuffer()
        event().writeTo(buffer, png)
        assertThrows(IllegalArgumentException::class.java) {
            PatternFillEvent.readFrom(RecordingReader(buffer.data.copyOf(buffer.size)), null)
        }
    }

    @Test fun `truncated pattern is rejected before allocation`() {
        val buffer = RecordingBuffer()
        event().writeTo(buffer, null)
        assertThrows(IllegalArgumentException::class.java) {
            PatternFillEvent.readFrom(RecordingReader(buffer.data.copyOf(buffer.size - 1)), null)
        }
    }

    @Test fun `invalid opacity and empty pattern are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { event().copy(opacity = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { event().copy(png = byteArrayOf()) }
    }
}
