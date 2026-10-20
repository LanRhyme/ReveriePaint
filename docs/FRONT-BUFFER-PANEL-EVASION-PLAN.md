# ReveriePaint 前缓冲覆盖规则与 50% 面板避让执行计划（v5.0 可施工终版）

> **适用工程**：`<workdir>\ReveriePaint`（`com.reverie.paint`）
> **分支**：`feat/front-buffer-panel-evasion`
> **Phase 1 基线提交**：`fcfbc7dd`（已提交的 21 槽位实测 bounds + 不可变 `UiClipSnapshot` 底座）
> **文档版本**：v5.0（可施工终版，直接指导代码清理、实现、单测与真机验收）
> **核心原则**：不降低 120/144Hz 帧率、4~8ms 前缓冲单缓冲直出延迟、512 瓦片 `TILE_UNDRAWN` 就地写优化与 240Hz `ACTION_MOVE` 热路径零分配铁律（`AGENTS.md §4`）；彻底根治圆角面板直角残影与底部阴影被覆盖问题，实现严格的 **“面板在墨之上（50% 内容/背景 + 100% 边框/阴影）”** 实时避让。
>
> **施工前必读**
> - 文中行号以 `fcfbc7dd` + 当前未提交工作区为基线；施工前用函数名/锚点复核，行号漂移不构成阻塞。
> - 文中 `0.06~0.09ms`、阴影外扩 dp 等数字是预估值/初始值，施工时必须以真机实测校准；不得把预估值当作验收结论。
> - `evadablePanelSurface` 与 `evadablePanelContent` 必须共享**同一个 per-slot alpha State**；文中两处 `animateFloatAsState` 只是示意，施工时改为 ViewModel 持有的单一 `Animatable`/`State`，避免两套动画相位差。

---

## 0. 不可违反的核心需求与红线

### 0.1 行为与交互硬约束

1. **无整面板消失、无方向性擦除、无两段式“先 1/2 再 0”**：
   - 笔进入可避让面板 bounds 外扩 **`32dp + 笔刷屏幕半径`**（含面板内部，`distance = 0`）时：面板**内容与背景过渡为 50% 透明度（`0.5f`）**，**边框与外部阴影保持 100% 透明度（`1.0f`）**。
   - 笔离开外扩 **`40dp（32dp + 8dp 滞后）+ 笔刷屏幕半径`** 时：**立即恢复 100% 不透明（`1.0f`），不等抬笔**；进入与离开均采用 **`70ms` `tween`** 平滑过渡。
2. **渐隐态毛玻璃策略**：
   - `EVADING`（50% 渐隐态）时**关闭毛玻璃实时模糊（`hazeChild` / `hazeEffect`）**，通过 `70ms` 交叉淡化切换至 50% 纯色面板背景（`Morandi.panel`）；
   - 离开回到 `NORMAL`（100% 态）时交叉淡化恢复毛玻璃；**严禁对 `blurRadius` 做逐帧动画**。
3. **多面板独立判定**：
   - 9 个可避让面板（5 个 `QUICK_*` + `REFERENCE_WINDOW` + 3 个 `PINNED_*`）各自独立计算距离与透明度；同时靠近几个就几个变为 50%，互不牵连。
4. **交互规则（绘画态优先，`strokeStarted` 单一真源）**：
   - 只要 `CanvasTouchView.strokeStarted == true`（或引擎笔画会话活跃，同步至 `vm.isStrokeSessionActive`），所有面板处于**被动避让态，不消费任何指针事件，只做 50% 透明度变化**；笔从画布划入面板内部绝不中断笔画。
   - 当 `strokeStarted == false`（无活跃笔画）时，面板无论处于 100% 还是刚结束笔画的恢复过渡中，均**正常响应点击与拖拽**。
5. **总开关与预测模式解耦**：
   - 总开关置于 **“设置 -> 视图显示（View & Display）”**，默认**开启（`true`）**，持久化于 `paint_prefs`（键名 `autoHidePanelsOnStroke`）。
   - 总开关**仅控制 50% 透明度避让行为**；前缓冲覆盖/圆角/阴影合成修复**不受开关影响，始终生效**。
   - **与预测模式无关**：无论笔迹预测处于“关闭（原生模式）”、“硬件算法（`HARDWARE`）”还是“APP 软件算法（`SOFTWARE`）”，只要总开关开启，运笔靠近即触发 50% 避让。
6. **Popup / Dialog 边界**：
   - 独立 Android 子窗口（`Popup` / `Dialog`）天然位于 `FrontBufferPreviewOverlay` 之上，不参与 50% 避让，仅验收绝不被前缓冲覆盖。

### 0.2 视觉与性能硬约束

1. **目标合成顺序铁律（面板在墨之上）**：

```
final = panel_border_shadow(alpha = 1.0)
        over ( panel_content_bg(alpha = 0.5)
               over ( new_ink
                      over old_canvas ) )
```

- **严禁**将新笔迹以 50% alpha 叠在面板上方（`ink(0.5) over panel` 会使面板有效不透明度跌至 25% 且墨迹压在按钮图标之上，已被用户明确否定）。
- **严禁**使用矩形或圆角矩形裁剪去近似模拟软阴影或毛玻璃。
- **必须彻底消除**“圆角面板四角外出现与抬笔前画布+阴影颜色一致的直角边框残影”与“底部软阴影在抬笔前被前缓冲切断覆盖”。

2. **性能红线（以 `fcfbc7dd` 真机 `PerfHud` 为基线）**：
   - 画布自由区与面板外围预警圈内：前缓冲帧耗时（平均/P99）、端到端笔迹延迟、GC 次数（240Hz `ACTION_MOVE` 零分配）、`TILE_UNDRAWN` 命中率（100%）零退化。
   - 严禁每帧整屏重绘、每帧离屏图层拷贝或 `blurRadius` 动画。

---

## 1. 用户原话（完整保留，不得改写）

> “我想要的不是整个面板避让而是被画到的部分渐隐（具体形式是以上下左右为边界，笔进入一定范围内‘要比现在的范围缩小2倍’以进入的方向上下左右向中渐变隐藏不是直接整个消失而是先消失1/2如果笔还在深入才整个消失）”
>
> “现在开启app算法之后这几个悬浮面板是圆角边框，再不抬笔时会有与抬笔前画布+阴影颜色的直角边框，底部阴影在不抬笔时会像以前面板那样被覆盖”
>
> “关于这个app算法覆盖的问题到现在都没有彻底根治，为什么不能直接让其像普通的画布那样覆盖规则，而是说不清又叠在了谁上面非要等着一个个去找bug才一个个修复？”
>
> “无论硬软件算法还是都不开原生模式这个只要开就避让”
>
> “最好不用近似之类的，要保持视觉一致不然很别扭”
>
> “渐隐状态可以考虑去掉毛玻璃实时模糊等效果，离开后在恢复，需要有过渡动画”
>
> “为什么不设定成如果产生笔迹就说明是在绘画状态，此时不允许操作只能避让？”
>
> “为什么还是叠加在上面？这样看起来不还是被覆盖吗”

---

## 2. 架构决策与源码级物理证明

### 2.1 为什么 Phase 1（`fcfbc7dd`）会出现“圆角外直角残影”与“底部阴影被切断”？

1. **源码事实 1（前缓冲物理层级与 `SRC` 覆盖）**：
   - `FrontBufferPreviewOverlay.kt:238-239` 设置了 `setZOrderOnTop(true)` 与 `PixelFormat.TRANSLUCENT`，其 `FrontBufferedSurfaceControl` 位于应用主窗口（`Activity Window / HWUI`）正上方。
   - `RealInkController.kt:475-507`（`bakeRect`）将 Krita 512×512 显示瓦片（不透明画布下包含 **100% 不透明画布底色 + 新笔迹**）烤入 `slotBitmap`，再由 `FrontBufferPreviewOverlay.kt:197-202, 876` 使用 `PorterDuff.Mode.SRC`（`A_fb = 1.0`）贴到最顶层。

