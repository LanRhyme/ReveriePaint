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

## 4. Accent 收窄规则

### 新增语义 token（AppColors）

```kotlin
val selFill: Color    // 中性选中填充: 白 α≈0.10 (暗) / 黑 α≈0.06 (浅)
val selStroke: Color  // 中性选中描边: 白 α≈0.45 (暗) / 黑 α≈0.30 (浅)
val selOn: Color      // 选中态文字/图标色 = text
```

### 分类清扫（约 267 处 `.accent` 引用逐类处理）

- **改中性白系**：ReSlider 填充、ReVerticalSlider 进度/指示条、ReSwitch 开启轨道、ReChip/ReMenuItem/ReColorDot 选中底色与文字、tab/图标选中 tint、图层行选中描边
- **保留 accent**：ReButton primary、创建页 CTA、主题设置强调色选择器本身、取色器当前色等「内容/主操作」场景
- 判断口径：**表达"状态反馈"→中性；表达"品牌/主操作/内容"→accent**

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
