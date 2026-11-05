/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushDiagnostics

/**
 * Photoshop 描述符 `BlnM` 枚举 → RP / Krita 合成 id 的唯一映射表（主笔、双重画笔、纹理三处共用）。
 *
 * 出处（本机 Krita 源码树）：
 * - `libs/image/kis_asl_layer_style_serializer.cpp::convertAndSetBlendMode`：Photoshop 描述符枚举码 → Krita 合成 id
 *   （Krita 自己导入 ASL 图层样式时用的同一套码，包括 `linearBurn`/`darkerColor`/`vividLight`/`hardMix`/`divide`…）；
 * - `libs/pigment/KoCompositeOpRegistry.h`：id 字面量。差值是 `diff`、亮度是 `luminize`、线性光是 `linear light`；
 * - 双重画笔：`KisMaskingBrushCompositeOp` 只认固定一组 id，其余会被它当成乘法；
 * - 纹理：`KisTextureOptionData.h::TexturingMode`，即 [com.reverie.paint.core.brushstore.TextureModes.NAMES]。
 *
 * 主笔路径里 id 一律原样交给 `KisPainter::setCompositeOpId`；不认识的 id 引擎会静默退回 normal，
 * 所以这里绝不产出 "difference"（图层专用别名）或 "luminosity" 这类不存在的 id。
 * 没有对应物的模式取最接近的模式并记 `abr_blend_approx`，不认识的码记 `abr_blend_unknown`，
 * 都不再静默变成 normal / multiply。
 *
 * `blendSubtraction` / `blendDivide` / `Bhnd` / `Clar` 不在 Krita 的表里，是较新版 Photoshop 的写法（推测，
 * 与样本里出现的 `Sbtr` 同义）；表里两种写法都收。
 */
internal object AbrBlendModes {
    enum class Context(val label: String) { BRUSH("brush"), DUAL("dual"), TEXTURE("texture") }

    /**
     * ABR 混合模式映射策略：
     * COMPAT_SAFE: 柔和容错模式（推荐/出墨保底）。双重画笔 linear_burn 安全回退为 burn（同 v53），避免在 Krita 蒙版 (a+b-1) 中导致严重下溢吃光墨水。
     * PRECISION: 精准映射模式（Photoshop 严格匹配）。所有模式全表精确映射（v54 行为）。
     */
    enum class Policy {
        COMPAT_SAFE,
        PRECISION,
    }

    data class Mapped(val id: String, val exact: Boolean)

    /** 归一化：去尾空格、小写（"H   " → "h"、"Clr " → "clr"） */
    private fun norm(code: String?): String = code?.trim()?.lowercase().orEmpty()

    /** 主笔：每个 Photoshop 模式都有 Krita 合成 id（Clar / Pass 除外） */
    private val BRUSH: Map<String, String> = buildMap {
        fun m(id: String, vararg codes: String) = codes.forEach { put(norm(it), id) }
        m("normal", "Nrml", "normal")
        m("dissolve", "Dslv", "dissolve")
        m("darken", "Drkn", "darken")
        m("multiply", "Mltp", "multiply")
        m("burn", "CBrn", "colorBurn")
        m("linear_burn", "linearBurn")
        m("darker color", "darkerColor")
        m("lighten", "Lghn", "lighten")
        m("screen", "Scrn", "screen")
        m("dodge", "CDdg", "colorDodge")
        m("linear_dodge", "linearDodge")
        m("lighter color", "lighterColor")
        m("overlay", "Ovrl", "overlay")
        m("soft_light", "SftL", "softLight")
        m("hard_light", "HrdL", "hardLight")
        m("vivid_light", "vividLight")
        m("linear light", "linearLight")
        m("pin_light", "pinLight")
        m("hard_mix_photoshop", "hardMix")
        m("diff", "Dfrn", "difference")
        m("exclusion", "Xclu", "exclusion")
        m("subtract", "Sbtr", "blendSubtraction", "subtract")
        m("divide", "divide", "blendDivide")
        m("hue", "H   ", "hue")
        m("saturation", "Strt", "saturation")
        m("color", "Clr ", "color")
        m("luminize", "Lmns", "luminosity")
        m("behind", "Bhnd", "behind")
    }

    /** 主笔里没有精确对应物的模式 → 最接近的 id（会记日志） */
    private val BRUSH_APPROX: Map<String, String> = mapOf(
        // Photoshop 画笔的 Clear 是擦成透明；RP 的 erase 只留给橡皮擦预设（原生按预设标志处理），普通预设里会被改成 normal
        norm("Clar") to "normal",
        // 穿透只对图层组有意义
        norm("pass") to "normal",
        norm("Pass") to "normal",
    )