2. **源码事实 2（为什么圆角面板四角出现“抬笔前画布+阴影颜色的直角边框”）**：
   - `ReComponents.kt:1908` 中 `coordinates.boundsInRoot()` 返回的是面板的**轴对齐外接矩形 `[L, T, R, B]`**。
   - `FrontBufferPreviewOverlay.kt:868` 调用 `canvas.clipOutRect(L, T, R, B)` 在顶层前缓冲挖出 90° 直角矩形洞。
   - 但面板本体使用了 `.clip(RoundedCornerShape(14dp..24dp))`（如 `QuickActionWindow.kt:142-148, 174`）。在直角矩形 `[L, T, R, B]` 四角与圆角弧线之间的 **4 个月牙形角落区**（单角面积 `r^2(1 - π/4)`）内，前缓冲因处于 `clipOutRect` 洞内而不画新墨；而底层 `CanvasTouchView` 在 `realInk.held == true` 期间不触发 `invalidate()`（`PaintViewModel.kt:5908` `if (!keptOnFrontBuffer) invalidateCanvasFromRender()`）。
   - 结果：圆角弧线外侧有新墨，而四角月牙区内死死保留着**抬笔前的旧画布 + 阴影颜色**，在视觉上拼出一个刺眼的直角边框，直到抬笔主画布刷新才消失。

3. **源码事实 3（为什么底部软阴影在抬笔前被覆盖）**：
   - `Modifier.shadow(12dp..16dp, shape)`（如 `QuickColorWindow.kt:198`、`ColorPanel.kt:119`）由 Android HWUI `RenderNode` 在组件自身 `boundsInRoot()` **`[L, T, R, B]` 之外**绘制环境光与点光源阴影（顶部外溢约 `0.6 × elevation`，左右外溢约 `1.0 × elevation`，底部受点光源倾角影响外溢达 `1.5 × elevation` ≈ `24dp`）。
   - Phase 1 的 `UiClipSnapshot` 只裁剪了 `[L, T, R, B]`，面板底部与四周的软阴影区在前缓冲层毫无保护，直接被 `PorterDuff.Mode.SRC` 的不透明画布底色块一刀切断。

### 2.2 自主架构定案：阴影外扩孔径裁剪 + 孔径内脏区主画布协同合成 + Compose 渲染树三明治分层

要在不拆分应用窗口、不破坏 240Hz `requestUnbufferedDispatch` 手写笔事件流、不破坏 `HazeState` 同窗口 `RenderNode` 采样的前提下，100% 实现 **“面板在墨之上、零近似、消除直角残影与阴影切断”**，采用如下架构：

```
=================== v5.0 前缓冲与主窗口协同合成架构 ===================

对于每个激活的 UI 面板槽位 p：
• 面板交互与避让几何框 (bodyBounds)：B_p = [L_p, T_p, R_p, B_p] (来自 boundsInRoot)
  └── 用于 32dp 进入 / 40dp 离开距离判定、hitTest 与接触检测
• 面板完整视觉孔径框 (clipFootprint)：F_p = [L_p - padL, T_p - padT, R_p + padR, B_p + padB]
  └── 包含面板本体 + 四角圆角外弧 + 完整外部软阴影裙边 (pad = 阴影真实外扩像素)

【区域 I：孔径外自由画布区（(x, y) ∉ ⋃ F_p，占运笔帧 >92%，含 32dp 外围预警圈）】

1. FrontBufferPreviewOverlay (ZOrderOnTop) 直接以 4~8ms 单缓冲直出全部新笔迹；
2. CanvasTouchView 完全不 invalidate (keptOnFrontBuffer = true)；
3. PublishedTiles 保持 100% TILE_UNDRAWN 原地写，0 额外拷贝、0 延迟增加！

【区域 II：孔径内面板与阴影合成区（当本帧脏区 screenRects[i] ∩ F_p ≠ ∅ 时）】

1. FrontBufferPreviewOverlay 对 F_p 执行精确 clipOutRect(F_p)：
   → 在整个 [面板本体 + 4 个圆角月牙区 + 外部软阴影裙边] 内，前缓冲严格保持 100% 全透明 (A_fb = 0)；
   → 绝不用 SRC 画布底色切断底部阴影，也绝不在面板上方画 50% 墨迹！
2. RealInkController.presentPending 检测到 screenRects[i] 与 F_p 相交时：
   → 孔径 F_p 之外的笔画部分照常走 FrontBufferPreviewOverlay 4~8ms 极速上屏；
   → 同时提取相交包围盒 U = ⋃ (screenRects[i] ∩ F_p)，通过 UI 线程投递局部剪刀失效：
     view.postInvalidateOnAnimation(uL, uT, uR, uB)；
3. 主窗口 HWUI 在局部剪刀区 U 内按原生从底到顶画家算法合成：
   [底] CanvasTouchView (绘制最新 PublishedTiles：new_ink over old_canvas)
     ↓ over
   [中] Compose 外层 Modifier.shadow (100% 真实高斯软阴影，零近似，叠在新墨之上)
     ↓ over
   [顶] Compose 面板 .clip(windowShape) 内部：
        • 50% 纯色背景 (drawBehind 读取 evadeAlpha = 0.5f)
        • 50% 面板内容 (内层 graphicsLayer { alpha = evadeAlpha })
        • 100% 面板边框 (drawWithContent 顶层绘制 100% 描边)
=======================================================================
```

### 2.3 源码级证明：为什么该架构满足三大铁律？

1. **为什么 100% 实现了“面板在墨之上”且零视觉近似？**
   - 在孔径 `F_p` 内部，顶层 `FrontBufferPreviewOverlay` 被 `clipOutRect` 完全裁空（`A_fb = 0`），屏幕像素 100% 来自主窗口 `HWUI` 渲染树。
   - 在主窗口 `HWUI` 渲染树中，`CanvasTouchView`（`CanvasView.kt:234`）位于最底层，所有 Compose 面板（`PaintingPage.kt:880-2500`）位于上层。当 `CanvasTouchView` 在相交脏区 `U` 内刷新出新笔迹后，HWUI 硬件管线严格按照：

     ```
     final = panel_border_shadow(1.0) over (panel_content_bg(0.5) over (new_ink over old_canvas))
     ```

     进行合成。
   - 四角月牙区与底部软阴影区均位于 `F_p` 内部：月牙区底下是 `CanvasTouchView` 刚刷新的新笔迹，上面叠着 `Modifier.shadow` 的真实硬件阴影与 `RoundedCornerShape` 的真实抗锯齿圆角边缘。验收要求抬笔前/后像素 diff 为 0；若因前缓冲与 HWUI 光栅化路径差异出现边界差异，必须调整孔径外扩/剪刀区直到 diff 为 0，不能以“接近”交付。

2. **为什么孔径外（含 `32dp` 外围触发圈）性能与延迟零退化？**
   - 面板阴影裙边外扩最大值 `padB = 26dp`（侧边 `18dp`），小于进入避让的距离阈值 `32dp + brushScreenRadius`。
   - 当笔在画布空白处、或刚进入 `32dp + brushScreenRadius` 触发圈使面板变为 50% 但墨迹包围盒尚未侵入 `F_p` 时，`clipsSnapshot.intersects(cl, ct, cr, cb)` 返回 `false`。
   - 此时 `RealInkController.presentPending` **不触发任何主画布失效**，`keptOnFrontBuffer = true`，`PublishedTiles.drawnByView` 100% 保持 `TILE_UNDRAWN`（`PaintViewModelTiles.kt:121`），`FrontBufferPreviewOverlay` Frame Time、端到端延迟、GC 次数与 `fcfbc7dd` 基线完全一致。

