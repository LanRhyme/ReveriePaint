// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <cstring>
#include <cstddef>
namespace PixelRegion {
// Replace, including alpha=0. Used before any viewport resampling or source-over.
inline bool replace(uint8_t *dst, int dw, int dh, int x, int y,
                    const uint8_t *src, int w, int h) {
    if (!dst || !src || x<0 || y<0 || w<=0 || h<=0 || x>dw || y>dh || w>dw-x || h>dh-y) return false;
    for(int row=0;row<h;++row)
        std::memcpy(dst+(size_t(row+y)*dw+x)*4,src+size_t(row)*w*4,size_t(w)*4);
    return true;
}
}
