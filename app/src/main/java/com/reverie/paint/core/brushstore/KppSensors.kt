/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

/**
 * Krita 曲线选项（KisCurveOption）读写规则（W-03）。
 *
 * 依据 KisKritaSensorPack.cpp:113-232 与 KisStandardOptionData：
 * - isChecked = !checkable || Pressure<Id>（缺省 false）
 * - `<Id>Sensor` 是单个 `<params id="pressure">…</params>` 或
 *   `<params id="sensorslist"><ChildSensor id=".."/>…</params>`
 * - `<Id>UseCurve` 缺省 true；`<Id>UseSameCurve` 缺省 true，且 `<Id>commonCurve` 存在时覆盖各传感器曲线
 * - `<Id>Value` 缺省为最大值
 *
 * 写规则（执行文档 W-03 细化）：
 * - 快照里该选项未勾选而用户改了值 → Pressure<Id>=true、<Id>Value、<Id>UseCurve=false（恒定值，不带出旧传感器）
 * - 快照里已勾选 → 只改 <Id>Value，传感器与 UseCurve 原样保留
 * - 抖动类（h/s/v）启用时把 fuzzy 合并进传感器列表，而不是覆盖原传感器
 */
object KppSensors {
    /** NotCheckable 的选项（Opacity / Flow）；其余标准选项都可勾选 */
    val NOT_CHECKABLE = setOf("Opacity", "Flow")

    const val LINEAR = "0,0;1,1;"

    fun isCheckable(id: String): Boolean = id !in NOT_CHECKABLE

    fun pressureKey(id: String): String = "Pressure$id"

    fun isChecked(xml: String, id: String): Boolean =
        !isCheckable(id) || KppXml.getBool(xml, pressureKey(id), false)

    fun useCurve(xml: String, id: String): Boolean = KppXml.getBool(xml, "${id}UseCurve", true)

    fun sensorXml(xml: String, id: String): String? = KppXml.get(xml, "${id}Sensor")?.takeIf { it.isNotBlank() }

    private val topParams = Regex("""<params\b[^>]*\bid="([^"]*)"[^>]*>""")
    private val childSensor = Regex("""<ChildSensor\b[^>]*?\bid="([^"]*)"[^>]*?(?:/>|>.*?</ChildSensor>)""", RegexOption.DOT_MATCHES_ALL)
    private val curveTag = Regex("""<curve>(.*?)</curve>""", RegexOption.DOT_MATCHES_ALL)

    fun topId(sensor: String): String? = topParams.find(sensor)?.groupValues?.get(1)

    /** 传感器 id 列表；sensorslist 展开为子传感器 */
    fun sensorIds(sensor: String?): List<String> {
        if (sensor.isNullOrBlank()) return emptyList()
        val top = topId(sensor) ?: return emptyList()
        return if (top == "sensorslist") childSensor.findAll(sensor).map { it.groupValues[1] }.toList() else listOf(top)
    }

    fun hasPressure(ids: Collection<String>): Boolean = "pressure" in ids || "pressurein" in ids

    /**
     * 对于复合传感器 (sensorslist)，优先选择真实压感 ("pressure" / "pressurein") 作为主传感器，
     * 避免因 speed 等附属子传感器排在第一项而误将其反相曲线张冠李戴给压感。
     */
    fun primarySensorId(sensor: String?, optionKey: String = ""): String? {
        if (sensor.isNullOrBlank()) return null
        val ids = sensorIds(sensor)
        if (ids.isEmpty()) return null
        if ("pressure" in ids) return "pressure"
        if ("pressurein" in ids) return "pressurein"
        return PresetPropertyMap.primarySensor(optionKey, ids) ?: ids.firstOrNull()
    }

    fun firstCurve(sensor: String?): String? = sensor?.let { curveTag.find(it)?.groupValues?.get(1)?.trim() }

    fun single(id: String, curve: String = LINEAR): String =
        """<!DOCTYPE params><params id="$id"><curve>$curve</curve></params>"""