3. **孔径内部重叠帧的量化成本与物理边界说明（不静默取舍）**：
   - **唯一存在物理权衡的子场景**：当用户的笔刷像素**真正画进某个面板的孔径 `F_p` 内部**（即正在穿过面板阴影或 50% 面板肚子作画的那几帧）时，为了让主窗口 HWUI 将真实的圆角、软阴影与 50% 面板合成在新墨之上，主窗口 `CanvasTouchView` 必须在剪刀矩形 `U = dirtyRect ∩ F_p` 内刷新。
   - **量化影响（预估值，必须真机实测）**：
     - 孔径 `F_p` **外部**的笔画与预测笔尖：依然由 `FrontBufferPreviewOverlay` 以 **4~8ms** 单缓冲直出，零延迟损失；
     - 孔径 `F_p` **内部**被画到的 **1~2 张 512×512 瓦片**：在穿过面板的那几帧被 `CanvasTouchView.drawDisplayTiles` 标记为 `TILE_DRAWN`，触发瓦片级 COW（单张 512×512 瓦片 `copyTileInto` 在 `reverie-render` 线程预估 **`0.06 ~ 0.09 ms`**，且仅限被画笔命中的 1~2 张瓦片，其余未命中瓦片零拷贝）；
     - **离开即恢复**：一旦笔迹移出 `F_p`，下一帧 `PaintViewModelTiles.kt:160`（`drawn.set(idx, PublishedTiles.TILE_UNDRAWN)`）立即将瓦片重置回 `TILE_UNDRAWN` 就地写状态。

---

## 3. 旧 Phase 2 草稿逐文件清理清单（施工第一步）

当前工作区有 9 个已修改文件（外加 1 个单测文件）包含 v3.1 的旧 Phase 2 草稿，施工时必须首先按以下清单逐项清理，**严禁误删 Phase 1 已提交的底座**：

| 序号 | 文件路径 | 必须删除/清理的旧 Phase 2 冲突代码 | 必须保留的 Phase 1 底座与替换为的新逻辑 |
| :--- | :--- | :--- | :--- |
| 1 | `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt` | 删除 `EvadeFadeOutSpec`/`EvadeFadeInSpec`；删除 `reportUiSlot` 中的 `composed` 分支、`vm.slotSnapZero` 读取、`targetValue = 0f`、`Modifier.layout` 协调器、`graphicsLayer { alpha = a; if (a <= 0.03f) translationX = -(size.width + 4096f) }` 以及 `.pointerInput(slot)` 半透明期 `consume()` 拦截器。 | 保留 `UiSlotElement` 与 `UiSlotNode`。将 `reportUiSlot` 简化为纯边界与阴影外扩注册；新增 `Modifier.evadablePanelSurface` 与 `Modifier.evadablePanelContent` 实现 50% 内容/背景 + 100% 边框/阴影 + 毛玻璃交叉淡化。 |
| 2 | `app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt` | 删除 `slotSnapZero` 同步；删除双圈 `warnPx = 64f * density`、`contactPx = 4f * density`、`UiRingState.CONTACT`、`slotSnapZero`、`setUnclipped`、`restoreEvadedPanelsRunnable`（180ms 延迟复位）与 `scheduleRestoreEvadedPanels()`；删除所有对 `scheduleRestoreEvadedPanels()` 的调用。 | 保留 Phase 1 的 `uiClipSnapshot`、`rebuildUiExclusionSnapshot`、`effectiveBrushScreenRadius` 以及落笔前同步发布快照不变式。替换为 `32dp` 进入 / `40dp` 离开的双向实时滞后评估函数 `evaluateUiEvasion50()` 与 `clearAllEvasionState()` 立即复位。 |
| 3 | `app/src/main/java/com/reverie/paint/model/UiPanelOcclusionTracker.kt` | 删除 `enum class UiRingState { NONE, WARN, CONTACT }`；删除 `unclippedMaskValue`、`setUnclipped`、`isUnclipped`；删除 `snapshot()` 中按 `unclippedMask` 排除面板的逻辑（50% 面板绝不撤除前缓冲孔径裁剪！）；删除 `ringState`、`ringStateForSlot`、`ringStateForEnvelope`。 | 保留 21 槽位多 owner 栈（`MAX_OWNERS_PER_SLOT = 4`）、`update`、`clear`、`snapshot`、`hitTest`、`intersects`。新增槽位阴影外扩 `shadowPad` 支持与双向滞后判定函数 `evaluateSlotEvasion(...)`。 |
| 4 | `app/src/main/java/com/reverie/paint/model/UiPanelSlot.kt` | 删除 `defaultEvade` 与 `pinnedOrReference` 字段及 `DEFAULT_EVADABLE`（总开关统一控制全部 9 个可避让面板，移除二级开关）。 | 保留 21 个槽位枚举、`evadable: Boolean`、`bit`、`COUNT`、`EVADABLE`、`EVADABLE_ARRAY`。为每个槽位补充真实阴影外扩 dp 参数。 |
| 5 | `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt` | 删除 `slotSnapZero` 数组；删除 `autoHidePinnedAndRefOnStroke` 与 `updateAutoHidePinnedAndRefOnStroke`。 | 保留 `panelOcclusion`、`slotEvading`、`autoHidePanelsOnStroke`（默认 `true`）与 `updateAutoHidePanelsOnStroke`；新增 `@Volatile var isStrokeSessionActive: Boolean = false` 供绘画态优先协议读取。 |
| 6 | `app/src/main/java/com/reverie/paint/ui/home/StylusSettingsSubPage.kt` | 删除在“手写笔设置”中添加的“面板避让”分类标题与两个 `SettingSwitchGroupItem`。 | 恢复至 `fcfbc7dd` 干净状态；将总开关移入 `SettingsTabPage.kt` 的“视图显示”子页。 |
| 7 | `app/src/main/res/values/strings.xml` & `values-en/strings.xml` | 删除 `stylus_category_panel_evasion` 与 `auto_hide_pinned_and_ref_on_stroke_*`。 | 更新 `auto_hide_panels_on_stroke_title` 与 `auto_hide_panels_on_stroke_desc` 为 50% 半透明避让的中英文文案。 |
| 8 | `app/src/test/java/com/reverie/paint/model/UiRingDetectorTest.kt` | 删除针对 `UiRingState.WARN / CONTACT`、`setUnclipped`、`64dp / 4dp` 的旧测试。 | 重写为测试 `32dp` 进入、`40dp` 离开双向滞后、多面板独立判定、阴影外扩孔径裁剪永不撤除、80ms 连续排线继承与复位的纯 Kotlin 单元测试。 |

---

## 4. 50% 双向滞后状态机与绘画态优先交互协议

### 4.1 状态定义与转移方程

每个 `slot.evadable == true` 的槽位（共 9 个：`QUICK_ACTION`、`QUICK_BRUSH`、`QUICK_COLOR`、`QUICK_OKLCH`、`QUICK_LAYER`、`REFERENCE_WINDOW`、`PINNED_BRUSH_PANEL`、`PINNED_LAYER_PANEL`、`PINNED_COLOR_PANEL`）独立维护布尔状态 `isEvaded`：

- **`NORMAL`（`isEvaded == false`）**：
  - 目标 `evadeAlpha = 1.0f`（过渡 `tween(70, FastOutSlowInEasing)`）；
  - 内容与背景不透明度 100%（乘以面板自身设置的基础不透明度 `opacity`），边框与阴影 100%，毛玻璃 `hazeChild` 正常启用。
- **`EVADING`（`isEvaded == true`）**：
  - 目标 `evadeAlpha = 0.5f`（过渡 `tween(70, FastOutLinearInEasing)`）；
  - 内容与背景不透明度 50%，边框与阴影保持 100%，毛玻璃关闭并交叉淡化为 50% 纯色背景。

#### 距离计算与双向滞后阈值

设当前输入事件在屏幕坐标系下的采样包络矩形为 `E = [envL, envT, envR, envB]`（汇总当前点 `(curX, curY)`、上一采样点 `priorStrokeScreenPos`、所有 `historySize` 历史点、辅助绘图吸附点及预测点 `(predictedScreenX, predictedScreenY)`），面板 `p` 的本体实测边界为 `B_p = [L_p, T_p, R_p, B_p]`，欧氏最短距离为：

