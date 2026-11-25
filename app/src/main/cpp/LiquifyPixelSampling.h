// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "PixelAlpha.h"

// RGB8 devices use associated-color interpolation; Alpha8 masks are scalar
// coverage and must not be interpreted as four-byte color pixels.
namespace LiquifyPixelSampling {
inline uint8_t clampRoundU8(float v) {
    if (!(v > 0.f)) return 0;
    if (v >= 255.f) return 255;
    return uint8_t(v + 0.5f);
}

inline void bilinear(const uint8_t *a, const uint8_t *b, const uint8_t *c,
                     const uint8_t *d, float x, float y, int pixelSize, uint8_t *out) {
    if (pixelSize == 1) {
        const float top = a[0] + (float(b[0]) - a[0]) * x;
        const float bottom = c[0] + (float(d[0]) - c[0]) * x;
        out[0] = clampRoundU8(top + (bottom - top) * y);
    } else {
        PixelAlpha::bilinear(a, b, c, d, x, y, out);
    }
}

/**
 * Catmull-Rom (三次卷积) 权重: 采样点落在 px[1] 与 px[2] 之间, f∈[0,1)。
 * 负权重是有意的 —— 它把高频补回来, 这正是双线性"每过一遍就糊一点"的解药。
 */
inline void catmullRomWeights(float f, float w[4]) {
    const float f2 = f * f;
    const float f3 = f2 * f;
    w[0] = -0.5f * f3 + f2 - 0.5f * f;
    w[1] = 1.5f * f3 - 2.5f * f2 + 1.0f;
    w[2] = -1.5f * f3 + 2.0f * f2 + 0.5f * f;
    w[3] = 0.5f * f3 - 0.5f * f2;
}

/**
 * 16 抽头三次卷积重采样 (p = 4x4 邻域, 行优先: p[y*4+x])。
 *
 * 与双线性共用同一套 alpha 口径: 先按 alpha 加权累加颜色(相当于预乘域), 再除回直通色。
 * 为什么不用双线性: 液化每次手势都会把结果落回图层, 下一次手势从落盘结果重新采样 ——
 * 双线性每过一遍就把边缘抹平 0.5~1px, 涂几次线条就"糊了"; Catmull-Rom 单遍基本无损。
 * p 的越界像素由调用方按**边缘复制**准备好 (否则四角会有半透黑边)。
 */
inline void bicubic16(const uint8_t *const p[16], float x, float y, int pixelSize, uint8_t *out) {
    float wx[4];
    float wy[4];
    catmullRomWeights(x, wx);
    catmullRomWeights(y, wy);
    if (pixelSize == 1) {
        float acc = 0.f;
        for (int j = 0; j < 4; ++j) {
            float row = 0.f;
            for (int i = 0; i < 4; ++i) row += wx[i] * float(p[j * 4 + i][0]);
            acc += wy[j] * row;
        }
        out[0] = clampRoundU8(acc);
        return;
    }
    float alpha = 0.f;
    float c0 = 0.f, c1 = 0.f, c2 = 0.f;
    for (int j = 0; j < 4; ++j) {
        const float wj = wy[j];
        if (wj == 0.f) continue;
        float rA = 0.f, r0 = 0.f, r1 = 0.f, r2 = 0.f;
        for (int i = 0; i < 4; ++i) {
            const uint8_t *s = p[j * 4 + i];
            const float wa = wx[i] * float(s[3]);
            rA += wa;
            r0 += wa * float(s[0]);
            r1 += wa * float(s[1]);
            r2 += wa * float(s[2]);
        }
        alpha += wj * rA;
        c0 += wj * r0;
        c1 += wj * r1;
        c2 += wj * r2;
    }
    if (alpha <= 0.5f) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    out[3] = clampRoundU8(alpha);
    const float invAlpha = 1.f / alpha;
    out[0] = clampRoundU8(c0 * invAlpha);
    out[1] = clampRoundU8(c1 * invAlpha);
    out[2] = clampRoundU8(c2 * invAlpha);
}

// 内部无越界像素专用的行指针快路径 (4 字节直通 BGRA, rK 指向第 K 行的首像素)
inline void bicubic16RowPtr(const uint8_t *r0, const uint8_t *r1, const uint8_t *r2, const uint8_t *r3,
                            float x, float y, uint8_t *out) {
    const uint8_t aOr = (r0[3] | r0[7] | r0[11] | r0[15]) |
                        (r1[3] | r1[7] | r1[11] | r1[15]) |
                        (r2[3] | r2[7] | r2[11] | r2[15]) |
                        (r3[3] | r3[7] | r3[11] | r3[15]);
    if (aOr == 0) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    float wx[4];
    float wy[4];
    catmullRomWeights(x, wx);
    catmullRomWeights(y, wy);
    const uint8_t *rows[4] = {r0, r1, r2, r3};
    const uint8_t aAnd = (r0[3] & r0[7] & r0[11] & r0[15]) &
                         (r1[3] & r1[7] & r1[11] & r1[15]) &
                         (r2[3] & r2[7] & r2[11] & r2[15]) &
                         (r3[3] & r3[7] & r3[11] & r3[15]);
    if (aAnd == 255) {
        float c0 = 0.f, c1 = 0.f, c2 = 0.f;
        for (int j = 0; j < 4; ++j) {
            const float wj = wy[j];
            if (wj == 0.f) continue;
            const uint8_t *s = rows[j];
            const float row0 = wx[0] * s[0] + wx[1] * s[4] + wx[2] * s[8] + wx[3] * s[12];
            const float row1 = wx[0] * s[1] + wx[1] * s[5] + wx[2] * s[9] + wx[3] * s[13];
            const float row2 = wx[0] * s[2] + wx[1] * s[6] + wx[2] * s[10] + wx[3] * s[14];
            c0 += wj * row0;
            c1 += wj * row1;
            c2 += wj * row2;
        }
        out[0] = clampRoundU8(c0);
        out[1] = clampRoundU8(c1);
        out[2] = clampRoundU8(c2);
        out[3] = 255;
        return;
    }
    float alpha = 0.f, c0 = 0.f, c1 = 0.f, c2 = 0.f;
    for (int j = 0; j < 4; ++j) {
        const uint8_t *rj = rows[j];
        if ((rj[3] | rj[7] | rj[11] | rj[15]) == 0) continue;
        const float wj = wy[j];
        if (wj == 0.f) continue;
        float rA = 0.f, acc0 = 0.f, acc1 = 0.f, acc2 = 0.f;
        for (int i = 0; i < 4; ++i) {
            const uint8_t *s = rj + i * 4;
            const float wa = wx[i] * float(s[3]);
            rA += wa;
            acc0 += wa * float(s[0]);
            acc1 += wa * float(s[1]);
            acc2 += wa * float(s[2]);
        }
        alpha += wj * rA;
        c0 += wj * acc0;
        c1 += wj * acc1;
        c2 += wj * acc2;
    }
    if (alpha <= 0.5f) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    out[3] = clampRoundU8(alpha);
    const float invAlpha = 1.f / alpha;
    out[0] = clampRoundU8(c0 * invAlpha);
    out[1] = clampRoundU8(c1 * invAlpha);
    out[2] = clampRoundU8(c2 * invAlpha);
}
} // namespace LiquifyPixelSampling
