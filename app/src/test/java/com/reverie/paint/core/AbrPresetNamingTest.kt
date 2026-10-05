/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ABR 预设落盘名的命名规则。
 *
 * 真实样本: Krita 自带测试数据 `brushes_by_mar_ka_d338ela.abr` (v6 subversion 2,
 * 31 个笔尖 / 36 个预设), Photoshop 把其中大量笔刷命名为纯数字 "1"、"2"、"3"…
 *
 * 裸数字名的两个实际问题:
 *  1. 笔刷列表里完全认不出属于哪个包;
 *  2. 再导入第二个同命名的包会走重名兜底, 变成 "1_2"、"2_2", 越导越乱。
 *
 * Krita 的做法是统一拼包名 (`brushes_by_mar_ka_d338ela_2`)。这里收窄为只处理
 * 纯数字名 —— 自带描述的名字保持原样, 避免 "Round 10 Hardness 80%" 变成
 * "marKA_Round 10 Hardness 80%" 这种冗余前缀。
 */
class AbrPresetNamingTest {

    @Test
    fun `纯数字名补包名前缀`() {
        assertEquals("marKA_1", abrPresetName("1", "marKA", 0))
        assertEquals("marKA_27", abrPresetName("27", "marKA", 26))
        assertEquals("pentaBristle_3", abrPresetName(" 3 ", "pentaBristle", 2))
    }

    @Test
    fun `自带描述的名字保持原样`() {
        assertEquals(
            "Round 10 Hardness 80%",
            abrPresetName("Round 10 Hardness 80%", "marKA", 0),
        )
        assertEquals("星芒笔刷", abrPresetName("星芒笔刷", "marKA", 3))
    }

    @Test
    fun `空名回落到包名加序号`() {
        assertEquals("marKA 1", abrPresetName("", "marKA", 0))
        assertEquals("marKA 6", abrPresetName("   ", "marKA", 5))
    }

    @Test
    fun `只要含字母就不加前缀`() {
        // "4ever" 这类含字母的仍视为有描述性, 不加前缀
        assertEquals("4ever", abrPresetName("4ever", "marKA", 3))
    }
}