```
d(E, B_p) = hypot(
    max(0, L_p - envR, envL - R_p),
    max(0, T_p - envB, envT - B_p)
)
```

（当包络与面板内部相交或落点在面板内部时，`d(E, B_p) = 0`）。

设当前有效工具屏幕半径为 `R_brush`（由 `effectiveBrushScreenRadius(v, pressure)` 给出，含笔尾橡皮/侧键临时橡皮的真实尺寸）：

- **进入半径**：`R_enter = 32dp + R_brush`
- **离开半径**：`R_exit = 40dp + R_brush`（即 `32dp + 8dp` 滞后回差）
- **单步状态转移函数**：

```
nextEvaded(p) =
    true,    if d(E, B_p) <= R_enter
    false,   if d(E, B_p) > R_exit
    prevEvaded(p), otherwise   // 滞后保持区，防边界抖动
```

### 4.2 运笔中途立即恢复与连续排线 80ms 继承协议

1. **运笔中途离开立即恢复**：
   在每次 `ACTION_MOVE` 中，对全部 9 个可避让槽位执行上述双向判定。一旦某槽位 `d(E, B_p) > R_exit`，`tracker.evaluateSlotEvasion` 立即将该槽位的 `isEvaded` 置为 `false`，并同步 `v.slotEvading[slot.ordinal].value = false`，触发 `70ms` 动画恢复到 100%，**绝不等待抬笔**。

2. **抬笔恢复与 80ms 连续点画/排线防闪烁**：
   - 当正常抬笔（`ACTION_UP`）时，若某面板当前仍处于 `EVADING`（说明抬笔点仍在 `40dp + R_brush` 内），启动一个轻量 **`80ms`** 抬笔延续定时器 `postDelayed(clearEvasionAfterUpRunnable, 80L)`：
     - 若用户在 `80ms` 内未落下一笔（或下一笔 `ACTION_DOWN` 落点距离该面板 `> 40dp + R_brush`），则立即将该面板置回 `NORMAL`（100%）；
     - 若用户在 `80ms` 内紧接着落下一笔且新落点仍在该面板 `40dp + R_brush` 内，则取消该定时器并无缝保持 `EVADING`（50%），彻底消除在面板旁高频排线/点画时的 `50% <-> 100%` 闪烁。

### 4.3 绘画态优先交互协议（`strokeStarted` 单一真源）

- 在 `CanvasTouchView` 中，每当 `strokeStarted` 变化时，同步写入 `vm?.isStrokeSessionActive = strokeStarted`。
- **起笔在画布上（`strokeStarted == true`）**：
  - `CanvasTouchView` 持有当前手势流；运笔途中哪怕笔尖划入任何面板内部，事件仍 100% 由 `CanvasTouchView` 消费并绘制到画布，面板仅被动变为 50%。
  - 面板侧 `Modifier.evadablePanelContent` 在 `vm.isStrokeSessionActive == true` 时通过 `PointerEventPass.Initial` 忽略对额外触点的响应，确保绝不抢断正在进行的笔画。
- **无活跃笔画（`strokeStarted == false`）**：
  - 面板不挂任何拦截器，所有按钮点击、滑块调节、浮窗拖拽 100% 正常响应。
- **触点在面板上开始且没有产生笔画**：视为 UI 操作，面板消费事件。若未来需要“从面板内部起笔也能画画”，作为独立需求处理，本版不实现。

### 4.4 12 条强制复位路径（杜绝卡在 50%）

在以下任一路径触发时，必须立即调用 `clearAllEvasionState(v)`（取消 `clearEvasionAfterUpRunnable`，将所有 `slotEvading` 与 `tracker.evadedMask` 清零）：

1. 正常抬笔 80ms 后（`clearEvasionAfterUpRunnable`）；
2. 手势取消（`MotionEvent.ACTION_CANCEL`）；
3. 笔画中被顶栏撤销/重做打断（`abortActiveStrokeForHistory()`）；
4. 长按吸色触发打断笔画；
5. 开启“禁用画布触控”拦截手指笔画；
6. 三指下滑呼出编辑菜单 / 三指轻点重做；
7. 双指捏合缩放/旋转/双指轻点撤销接管；
8. 停顿 650ms 抬笔进入 `QuickShapeEditor` 速创编辑；
9. 切换工具 `tool` setter；
10. 打开任一模态/半模态覆盖面板（`overlayPanelsOpen == true` 时）；
11. 视图卸载 `onDetachedFromWindow()`；
12. 用户在“视图显示”设置中关闭总开关 `updateAutoHidePanelsOnStroke(false)`。

> 施工时以函数名/锚点定位；行号可能随工作区变化。

---

## 5. Compose 侧 50% 分层与毛玻璃交叉淡化设计

### 5.1 为什么不能对整个面板调 `graphicsLayer { alpha = 0.5f }`？

若在面板最外层（`.shadow` 与边框之外）设置 `graphicsLayer { alpha = 0.5f }`，RenderNode 会将阴影、边框、背景、内容一并乘以 `0.5`，违反“边框与阴影保持 100%”的硬性需求。

### 5.2 三明治修饰符拆分（零 Recomposition、零 Layout）

在 `ReComponents.kt` 中提供两个配对修饰符，供全部 9 个可避让面板使用。**两者必须共享同一个 per-slot alpha State**（建议在 `PaintViewModel` 中为每个槽位维护一个 `Animatable` 或 `State<Float>`；不要分别创建两个 `animateFloatAsState`，否则会有相位差）。

1. **外层容器修饰符 `Modifier.evadablePanelSurface(...)`**（紧接在 `.shadow(elevation, shape).clip(shape)` 之后调用）：
   - **毛玻璃控制**：当 `vm.blurBackground && hazeState != null && !isEvading` 时挂载 `Modifier.hazeChild(state = hazeState, style = ...)`；一旦进入 `isEvading == true`，立即卸载 `hazeChild`（停止实时模糊采样开销），由下方 `drawBehind` 接管交叉淡化。
   - **背景层（Draw 阶段读取 alpha）**：通过 `Modifier.drawBehind` 读取动画状态 `evadeAlphaState.value`（`1.0f <-> 0.5f`，`70ms tween`）：
     - 在非毛玻璃模式或 `isEvading` 期间，绘制纯色背景 `drawRect(panelColor.copy(alpha = baseOpacity * evadeAlphaState.value))`；
     - 在毛玻璃刚恢复（`!isEvading` 且 `evadeAlphaState.value < 0.99f`）的 70ms 过渡期内，用 `1f - (evadeAlphaState.value - 0.5f) * 2f` 对纯色背景与毛玻璃做平滑交叉淡化，无任何 `blurRadius` 动画，也无闪白/跳变。
   - **100% 边框层（DrawWithContent 顶层描边）**：通过 `Modifier.drawWithContent` 在 `drawContent()` 之后，以 **固定 `alpha = 1.0f`** 沿着 `shape` 绘制 `1.dp` 的面板轮廓描边（`Morandi.border`），确保面板在 50% 避让态下依然拥有 100% 清晰锐利的圆角边框轮廓。

2. **内层内容修饰符 `Modifier.evadablePanelContent(vm, slot)`**（包裹面板内部的子组件布局）：
   - 使用 `Modifier.graphicsLayer { alpha = evadeAlphaState.value }`：仅在 RenderNode 属性更新阶段读取 `evadeAlphaState.value`（`1.0f <-> 0.5f`），使内部按钮、图标、文字、色轮、图层行、参考图降为 50% 不透明度，**全程触发 0 次子组件 Recomposition、0 次 Measure/Layout**。

---

## 6. 前缓冲阴影孔径外扩与局部主画布协同合成设计

### 6.1 槽位阴影外扩表（`UiPanelSlot`）

