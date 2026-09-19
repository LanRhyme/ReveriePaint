/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 3D 光影球 (Shading Sphere / Lighting Orb) 着色模型。
 *
 * ## 设计哲学与色彩体系
 * 1. **冷暖光影推导哲学**：
 *    遵循画师核心原则“受光面偏暖，则背光面偏冷”：
 *    - 主光高光 (Lighting)：朝向暖锚点偏移，明度适度提升 (Tone + ~20%)，保持饱满暖色高光，杜绝惨白过曝；
 *    - 阴影暗部 (Shadow)：朝向冷锚点偏移，明度适度降低 (Tone - ~25%)，提升色彩纯度，杜绝发灰发黑。
 *
 * 2. **3D 曲面体积感与环境反光 (Bounce Light)**：
 *    基于真实 3D 球体表面法向量 N = (x, y, sqrt(1 - x^2 - y^2)) 实时计算：
 *    - 主光源 L = (0.39, -0.54, 0.746) 处于右上方仰角，产生平滑球弧高光；
 *    - 地面环境反射光源 B = (-0.55, 0.65, 0.30) 处于左下方，在背光边缘产生清晰自然的环境反光 (Bounce Light)；
 *    - 球心归一为 g = 0.500，严格等于固有色；
 *    - 明暗交界面形成深邃饱满的 Core Shadow。
 *
 * 3. **双段线性色阶 (Piecewise Color Ramp) + Overlay (叠加) 纹理合成**：
 *    - g < 0.5 时在 Shadow 与 Base 间插值，进行 2 * g * Ramp 叠加压暗；
 *    - g >= 0.5 时在 Base 与 Lighting 间插值，进行 1 - 2*(1 - g)*(1 - Ramp) 叠加提亮；
 *    - 在 g = 0.500 处数学上严格恒等于固有色 Base。
 */
object SphereShading {

    private const val MAP_SIZE = 256

