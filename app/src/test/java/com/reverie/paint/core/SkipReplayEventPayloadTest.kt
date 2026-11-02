/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingEvents.CONTEXT
import com.reverie.paint.model.RecordingEvents.CONTEXT_EXT
import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.FILTER_LUT
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import com.reverie.paint.model.RecordingEvents.T_CROP
import com.reverie.paint.model.RecordingEvents.T_ENCLOSE_FILL
import com.reverie.paint.model.RecordingEvents.T_GRADIENT_V3
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_PROFILE
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_SIZE
import com.reverie.paint.model.RecordingEvents.T_POLYGON
import com.reverie.paint.model.RecordingEvents.T_PRESET_SELECT_V2
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL
import com.reverie.paint.model.RecordingEvents.T_SHAPE
import com.reverie.paint.model.RecordingEvents.T_WARP
import com.reverie.paint.model.RecordingReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 skipReplayEventPayload 与各事件写入格式的字节长度一致性。
 * 这些测试不触碰 JNI/C++，只验证 RecordingReader 游标推进。
 */
class SkipReplayEventPayloadTest {
    private fun skipAndAssert(
        type: Int,
        version: Int = 2,
        build: RecordingBuffer.() -> Unit,
    ) {
        val buf = RecordingBuffer(16)
        buf.build()
        val r = RecordingReader(buf.data.copyOf(buf.size))
        skipReplayEventPayload(type, r, version)
        assertEquals(buf.size, r.pos)
        assertEquals(0, r.remaining())
    }

    @Test
    fun `stroke move payload`() {
        skipAndAssert(STROKE_MOVE) {
            f32(1f)
            f32(2f)
            f32(0.5f)
        }
    }

    @Test
    fun `context payload`() {
        skipAndAssert(CONTEXT) {
            u8(0)
            u16(3)
            f32(12f)
            f32(1f)
            f32(1f)
            str("normal")
            str("#FF112233")
            u16(2)
        }
    }

    @Test
    fun `context ext payload version 2 and 1`() {
        val base: RecordingBuffer.() -> Unit = {
            repeat(9) { f32(it.toFloat()) }
            str("#FF000000")
            u8(1)
            f32(0.5f)
        }
        skipAndAssert(CONTEXT_EXT, 2) {
            base()
            u8(0)
        }
        skipAndAssert(CONTEXT_EXT, 1) {
            base()
        }
    }

    @Test
    fun `filter and lut payloads`() {
        skipAndAssert(FILTER) {
            u16(1)
            u8(2)
            f64(1.0)
            f64(2.0)
            f64(3.0)
            f64(4.0)
            str("Curves")
        }
        skipAndAssert(FILTER_LUT) {
            u16(1)
            u8(0)
            u32(3)
            writeBytes(byteArrayOf(1, 2, 3))
            str("LUT")
        }
    }

    @Test
    fun `layer op payload`() {
        skipAndAssert(LAYER_OP) {
            u8(1)
            u16(2)
            str("arg")
        }
    }

