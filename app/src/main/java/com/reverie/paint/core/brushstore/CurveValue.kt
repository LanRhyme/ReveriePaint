/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import java.util.Locale

/**
 * 工作台动力学曲线控件（CURVE_*）的值。
 *
 * 存储/比较用的规范串与 sensor params 全文同形：
 * `<!DOCTYPE params><params id="pressure" enabled="true" strength="1.0"><curve>0,0;1,1;</curve></params>`
 * —— 这样旧 BrushParams.dynamicOptions 的值可直接作为覆盖值，`applySensorXmlToDynamicOptions`
 * 也能直接读回（它认 enabled / strength 属性）。
 */
data class CurveValue(
    val enabled: Boolean,
    val sensorId: String,
    val curve: String,
    val strength: Double = 1.0,
) {
    fun encode(): String =
        """<!DOCTYPE params><params id="$sensorId" enabled="$enabled" strength="${CurveValue.fmt(strength)}"><curve>${normalizeCurve(curve)}</curve></params>"""

    /** 曲线点按数值比较（容差 [TOL]），避免 "0.5" 与 "0.50000001" 之类的格式差异被当成改动 */
    fun sameAs(other: CurveValue): Boolean {
        if (enabled != other.enabled || sensorId != other.sensorId) return false
        if (kotlin.math.abs(strength - other.strength) > TOL) return false
        val a = points(curve)
        val b = points(other.curve)
        if (a.size != b.size) return false
        return a.indices.all { kotlin.math.abs(a[it].first - b[it].first) <= TOL && kotlin.math.abs(a[it].second - b[it].second) <= TOL }
    }

    companion object {
        const val TOL = 1e-5

        private val idAttr = Regex("""\bid="([^"]*)"""")
        private val enabledAttr = Regex("""\benabled="([^"]*)"""")
        private val strengthAttr = Regex("""\bstrength="([^"]*)"""")
        private val curveTag = Regex("""<curve>(.*?)</curve>""", RegexOption.DOT_MATCHES_ALL)

        fun parse(s: String?): CurveValue? {
            if (s.isNullOrBlank()) return null
            val id = idAttr.find(s)?.groupValues?.get(1)?.trim()?.lowercase()?.ifBlank { null } ?: "pressure"
            val en = enabledAttr.find(s)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: true
            val st = strengthAttr.find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val curveMatch = curveTag.find(s)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val c = when {
                curveMatch != null -> curveMatch
                s.contains(';') || s.contains(',') -> s.trim()
                else -> KppSensors.LINEAR
            }
            return CurveValue(en, id, normalizeCurve(c), st)
        }

        fun points(curve: String): List<Pair<Double, Double>> =
            curve.split(';').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { t ->
                val p = t.split(',')
                if (p.size < 2) null else {
                    val x = p[0].trim().toDoubleOrNull()
                    val y = p[1].trim().toDoubleOrNull()
                    if (x == null || y == null) null else x to y
                }
            }

        fun normalizeCurve(curve: String): String {
            val pts = points(curve)
            if (pts.isEmpty()) return KppSensors.LINEAR
            return pts.joinToString(separator = "") { "${fmt(it.first)},${fmt(it.second)};" }
        }

        fun fmt(v: Double): String {
            val r = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            return if (r == "-0") "0" else r
        }
    }
}

fun com.reverie.paint.model.DynamicOptionConfig.toCurveValue(): CurveValue =
    CurveValue(
        enabled = enabled,
        sensorId = sensorId.ifBlank { "pressure" },
        curve = toKritaCurveString(),
        strength = strength.toDouble(),
    )

fun com.reverie.paint.model.DynamicOptionConfig.toCurveXml(): String = toCurveValue().encode()

