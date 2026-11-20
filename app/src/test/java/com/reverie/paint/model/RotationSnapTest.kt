/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RotationSnapTest {

    @Test
    fun `nearestMultiple lands on each quarter turn`() {
        assertEquals(0f, RotationSnap.nearestMultiple(0f), 1e-4f)
        assertEquals(90f, RotationSnap.nearestMultiple(87f), 1e-4f)
        assertEquals(180f, RotationSnap.nearestMultiple(184f), 1e-4f)
        assertEquals(270f, RotationSnap.nearestMultiple(268f), 1e-4f)
        assertEquals(360f, RotationSnap.nearestMultiple(358f), 1e-4f)
    }

    @Test
    fun `nearestMultiple handles negative and wrapped accumulators`() {
        assertEquals(-90f, RotationSnap.nearestMultiple(-88f), 1e-4f)
        assertEquals(0f, RotationSnap.nearestMultiple(-2f), 1e-4f)
        assertEquals(720f, RotationSnap.nearestMultiple(718f), 1e-4f)
        assertEquals(-360f, RotationSnap.nearestMultiple(-362f), 1e-4f)
    }

    @Test
    fun `disabled threshold returns raw angle untouched`() {
        assertEquals(87.3f, RotationSnap.apply(87.3f, 0f), 0f)
        assertEquals(-44f, RotationSnap.apply(-44f, 0f), 0f)
        assertFalse(RotationSnap.isWithinThreshold(87f, 0f))
    }

    @Test
    fun `exact multiple is locked onto itself`() {
        assertEquals(90f, RotationSnap.apply(90f, 5f), 1e-4f)
        assertEquals(0f, RotationSnap.apply(0f, 5f), 1e-4f)
        assertEquals(360f, RotationSnap.apply(360f, 5f), 1e-4f)
    }

    @Test
    fun `angle inside threshold is pulled toward the multiple`() {
        // 88° 距 90° 为 -2°, 落在 ±5° 内 -> 被拉向 90°, 但不超过 90°
        val a = RotationSnap.apply(88f, 5f)
        assertTrue("expected pull toward 90, got $a", a > 88f && a < 90f)
        // 反向 (逆时针) 同理: 92° 被拉向 90°
        val b = RotationSnap.apply(92f, 5f)
        assertTrue("expected pull toward 90, got $b", b < 92f && b > 90f)
        // 越靠近倍数拽得越紧 (残差更小)
        val near = abs(90f - RotationSnap.apply(89.5f, 5f))
        val far = abs(90f - RotationSnap.apply(88f, 5f))
        assertTrue("closer angle should have smaller residual ($near vs $far)", near < far)
    }

    @Test
    fun `outside threshold equals free rotation`() {
        assertEquals(84f, RotationSnap.apply(84f, 5f), 1e-5f)
        assertEquals(96.5f, RotationSnap.apply(96.5f, 5f), 1e-5f)
        // 恰好等于阈值边界时也走自由旋转 (t == 1)
        assertEquals(95f, RotationSnap.apply(95f, 5f), 1e-5f)
    }

    @Test
    fun `crossing 0 and 360 boundary stays continuous`() {
        var prev = RotationSnap.apply(355f, 5f)
        var raw = 355f
        while (raw <= 365f) {
            val cur = RotationSnap.apply(raw, 5f)
            assertTrue("not monotonic at raw=$raw", cur >= prev - 1e-4f)
            // 斜率上界 1.25, 步长 0.05° 时单步变化不超过 0.0625°, 留余量到 0.07°
            assertTrue("jump at raw=$raw", abs(cur - prev) <= 0.07f)
            prev = cur
            raw += 0.05f
        }
        // 358° 被拉向 360°, 2° 被拉向 0° (等价角度, 各自吸附到所在圈层的倍数)
        assertTrue(RotationSnap.apply(358f, 5f) > 358f)
        assertTrue(RotationSnap.apply(2f, 5f) < 2f)
    }

    @Test
    fun `magnet is monotonic and slope bounded across a detent`() {
        val stepSize = 0.01f
        var raw = 80f
        var prev = RotationSnap.apply(raw, 5f)
        var maxSlope = 0f
        while (raw <= 100f) {
            val cur = RotationSnap.apply(raw, 5f)
            assertTrue("not monotonic at raw=$raw", cur >= prev - 1e-4f)
            maxSlope = maxOf(maxSlope, abs(cur - prev) / stepSize)
            prev = cur
            raw += stepSize
        }
        // 曲线斜率上界 1.25, 留取整余量: 不得出现飞跳
        assertTrue("slope too large: $maxSlope", maxSlope <= 1.3f)
        // 吸附区外恢复自由旋转: 斜率回到 1
        assertEquals(1f, abs(RotationSnap.apply(99.99f, 5f) - RotationSnap.apply(99.98f, 5f)) / stepSize, 0.02f)
    }

    @Test
    fun `reversing direction re-expands symmetrically through the detent`() {
        // 顺时针进入 90° 吸附区, 再逆时针退出, 数值应沿同一条曲线回落, 不残留偏移
        val clockwise = (85..95).map { RotationSnap.apply(it.toFloat(), 5f) }
        val counterClockwise = (95 downTo 85).map { RotationSnap.apply(it.toFloat(), 5f) }
        assertEquals(clockwise, counterClockwise.reversed())
    }

    @Test
    fun `snap engaged flag uses hysteresis to avoid flutter`() {
        // 进入: 必须在阈值内 (94.5 距 90 为 4.5°)
        assertTrue(RotationSnap.isSnapEngaged(94.5f, 5f, prevEngaged = false))
        assertFalse(RotationSnap.isSnapEngaged(95.2f, 5f, prevEngaged = false))
        // 退出: 放宽到 1.15 倍 (5.75°), 阈值边缘不抖动
        assertTrue(RotationSnap.isSnapEngaged(95.2f, 5f, prevEngaged = true))
        assertFalse(RotationSnap.isSnapEngaged(95.8f, 5f, prevEngaged = true))
        // 关闭吸附时永不判定为进入
        assertFalse(RotationSnap.isSnapEngaged(90f, 0f, prevEngaged = true))
    }

    @Test
    fun `pivot compensation with snapped delta keeps finger point pinned`() {
        val viewW = 1080f
        val viewH = 2400f
        val canvasPanX = 50f
        val canvasPanY = -30f
        val canvasZoom = 1.2f
        val canvasRotation = 89f
        // 原始角一帧内前进 3° -> 92° (越过 90° 后仍在吸附区内, 磁性曲线把运动放缓)
        val rawRotation = canvasRotation + 3f
        val snapped = RotationSnap.apply(rawRotation, 5f)
        assertTrue("snap should slow the motion ($snapped vs $rawRotation)", snapped < rawRotation)
        assertTrue("snapped must stay inside the detent", abs(90f - snapped) < abs(90f - rawRotation))

        val k = 1.15f
        val prevCentroid = Offset(400f, 600f)
        val dPan = Offset(12f, -8f)
        val newCentroid = prevCentroid + dPan

        val rad = Math.toRadians((snapped - canvasRotation).toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()

        val vx = prevCentroid.x - (viewW / 2f + canvasPanX)
        val vy = prevCentroid.y - (viewH / 2f + canvasPanY)

        val targetZoom = (canvasZoom * k).coerceIn(0.02f, 128f)
        val actualK = if (canvasZoom > 0.0001f) targetZoom / canvasZoom else 1f
        val vRotX = actualK * (vx * cosR - vy * sinR)
        val vRotY = actualK * (vx * sinR + vy * cosR)

        val newPanX = newCentroid.x - vRotX - viewW / 2f
        val newPanY = newCentroid.y - vRotY - viewH / 2f

        val newCenter = Offset(viewW / 2f + newPanX, viewH / 2f + newPanY)
        val actualV = newCentroid - newCenter
        assertEquals(vRotX, actualV.x, 1e-4f)
        assertEquals(vRotY, actualV.y, 1e-4f)
        // 吸附只影响旋转角, 缩放比例保持不变
        assertEquals(canvasZoom * k, targetZoom, 1e-4f)
    }

    @Test
    fun `threshold above half step stays continuous at the midpoint`() {
        // |Δ| 最大只有 45°(step/2); 阈值给到 60° 时若不做夹取, 45° 中点处会出现约 1.4° 的跳变
        val stepSize = 0.01f
        var raw = 40f
        var prev = RotationSnap.apply(raw, 60f)
        var maxJump = 0f
        while (raw <= 50f) {
            val cur = RotationSnap.apply(raw, 60f)
            assertTrue("not monotonic at raw=$raw", cur >= prev - 1e-4f)
            maxJump = maxOf(maxJump, abs(cur - prev))
            prev = cur
            raw += stepSize
        }
        // 单步 0.01° 时斜率上界 1.25 ⇒ 变化不超过 0.0125°, 留余量到 0.02°
        assertTrue("discontinuity detected: $maxJump", maxJump <= 0.02f)

        // 阈值 <= step/2 的既有配置行为不变
        assertEquals(88.432f, RotationSnap.apply(88f, 5f), 0.01f)
        assertEquals(84f, RotationSnap.apply(84f, 5f), 1e-5f)
    }

    @Test
    fun `non finite inputs never throw and never snap`() {
        // Float.roundToInt() 对 NaN 会抛异常, 而该函数位于 HUD 每帧渲染路径上
        assertTrue(RotationSnap.nearestMultiple(Float.NaN).isNaN())
        assertEquals(87f, RotationSnap.nearestMultiple(87f, Float.NaN), 0f)
        assertEquals(87f, RotationSnap.nearestMultiple(87f, 0f), 0f)

        // 非有限阈值/步长: 不做吸附, 原样返回
        assertEquals(87f, RotationSnap.apply(87f, Float.NaN), 0f)
        assertEquals(87f, RotationSnap.apply(87f, 5f, Float.NaN), 0f)
        assertEquals(87f, RotationSnap.apply(87f, Float.POSITIVE_INFINITY), 0f)

        // 非有限原始角: 判定一律保守 (不吸附 / 不收敛 / 不触发反馈)
        assertFalse(RotationSnap.isWithinThreshold(Float.NaN, 5f))
        assertFalse(RotationSnap.isWithinThreshold(90f, Float.NaN))
        assertFalse(RotationSnap.isSnapEngaged(Float.NaN, 5f, prevEngaged = true))
        assertFalse(RotationSnap.isSnapEngaged(90f, Float.NaN, prevEngaged = false))
    }

    @Test
    fun `hold to rotate crossing branch cut accumulates smooth continuous angle`() {
        // 模拟触控点跨越 -X 轴 (atan2 从 179° 到 -179°)
        val prevAngle = 179f
        val currentAngle = -179f
        var diff = (currentAngle - prevAngle) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f

        // 逆时针微动 2 度，不应产生 -358 度的剧烈翻转
        assertEquals(2f, diff, 1e-4f)

        // 顺时针同理：从 -179° 到 179°
        var reverseDiff = (prevAngle - currentAngle) % 360f
        if (reverseDiff > 180f) reverseDiff -= 360f
        if (reverseDiff < -180f) reverseDiff += 360f
        assertEquals(-2f, reverseDiff, 1e-4f)
    }

    @Test
    fun `cardinal soft snap within 2 degrees snaps to cardinal angle`() {
        val testAngles = listOf(
            0f to 0f,
            1.8f to 0f,
            -1.8f to 0f,
            2.1f to 2.1f, // 超过 2° 不吸附
            88.5f to 90f,
            91.5f to 90f,
            93.0f to 93f, // 超过 2° 不吸附
            179.0f to 180f,
            181.5f to 180f,
            269.0f to 270f,
            271.9f to 270f,
        )
        for ((raw, expected) in testAngles) {
            val target = RotationSnap.nearestMultiple(raw)
            val delta = RotationSnap.shortestDelta(raw, target)
            val finalRot = if (abs(delta) <= 2f) target else raw
            assertEquals("Angle $raw snap mismatch", expected, finalRot, 1e-4f)
        }
    }
}
