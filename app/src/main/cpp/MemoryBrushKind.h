// SPDX-License-Identifier: GPL-3.0-or-later
// MemoryBrushKind.h — 纯 std 头文件: 按文件扩展名决定内存笔尖注册走哪种解码器。
// 现状 (ReverieCoreStroke.cpp:1403-1405) gih/svg 会被当作 PNG 解码; 这里给出显式分派。
#pragma once
#include <string>

namespace reverie {

enum class MemoryBrushKind { Png, Gbr, Gih, Svg };

inline std::string lowerExt(const std::string &fileName)
{
    const size_t dot = fileName.rfind('.');
    if (dot == std::string::npos) return std::string();
    std::string ext = fileName.substr(dot + 1);
    for (char &c : ext) if (c >= 'A' && c <= 'Z') c = char(c - 'A' + 'a');
    return ext;
}

// isGbrHint: 调用方已有的 isGbr 判断 (扩展名未知时沿用旧行为: isGbr ? Gbr : Png)
inline MemoryBrushKind memoryBrushKindFor(const std::string &fileName, bool isGbrHint)
{
    const std::string ext = lowerExt(fileName);
    if (ext == "gbr") return MemoryBrushKind::Gbr;
    if (ext == "gih") return MemoryBrushKind::Gih;
    if (ext == "svg") return MemoryBrushKind::Svg;
    if (ext == "png") return MemoryBrushKind::Png;
    return isGbrHint ? MemoryBrushKind::Gbr : MemoryBrushKind::Png;
}

} // namespace reverie
