// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// 笔尖退化判定 (纯函数). 1x1 / 0x0 / 无效 的笔尖在 Krita 里只会画出一个点, 需要在日志里被明确标出.
namespace reverie {
inline bool isDegenerateTip(int width, int height, bool valid) {
    return !valid || width <= 1 || height <= 1;
}
} // namespace reverie
