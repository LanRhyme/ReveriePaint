/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.json.JSONObject

/**
 * 旧 `paint_prefs/brush_params` 单条记录的 JSON 编解码（原样搬自 persistBrushParams/loadBrushParams，
 * 抽出来是为了让 W-07 迁移测试能走与真机相同的"写入→重启读回(含 heal)"路径）。
 */
object LegacyBrushParamsCodec {
    /** loadBrushParams 中按一次性 prefs 标记触发的旧数据修复 */
    data class Heal(
        val softness: Boolean = false,
        val fade: Boolean = false,
        val jitter: Boolean = false,
        val spacing: Boolean = false,
        val scatter: Boolean = false,
    )

    fun encode(name: String, p: BrushParams): JSONObject {
        val o = JSONObject()
        o.put("n", name)
        o.put("s", p.size)
        o.put("o", p.opacity)
        o.put("f", p.flow)
        o.put("sp", p.spacing)
        o.put("ang", p.angle)
        o.put("sc", p.scatter)
        o.put("fa", p.fade)
        o.put("so", p.softness)
        o.put("ra", p.ratio)
        o.put("sh", p.sharpness)
        o.put("ro", p.rotation)
        o.put("cop", p.compositeOp)
        o.put("aa", p.antiAliasing)
        o.put("ts", p.tipShape)
        o.put("rfx", p.randomFlipX)
        o.put("rfy", p.randomFlipY)
        o.put("fd", p.followDirection)
        o.put("sl", p.streamline)
        o.put("tp", p.taper)
        o.put("te", p.textureEnabled)
        o.put("tscl", p.textureScale)
        o.put("tstr", p.textureStrength)
        o.put("t_spm", p.textureStrengthPressureMin)
        o.put("tm", p.textureMode)
        o.put("txp", p.texturePattern)
        o.put("tinv", p.textureInvert)
        o.put("hj", p.hueJitter)
        o.put("sj", p.satJitter)
        o.put("vj", p.valJitter)
        o.put("sm", p.secondaryMix)
        o.put("pcm", p.pressureColorMix)
        o.put("pe", p.pressureEnabled)
        o.put("ps", p.pressureSize)
        o.put("po", p.pressureOpacity)
        o.put("pf", p.pressureFlow)
        o.put("ss", p.speedSize)
        o.put("pc", p.pressureCurve)
        o.put("mins", p.minSizeLimit)
        o.put("maxs", p.maxSizeLimit)
        o.put("ta", p.tipAsset)
        o.put("poid", p.paintOpId)
        o.put("ab", p.airbrush)
        o.put("abr", p.airbrushRate)
        o.put("smr", p.smudgeRate)
        o.put("sml", p.smudgeLength)
        o.put("spk", p.spikes)
        o.put("ja", p.jitterAngle)
        o.put("js", p.jitterSize)
        o.put("aut", p.author)
        o.put("autl", p.isAuthorLocked)
        o.put("desc", p.description)
        o.put("ver", p.version)
        o.put("cus", p.isCustomized)
        o.put("dc", p.dynamicsCustomized)
        o.put("scus", p.smudgeCustomized)
        o.put("spc", p.spacingCustomized)
        o.put("m_en", p.maskingEnabled)
        o.put("m_op", p.maskingCompositeOp)
        o.put("m_sr", p.maskingSizeRatio)
        o.put("m_sp", p.maskingSpacing)
        o.put("m_ta", p.maskingTipAsset)
        o.put("m_sc", p.maskingScatter)
        o.put("m_sba", p.maskingScatterBothAxes)
        o.put("m_ang", p.maskingAngle)
        o.put("m_fx", p.maskingFlipX)
        o.put("m_fy", p.maskingFlipY)
        o.put("m_ts", p.maskingTipShape)
        o.put("m_fa", p.maskingFade)
        o.put("m_so", p.maskingSoftness)
        o.put("r_se", p.rotationSensor)
        o.put("sc_se", p.scatterSensor)
        o.put("sz_se", p.sizeSensor)
        o.put("op_se", p.opacitySensor)
        o.put("fl_se", p.flowSensor)
        o.put("cr", p.colorRate)
        o.put("smm", p.smudgeMode)
        if (p.dynamicOptions.isNotEmpty()) {
            val dynObj = org.json.JSONObject()
            for ((k, v) in p.dynamicOptions) dynObj.put(k, v)
            o.put("dyn_opt", dynObj)
        }
        return o
    }