为彻底消除底部与四周软阴影被前缓冲 `PorterDuff.Mode.SRC` 切断，每个 `UiPanelSlot` 携带其阴影外扩 dp 值 `(shadowPadHorizontalDp, shadowPadTopDp, shadowPadBottomDp)`。下表是**初始值**，施工时必须按真机阴影实测校准：

- **无外阴影的停靠栏**（`ANIMATION_TIMELINE`）：`(0f, 0f, 0f)`；
- **中等阴影栏/胶囊**（`TOP_BAR`、`TOOL_RAIL`、`SLIDER_RAIL`、`ACTIVE_SELECTION_PILL`、`TOP_HUD_TOAST`，`elevation = 8dp~10dp`）：`(12f, 8f, 16f)`；
- **高阴影悬浮窗与钉住大面板**（5 个 `QUICK_*`、`REFERENCE_WINDOW`、3 个 `PINNED_*`、`BOTTOM_TOOL_PANEL`、`SOLO_MODE_BAR`、`PICKER_SOURCE_BAR`、`SHIFT_TRACE_CAPSULE`、`SAVED_SELECTIONS_POPUP`、`ANIMATION_SETTINGS_CARD`，`elevation = 12dp~16dp`）：`(18f, 12f, 26f)`。

### 6.2 `UiPanelOcclusionTracker` 双矩形存储

- `slotBounds`（`FloatArray(COUNT * 4)`）：保存面板本体的实测 `[L, T, R, B]`（所有活跃 owner 的并集），专供 **`32dp / 40dp` 避让距离判定**与 **`hitTest`** 使用（保证外扩阴影不会虚增 32dp 触发距离！）。
- `slotClipBounds`（`FloatArray(COUNT * 4)`）：保存面板本体加上像素级阴影外扩后的孔径 `[L - padH, T - padT, R + padH, B + padB]`，专供 **`snapshot(): UiClipSnapshot`** 导出给 `FrontBufferPreviewOverlay.clipOutUi` 使用。
- **关键铁律**：`snapshot()` 导出**全部**激活槽位的 `slotClipBounds`（无论该槽位是否处于 50% `EVADING` 态，**绝不从 `UiClipSnapshot` 中移除**！），从而保证 `FrontBufferPreviewOverlay` 永远不会把 `SRC` 画布底色或墨迹画到 50% 面板或软阴影的头顶上。

### 6.3 `RealInkController.presentPending` 孔径相交局部协同刷新

在 `RealInkController.presentPending(tiles: PublishedTiles)`（运行于 `reverie-render` 线程）中：

1. 对本帧烘焙的每一块屏幕脏区 `screenRects[d .. d+3]`（`[cl, ct, cr, cb]`）：
   - `FrontBufferPreviewOverlay.renderInkScreenRegions` 照常提交并由 `clipOutUi` 裁掉所有 `clipsSnapshot` 孔径 `F_p`，使孔径外的笔画以 4~8ms 立即上屏。
   - 同时调用零分配方法 `clipsSnapshot.accumulateIntersection(cl, ct, cr, cb, interScratch)`：检查 `[cl, ct, cr, cb]` 是否与任一面板孔径 `F_p` 相交，并累积精确交集包围盒。
2. **分支 A：本帧所有脏区均未碰触任何面板孔径（`hasClipOverlap == false`）**：
   - 不调用 `view.postInvalidate`；
   - `presentTileFrame` 返回 `kept = true`，`PublishedTiles` 100% 保持 `TILE_UNDRAWN` 就地写。
3. **分支 B：本帧存在脏区侵入面板孔径 `F_p`（`hasClipOverlap == true`）**：
   - 汇总相交区域的最小屏幕整数包围盒 `[overlapMinL, overlapMinT, overlapMaxR, overlapMaxB]`；
   - 调用 `view.postInvalidateOnAnimation(overlapMinL, overlapMinT, overlapMaxR, overlapMaxB)`，唤醒主窗口 HWUI 在下一拍 VSYNC 仅重绘该相交孔径区域，将面板的真实圆角、真实软阴影与 50% 面板本体原生合成在 `CanvasTouchView` 的最新瓦片墨迹之上。

---

## 7. 全文件代码修改清单（具体到文件、函数、数据结构、线程协议）

### 7.1 `app/src/main/java/com/reverie/paint/model/UiPanelSlot.kt`（全量替换）

- **线程协议**：纯不可变枚举，任意线程安全只读。
- **修改内容**：移除 `defaultEvade` 与 `pinnedOrReference`；新增阴影外扩 dp 属性 `shadowPadHDp`、`shadowPadTopDp`、`shadowPadBottomDp`。

```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲裁剪与 50% 运笔避让所用的 UI 槽位表（共 21 个槽位）。
 *
 * - [evadable]：是否参与运笔靠近 50% 半透明避让（共 9 个：5 个 QUICK_* + 4 个钉住/参考）。
 * - [shadowPadHDp] / [shadowPadTopDp] / [shadowPadBottomDp]：面板外部软阴影
 *   超出 boundsInRoot() 的外扩 dp，用于生成前缓冲孔径裁剪区。
 */
enum class UiPanelSlot(
    val evadable: Boolean = false,
    val shadowPadHDp: Float = 18f,
    val shadowPadTopDp: Float = 12f,
    val shadowPadBottomDp: Float = 26f,
) {
    // 1. 常驻/非渐隐槽位（12 个：仅做孔径裁剪与局部合成保护，不参与 50% 避让）
    TOP_BAR(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    TOOL_RAIL(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    SLIDER_RAIL(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    ANIMATION_TIMELINE(evadable = false, shadowPadHDp = 0f, shadowPadTopDp = 0f, shadowPadBottomDp = 0f),
    BOTTOM_TOOL_PANEL(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    SOLO_MODE_BAR(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    PICKER_SOURCE_BAR(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    ACTIVE_SELECTION_PILL(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    SHIFT_TRACE_CAPSULE(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    TOP_HUD_TOAST(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    SAVED_SELECTIONS_POPUP(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    ANIMATION_SETTINGS_CARD(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),

    // 2. 可避让悬浮小窗与钉住/参考面板（9 个：孔径保护 + 32dp/40dp 50% 半透明避让）
    QUICK_ACTION(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 22f),
    QUICK_BRUSH(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 22f),
    QUICK_COLOR(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    QUICK_OKLCH(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    QUICK_LAYER(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 24f),
    REFERENCE_WINDOW(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 24f),
    PINNED_BRUSH_PANEL(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    PINNED_LAYER_PANEL(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    PINNED_COLOR_PANEL(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        val COUNT: Int get() = entries.size
        val EVADABLE: List<UiPanelSlot> = entries.filter { it.evadable }
        internal val EVADABLE_ARRAY: Array<UiPanelSlot> = EVADABLE.toTypedArray()
    }
}
```

### 7.2 `app/src/main/java/com/reverie/paint/model/UiClipSnapshot.kt`（新增相交包围盒提取）

- **线程协议**：不可变对象，UI 线程构建并通过 `@Volatile` 发布，`reverie-render` 线程与 GL 线程无锁只读。
- **修改内容**：新增零分配方法 `accumulateIntersection(...)`，供 `RealInkController` 提取脏区与面板孔径的精确交集：

```kotlin
/**
 * 若 [l, t, r, b] 与任一裁剪孔径相交，将相交部分并入 [outBounds]
 * （`[minL, minT, maxR, maxB]`）并返回 true；完全不相交时返回 false。零分配。
 */
fun accumulateIntersection(l: Int, t: Int, r: Int, b: Int, outBounds: IntArray): Boolean {
    val fl = l.toFloat()
    val ft = t.toFloat()
    val fr = r.toFloat()
    val fb = b.toFloat()
    var hit = false
    var i = 0
    while (i < count * 4) {
        val cl = flat[i]
        val ct = flat[i + 1]
        val cr = flat[i + 2]
        val cb = flat[i + 3]
        if (fr > cl && fl < cr && fb > ct && ft < cb) {
            val il = maxOf(l, kotlin.math.floor(cl).toInt())
            val it = maxOf(t, kotlin.math.floor(ct).toInt())
            val ir = minOf(r, kotlin.math.ceil(cr).toInt())
            val ib = minOf(b, kotlin.math.ceil(cb).toInt())
            if (ir > il && ib > it) {
                if (il < outBounds[0]) outBounds[0] = il
                if (it < outBounds[1]) outBounds[1] = it
                if (ir > outBounds[2]) outBounds[2] = ir
                if (ib > outBounds[3]) outBounds[3] = ib
                hit = true
            }
        }
        i += 4
    }
    return hit
}
```

