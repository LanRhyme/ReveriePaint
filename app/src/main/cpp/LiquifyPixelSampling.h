// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "PixelAlpha.h"

// RGB8 devices use associated-color interpolation; Alpha8 masks are scalar
// coverage and must not be interpreted as four-byte color pixels.
namespace LiquifyPixelSampling {
inline void bilinear(const uint8_t *a, const uint8_t *b, const uint8_t *c,
                     const uint8_t *d, float x, float y, int pixelSize, uint8_t *out) {
    if (pixelSize == 1) {
        const float top = a[0] + (float(b[0]) - a[0]) * x;
        const float bottom = c[0] + (float(d[0]) - c[0]) * x;
        out[0] = uint8_t(std::clamp(std::lround(top + (bottom - top) * y), 0l, 255l));
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
        out[0] = uint8_t(std::clamp(std::lround(acc), 0l, 255l));
        return;
    }
    float alpha = 0.f;
    float colors[3] = {0.f, 0.f, 0.f};
    for (int j = 0; j < 4; ++j) {
        const float wj = wy[j];
        if (wj == 0.f) continue;
        for (int i = 0; i < 4; ++i) {
            const float w = wj * wx[i];
            if (w == 0.f) continue;
            const uint8_t *s = p[j * 4 + i];
            const float wa = w * float(s[3]);
            alpha += wa;
            for (int ch = 0; ch < 3; ++ch) colors[ch] += wa * float(s[ch]);
        }
    }
    if (alpha <= 0.5f) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    out[3] = uint8_t(std::clamp(std::lround(alpha), 0l, 255l));
    for (int ch = 0; ch < 3; ++ch) {
        const float v = colors[ch] / alpha;
        out[ch] = uint8_t(std::clamp(std::lround(v), 0l, 255l));
    }
}
} // namespace LiquifyPixelSampling
