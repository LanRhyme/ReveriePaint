/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.reverie.paint.core.packIntsLE1024
import com.reverie.paint.model.AdjustmentLutEditor

internal class AdjustmentLutSnapshot(
    val bytes: ByteArray,
    val curves: AdjustmentLutEditor.Curves? = null,
    val gradient: AdjustmentLutEditor.Gradient? = null,
)

private fun FilterAdjustState.curvesSnapshot() = AdjustmentLutEditor.Curves(List(4) { channel ->
    curveChannels.getValue(channel).map { AdjustmentLutEditor.Point(it.x, it.y) }
})

private fun FilterAdjustState.gradientSnapshot() = AdjustmentLutEditor.Gradient(
    customGradStops.map { AdjustmentLutEditor.Stop(it.pos, it.color.toArgb()) }, reverseGradient,
)

internal fun FilterAdjustState.restoreAdjustmentLut(type: Int, lut: ByteArray?) {
    if (lut == null) return
    when (type) {
        13 -> AdjustmentLutEditor.decodeCurves(lut)?.let { curves ->
            curves.channels.forEachIndexed { channel, points ->
                curveChannels.getValue(channel).apply {
                    clear()
                    addAll(points.map { Offset(it.x, it.y) })
                }
            }
            adjustmentLutSnapshot = AdjustmentLutSnapshot(lut.copyOf(), curves = curvesSnapshot())
        }
        30 -> AdjustmentLutEditor.decodeGradient(lut)?.let { gradient ->
            customGradStops.clear()
            customGradStops.addAll(gradient.stops.mapIndexed { index, stop ->
                CustomGradStop(index.toLong() + 1, stop.position, Color(stop.argb))
            })
            reverseGradient = gradient.reverse
            adjustmentLutSnapshot = AdjustmentLutSnapshot(lut.copyOf(), gradient = gradientSnapshot())
        }
    }
}

internal fun FilterAdjustState.buildAdjustmentCurvesLut(): ByteArray {
    val curves = curvesSnapshot()
    adjustmentLutSnapshot?.takeIf { it.curves == curves }?.let { return it.bytes }
    val channels = List(4) { calculateMonotoneCubicSplineLUT(curveChannels.getValue(it)) }
    val lut = ByteArray(768) { index ->
        val master = channels[0][index % 256].toInt() and 255
        channels[index / 256 + 1][master]
    }
    return AdjustmentLutEditor.encodeCurves(lut, curves)
}

internal fun FilterAdjustState.buildAdjustmentGradientLut(): ByteArray {
    val gradient = gradientSnapshot()
    adjustmentLutSnapshot?.takeIf { it.gradient == gradient }?.let { return it.bytes }
    val lut = packIntsLE1024(generateGradientLUTFromStops(customGradStops, reverseGradient))
    return AdjustmentLutEditor.encodeGradient(lut, gradient)
}