### 7.3 `app/src/main/java/com/reverie/paint/model/UiPanelOcclusionTracker.kt`（重写）

- **线程协议**：仅在 UI 线程读写。
- **修改内容**：
  1. 删除 `UiRingState`、`unclippedMaskValue`、`setUnclipped`、`isUnclipped`、`ringState*`。
  2. `update(...)` 接收像素级阴影外扩参数 `padH: Float = 0f, padT: Float = 0f, padB: Float = 0f`（由 `UiSlotNode` 根据当前 `density` 与 `slot.shadowPad*Dp` 算好传入），内部同时维护本体并集 `slotBounds` 与孔径并集 `slotClipBounds`。
  3. 新增双向滞后判定方法：

```kotlin
/**
 * 双向滞后评估指定槽位是否应处于 50% 避让态（零分配）。
 *
 * - 包络到槽位本体 bounds 的距离 <= [enterRadiusPx]：进入避让（true）；
 * - 距离 > [exitRadiusPx]：离开避让（false）；
 * - 处于 (enterRadiusPx, exitRadiusPx] 滞后带内：保持当前 [isEvaded] 状态。
 */
fun evaluateSlotEvasion(
    slot: UiPanelSlot,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    enterRadiusPx: Float,
    exitRadiusPx: Float,
): Boolean {
    val ordinal = slot.ordinal
    if (!slot.evadable || currentTokens[ordinal] == 0) {
        setEvaded(slot, false)
        return false
    }
    val i = ordinal * 4
    val dist = distanceBetweenRects(
        left, top, right, bottom,
        slotBounds[i], slotBounds[i + 1], slotBounds[i + 2], slotBounds[i + 3],
    )
    val currentlyEvaded = isEvaded(slot)
    val nextEvaded = when {
        dist <= enterRadiusPx -> true
        dist > exitRadiusPx -> false
        else -> currentlyEvaded
    }
    setEvaded(slot, nextEvaded)
    return nextEvaded
}
```

  4. `snapshot()` 不接受任何排除掩码，始终将所有 `currentTokens[ordinal] != 0` 的 `slotClipBounds` 打包为 `UiClipSnapshot`（50% 面板仍必须通过孔径裁剪保护其圆角、阴影与 50% 本体不被前缓冲 `SRC` 覆盖）。

### 7.4 `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt`

- **修改内容**：
  1. 删除 `slotSnapZero`、`autoHidePinnedAndRefOnStroke`、`updateAutoHidePinnedAndRefOnStroke`。
  2. 保留 `val slotEvading = Array(UiPanelSlot.COUNT) { mutableStateOf(false) }`。
  3. 新增 `@Volatile var isStrokeSessionActive: Boolean = false`。
  4. `updateAutoHidePanelsOnStroke(enabled: Boolean)`：更新 `autoHidePanelsOnStroke = enabled` 并写入 `paint_prefs`；当 `!enabled` 时清空 `panelOcclusion.clearAllEvasion()` 与全部 `slotEvading[i].value = false`。

### 7.5 `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`（重写）

- **线程协议**：Compose UI 线程组合与绘制；`evadeAlpha` 仅在 `drawBehind` / `drawWithContent` / `graphicsLayer` lambda 阶段读取，**组合阶段零拆箱**。
- **修改内容**：
  1. `Modifier.reportUiSlot(vm, slot)` 简化为纯边界注册。
  2. `UiSlotNode.onGloballyPositioned`：通过当前 `density` 将 `slot.shadowPad*Dp` 转为像素 `padH, padT, padB`，传给 `vm.panelOcclusion.update(...)`。
  3. 新增 `Modifier.evadablePanelSurface` 与 `Modifier.evadablePanelContent`；两者共享同一个 per-slot alpha State（建议 `vm.slotEvadeAlpha[slot.ordinal]`）。

```kotlin
private val EvadeTransitionSpec = tween<Float>(durationMillis = 70, easing = FastOutSlowInEasing)

/**
 * 可避让面板的外壳背景与 100% 轮廓修饰符。
 *
 * 必须在 `.reportUiSlot(vm, slot).shadow(elevation, shape).clip(shape)` 之后调用：
 * - 外层 `.shadow` 不受本修饰符影响，保持 100% 阴影；
 * - `EVADING` 态卸载 `hazeChild` 实时模糊，通过 `drawWithContent` 将面板底色平滑过渡到 50%；
 * - `drawWithContent` 在内容之上以 100% 不透明度绘制 `1.dp` 面板边框，保留清晰轮廓。
 */
internal fun Modifier.evadablePanelSurface(
    vm: PaintViewModel,
    slot: UiPanelSlot,
    shape: Shape,
    baseOpacity: Float,
    hazeState: dev.chrisbanes.haze.HazeState?,
    isBarStyle: Boolean = false,
    panelColor: Color = Morandi.panel,
): Modifier = composed {
    val isEvading = slot.evadable && vm.slotEvading[slot.ordinal].value
    // 与 evadablePanelContent 共享同一个 State，避免两套动画相位差
    val evadeAlphaState = vm.slotEvadeAlpha[slot.ordinal]
    val borderStrokePx = with(LocalDensity.current) { 1.dp.toPx() }
    val borderColor = Morandi.border
    val useHaze = vm.blurBackground && hazeState != null && !isEvading
    val hazeModifier = if (useHaze) {
        val style = if (isBarStyle) {
            com.reverie.paint.ui.theme.Glass.barStyle(if (baseOpacity >= 0.99f) 0.92f else baseOpacity)
        } else {
            com.reverie.paint.ui.theme.Glass.popupStyle(if (baseOpacity >= 0.99f) 0.90f else baseOpacity)
        }
        Modifier.hazeChild(state = hazeState!!, style = style)
    } else {
        Modifier
    }
    this
        .then(hazeModifier)
        .drawWithContent {
            val factor = evadeAlphaState.value
            if (!useHaze || factor < 0.999f) {
                val bgAlpha = if (!useHaze) {
                    (baseOpacity * factor).coerceIn(0f, 1f)
                } else {
                    // 从 EVADING 返回 NORMAL 的 70ms 内，纯色层平滑淡出交给 hazeChild
                    val crossfade = ((1.0f - factor) * 2.0f).coerceIn(0f, 1f)
                    (baseOpacity * 0.5f * crossfade).coerceIn(0f, 1f)
                }
                drawRect(color = panelColor.copy(alpha = bgAlpha))
            }
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                color = borderColor,
                alpha = 1.0f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = borderStrokePx),
            )
        }
}

/**
 * 可避让面板的内部内容透明度与绘画态被动穿透修饰符。
 *
 * - 在 RenderNode 层级将内部控件/图标/色轮透明度设为 `evadeAlpha`（1.0f <-> 0.5f），零重组；
 * - 当 `vm.isStrokeSessionActive == true` 时处于被动避让态，不响应额外触点。
 */
internal fun Modifier.evadablePanelContent(
    vm: PaintViewModel,
    slot: UiPanelSlot,
): Modifier = composed {
    if (!slot.evadable) return@composed this
    val evadeAlphaState = vm.slotEvadeAlpha[slot.ordinal]
    this.graphicsLayer {
        alpha = evadeAlphaState.value
    }
}
```

### 7.6 9 个可避让面板接入 `evadablePanelSurface` 与 `evadablePanelContent`

