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
} // namespace LiquifyPixelSampling
