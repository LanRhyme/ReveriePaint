/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Wave kinematics, SDF geometry and OKLab palette derivation inspired by
 * Libraries.dev Distortion waves (MIT) and dsh-Go DistortionWaveRoute.
 */

package com.reverie.paint.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 逻辑 dp 或像素空间下的 2D 坐标点 (纯模型层, 无 Android/Compose 依赖)。 */
data class WaveOffset(
    val x: Float,
    val y: Float,
)

/** 逻辑 dp 或像素空间下的尺寸。 */
data class WaveSize(
    val width: Float,
    val height: Float,
) {
    val isValid: Boolean get() = width >= 1f && height >= 1f
}

/** 逻辑 dp 或像素空间下的矩形区域 (用于锚定画廊作品卡片)。 */
data class WaveRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = left + width * 0.5f
    val centerY: Float get() = top + height * 0.5f
    val isValid: Boolean get() = width >= 1f && height >= 1f

    fun scaleBy(factor: Float): WaveRect =
        WaveRect(left * factor, top * factor, right * factor, bottom * factor)
}

/** 0..1 区间 sRGB 颜色。 */
data class WaveRgb(
    val r: Float,
    val g: Float,
    val b: Float,
)

/** CSS cubic-bezier(x1, y1, x2, y2) 同款缓动曲线。 */
data class DistortionWaveEasing(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) {
    operator fun invoke(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        repeat(24) {
            val mid = (low + high) * 0.5f
            if (bezier(mid, x1, x2) < x) {
                low = mid
            } else {
                high = mid
            }
        }
        return bezier((low + high) * 0.5f, y1, y2)
    }

    private fun bezier(s: Float, a: Float, b: Float): Float {
        val r = 1f - s
        return 3f * r * r * s * a + 3f * r * s * s * b + s * s * s
    }

    companion object {
        val FastOutVelvetStop = DistortionWaveEasing(0.22f, 1.0f, 0.36f, 1.0f)
        val SmoothInOut = DistortionWaveEasing(0.42f, 0.0f, 0.58f, 1.0f)
    }
}

/**
 * 莫兰迪主题派生的 OKLab 波前/边缘配色。
 *
 * [colors] 为最多 8 档邻近色相的柔和过渡色, [ahead] 为波前较亮前缘, [behind] 为较深尾缘。
 */
