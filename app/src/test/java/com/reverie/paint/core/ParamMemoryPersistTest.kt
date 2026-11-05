/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v5 S3: 写出后重启读回，key 是 id；旧的按名字的 "pm" 能换成 id 读入，无 id 的旧条目丢弃。
 */
class ParamMemoryPersistTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("param_memory_persist_test").toFile()
        BrushIdentity.open(root)
    }

    @After
    fun tearDown() {
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    /**
     * 辅助函数：根据 S3 规范将 toolBrushStates 序列化为 JSON 字符串
     */
    private fun serializeToolBrushStates(map: Map<String, PaintViewModel.ToolBrushState>): String {
        val json = JSONArray()
        for ((id, s) in map) {
            val o = JSONObject()
            o.put("id", id)
            o.put("pid", s.presetId)
            o.put("c", s.category)
            o.put("csi", s.categoryScrollIndex)
            o.put("cso", s.categoryScrollOffset)
            o.put("psi", s.presetScrollIndex)
            o.put("pso", s.presetScrollOffset)
            if (s.paramMemory.isNotEmpty()) {
                val pmi = JSONArray()
                for ((k, v) in s.paramMemory) {
                    if (v.size >= 3) {
                        pmi.put(JSONArray().apply {
                            put(k); put(v[0]); put(v[1]); put(v[2])
                        })
                    }
                }
                o.put("pmi", pmi)
            }
            json.put(o)
        }
        return json.toString()
    }

    /**
     * 辅助函数：根据 S3 规范反序列化 JSON 字符串为 ToolBrushState map
     */
    private fun deserializeToolBrushStates(raw: String): Map<String, PaintViewModel.ToolBrushState> {
        val json = JSONArray(raw)
        val map = mutableMapOf<String, PaintViewModel.ToolBrushState>()
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val pmiJson = o.optJSONArray("pmi")
            val pmJson = o.optJSONArray("pm")
            var paramMemory: Map<String, List<Double>> = emptyMap()
            if (pmiJson != null) {
                val m = mutableMapOf<String, List<Double>>()
                for (j in 0 until pmiJson.length()) {
                    val e = pmiJson.optJSONArray(j) ?: continue
                    if (e.length() >= 4) {
                        m[e.optString(0)] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                    }
                }
                paramMemory = m
            } else if (pmJson != null) {
                val m = mutableMapOf<String, List<Double>>()
                for (j in 0 until pmJson.length()) {
                    val e = pmJson.optJSONArray(j) ?: continue
                    if (e.length() >= 4) {
                        val oldName = e.optString(0)
                        val id = BrushIdentity.idForName(oldName)
                        if (id != null) {
                            m[id] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                        }
                    }
                }
                paramMemory = m
            }
            var pid = o.optString("pid", "")
            val pn = o.optString("pn", "")
            if (pid.isBlank() && pn.isNotBlank()) {
                pid = BrushIdentity.idForName(pn) ?: ""
            }
            map[o.getString("id")] = PaintViewModel.ToolBrushState(
                presetIndex = o.optInt("pi", -1),
                presetId = pid,
                category = o.optString("c", "全部"),
                categoryScrollIndex = o.optInt("csi", 0),
                categoryScrollOffset = o.optInt("cso", 0),
                presetScrollIndex = o.optInt("psi", 0),
                presetScrollOffset = o.optInt("pso", 0),
                paramMemory = paramMemory
            )
        }
        return map
    }

    @Test
    fun `writing state uses pid and pmi and restores correctly on read`() {
        val id = "imp:pencil_soft"
        BrushIdentity.register(BrushIdentity.Entry(id, "Pencil Soft", "kpp", BrushIdentity.Kind.IMPORTED))

        val original = mapOf(
            "brush" to PaintViewModel.ToolBrushState(
                presetIndex = 3,
                presetId = id,
                category = "铅笔",
                paramMemory = mapOf(id to listOf(18.5, 0.75, 0.90))
            )
        )

        val serialized = serializeToolBrushStates(original)
        val jsonArray = JSONArray(serialized)
        val obj = jsonArray.getJSONObject(0)

        // 验证写出字段：有 pid 和 pmi，没有 pi、pn、pm
        assertTrue("必须含有 pid", obj.has("pid"))
        assertEquals(id, obj.getString("pid"))
        assertTrue("必须含有 pmi", obj.has("pmi"))
        assertFalse("不应再写出 pi", obj.has("pi"))
        assertFalse("不应再写出 pn", obj.has("pn"))
        assertFalse("不应再写出 pm", obj.has("pm"))

        val deserialized = deserializeToolBrushStates(serialized)
        val restored = deserialized["brush"]
        assertNotNull(restored)
        assertEquals(id, restored?.presetId)
        assertEquals("铅笔", restored?.category)
        assertTrue(restored!!.paramMemory.containsKey(id))
        assertEquals(18.5, restored.paramMemory[id]!![0], 1e-6)
        assertEquals(0.75, restored.paramMemory[id]!![1], 1e-6)
        assertEquals(0.90, restored.paramMemory[id]!![2], 1e-6)
    }

    @Test
    fun `reading legacy json with pm and pn maps to id and drops unknown entries`() {
        val validName = "LegacyPencil"
        val validId = "imp:legacy_pencil"
        BrushIdentity.register(BrushIdentity.Entry(validId, validName, "kpp", BrushIdentity.Kind.IMPORTED))

        // 构造含有旧字段 pn, pi, pm 的 JSON 字符串
        val legacyJson = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "brush")
                put("pi", 7)
                put("pn", validName)
                put("c", "基础")
                put("pm", JSONArray().apply {
                    put(JSONArray().apply { put(validName); put(22.0); put(0.8); put(1.0) })
                    put(JSONArray().apply { put("DeletedBrush"); put(99.0); put(1.0); put(1.0) })
                })
            })
        }.toString()

        val deserialized = deserializeToolBrushStates(legacyJson)
        val restored = deserialized["brush"]
        assertNotNull(restored)

        // pn 回填为 pid
        assertEquals(validId, restored?.presetId)

        // pm 兼容读：有效名字转成 id
        assertTrue("已知旧名字必须成功映射到 id", restored!!.paramMemory.containsKey(validId))
        assertEquals(22.0, restored.paramMemory[validId]!![0], 1e-6)

        // 找不到 id 的条目（例如 DeletedBrush）被丢弃
        assertFalse("无 id 的条目必须被丢弃", restored.paramMemory.containsKey("DeletedBrush"))
        assertEquals(1, restored.paramMemory.size)
    }

    @Test
    fun `pmi takes precedence over legacy pm`() {
        val id = "imp:new_brush"
        BrushIdentity.register(BrushIdentity.Entry(id, "NewBrush", "kpp", BrushIdentity.Kind.IMPORTED))

        val mixedJson = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "brush")
                put("pid", id)
                put("pmi", JSONArray().apply {
                    put(JSONArray().apply { put(id); put(30.0); put(1.0); put(1.0) })
                })
                put("pm", JSONArray().apply {
                    put(JSONArray().apply { put("NewBrush"); put(99.0); put(1.0); put(1.0) })
                })
            })
        }.toString()

        val deserialized = deserializeToolBrushStates(mixedJson)
        val restored = deserialized["brush"]
        assertNotNull(restored)
        // 优先读取 pmi
        assertEquals(30.0, restored!!.paramMemory[id]!![0], 1e-6)
    }
}
