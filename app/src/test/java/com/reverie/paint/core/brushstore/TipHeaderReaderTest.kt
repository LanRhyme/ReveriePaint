/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 笔尖文件头尺寸解析：手工构造最小 PNG IHDR / GBR v2 / GIH 头，坏字节返回 null */
class TipHeaderReaderTest {

    private fun be32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun png_ihdrDimensions() {
        // 签名 + IHDR chunk：长度 13 + "IHDR" + 宽 320 + 高 200 + bitdepth/color 等
        val bytes = pngMagic + be32(13) + "IHDR".toByteArray() + be32(320) + be32(200) +
            byteArrayOf(8, 6, 0, 0, 0)
        assertEquals(320 to 200, TipHeaderReader.readDimensions(bytes))
    }

    @Test
    fun gbr_v2HeaderDimensions() {
        // header_size=28, version=2, width=64, height=48, bytes=1, magic "GIMP", spacing=100
        val bytes = be32(28) + be32(2) + be32(64) + be32(48) + be32(1) +
            "GIMP".toByteArray() + be32(100)
        assertEquals(64 to 48, TipHeaderReader.readDimensions(bytes))
    }

    @Test
    fun gih_parasiteCellDimensions() {
        // 第 1 行名字，第 2 行 "<ncells> ncells:3 cellwidth:168 cellheight:514 …"，其后是二进制 gbr
        val header = "A_4 Bamboo\n3 ncells:3 cellwidth:168 cellheight:514 step:100 dim:1 cols:1 rows:1\n"
        val bytes = header.toByteArray() + ByteArray(16)
        assertEquals(168 to 514, TipHeaderReader.readDimensions(bytes))
    }

    @Test
    fun gih_legacyPositionalDimensions() {
        // 旧格式：第 2 行只有 ncells，第 3 行前两个整数为 cell width / height
        val header = "legacy\n4\n32 24 100 1 1 1\n"
        val bytes = header.toByteArray() + ByteArray(8)
        assertEquals(32 to 24, TipHeaderReader.readDimensions(bytes))
    }

    @Test
    fun realGbrAssetHeader() {
        val f = KppFixtures.dir.parentFile?.resolve("brushes/A_bokey.gbr") ?: return
        if (!f.isFile) return
        val dims = TipHeaderReader.readDimensions(f.readBytes())!!
        // 真实资产：宽高在 GBR v2 头 8..15 字节，且与 od 观察一致（0x100 x 0x100）
        assertEquals(256, dims.first)
        assertEquals(256, dims.second)
    }

    @Test
    fun brokenBytesReturnNull() {
        assertNull(TipHeaderReader.readDimensions(ByteArray(0)))
        assertNull(TipHeaderReader.readDimensions("not an image".toByteArray()))
        // PNG 签名后不是 IHDR
        assertNull(TipHeaderReader.readDimensions(pngMagic + be32(13) + "IDAT".toByteArray() + be32(1) + be32(1)))
        // GBR 魔数不对
        assertNull(TipHeaderReader.readDimensions(be32(28) + be32(2) + be32(64) + be32(48) + be32(1) + "XXXX".toByteArray() + be32(100)))
        // GIH 缺少尺寸信息
        assertNull(TipHeaderReader.readDimensions("name\n3\n".toByteArray()))
        // 尺寸非正数
        assertNull(
            TipHeaderReader.readDimensions(
                pngMagic + be32(13) + "IHDR".toByteArray() + be32(0) + be32(0) + byteArrayOf(8, 6, 0, 0, 0),
            ),
        )
    }
}
