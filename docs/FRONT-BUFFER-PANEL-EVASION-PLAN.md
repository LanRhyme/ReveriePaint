# ReveriePaint 前缓冲笔迹预测与悬浮面板遮挡/空白根因剖析及智能渐隐避让执行计划

> **适用工程**：`E:\gongju\ReveriePaint` (`com.reverie.paint`)  
> **关联模块**：`FrontBufferPreviewOverlay.kt` / `RealInkController.kt` / `CanvasTouchView.kt` / `PaintingPage.kt` / `PaintingQuickWindows.kt` / `ReComponents.kt`  
> **设计目标**：在不降低当前 120/144Hz 帧率、4~8ms 单缓冲直出延迟、512 瓦片 `TILE_UNDRAWN` 就地写优化与热路径零分配铁律（`AGENTS.md §4`）的前提下：
> 1. 彻底根治“开启 App 软件预测算法后不抬笔时，快捷操作、颜色、图层等面板被笔迹与锯齿状背景覆盖”的缺陷；
> 2. 彻底根治“快捷操作面板右侧（及下侧）出现无形空白在抬笔前挡住笔迹、抬笔后恢复”的缺陷；
> 3. 修复同源排查发现的 7 项隐藏边界缺陷（手指落笔漏刷、界面缩放 `paintingUiScale` 密度错位、`MAX_UI_CLIPS=6` 截断、组件卸载残留拒墨洞等）；
> 4. 实现零重组、零分配、零瓦片退化的 **“运笔靠近面板自动平滑渐隐避让（Smart Stroke-Proximity Auto-Evade）”** 特性。

---

## 一、已知问题与深层根因全链路解剖

### 1.1 为什么开启“笔迹预测（App软件算法）”后，不抬笔时面板会被“笔迹 + 锯齿状背景”覆盖（开透明画布则只被笔迹覆盖）？

#### （1）物理层级根因：`SurfaceView.setZOrderOnTop(true)` 位于整个 Compose 窗口之上
- 当开启“笔迹预测 -> APP软件算法”时，`PaintViewModel.frontBufferPredictionEnabled = true`（`PaintViewModel.kt:1857, 1877`）。
- `CanvasView.kt:393-409` 会挂载 `FrontBufferPreviewOverlay`（继承自 `android.view.SurfaceView`），其 `init` 块（`FrontBufferPreviewOverlay.kt:238-239`）执行了：
  ```kotlin
  setZOrderOnTop(true)
  holder.setFormat(PixelFormat.TRANSLUCENT)
  ```
- 在 Android `WindowManager` / `SurfaceFlinger` 硬件合成树中，`setZOrderOnTop(true)` 创建的 `FrontBufferedSurfaceControl` 单缓冲层**物理位于整个应用主窗口（Application Window）的正上方**，而所有 Jetpack Compose 面板（快捷浮窗、固定面板、工具栏等）均绘制在底下的主窗口 HWUI 层中。
- 因此，在落笔到抬笔期间（`ACTION_DOWN` ~ `ACTION_UP`），只要 `FrontBufferPreviewOverlay` 向某块屏幕坐标写入了非透明像素，且没有通过 `canvas.clipOutRect(...)` 挖掉 UI 面板所在的矩形，前缓冲像素就会**直接在屏幕最顶层盖住 Compose 面板**。
- 抬笔瞬间（`ACTION_UP`），`RealInkController.onStrokeCommitted()` 触发主画布 `postInvalidate()`，并在主画布帧提交回调（`registerFrameCommitCallback`）中调用 `frontBufferOverlay.clearInk()` -> `renderer.commit()` 清空并隐藏单缓冲层（`RealInkController.kt:178-189`），画面交还给位于 Compose 面板下方的 `CanvasTouchView`，所以**抬笔后立刻恢复正常**。

#### （2）为什么不透明画布下会有“锯齿状（阶梯状）背景块”，而透明画布下“只被笔迹覆盖”？
- 在运笔按住期间，`RealInkController`（`RealInkController.kt:267-436`）每当后台渲染线程（`reverie-render`）更新完 512×512 显示瓦片（`PublishedTiles`），就会将本帧的离散脏区（最多 `RealInkPresent.MAX_DISCRETE_RECTS = 8` 块轴对齐矩形）从显示瓦片烘焙（`bakeRect`）进屏幕分辨率的 `slotBitmap`，再由 `FrontBufferPreviewOverlay.blitInk()` 用 **`PorterDuff.Mode.SRC`** 逐块贴到顶层 `SurfaceView` 上。
- 为防止斜向运笔时单一包围盒四角空白把已有前缓冲墨迹擦成透明洞，`RealInkPresent.appendDiscrete()`（`RealInkPresent.kt:158-206`）规定：**当两个脏区合并后的空心面积 ≥ 25% 时绝不合并，而是保留最多 8 个沿笔画轨迹呈阶梯状排列的独立轴对齐小矩形块**（每块外扩 `PAD_PX = 2`）。
- **不透明画布 vs 透明画布的像素差异**：
  - **不透明画布**：Krita 投影瓦片（`PublishedTiles`）包含**不透明的画布背景层（如白纸底色）+ 笔迹像素**。当这组沿斜线排列的阶梯状矩形块通过 `PorterDuff.Mode.SRC` 贴到最顶层 `SurfaceView` 时，每个小矩形内部的不透明画布底色与笔迹一起盖在了面板上，视觉上就形成了**沿笔画边缘呈锯齿状/阶梯状拼接的背景色块 + 笔迹覆盖面板**的现象。
  - **透明画布**：Krita 投影瓦片中无画布底色，笔迹以外的像素全部是 `0x00000000`（全透明），且 `RealInkController.bakeRect()`（第 442 行）特意设计为**不绘制透明棋盘格**（棋盘格仅在底层 `CanvasTouchView.onDraw` 中绘制）。因此 `slotBitmap` 矩形块内除笔迹外的区域保持全透明，贴到顶层后自然透出下方的 Compose 面板，故**只会被笔迹本身覆盖**。

---

### 1.2 为什么“速创面板”和“普通的设置面板”不会被覆盖，而“快捷操作、颜色、图层等面板”会被覆盖？

您的怀疑完全准确：**这两类面板在代码中确实走了两套截然不同的渲染互斥与防遮挡机制**，而“快捷浮窗 / 钉住面板”所依赖的第二套机制存在多处漏登与容量截断：

