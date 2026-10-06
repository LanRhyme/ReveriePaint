/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectReferencesTest {
    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun `view state preserves image transform and display choices`() {
        val state = ReferenceViewState(true, true, true, 1, true, 2.5f, -37f, -123f, 876f)
        assertEquals(state, ProjectReferences.decodeState(ProjectReferences.encodeState(state)))
    }

    @Test fun `missing truncated unknown and nonfinite state uses safe defaults`() {
        val invalid = listOf(null, byteArrayOf(1), ByteArray(28),
            ProjectReferences.encodeState(ReferenceViewState(zoom = Float.NaN)),
            ProjectReferences.encodeState(ReferenceViewState(panX = Float.POSITIVE_INFINITY)),
            ProjectReferences.encodeState(ReferenceViewState(tab = 9)),
            ProjectReferences.encodeState(ReferenceViewState(zoom = 0f)))
        for (bytes in invalid) assertEquals(ReferenceViewState(), ProjectReferences.decodeState(bytes))
    }

    @Test fun `image bundle preserves ordering and exact bytes without local paths`() {
        val images = listOf(byteArrayOf(4, 8, 15), byteArrayOf(16, 23, 42))
        val restored = ProjectReferences.decodeImages(ProjectReferences.encodeImages(images))
        assertEquals(images.size, restored.size)
        images.zip(restored).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun `cleared images have nonempty encoding and replace a previous bundle`() {
        val cleared = ProjectReferences.encodeImages(emptyList())
        assertTrue(cleared.isNotEmpty())
        assertTrue(ProjectReferences.decodeImages(cleared).isEmpty())
        assertTrue(ProjectReferences.decodeImages(null).isEmpty())
        assertFalse(cleared.contentEquals(ProjectReferences.encodeImages(listOf(byteArrayOf(1)))))
    }

    @Test fun `unexpected names directories gaps and empty payloads are rejected`() {
        val invalid = listOf(zip("../0.png" to byteArrayOf(1)), zip("1.png" to byteArrayOf(1)),
            zip("0.png/" to byteArrayOf(1)), zip("0.png" to byteArrayOf()),
            zip("0.png" to byteArrayOf(1), "2.png" to byteArrayOf(2)), ByteArray(22))
        for (bytes in invalid) assertTrue(runCatching { ProjectReferences.decodeImages(bytes) }.isFailure)
    }

    @Test fun `bundle count and decoded byte budgets are enforced`() {
        assertTrue(runCatching {
            ProjectReferences.encodeImages(List(ProjectReferences.MAX_IMAGES + 1) { byteArrayOf(1) })
        }.isFailure)
        // Small compressed input with an oversized decompressed entry, independent of ZIP size claims.
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("0.png"))
                val block = ByteArray(1024 * 1024)
                repeat(65) { zip.write(block) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(runCatching { ProjectReferences.decodeImages(bomb) }.isFailure)
    }
}