    /**
     * 预烘焙 3D 漫反射与环境光遮蔽灰度纹理 (256x256 零分配快速双线性采样)。
     * 保证高光为极其自然的真实 3D 漫反射曲面包络，彻底消除任何一维/轴向切片导致的卵形与硬边。
     */
    private val ORB_GRAY: ByteArray by lazy {
        val stream = SphereShading::class.java.getResourceAsStream("/render_orb_gray.bin")
            ?: SphereShading::class.java.classLoader?.getResourceAsStream("render_orb_gray.bin")
            ?: error("render_orb_gray.bin resource not found")
        val bytes = ByteArray(MAP_SIZE * MAP_SIZE)
        stream.use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                offset += count
            }
        }
        bytes
    }

    // 自动配色的暖/冷锚点色相 (暖黄 ≈ 0.125 即 45°, 冷青蓝 ≈ 0.625 即 225°)
    private const val WARM_HUE = 0.125f
    private const val COOL_HUE = 0.625f
    private const val MAX_HUE_SHIFT = 0.097f // 最大色相偏移限制在约 35° 内, 避免偏色过度

    // ------------------------------------------------------------------ 对外 API

    /**
     * 根据固有色推算推荐的主光色与阴影色。
     * 遵循画师核心色彩原理：“受光面偏暖，则背光面偏冷”：
     * - 高光暖色推导：色相朝暖锚点偏移，提升亮度 (Tone + ~35%)，适度保持纯度，避免惨白；
     * - 暗部冷色推导：色相朝冷锚点偏移，降低亮度 (Tone * ~42%)，提高纯度，防止死黑与发灰。
     *
     * @return Pair(主光色, 阴影色), 均为 0xAARRGGBB
     */
    fun computeAutoLightingColors(baseColorInt: Int): Pair<Int, Int> {
        val h0 = hue01Of(baseColorInt)
        val s0 = sat01Of(baseColorInt)
        val l0 = lum01Of(baseColorInt)

        // 光色: 色相朝暖锚点偏移, 提亮, 适度保持纯度, 避免惨白
        val warmHue = clampHueShift(h0, WARM_HUE, factor = 0.35f, maxShift = MAX_HUE_SHIFT)
        val lightL = min(0.86f, max(0.42f, l0 + (1f - l0) * 0.35f))
        val lightS = min(0.95f, max(0.35f, s0 * 0.88f))
        val lightInt = hslToArgb(warmHue, lightS, lightL)

        // 阴影色: 色相朝冷锚点偏移, 压暗, 增强饱和度, 避免死黑发灰
        val coolHue = clampHueShift(h0, COOL_HUE, factor = 0.40f, maxShift = MAX_HUE_SHIFT)
        val shadowL = max(0.12f, min(0.38f, l0 * 0.42f))
        val shadowS = min(1f, max(0.45f, s0 * 1.15f))
        val shadowInt = hslToArgb(coolHue, shadowS, shadowL)

        return Pair(lightInt, shadowInt)
    }

    private fun clampHueShift(h0: Float, targetAnchor: Float, factor: Float, maxShift: Float): Float {
        val d = hueDelta(h0, targetAnchor)
        val shift = max(-maxShift, min(maxShift, d * factor))
        return (h0 + shift + 1f) % 1f
    }

    /**
     * 采样球面上归一化坐标 (nx, ny) 处的颜色 (手指吸色热路径)。
     *
     * 除返回的字符串外零分配: 无临时数组/对象。
     *
     * @param nx 归一化横坐标 [-1, 1], 向右为正
     * @param ny 归一化纵坐标 [-1, 1], 向下为正
     * @return 6 位十六进制颜色字符串, 形如 "RRGGBB"
     */
    fun calculateSphereColorHex(
        nx: Float,
        ny: Float,
        baseHex: String,
        shadowHex: String,
        lightHex: String,
        softness: Float
    ): String {
        val base = parseHexToArgb(baseHex)
        val shadow = parseHexToArgb(shadowHex)
        val light = parseHexToArgb(lightHex)
        return toHex6(sampleArgb(nx, ny, base, shadow, light, softness))
    }

    /**
     * 同 [calculateSphereColorHex], 但输入输出均为 0xAARRGGBB 整型 (完全零分配)。
     */
    fun sampleArgb(
        nx: Float,
        ny: Float,
        baseArgb: Int,
        shadowArgb: Int,
        lightArgb: Int,
        softness: Float
    ): Int {
        val rBase = ((baseArgb shr 16) and 0xFF) / 255f
        val gBase = ((baseArgb shr 8) and 0xFF) / 255f
        val bBase = (baseArgb and 0xFF) / 255f

        val rShadow = ((shadowArgb shr 16) and 0xFF) / 255f
        val gShadow = ((shadowArgb shr 8) and 0xFF) / 255f
        val bShadow = (shadowArgb and 0xFF) / 255f

        val rLight = ((lightArgb shr 16) and 0xFF) / 255f
        val gLight = ((lightArgb shr 8) and 0xFF) / 255f
        val bLight = (lightArgb and 0xFF) / 255f

        val gRaw = computeShadingGray(nx, ny)
        return colorizeGray(
            gRaw,
            rBase, gBase, bBase,
            rShadow, gShadow, bShadow,
            rLight, gLight, bLight,
            softness
        )
    }

    /**
     * 预计算着色剖面。整张位图渲染时先构造一次, 再逐点调用 [shadeArgb],
     * 避免每像素重复解析颜色。
     */
    class Profile(
        baseArgb: Int,
        shadowArgb: Int,
        lightArgb: Int,
        private val softness: Float
    ) {
        private val rBase = ((baseArgb shr 16) and 0xFF) / 255f
        private val gBase = ((baseArgb shr 8) and 0xFF) / 255f
        private val bBase = (baseArgb and 0xFF) / 255f

        private val rShadow = ((shadowArgb shr 16) and 0xFF) / 255f
        private val gShadow = ((shadowArgb shr 8) and 0xFF) / 255f
        private val bShadow = (shadowArgb and 0xFF) / 255f

        private val rLight = ((lightArgb shr 16) and 0xFF) / 255f
        private val gLight = ((lightArgb shr 8) and 0xFF) / 255f
        private val bLight = (lightArgb and 0xFF) / 255f

        private val hasSoftness = softness != 0.5f
        private val k = if (hasSoftness) 4.0f * exp(3.0f * (softness - 0.5f)) else 0f
        private val sig0 = if (hasSoftness) 1.0f / (1.0f + exp(0.5f * k)) else 0f
        private val invRange = if (hasSoftness) 1.0f / (1.0f - 2.0f * sig0) else 1f

        /** 采样球面上一点, 返回 0xAARRGGBB (不透明) */
        fun shadeArgb(nx: Float, ny: Float): Int {
            var g = computeShadingGray(nx, ny)
            if (hasSoftness) {
                val sig = 1.0f / (1.0f + exp(-k * (g - 0.5f)))
                g = min(1.0f, max(0.0f, (sig - sig0) * invRange))
            }

            val rRamp: Float
            val gRamp: Float
            val bRamp: Float
            if (g < 0.5f) {
                val t = 2f * g
                val invT = 1f - t
                rRamp = invT * rShadow + t * rBase
                gRamp = invT * gShadow + t * gBase
                bRamp = invT * bShadow + t * bBase
            } else {
                val t = 2f * (g - 0.5f)
                val invT = 1f - t
                rRamp = invT * rBase + t * rLight
                gRamp = invT * gBase + t * gLight
                bRamp = invT * bBase + t * bLight
            }

            val rOut: Float
            val gOut: Float
            val bOut: Float
            if (g < 0.5f) {
                val factor = 2f * g
                rOut = factor * rRamp
                gOut = factor * gRamp
                bOut = factor * bRamp
            } else {
                val factor = 2f * (1f - g)
                rOut = 1f - factor * (1f - rRamp)
                gOut = 1f - factor * (1f - gRamp)
                bOut = 1f - factor * (1f - bRamp)
            }

            val rInt = min(255, max(0, (rOut * 255f + 0.5f).toInt()))
            val gInt = min(255, max(0, (gOut * 255f + 0.5f).toInt()))
            val bInt = min(255, max(0, (bOut * 255f + 0.5f).toInt()))
            return 0xFF000000.toInt() or (rInt shl 16) or (gInt shl 8) or bInt
        }
    }

    // ------------------------------------------------------------------ 核心 3D 光照与叠加着色

    /**
     * 计算球面上 (nx, ny) 处的 3D 灰度光照标量 g ∈ [0, 1]。
     * - 球心 (0, 0) 处归一为 g = 0.500 (对应固有色基准)；
     * - 主光面朝向右上仰角产生平滑扩散高光弧面 (峰值 g ≈ 0.765)；
     * - 背光面产生明暗交界面 (Core Shadow, 约 g ≈ 0.196)；
     * - 边缘下方接收环境反光 (Bounce Light, g 回升至 ≈ 0.231)。
     */
    fun computeShadingGray(nx0: Float, ny0: Float): Float {
        var nx = nx0
        var ny = ny0
        val dist2 = nx * nx + ny * ny
        if (dist2 > 1f) {
            val inv = 1f / sqrt(dist2)
            nx *= inv
            ny *= inv
        }

        val fx = (nx * 0.5f + 0.5f) * (MAP_SIZE - 1)
        val fy = (ny * 0.5f + 0.5f) * (MAP_SIZE - 1)
        val x0 = fx.toInt().coerceIn(0, MAP_SIZE - 2)
        val y0 = fy.toInt().coerceIn(0, MAP_SIZE - 2)
        val x1 = x0 + 1
        val y1 = y0 + 1
        val wx = fx - x0
        val wy = fy - y0

        val map = ORB_GRAY
        val g00 = (map[y0 * MAP_SIZE + x0].toInt() and 0xFF) / 255f
        val g10 = (map[y0 * MAP_SIZE + x1].toInt() and 0xFF) / 255f
        val g01 = (map[y1 * MAP_SIZE + x0].toInt() and 0xFF) / 255f
        val g11 = (map[y1 * MAP_SIZE + x1].toInt() and 0xFF) / 255f

        val g = (1f - wx) * ((1f - wy) * g00 + wy * g01) + wx * ((1f - wy) * g10 + wy * g11)
        return min(1f, max(0f, g))
    }

    /**
     * 将 3D 灰度标量 g 结合三关键色执行色阶双段插值与 Overlay (叠加) 纹理合成。
     * 1. 基于 Logistic Sigmoid 的光滑连续可微对比度调制 (softness 控制明暗过渡软硬，无无穷大导数硬边)；
     * 2. 双段线性色阶 (Piecewise Color Ramp)：
     *    - g < 0.5: 在 Shadow 与 Base 间插值；
     *    - g >= 0.5: 在 Base 与 Light 间插值；
     * 3. Overlay 模式纹理合成：
     *    - g < 0.5: 2 * g * Ramp
     *    - g >= 0.5: 1 - 2 * (1 - g) * (1 - Ramp)
     *    - g = 0.500 处数学上严格恒等于固有色 Base。
     */
    fun colorizeGray(
        gRaw: Float,
        rBase: Float, gBase: Float, bBase: Float,
        rShadow: Float, gShadow: Float, bShadow: Float,
        rLight: Float, gLight: Float, bLight: Float,
        softness: Float
    ): Int {
        var g = gRaw
        // 基于标准 Logistic Sigmoid 的光滑连续可微对比度调制
        if (softness != 0.5f) {
            val k = 4.0f * exp(3.0f * (softness - 0.5f))
            val sig = 1.0f / (1.0f + exp(-k * (g - 0.5f)))
            val sig0 = 1.0f / (1.0f + exp(0.5f * k))
            val sig1 = 1.0f - sig0
            g = min(1.0f, max(0.0f, (sig - sig0) / (sig1 - sig0)))
        }

        // 1. 双段线性色阶 (Piecewise Color Ramp)
        val rRamp: Float
        val gRamp: Float
        val bRamp: Float
        if (g < 0.5f) {
            val t = 2f * g
            val invT = 1f - t
            rRamp = invT * rShadow + t * rBase
            gRamp = invT * gShadow + t * gBase
            bRamp = invT * bShadow + t * bBase
        } else {
            val t = 2f * (g - 0.5f)
            val invT = 1f - t
            rRamp = invT * rBase + t * rLight
            gRamp = invT * gBase + t * gLight
            bRamp = invT * bBase + t * bLight
        }

        // 2. Overlay (叠加) 模式纹理合成
        val rOut: Float
        val gOut: Float
        val bOut: Float
        if (g < 0.5f) {
            val factor = 2f * g
            rOut = factor * rRamp
            gOut = factor * gRamp
            bOut = factor * bRamp
        } else {
            val factor = 2f * (1f - g)
            rOut = 1f - factor * (1f - rRamp)
            gOut = 1f - factor * (1f - gRamp)
            bOut = 1f - factor * (1f - bRamp)
        }

        val rInt = min(255, max(0, (rOut * 255f + 0.5f).toInt()))
        val gInt = min(255, max(0, (gOut * 255f + 0.5f).toInt()))
        val bInt = min(255, max(0, (bOut * 255f + 0.5f).toInt()))
        return 0xFF000000.toInt() or (rInt shl 16) or (gInt shl 8) or bInt
    }

    // ------------------------------------------------------------------ 颜色工具

    /** 沿最短弧的带符号色相差 (b - a), 结果落在 [-0.5, 0.5] */
    private fun hueDelta(a: Float, b: Float): Float {
        var d = (b - a) % 1f
        if (d < 0f) d += 1f
        return if (d > 0.5f) d - 1f else d
    }

    private fun lum01Of(argb: Int): Float =
        lum01(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    private fun sat01Of(argb: Int): Float =
        sat01(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    private fun hue01Of(argb: Int): Float =
        hue01(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    private fun lum01(r: Float, g: Float, b: Float): Float =
        (max(r, max(g, b)) + min(r, min(g, b))) * 0.5f

    private fun sat01(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) * 0.5f
        val d = mx - mn
        if (d <= 1e-6f) return 0f
        return if (l < 0.5f) d / (mx + mn) else d / (2f - mx - mn)
    }

    private fun hue01(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        if (d <= 1e-6f) return 0f
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return ((h / 6f) + 1f) % 1f
    }

    private fun hslToArgb(h: Float, s: Float, l: Float): Int {
        if (s <= 1e-6f) {
            val v = (l * 255f + 0.5f).toInt()
            return 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val r = (hue2channel(p, q, h + 1f / 3f) * 255f + 0.5f).toInt()
        val g = (hue2channel(p, q, h) * 255f + 0.5f).toInt()
        val b = (hue2channel(p, q, h - 1f / 3f) * 255f + 0.5f).toInt()
        return 0xFF000000.toInt() or
            (min(255, max(0, r)) shl 16) or
            (min(255, max(0, g)) shl 8) or
            min(255, max(0, b))
    }

    private fun hue2channel(p: Float, q: Float, t0: Float): Float {
        val t = ((t0 % 1f) + 1f) % 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** 解析 "#RRGGBB" / "RRGGBB" / "#AARRGGBB", 缺省 alpha = FF */
    fun parseHexToArgb(hex: String): Int {
        var i = 0
        if (hex.isNotEmpty() && hex[0] == '#') i = 1
        val v = java.lang.Long.parseLong(hex.substring(i), 16)
        return if (hex.length - i > 6) v.toInt() else (0xFF000000.toInt() or v.toInt())
    }

    private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    private fun toHex6(argb: Int): String {
        val out = CharArray(6)
        var v = argb
        for (i in 5 downTo 0) {
            out[i] = HEX_CHARS[v and 0xF]
            v = v shr 4
        }
        return String(out)
    }
}
