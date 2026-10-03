// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/cpp/LiquifyPixelSampling.h"
#include "../../app/src/main/cpp/LiquifyInverseField.h"
#include <cassert>
#include <cstdio>

int main() {
    using Field = LiquifyInverseField;
    assert(Field::stepForDocument(512, 512, 8) == 1);
    assert(Field::stepForDocument(512, 512, 31) == 1);
    assert(Field::stepForDocument(512, 512, 32) == 2);
    assert(Field::stepForDocument(512, 512, 60) == 2);
    assert(Field::stepForDocument(512, 512, 68) == 4);
    assert(Field::stepForDocument(512, 512, 200) == 8);
    assert(Field::stepForDocument(512, 512, 300) == 16);
    assert(Field::stepForDocument(2048, 2048, 8) == 2);
    assert(Field::stepForDocument(4096, 4096, 8) == 4);
    assert(Field::stepForDocument(8192, 8192, 8) == 8);
    assert(Field::stepForDocument(INT32_MAX, INT32_MAX, 8) == 16);
    uint8_t zero = 0, white = 255, out[4] = {};
    LiquifyPixelSampling::bilinear(&zero, &white, &white, &zero, .5f, .5f, 1, out);
    assert(out[0] == 128);
    for (int coverage = 0; coverage <= 255; ++coverage) {
        const auto value = uint8_t(coverage);
        LiquifyPixelSampling::bilinear(&value, &value, &value, &value, .37f, .81f, 1, out);
        assert(out[0] == value);
        LiquifyPixelSampling::bilinear(&value, &zero, &value, &zero, .5f, .7f, 1, out);
        assert(out[0] == uint8_t(std::lround(coverage * .5f)));
    }
    const uint8_t red[4] = {0, 0, 255, 255};
    const uint8_t hiddenWhite[4] = {255, 255, 255, 0};
    LiquifyPixelSampling::bilinear(red, hiddenWhite, red, hiddenWhite, .5f, .5f, 4, out);
    assert(out[0] == 0 && out[1] == 0 && out[2] == 255 && out[3] == 128);
    std::puts("PASS Alpha8 coverage/edges and RGB8 associated-color sampling");
}
