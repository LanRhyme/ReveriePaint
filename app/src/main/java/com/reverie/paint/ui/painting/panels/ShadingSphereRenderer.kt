package com.reverie.paint.ui.painting.panels

import android.graphics.Bitmap
import com.reverie.paint.model.SphereShading

/**
 * 3D 光影球位图预渲染。仅在固有色 / 光色 / 阴影色 / softness 变化时调用一次,
 * 渲染结果可被 Compose 直接当静态图使用 (滑动吸色时不重绘)。
 */

/** 边缘抗锯齿宽度 (像素) */
private const val AA_WIDTH_PX = 1.5f

/**
 * 渲染 3D 光影球。
 *
 * @param baseHex 固有色 (球心), 形如 "#6BA43B"
 * @param shadowHex 阴影 / 环境光色
 * @param lightHex 主光色
 * @param softness 漫反射柔和度 [0, 1], 控制明暗交界线宽度
 * @param size 位图边长 (正方形)
 */
fun renderSphereBitmap(
    baseHex: String,
    shadowHex: String,
    lightHex: String,
    softness: Float,
    size: Int = 320
): Bitmap {
    val base = SphereShading.parseHexToArgb(baseHex)
    val shadow = SphereShading.parseHexToArgb(shadowHex)
    val light = SphereShading.parseHexToArgb(lightHex)
    val profile = SphereShading.Profile(base, shadow, light, softness)

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    val half = (size - 1) / 2f
    // 半径内缩 2px, 给抗锯齿留出画布空间, 避免球体被位图边界切边
    val radius = (size - 4) / 2f
    val outerLimit = 1f + AA_WIDTH_PX / radius
    val aaScale = radius / AA_WIDTH_PX

    var idx = 0
    for (row in 0 until size) {
        val dy = (row - half) / radius
        val dy2 = dy * dy
        for (col in 0 until size) {
            val dx = (col - half) / radius
            val dist = kotlin.math.sqrt(dx * dx + dy2)
            if (dist >= outerLimit) {
                idx++
                continue
            }
            val rgb = profile.shadeArgb(dx, dy) and 0x00FFFFFF
            // 覆盖率抗锯齿: 单位圆外用 1.5px 渐变消隐
            val cov = ((1f - dist) * aaScale).coerceIn(0f, 1f)
            val a = (255f * cov * cov * (3f - 2f * cov) + 0.5f).toInt()
            pixels[idx++] = (a shl 24) or rgb
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}
