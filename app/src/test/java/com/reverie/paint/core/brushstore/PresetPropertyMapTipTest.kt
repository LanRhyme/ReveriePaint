/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetPropertyMapTipTest {

    private fun kpp(brushDef: String): String =
        """<Preset paintopid="paintbrush" name="t">
 <param type="string" name="brush_definition"><![CDATA[$brushDef]]></param>
</Preset>"""

    @Test
    fun autoSpacingSurvivesTipSwap() {
        val xml = kpp("""<Brush scale="1" width="64" height="64" type="png_brush" useAutoSpacing="1" autoSpacingCoeff="0.7" BrushVersion="2" filename="old.png" spacing="0.2" angle="0" brushApplication="0"/>""")
        val out = PresetPropertyMap.writeTipAsset(xml, "new.png", BrushParams(spacing = 0.7))
        assertEquals("1", KppXml.brushAttr(out, "useAutoSpacing"))
        assertEquals("0.7", KppXml.brushAttr(out, "autoSpacingCoeff"))
        assertEquals("0.2", KppXml.brushAttr(out, "spacing"))
        assertEquals("new.png", KppXml.brushAttr(out, "filename"))
    }

    @Test
    fun fixedSpacingStaysFixed() {
        val xml = kpp("""<Brush scale="1" width="64" height="64" type="png_brush" useAutoSpacing="0" BrushVersion="2" filename="old.png" spacing="0.2" angle="0" brushApplication="0"/>""")
        val out = PresetPropertyMap.writeTipAsset(xml, "new.png", BrushParams(spacing = 0.35))
        assertEquals("0", KppXml.brushAttr(out, "useAutoSpacing"))
        assertEquals("0.35", KppXml.brushAttr(out, "spacing"))
    }
}
