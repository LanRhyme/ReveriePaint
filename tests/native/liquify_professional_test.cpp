// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/cpp/LiquifyInverseField.h"
#include <cassert>
#include <cstdio>

int main() {
    LiquifyInverseField f;
    for (float hardness : {0.f, .5f, 1.f}) for (int mode = 0; mode < 7; ++mode) {
        f.reset(256, 256, 1);
        f.apply(128, 128, 132, 128, .8f, 80, mode, true, hardness);
        for (int y = 0; y < 256; ++y) for (int x = 0; x < 256; ++x) {
            auto p = f.sample(x + .5f, y + .5f);
            assert(std::isfinite(p.x) && std::isfinite(p.y));
            if (std::hypot(x + .5f - 130, y + .5f - 128) > 44) assert(p.x == 0 && p.y == 0);
        }
        auto p = f.sample(130, 128);
        if (mode == 0) assert(p.x > 2.9f && std::abs(p.y) < .001f);
        if (mode == 5) assert(p.y < -2.9f && std::abs(p.x) < .001f);
        if (mode == 6) assert(p.y > 2.9f && std::abs(p.x) < .001f);
    }
    assert(LiquifyInverseField::mask(30, 0, 40, .85f) == 1.f);
    assert(LiquifyInverseField::mask(30, 0, 40) < .2f);
    for (int mode = 1; mode <= 4; ++mode) {
        f.reset(256, 256, 1);
        for (int i = 0; i < 60; ++i) f.apply(128,128,128,128,.016f,80,mode,true,.5f);
        auto p = f.sample(140, 128);
        if (mode == 1) assert(p.x > 0);
        if (mode == 2) assert(p.x < 0);
        if (mode == 3) assert(p.y > 0);
        if (mode == 4) assert(p.y < 0);
    }
    // Push composes the original displacement without the former elastic decay.
    f.reset(256,256,1);
    f.apply(120,128,124,128,.8f,80,0,true,1);
    const auto before = f.sample(124,128);
    f.apply(124,128,128,128,.8f,80,0,true,1);
    const auto after = f.sample(128,128);
    assert(after.x > before.x * 1.8f);
    std::puts("professional liquify: seven modes, cursor support, hardness, hold and accumulated push PASS");
}