    @Test
    fun `tool shape polygon and enclose fill payloads`() {
        skipAndAssert(TOOL_OP) {
            u8(T_SHAPE)
            u8(1)
            f32(1f)
            f32(2f)
            f32(3f)
            f32(4f)
            u8(1)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_POLYGON)
            u8(1)
            u16(2)
            repeat(2) {
                f32(1f)
                f32(2f)
            }
        }
        skipAndAssert(TOOL_OP) {
            u8(T_ENCLOSE_FILL)
            u16(2)
            repeat(2) {
                f32(1f)
                f32(2f)
            }
            u16(10)
            u8(1)
            u16(0)
            u16(2)
            u16(4)
            u8(0)
            u32(0xFF112233.toInt())
            u8(1)
        }
    }

    @Test
    fun `tool warp crop and select all payloads`() {
        skipAndAssert(TOOL_OP) {
            u8(T_WARP)
            u16(2)
            repeat(2) {
                f32(1f)
                f32(2f)
            }
            repeat(2) {
                f32(3f)
                f32(4f)
            }
            f64(0.0)
            f64(0.0)
            f64(100.0)
            f64(100.0)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_CROP)
            u16(1)
            u16(2)
            u16(3)
            u16(4)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_SELECT_ALL)
            u16(2)
            u8(0)
        }
    }

    @Test
    fun `tool gradient v3 payload`() {
        skipAndAssert(TOOL_OP) {
            u8(T_GRADIENT_V3)
            f32(1f)
            f32(2f)
            f32(3f)
            f32(4f)
            u8(0)
            u8(0)
            u8(0)
            f32(1f)
            u8(0)
            str("normal")
            u16(2)
            repeat(2) {
                f32(0.5f)
                u32(0xFF112233.toInt())
            }
        }
    }

    @Test
    fun `tool liquify payloads`() {
        skipAndAssert(TOOL_OP) {
            u8(T_LIQUIFY_PROFILE)
            u8(1)
            f32(0.5f)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_LIQUIFY_SIZE)
            f32(20f)
        }
    }

    // ------------------------------------------------------------ #136：v2 选笔事件

    private fun RecordingBuffer.presetSelectV2(
        index: Int = 3,
        name: String = "Maru pen",
        presetId: String = "imp_0a1b2c3d",
        md5: String = "d41d8cd98f00b204e9800998ecf8427e",
    ) {
        u8(T_PRESET_SELECT_V2)
        u16(index)
        str(name)
        str(presetId)
        str(md5)
    }

    @Test
    fun `tool preset select v2 payload`() {
        skipAndAssert(TOOL_OP) {
            presetSelectV2()
        }
    }

    @Test
    fun `v2 选笔事件不再让有效时长退回 -1`() {
        val buf = RecordingBuffer(64)
        // 从 v2 笔刷库选预设（#127 W-12 写入 op 50），随后一条普通笔画
        buf.u8(TOOL_OP)
        buf.varint(10)
        buf.presetSelectV2()
        buf.u8(STROKE_MOVE)
        buf.varint(20)
        buf.f32(1f)
        buf.f32(2f)
        buf.f32(0.5f)

        assertEquals(
            "op 50 的 payload 被完整跳过后才能得到 10+20=30ms；错位会返回 -1",
            30L,
            ReplayTimeline.effectiveTotalMs(buf.data.copyOf(buf.size), 2, 1000L, 2),
        )
    }

    @Test
    fun `未登记的事件类型降级为 -1 而不是静默错位`() {
        // 顶层类型未登记：无法得知 payload 长度，必须明确失败
        val top = RecordingBuffer(16)
        top.u8(0x7E)
        top.varint(10)
        assertEquals(-1L, ReplayTimeline.effectiveTotalMs(top.data.copyOf(top.size), 2, 1000L))

        // 工具子类型未登记：同上，跳过 u8 后 payload 仍无从跳过
        val sub = RecordingBuffer(16)
        sub.u8(TOOL_OP)
        sub.varint(10)
        sub.u8(0x7E)
        assertEquals(-1L, ReplayTimeline.effectiveTotalMs(sub.data.copyOf(sub.size), 2, 1000L))
    }

    @Test
    fun `T_SELECT_ALL 无论是 2 字节还是 3 字节，后接事件流均不发生错位`() {
        // 场景 1: 旧格式 / selectAllAction 写入的 2 字节 (u16 layer)，后接 CONTEXT 事件
        val buf2Context = RecordingBuffer(64)
        buf2Context.u8(TOOL_OP)
        buf2Context.varint(10)
        buf2Context.u8(T_SELECT_ALL)
        buf2Context.u16(1) // 仅 2 字节
        buf2Context.u8(CONTEXT)
        buf2Context.varint(15)
        buf2Context.u8(0) // toolMode
        buf2Context.u16(0) // preset
        buf2Context.f32(10f) // size
        buf2Context.f32(1f) // opacity
        buf2Context.f32(1f) // flow
        buf2Context.str("normal")
        buf2Context.str("#000000")
        buf2Context.u16(1) // layer
        assertEquals(25L, ReplayTimeline.effectiveTotalMs(buf2Context.data.copyOf(buf2Context.size), 2, 1000L, 2))

        // 场景 2: 旧格式 / selectAllAction 写入的 2 字节，后接普通笔画
        val buf2Stroke = RecordingBuffer(64)
        buf2Stroke.u8(TOOL_OP)
        buf2Stroke.varint(10)
        buf2Stroke.u8(T_SELECT_ALL)
        buf2Stroke.u16(1) // 仅 2 字节
        buf2Stroke.u8(STROKE_MOVE)
        buf2Stroke.varint(20)
        buf2Stroke.f32(100f)
        buf2Stroke.f32(200f)
        buf2Stroke.f32(0.8f)
        assertEquals(30L, ReplayTimeline.effectiveTotalMs(buf2Stroke.data.copyOf(buf2Stroke.size), 2, 1000L, 2))

        // 场景 3: selectionFromLayer 写入的 3 字节 (u16 layer + u8 mode)，后接普通笔画
        val buf3Stroke = RecordingBuffer(64)
        buf3Stroke.u8(TOOL_OP)
        buf3Stroke.varint(10)
        buf3Stroke.u8(T_SELECT_ALL)
        buf3Stroke.u16(1)
        buf3Stroke.u8(2) // mode = SUBTRACT
        buf3Stroke.u8(STROKE_MOVE)
        buf3Stroke.varint(20)
        buf3Stroke.f32(100f)
        buf3Stroke.f32(200f)
        buf3Stroke.f32(0.8f)
        assertEquals(30L, ReplayTimeline.effectiveTotalMs(buf3Stroke.data.copyOf(buf3Stroke.size), 2, 1000L, 2))
    }
}
