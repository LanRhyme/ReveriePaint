/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ReferenceViewState(
    val open: Boolean = false,
    val grayscale: Boolean = false,
    val flipped: Boolean = false,
    val tab: Int = 0,
    val collapsed: Boolean = false,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

/** Two reserved document assets; image data never goes into gallery metadata or recording events. */
object ProjectReferences {
    const val IMAGES_ASSET = "reverie-reference-images-v1.zip"
    const val STATE_ASSET = "reverie-reference-state-v1.bin"
    const val MAX_IMAGES = 50
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAGIC = 0x52524631

    fun encodeState(state: ReferenceViewState): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeBoolean(state.open); out.writeBoolean(state.grayscale); out.writeBoolean(state.flipped)
            out.writeInt(state.tab); out.writeBoolean(state.collapsed)
            out.writeFloat(state.zoom); out.writeFloat(state.rotation)
            out.writeFloat(state.panX); out.writeFloat(state.panY)
        }
    }.toByteArray()

    fun decodeState(bytes: ByteArray?): ReferenceViewState {
        if (bytes == null || bytes.size != 28) return ReferenceViewState()
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                ReferenceViewState(input.readBoolean(), input.readBoolean(), input.readBoolean(),
                    input.readInt(), input.readBoolean(), input.readFloat(), input.readFloat(),
                    input.readFloat(), input.readFloat()).also {
                    require(it.tab in 0..1 && it.zoom.isFinite() && it.zoom in .02f..128f)
                    require(it.rotation.isFinite() && it.panX.isFinite() && it.panY.isFinite())
                }
            }
        }.getOrDefault(ReferenceViewState())
    }

    fun encodeImages(images: List<ByteArray>): ByteArray {
        require(images.size <= MAX_IMAGES && images.sumOf { it.size.toLong() } <= MAX_BYTES)
        return ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                // PNG is already compressed; storing avoids recompressing large reference images.
                images.forEachIndexed { index, bytes ->
                    require(bytes.isNotEmpty())
                    zip.putNextEntry(ZipEntry("$index.png").apply {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    /** No filesystem extraction. Enforce bounds while streaming, including forged ZIP sizes. */
    fun decodeImages(bytes: ByteArray?): List<ByteArray> {
        if (bytes == null) return emptyList()
        require(bytes.size in 22..(MAX_BYTES + 16384))
        require(bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte())
        val empty = encodeImages(emptyList())
        if (bytes.contentEquals(empty)) return emptyList()
        require(bytes[2] == 3.toByte() && bytes[3] == 4.toByte())
        val images = mutableListOf<ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(images.size < MAX_IMAGES && entry.name == "${images.size}.png" && !entry.isDirectory)
                val buffer = ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (true) {
                    val count = zip.read(block)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES)
                    buffer.write(block, 0, count)
                }
                require(buffer.size() > 0)
                images.add(buffer.toByteArray())
                zip.closeEntry()
            }
        }
        require(images.isNotEmpty())
        return images
    }
}