data class DistortionWavePalette(
    val colors: List<WaveRgb>,
    val ahead: WaveRgb,
    val behind: WaveRgb,
) {
    /**
     * 打包为 41 个 float 供 AGSL uniform 直接上传:
     * `[0..31]` = 8 × OKLab vec4 (padded 0), `[32..35]` = ahead, `[36..39]` = behind, `[40]` = 有效颜色数。
     */
    fun packOklab(): FloatArray {
        val src = if (colors.isEmpty()) listOf(ahead) else colors.take(8)
        val out = FloatArray(41)
        for (k in 0 until 8) {
            val lab = waveSrgbToOklab(src[k % src.size])
            out[k * 4] = lab[0]
            out[k * 4 + 1] = lab[1]
            out[k * 4 + 2] = lab[2]
            out[k * 4 + 3] = 0f
        }
        val aheadLab = waveSrgbToOklab(ahead)
        out[32] = aheadLab[0]
        out[33] = aheadLab[1]
        out[34] = aheadLab[2]
        val behindLab = waveSrgbToOklab(behind)
        out[36] = behindLab[0]
        out[37] = behindLab[1]
        out[38] = behindLab[2]
        out[40] = src.size.toFloat()
        return out
    }

    companion object {
        /**
         * 跟随莫兰迪主题强调色在 HSL/OKLab 空间派生和谐柔和色带:
         * 灰调主题保留一缕低饱和雾蓝/雾暖微光, 高饱和自定义色自动收柔。
         */
        fun fromMorandiAccent(accent: WaveRgb, isDark: Boolean): DistortionWavePalette {
            val hsl = rgbToHsl(accent.r, accent.g, accent.b)
            val hue = hsl[0]
            val sat = (hsl[1] * 0.62f).coerceIn(0.24f, 0.48f)
            val l = if (isDark) 0.68f else 0.62f
            fun tone(shift: Float, s: Float, lightness: Float): WaveRgb {
                val h = ((hue + shift) % 360f + 360f) % 360f
                return hslToRgb(h, s.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
            }
            return DistortionWavePalette(
                colors = listOf(
                    tone(-30f, sat * 0.90f, l + 0.06f),
                    tone(14f, sat, l - 0.04f),
                    tone(-8f, sat * 1.08f, l - 0.07f),
                    tone(32f, sat * 0.85f, l + 0.05f),
                    tone(-18f, sat, l),
                    tone(6f, sat * 0.90f, l + 0.08f),
                    tone(24f, sat * 1.04f, l - 0.03f),
                ),
                ahead = tone(-22f, sat * 0.78f, l + 0.13f),
                behind = tone(20f, sat * 1.05f, l - 0.12f),
            )
        }
    }
}

/** sRGB (0..1) -> OKLab [L, a, b]。 */
fun waveSrgbToOklab(c: WaveRgb): FloatArray {
    fun linear(v: Float): Double {
        val x = v.coerceIn(0f, 1f).toDouble()
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    val lr = linear(c.r)
    val lg = linear(c.g)
    val lb = linear(c.b)
    val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
    val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
    val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
    return floatArrayOf(
        (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat(),
        (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat(),
        (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat(),
    )
}

private fun rgbToHsl(rIn: Float, gIn: Float, bIn: Float): FloatArray {
    val r = rIn.coerceIn(0f, 1f)
    val g = gIn.coerceIn(0f, 1f)
    val b = bIn.coerceIn(0f, 1f)
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) * 0.5f
    val d = maxC - minC
    if (d < 1e-5f) return floatArrayOf(220f, 0f, l)
    val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
    val hRaw = when (maxC) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return floatArrayOf((hRaw * 60f) % 360f, s, l)
}

private fun hslToRgb(hDeg: Float, s: Float, l: Float): WaveRgb {
    if (s <= 1e-5f) return WaveRgb(l, l, l)
    val h = ((hDeg % 360f) + 360f) % 360f / 360f
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun hue2rgb(tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return WaveRgb(
        hue2rgb(h + 1f / 3f).coerceIn(0f, 1f),
        hue2rgb(h).coerceIn(0f, 1f),
        hue2rgb(h - 1f / 3f).coerceIn(0f, 1f),
    )
}

/**
 * 工作室轻快扭曲波风格参数 (长度单位: dp, 时间单位: 秒)。
 */
data class DistortionWaveStyle(
    val anticipation: Float = 0.22f,
    val travel: Float = 1.18f,
    val launchGap: Float = 0f,
    val linger: Float = -0.14f,
    val rimFade: Float = 1.32f,
    val rimEasing: DistortionWaveEasing = DistortionWaveEasing.SmoothInOut,
    val returnDuration: Float = 0.50f,
    val easing: DistortionWaveEasing = DistortionWaveEasing.FastOutVelvetStop,
    val pull: Float = 16f,
    val inhale: Float = 42f,
    val gatherGlow: Float = 0.54f,
    val gatherDots: Float = 0.94f,
    val amplitude: Float = 30f,
    val lensWidth: Float = 144f,
    val aberration: Float = 0.38f,
    val swell: Float = 20f,
    val blur: Float = 14f,
    val nearBlur: Float = 5.5f,
    val blurAhead: Float = 72f,
    val blurBehind: Float = 190f,
    val lead: Float = 108f,
    val tail: Float = 244f,
    val strength: Float = 0.88f,
    val rimWidth: Float = 15f,
    val rimUnevenness: Float = 0.95f,
    val rimStrength: Float = 0.86f,
    val rimSoftness: Float = 260f,
    val cornerRadius: Float = 36f,
    val rimPulse: Float = 0.65f,
    val hairlineWidth: Float = 1.8f,
    val hairlineOpacity: Float = 0.92f,
    val hairlineWhite: Float = 0.95f,
    val hairlineLinger: Float = 0.16f,
    val hairlineFade: Float = 1.24f,
    val particleCount: Int = 64,
    val particleSize: Float = 2.2f,
    val particleReach: Float = 110f,
    val particleFlight: Float = 26f,
    val particleGap: Float = 14f,
    val particleLife: Float = 1.22f,
    val particleSpread: Float = 0.92f,
) {
    /** 放波后到所有视觉余韵完全淡出的总时长 (秒)。 */
    val postLaunchDuration: Float
        get() = max(
            max(travel * 0.72f, linger + rimFade),
            max(linger + hairlineLinger + hairlineFade, particleLife),
        ).coerceAtLeast(0.25f)

    companion object {
        /** 标准档 (UiAnimationSpeed.NORMAL): 轻快工作室节奏 + 完整四层质感。 */
        val StudioNormal = DistortionWaveStyle()

        /** 极速档 (UiAnimationSpeed.FAST): 节奏收紧约一半, 干脆利落。 */
        val StudioFast = DistortionWaveStyle(
            anticipation = 0.10f,
            travel = 0.58f,
            linger = -0.07f,
            rimFade = 0.64f,
            returnDuration = 0.26f,
            hairlineLinger = 0.08f,
            hairlineFade = 0.60f,
            particleLife = 0.60f,
            particleCount = 42,
        )

        fun forSpeed(speed: UiAnimationSpeed, cornerRadiusDp: Float = 36f): DistortionWaveStyle {
            val base = when (speed) {
                UiAnimationSpeed.FAST -> StudioFast
                else -> StudioNormal
            }
            return base.copy(cornerRadius = cornerRadiusDp.coerceIn(12f, 56f))
        }
    }
}

/** 缺省卡片锚点: 屏幕正中 64dp × 64dp 区域 (无卡片来源时的回退)。 */
fun defaultWaveSourceRect(viewport: WaveSize): WaveRect {
    val half = min(32f, min(viewport.width, viewport.height) * 0.15f).coerceAtLeast(8f)
    val cx = viewport.width * 0.5f
    val cy = viewport.height * 0.5f
    return WaveRect(cx - half, cy - half, cx + half, cy + half)
}

/**
 * 某一帧扭曲波的完整几何与光学状态 (纯函数求值, 无副作用)。
 *
 * 波前采用「卡片圆角矩形 -> 有机波动圆环」平滑变形有向距离场 (SDF):
 * 蓄力期紧密贴合被点击作品卡片的真实轮廓, 卡片内部保持清晰作视觉锚点;
 * 放波后 `boxHalf` 随 `opening` 收敛归零、半径向外荡开, 将整屏画面卷入画布。
 */
data class WaveFront(
    val originX: Float,
    val originY: Float,
    val boxHalfX: Float,
    val boxHalfY: Float,
    val radius: Float,
    val cardCenterX: Float,
    val cardCenterY: Float,
    val cardHalfX: Float,
    val cardHalfY: Float,
    val cardCornerRadius: Float,
    val moved: Float,
    val time: Float,
    val energy: Float,
    val swell: Float,
    val gravity: Float,
    val holdRelease: Float,
    val glow: Float,
    val clearRadius: Float,
    val hairClearRadius: Float,
    val rim: Float,
    val sparkle: Float,
    val flying: Float,
    val launched: Boolean,
    val hasCleared: Boolean,
    val reversing: Boolean,
    val outgoingCoverMul: Float,
) {
    companion object {
        /** 两个页面交叉融合的半宽 (dp), 与 AGSL 中 coverZone 严格一致。 */
        const val COVER_ZONE_DP = 56f

        /** 波前越过最远角后的缓冲余量 (dp), 让缓动收尾自然且提前关闭离场层。 */
        private const val OVERSHOOT_DP = 92f

        /** 放波后引力松手时长 (秒)。 */
        private const val GRAVITY_RELEASE_SEC = 0.20f

        /** 蓄力结束时卡片边缘微光的封顶倍率。 */
        private const val GATHERED_GLOW_CAP = 0.55f

        /** 折射鼓包与模糊自然衰减的区间 (相对 travel)。 */
        private const val SETTLE_START = 0.24f
        private const val SETTLE_END = 0.62f

        fun smooth(a: Float, b: Float, x: Float): Float {
            val denom = b - a
            if (abs(denom) < 1e-6f) return if (x >= b) 1f else 0f
            val t = ((x - a) / denom).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun resolveSource(source: WaveRect?, size: WaveSize): WaveRect {
            val valid = source?.takeIf { it.isValid } ?: return defaultWaveSourceRect(size)
            val clampedL = valid.left.coerceIn(0f, (size.width - 2f).coerceAtLeast(0f))
            val clampedT = valid.top.coerceIn(0f, (size.height - 2f).coerceAtLeast(0f))
            val clampedR = valid.right.coerceIn(clampedL + 2f, size.width.coerceAtLeast(clampedL + 2f))
            val clampedB = valid.bottom.coerceIn(clampedT + 2f, size.height.coerceAtLeast(clampedT + 2f))
            return WaveRect(clampedL, clampedT, clampedR, clampedB)
        }

        private fun computeCardGeometry(
            rect: WaveRect,
            size: WaveSize,
            style: DistortionWaveStyle,
            focus: WaveOffset?,
        ): CardGeom {
            val cornerR = min(10f, min(rect.width, rect.height) * 0.5f).coerceAtLeast(2f)
            val halfX = (rect.width * 0.5f - cornerR).coerceAtLeast(0f)
            val halfY = (rect.height * 0.5f - cornerR).coerceAtLeast(0f)
            val cx = rect.centerX
            val cy = rect.centerY
            val shiftX = if (focus != null) {
                (focus.x - cx).coerceIn(-rect.width * 0.5f, rect.width * 0.5f) * 0.30f
            } else {
                0f
            }
            val shiftY = if (focus != null) {
                (focus.y - cy).coerceIn(-rect.height * 0.5f, rect.height * 0.5f) * 0.30f
            } else {
                0f
            }
            val ox = cx + shiftX
            val oy = cy + shiftY
            var reach = 0f
            val corners = arrayOf(
                0f to 0f,
                size.width to 0f,
                0f to size.height,
                size.width to size.height,
            )
            for ((kx, ky) in corners) {
                reach = max(reach, hypot(kx - ox, ky - oy))
            }
            val launchRadius = cornerR + style.launchGap.coerceAtLeast(0f)
            val crossed = reach + style.swell + COVER_ZONE_DP
            return CardGeom(
                cx = cx,
                cy = cy,
                halfX = halfX,
                halfY = halfY,
                cornerR = cornerR,
                shiftX = shiftX,
                shiftY = shiftY,
                reach = reach,
                launchRadius = launchRadius,
                crossed = crossed,
            )
        }

        /**
         * 阶段 1 (蓄力等待首帧): `elapsedSec` 从 0 递增。
         * 若加载耗时超过 `style.anticipation`, 自动无缝进入轻柔呼吸维持态, 等待首帧就绪。
         */
        fun gathering(
            elapsedSec: Float,
            source: WaveRect?,
            size: WaveSize,
            style: DistortionWaveStyle,
            focus: WaveOffset? = null,
        ): WaveFront {
            val safeSize = WaveSize(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            val rect = resolveSource(source, safeSize)
            val g = computeCardGeometry(rect, safeSize, style, focus)
            val t = elapsedSec.coerceAtLeast(0f)
            val ant = style.anticipation.coerceAtLeast(0.001f)
            val gathered = smooth(0f, ant, t)
            val overtime = (t - ant).coerceAtLeast(0f)
            // 超出最短蓄力时间后柔和呼吸 (sin(0) == 0, 保证在 t == ant 处严格连续)
            val breathWave = if (overtime > 0f) sin(overtime * 3.4f) else 0f
            val breathGlow = 1f + 0.14f * breathWave
            val breathPull = 1f + 0.05f * breathWave
            val breathInhale = if (overtime > 0f) 3.0f * (0.5f - 0.5f * cos(overtime * 3.4f)) else 0f

            val gatheredGlow = min(GATHERED_GLOW_CAP * style.gatherGlow.coerceAtLeast(0f), 1f)
            val clearStart = g.launchRadius - style.rimSoftness

            return WaveFront(
                originX = g.cx,
                originY = g.cy,
                boxHalfX = g.halfX,
                boxHalfY = g.halfY,
                radius = g.launchRadius + style.inhale * (1f - gathered) + breathInhale,
                cardCenterX = g.cx,
                cardCenterY = g.cy,
                cardHalfX = g.halfX,
                cardHalfY = g.halfY,
                cardCornerRadius = g.cornerR,
                moved = 0f,
                time = t,
                energy = 0f,
                swell = 0f,
                gravity = style.pull * gathered * breathPull,
                holdRelease = 0f,
                glow = (gatheredGlow * gathered * breathGlow).coerceIn(0f, 1f),
                clearRadius = clearStart,
                hairClearRadius = clearStart,
                rim = 0f,
                sparkle = smooth(0f, ant * 0.35f, t),
                flying = 0f,
                launched = false,
                hasCleared = false,
                reversing = false,
                outgoingCoverMul = 1f,
            )
        }

        /**
         * 阶段 2 (首帧就绪放波): `sinceLaunchSec` 从 0 推进至 `style.postLaunchDuration`。
         * `launchTimeSec` 为蓄力结束时刻, 保证着色器 `time` 严格连续无跳变。
         */
        fun sweeping(
            sinceLaunchSec: Float,
            launchTimeSec: Float,
            source: WaveRect?,
            size: WaveSize,
            style: DistortionWaveStyle,
            focus: WaveOffset? = null,
        ): WaveFront {
            val safeSize = WaveSize(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            val rect = resolveSource(source, safeSize)
            val g = computeCardGeometry(rect, safeSize, style, focus)
            val dt = sinceLaunchSec.coerceAtLeast(0f)
            val time = launchTimeSec.coerceAtLeast(0f) + dt
            val x = (dt / style.travel.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            val moved = style.easing(x)
            val opening = smooth(0f, 0.45f, moved)
            val settling = 1f - smooth(SETTLE_START, SETTLE_END, x)

            val radius = g.launchRadius + (g.crossed + OVERSHOOT_DP - g.launchRadius) * moved
            val gatheredGlow = min(GATHERED_GLOW_CAP * style.gatherGlow.coerceAtLeast(0f), 1f)
            val clearStart = g.launchRadius - style.rimSoftness
            val clearEnd = g.reach + style.rimSoftness + style.swell

            val rimFrac = ((dt - style.linger) / style.rimFade.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
            val hairFrac = ((dt - style.linger - style.hairlineLinger) / style.hairlineFade.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
            val totalFrac = (dt / style.postLaunchDuration).coerceIn(0f, 1f)

            return WaveFront(
                originX = g.cx + g.shiftX * opening,
                originY = g.cy + g.shiftY * opening,
                boxHalfX = g.halfX * (1f - opening),
                boxHalfY = g.halfY * (1f - opening),
                radius = radius,
                cardCenterX = g.cx,
                cardCenterY = g.cy,
                cardHalfX = g.halfX,
                cardHalfY = g.halfY,
                cardCornerRadius = g.cornerR,
                moved = moved,
                time = time,
                energy = smooth(0f, 0.06f, x) * settling,
                swell = style.swell * smooth(0f, 0.30f, moved),
                gravity = style.pull * (1f - smooth(0f, GRAVITY_RELEASE_SEC, dt)),
                holdRelease = smooth(0f, 0.14f, x),
                glow = (gatheredGlow + (1f - gatheredGlow) * smooth(0f, 0.08f, x)) * settling,
                clearRadius = clearStart + (clearEnd - clearStart) * style.rimEasing(rimFrac),
                hairClearRadius = clearStart + (clearEnd - clearStart) * style.rimEasing(hairFrac),
                rim = style.rimStrength * smooth(0f, 0.10f, x) * (1f - smooth(0.86f, 1f, totalFrac)),
                sparkle = 1f,
                flying = dt,
                launched = true,
                hasCleared = radius > g.crossed,
                reversing = false,
                outgoingCoverMul = 1f,
            )
        }

        /**
         * 阶段 3 (从画布返回画廊的对称收拢): `progress01` 从 0 (满屏画布) 推进至 1 (收拢回卡片)。
         */
        fun returning(
            progress01: Float,
            source: WaveRect?,
            size: WaveSize,
            style: DistortionWaveStyle,
        ): WaveFront {
            val safeSize = WaveSize(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            val rect = resolveSource(source, safeSize)
            val g = computeCardGeometry(rect, safeSize, style, focus = null)
            val p = progress01.coerceIn(0f, 1f)
            val collapsed = style.easing(p)
            val remaining = (1f - collapsed).coerceIn(0f, 1f)
            val opening = smooth(0f, 0.45f, remaining)
            val time = p * style.returnDuration

            val targetRadius = g.launchRadius * 0.45f
            val radius = targetRadius + (g.crossed - targetRadius) * remaining
            val envelope = smooth(0f, 0.10f, p) * (1f - smooth(0.74f, 1f, p))
            val rimEnv = style.rimStrength * 0.65f * smooth(0f, 0.08f, p) * (1f - smooth(0.24f, 0.68f, p))
            val clearFar = g.reach + style.rimSoftness + style.swell

            return WaveFront(
                originX = g.cx,
                originY = g.cy,
                boxHalfX = g.halfX * (1f - opening),
                boxHalfY = g.halfY * (1f - opening),
                radius = radius,
                cardCenterX = g.cx,
                cardCenterY = g.cy,
                cardHalfX = g.halfX,
                cardHalfY = g.halfY,
                cardCornerRadius = g.cornerR,
                moved = remaining,
                time = time,
                energy = envelope * 0.90f,
                swell = style.swell * 0.78f * smooth(0f, 0.35f, remaining),
                gravity = 0f,
                holdRelease = 1f,
                glow = envelope * 0.85f,
                clearRadius = -clearFar,
                hairClearRadius = -clearFar,
                rim = rimEnv,
                sparkle = envelope,
                flying = time,
                launched = true,
                hasCleared = p >= 1f,
                reversing = true,
                outgoingCoverMul = 1f - smooth(0.72f, 1f, p),
            )
        }

        private data class CardGeom(
            val cx: Float,
            val cy: Float,
            val halfX: Float,
            val halfY: Float,
            val cornerR: Float,
            val shiftX: Float,
            val shiftY: Float,
            val reach: Float,
            val launchRadius: Float,
            val crossed: Float,
        )
    }
}

/**
 * 屏幕边缘流光的 7 个有机漂移呼吸团块 (每帧一次计算, 写入复用 FloatArray 零分配)。
 */
object WaveRim {
    const val COUNT = 7

    /** `out` 长度至少为 `COUNT * 3` ([center, length, depth] × 7)。 */
    fun computeLumps(
        out: FloatArray,
        time: Float,
        size: WaveSize,
        cornerRadius: Float,
        pulse: Float,
    ) {
        val ex = max(size.width * 0.5f - cornerRadius, 0f)
        val ey = max(size.height * 0.5f - cornerRadius, 0f)
        val perimeter = max(4f * (ex + ey + 0.5f * PI.toFloat() * cornerRadius), 1f)
        for (i in 0 until COUNT) {
            val fi = i.toFloat()
            val a = hash(fi, 12.9898f, 1.3f)
            val b = hash(fi, 78.233f, 4.1f)
            val c = hash(fi, 39.425f, 2.7f)
            val breath = (0.25f + 0.30f * pulse) * sin(time * (1.1f + 1.2f * a) * (1f + pulse) + fi * 2.4f)
            out[i * 3] = perimeter * (fi + 0.35f * a) / COUNT + time * (-22f + 44f * b) * (1f + 2f * pulse)
            out[i * 3 + 1] = max(perimeter / COUNT * (0.20f + 0.25f * c), 4f)
            out[i * 3 + 2] = (0.20f + 2.60f * b) * (1f + breath)
        }
    }

    private fun hash(i: Float, a: Float, b: Float): Float {
        val x = sin((i * a + b).toDouble()) * 43758.5453
        return (x - floor(x)).toFloat()
    }
}

/**
 * 围绕卡片边界跃动汇聚并在放波后随波前散射闪烁的白色微粒。
 */
class WaveParticle(
    index: Int,
    style: DistortionWaveStyle,
) {
    private val idx = (index + 1).toFloat()
    private val heading: Float
    private val pace: Float
    private val drift: Float
    private val sway: Float
    private val swayRate: Float
    private val twinkleRate: Float
    private val phase: Float
    private val life: Float
    val sizeDp: Float
    private val brightness: Float
    private val hop: Float
    private val hopOffset: Float
    private val reach: Float
    private val flight: Float
    private val gap: Float
    private val gatherDots: Float
    private val spread: Float

    init {
        fun scatter(step: Double): Float {
            val v = (idx.toDouble() * step) % 1.0
            return if (v < 0.0) (v + 1.0).toFloat() else v.toFloat()
        }
        val small = scatter(0.5497004779)
        heading = scatter(0.7548776662) * (2f * PI.toFloat())
        pace = 0.35f + 0.72f * sqrt(scatter(0.8191725134))
        drift = 14f + 46f * scatter(0.6710436067)
        sway = 3f + 10f * scatter(0.4142135624)
        swayRate = 2.5f + 3.5f * scatter(0.7320508076)
        twinkleRate = 8f + 12f * scatter(0.2360679775)
        phase = scatter(0.6180339887) * (2f * PI.toFloat())
        life = style.particleLife * (0.42f + 0.58f * scatter(0.6457513111))
        sizeDp = style.particleSize * (0.55f + 0.45f * small * small)
        brightness = 0.32f + 0.68f * scatter(0.6055512755)
        hop = 0.17f + 0.14f * scatter(0.1231056256)
        hopOffset = scatter(0.4721359550)
        reach = style.particleReach
        flight = style.particleFlight
        gap = style.particleGap
        gatherDots = style.gatherDots
        spread = style.particleSpread.coerceIn(0f, 1f)
    }

    /** 将本帧粒子逻辑坐标写入 [outXY] (`[0]=xDp, [1]=yDp`), 零对象分配。 */
    fun positionInto(front: WaveFront, outXY: FloatArray) {
        if (!front.launched) {
            val hops = front.time / hop + hopOffset
            val count = floor(hops)
            val along = hops - count
            val angle = hash(idx, count, 1f) * (2f * PI.toFloat())
            val dirX = cos(angle)
            val dirY = sin(angle)
            // 从卡片轮廓外圈向卡片边界加速跃入
            val cardBorder = cardContourRadius(dirX, dirY, front.cardHalfX, front.cardHalfY, front.cardCornerRadius)
            val nearest = cardBorder + gap + flight
            val away = nearest + max(reach - gap - flight, 0f) * hash(idx, count, 2f)
            val dist = away - flight * along * along
            outXY[0] = front.cardCenterX + dirX * dist
            outXY[1] = front.cardCenterY + dirY * dist
            return
        }

        val dirX = cos(heading)
        val dirY = sin(heading)
        val edge = cardContourRadius(dirX, dirY, front.boxHalfX, front.boxHalfY, front.cardCornerRadius)
        val travelDist = if (front.reversing) {
            edge + (front.radius - front.cardCornerRadius).coerceAtLeast(0f) * pace * spread
        } else {
            edge + ((front.radius - front.cardCornerRadius).coerceAtLeast(0f) * pace + drift * front.flying) * spread
        }
        val swaying = sway * sin(swayRate * front.flying + phase) * min(front.flying / 0.22f, 1f)
        outXY[0] = front.originX + dirX * travelDist - dirY * swaying
        outXY[1] = front.originY + dirY * travelDist + dirX * swaying
    }

    fun opacity(front: WaveFront): Float {
        val twinkle = 0.5f + 0.5f * sin(front.time * twinkleRate + phase)
        val glint = brightness * (0.70f + 0.30f * twinkle)
        if (!front.launched) {
            val hops = front.time / hop + hopOffset
            val along = hops - floor(hops)
            val hopping = WaveFront.smooth(0f, 0.25f, along) * (1f - WaveFront.smooth(0.55f, 1f, along))
            return (front.sparkle * hopping * glint * gatherDots).coerceIn(0f, 1f)
        }
        if (front.reversing) {
            return (front.sparkle * glint * 0.80f).coerceIn(0f, 1f)
        }
        val burning = 1f - WaveFront.smooth(life * 0.52f, life, front.flying)
        return (burning * glint).coerceIn(0f, 1f)
    }

    private fun cardContourRadius(
        dirX: Float,
        dirY: Float,
        halfX: Float,
        halfY: Float,
        cornerR: Float,
    ): Float {
        // 射线与卡片圆角矩形近似距离: 让粒子从卡片四周边框均匀汇聚/发散
        val tx = if (abs(dirX) > 1e-3f) halfX / abs(dirX) else Float.POSITIVE_INFINITY
        val ty = if (abs(dirY) > 1e-3f) halfY / abs(dirY) else Float.POSITIVE_INFINITY
        val boxDist = min(tx, ty).let { if (it.isFinite()) it else 0f }
        return boxDist + cornerR
    }

    private fun hash(i: Float, n: Float, salt: Float): Float {
        val x = sin((i * 12.9898f + n * 78.233f + salt * 37.719f).toDouble()) * 43758.5453
        return (x - floor(x)).toFloat()
    }
}
