// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

// Krita RGB8 devices store straight BGRA. Android Bitmaps and GLES surfaces
// consume premultiplied RGBA. Do not confuse a channel swap with this conversion.
namespace PixelAlpha {
inline uint8_t premultiply(unsigned c, unsigned a) { return uint8_t((c * a + 127) / 255); }
inline uint8_t unpremultiply(unsigned c, unsigned a) {
    return a ? uint8_t(std::min(255u, (c * 255 + a / 2) / a)) : 0;
}
inline void toDisplay(const uint8_t *s, uint8_t *d, size_t count) {
    size_t i = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    for (; i + 8 <= count; i += 8) {
        const uint8x8x4_t p = vld4_u8(s + i * 4);
        uint8x8x4_t out;
        for (int c = 0; c < 3; ++c) {
            uint16x8_t v = vmull_u8(p.val[2 - c], p.val[3]);
            v = vaddq_u16(v, vdupq_n_u16(128));
            v = vaddq_u16(v, vshrq_n_u16(v, 8));
            out.val[c] = vshrn_n_u16(v, 8);
        }
        out.val[3] = p.val[3];
        vst4_u8(d + i * 4, out);
    }
#endif
    for (; i < count; ++i) {
        const unsigned b = s[i*4], g = s[i*4+1], r = s[i*4+2], a = s[i*4+3];
        d[i*4] = premultiply(r,a); d[i*4+1] = premultiply(g,a);
        d[i*4+2] = premultiply(b,a); d[i*4+3] = uint8_t(a);
    }
}
inline void toDisplayRows(const uint8_t *s, int ss, uint8_t *d, int ds, int w, int h) {
    for (int y=0; y<h; ++y) toDisplay(s + size_t(y)*ss, d + size_t(y)*ds, size_t(w));
}
inline void toDevice(const uint8_t *s, uint8_t *d, size_t count) {
    for (size_t i=0; i<count; ++i) {
        const unsigned r=s[i*4], g=s[i*4+1], b=s[i*4+2], a=s[i*4+3];
        d[i*4]=unpremultiply(b,a); d[i*4+1]=unpremultiply(g,a);
        d[i*4+2]=unpremultiply(r,a); d[i*4+3]=uint8_t(a);
    }
}
// Filter associated colors, then return straight BGRA for a Krita device.
// A transparent texel's hidden RGB must never contaminate visible neighbors.
inline void bilinear(const uint8_t *a, const uint8_t *b, const uint8_t *c,
                     const uint8_t *d, float x, float y, uint8_t *out) {
    // Empty canvas dominates sparse artwork. Hidden RGB must stay unobservable.
    if ((a[3] | b[3] | c[3] | d[3]) == 0) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    const float weights[4]={(1-x)*(1-y),x*(1-y),(1-x)*y,x*y};
    const uint8_t *p[4]={a,b,c,d};
    float alpha=0; float colors[3]={0,0,0};
    for (int i=0;i<4;++i) {
        const float wa=weights[i]*p[i][3]; alpha+=wa;
        for (int ch=0;ch<3;++ch) colors[ch]+=wa*p[i][ch];
    }
    out[3]=uint8_t(std::clamp(std::lround(alpha),0l,255l));
    for (int ch=0;ch<3;++ch)
        out[ch]=out[3] ? uint8_t(std::clamp(std::lround(colors[ch]/alpha),0l,255l)) : 0;
}
} // namespace PixelAlpha