| 面板类别 | 包含的具体面板 | 当前防遮挡机制 | 为什么不会/会被覆盖（源码定位） |
|---|---|---|---|
| **第一类：模态/半模态覆盖面板**<br>（不会被覆盖） | 1. 普通设置面板 (`SettingsPanel`)<br>2. 绘图辅助与速创设置面板 (`DrawingGuidePanel`，内含 `QuickShapeSettingRow`)<br>3. 速创形状编辑顶栏 (`QuickShapeEditor` + `QuickShapeTopBar`)<br>4. 更多工具面板 (`AllToolsPanel`)<br>5. **未钉住**的画笔/图层/颜色面板 | **短路前缓冲真墨 (`overlayPanelsOpen = true`) + 全屏遮罩拦截** | 1. `PaintingPage.kt:700-703` 将 `settingsPanelOpen`、`drawingGuidePanelOpen`、`moreToolsOpen` 及未钉住的三大面板汇总为 `overlayPanelsOpen = true` 传入 `CanvasView`。<br>2. `RealInkController.kt:195, 207` 在 `wantsHold()` 和 `predictedTip()` 首行检查 `if (!held \|\| view.isTransformActive \|\| view.overlayPanelsOpen) return false`，**直接关闭前缓冲真墨驻留**，回退至位于 Compose 下层的 `CanvasTouchView` 常规渲染。<br>3. 速创形状识别触发时（`CanvasTouchView.kt:6865`）立即调用 `realInk.release()` 并进入 `isQuickShapeEditing` 全屏手势接管态，期间不走前缓冲绘制。 |
| **第二类：可边看边画的常驻浮窗与钉住态面板**<br>（会被覆盖） | 1. 快捷颜色浮窗 (`QuickColorWindow`)<br>2. OKLCH 调色浮窗 (`QuickOklchWindow`)<br>3. 快捷图层浮窗 (`QuickLayerWindow`)<br>4. **已钉住 (Pinned)** 的颜色面板 (`ColorPanel`)、图层面板 (`LayerPanel`)、画笔面板 (`BrushPanel`)<br>5. 快捷操作浮窗 (`QuickActionWindow`) | **保持 `overlayPanelsOpen = false`，依赖 `rebuildUiExclusionRects()` 传入 `clipOutRect` 裁剪** | 为了允许用户边开面板边画画，这些面板不设 `overlayPanelsOpen = true`，而是靠 `CanvasTouchView.rebuildUiExclusionRects()`（第 1018~1093 行）收集矩形并在 `FrontBufferPreviewOverlay.clipOutUi()` 中挖空。但代码中存在 **6 处直接 Bug** 导致裁剪失效（详列于下）。 |

#### 导致第二类面板被覆盖的 6 处具体代码缺陷：
1. **【缺陷 1：3 个常驻快捷浮窗在 `rebuildUiExclusionRects()` 中完全漏写】**  
   对比 `CanvasTouchView.kt` 的 `isHoverOverUi()`（第 2258~2380 行）与 `rebuildUiExclusionRects()`（第 1056~1091 行）：`rebuildUiExclusionRects()` 仅写入了 `referenceWindowOpen`、`quickActionWindowOpen`、`quickBrushWindowOpen`，**完全漏掉了 `v.quickColorWindowOpen`（快捷颜色）、`v.quickOklchWindowOpen`（OKLCH 调色）、`v.quickLayerWindowOpen`（快捷图层）**！这 3 个面板在前缓冲层没有任何裁剪保护，100% 被覆盖。
2. **【缺陷 2：钉住态（Pinned）的画笔、图层、颜色三大主面板完全漏写】**  
   当用户在 `ColorPanel`、`LayerPanel`、`BrushPanel` 上点击图钉固定面板后，`PaintingPage.kt:700-703` 将它们的 `overlayPanelsOpen` 置为 `false` 以放行画布绘画；但这 3 个组件既没有调用 `.reportUiBounds` 上报边界，`rebuildUiExclusionRects()` 中也完全没有它们的矩形，导致**固定后的颜色、图层、画笔面板 100% 被前缓冲覆盖**。
3. **【缺陷 3：`MAX_UI_CLIPS = 6` 硬编码容量过小导致后续面板被静默丢弃】**  
   `FrontBufferPreviewOverlay.kt:36` 定义了 `private const val MAX_UI_CLIPS = 6`，并在第 566 行执行 `if (clips >= MAX_UI_CLIPS) break`。而 `rebuildUiExclusionRects()` 前 3 项固定塞入了 `TopBar`、`ToolRail`、`SliderRail`，**留给所有浮动面板的槽位仅剩 3 个**！一旦同时打开超过 3 个浮窗或补齐上述漏登面板，超出的面板直接被截断丢弃。
4. **【缺陷 4：手指单指作画路径 `ACTION_DOWN` 完全漏调 `rebuildUiExclusionRects()`】**  
   在 `CanvasTouchView.kt:4416` 手写笔分支中有 `if (isDown) rebuildUiExclusionRects()`；但在第 `5027` 行的单指触控 `ACTION_DOWN`（以及第 `5115` 行边缘延迟落笔、第 `5134` 行长按吸色移动落笔）中**完全漏调了 `rebuildUiExclusionRects()`**。只要用户在进入画布后才打开或移动了快捷操作等浮窗，用手指（或被识别为 Touch 的输入源）绘制时使用的仍是 `onSizeChanged` 时的空表或旧坐标，导致快捷操作面板被覆盖！
5. **【缺陷 5：`QuickActionWindow` 按钮较多或首次打开未落笔时的尺寸/坐标越界】**  
   当快捷操作面板处于单行模式 (`ROW`) 且配置了较多按钮（宽度 > `380dp`）或单列模式 (`COLUMN`) 高度 > `380dp` 时，超出硬编码 `380dp` 的部分无裁剪保护而被覆盖。
6. **【缺陷 6：`#81` 折线/戳印预览路径 `drawPreviewPacket` 未执行 `clipOutUi`】**  
   在 `FrontBufferPreviewOverlay.kt:340` 的 `drawPreviewPacket()` 中，没有像 `drawInkPacket()` 那样调用 `clipOutUi(canvas, param)`，且 `renderPreviewPath` / `renderStampPreview` 未携带 `uiClips`。

---

### 1.3 为什么“快捷操作面板的右侧会出现空白，在抬笔前挡住笔迹，抬笔后笔迹正常”？

根因直接定位在 **`CanvasTouchView.kt` 第 1065~1071 行 (`rebuildUiExclusionRects`)**：

```kotlin
// CanvasTouchView.kt:1065-1071 (问题代码)
// 快捷操作浮窗 (若打开)
if (v.quickActionWindowOpen) {
    val qw = if (v.quickActionCollapsed) 90f * d else 380f * d
    val qh = if (v.quickActionCollapsed) 50f * d else 380f * d
    val qx = if (v.quickActionWindowX >= 0f) v.quickActionWindowX else ((w - qw) / 2f).coerceAtLeast(0f)
    val qy = if (v.quickActionWindowY >= 0f) v.quickActionWindowY else ((h - qh) / 2f).coerceAtLeast(0f)
    list.add(android.graphics.RectF(qx, qy, qx + qw, qy + qh))
}
```

#### 形成机制复盘：
1. **历史修改不同步**：在之前的提交中，为修复“快捷操作面板右侧悬浮时光标变成系统箭头”的问题，开发者将 `isHoverOverUi()`（第 2297~2316 行）改成了优先读取实测像素 `v.quickActionWindowWidth` 和 `v.quickActionWindowHeight`，但**遗漏了第 1066 行的 `rebuildUiExclusionRects()`**，使其仍然保留着硬编码的 **`380dp × 380dp`**！
2. **右侧与下侧的“幽灵拒墨区”**：
   - 快捷操作面板在单列模式 (`COLUMN`) 下实宽仅 **`56dp`**，双列网格 (`GRID_2`) 实宽约 **`110dp`**，三列网格 (`GRID_3`) 实宽约 **`160dp`**，短单行模式 (`ROW`) 实宽约 **`180~240dp`**（哪怕折叠态实宽也只有 `64~72dp`，小于硬编码的 `90dp`）。
   - 因为 `rebuildUiExclusionRects()` 硬塞了一个 `380dp` 宽、`380dp` 高的矩形给 `RealInkController.uiClips`，所以在快捷操作面板右侧（宽度差最高达 **`324dp`**）及下方区域，形成了一块肉眼看不见的 **`clipOutRect` 裁剪禁区**。