    fun decode(o: JSONObject, heal: Heal = Heal()): BrushParams {
        val name = o.getString("n")
        val isEraser = name.startsWith("a)_") || name.contains("Eraser", ignoreCase = true)
        val rawCop = o.optString("cop", "normal")
        val rawSp = o.optDouble("sp", 0.1)
        val spc = o.optBoolean("spc", false)
        val healedSpacing = if (!spc || heal.spacing) 0.1 else rawSp
        val dc = o.optBoolean("dc", false)
        val rawSc = o.optDouble("sc", 0.0)
        val healedScatter = if (!dc || heal.scatter || rawSc >= 2.0) 0.0 else rawSc
        return BrushParams(
            size = o.optDouble("s", 20.0),
            opacity = o.optDouble("o", 1.0),
            flow = o.optDouble("f", 1.0),
            spacing = healedSpacing,
            angle = o.optDouble("ang", 0.0),
            scatter = healedScatter,
            fade = if (heal.fade) KppHelper.FADE_SOLID else o.optDouble("fa", KppHelper.FADE_SOLID),
            softness = if (heal.softness) KppHelper.SOFTNESS_NEUTRAL else o.optDouble("so", KppHelper.SOFTNESS_NEUTRAL),
            ratio = o.optDouble("ra", 1.0),
            sharpness = o.optDouble("sh", 0.0),
            rotation = o.optDouble("ro", 0.0),
            compositeOp = if (!isEraser && rawCop == "erase") "normal" else rawCop,
            antiAliasing = o.optInt("aa", 1),
            tipShape = o.optInt("ts", 0),
            randomFlipX = o.optBoolean("rfx", false),
            randomFlipY = o.optBoolean("rfy", false),
            followDirection = o.optBoolean("fd", false),
            streamline = o.optDouble("sl", 0.0),
            taper = o.optDouble("tp", 0.0),
            textureEnabled = o.optBoolean("te", false),
            textureScale = o.optDouble("tscl", 1.0),
            textureStrength = o.optDouble("tstr", 0.5),
            textureStrengthPressureMin = o.optDouble("t_spm", -1.0),
            textureMode = o.optString("tm", "multiply"),
            texturePattern = o.optString("txp", ""),
            textureInvert = o.optBoolean("tinv", false),
            hueJitter = if (heal.jitter) 0.0 else o.optDouble("hj", 0.0),
            satJitter = if (heal.jitter) 0.0 else o.optDouble("sj", 0.0),
            valJitter = if (heal.jitter) 0.0 else o.optDouble("vj", 0.0),
            secondaryMix = if (heal.jitter) 0.0 else o.optDouble("sm", 0.0),
            pressureColorMix = o.optBoolean("pcm", false),
            pressureEnabled = o.optBoolean("pe", true),
            pressureSize = o.optDouble("ps", 1.0),
            pressureOpacity = o.optDouble("po", 1.0),
            pressureFlow = o.optDouble("pf", 1.0),
            speedSize = o.optDouble("ss", 0.0),
            pressureCurve = o.optInt("pc", 0),
            minSizeLimit = o.optDouble("mins", 1.0),
            maxSizeLimit = o.optDouble("maxs", 500.0),
            tipAsset = o.optString("ta", ""),
            paintOpId = o.optString("poid", "defaultpaintop"),
            airbrush = o.optBoolean("ab", false),
            airbrushRate = o.optDouble("abr", 30.0).let { if (it < 5.0) 30.0 else it },
            smudgeRate = o.optDouble("smr", 0.5),
            smudgeLength = o.optDouble("sml", 0.5),
            spikes = o.optInt("spk", 2),
            jitterAngle = o.optDouble("ja", 0.0),
            jitterSize = o.optDouble("js", 0.0),
            author = o.optString("aut", "ReveriePaint"),
            isAuthorLocked = o.optBoolean("autl", false),
            description = o.optString("desc", ""),
            version = o.optString("ver", "1.0"),
            isCustomized = run {
                if (o.has("cus")) {
                    o.optBoolean("cus", false)
                } else {
                    // Legacy cache without "cus": heal contaminated imported/default presets
                    val aut = o.optString("aut", "")
                    val s = o.optDouble("s", 20.0)
                    !(aut == "外部创作者 (分享)" || (aut == "ReveriePaint" && s == 20.0))
                }
            },
            dynamicsCustomized = o.optBoolean("dc", false),
            smudgeCustomized = o.optBoolean("scus", false),
            spacingCustomized = if (heal.spacing) false else spc,
            maskingEnabled = o.optBoolean("m_en", false),
            maskingCompositeOp = o.optString("m_op", "multiply"),
            maskingSizeRatio = o.optDouble("m_sr", 1.0),
            maskingSpacing = o.optDouble("m_sp", 0.1),
            maskingTipAsset = o.optString("m_ta", ""),
            maskingScatter = o.optDouble("m_sc", 0.0),
            maskingScatterBothAxes = o.optBoolean("m_sba", true),
            maskingAngle = o.optDouble("m_ang", 0.0),
            maskingFlipX = o.optBoolean("m_fx", false),
            maskingFlipY = o.optBoolean("m_fy", false),
            maskingTipShape = o.optInt("m_ts", 0),
            maskingFade = o.optDouble("m_fa", 0.0),
            maskingSoftness = o.optDouble("m_so", 1.0),
            rotationSensor = o.optString("r_se", "drawingangle"),
            scatterSensor = o.optString("sc_se", "fuzzy"),
            sizeSensor = o.optString("sz_se", "pressure"),
            opacitySensor = o.optString("op_se", "pressure"),
            flowSensor = o.optString("fl_se", "pressure"),
            colorRate = o.optDouble("cr", 0.5),
            smudgeMode = o.optInt("smm", 0),
            dynamicOptions = run {
                val dynObj = o.optJSONObject("dyn_opt")
                if (dynObj != null) {
                    val map = mutableMapOf<String, String>()
                    val keys = dynObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = dynObj.getString(k)
                    }
                    map
                } else emptyMap()
            },
        )
    }
}
