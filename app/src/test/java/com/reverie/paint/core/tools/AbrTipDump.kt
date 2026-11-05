/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.tools

import com.reverie.paint.core.AbrParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Task C 证据脚本：把 MEIKA_ABR 里指定 uuid 的采样笔尖导出成 PNG，并报告边框 alpha 统计。ABR_TIP_UUIDS=逗号分隔，ABR_TIP_OUT=目录。 */
class AbrTipDump {
    @Test
    fun dump() {
        val f = System.getenv("MEIKA_ABR")?.let(::File)
        assumeTrue("MEIKA_ABR 未设置", f != null && f.isFile)
        val want = (System.getenv("ABR_TIP_UUIDS") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        val out = File(System.getenv("ABR_TIP_OUT") ?: "/tmp/tips").also { it.mkdirs() }
        val sb = StringBuilder()
        f!!.inputStream().use { ins ->
            AbrParser.parse(ins, "MEIKA") { t ->
                if (t.uuid in want || want.isEmpty()) {
                    var border = 0; var borderOpaque = 0; var total = 0L; var maxA = 0
                    val w = t.width; val h = t.height
                    for (y in 0 until h) for (x in 0 until w) {
                        val a = t.data[y * w + x].toInt() and 0xFF
                        total += a; if (a > maxA) maxA = a
                        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { border++; if (a > 40) borderOpaque++ }
                    }
                    // 距边 2% 内的"近边"带
                    val bx = maxOf(1, w / 50); val by = maxOf(1, h / 50)
                    var band = 0; var bandOp = 0
                    for (y in 0 until h) for (x in 0 until w) if (x < bx || y < by || x >= w - bx || y >= h - by) { band++; if ((t.data[y * w + x].toInt() and 0xFF) > 40) bandOp++ }
                    sb.appendLine("${t.uuid} ${w}x$h depth=${t.depth} mean=${"%.1f".format(total.toDouble() / (w * h))} max=$maxA borderOpaque=$borderOpaque/$border band2%Opaque=$bandOp/$band")
                    File(out, "${t.uuid}.png").writeBytes(AbrParser.encodeTipPng(t))
                }
            }
        }
        File(out, "tips.txt").writeText(sb.toString())
    }
}