    /** 把 [id] 合并进已有传感器（已有则原样返回）；单传感器会升级为 sensorslist */
    fun addSensor(sensor: String?, id: String, curve: String = LINEAR): String {
        if (sensor.isNullOrBlank() || topId(sensor) == null) return single(id, curve)
        val ids = sensorIds(sensor)
        if (id in ids) return sensor
        val top = topId(sensor)!!
        val newChild = """<ChildSensor id="$id"><curve>$curve</curve></ChildSensor>"""
        if (top == "sensorslist") {
            val end = sensor.lastIndexOf("</params>")
            return if (end >= 0) sensor.substring(0, end) + newChild + sensor.substring(end) else sensor
        }
        // 单传感器 → 列表：保留原传感器的属性与内部（曲线等）
        val m = topParams.find(sensor)!!
        val attrs = m.value.removePrefix("<params").removeSuffix(">").replace(Regex("""\s*\bid="[^"]*""""), "")
        val innerStart = m.range.last + 1
        val innerEnd = sensor.lastIndexOf("</params>").takeIf { it >= innerStart } ?: sensor.length
        val inner = sensor.substring(innerStart, innerEnd)
        val oldChild = """<ChildSensor id="$top"$attrs>$inner</ChildSensor>"""
        return """<!DOCTYPE params><params id="sensorslist">$oldChild$newChild</params>"""
    }

    /** 从传感器中去掉 [id]；没有剩余传感器时返回 null */
    fun removeSensor(sensor: String?, id: String): String? {
        if (sensor.isNullOrBlank()) return null
        val top = topId(sensor) ?: return null
        if (top != "sensorslist") return if (top == id) null else sensor
        val out = childSensor.replace(sensor) { if (it.groupValues[1] == id) "" else it.value }
        return if (sensorIds(out).isEmpty()) null else out
    }

    /**
     * 只改 [sensor] 里 id 为 [id] 的那一个传感器的曲线：单传感器改 `<curve>`，
     * sensorslist 只改对应 ChildSensor（自闭合的补上 `<curve>`）。[id] 不存在时返回 null。
     */
    fun setSensorCurve(sensor: String, id: String, curve: String): String? {
        val top = topId(sensor) ?: return null
        if (top != "sensorslist") {
            if (top != id) return null
            return if (curveTag.containsMatchIn(sensor)) {
                curveTag.replaceFirst(sensor, Regex.escapeReplacement("<curve>$curve</curve>"))
            } else {
                val end = sensor.lastIndexOf("</params>")
                if (end < 0) null else sensor.substring(0, end) + "<curve>$curve</curve>" + sensor.substring(end)
            }
        }
        val m = childSensor.findAll(sensor).firstOrNull { it.groupValues[1] == id } ?: return null
        val child = m.value
        val newChild = if (child.trimEnd().endsWith("/>") && !child.contains("</ChildSensor>")) {
            child.trimEnd().removeSuffix("/>").trimEnd() + "><curve>$curve</curve></ChildSensor>"
        } else if (curveTag.containsMatchIn(child)) {
            curveTag.replaceFirst(child, Regex.escapeReplacement("<curve>$curve</curve>"))
        } else {
            child.replace("</ChildSensor>", "<curve>$curve</curve></ChildSensor>")
        }
        return sensor.replaceRange(m.range, newChild)
    }

    /** 把 sensorslist 里 [id] 的子传感器移到最前（其余子传感器原样保留）；单传感器或不存在时原样返回 */
    fun moveFirst(sensor: String, id: String): String {
        if (topId(sensor) != "sensorslist") return sensor
        val ms = childSensor.findAll(sensor).toList()
        val m = ms.firstOrNull { it.groupValues[1] == id } ?: return sensor
        if (ms.first().range == m.range) return sensor
        val without = sensor.removeRange(m.range)
        val firstStart = childSensor.find(without)?.range?.first ?: return sensor
        return without.substring(0, firstStart) + m.value + without.substring(firstStart)
    }

    /** [sensor] 里 id 为 [id] 的传感器的曲线；自闭合 ChildSensor 视为线性 */
    fun sensorCurve(sensor: String?, id: String): String? {
        if (sensor == null) return null
        val top = topId(sensor) ?: return null
        if (top != "sensorslist") return if (top == id) (firstCurve(sensor) ?: LINEAR) else null
        val m = childSensor.findAll(sensor).firstOrNull { it.groupValues[1] == id } ?: return null
        return curveTag.find(m.value)?.groupValues?.get(1)?.trim() ?: LINEAR
    }

    /** UseSameCurve 且有 commonCurve 时，Krita 实际使用 commonCurve；读回时把各传感器曲线替换成它 */
    fun effectiveSensorXml(xml: String, id: String): String? {
        val s = sensorXml(xml, id) ?: return null
        val common = commonCurve(xml, id) ?: return s
        return curveTag.replace(s) { "<curve>$common</curve>" }
    }

    fun commonCurve(xml: String, id: String): String? {
        if (!KppXml.getBool(xml, "${id}UseSameCurve", true)) return null
        return KppXml.get(xml, "${id}commonCurve")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 只写传感器结构（增删子传感器等）。**不**改 commonCurve：UseSameCurve 时 Krita 对所有传感器用
     * commonCurve，结构变化不应改变它；曲线编辑由 PresetPropertyMap.writeCurve 显式同步。
     */
    fun writeSensor(xml: String, id: String, sensor: String): String = KppXml.set(xml, "${id}Sensor", sensor)

    /** 曲线选项强度读：勾选取 Value（缺省 [max]），未勾选取 [unchecked] */
    fun readStrength(xml: String, id: String, unchecked: Double, max: Double = 1.0): Double =
        if (isChecked(xml, id)) KppXml.getDouble(xml, "${id}Value") ?: max else unchecked

    /**
     * 曲线选项强度写。[enable]=false 时取消勾选（Value 仍写，便于导出可读）；
     * true 时见类注释的两条规则。
     */
    fun writeStrength(xml: String, id: String, value: Double, enable: Boolean): String {
        var x = xml
        if (!isCheckable(id)) return KppXml.set(x, "${id}Value", KppXml.fmt(value))
        if (!enable) {
            x = KppXml.set(x, pressureKey(id), "false")
            return KppXml.set(x, "${id}Value", KppXml.fmt(value))
        }
        val wasChecked = isChecked(x, id)
        x = KppXml.set(x, pressureKey(id), "true")
        x = KppXml.set(x, "${id}Value", KppXml.fmt(value))
        if (!wasChecked) x = KppXml.set(x, "${id}UseCurve", "false")
        return x
    }

    /** 抖动类：启用时勾选 + UseCurve + 合并 fuzzy 传感器 */
    fun writeJitter(xml: String, id: String, value: Double, enable: Boolean): String {
        var x = KppXml.set(xml, "${id}Value", KppXml.fmt(value))
        x = KppXml.set(x, pressureKey(id), enable.toString())
        if (enable) {
            val s = sensorXml(x, id)
            if (!isChecked(xml, id) || "fuzzy" !in sensorIds(s)) {
                x = KppXml.set(x, "${id}UseCurve", "true")
                x = writeSensor(x, id, if (isChecked(xml, id)) addSensor(s, "fuzzy") else single("fuzzy"))
            }
        }
        return x
    }

    /**
     * 全树扫描预设 XML，判定是否存在任何已启用的压感传感器。
     * 用于解决类似 inkP 21 Flat Rake 等笔刷（其 Size/Opacity/Flow 未开启压感，但
     * Texture/Strength 或 MaskingBrush 开启了压感）在导入时基线 pressureEnabled 被误判为 false 的问题。
     */
    fun hasActivePressureSensor(xml: String): Boolean {
        // 1. 纹理强度 (Texture/Strength): inkP 20/21/22 等的核心压感挂载点
        val texEnabled = KppXml.getBool(xml, "Texture/Pattern/Enabled", false)
        if (texEnabled && useCurve(xml, "Texture/Strength/")) {
            val s = sensorXml(xml, "Texture/Strength/")
            if (hasPressure(sensorIds(s))) return true
        }

        // 2. 副笔刷 (MaskingBrush/Preset/) 内部选项
        if (KppXml.getBool(xml, "MaskingBrush/Enabled", false)) {
            val subCheckable = listOf(
                "Size", "Rotation", "Scatter", "Softness", "Sharpness", "Spacing", "Ratio"
            )
            for (opt in subCheckable) {
                val prefix = "MaskingBrush/Preset/$opt"
                val checked = KppXml.getBool(xml, "MaskingBrush/Preset/Pressure$opt", false)
                val useCurve = KppXml.getBool(xml, "${prefix}UseCurve", true)
                if (checked && useCurve) {
                    val s = KppXml.get(xml, "${prefix}Sensor")
                    if (hasPressure(sensorIds(s))) return true
                }
            }
            // 副笔刷非 checkable 选项 (Opacity / Flow)
            for (opt in listOf("Opacity", "Flow")) {
                val prefix = "MaskingBrush/Preset/$opt"
                if (KppXml.getBool(xml, "${prefix}UseCurve", false)) {
                    val s = KppXml.get(xml, "${prefix}Sensor")
                    if (hasPressure(sensorIds(s))) return true
                }
            }
        }

        // 3. 主笔刷其它标准带开关选项 (需要显式 Pressure<Id>=true 且 UseCurve=true)
        val otherCheckable = listOf(
            "Rotation", "Scatter", "Softness", "Sharpness", "Spacing", "Darken", "Mix",
            "Rate", "Ratio", "Mirror", "s", "v", "h"
        )
        for (opt in otherCheckable) {
            val checked = KppXml.getBool(xml, pressureKey(opt), false)
            val useCurve = useCurve(xml, opt)
            if (checked && useCurve) {
                val s = sensorXml(xml, opt)
                if (hasPressure(sensorIds(s))) return true
            }
        }

        // 4. 通用全参数扫描 (解除硬编码白名单限制，覆盖 Deform/Filter/Hairy/Hatching/Particle 等全部笔刷引擎)
        val paramRegex = Regex("""<param\b[^>]*\bname="([^"]*)"[^>]*>(.*?)</param>""", RegexOption.DOT_MATCHES_ALL)
        for (m in paramRegex.findAll(xml)) {
            val name = m.groupValues[1]
            val rawValue = m.groupValues[2].trim()
            val value = if (rawValue.startsWith("<![CDATA[")) rawValue.removePrefix("<![CDATA[").removeSuffix("]]>").trim() else rawValue

            // 跳过未启用的模块
            if ("MaskingBrush/" in name && !KppXml.getBool(xml, "MaskingBrush/Enabled", false)) continue
            if ("Texture/" in name && !KppXml.getBool(xml, "Texture/Pattern/Enabled", false)) continue

            // 4.1 参数名以 Pressure 开头且值为 true 的布尔开关 (如 PressureSize)
            if (name.startsWith("Pressure", ignoreCase = true) && (value.equals("true", ignoreCase = true) || value == "1")) {
                val opt = name.substring(8)
                if (opt.isEmpty() || useCurve(xml, opt)) return true
            }

            // 4.2 参数值内部包含传感器且包含 pressure
            if (value.startsWith("<!DOCTYPE params>") || value.contains("<params") || value.contains("<ChildSensor")) {
                val ids = sensorIds(value)
                if (hasPressure(ids)) {
                    val opt = name.removeSuffix("Sensor").removePrefix("MaskingBrush/Preset/")
                    val active = useCurve(xml, opt) && (!isCheckable(opt) || isChecked(xml, opt))
                    if (opt.isEmpty() || active) {
                        return true
                    }
                }
            }
        }

        return false
    }
}