3. **为什么抬笔前挡住笔迹、抬笔后正常**：
   - **抬笔前**：`RealInkController.held == true`，新笔迹只在顶层 `FrontBufferPreviewOverlay` 上渲染，而底层 `CanvasTouchView` 在按住期间不触发 `invalidate()`。当笔迹划过快捷操作面板右侧的 `380dp` 幽灵禁区时，`FrontBufferPreviewOverlay` 执行 `canvas.clipOutRect` 把这段真墨裁掉了，底层 `CanvasTouchView` 又还没画这一笔，于是屏幕上就像有一块透明白板把笔迹挡住了一样！
   - **抬笔后**：`onStrokeCommitted()` 唤醒底层 `CanvasTouchView.postInvalidate()` 并清掉顶层前缓冲，底层主画布不受 `uiClips` 裁剪，完整画出整条笔画，笔迹瞬间恢复正常。

---

### 1.4 深度排查：还有哪些您尚未发现但同源的潜在隐患？（共 7 项）

通过对全工程 `ui/painting` 与 `core/` 的交叉排查，还存在以下 7 个同源隐患，必须在本次计划中一并根治：

1. **【隐患 A：`paintingUiScale`（界面缩放）导致参考窗与快捷笔刷窗排除区错位】**  
   `PaintingPage.kt:865-875` 使用 `CompositionLocalProvider(LocalDensity provides scaledDensity)` 将 UI 密度乘以了 `vm.paintingUiScale`（0.75x ~ 1.35x）。但 `CanvasTouchView.rebuildUiExclusionRects()` 和 `isHoverOverUi()` 中的 `val d = density` 是 View 原始系统密度（未乘 `paintingUiScale`）！  
   这导致 `ReferenceWindow`（`val rw = v.referenceWindowWidth * d`，第 1060 行）在界面缩放 ≠ 1.0 时，计算的排除框比真实参考窗偏小（缩放 > 1 时右下边缘被笔迹覆盖）或偏大（缩放 < 1 时右下侧出现空白挡笔迹）！
2. **【隐患 B：`Modifier.reportUiBounds` 在组件隐藏/销毁时未清空旧矩形，留下“隐形拒墨空洞”】**  
   `ReComponents.kt:1809` 的注释写道：`节点移除时不会回调, 上次的边界会留着`。当进入滤镜全屏调节 (`filterController != null`)、画布尺寸裁切 (`isCanvasAdjustActive`) 或关闭某个通过 `reportUiBounds` 注册的面板时，旧的 `RectF` 依然残留在 `PaintViewModel` 中，导致原本面板所在位置的画布在抬笔前无法显示新笔迹！
3. **【隐患 C：其余 7 个可在绘画时共存的浮动 UI 组件未纳入避让注册表】**  
   在画布处于画笔/橡皮/涂抹工具时，以下可能同时显示的 UI 组件均未纳入 `uiExclusionRects`：
   - 动画时间轴面板 (`AnimationTimelinePanel`, `vm.anim.enabled && vm.anim.panelOpen`)
   - 透光台对位胶囊 (`ShiftTraceCapsule`, `vm.anim.shiftTraceActive`)
   - 全局活跃选区提示胶囊 (`Global Active Selection Pill`, `vm.hasSelection && tool !in selectionTools`)
   - 独显模式底部胶囊 (`Solo-mode ToolFloatPanel`, `vm.soloActive`)
   - 选区历史悬浮面板 (`SavedSelectionsPopup`, `vm.savedSelectionsPopupOpen`)
   - 撤销/重做顶部胶囊提示 (`Action Toast`, `vm.undoToastEnabled && vm.actionToastMessage != null`)与缩放旋转指示器 (`TransformIndicatorHud`)
4. **【隐患 D：`QuickActionWindow` / `QuickBrushWindow` 折叠或展开动画期间的几何同步滞后】**  
   浮窗带有 `.animateContentSize()`。若仅靠 `windowWidth/Height` 字段拼接 `windowX/Y`（且当 `windowX < 0f` 初次居中时计算公式分散在多处），极易因初始值 `-1f` 或动画过渡态产生偏移。全部改为在浮窗实体 `Box`（不含外挂微型 Toast）上挂载 `Modifier.reportUiBounds` 直接读取 `coords.boundsInRoot()`，即可 100% 与屏幕真实渲染位置逐像素对齐。
5. **【隐患 E：不透明画布下，前缓冲真墨会局部盖住 `ShiftTraceOverlay`（透光台参考图）】**  
   在 `CanvasView.kt:356` 中，`ShiftTraceOverlay` 在 Compose 层绘制参考帧叠影。当 `vm.anim.shiftTraceActive == true` 且画布为不透明背景时，如果 `RealInkController.wantsHold()` 返回 `true`，顶层前缓冲贴上的不透明底色块会在运笔途把透光台参考图盖住。因此当 `vm.anim.shiftTraceActive` 开启且画布含不透明背景时，`RealInkController.wantsHold()` 应让位给主画布（仅保留 `#81` 笔尖前沿预览线），确保透光台叠影始终清晰可见。
6. **【隐患 F：若面板在运笔中途动态渐隐，前缓冲曾被 `clipOutRect` 裁掉的区域会出现“墨迹空洞”】**  
   当笔迹刚划过面板边缘、面板尚处于渐隐动画中时，`clipOutUi` 阻止了该区域的真墨写入前缓冲；等 90ms 后面板完全透明时，由于主画布在 `held` 期间未刷新、前缓冲该处又是透明的，面板消失后会露出没有当前笔画的旧画布，直到抬笔才闪出笔画。必须在面板完全透明移除 `uiClip` 的那一刻，把该面板矩形交集推入 `RealInkController` 补烤一次前缓冲（或从 `strokeLayer` 回填），实现无缝衔接。
7. **【隐患 G：异常中断路径（`ACTION_CANCEL` / 双指撤销 / 三指下滑菜单 / 速创长按成形）下面板避让状态锁死风险】**  
   若运笔触发了面板渐隐，随后用户未正常 `ACTION_UP` 而是双指轻点撤销、三指下滑呼出编辑菜单、或停顿 650ms 进入 `QuickShapeEditor`，必须在所有这些退出路径上兜底重置面板避让状态，严禁面板卡在 `alpha = 0f` 隐身状态。

---

## 二、方案权衡与总体架构设计

