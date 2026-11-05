// SPDX-License-Identifier: GPL-3.0-or-later
// BrushDefinitionGuard.h — 纯 std 头文件, 不依赖 Qt/Krita, 供 harness 与 RP 共用。
// 判定: setBrushSize 等 BrushWriter 类调用之后, brush_definition 的 <Brush type=...> 是否
// 从非 auto_brush 被降级为 auto_brush (即绑定落空后被上游 BrushWriter 回写成 1x1 兜底笔刷)。
#pragma once
#include <string>

namespace reverie {

// 取第一个 <Brush ...> 标签里的 type 属性值; 找不到返回空串。
inline std::string brushDefType(const std::string &xml)
{
    const size_t tag = xml.find("<Brush");
    if (tag == std::string::npos) return std::string();
    const size_t end = xml.find('>', tag);
    if (end == std::string::npos) return std::string();
    const std::string head = xml.substr(tag, end - tag);
    const std::string key = " type=\"";
    const size_t k = head.find(key);
    if (k == std::string::npos) return std::string();
    const size_t vs = k + key.size();
    const size_t ve = head.find('"', vs);
    if (ve == std::string::npos) return std::string();
    return head.substr(vs, ve - vs);
}

// before 是调用前快照, after 是调用后。仅当 before 有明确的非 auto_brush 类型而 after 变成 auto_brush 时为 true。
inline bool brushDefDowngraded(const std::string &before, const std::string &after)
{
    const std::string b = brushDefType(before);
    const std::string a = brushDefType(after);
    return !b.empty() && b != "auto_brush" && a == "auto_brush";
}

} // namespace reverie
