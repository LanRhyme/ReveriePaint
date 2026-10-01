// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../app/src/main/cpp/PixelRegion.h"
#include "../../app/src/main/cpp/PixelAlpha.h"
#include <vector>
#include <cassert>
#include <iostream>

int main() {
    // Old mark at (4..7,4..7), moved outside it by the inverse-map preview.
    // A transparent preview texel cannot erase this mark via source-over.
    std::vector<uint8_t> original(16*16*4),base(12*12*4),fixed;
    for(int y=4;y<8;++y) for(int x=4;x<8;++x) {
        original[(y*16+x)*4+2]=220; original[(y*16+x)*4+3]=128;
    }
    fixed=original;
    assert(PixelRegion::replace(fixed.data(),16,16,2,2,base.data(),12,12));
    for(int scale : {1,2,4}) {
        unsigned before=0,after=0;
        // Integrating alpha is invariant under an area-filtered downscale.
        for(int by=0;by<16;by+=scale) for(int bx=0;bx<16;bx+=scale)
            for(int y=by;y<by+scale;++y) for(int x=bx;x<bx+scale;++x) {
                before+=original[(y*16+x)*4+3]; after+=fixed[(y*16+x)*4+3];
            }
        assert(before==2048 && after==0);
    }
    // Partial dirty region, non-zero offset, alpha zero erases old RGB too.
    std::vector<uint8_t> region(11*7*4,99),patch(3*2*4,0);
    assert(PixelRegion::replace(region.data(),11,7,4,3,patch.data(),3,2));
    for(int y=0;y<7;++y) for(int x=0;x<11;++x) for(int c=0;c<4;++c)
        assert(region[(y*11+x)*4+c]==((x>=4&&x<7&&y>=3&&y<5)?0:99));
    auto snapshot=region;
    assert(!PixelRegion::replace(region.data(),11,7,10,3,patch.data(),3,2));
    assert(snapshot==region);
    uint8_t input[4]={0,0,200,128},display[4];
    PixelAlpha::toDisplay(input,display,1);
    assert(display[0]==100 && display[3]==128);
    std::cout<<"PASS: old-position alpha 2048 -> 0; scaled/dirty replacement and premultiplied colors\n";
}
