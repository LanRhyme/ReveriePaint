// SPDX-License-Identifier: GPL-3.0-or-later
// 液化重采样核的性质测试 (host, 无 Qt/Android 依赖):
//  1) 线性剖面必须被精确还原 (Catmull-Rom 对一次函数是恒等) —— 否则形变会引入明暗带;
//  2) 同一步长位移下, 三次卷积的边缘保持明显优于双线性 (这是"越涂越糊"的解药);
//  3) 全透明邻域输出全透明 (不会漏出黑边)。
#include "LiquifyPixelSampling.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace {
using Pixel = unsigned char[4];

// 造一个 RGBA(BGRA 顺序, 但本测试只关心亮度对称) 的竖直边缘图: 左半 20, 右半 220
void fillEdge(Pixel *px, int w, int h, int edge) {
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const unsigned char v = x < edge ? 20 : 220;
            px[y * w + x][0] = v;
            px[y * w + x][1] = v;
            px[y * w + x][2] = v;
            px[y * w + x][3] = 255;
        }
    }
}

const unsigned char *at(const Pixel *px, int w, int h, int x, int y) {
    x = x < 0 ? 0 : (x >= w ? w - 1 : x);
    y = y < 0 ? 0 : (y >= h ? h - 1 : y);
    return px[y * w + x];
}

unsigned char sampleBilinear(const Pixel *px, int w, int h, float u, float v) {
    const int ix = int(std::floor(u)), iy = int(std::floor(v));
    const float fx = u - ix, fy = v - iy;
    unsigned char out[4];
    LiquifyPixelSampling::bilinear(at(px, w, h, ix, iy), at(px, w, h, ix + 1, iy),
                                   at(px, w, h, ix, iy + 1), at(px, w, h, ix + 1, iy + 1),
                                   fx, fy, 4, out);
    return out[0];
}

unsigned char sampleCubic(const Pixel *px, int w, int h, float u, float v) {
    const int ix = int(std::floor(u)), iy = int(std::floor(v));
    const unsigned char *tap[16];
    for (int j = 0; j < 4; ++j)
        for (int i = 0; i < 4; ++i) tap[j * 4 + i] = at(px, w, h, ix - 1 + i, iy - 1 + j);
    unsigned char out[4];
    LiquifyPixelSampling::bicubic16(tap, u - ix, v - iy, 4, out);
    return out[0];
}

// 连续做 n 次 0.4px 位移后, 边缘处的最大相邻差值 (= 清晰度指标)
int edgeContrast(const Pixel *px, int w, int h, int row, bool cubic, int passes) {
    unsigned char cur[64 * 4];
    const int n = w;
    for (int x = 0; x < n; ++x) std::memcpy(&cur[x * 4], px[row * w + x], 4);
    Pixel work[64];
    for (int i = 0; i < n; ++i) std::memcpy(work[i], &cur[i * 4], 4);
    for (int p = 0; p < passes; ++p) {
        for (int x = 0; x < n; ++x) {
            const float u = float(x) - 0.4f;
            const unsigned char v = cubic ? sampleCubic(work, n, 1, u, 0.f)
                                          : sampleBilinear(work, n, 1, u, 0.f);
            work[x][0] = v; work[x][1] = v; work[x][2] = v; work[x][3] = 255;
        }
    }
    int peak = 0;
    for (int x = 1; x < n; ++x) peak = std::max(peak, std::abs(int(work[x][0]) - int(work[x - 1][0])));
    return peak;
}
} // namespace

int main() {
    constexpr int W = 64, H = 8;
    Pixel img[W * H];
    fillEdge(img, W, H, W / 2);

    // 1) 线性剖面恒等: 构造 0,4,8,...,252 的斜坡, 三点中心采样应还原精确值
    {
        Pixel ramp[W * H];
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                const unsigned char v = (unsigned char)(x * 4 % 256);
                ramp[y * W + x][0] = v; ramp[y * W + x][1] = v;
                ramp[y * W + x][2] = v; ramp[y * W + x][3] = 255;
            }
        // 斜坡在 4px 周期内是线性的: 在 10.25 / 11.5 / 12.75 处采样
        for (int k = 0; k < 3; ++k) {
            const float u = 10.f + 0.25f * float(k + 1);
            const unsigned char got = sampleCubic(ramp, W, H, u, 2.f);
            const int expect = int(std::lround(4.f * u)) % 256;
            if (std::abs(int(got) - expect) > 1) {
                std::printf("FAIL linear ramp: u=%.2f got=%d expect=%d\n", u, got, expect);
                return 1;
            }
        }
    }

    // 2) 反复位移后的清晰度: 三次卷积必须优于双线性
    {
        const int b = edgeContrast(img, W, H, 0, false, 4);
        const int c = edgeContrast(img, W, H, 0, true, 4);
        std::printf("edge contrast after 4x0.4px: bilinear=%d cubic=%d\n", b, c);
        if (!(c > b)) {
            std::printf("FAIL: cubic should keep edges sharper than bilinear\n");
            return 1;
        }
    }

    // 3) 全透明邻域 → 全透明 (不能漏出黑边)
    {
        Pixel clear[W * H];
        std::memset(clear, 0, sizeof(clear));
        const int ix = W / 2, iy = H / 2;
        const unsigned char *tap[16];
        for (int j = 0; j < 4; ++j)
            for (int i = 0; i < 4; ++i) tap[j * 4 + i] = at(clear, W, H, ix - 1 + i, iy - 1 + j);
        unsigned char out[4] = {9, 9, 9, 9};
        LiquifyPixelSampling::bicubic16(tap, 0.3f, 0.7f, 4, out);
        if (out[0] || out[1] || out[2] || out[3]) {
            std::printf("FAIL: transparent neighborhood produced %d,%d,%d,%d\n", out[0], out[1], out[2], out[3]);
            return 1;
        }
    }

    std::printf("liquify_sampling_test OK\n");
    return 0;
}