将以下 9 个面板原有的 `.then(if (vm.blurBackground && hazeState != null) Modifier.hazeChild(...) else Modifier.background(...)).glassBorder(...)` 统一替换为 `.evadablePanelSurface(...)`，并在其内部根容器上挂载 `.evadablePanelContent(vm, slot)`：

1. `QuickActionWindow.kt`（`slot = UiPanelSlot.QUICK_ACTION`，内层 `Row`/`Column` 挂 `evadablePanelContent`）；
2. `QuickBrushWindow.kt`（`slot = UiPanelSlot.QUICK_BRUSH`）；
3. `QuickColorWindow.kt`（`slot = UiPanelSlot.QUICK_COLOR`）；
4. `QuickOklchWindow.kt`（`slot = UiPanelSlot.QUICK_OKLCH`）；
5. `QuickLayerWindow.kt`（`slot = UiPanelSlot.QUICK_LAYER`）；
6. `ReferenceWindow.kt`（`slot = UiPanelSlot.REFERENCE_WINDOW`，内层 `Box` 挂 `evadablePanelContent`）；
7. `ColorPanel.kt`（当 `vm.isColorPanelPinned` 时生效，`slot = UiPanelSlot.PINNED_COLOR_PANEL`）；
8. `LayerPanel.kt`（当 `vm.panelPinningEnabled && vm.isLayerPanelPinned` 时生效，`slot = UiPanelSlot.PINNED_LAYER_PANEL`）；
9. `BrushPanel.kt`（当 `vm.panelPinningEnabled && vm.isBrushPanelPinned` 时生效，`slot = UiPanelSlot.PINNED_BRUSH_PANEL`）。

### 7.7 `app/src/main/java/com/reverie/paint/ui/painting/canvas/RealInkController.kt`

- **线程协议**：`presentPending` 运行于 `reverie-render` 线程，使用预分配 `private val clipOverlapBounds = IntArray(4)`，零对象分配。
- **修改内容**：在 `presentPending(tiles: PublishedTiles)` 完成脏区烘焙并成功调用 `overlay.renderInkScreenRegions(...)` 后，检查是否有脏区与 `clipsSnapshot` 孔径相交：

```kotlin
clipOverlapBounds[0] = Int.MAX_VALUE
clipOverlapBounds[1] = Int.MAX_VALUE
clipOverlapBounds[2] = Int.MIN_VALUE
clipOverlapBounds[3] = Int.MIN_VALUE
var hasClipOverlap = false
if (!clipsSnapshot.isEmpty) {
    for (i in 0 until baked) {
        val d = i * 4
        if (clipsSnapshot.accumulateIntersection(
                screenRects[d],
                screenRects[d + 1],
                screenRects[d + 2],
                screenRects[d + 3],
                clipOverlapBounds,
            )
        ) {
            hasClipOverlap = true
        }
    }
}
if (hasClipOverlap) {
    // 脏区侵入了面板/阴影孔径：孔径内前缓冲已 clipOut 为全透明，
    // 立即投递孔径交集区域的局部主画布重绘，由主窗口 HWUI 将真实圆角、软阴影与 50% 面板叠在新墨之上。
    view.postInvalidateOnAnimation(
        clipOverlapBounds[0],
        clipOverlapBounds[1],
        clipOverlapBounds[2],
        clipOverlapBounds[3],
    )
}
```

### 7.8 `app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt`

- **线程协议**：UI 主线程独占，240Hz `ACTION_MOVE` 热路径 100% 标量运算，零分配。
- **修改内容**：
  1. 新增双向滞后评估函数 `evaluateUiEvasion50`（取代旧 `evaluateUiEvasion`）：

```kotlin
private const val EVADE_ENTER_DP = 32f
private const val EVADE_EXIT_DP = 40f // 32dp + 8dp 滞后
private const val POST_UP_CONTINUATION_MS = 80L

private val clearEvasionAfterUpRunnable = Runnable {
    if (!strokeStarted) {
        vm?.let { clearAllEvasionState(it) }
    }
}

private fun evaluateUiEvasion50(
    v: PaintViewModel,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    brushRadiusPx: Float,
) {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return
    if (!brushRadiusPx.isFinite() || brushRadiusPx < 0f) return
    if (!v.autoHidePanelsOnStroke) return
    val enterRadiusPx = EVADE_ENTER_DP * density + brushRadiusPx
    val exitRadiusPx = EVADE_EXIT_DP * density + brushRadiusPx
    for (i in UiPanelSlot.EVADABLE_ARRAY.indices) {
        val slot = UiPanelSlot.EVADABLE_ARRAY[i]
        val shouldEvade = v.panelOcclusion.evaluateSlotEvasion(
            slot, left, top, right, bottom, enterRadiusPx, exitRadiusPx,
        )
        if (v.slotEvading[slot.ordinal].value != shouldEvade) {
            v.slotEvading[slot.ordinal].value = shouldEvade
        }
    }
}
```

  2. 在 `handleToolDown` 中：取消 `clearEvasionAfterUpRunnable`，先调用 `rebuildUiExclusionSnapshot(force = true)`，再调用 `evaluateUiEvasion50(...)`。
  3. 在 `handleToolMove` 中：收集当前点、历史点、辅助绘图点与预测点（若 `hasPredictedScreenPoint` 为真则并入包络），在任何 `v.touchMove` 之前调用 `evaluateUiEvasion50(...)`。因此当笔在运笔途中移出 `40dp + brushR` 时，当帧立即将对应面板的 `slotEvading` 置为 `false` 恢复 100%。
  4. 在正常 `ACTION_UP` 结束笔画后：`removeCallbacks(clearEvasionAfterUpRunnable); postDelayed(clearEvasionAfterUpRunnable, POST_UP_CONTINUATION_MS)`；在 §4.4 所列的其余 11 条取消/中断/切工具/打开模态面板路径中，直接调用 `clearAllEvasionState(v)` 立即恢复 100%。

### 7.9 设置页迁移与国际化资源

1. **`SettingsTabPage.kt` 的“视图显示（VIEW）”子页**：在“面板固定与自由平移”下方插入“运笔靠近面板时半透明避让”开关行。按该页现有 `SettingGroup` / `ReSwitch` 模式落地，不要硬编码布局。

2. **`values/strings.xml` 与 `values-en/strings.xml`**：

```xml
<string name="auto_hide_panels_on_stroke_title">运笔靠近面板时半透明避让</string>
<string name="auto_hide_panels_on_stroke_desc">笔尖进入面板外围时内容变为 50% 半透明并保留边框，离开后立即恢复</string>
```

```xml
<string name="auto_hide_panels_on_stroke_title">Translucent panel evasion while drawing</string>
<string name="auto_hide_panels_on_stroke_desc">Fade panel content to 50% while keeping borders when the brush approaches, and restore immediately on exit</string>
```

---

## 8. 纯 Kotlin 单元测试与机械门禁

### 8.1 纯 Kotlin 单元测试（重写 `UiRingDetectorTest.kt`，建议改名 `UiPanelEvasionStateMachineTest.kt`）

全部测试基于纯 JUnit4（不依赖 Robolectric），覆盖以下 8 个必测断言：

1. **`enter_at_32dp_plus_brushRadius_sets_evaded_true`**：面板 `[100, 100, 200, 200]`，`enter = 32 + 10 = 42`，点在 `x = 242f` 时返回 `true`，在 `x = 242.1f` 时返回 `false`。
2. **`hysteresis_band_between_32dp_and_40dp_preserves_previous_state`**：
   - 未进入态下移动到距离 `36px`（处于 `32..40` 之间），保持 `false`；
   - 进入 `30px` 触发 `true` 后，回退到 `36px`（处于 `32..40` 之间），保持 `true` 不抖动；
   - 继续移动到 `40.1px`（`> 40`），无需抬笔立即变为 `false`。
