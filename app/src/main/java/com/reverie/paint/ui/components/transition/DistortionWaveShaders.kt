/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * AGSL RuntimeShader pipeline (API 33+) and fast off-UI-thread blur pyramid for
 * the gallery <-> painting DistortionWave transition.
 */

package com.reverie.paint.ui.components.transition

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.reverie.paint.model.DistortionWavePalette
import com.reverie.paint.model.DistortionWaveStyle
import com.reverie.paint.model.WaveFront
import com.reverie.paint.model.WaveRim
import com.reverie.paint.model.WaveSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 单张页面快照的清晰 + 渐进模糊金字塔 (清晰全分辨图 + 0.22x 近模糊/远模糊图)。 */
internal class WaveSnapshotPyramid(
    val sharp: Bitmap,
    @Volatile var near: Bitmap? = null,
    @Volatile var far: Bitmap? = null,
    val ownsSharp: Boolean = false,
) {
    fun release() {
        val n = near
        val f = far
        near = null
        far = null
        if (n != null && !n.isRecycled && n !== sharp) n.recycle()
        if (f != null && !f.isRecycled && f !== sharp && f !== n) f.recycle()
        if (ownsSharp && !sharp.isRecycled) sharp.recycle()
    }
}

/**
 * 后台线程使用的快速下采样 + 3 趟滑动窗口可分离盒式模糊 (近似真高斯, O(1)/px)。
 * 在 0.22x 分辨率 (~240×520) 下总耗时约 1~2ms, 全 Android 版本可用且无 HardwareRenderer 泄漏风险。
 */
internal object SnapshotBlurHelper {
    private const val BLUR_SCALE = 0.22f
    private const val MAX_BLUR_EDGE = 480

