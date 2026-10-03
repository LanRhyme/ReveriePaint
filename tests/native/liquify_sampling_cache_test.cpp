// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/cpp/LiquifyInverseField.h"
#include <cassert>
#include <chrono>
#include <cstdio>

int main()
{
    LiquifyInverseField field;
    for (int step : {1, 2, 4}) {
        for (int mode = 0; mode <= 4; ++mode) {
            field.reset(257, 193, step);
            for (int i = 0; i < 12; ++i)
                field.apply(12.f + i*17, 5.f + i*13, 19.f + i*17, 8.f + i*13, .8f, 24.f, mode);
            LiquifyInverseField::SamplingCache cache;
            for (int y = -2; y <= 195; ++y) {
                for (int x = -2; x <= 259; ++x) {
                    const auto a = field.sample(x + .37f, y + .81f);
                    const auto b = field.sample(x + .37f, y + .81f, &cache);
                    assert(a.x == b.x && a.y == b.y);
                }
            }
        }
    }
    field.reset(1024, 1024);
    for (int i = 0; i < 20; ++i)
        field.apply(360.f+i*4, 480.f, 364.f+i*4, 482.f, .9f, 100.f, 0);
    volatile double sums[2] = {};
    double elapsed[2] = {};
    for (int pass = 0; pass < 2; ++pass) {
        LiquifyInverseField::SamplingCache cache;
        const auto start = std::chrono::steady_clock::now();
        for (int repeat = 0; repeat < 8; ++repeat)
            for (int y = 0; y < 1024; ++y)
                for (int x = 0; x < 1024; ++x) {
                    const auto p = field.sample(x+.5f, y+.5f, pass ? &cache : nullptr);
                    sums[pass] += p.x + p.y;
                }
        elapsed[pass] = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now()-start).count();
    }
    assert(sums[0] == sums[1]);
    std::printf("PASS cached/uncached exact equality: five modes, three resolutions, edges and tile seams\n"
        "8M samples: uncached %.2f ms, cached %.2f ms, ratio %.3f\n",
        elapsed[0], elapsed[1], elapsed[1]/elapsed[0]);
}