3. **`point_inside_panel_has_zero_distance_and_enters_evasion`**：落点 `(150, 150)` 在面板内部，返回 `true`。
4. **`multi_panel_independent_evaluation`**：同时注册 `QUICK_ACTION` 与 `QUICK_COLOR`，笔靠近 A 远离 B 时仅 A 为 `true`；同时靠近 A 和 B 时两者均为 `true`；中途离开 A 靠近 B 时 A 立即恢复 `false`、B 保持 `true`。
5. **`shadow_outset_expands_clip_snapshot_without_inflating_32dp_trigger_distance`**：
   注册 `QUICK_COLOR`（bounds `[100, 100, 200, 200]`，`padH = 18, padT = 12, padB = 26`）：
   - 验证 `tracker.snapshot()` 导出的裁剪矩形为 `[82, 88, 218, 226]`（完整保护底部 26px 软阴影）；
   - 验证 `evaluateSlotEvasion` 仍以本体 `[100, 100, 200, 200]` 计算 32dp 距离（右边界触发点仍为 `232f` 而非 `250f`）。
6. **`evaded_panel_remains_in_clip_snapshot`**：即使某槽位 `isEvaded == true`（处于 50% 态），`tracker.snapshot()` 依然包含该槽位的孔径矩形，绝不撤除前缓冲裁剪。
7. **`clip_snapshot_accumulate_intersection_computes_exact_scissor_rect`**：验证 `UiClipSnapshot.accumulateIntersection` 对不相交脏区返回 `false`，对相交脏区精确输出交集包围盒 `[minL, minT, maxR, maxB]`。
8. **`clear_all_evasion_resets_every_slot_immediately`**：验证 `clearAllEvasion()` 将所有槽位 `isEvaded` 归零且不丢失已登记的 `bounds`。

### 8.2 机械编译与静态检查四道门禁

每次修改完成后，必须在工程根目录依次执行并 100% 通过：

```bash
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:compileDebugKotlin
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:testDebugUnitTest
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:lintDebug
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:assembleDebug
```

---

## 9. 真机验收矩阵、性能基线测量协议与用户裁决项

### 9.1 真机视觉与交互 12 项必验矩阵

1. **圆角四角零残影验证**：在不透明浅色/深色画布下开启 APP 软件预测，用粗画笔紧贴 `QuickActionWindow` / `QuickColorWindow` 四个圆角外弧涂抹并保持按住不抬笔，400% 放大截图对比抬笔前/后，确认**无任何直角边框残影，抬笔前后像素 diff 为 0**。
2. **底部软阴影零切断验证**：在不透明白纸画布下，用深色画笔紧贴悬浮面板底部阴影区来回运笔不抬笔，确认**面板底部柔和阴影完整叠在新笔迹上方，无刀切感**。
3. **50% 内容/背景 + 100% 边框/阴影验证**：笔尖进入面板外 `32dp + brushR`，确认面板外框线与阴影保持 100% 清晰，背景与内部图标平滑降至 50%。
4. **“面板在墨之上”合成顺序验证**：运笔穿过 50% 半透明面板内部并保持不抬笔，确认**面板半透明底色与图标压在新笔迹上方（墨从面板下方透出），且抬笔瞬间画面无跳变**。
5. **毛玻璃交叉淡化验证**：开启背景模糊，笔进入 32dp 时毛玻璃平滑淡出为 50% 纯色背景；笔移出 40dp 时毛玻璃平滑恢复，无卡顿或闪烁。
6. **运笔中途离开立即恢复验证**：一笔不抬起，从面板附近划向远处画布（距离 `> 40dp + brushR`），确认面板在运笔途中 70ms 内立即恢复 100% 不透明。
7. **多面板独立与并发验证**：将快捷操作与快捷颜色并排放置，笔经过两者之间使两者同时变 50%，随后偏向其中一侧，确认远离的面板先恢复 100%。
8. **高频排线/点画 80ms 防抖验证**：在面板边缘连续快速排线（抬笔间隔 `< 80ms`），确认面板稳定保持 50% 不在 50%/100% 间闪烁。
9. **绘画态优先交互验证**：从画布起笔划入面板内部按钮区，笔画连续不断；抬笔后直接点击面板按钮或拖拽面板，100% 正常响应。
10. **全预测模式与总开关验证**：在“视图显示”开关开启时，分别切换“关闭预测（原生模式）”、“硬件算法”、“APP 软件算法”，确认三种模式下均一致触发 50% 避让；关闭总开关后，面板保持 100% 不避让，但圆角与底部阴影依然无覆盖破绽。
11. **异常中断复位验证**：在面板处于 50% 时触发双指撤销、三指菜单、长按吸色、速创 650ms 停顿、切工具，确认面板均立即恢复 100%。
12. **Popup/Dialog、左手模式与 `paintingUiScale` 验证**：切换 `paintingUiScale`（`0.75x / 1.0x / 1.35x`）与左手模式，打开任意 Popup/Dialog，确认裁剪与避让边界丝毫不差、Popup 不被前缓冲覆盖。

### 9.2 性能基线测量协议与明确的用户裁决项

1. **测量方法**：
   在真机 120Hz/144Hz 下打开 `PerfHud` 与 `TilePerfLog`，在 4K 画布同时打开 5 个快捷浮窗 + 3 个钉住面板，对比 `fcfbc7dd` 与 v5.0：
   - **场景 A（画布自由区及面板外 `26dp ~ 32dp+brushR` 预警圈内运笔）**：
     验收门槛：前缓冲帧耗时变化 `<= ±0.05ms`，`TILE_UNDRAWN` 命中率 `= 100%`，`ACTION_MOVE` GC 分配 `= 0`。
   - **场景 B（画笔直接穿过面板阴影裙边或 50% 面板本体内部的那几帧）**：
     物理事实：为了在单窗口内让主窗口 HWUI 将真实 `Modifier.shadow` 与 50% 面板叠在新墨上方，被面板盖住的 1~2 张 512×512 瓦片在该重叠帧会执行 `postInvalidateOnAnimation(scissorRect)` 并走瓦片级 COW（预估 `~0.08ms/瓦片`，以真机实测为准），离开面板孔径后下一帧立即恢复 `TILE_UNDRAWN`。

2. **显式裁决规则（绝不静默取舍）**：
   - 若真机实测场景 B（画笔直接涂抹在面板肚子上时）的端到端感知延迟或帧率完全无感知差异（符合 `PerfHud` 帧预算），则直接以本方案交付；
   - 若真机实测发现某款设备在画笔穿过面板肚子那几帧因主窗口局部 `postInvalidateOnAnimation` 出现可感知的帧率波动，施工方必须立即停止并附上 `PerfHud` 实测数据上报用户，由用户裁决是否启用备选的“浮动 UI 独立 `TYPE_APPLICATION_PANEL` 硬件图层提升方案”，严禁私自退回 `ink(0.5) over panel` 近似方案。

---

## 10. 施工执行顺序（供施工会话直接按序推进）

1. **Step 1（清理旧草稿与升级模型层）**：修改 `UiPanelSlot.kt`、`UiClipSnapshot.kt`、`UiPanelOcclusionTracker.kt`，编写并跑通纯 Kotlin 单元测试门禁。
2. **Step 2（ViewModel 与设置页迁移）**：修改 `PaintViewModel.kt`、恢复 `StylusSettingsSubPage.kt`、在 `SettingsTabPage.kt`“视图显示”中加入总开关、更新中英文 `strings.xml`。
3. **Step 3（Compose 50% 三明治分层与毛玻璃交叉淡化）**：修改 `ReComponents.kt` 并接入全部 9 个可避让面板（`Quick*Window`、`ReferenceWindow`、`ColorPanel`、`LayerPanel`、`BrushPanel`）。
4. **Step 4（前缓冲孔径局部协同合成与双向滞后状态机接入）**：修改 `RealInkController.kt` 与 `CanvasTouchView.kt`，接通全部 12 条复位路径。
5. **Step 5（四道机械门禁与真机验收）**：执行 `compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`assembleDebug`，按 §9 验收矩阵完成真机验证。