    fun populateBlurs(
        pyramid: WaveSnapshotPyramid,
        density: Float,
        nearSigmaDp: Float,
        farSigmaDp: Float,
    ) {
        val src = pyramid.sharp
        if (src.isRecycled || src.width <= 1 || src.height <= 1) return
        val sw = src.width
        val sh = src.height
        val longEdge = max(sw, sh).toFloat()
        val effectiveScale = (MAX_BLUR_EDGE / longEdge).coerceAtMost(BLUR_SCALE).coerceAtLeast(0.08f)
        val bw = ceil(sw * effectiveScale).toInt().coerceIn(8, MAX_BLUR_EDGE)
        val bh = ceil(sh * effectiveScale).toInt().coerceIn(8, MAX_BLUR_EDGE)

        // HARDWARE 位图不能直接画到软件 Bitmap 或 getPixels; 防御性转一份可读 ARGB_8888
        var copiedHardware: Bitmap? = null
        val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false)?.also { copiedHardware = it } ?: return
        } else {
            src
        }

        try {
            val downscaled = createBitmap(bw, bh)
            val c = Canvas(downscaled)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            c.drawBitmap(readable, Rect(0, 0, sw, sh), Rect(0, 0, bw, bh), p)

            val basePixels = IntArray(bw * bh)
            downscaled.getPixels(basePixels, 0, bw, 0, 0, bw, bh)
            downscaled.recycle()

            val scaleToBlurPx = effectiveScale * density.coerceAtLeast(1f)
            val nearRadius = sigmaToBoxRadius(nearSigmaDp * scaleToBlurPx)
            val farRadius = sigmaToBoxRadius(farSigmaDp * scaleToBlurPx)

            val scratch = IntArray(bw * bh)
            val nearPixels = basePixels.copyOf()
            boxBlur3Pass(nearPixels, scratch, bw, bh, nearRadius)
            val nearBmp = createBitmap(bw, bh)
            nearBmp.setPixels(nearPixels, 0, bw, 0, 0, bw, bh)

            val farPixels = basePixels
            boxBlur3Pass(farPixels, scratch, bw, bh, farRadius)
            val farBmp = createBitmap(bw, bh)
            farBmp.setPixels(farPixels, 0, bw, 0, 0, bw, bh)

            pyramid.near = nearBmp
            pyramid.far = farBmp
        } catch (_: Throwable) {
            // 降级: 保留 null, 着色器自动以 sharp 替代
        } finally {
            copiedHardware?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun sigmaToBoxRadius(sigmaPx: Float): Int {
        if (sigmaPx <= 0.6f) return 1
        val r = ((sqrt((4.0 * sigmaPx * sigmaPx + 1.0)) - 1.0) * 0.5).roundToInt()
        return r.coerceIn(1, 24)
    }

    private fun boxBlur3Pass(pixels: IntArray, tmp: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0 || w <= 1 || h <= 1) return
        repeat(3) {
            blurHorizontal(pixels, tmp, w, h, radius)
            blurVertical(tmp, pixels, w, h, radius)
        }
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        val maxX = w - 1
        for (y in 0 until h) {
            val row = y * w
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -radius..radius) {
                val px = src[row + k.coerceIn(0, maxX)]
                aSum += (px ushr 24) and 0xFF
                rSum += (px ushr 16) and 0xFF
                gSum += (px ushr 8) and 0xFF
                bSum += px and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] =
                    ((aSum / div) shl 24) or
                    ((rSum / div) shl 16) or
                    ((gSum / div) shl 8) or
                    (bSum / div)
                val addIdx = (x + radius + 1).coerceAtMost(maxX)
                val subIdx = (x - radius).coerceAtLeast(0)
                val addPx = src[row + addIdx]
                val subPx = src[row + subIdx]
                aSum += ((addPx ushr 24) and 0xFF) - ((subPx ushr 24) and 0xFF)
                rSum += ((addPx ushr 16) and 0xFF) - ((subPx ushr 16) and 0xFF)
                gSum += ((addPx ushr 8) and 0xFF) - ((subPx ushr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (subPx and 0xFF)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        val maxY = h - 1
        for (x in 0 until w) {
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -radius..radius) {
                val px = src[k.coerceIn(0, maxY) * w + x]
                aSum += (px ushr 24) and 0xFF
                rSum += (px ushr 16) and 0xFF
                gSum += (px ushr 8) and 0xFF
                bSum += px and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] =
                    ((aSum / div) shl 24) or
                    ((rSum / div) shl 16) or
                    ((gSum / div) shl 8) or
                    (bSum / div)
                val addY = (y + radius + 1).coerceAtMost(maxY)
                val subY = (y - radius).coerceAtLeast(0)
                val addPx = src[addY * w + x]
                val subPx = src[subY * w + x]
                aSum += ((addPx ushr 24) and 0xFF) - ((subPx ushr 24) and 0xFF)
                rSum += ((addPx ushr 16) and 0xFF) - ((subPx ushr 16) and 0xFF)
                gSum += ((addPx ushr 8) and 0xFF) - ((subPx ushr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (subPx and 0xFF)
            }
        }
    }
}

/**
 * Android 13+ (API 33+) AGSL 双着色器管线:
 * - Lens 着色器: 卡片轮廓 -> 有机圆环 SDF 折射鼓包 + 3 级渐进模糊 + 5 抽头光谱色散 + 交叉淡化
 * - Band 着色器: OKLab 莫兰迪流光色带 + 连续圆角屏幕边缘有机流光团块 + 游动玻璃细线 + 抖动防色带
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class DistortionWaveShaderPipeline private constructor(
    private val lensShader: RuntimeShader,
    private val bandShader: RuntimeShader,
) {
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lumpsScratch = FloatArray(WaveRim.COUNT * 3)

    private var lastPalette: DistortionWavePalette? = null
    private var packedPalette: FloatArray? = null

    private var boundSharp: Bitmap? = null
    private var boundNear: Bitmap? = null
    private var boundFar: Bitmap? = null
    private var sharpShader: BitmapShader? = null
    private var nearShader: BitmapShader? = null
    private var farShader: BitmapShader? = null

    fun drawLens(
        canvas: Canvas,
        widthPx: Float,
        heightPx: Float,
        density: Float,
        sizeDp: WaveSize,
        front: WaveFront,
        style: DistortionWaveStyle,
        pyramid: WaveSnapshotPyramid,
        sideMode: Float,
    ) {
        val sharp = pyramid.sharp
        if (sharp.isRecycled) return
        val near = pyramid.near?.takeIf { !it.isRecycled } ?: sharp
        val far = pyramid.far?.takeIf { !it.isRecycled } ?: near

        if (boundSharp !== sharp) {
            boundSharp = sharp
            sharpShader = BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (boundNear !== near) {
            boundNear = near
            nearShader = BitmapShader(near, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (boundFar !== far) {
            boundFar = far
            farShader = BitmapShader(far, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        val s = lensShader
        s.setInputShader("uSharp", sharpShader!!)
        s.setInputShader("uNear", nearShader!!)
        s.setInputShader("uFar", farShader!!)

        val invW = 1f / sizeDp.width.coerceAtLeast(1f)
        val invH = 1f / sizeDp.height.coerceAtLeast(1f)
        s.setFloatUniform("uSize", sizeDp.width, sizeDp.height)
        s.setFloatUniform("uDensity", density.coerceAtLeast(0.5f))
        s.setFloatUniform("uSharpScale", sharp.width * invW, sharp.height * invH)
        s.setFloatUniform("uNearScale", near.width * invW, near.height * invH)
        s.setFloatUniform("uFarScale", far.width * invW, far.height * invH)
        s.setFloatUniform("uFront", front.originX, front.originY, front.radius, front.cardCornerRadius)
        s.setFloatUniform("uBox", front.boxHalfX, front.boxHalfY, front.holdRelease, front.outgoingCoverMul)
        s.setFloatUniform("uCard", front.cardCenterX, front.cardCenterY, front.cardHalfX, front.cardHalfY)
        s.setFloatUniform("uShape", front.time, front.swell, front.energy, front.gravity)
        s.setFloatUniform("uLens", style.amplitude, style.lensWidth, style.aberration, sideMode)
        s.setFloatUniform(
            "uBlur",
            style.blur,
            style.nearBlur,
            style.blurAhead,
            style.blurBehind,
        )
        s.setFloatUniform("uFlags", if (front.launched) 1f else 0f, if (pyramid.near != null) 1f else 0f)

        lensPaint.shader = s
        canvas.drawRect(0f, 0f, widthPx, heightPx, lensPaint)
    }

    fun drawBand(
        canvas: Canvas,
        widthPx: Float,
        heightPx: Float,
        density: Float,
        sizeDp: WaveSize,
        front: WaveFront,
        style: DistortionWaveStyle,
        palette: DistortionWavePalette,
    ) {
        if (front.glow <= 0.0005f && front.rim <= 0.0005f) return

        val packed = if (lastPalette == palette && packedPalette != null) {
            packedPalette!!
        } else {
            palette.packOklab().also {
                lastPalette = palette
                packedPalette = it
            }
        }

        WaveRim.computeLumps(
            out = lumpsScratch,
            time = front.time,
            size = sizeDp,
            cornerRadius = style.cornerRadius,
            pulse = style.rimPulse,
        )

        val s = bandShader
        s.setFloatUniform("uSize", sizeDp.width, sizeDp.height)
        s.setFloatUniform("uDensity", density.coerceAtLeast(0.5f))
        s.setFloatUniform("uFront", front.originX, front.originY, front.radius, front.cardCornerRadius)
        s.setFloatUniform("uBox", front.boxHalfX, front.boxHalfY, front.holdRelease, 0f)
        s.setFloatUniform("uCard", front.cardCenterX, front.cardCenterY, front.cardHalfX, front.cardHalfY)
        s.setFloatUniform("uShape", front.time, front.swell, style.lead, style.tail)
        s.setFloatUniform("uGlow", front.glow, front.rim, front.clearRadius, front.hairClearRadius)
        s.setFloatUniform("uRimA", style.strength, style.rimWidth, style.rimSoftness, style.cornerRadius)
        s.setFloatUniform("uRimB", style.rimUnevenness, style.rimPulse, style.hairlineWidth, style.hairlineOpacity)
        s.setFloatUniform("uRimC", style.hairlineWhite, packed[40], 0f, 0f)

        s.setFloatUniform("uP0", packed[0], packed[1], packed[2], 0f)
        s.setFloatUniform("uP1", packed[4], packed[5], packed[6], 0f)
        s.setFloatUniform("uP2", packed[8], packed[9], packed[10], 0f)
        s.setFloatUniform("uP3", packed[12], packed[13], packed[14], 0f)
        s.setFloatUniform("uP4", packed[16], packed[17], packed[18], 0f)
        s.setFloatUniform("uP5", packed[20], packed[21], packed[22], 0f)
        s.setFloatUniform("uP6", packed[24], packed[25], packed[26], 0f)
        s.setFloatUniform("uP7", packed[28], packed[29], packed[30], 0f)
        s.setFloatUniform("uAhead", packed[32], packed[33], packed[34], 0f)
        s.setFloatUniform("uBehind", packed[36], packed[37], packed[38], 0f)

        s.setFloatUniform("uL0", lumpsScratch[0], lumpsScratch[1], lumpsScratch[2], 0f)
        s.setFloatUniform("uL1", lumpsScratch[3], lumpsScratch[4], lumpsScratch[5], 0f)
        s.setFloatUniform("uL2", lumpsScratch[6], lumpsScratch[7], lumpsScratch[8], 0f)
        s.setFloatUniform("uL3", lumpsScratch[9], lumpsScratch[10], lumpsScratch[11], 0f)
        s.setFloatUniform("uL4", lumpsScratch[12], lumpsScratch[13], lumpsScratch[14], 0f)
        s.setFloatUniform("uL5", lumpsScratch[15], lumpsScratch[16], lumpsScratch[17], 0f)
        s.setFloatUniform("uL6", lumpsScratch[18], lumpsScratch[19], lumpsScratch[20], 0f)

        bandPaint.shader = s
        canvas.drawRect(0f, 0f, widthPx, heightPx, bandPaint)
    }

    fun clearBoundShaders() {
        boundSharp = null
        boundNear = null
        boundFar = null
        sharpShader = null
        nearShader = null
        farShader = null
        lensPaint.shader = null
        bandPaint.shader = null
    }

    companion object {
        const val SIDE_FORWARD_OUTGOING = 1f
        const val SIDE_FORWARD_INCOMING = -1f
        const val SIDE_RETURN_OUTGOING = -2f

        /** 安全编译 AGSL 着色器; 任一设备驱动编译失败时返回 null, 由上层无缝切入 Canvas 降级。 */
        fun createOrNull(): DistortionWaveShaderPipeline? =
            try {
                DistortionWaveShaderPipeline(
                    lensShader = RuntimeShader(LENS_SHADER_AGSL),
                    bandShader = RuntimeShader(BAND_SHADER_AGSL),
                )
            } catch (_: Throwable) {
                null
            }

        private const val SHARED_FIELD_AGSL = """
            const float coverZone = 56.0;
            const float wavePi = 3.14159265;

            float roundedBoxSDF(float2 p, float2 halfBox, float r) {
              float2 d = abs(p) - halfBox;
              return length(max(d, float2(0.0))) + min(max(d.x, d.y), 0.0) - r;
            }

            float heldAt(float2 p) {
              float cardD = roundedBoxSDF(p - uCard.xy, uCard.zw, uFront.w);
              float cardHeld = smoothstep(0.0, 26.0, cardD);
              return mix(cardHeld, 1.0, uBox.z);
            }

            float fieldAt(float2 p, out float2 n, out float2 q) {
              q = p - uFront.xy;
              float2 boxHalf = uBox.xy;
              float radius = uFront.z;
              float time = uShape.x;
              float swell = uShape.y;

              float2 dBox = abs(q) - boxHalf;
              float2 outer = max(dBox, float2(0.0));
              float outsideLen = length(outer);
              float insideDist = min(max(dBox.x, dBox.y), 0.0);
              float baseSd = outsideLen + insideDist - radius;

              if (outsideLen > 1e-3) {
                float2 sgn = float2(q.x >= 0.0 ? 1.0 : -1.0, q.y >= 0.0 ? 1.0 : -1.0);
                n = (outer * sgn) / outsideLen;
              } else {
                float qLen = length(q);
                n = qLen > 1e-3 ? (q / qLen) : float2(0.0, -1.0);
              }

              float a = (abs(q.x) + abs(q.y) > 1e-4) ? atan(q.y, q.x) : 0.0;
              float bend = 0.62 * sin(2.0 * a + time * 1.1) + 0.38 * sin(3.0 * a - time * 1.5 + 1.7);
              return baseSd + swell * bend;
            }
        """

        private const val LENS_SHADER_AGSL = """
            uniform shader uSharp;
            uniform shader uNear;
            uniform shader uFar;
            uniform float2 uSize;
            uniform float uDensity;
            uniform float2 uSharpScale;
            uniform float2 uNearScale;
            uniform float2 uFarScale;
            uniform float4 uFront;   // originX, originY, radius, cardCornerR
            uniform float4 uBox;     // boxHalfX, boxHalfY, holdRelease, coverMul
            uniform float4 uCard;    // cardCx, cardCy, cardHalfX, cardHalfY
            uniform float4 uShape;   // time, swell, energy, gravity
            uniform float4 uLens;    // amplitude, lensWidth, aberration, sideMode
            uniform float4 uBlur;    // blur, nearBlur, blurAhead, blurBehind
            uniform float2 uFlags;   // launched, hasBlur

            $SHARED_FIELD_AGSL

            float easeSmooth(float a, float b, float x) {
              float t = clamp((x - a) / max(b - a, 1e-3), 0.0, 1.0);
              return t * t * (3.0 - 2.0 * t);
            }

            half4 layerAt(float2 posDp, float nearW, float farW) {
              float2 clamped = clamp(posDp, float2(0.5), uSize - float2(0.5));
              half4 c = uSharp.eval(clamped * uSharpScale);
              if (nearW > 0.0) {
                half4 a = uNear.eval(clamped * uNearScale) * half(nearW);
                c = a + c * (half(1.0) - a.a);
              }
              if (farW > 0.0) {
                half4 b = uFar.eval(clamped * uFarScale) * half(farW);
                c = b + c * (half(1.0) - b.a);
              }
              return c;
            }

            half4 main(float2 fragCoord) {
              float2 p = fragCoord / uDensity;
              float2 n;
              float2 q;
              float d = fieldAt(p, n, q);
              float energy = uShape.z;
              float gravity = uShape.w;
              float sideMode = uLens.w;
              float coverMul = uBox.w;

              float cover = 1.0;
              if (sideMode > 0.0) {
                if (uFlags.x > 0.5) {
                  cover = smoothstep(-coverZone, coverZone, d) * coverMul;
                }
                if (cover <= 0.001) {
                  return half4(0.0);
                }
              } else if (sideMode < -1.5) {
                cover = (1.0 - smoothstep(-coverZone, coverZone, d)) * coverMul;
                if (cover <= 0.001) {
                  return half4(0.0);
                }
              } else {
                if (d > coverZone + 6.0) {
                  return half4(0.0);
                }
              }

              float u = d / (uLens.y * (d > 0.0 ? 0.85 : 1.5));
              float lift = exp(-u * u);
              float r = max(length(q), 1.0);
              float held = heldAt(p);
              float2 swollen = n * energy * uLens.x * lift * held;
              float2 inward = q / r;
              float2 s = p - swollen + inward * gravity * exp(-r / 900.0) * held;
              float2 split = (swollen - inward * gravity * exp(-r / 240.0) * held) * uLens.z;

              float nearW = 0.0;
              float farW = 0.0;
              if (energy > 0.0 && uBlur.x > 0.0 && uFlags.y > 0.5) {
                float2 sn;
                float2 sq;
                float ds = fieldAt(s, sn, sq);
                float su = ds / (ds > 0.0 ? uBlur.z : uBlur.w);
                float radius = energy * uBlur.x * exp(-su * su) * heldAt(s);
                nearW = easeSmooth(0.0, uBlur.y, radius);
                farW = easeSmooth(uBlur.y, uBlur.x, radius);
              }

              if (dot(split, split) < 0.0625) {
                return layerAt(s, nearW, farW) * half(cover);
              }

              half4 c0 = layerAt(s - split, nearW, farW);
              half4 c1 = layerAt(s - split * 0.5, nearW, farW);
              half4 c2 = layerAt(s, nearW, farW);
              half4 c3 = layerAt(s + split * 0.5, nearW, farW);
              half4 c4 = layerAt(s + split, nearW, farW);
              half3 rgb = c0.rgb * half3(0.571, 0.0, 0.0)
                + c1.rgb * half3(0.343, 0.206, 0.0)
                + c2.rgb * half3(0.086, 0.588, 0.086)
                + c3.rgb * half3(0.0, 0.206, 0.343)
                + c4.rgb * half3(0.0, 0.0, 0.571);
              half a = (c0.a + c1.a + c2.a + c3.a + c4.a) * half(0.2);
              return half4(rgb, a) * half(cover);
            }
        """

        private const val BAND_SHADER_AGSL = """
            uniform float2 uSize;
            uniform float uDensity;
            uniform float4 uFront;   // originX, originY, radius, cardCornerR
            uniform float4 uBox;     // boxHalfX, boxHalfY, holdRelease, -
            uniform float4 uCard;    // cardCx, cardCy, cardHalfX, cardHalfY
            uniform float4 uShape;   // time, swell, lead, tail
            uniform float4 uGlow;    // glow, rim, clearRadius, hairClearRadius
            uniform float4 uRimA;    // strength, rimWidth, rimSoftness, cornerRadius
            uniform float4 uRimB;    // unevenness, pulse, hairlineWidth, hairlineOpacity
            uniform float4 uRimC;    // hairlineWhite, paletteCount, -, -
            uniform float4 uP0;
            uniform float4 uP1;
            uniform float4 uP2;
            uniform float4 uP3;
            uniform float4 uP4;
            uniform float4 uP5;
            uniform float4 uP6;
            uniform float4 uP7;
            uniform float4 uAhead;
            uniform float4 uBehind;
            uniform float4 uL0;
            uniform float4 uL1;
            uniform float4 uL2;
            uniform float4 uL3;
            uniform float4 uL4;
            uniform float4 uL5;
            uniform float4 uL6;

            $SHARED_FIELD_AGSL

            float sq(float x) {
              return x * x;
            }

            float3 oklabToSRGB(float3 c) {
              float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
              float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
              float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
              l = l * l * l;
              m = m * m * m;
              s = s * s * s;
              float3 lin = clamp(float3(
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
              ), 0.0, 1.0);
              float3 hi = 1.055 * pow(max(lin, float3(1e-6)), float3(1.0 / 2.4)) - 0.055;
              return mix(12.92 * lin, hi, step(float3(0.0031308), lin));
            }

            float continuousRectSDF(float2 p, float2 halfSize, float radius) {
              float a = min(radius * 1.528665, min(halfSize.x, halfSize.y));
              if (a <= 1e-3) {
                return max(abs(p.x) - halfSize.x, abs(p.y) - halfSize.y);
              }
              float2 q = abs(p) - (halfSize - a);
              if (q.x <= 0.0 || q.y <= 0.0) {
                return max(abs(p.x) - halfSize.x, abs(p.y) - halfSize.y);
              }
              const float n = 3.26;
              float2 u = max(q / a, float2(1e-5));
              float norm = pow(pow(u.x, n) + pow(u.y, n), 1.0 / n);
              float2 g = pow(max(u / max(norm, 1e-4), float2(1e-5)), float2(n - 1.0));
              return (norm - 1.0) * a / max(length(g), 1e-3);
            }

            float2 perimeterAt(float2 p, float2 halfSize, float r) {
              float ex = max(halfSize.x - r, 0.0);
              float ey = max(halfSize.y - r, 0.0);
              float arc = 0.5 * wavePi * r;
              float P = max(4.0 * (ex + ey + arc), 1.0);
              float ax = abs(p.x);
              float ay = abs(p.y);
              if (ax > ex && ay > ey) {
                float2 v = p - float2(p.x >= 0.0 ? ex : -ex, p.y >= 0.0 ? ey : -ey);
                float a;
                float base;
                if (p.x >= 0.0 && p.y < 0.0) {
                  a = atan(v.x, max(-v.y, 1e-5));
                  base = ex;
                } else if (p.x >= 0.0) {
                  a = atan(v.y, max(v.x, 1e-5));
                  base = ex + arc + 2.0 * ey;
                } else if (p.y >= 0.0) {
                  a = atan(-v.x, max(v.y, 1e-5));
                  base = 3.0 * ex + 2.0 * arc + 2.0 * ey;
                } else {
                  a = atan(-v.y, max(-v.x, 1e-5));
                  base = 3.0 * ex + 3.0 * arc + 4.0 * ey;
                }
                return float2(base + r * clamp(a, 0.0, 0.5 * wavePi), P);
              }
              bool side = ax > ex ? true : (ay > ey ? false : (halfSize.x - ax) < (halfSize.y - ay));
              if (side) {
                return float2(p.x >= 0.0 ? ex + arc + (p.y + ey) : 3.0 * ex + 3.0 * arc + 2.0 * ey + (ey - p.y), P);
              }
              return float2(p.y < 0.0 ? (p.x >= 0.0 ? p.x : P + p.x) : ex + 2.0 * arc + 2.0 * ey + (ex - p.x), P);
            }

            float lumpAt(float s, float P, float4 lump) {
              float ds = s - lump.x;
              ds -= P * floor(ds / P + 0.5);
              return lump.z * exp(-sq(ds / max(lump.y, 1.0)));
            }

            float rimThickness(float s, float P) {
              float t = 0.08 + lumpAt(s, P, uL0) + lumpAt(s, P, uL1) + lumpAt(s, P, uL2) + lumpAt(s, P, uL3)
                + lumpAt(s, P, uL4) + lumpAt(s, P, uL5) + lumpAt(s, P, uL6);
              return t / 1.0498;
            }

            float dither(float2 p) {
              return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))) - 0.5;
            }

            float3 paletteColor(float k) {
              if (k < 0.5) return uP0.xyz;
              if (k < 1.5) return uP1.xyz;
              if (k < 2.5) return uP2.xyz;
              if (k < 3.5) return uP3.xyz;
              if (k < 4.5) return uP4.xyz;
              if (k < 5.5) return uP5.xyz;
              if (k < 6.5) return uP6.xyz;
              return uP7.xyz;
            }

            float3 paletteAt(float u) {
              float count = max(uRimC.y, 1.0);
              float x = fract(u) * count;
              float i = min(floor(x), count - 1.0);
              float j = mod(i + 1.0, count);
              float m = fract(x);
              m = m * m * (3.0 - 2.0 * m);
              return mix(paletteColor(i), paletteColor(j), m);
            }

            half4 main(float2 fragCoord) {
              float2 p = fragCoord / uDensity;
              float glow = uGlow.x;
              float rim = uGlow.y;
              float lead = uShape.z;
              float tail = uShape.w;
              float strength = uRimA.x;
              float rimWidth = uRimA.y;
              float rimSoftness = uRimA.z;
              float cornerRadius = uRimA.w;
              float unevenness = uRimB.x;
              float pulse = uRimB.y;
              float hairWidth = uRimB.z;
              float hairOpacity = uRimB.w;
              float hairWhite = uRimC.x;

              float2 c = p - uSize * 0.5;
              float edgeDistance = continuousRectSDF(c, uSize * 0.5, cornerRadius);
              float inset = max(-edgeDistance, 0.0);
              float inside = 1.0 - smoothstep(0.0, 0.75, edgeDistance);
              bool nearEdge = rim > 0.0 && inside > 0.0
                && inset < max(rimWidth * 2.2 * (1.0 + 1.9 * unevenness), hairWidth * 0.5 + 0.75);
              if (glow <= 0.0 && !nearEdge) {
                return half4(0.0);
              }

              float2 n;
              float2 q;
              float d = fieldAt(p, n, q);
              float time = uShape.x;

              float turn = (abs(q.x) + abs(q.y) > 1e-4) ? atan(q.y, q.x) : 0.0;
              float lobes = 0.5 + 0.5 * sin(2.0 * turn + time * 1.4) * cos(turn - time * 0.7 + 1.2);
              float bandA = 0.0;
              if (glow > 0.0 && d < lead) {
                if (d >= 0.0) {
                  float k = 1.0 - d / max(lead, 1.0);
                  bandA = k * k * k * (k * (k * 6.0 - 15.0) + 10.0);
                } else {
                  bandA = pow(max(1.0 - clamp(-d / max(tail * mix(0.6, 1.3, lobes), 1.0), 0.0, 1.0), 1e-5), 2.3);
                }
                // 蓄力阶段保留卡片内部清晰, 柔和光晕紧贴卡片外沿; 放波后全屏平滑释放
                float cardD = roundedBoxSDF(p - uCard.xy, uCard.zw, uFront.w);
                float cardOuter = smoothstep(-2.0, 6.0, cardD);
                float bandMask = mix(cardOuter, 1.0, uBox.z);
                bandA *= glow * strength * mix(0.62, 1.0, exp(-inset / 140.0)) * bandMask;
              }

              float around = (abs(c.x) + abs(c.y) > 1e-4) ? atan(c.y / max(uSize.y, 1.0), c.x / max(uSize.x, 1.0)) : 0.0;
              float rimA = 0.0;
              float hairA = 0.0;
              float shimmer = 0.5;
              if (nearEdge) {
                float2 edge = perimeterAt(c, uSize * 0.5, cornerRadius);
                float thickness = 1.0;
                if (unevenness > 0.0) {
                  thickness = max(1.0 + (rimThickness(edge.x, edge.y) - 1.0) * unevenness, 0.04);
                }
                float hug = exp(-sq(inset / max(rimWidth * thickness, 0.5)));
                float reached = 1.0 - smoothstep(-lead * 0.7, lead * 0.5, d);
                float kept = smoothstep(-rimSoftness, rimSoftness, d + (uFront.z - uGlow.z));
                shimmer = 0.5 + 0.5 * sin(time * 3.4 - edge.x / edge.y * 4.0 * wavePi);
                rimA = hug * reached * kept * rim * mix(1.0, 0.7 + 0.3 * shimmer, pulse) * inside;

                if (hairWidth > 0.0) {
                  float line = 1.0 - smoothstep(hairWidth * 0.5, hairWidth * 0.5 + 0.75, inset);
                  float hairKept = smoothstep(-rimSoftness, rimSoftness, d + (uFront.z - uGlow.w));
                  hairA = line * reached * hairKept * rim * hairOpacity
                    * mix(0.5, 1.0, smoothstep(0.1, 1.5, thickness))
                    * mix(1.0, 0.55 + 0.45 * shimmer, pulse) * inside;
                }
              }

              float a = 1.0 - (1.0 - bandA) * (1.0 - rimA);
              float total = 1.0 - (1.0 - a) * (1.0 - hairA);
              if (total <= 0.0005) {
                return half4(0.0);
              }

              float flow = 0.62 + turn / (2.0 * wavePi) + time * 0.13 + d * 0.0015;
              float3 lab = paletteAt(flow);
              lab = mix(lab, uAhead.xyz, 0.5 * exp(-sq((d - 30.0) / 50.0)));
              lab = mix(lab, uBehind.xyz, 0.5 * exp(-sq((d + 84.0) / 72.0)));
              lab.yz *= 1.18;
              lab = mix(lab, float3(0.99, 0.0, 0.0), 0.65 * exp(-sq((d + 8.0) / 26.0)));

              float3 rimLab = paletteAt(around / (2.0 * wavePi) + time * (0.12 + 0.25 * pulse) + inset * 0.0012);
              rimLab.yz *= 1.08;
              lab = mix(lab, rimLab, rimA / (rimA + bandA + 1e-4));

              float3 rgb = oklabToSRGB(lab);
              if (hairA > 0.0) {
                float3 hair = mix(oklabToSRGB(rimLab), float3(1.0), hairWhite * (0.5 + 0.5 * shimmer));
                rgb = (rgb * a * (1.0 - hairA) + hair * hairA) / total;
              }

              float noise = dither(p) * (2.0 / 255.0);
              float outA = clamp(total + noise, 0.0, 1.0);
              float3 outRgb = clamp(rgb * outA, float3(0.0), float3(outA));
              return half4(outRgb, outA);
            }
        """
    }
}
