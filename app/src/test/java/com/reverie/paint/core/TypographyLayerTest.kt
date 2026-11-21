/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.Point2D
import com.reverie.paint.model.ShapeGeometry
import com.reverie.paint.model.TypographyConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TypographyLayerTest {

    @Test
    fun `TypographyConfig serialization and deserialization roundtrip preserves all attributes`() {
        val original = TypographyConfig(
            id = "test-uuid-12345",
            text = "Hello ReveriePaint 文字图层",
            fontFamilyName = "custom_font",
            fontPath = "/data/user/0/com.reverie.paint/files/font.ttf",
            fontSize = 64f,
            letterSpacingSp = 2.5f,
            lineHeightMultiplier = 1.5f,
            isBold = true,
            isItalic = true,
            isUnderline = true,
            isAllCaps = false,
            alignment = 1,
            textColor = "#FF5722",
            posX = 250f,
            posY = 480f,
            boxWidth = 600f,
            boxHeight = 320f,
            rotationDeg = 45f,
            snapEnabled = false,
            isVertical = true,
            verticalRtl = false,
        )

        val json = original.toJsonObject()

        assertEquals("test-uuid-12345", json.getString("id"))
        assertEquals("Hello ReveriePaint 文字图层", json.getString("text"))
        assertEquals(64.0, json.getDouble("fontSize"), 0.001)
        assertEquals(45.0, json.getDouble("rotationDeg"), 0.001)
        assertTrue(json.getBoolean("isVertical"))

        val restored = typographyConfigFromJson(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.text, restored.text)
        assertEquals(original.fontFamilyName, restored.fontFamilyName)
        assertEquals(original.fontPath, restored.fontPath)
        assertEquals(original.fontSize, restored.fontSize, 0.001f)
        assertEquals(original.letterSpacingSp, restored.letterSpacingSp, 0.001f)
        assertEquals(original.lineHeightMultiplier, restored.lineHeightMultiplier, 0.001f)
        assertEquals(original.isBold, restored.isBold)
        assertEquals(original.isItalic, restored.isItalic)
        assertEquals(original.isUnderline, restored.isUnderline)
        assertEquals(original.isAllCaps, restored.isAllCaps)
        assertEquals(original.alignment, restored.alignment)
        assertEquals(original.textColor, restored.textColor)
        assertEquals(original.posX, restored.posX, 0.001f)
        assertEquals(original.posY, restored.posY, 0.001f)
        assertEquals(original.boxWidth, restored.boxWidth, 0.001f)
        assertEquals(original.boxHeight, restored.boxHeight, 0.001f)
        assertEquals(original.rotationDeg, restored.rotationDeg, 0.001f)
        assertEquals(original.snapEnabled, restored.snapEnabled)
        assertEquals(original.isVertical, restored.isVertical)
        assertEquals(original.verticalRtl, restored.verticalRtl)
    }

    @Test
    fun `parseTextLayerJson supports multiple text items per layer`() {
        val item1 = TypographyConfig(id = "item-1", text = "标题文本", fontSize = 72f)
        val item2 = TypographyConfig(id = "item-2", text = "正文对白", fontSize = 36f)

        val layerJson = JSONObject().apply {
            put("layerId", 10086L)
            put("layerName", "对白图层")
            put("items", JSONArray().apply {
                put(item1.toJsonObject())
                put(item2.toJsonObject())
            })
        }

        val parsed = parseTextLayerJson(layerJson)
        assertNotNull(parsed)
        assertEquals(10086L, parsed!!.first)
        assertEquals(2, parsed.second.size)
        assertEquals("item-1", parsed.second[0].id)
        assertEquals("标题文本", parsed.second[0].text)
        assertEquals("item-2", parsed.second[1].id)
        assertEquals("正文对白", parsed.second[1].text)
    }

    @Test
    fun `parseTextLayerJson backward compatibility with legacy single-item format`() {
        val legacyJson = JSONObject().apply {
            put("layerId", 42L)
            put("text", "Legacy Single Text")
            put("fontSize", 50.0)
        }

        val parsed = parseTextLayerJson(legacyJson)
        assertNotNull(parsed)
        assertEquals(42L, parsed!!.first)
        assertEquals(1, parsed.second.size)
        assertEquals("Legacy Single Text", parsed.second[0].text)
        assertEquals(50f, parsed.second[0].fontSize, 0.001f)
    }

    @Test
    fun `ShapeGeometry rotatePoint correctly transforms hit-test coordinates`() {
        val center = Point2D(100f, 100f)
        val point = Point2D(100f, 50f) // 50 units directly above center

        // Rotate 90 degrees clockwise -> should be at (150, 100)
        val rotated90 = ShapeGeometry.rotatePoint(point, center, 90f)
        assertTrue(abs(rotated90.x - 150f) < 0.01f)
        assertTrue(abs(rotated90.y - 100f) < 0.01f)

        // Rotate back -90 degrees -> should return to (100, 50)
        val unrotated = ShapeGeometry.rotatePoint(rotated90, center, -90f)
        assertTrue(abs(unrotated.x - point.x) < 0.01f)
        assertTrue(abs(unrotated.y - point.y) < 0.01f)
    }
}