    /** 双重画笔 masking 支持的合成 id（原生 KisMaskingBrushCompositeOp 的固定集合） */
    private val DUAL: Map<String, String> = buildMap {
        fun m(id: String, vararg codes: String) = codes.forEach { put(norm(it), id) }
        m("multiply", "Mltp", "multiply")
        m("darken", "Drkn", "darken")
        m("overlay", "Ovrl", "overlay")
        m("dodge", "CDdg", "colorDodge")
        m("burn", "CBrn", "colorBurn")
        m("linear_burn", "linearBurn")
        m("linear_dodge", "linearDodge")
        m("hard_mix_photoshop", "hardMix")
        m("subtract", "Sbtr", "blendSubtraction", "subtract")
        m("height_photoshop", "Hght")
        m("linear_height_photoshop", "linearHeight")
    }

    /** 纹理模式：Krita `TexturingMode` 名 */
    private val TEXTURE: Map<String, String> = buildMap {
        fun m(id: String, vararg codes: String) = codes.forEach { put(norm(it), id) }
        m("multiply", "Mltp", "multiply")
        m("subtract", "Sbtr", "blendSubtraction", "subtract")
        m("darken", "Drkn", "darken")
        m("overlay", "Ovrl", "overlay")
        m("color_dodge", "CDdg", "Ddg ", "colorDodge")
        m("color_burn", "CBrn", "Brn ", "colorBurn")
        m("linear_dodge", "linearDodge")
        m("linear_burn", "linearBurn")
        m("hard_mix_photoshop", "hardMix")
        m("height_photoshop", "Hght")
        m("linear_height_photoshop", "linearHeight")
    }

    /** 双重画笔 / 纹理共用的"最接近"族：Photoshop 有而 Krita 的这两处没有的模式 */
    private fun closestFamily(code: String, isDual: Boolean): String {
        val dodge = if (isDual) "dodge" else "color_dodge"
        return when (code) {
            norm("Lghn"), norm("Scrn"), norm("lighterColor") -> "linear_dodge"
            norm("SftL"), norm("HrdL"), norm("vividLight"), norm("linearLight"), norm("pinLight") -> "overlay"
            norm("Dfrn"), norm("Xclu") -> "subtract"
            norm("divide"), norm("blendDivide") -> dodge
            norm("darkerColor") -> "darken"
            else -> "multiply"
        }
    }

    private fun resolve(ctx: Context, rawCode: String?, exactTable: Map<String, String>, default: String): Mapped {
        val code = norm(rawCode)
        if (code.isEmpty()) return Mapped(default, true)
        exactTable[code]?.let { return Mapped(it, true) }
        if (ctx == Context.BRUSH) {
            BRUSH_APPROX[code]?.let {
                BrushDiagnostics.log("abr_blend_approx", "ctx" to ctx.label, "code" to rawCode?.trim(), "to" to it)
                return Mapped(it, false)
            }
        } else if (code in BRUSH) {
            val to = closestFamily(code, ctx == Context.DUAL)
            BrushDiagnostics.log("abr_blend_approx", "ctx" to ctx.label, "code" to rawCode?.trim(), "to" to to)
            return Mapped(to, false)
        }
        BrushDiagnostics.log("abr_blend_unknown", "ctx" to ctx.label, "code" to rawCode?.trim(), "to" to default)
        return Mapped(default, false)
    }

    fun brush(code: String?): Mapped = resolve(Context.BRUSH, code, BRUSH, "normal")

    fun dual(code: String?, policy: Policy = Policy.COMPAT_SAFE): Mapped {
        val mapped = resolve(Context.DUAL, code, DUAL, "multiply")
        if (policy == Policy.COMPAT_SAFE && (mapped.id == "linear_burn" || mapped.id == "burn")) {
            BrushDiagnostics.log("abr_blend_compat", "ctx" to "dual", "code" to code?.trim(), "from" to mapped.id, "to" to "multiply")
            return Mapped("multiply", false)
        }
        return mapped
    }

    fun texture(code: String?, policy: Policy = Policy.COMPAT_SAFE): Mapped {
        val mapped = resolve(Context.TEXTURE, code, TEXTURE, "multiply")
        if (policy == Policy.COMPAT_SAFE && mapped.id == "linear_burn") {
            BrushDiagnostics.log("abr_blend_compat", "ctx" to "texture", "code" to code?.trim(), "from" to "linear_burn", "to" to "color_burn")
            return Mapped("color_burn", false)
        }
        return mapped
    }

    /** 测试 / 审计用：该上下文能精确映射的全部 Photoshop 码 */
    fun exactCodes(ctx: Context): Set<String> = when (ctx) {
        Context.BRUSH -> BRUSH.keys
        Context.DUAL -> DUAL.keys
        Context.TEXTURE -> TEXTURE.keys
    }
}