针对您的核心诉求——**“既要彻底修好遮挡与右侧空白 Bug，又想把笔迹画到面板时变成平滑自动渐变隐藏不挡视野，且绝不降低当前性能优化与延迟”**，我们采用 **“双层解耦架构：精确几何裁剪底座 + 零开销运笔智能渐隐避让”**：

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 第一层（必做底座）：统一实测 UI 边界注册表 (UiBoundsRegistry)                  │
│  • 废除一切手写 380dp / dp*density 估算公式                                   │
│  • 所有常驻欄、快捷浮窗、固定面板统一挂载生命周期安全的 reportUiBounds(slot)    │
│  • 组件退出组合 (onDispose) 自动清空对应槽位，彻底消灭“右侧空白”与“漏登被盖”   │
│  • FrontBufferPreviewOverlay.MAX_UI_CLIPS 从 6 扩容至 24，全路径(含#81)裁剪   │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 第二层（核心特性）：运笔智能渐隐避让引擎 (Smart Stroke-Proximity Auto-Evade)   │
│  • 落笔/运笔时，在 CanvasTouchView 用纯标量检测笔尖/预测点是否进入浮动面板的   │
│    外扩预警圈 R_enter (面板边界外扩 56dp + 笔刷屏幕半径)                       │
│  • 阶段 1 [预警渐隐期 ~90ms]：笔尖尚在外围 56dp 缓冲区内，置位位掩码           │
│    evadingPanelMask，Compose 侧仅通过 Modifier.graphicsLayer { alpha }        │
│    在 RenderThread 执行 90ms 极速淡出（零重组、零测量）；此期间面板仍在        │
│    uiClips 中，绝不让锯齿背景画到半透明面板脸上                               │
│  • 阶段 2 [完全穿透期 alpha=0f]：面板淡出完成的瞬间，从 RealInkController      │
│    动态移除该面板的 uiClip，并将面板矩形交集投递给 renderHandler 补烤一次      │
│    前缓冲槽位（不触发 CanvasTouchView.invalidate，100% 保留瓦片 TILE_UNDRAWN  │
│    就地写优化与 4~8ms 超低延迟！）                                            │
│  • 阶段 3 [抬笔防抖恢复]：本笔期间保持隐藏不反复横跳；抬笔并完成前缓冲追赶后   │
│    延迟 180ms（防高频排线闪烁），以 160ms 平滑淡入恢复 alpha = 1f             │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 为什么这套架构能做到“零性能回退、零额外延迟”？

1. **热路径 100% 零对象分配（符合 `AGENTS.md §4.4`）**：
   - 240Hz `ACTION_MOVE` 中只遍历预分配的固定长度数组（24 个槽位的 `FloatArray` 扁平坐标 `left, top, right, bottom`），做简单的浮点数大小比较。
   - 仅当某个面板从“未避让”首次变为“避让”的那 **1 帧**，才执行一次 `vm.evadingPanelMask = newMask`（位掩码写操作，每笔每面板最多触发 1 次，其余 99.9% 的 `ACTION_MOVE` 事件因位掩码已置位直接短路跳过）。
2. **Compose 侧零 Recomposition、零 Layout（符合高刷渲染规范）**：
   - 面板渐隐透明度通过 `Modifier.graphicsLayer { alpha = evadeAlphaState.value }`（Lambda 写法）注入。Compose 编译器对此仅在 **Draw / RenderNode 属性更新阶段** 读取状态，不会触发面板内部（如色轮、图层列表、按钮）的任何重组或重新测量！
3. **100% 保住 512 瓦片 `TILE_UNDRAWN` 原地写快速路径（符合 `AGENTS.md §4.3`）**：
   - 在面板渐隐穿透后，**绝不调用 `CanvasTouchView.invalidate()`**，而是直接复用 `RealInkController` 把该面板挡住的脏区补烤到 `FrontBufferPreviewOverlay`（并在 `strokeLayer` 已启用时直接从 `strokeLayer` 内存拷贝）。`PublishedTiles.drawnByView` 全程保持 `TILE_UNDRAWN`，无任何额外 COW 位图分配或 HWUI 整片纹理上传。

---

## 三、分步可执行修改计划（精确到文件、函数与代码实现）

### Task 1：升级 `UiBoundsRegistry` 与生命周期安全的 `Modifier.reportUiBounds`

**目标**：建立统一的、预分配的 UI 边界槽位注册表，彻底取代散落在 `PaintViewModel` 的零散 `measured*Rect` 字段与 `CanvasTouchView` 的手写 dp 估算，并保证组件卸载时自动清零槽位。

**涉及文件**：
- 新建：`app/src/main/java/com/reverie/paint/model/UiPanelSlot.kt`
- 修改：`app/src/main/java/com/reverie/paint/core/PaintViewModel.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`

- [ ] **Step 1.1：新建 `model/UiPanelSlot.kt` 定义全部 UI 避让槽位与可渐隐属性**

```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 画布顶层 UI 面板边界槽位定义。
 *
 * - [evadable] = true 表示该面板属于悬浮小窗或钉住态面板，当开启“运笔靠近面板自动渐隐”时，
 *   笔迹靠近会自动平滑淡出让出画布视野；
 * - [evadable] = false 表示常驻边缘栏（顶栏、工具栏、滑块栏等），仅做精确前缓冲裁剪，不自动隐藏。
 */
enum class UiPanelSlot(val evadable: Boolean, val defaultReferenceStyle: Boolean = false) {
    // 1. 常驻边缘停靠栏 / 胶囊 (不自动渐隐，仅精确裁剪前缓冲)
    TOP_BAR(evadable = false),
    TOOL_RAIL(evadable = false),
    SLIDER_RAIL(evadable = false),
    ANIMATION_TIMELINE(evadable = false),
    BOTTOM_TOOL_PANEL(evadable = false),
    TOP_STATUS_PILL(evadable = false),

    // 2. 常驻悬浮快捷小窗 (支持精确裁剪 + 运笔靠近自动渐隐)
    QUICK_ACTION(evadable = true),
    QUICK_BRUSH(evadable = true),
    QUICK_COLOR(evadable = true),
    QUICK_OKLCH(evadable = true),
    QUICK_LAYER(evadable = true),
    REFERENCE_WINDOW(evadable = true, defaultReferenceStyle = true),

    // 3. 钉住态 (Pinned) 三大主面板 + 选区历史浮窗 (支持精确裁剪 + 运笔靠近自动渐隐)
    PINNED_BRUSH_PANEL(evadable = true),
    PINNED_LAYER_PANEL(evadable = true),
    PINNED_COLOR_PANEL(evadable = true),
    SAVED_SELECTIONS_POPUP(evadable = true),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        const val COUNT = 16
    }
}
```

- [ ] **Step 1.2：在 `PaintViewModel.kt` 中维护零分配槽位表与避让状态掩码**

在 `PaintViewModel.kt` 的 `measuredTopBarRect` 所在位置（约第 1684 行），扩展为固定数组槽位（保留旧属性兼容访问，内部全部指向同一套槽位数组）：

```kotlin
    // ---- 前缓冲 UI 边界注册表 & 运笔靠近自动渐隐控制 ----
    // 每个槽位 4 个 float: [left, top, right, bottom] (root 屏幕像素坐标), right <= left 表示槽位未激活
    internal val uiSlotBounds = FloatArray(com.reverie.paint.model.UiPanelSlot.COUNT * 4)
    @Volatile
    internal var uiSlotVersion: Int = 0
        private set

    /** 运笔靠近悬浮面板时自动渐隐避让总开关 (默认开启，可在手写笔/界面设置中开关) */
    var autoHidePanelsOnStroke by mutableStateOf(true)

    /** 参考窗是否参与运笔自动渐隐 (默认关闭：画师通常需要边看参考窗边画，仅裁剪不隐藏；开启则同样渐隐) */
    var autoHideReferenceOnStroke by mutableStateOf(false)

    /** 当前正处于“运笔渐隐预警/隐藏态”的面板位掩码 (bit = 1 shl UiPanelSlot.ordinal) */
    var evadingPanelMask by mutableIntStateOf(0)

    /** 当前已完成淡出动画 (alpha <= 0.02f)、可从前缓冲 clipOutRect 中移除并穿透显示笔迹的面板位掩码 */
    @Volatile
    var fullyEvadedPanelMask: Int = 0

    internal fun updateUiSlotBounds(slot: com.reverie.paint.model.UiPanelSlot, l: Float, t: Float, r: Float, b: Float) {
        val base = slot.ordinal * 4
        if (uiSlotBounds[base] == l && uiSlotBounds[base + 1] == t &&
            uiSlotBounds[base + 2] == r && uiSlotBounds[base + 3] == b
        ) {
            return
        }
        uiSlotBounds[base] = l
        uiSlotBounds[base + 1] = t
        uiSlotBounds[base + 2] = r
        uiSlotBounds[base + 3] = b
        uiSlotVersion++
    }

    internal fun clearUiSlotBounds(slot: com.reverie.paint.model.UiPanelSlot) {
        val base = slot.ordinal * 4
        if (uiSlotBounds[base + 2] <= uiSlotBounds[base]) return
        uiSlotBounds[base] = 0f
        uiSlotBounds[base + 1] = 0f
        uiSlotBounds[base + 2] = 0f
        uiSlotBounds[base + 3] = 0f
        evadingPanelMask = evadingPanelMask and slot.bit.inv()
        fullyEvadedPanelMask = fullyEvadedPanelMask and slot.bit.inv()
        uiSlotVersion++
    }
```

- [ ] **Step 1.3：升级 `ReComponents.kt` 中的 `Modifier.reportUiBounds` 支持槽位绑定与 `onDispose` 自动清理**

修改 `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`（第 1809 行）：

```kotlin
/**
 * 将当前 Composable 节点的真实屏幕边界 (boundsInRoot, px) 注册到 [PaintViewModel] 的指定 [slot]，
 * 并在节点退出组合 (onDispose) 时自动清空该槽位，杜绝面板关闭后残留“隐形拒墨空洞”。
 *
 * 若该槽位支持运笔自动渐隐 ([UiPanelSlot.evadable] == true)，同时自动挂载基于 RenderNode 的
 * `graphicsLayer { alpha }` 平滑渐隐动画（零 Recomposition、零 Layout 开销）。
 */
internal fun Modifier.reportUiSlot(
    vm: com.reverie.paint.core.PaintViewModel?,
    slot: com.reverie.paint.model.UiPanelSlot,
): Modifier = composed {
    if (vm == null) return@composed this
    DisposableEffect(vm, slot) {
        onDispose {
            vm.clearUiSlotBounds(slot)
        }
    }
    val isEvading = slot.evadable && ((vm.evadingPanelMask and slot.bit) != 0)
    val targetAlpha = if (isEvading) 0f else 1f
    val animAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (isEvading) {
            // 笔迹靠近淡出：90ms 极速响应，确保笔尖跨过 56dp 预警圈到达面板前已完成透明化
            androidx.compose.animation.core.tween(
                durationMillis = 90,
                easing = androidx.compose.animation.core.FastOutLinearInEasing,
            )
        } else {
            // 抬笔恢复淡入：160ms 柔和归位
            androidx.compose.animation.core.tween(
                durationMillis = 160,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            )
        },
        finishedListener = { finalAlpha ->
            if (finalAlpha <= 0.02f && ((vm.evadingPanelMask and slot.bit) != 0)) {
                vm.onPanelFullyEvaded(slot)
            }
        },
        label = "evadeAlpha_${slot.name}",
    )
    // 当动画过程中 alpha 已经低于 0.02f 时提前放行穿透，无需干等动画末帧回调
    SideEffect {
        if (isEvading && animAlpha <= 0.02f && ((vm.fullyEvadedPanelMask and slot.bit) == 0)) {
            vm.onPanelFullyEvaded(slot)
        } else if (!isEvading && ((vm.fullyEvadedPanelMask and slot.bit) != 0)) {
            vm.fullyEvadedPanelMask = vm.fullyEvadedPanelMask and slot.bit.inv()
        }
    }
    this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                val b = coords.boundsInRoot()
                vm.updateUiSlotBounds(slot, b.left, b.top, b.right, b.bottom)
            }
        }
        .then(
            if (slot.evadable) {
                Modifier.graphicsLayer {
                    alpha = animAlpha
                }
            } else {
                Modifier
            }
        )
}
```

---

### Task 2：全量接入所有面板槽位，彻底消除“漏登被覆盖”与“380dp 空白挡笔迹”

**目标**：为所有常驻栏、6 个快捷悬浮窗、3 个钉住态大面板及底部浮动胶囊挂上 `.reportUiSlot(vm, UiPanelSlot.*)`，确保 **100% 实测屏幕像素边界同源**。

**涉及文件**：
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/TopBar.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/panels/ToolRail.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/quickaction/QuickActionWindow.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/quickbrush/QuickBrushWindow.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/quickcolor/QuickColorWindow.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/quickoklch/QuickOklchWindow.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/quicklayer/QuickLayerWindow.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/ReferenceWindow.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/panels/ColorPanel.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/layers/LayerPanel.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/brush/BrushPanel.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt`

- [ ] **Step 2.1：常驻三栏（`TopBar` / `ToolRail` / `SliderRail`）切换至 `reportUiSlot`**
  - `TopBar.kt:78`：將 `.reportUiBounds { vm.measuredTopBarRect = it }` 替换为 `.reportUiSlot(vm, UiPanelSlot.TOP_BAR)`。
  - `ToolRail.kt:283`：将 `.reportUiBounds { vm.measuredToolRailRect = it }` 替换为 `.reportUiSlot(vm, UiPanelSlot.TOOL_RAIL)`。
  - `ToolRail.kt:584`：将 `.reportUiBounds { vm.measuredSliderRailRect = it }` 替换为 `.reportUiSlot(vm, UiPanelSlot.SLIDER_RAIL)`。

- [ ] **Step 2.2：6 大常驻悬浮小窗挂载 `reportUiSlot`（注意挂在悬浮窗实体 `Box` 上，避开外挂微型 Toast）**
  - `QuickActionWindow.kt:153`（`// ---- 悬浮窗实体 ----` 的 `Box`）：在 `.onSizeChanged { ... }` 后追加 `.reportUiSlot(vm, UiPanelSlot.QUICK_ACTION)`。
  - `QuickBrushWindow.kt:146`（`// ---- 悬浮窗实体 ----` 的 `Box`）：在 `.onSizeChanged { ... }` 后追加 `.reportUiSlot(vm, UiPanelSlot.QUICK_BRUSH)`。
  - `QuickColorWindow.kt:187`（内层实体 `Box`）：在 `.onSizeChanged { ... }` 后追加 `.reportUiSlot(vm, UiPanelSlot.QUICK_COLOR)`。
  - `QuickOklchWindow.kt:228`（内层实体 `Box`）：在 `.onSizeChanged { ... }` 后追加 `.reportUiSlot(vm, UiPanelSlot.QUICK_OKLCH)`。
  - `QuickLayerWindow.kt:190`（内层实体 `Box`）：在 `.onSizeChanged { ... }` 后追加 `.reportUiSlot(vm, UiPanelSlot.QUICK_LAYER)`。
  - `ReferenceWindow.kt:149`（`// Floating Window Container` 的 `Box`）：在 `.size(vm.referenceWindowWidth.dp, vm.referenceWindowHeight.dp)` 后追加 `.reportUiSlot(vm, UiPanelSlot.REFERENCE_WINDOW)`。

- [ ] **Step 2.3：三大可钉住面板（`ColorPanel` / `LayerPanel` / `BrushPanel`）挂载 `reportUiSlot`**
  - `ColorPanel.kt:115`（`cardContent` 的根 `Column`）：当 `vm.isColorPanelPinned` 为 `true` 时，附加 `.reportUiSlot(vm, UiPanelSlot.PINNED_COLOR_PANEL)`（未钉住时由 `overlayPanelsOpen = true` 拦截，且调用 `vm.clearUiSlotBounds(UiPanelSlot.PINNED_COLOR_PANEL)`）。
  - `LayerPanel.kt:234`（`cardContent` 的根 `Column`）：当 `vm.panelPinningEnabled && vm.isLayerPanelPinned` 时，附加 `.reportUiSlot(vm, UiPanelSlot.PINNED_LAYER_PANEL)`。
  - `BrushPanel.kt:440`（`cardContent` 的根 `Column`）：当 `vm.panelPinningEnabled && vm.isBrushPanelPinned` 时，附加 `.reportUiSlot(vm, UiPanelSlot.PINNED_BRUSH_PANEL)`。

- [ ] **Step 2.4：其余画布共存浮动条（时间轴、选区历史、底部工具胶囊、顶部选区/透光台胶囊）挂载 `reportUiSlot`**
  - `PaintingPage.kt:2051` (`AnimationTimelinePanel` 容器)：附加 `.reportUiSlot(vm, UiPanelSlot.ANIMATION_TIMELINE)`。
  - `SavedSelectionsPopup.kt:76` (根 `Box`)：附加 `.reportUiSlot(vm, UiPanelSlot.SAVED_SELECTIONS_POPUP)`。
  - `ToolFloatPanel.kt:79` (根 `Row`)：附加 `.reportUiSlot(vm, UiPanelSlot.BOTTOM_TOOL_PANEL)`。
  - `PaintingPage.kt:1985` (Global Active Selection Pill) 与 `ShiftTraceCapsule.kt`：附加 `.reportUiSlot(vm, UiPanelSlot.TOP_STATUS_PILL)`。

---

### Task 3：扩容 `FrontBufferPreviewOverlay` 裁剪槽位并补齐 `#81` 预览裁剪与动态揭开（Uncover）

**目标**：
1. 将 `MAX_UI_CLIPS` 从 `6` 提升至 `24`（彻底消除多面板打开时的静默截断）；
2. 在 `#81` 预览路径（`drawPreviewPacket`）中也携带并应用 `clipOutUi`，防止整图回退路径下预览假线画到面板上；
3. 支持面板在运笔途中完全渐隐（`alpha == 0f`）时，在 GL 线程立即用 `strokeLayer` 备份层（若有）或渲染线程补烤脏区回填刚才被 `clipOutRect` 挖空的面板区域，杜绝面板渐隐后露出旧画布空洞。

**涉及文件**：
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/canvas/FrontBufferPreviewOverlay.kt`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/canvas/RealInkController.kt`

- [ ] **Step 3.1：在 `FrontBufferPreviewOverlay.kt` 中将 `MAX_UI_CLIPS` 扩容至 `24` 并为预览包补齐 `clipOutUi`**

```kotlin
// FrontBufferPreviewOverlay.kt:36
/** 前缓冲包最多携带的 UI 避让矩形 (覆盖全部 16 个 UiPanelSlot 并留冗余)。 */
private const val MAX_UI_CLIPS = 24
```

在 `drawPreviewPacket(canvas: Canvas, param: FrontBufferPathPacket)`（第 340 行）中补齐 `clipOutUi`：

```kotlin
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawPreviewPacket(canvas: Canvas, param: FrontBufferPathPacket) {
        canvas.save()
        clipOutUi(canvas, param)
        try {
            // 2a. STAMP 分级: 逐 dab 盖印真实笔尖位图
            if (param.stampCount > 0) {
                val stampBmp = param.stampBitmap
                val stampN = param.stampCount.coerceAtMost(MAX_STAMPS)
                if (stampBmp != null && !stampBmp.isRecycled) {
                    for (i in 0 until stampN) {
                        val s = param.stampSize[i]
                        if (s <= 0f) continue
                        val cx = param.stampX[i]
                        val cy = param.stampY[i]
                        stampPaint.alpha =
                            (param.stampAlpha[i] * 255f).toInt().coerceIn(0, 255)
                        val half = s / 2f
                        stampDst.set(cx - half, cy - half, cx + half, cy + half)
                        canvas.drawBitmap(stampBmp, null, stampDst, stampPaint)
                    }
                }
                return
            }

            // 2b. TIER_1/2: 绘制当前最新笔尖前沿段 (单色折线)
            val path = param.path ?: return
            previewPaint.color = param.color
            previewPaint.strokeWidth = param.strokeWidth.coerceAtLeast(1.5f)
            canvas.drawPath(path, previewPaint)
        } finally {
            canvas.restore()
        }
    }
```

同时在 `renderPreviewPath` 和 `renderStampPreview` 中传入当前有效的 `uiRects: Array<RectF>` 与 `uiRectCount: Int`，拷入 `packet.uiClips`。

- [ ] **Step 3.2：在 `RealInkController.kt` 中支持动态更新 `uiClips` 并对刚渐隐完成的面板区域补烤真墨**

当某个面板在运笔途中完成 90ms 渐隐动画（`alpha <= 0.02f`）时，调用 `RealInkController.onPanelUncovered(screenRect: RectF)`：
1. 更新 `RealInkController.uiClips`（剔除已隐藏的面板矩形）；
2. 将 `screenRect` 逆变换回显示位图坐标矩形 `[bl, bt, br, bb]`，调用 `RealInkPresent.appendDiscrete(pendingRects, pendingCount, bl, bt, br, bb)` 并触发一次 `renderHandler.post(repostRunnable)`！
3. 后台渲染线程立即将 `PublishedTiles` 中该面板下方的最新瓦片像素烤入 `slotBitmap` 并贴上 `FrontBufferPreviewOverlay`——**无需 `CanvasTouchView.invalidate()`，毫秒级无缝补齐面板下方的已画笔迹**！

```kotlin
    // RealInkController.kt 新增方法
    fun updateActiveUiClips(clips: Array<RectF>, count: Int) {
        uiClipCount = count
        uiClips = clips
    }

    /**
     * 当某个悬浮面板在运笔途中完成淡出 (alpha == 0f) 时调用：
     * 将该面板原先遮挡的屏幕矩形逆映射回位图坐标并加入 pendingRects 补烤一帧，
     * 使得此前被 clipOutRect 挡住的笔画立即在前缓冲层显现，且不触发主画布 invalidate。
     */
    fun uncoverScreenRect(screenL: Float, screenT: Float, screenR: Float, screenB: Float) {
        if (!held) return
        val p = pose ?: return
        if (p.scale <= 0.001f || p.bmpW <= 0 || p.bmpH <= 0) return
        // 将屏幕矩形四角逆变换回位图坐标包围盒
        val radians = -Math.toRadians(p.rotation.toDouble())
        val cosR = kotlin.math.cos(radians).toFloat()
        val sinR = kotlin.math.sin(radians).toFloat()
        val invSx = 1f / (p.scale * p.signX)
        val invSy = 1f / (p.scale * p.signY)
        var minBx = Float.MAX_VALUE
        var minBy = Float.MAX_VALUE
        var maxBx = -Float.MAX_VALUE
        var maxBy = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val sx = (if (i == 0 || i == 2) screenL else screenR) - p.centerX
            val sy = (if (i < 2) screenT else screenB) - p.centerY
            val rx = sx * cosR - sy * sinR
            val ry = sx * sinR + sy * cosR
            val bx = rx * invSx + p.halfW
            val by = ry * invSy + p.halfH
            if (bx < minBx) minBx = bx
            if (bx > maxBx) maxBx = bx
            if (by < minBy) minBy = by
            if (by > maxBy) maxBy = by
        }
        val bl = kotlin.math.floor(minBx).toInt().coerceIn(0, p.bmpW)
        val bt = kotlin.math.floor(minBy).toInt().coerceIn(0, p.bmpH)
        val br = kotlin.math.ceil(maxBx).toInt().coerceIn(0, p.bmpW)
        val bb = kotlin.math.ceil(maxBy).toInt().coerceIn(0, p.bmpH)
        if (br <= bl || bb <= bt) return
        synchronized(pendingLock) {
            pendingCount = RealInkPresent.appendDiscrete(pendingRects, pendingCount, bl, bt, br, bb)
        }
        frameDoneCallback()
    }
```

---

### Task 4：重构 `CanvasTouchView` 的 `rebuildUiExclusionRects`、`isHoverOverUi` 与运笔智能渐隐触发器

**目标**：
1. 彻底删除 `rebuildUiExclusionRects()` 中硬编码的 `380f * d` 及漏项代码，直接从 `vm.uiSlotBounds` 零分配同步激活槽位（排除已完全渐隐的槽位 `fullyEvadedPanelMask`）；
2. 在**手写笔落笔**与**手指单指落笔**（含边缘延迟落笔、长按吸色移动落笔）以及 `armRealInkHold` 中统一调用 `syncUiExclusionRects()`；
3. 实现零分配的 `checkStrokePanelEvade(curX, curY, predX, predY)`，在笔尖靠近可避让面板外围 `56dp + 笔刷半径` 时触发平滑渐隐，抬笔后带 `180ms` 防抖恢复。

**涉及文件**：
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt`

- [ ] **Step 4.1：用预分配池重构 `rebuildUiExclusionRects()` 与 `isHoverOverUi()`**

在 `CanvasTouchView.kt`（第 1013~1095 行）中，预分配 `MAX_UI_CLIPS` 个 `RectF` 槽位，彻底消除每次落笔新建 `ArrayList<RectF>` 的堆分配，并从 `vm.uiSlotBounds` 读取真实测量边界：

```kotlin
    // 预分配 UI 避让矩形池 (容量 24, 热路径零分配)
    private val uiExclusionPool = Array( com.reverie.paint.model.UiPanelSlot.COUNT) { android.graphics.RectF() }
    @Volatile
    private var uiExclusionCount: Int = 0
    private var lastSyncedSlotVersion: Int = -1
    private var lastSyncedEvadedMask: Int = 0

    // 抬笔后恢复面板显示的防抖延迟任务 (180ms 防高频排线/连续点画导致面板反复闪烁)
    private val restoreEvadedPanelsRunnable = Runnable {
        val v = vm ?: return@Runnable
        if (!localIsTouching && !realInk.held) {
            v.evadingPanelMask = 0
            v.fullyEvadedPanelMask = 0
        }
    }

    /**
     * 从 ViewModel 的实测槽位表同步当前需要裁剪的 UI 矩形（零对象分配）。
     * 已经完成淡出隐藏 (fullyEvadedPanelMask 对应 bit 为 1) 的面板不再裁剪，允许笔迹直接穿透显示。
     */
    internal fun rebuildUiExclusionRects() {
        val v = vm
        val w = width.toFloat()
        val h = height.toFloat()
        if (v == null || w <= 0f || h <= 0f) {
            uiExclusionCount = 0
            return
        }
        val bounds = v.uiSlotBounds
        val evadedMask = v.fullyEvadedPanelMask
        var count = 0
        val slots = com.reverie.paint.model.UiPanelSlot.entries
        for (i in 0 until slots.size) {
            val slot = slots[i]
            // 已完全透明避让的面板不再从前缓冲挖空
            if (slot.evadable && ((evadedMask and slot.bit) != 0)) continue
            val base = i * 4
            val l = bounds[base]
            val t = bounds[base + 1]
            val r = bounds[base + 2]
            val b = bounds[base + 3]
            if (r > l && b > t) {
                uiExclusionPool[count].set(l, t, r, b)
                count++
            }
        }
        uiExclusionCount = count
        lastSyncedSlotVersion = v.uiSlotVersion
        lastSyncedEvadedMask = evadedMask
        if (realInk.held) {
            realInk.updateActiveUiClips(uiExclusionPool, count)
        }
    }
```

- [ ] **Step 4.2：实现零分配的运笔靠近检测 `updateStrokePanelEvasion(curX, curY)`**

```kotlin
    /**
     * 运笔期间 (ACTION_DOWN / ACTION_MOVE) 检测笔尖及预测端点是否进入可避让面板的外扩预警区。
     * 纯标量计算，零对象分配；仅在位掩码发生 0 -> 1 跳变的那一帧写一次 ViewModel 状态。
     */
    private fun updateStrokePanelEvasion(curX: Float, curY: Float) {
        val v = vm ?: return
        if (!v.autoHidePanelsOnStroke) return
        val bounds = v.uiSlotBounds
        val curMask = v.evadingPanelMask
        var newMask = curMask

        // 预警外扩半径 = 56dp + 当前笔刷屏幕半径 (大笔刷提前避让，防止笔刷边缘先蹭到面板)
        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val brushScreenR = (v.brushSize.toFloat() * scale * 0.5f).coerceIn(0f, 120f * density)
        val approachHaloPx = 56f * density + brushScreenR

        val tipX = if (hasPredictedScreenPoint && predictedScreenX.isFinite()) predictedScreenX else curX
        val tipY = if (hasPredictedScreenPoint && predictedScreenY.isFinite()) predictedScreenY else curY
        val envMinX = minOf(curX, tipX) - approachHaloPx
        val envMaxX = maxOf(curX, tipX) + approachHaloPx
        val envMinY = minOf(curY, tipY) - approachHaloPx
        val envMaxY = maxOf(curY, tipY) + approachHaloPx

        val slots = com.reverie.paint.model.UiPanelSlot.entries
        for (i in 0 until slots.size) {
            val slot = slots[i]
            if (!slot.evadable) continue
            if (slot.defaultReferenceStyle && !v.autoHideReferenceOnStroke) continue
            val bit = slot.bit
            // 本笔已进入避让态的面板保持隐藏直到抬笔，不做中途反复横跳
            if ((newMask and bit) != 0) continue
            val base = i * 4
            val l = bounds[base]
            val t = bounds[base + 1]
            val r = bounds[base + 2]
            val b = bounds[base + 3]
            if (r <= l || b <= t) continue
            if (envMaxX >= l && envMinX <= r && envMaxY >= t && envMinY <= b) {
                newMask = newMask or bit
            }
        }
        if (newMask != curMask) {
            removeCallbacks(restoreEvadedPanelsRunnable)
            v.evadingPanelMask = newMask
        }
    }
```

- [ ] **Step 4.3：打通 `PaintViewModel.onPanelFullyEvaded(slot)` 到 `CanvasTouchView` 的实时揭开回调**

在 `PaintViewModel.kt` 中实现 `onPanelFullyEvaded(slot: UiPanelSlot)`：
```kotlin
    internal fun onPanelFullyEvaded(slot: com.reverie.paint.model.UiPanelSlot) {
        if ((evadingPanelMask and slot.bit) == 0) return
        if ((fullyEvadedPanelMask and slot.bit) != 0) return
        fullyEvadedPanelMask = fullyEvadedPanelMask or slot.bit
        val base = slot.ordinal * 4
        val l = uiSlotBounds[base]
        val t = uiSlotBounds[base + 1]
        val r = uiSlotBounds[base + 2]
        val b = uiSlotBounds[base + 3]
        val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (touchView != null) {
            touchView.rebuildUiExclusionRects()
            if (r > l && b > t) {
                touchView.realInk.uncoverScreenRect(l, t, r, b)
            }
        }
    }
```

- [ ] **Step 4.4：补齐所有落笔、运笔、抬笔与异常中断入口**
  1. **`armRealInkHold`（`CanvasTouchView.kt:3589`）**：首行调用 `rebuildUiExclusionRects()`，确保无论手写笔、手指单指、边缘延迟落笔还是长按吸色取消后落笔，100% 刷新最新 `uiExclusionPool`！
  2. **运笔移动（`handleToolMove` 中 `Tool.BRUSH, Tool.ERASER, Tool.SMUDGE` 分支）**：
     - 若 `v.uiSlotVersion != lastSyncedSlotVersion || v.fullyEvadedPanelMask != lastSyncedEvadedMask`，调用 `rebuildUiExclusionRects()`；
     - 调用 `updateStrokePanelEvasion(event.getX(pointerIndex), event.getY(pointerIndex))`。
  3. **抬笔与所有中断出口（`handleToolUp`、`ACTION_CANCEL`、双指撤销、三指菜单触发、`beginQuickShape`）**：
     - 统一调用 `scheduleRestoreEvadedPanels(immediate = isCancel)`：正常抬笔延迟 `180ms` 执行 `restoreEvadedPanelsRunnable`（若 180ms 内下一笔再次落下并命中预警区，`removeCallbacks(restoreEvadedPanelsRunnable)` 自动续期，排线/点画绝不闪烁！）；取消或切工具时立即恢复。
  4. **透光台保护（`RealInkController.wantsHold`）**：
     - 在 `RealInkController.kt:207` 的 `wantsHold()` 中增加：`if (v != null && v.anim.shiftTraceActive) return false`，防止不透明画布下前缓冲块遮挡透光台参考图。

---

### Task 5：设置项开关与国际化文案补充

**目标**：在设置面板（手写笔设置 / 界面设置）中提供“运笔靠近浮动面板时自动渐隐”及“参考窗同步渐隐”开关，遵循 `AGENTS.md §6` 国际化规范（同步更新 `values/strings.xml` 与 `values-en/strings.xml`）。

**涉及文件**：
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-en/strings.xml`
- 修改：`app/src/main/java/com/reverie/paint/ui/painting/panels/SettingsTabPage.kt`

- [ ] **Step 5.1：补充中英文 `strings.xml` 资源**
  - `values/strings.xml`：
    ```xml
    <string name="settings_auto_hide_panels_on_stroke">运笔靠近时自动渐隐浮动面板</string>
    <string name="settings_auto_hide_panels_on_stroke_desc">绘画笔迹靠近快捷浮窗或已固定的面板时平滑淡出避让，抬笔后自动恢复，避免遮挡视野</string>
    <string name="settings_auto_hide_reference_on_stroke">参考窗参与运笔自动渐隐</string>
    <string name="settings_auto_hide_reference_on_stroke_desc">开启后笔迹靠近参考窗时同样自动淡出；关闭则保持参考窗常亮显示</string>
    ```
  - `values-en/strings.xml`：
    ```xml
    <string name="settings_auto_hide_panels_on_stroke">Auto-Fade Floating Panels Near Stroke</string>
    <string name="settings_auto_hide_panels_on_stroke_desc">Smoothly fade out floating windows and pinned panels when drawing nearby, restoring them after pen-up</string>
    <string name="settings_auto_hide_reference_on_stroke">Auto-Fade Reference Window on Stroke</string>
    <string name="settings_auto_hide_reference_on_stroke_desc">Also fade out the Reference Window when drawing nearby; keep off to always show references</string>
    ```

---

## 四、机械验证与真机回归验收清单（遵循 `AGENTS.md §9`）

### 4.1 机械编译与单元测试门禁
每次完成代码修改后，必须在项目根目录依次执行并通过以下命令：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

### 4.2 新增单元测试用例（`app/src/test/java/com/reverie/paint/model/UiPanelEvasionTest.kt`）
- [ ] **测试 1（槽位生命周期）**：验证 `updateUiSlotBounds` 与 `clearUiSlotBounds` 在面板打开、移动、折叠、关闭时的坐标正确性，关闭后槽位宽高归零，无残留拒墨框。
- [ ] **测试 2（容量上限测试）**：验证同时激活 12 个 `UiPanelSlot`（顶栏 + 工具栏 + 滑块栏 + 6 个快捷浮窗 + 3 个固定面板）时，`uiExclusionCount == 12` 且无任何面板被截断丢弃。
- [ ] **测试 3（渐隐两阶段状态机）**：验证笔尖进入外扩预警区 `56dp + brushRadius` 时 `evadingPanelMask` 置位但 `fullyEvadedPanelMask` 未置位（此时面板仍受 `clipOutRect` 保护）；当 `onPanelFullyEvaded` 触发后该面板从 `uiExclusionPool` 移除，且逆变换计算出的补烤位图矩形 `[bl, bt, br, bb]` 精确覆盖面板区域。

### 4.3 真机逐项回归验收矩阵
- [ ] **场景 1（快捷操作面板右侧空白修复验证）**：打开快捷操作面板（分别切换单列、双列、三列、单行、折叠态），开启“笔迹预测 -> APP软件算法”，紧贴快捷操作面板右侧 2px 处连续运笔不抬笔，确认**笔迹 100% 实时跟手显示，无任何右侧或下侧空白遮挡**。
- [ ] **场景 2（全部面板防覆盖与锯齿背景消除验证）**：在不透明画布和透明画布下，同时打开快捷操作、快捷颜色、OKLCH 调色、快捷图层、快捷笔刷、参考窗，并将颜色/图层/画笔主面板钉住在画布上：
  - 关闭“运笔自动渐隐”开关时：手写笔与手指划过任意面板，确认**笔迹与锯齿底色块绝不盖在任何面板表面**，抬笔后笔画完整出现在面板下方的画布上；
  - 开启“运笔自动渐隐”开关时：运笔靠近面板约 `56dp` 处面板在 `90ms` 内丝滑渐隐至完全透明，笔迹在面板原位置以 4~8ms 满血前缓冲直出，**无任何锯齿背景切割半透明面板，也无旧画布空白断层**；抬笔 `180ms` 后面板平滑淡入恢复。
- [ ] **场景 3（高频排线防闪烁与异常手势中断验证）**：在悬浮面板位置快速连续点画/排线（抬笔间隔 < 150ms），确认面板保持隐藏不反复闪现；运笔中途触发双指撤销、三指下滑菜单或停顿 650ms 触发速创形状，确认面板均能正常恢复显示。
- [ ] **场景 4（界面缩放 `paintingUiScale` 一致性验证）**：在设置中将界面缩放分别调至最小（0.80x）与最大（1.30x），验证所有浮窗与参考窗的裁剪边界依然 100% 贴合视觉边缘。
- [ ] **场景 5（性能标尺零退化验证）**：在 Debug 包开启 `PerfHud`，对比改动前后的前缓冲帧耗时与 `TILE_UNDRAWN` 就地写命中率，确认 240Hz 触控采样下零 GC 分配、120/144Hz 帧率无掉帧。
