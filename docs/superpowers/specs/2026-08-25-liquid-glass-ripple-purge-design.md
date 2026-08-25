# 涟漪清零 · 触点光效 · Accent 收窄 设计文档

日期: 2026-08-25
状态: 已批准（用户确认方向，全自动实施）
前置: [2026-08-24-ui-glass-motion-design.md](2026-08-24-ui-glass-motion-design.md)（三件套 Motion/Glass/Liquid 已落地）

## 1. 目标

在昨日玻璃质感基础上完成三件事：

1. **涟漪全部替换为光效**：残留 Material 组件的默认 ripple 一律改为触点径向白光
2. **Material 组件清零**：TextButton / IconButton / Switch / FloatingActionButton 全部换成 Re* 玻璃组件
3. **Accent 收窄**：配色值不动，缩小使用面——状态反馈类（选中/激活/滑条/开关）改中性白系，accent 只留主操作与功能性小元素

## 2. LiquidIndication（全局兜底）

`ui/components/Liquid.kt` 新增：

```kotlin
object LiquidIndication : IndicationNodeFactory {
    // 按下时触点画径向白光 (α≈0.18→0)，移动跟随，松手淡出
    // Deque 复用 Delegate 节点，零分配约束同 liquidHighlight
}
```

根部接线（`MainActivity.ReverieApp`）：

```kotlin
CompositionLocalProvider(LocalIndication provides LiquidIndication) { ... }
```

- 本 app 无 MaterialTheme 包装，M3 组件读 `LocalIndication` 的默认涟漪；根部覆盖后所有漏网组件自动获得光效
- 现有显式 `indication = null` 的调用点不受影响；机械扫换仍是主路径，此为双保险

## 3. Re* 组件补齐与替换清单

新增共享组件（ReComponents.kt 或新文件）：

| 组件 | 形态 |
|---|---|
| `ReTextButton(text, onClick, primary, enabled)` | 对话框按钮对：primary=accent 实心胶囊，secondary=玻璃胶囊 glassBorder，均带 pressScale+liquidHighlight |
| `ReFab(icon, desc, onTap)` | 圆形玻璃按钮，haze/glassBorder + pressGrow |

替换清单（机械扫换）:

| 残留 | 数量 | 目标 |
|---|---|---|
| material3 `Switch` | ReferenceWindow ×2、ToolbarCustomizeDialog、SettingsWidgets、BrushStudioPage | `ReSwitch` |
| material3 `IconButton` | ReplayPage、BrushStudioPage、SponsorDialog、ContributorsDialog、PaintingPage、HomePage | `ReIconButton` |
| material3 `FloatingActionButton` | SponsorDialog ×2 | `ReFab` |
| material3 `TextButton` | 约 50 处（各对话框） | `ReTextButton` |
| `DropdownMenuItem` | HomePage、ThemeSettingsSubPage | 保留容器，样式经 LiquidIndication 兜底去涟漪 |

## 4. Accent 收窄规则（2026-08-25 二次修订：真机验收后定稿）

> 初版把"状态反馈"一刀切为中性，导致开关/滑条/图层背景灰白而部分图标文字仍是 accent，两头不统一。真机验收后推翻，改为按「交互与激活」划线。

### 最终规则矩阵

| 类别 | 用色 | 示例 |
|---|---|---|
| 控件的激活/填充态 | ✅ accent | 开关开启轨道、滑条填充与指示条 |
| 选中容器 | ✅ accent 低透明底 + accent 描边 | 当前图层、选中笔刷/预设、tab 指示 |
| 激活态图标/文字 | ✅ accent | 当前工具图标、选中 tab/chip 文字 |
| 主操作按钮 | ✅ accent 实心 | 创建 CTA、对话框确定、FAB |
| 输入焦点 | ✅ accent | 光标、聚焦描边 |
| 危险操作 | 🔴 红 | 删除、丢弃修改 |
| 静态/未激活图标与正文 | ⚪ 中性 | 未选中图标、正文、标签页文字 |
| 装饰性元素 | ⚪ 中性 | 占位插画、「自动保存」角标 |

一句话：**蓝色只跟「交互与激活」走；装饰和信息展示永远中性。**

### 执行记录

- 初版中性化整体 revert（fd31832），仅保留 3 处装饰性中性化：自动保存角标（黑胶囊）、空画廊占位图标（subText）、微调弹窗标题图标与数值文本（信息展示型）
- `selFill`/`selStroke` token 随回滚移除；如未来需要极低调的大面积选中场景再按需重引

## 5. 动效收尾

高频表面（HomeBottomBar tab、TopBar 图标、LayerRow）补齐 pressScale + 光效组合；沿用 Motion 三档弹簧，不新增 token。

## 6. 实施顺序与验证

| Phase | 内容 | 验证 |
|---|---|---|
| A | LiquidIndication + 根部提供 | compileDebugKotlin |
| B | ReTextButton / ReFab 新增 | compileDebugKotlin |
| C | Switch ×5 → ReSwitch | compileDebugKotlin |
| D | IconButton ~15 → ReIconButton | compileDebugKotlin |
| E | FAB ×2 → ReFab | compileDebugKotlin |
| F | TextButton ~50 → ReTextButton | compileDebugKotlin |
| G | AppColors sel token + accent 清扫 | compileDebugKotlin |
| H | 高频表面动效收尾 + lintDebug + 回归审查 | lintDebug |

纯 UI 层改动，不触碰引擎/JNI/手势热路径；每阶段独立 commit。

## 7. 风险与对策

- **M3 组件不吃 LocalIndication**（版本行为差异）→ 机械扫换是主路径，兜底失效也不留涟漪
- **ReTextButton 语义差异**（enabled/disabled、长按）→ 组件带 enabled 参数，替换点逐一核对原语义
- **accent 清扫误伤可读性** → 文字/图标上的 accent 不降透明度；sel token 在明暗两套分别校准对比度
- **零分配铁律** → LiquidIndication 节点用 Deque 复用，组合期预分配
