// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/cpp/LiquifyInverseField.h"
#include "liquify_kernel_reference.h"
#include <cassert>
#include <chrono>
#include <cstdio>

template<class Field> double run(Field &field, int mode, int step)
{
    field.reset(257, 193, step);
    const auto begin = std::chrono::steady_clock::now();
    for (int i = 0; i < 100; ++i) {
        const float x = 1.f + (i * 17 % 256), y = 1.f + (i * 13 % 192);
        field.apply(x, y, x + (i % 2 ? 7.f : -7.f), y + 3.f, .8f, 24.f, mode);
    }
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now()-begin).count();
}
int main()
{
    double oldMs = 0, newMs = 0;
    for (int step : {1, 2, 4}) for (int mode = 0; mode < 5; ++mode) {
        LiquifyKernelReference reference;
        LiquifyInverseField optimized;
        oldMs += run(reference, mode, step);
        newMs += run(optimized, mode, step);
        for (int y = -2; y < 195; ++y) for (int x = -2; x < 259; ++x) {
            const auto a = reference.sample(x + .37f, y + .81f);
            const auto b = optimized.sample(x + .37f, y + .81f);
            assert(a.x == b.x && a.y == b.y);
        }
    }
    std::printf("PASS five modes, edges, reversals, tile seams, step 1/2/4: exact equality\n"
        "1500 dabs: reference %.2f ms, optimized %.2f ms, ratio %.3f\n", oldMs,newMs,newMs/oldMs);
}
