# ReveriePaint 前缓冲笔迹预测与悬浮面板遮挡/空白根因剖析及智能渐隐避让执行计划（v3 终版）

> **适用工程**：`E:\gongju\ReveriePaint`（`com.reverie.paint`）
> **分支**：`feat/front-buffer-panel-evasion`
> **状态**：计划终版（只读审查已完成，业务代码未改动）
> **审查基线**：2026-10-19 当前源码；文中行号以该基线为准，若后续漂移请以函数名/锚点为准
> **关联模块**：`FrontBufferPreviewOverlay.kt` / `RealInkController.kt` / `model/RealInkPresent.kt` / `CanvasTouchView.kt` / `PaintingPage.kt` / `PaintViewModel*.kt` / `ReComponents.kt`

---

## 0. 终版结论与六条不可违反的不变量

初版方案的方向正确，但存在 6 个阻断级问题与多项高风险。v3 的核心变化是：

> **把“Compose 动画结束回调 → 跨线程补烤面板矩形”这条异步链路彻底拆掉，改为“双圈同步前馈状态机”：外圈预警淡出，内圈接触的同一帧、在把触摸点提交给引擎之前，同步撤除该面板的 `uiClip` 并发布新的不可变裁剪快照。**

由此：

- 第一个真正落入面板范围的 dab 从未被裁剪过，主路径**不需要** `uncoverScreenRect`；
- 不再有整块面板矩形塞进 8 槽 `pendingRects` 的槽位膨胀；
- 不再需要 Compose 动画回调跨线程调用 `CanvasTouchView`，`core → ui` 反向依赖消失；
- 快速运笔时也不会出现“笔尖先撞上半透明面板、面板下是旧画布”的断层。

### 0.1 六条不变量

1. **单一裁剪数据源**：只保留一份不可变 `UiClipSnapshot`（扁平 `FloatArray` + `count`），`arm`、`previewEnvelopeHitsUi`、`RealInkController`、`renderInkScreenRegions` 全部读它；禁止同时维护 `uiExclusionRects` 与 `uiExclusionPool` 两套数据。
2. **跨线程只传不可变快照**：UI 线程构建并 `@Volatile` 发布；渲染线程只读。禁止共享可变 `RectF`，禁止渲染线程读取 UI 线程正在原地改写的数组。
3. **接触前同步撤 Clip**：在 `handleToolDown` 首行、`handleToolMove` 的 `startedNow`/`historySize` 循环/当前 `touchMove` 之前完成内圈判定与快照发布。先撤 Clip，再提交触摸点。
4. **动画回调不参与裁剪正确性**：`animateFloatAsState` 只负责视觉 alpha；裁剪的移除由内圈接触同步完成，不依赖动画结束回调。
5. **热路径零分配、零逐帧重组**：240Hz `ACTION_MOVE` 只做标量比较；alpha 用 `State` 对象在 draw 阶段读取，禁止 `val alpha by animateFloatAsState(...)` 这种在 composition 阶段拆箱的写法。
6. **可测逻辑与 Android 粘合层分离**：槽位表、快照生成、双圈碰撞、位掩码状态机全部放入 `model/` 纯 Kotlin 类，用纯 JUnit4 测试；不引入 Robolectric，不违反 `AGENTS.md §8`。

---

## 1. 根因解剖（已逐条对照源码核验）

### 1.1 物理层级：`setZOrderOnTop(true)` 的前缓冲层位于同一应用窗口的 HWUI 内容之上

- `FrontBufferPreviewOverlay` 继承 `SurfaceView`（`FrontBufferPreviewOverlay.kt:108-111`），`init` 在 `232-242`，其中：
  - `234`：`setZOrderOnTop(true)`
  - `235`：`holder.setFormat(PixelFormat.TRANSLUCENT)`
- `CanvasView.kt:393` 在 `vm.frontBufferPredictionEnabled && FrontBufferProbe.isSupported()` 时挂载该 overlay；`FrontBufferProbe` 还要求 API 29+ 且 `CanvasFrontBufferedRenderer` 可加载（`FrontBufferProbe.kt:23-37`）。**并非开关为 true 就必然挂载。**
- `PaintViewModel.kt:1857`：`frontBufferPredictionEnabled` 默认 `false`；`1876-1877`：开启后 `stylusPredictionAlgorithmType = "SOFTWARE"`（即“APP 软件算法”）。
- 该 SurfaceView 位于**同一应用窗口的 HWUI 内容之上**，但不高于独立 `Dialog`/`Popup` 窗口；其边界是 `CanvasView` 的 `fillMaxSize` 区域（`CanvasView.kt:260-275`、`393-412`；`PaintingPage.kt:629-663`）。`immersiveMode` 默认 `false`（`PaintViewModel.kt:3307`），非沉浸时 `MainActivity.kt:83-89` 调用 `setDecorFitsSystemWindows(true)`，所以**不是无条件覆盖整个物理屏幕**。overlay 自身没有 insets 处理。
- 抬笔清层不是 `onStrokeCommitted` 直接 `clearInk()`：
  - `RealInkController.onStrokeCommitted()`（`279-287`）只做 `release()`、`finishFrame(false)`、`view.postInvalidate()`；
  - 主画布 `drawCanvas` 末尾调用 `clearIfViewCaughtUp()`（`CanvasTouchView.kt:2996`），后者在 `177-201` 用 `registerFrameCommitCallback` 等主画布这一帧提交后再 `frontBufferOverlay.clearInk()`；
  - `clearInk → clearInternal`（`FrontBufferPreviewOverlay.kt:1003-1031`）最终 `commit()` 隐藏单缓冲层。
- 结论：**抬笔后恢复正常的机制成立**，但文档初版把 `onStrokeCommitted` 与 `clearIfViewCaughtUp` 混为一段，行号也需修正。

### 1.2 不透明画布的“阶梯底色块”与透明画布的“只盖笔迹”

- 前缓冲贴图用 `PorterDuff.Mode.SRC`：`FrontBufferPreviewOverlay.kt:197-201` 设置 `screenInkPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)`；`blitInk`（`782-819`）逐块 `canvas.drawBitmap(..., screenInkPaint)`。
- `bakeRect`（`RealInkController.kt:434-478`）明确**不铺棋盘格**：`436-437` 注释“没有文档像素的地方保持透明，SRC 之后由下层主画布透出”；棋盘格只在 `CanvasTouchView.kt:2914-2917` 绘制。
- 不透明底色来自背景层：`ReverieCore.h:78` 注明 layer 0 是白色、alpha-locked、锁定的背景层；`ReverieCoreDocument.cpp:508-523` 设置背景层与 `defaultProjectionColor`；`ReverieCoreRender.cpp:593,628-641` 从投影读像素。
- 透明画布：背景不可见时 `ReverieCoreDocument.cpp:518-522` 设置 `Qt::transparent`；`PixelAlpha.h:34-37` premultiply 后 `a=0` 时 RGB 也为 0。**前提是画布只有透明背景+笔迹层**；若还有其他可见图层/滤镜/液化预览，非笔迹像素不保证全透明。
- `appendDiscrete` 的真实规则（`model/RealInkPresent.kt`，**注意文件在 `model/`，不在 `canvas/`**）：
  - `21`：`PAD_PX = 2`；`24`：`MAX_DISCRETE_RECTS = 8`；
  - 普通追加在 `188` 用 `hollow * 4 < merged` 判断，即空心面积 < 25% 才合并；
  - **但列表满 8 块时**，`202-203` 调用 `mergeCheapestPair` **无条件**合并面积增益最小的一对，**不检查 25% 阈值**；文档初版“绝不合并”不成立；
  - `PAD_PX` 用在 `RealInkController.presentPending` 的 `bmpRectToScreenBounds`（`342-345`）与 `bakeSrcRect` 的 `PAD_PX + 2`（`RealInkPresent.kt:121`），**不是 `appendDiscrete` 的外扩**。
- 512 瓦片：`DisplayTileGrid.kt:113-115` `TILE = 512`；`DisplayTileState.kt:27` `drawnByView`，`30-32` `markDrawn` 置 `TILE_DRAWN`，`35` `TILE_UNDRAWN = 0`；`PaintViewModelTiles.kt:105,120-121` 用 CAS 就地写；`CanvasTouchView.kt:2864-2865` 在 `drawDisplayTiles` 里 `markDrawn`。
- **`TILE_UNDRAWN` 不是绝对保证**：只有主画布尚未 `onDraw` 该瓦片时才保持 `TILE_UNDRAWN`；一旦 `drawDisplayTiles` 执行即置 `TILE_DRAWN`。文档初版“全程保持”需改为条件性结论。

### 1.3 两套面板机制：为什么速创/设置不被覆盖，快捷操作/颜色/图层会被覆盖

#### 第一类：模态/半模态面板（不会被覆盖）

- `PaintingPage.kt:700-703`：
  ```kotlin
  overlayPanelsOpen =
      (brushPanelOpen && !(vm.panelPinningEnabled && vm.isBrushPanelPinned)) ||
      (layerPanelOpen && !(vm.panelPinningEnabled && vm.isLayerPanelPinned)) ||
      (colorPanelOpen && !vm.isColorPanelPinned) ||
      settingsPanelOpen || moreToolsOpen || drawingGuidePanelOpen
  ```
  注意：`color` 项只判断 `!vm.isColorPanelPinned`，没有 `panelPinningEnabled` 前缀；`brushStudioOpen`/`moreSettingsOpen` 不在 `overlayPanelsOpen` 内，只在 `isHoverOverUi` 的 `2227` 单独判断。
- `CanvasView.kt:345-349` 把该值写入 `touchView.overlayPanelsOpen`，随后更新系统手势排除区并 `invalidate()`。
- `CanvasTouchView.kt:220` 声明 `@Volatile var overlayPanelsOpen`；`2231` `if (overlayPanelsOpen) return true`。
- `RealInkController.kt:207`（`predictedTip`）与 `:222`（`wantsHold`）首行均为：
  ```kotlin
  if (!held || view.isTransformActive || view.overlayPanelsOpen) return false
  ```
- 速创：`CanvasTouchView.kt:6865` 在抬笔识别分支先 `realInk.release()` 再 `beginQuickShape(...)`；`PaintViewModelQuickShape.kt:30` 置 `isQuickShapeEditing = true`；`CanvasTouchView.kt:4210` 在 `isQuickShapeEditing` 时吞掉全部触摸。**`release` 由 `CanvasTouchView` 的抬笔识别分支调用，不是 `QuickShapeEditor` 组件调用。**

#### 第二类：常驻浮窗与钉住面板（会被覆盖，依赖裁剪）

- 当前 `rebuildUiExclusionRects()`（`CanvasTouchView.kt:1018-1093`）只登记：
  - `TopBar` / `ToolRail`（有实测值优先，否则硬编码）；
  - `SliderRail`（仅当 `measuredSliderRailRect != null`）；
  - `ReferenceWindow`；
  - `QuickActionWindow`；
  - `QuickBrushWindow`。
- **完全漏掉**：`QuickColorWindow`、`QuickOklchWindow`、`QuickLayerWindow`、钉住态 `ColorPanel`/`LayerPanel`/`BrushPanel`。
- **当前最多正好 6 项**（TopBar、ToolRail 无条件；SliderRail 条件；reference/quickAction/quickBrush 各 0/1），所以 `MAX_UI_CLIPS = 6` 的 `break`（`FrontBufferPreviewOverlay.kt:36,564-570`）**当前不会触发**；容量问题是在补齐漏登面板后才会出现。文档初版“多开面板时已被静默丢弃”需改为“补齐后会触发”。
- 手指 `ACTION_DOWN`（`CanvasTouchView.kt:5033-5105`）没有调用 `rebuildUiExclusionRects()`；边缘延迟落笔（`5131-5144`、`5249-5256`）也没有；手写笔路径在 `4409-4416` 有 `if (isDown) rebuildUiExclusionRects()`；`armRealInkHold`（`3589-3600`）直接 `realInk.arm(allowed, extras, uiExclusionRects)`，自身不 rebuild。
- 长按吸色（`878-895`、`5120-5122`、`5231-5246`）先 `realInk.release()`，移动只采样颜色，**不产生笔迹**，不需要为 ink 裁剪 rebuild；文档初版把它列为缺陷不成立。

### 1.4 快捷操作面板右侧/下侧“幽灵拒墨区”

- `CanvasTouchView.kt:1064-1069`（`rebuildUiExclusionRects`）仍然：
  ```kotlin
  val qw = if (v.quickActionCollapsed) 90f * d else 380f * d
  val qh = if (v.quickActionCollapsed) 50f * d else 380f * d
  ```
- `isHoverOverUi`（`2269-2297`）已优先读取 `v.quickActionWindowWidth/Height`（实测 px，由 `QuickActionWindow.kt:164-167` 的 `onSizeChanged` 写入）。
- **右侧幽灵区成立**：默认 `COLUMN` 实宽约 56dp、`GRID_2` 约 108dp、`GRID_3` 约 156dp、折叠约 72×42dp，均小于硬编码 380×380dp；右侧凭空多出最高约 324dp 的裁剪禁区。
- **下侧不普遍是幽灵区**：默认 `COLUMN` + 8 个默认动作实测高度约 412dp（`QuickActionWindow.kt:259-262` padding 6、`:266-276` 顶栏 24、`:394-398` Spacer 4、`:417-446` 8×38 按钮+8×4 间距+36 齿轮），大于硬编码 380dp，底部约 32dp 是**欠裁剪**（墨会盖住面板），不是幽灵区。`GRID/ROW/折叠`或动作较少时才是下方幽灵区。
- `ROW` 实宽不是 180~240dp：无标签约 `42N + 8` dp（默认 8 个动作约 344dp），有标签约 `56N + 8` dp（约 456dp）。默认配置右侧幽灵只有约 36dp；动作多或开标签时硬编码矩形反而偏小，是欠裁剪。
- 历史归因：当前仓库是**浅克隆（depth 110）**，`61d51c32` 是边界提交，无法验证“之前的提交改了 `isHoverOverUi` 但漏改 `rebuild`”的因果；只能确认两者当前不一致。文档应改为“当前不一致已确认；历史归因无法验证”。

### 1.5 同源隐患（逐条核验后的准确表述）

| 隐患 | 核验结果 | 准确描述 |
|---|---|---|
| A. `paintingUiScale` 密度错位 | **成立，但范围需收窄** | `PaintingPage.kt:870-880` 用 `scaledDensity` 应用 `paintingUiScale`；`CanvasTouchView` 用原始 `density`（`245`）做 dp 推导。三栏和已测量的快捷窗使用 px 实测值，错位集中在 `ReferenceWindow`（`1058-1061`）与快捷窗回退尺寸（`1065-1086`）。快捷窗自身 `screenWidthPx` 也用 `scaledDensity`（`PaintingQuickWindows.kt:41-44`），而 offset 用原始 px。实际 clamp 是 **0.70-1.40**；两个滑块分别是 0.70-1.30 与 0.75-1.35。 |
| B. `reportUiBounds` 卸载不清空 | **成立** | `ReComponents.kt:1802-1813`，`1807` 注释明确“节点移除时不会回调，上次的边界会留着”。当前只有 3 个调用点：`TopBar.kt:78`、`ToolRail.kt:283`、`ToolRail.kt:584`；旧 `measured*Rect` 的消费者只有 `CanvasTouchView`。 |
| C. 其余浮动 UI 漏登 | **成立，且需拆分** | `AnimationTimelinePanel`、`ShiftTraceCapsule`、Global Active Selection Pill、Solo-mode `ToolFloatPanel`、`SavedSelectionsPopup`、Action Toast、`TransformIndicatorHud` 均真实存在且在主 Compose 树（`SavedSelectionsPopup` 主 Box 在主树，只有重命名子对话框是 `Dialog`）。其中 `TOP_STATUS_PILL` 不能同时给 Global pill 与 ShiftTraceCapsule；`BOTTOM_TOOL_PANEL` 不能给所有 `ToolFloatPanel` 实例共用；Action Toast 与 TransformIndicatorHud 互斥（`PaintingPage.kt:2125` 在 `hasActionToast` 时隐藏 Transform HUD）。 |
| D. 动画期间几何滞后 | **不成立/弱** | 5 个快捷窗用 `animateContentSize`，时间轴用 `animateDpAsState`；`onSizeChanged` 位于 `animateContentSize` 之外（外层），能观察到动画中的实际尺寸。真正的问题是 dp 估算与实测混用，而不是动画滞后。 |
| E. 不透明画布下前缓冲盖住 `ShiftTraceOverlay` | **成立** | `ShiftTraceOverlay` 在主窗口 Compose 层；前缓冲 `setZOrderOnTop` 在其上；`shiftTraceActive` 是透光台对位模式；当前 `wantsHold` 没有该判断。 |
| F. 运笔中途渐隐后的旧画布空洞 | **成立** | 前缓冲 `clipOutUi` 不写入面板区域；主画布在 held/kept 期间不失效（例外：`tornInPlace` 等仍会补一次失效）；裁剪区下方是上一帧主画布（透明画布为棋盘格）。 |
| G. 异常中断路径状态锁死 | **成立** | `ACTION_CANCEL`、双指撤销、三指菜单、禁用画布触控、长按吸色、`abortActiveStrokeForHistory` 等存在绕过 `handleToolUp` 的直接 `release()` 路径；`localIsTouching` 在多指分支不复位。计划初版虽列出双指/三指/速创，但没有给出具体调用点，且 180ms runnable 的 guard 可能空转不重投。 |

### 1.6 v3 新增的 5 个深层盲区（本次二次核实）

#### 盲区 1：父节点拦截不能把触控透传给下层画布

- `PaintingPage.kt` 中浮窗 `zIndex` 为 50~100（`1034`、`1168`、`1280`、`2168`、`2202`、`2234` 等），`CanvasView` 是同级兄弟。
- Compose `LayoutNode.hitTest` 从高 `zIndex` 向下遍历；只要浮窗子树中任意子节点（按钮 `.clickable`、拖拽 `.pointerInput`、`.systemHoverIcon`）命中坐标且 `sharePointerInputWithSiblings() == false`（默认），`Box.hitTest` 就停止向下遍历，`CanvasTouchView` 收不到 `ACTION_DOWN`。
- 因此只在父节点挂 `PointerInputModifierNode` 只能吞点击，不能让触控穿透；画师在刚渐隐的面板原区域连续排线时，第 2 笔可能画不出笔迹。
- 终版方案：`UiSlotModifierNode` 在面板完全透明（`alpha <= 0.03f`）时把内层 `placeable` 放到屏幕外（有界偏移，不用 `1_000_000f`），使子树 `hitTest` 落空；半透明过渡期（`0.03 < alpha < 0.90`）用 `PointerEventPass.Initial` 消费点击防误触。**该方案必须先做 Compose spike 验证**（见 Task 0）。

#### 盲区 2：`strokeLayer` 是无裁剪的 CPU 备份层，可作为 GL 线程安全网

- `FrontBufferPreviewOverlay.kt:181-187` 声明 `strokeLayer: Bitmap?`、`strokeCanvas: Canvas?`、`strokeLayerDirty: Rect`。
- `drawInkPacket`（`718`）在 `748` 先 `blitInk(sc, param, bmp)` 写入 `strokeLayer`（**没有 `clipOutUi`**），并在 `751-758` 把每块屏幕矩形并入 `strokeLayerDirty`；随后 `758-761` 才对屏幕 `canvas` 执行 `clipOutUi + blitInk`。
- `ensureStrokeLayer`（`921-936`）分配 **全屏 `ARGB_8888`** 位图；`clearStrokeLayerDirty`（`938-946`）只清记过账的区域。
- 结论：`strokeLayer` 确实保存了当前笔画“未被 UI 裁剪”的像素，可在 GL 线程把 `pendingUncover ∩ strokeLayerDirty` 直接拷回屏幕，避免 `pendingRects` 竞态与大位图分配。
- **代价**：全屏 ARGB 位图（例如 1440×3200×4 ≈ 18MB）。当前只在 `needsBackup`（`wantsExtras`/光标/预测笔尖/`prevOverlayValid`）时分配。若自动渐隐强制 `extras=true`，会让每一笔都常驻这张全屏位图。**必须实测内存/帧耗时；必要时改为按脏区分配的分块备份层。**

#### 盲区 3：历史采样点在当前点之前提交给引擎

- `handleToolMove`（`5647`）：
  - `5659` `startedNow = !strokeStarted`；
  - `5696` 左右 `armRealInkHold(...)`；
  - `5712` `for (i in 0 until event.historySize)`；
  - `5722` 历史点 `v.touchMove(...)`；
  - `6162` 当前点 `v.touchMove(...)`。
- 若只在 `6162` 之前做避让检测，历史点已经以旧 `uiClips` 入队；高速划过折叠浮球时，历史线段可能穿过浮球而当前点已跨过。
- 终版方案：**物理轨迹同步**放在 `handleToolDown` 首行与 `handleToolMove` 的 `startedNow`/`historySize` 循环之前；**预测前瞻**只用于外圈预警，放在预测点算出后、`#81` 预览与 `6162` 当前点提交之前。

#### 盲区 4：`AnimationTimelinePanel` 外层是全宽容器

- `AnimationTimelinePanel.kt:152` 的外层 `Column(fillMaxWidth)` 包含：
  - `164` `AnimationSettingsCard`（在 `Box(fillMaxWidth, CenterEnd)` 内，`TimelineSettingsCard.kt:83` 宽 320dp 或竖屏 `screenWidth - 44`）；
  - `174` `StoryboardReferenceBar`；
  - `176` `TimelinePanelSurface`。
- 若把 `ANIMATION_TIMELINE` 挂在外层 `Column`，展开动画设置时 `boundsInRoot()` 会把左侧 75% 的透明画布一起算进去，形成巨型隐形 `clipOutRect`。
- 终版方案：`ANIMATION_TIMELINE` 挂在内层只包 `StoryboardReferenceBar + TimelinePanelSurface` 的容器；`AnimationSettingsCard` 单独挂 `ANIMATION_SETTINGS_CARD`。

#### 盲区 5：绕过 `handleToolUp` 的提前 return 与同槽位新旧节点竞争

- `CanvasTouchView` 中至少这些路径直接 `release()` 或提前 return，不经过 `handleToolUp`：
  - `draggingGuideHandleIndex != -1`（`4534`、`5226`）；
  - 长按吸色结束 `isLongPressPickerActive`（`4543`、`5231`）；
  - `isPendingEdgeFinger`（`5250`）；
  - `isPendingLongPress`（`5263`）；
  - `penOnlyMode`（`5275`）；
  - 双指/三指手势中断（`4604`、`4635`、`4666`、`4986`、`5000`）；
  - `isCanvasTouchDisabled`（`4306`）；
  - `abortActiveStrokeForHistory`（`413`）。
- `floatingUiConfig.enabled` 在停靠/悬浮间切换，或 `ToolFloatPanel` 在不同工具间切换时，旧节点 `onDetach` 可能晚于新节点 `onGloballyPositioned`，误清新节点坐标。
- 终版方案：统一 `scheduleRestoreEvadedPanels()` 在 `ACTION_UP`/`CANCEL` 最顶部（所有提前 return 之前）及上述路径调用；`restoreEvadedPanelsRunnable` 用 `strokeStarted || realInk.held` 判定活跃笔画并自重排；每个槽位用单调 `ownerToken`，`clearUiSlotBounds(slot, ownerToken)` 只在 token 匹配时清零。

### 1.7 文档事实修正表

| 类别 | 初版写法 | 修正 |
|---|---|---|
| 行号漂移 | `setZOrderOnTop` 238-239；`onStrokeCommitted` 178-189；`appendDiscrete` 158-206；`isHoverOverUi` 2258-2380；`wantsHold` 207；`rebuild` 1065-1071 | 以本文件当前行号为准；建议后续用“函数名 + 锚点”引用 |
| 文件路径 | `RealInkPresent.kt` 在 `canvas/` | 实际在 `app/src/main/java/com/reverie/paint/model/` |
| 合并规则 | `appendDiscrete` “绝不合并” | 普通追加受 25% 空心约束；满 8 块时 `mergeCheapestPair` 无条件合并最小增益对 |
| `PAD_PX` | `appendDiscrete` 的外扩 | 用于 `bmpRectToScreenBounds` 与 `bakeSrcRect` 阶段 |
| TILE_UNDRAWN | “全程保持” | 仅在主画布未 `onDraw` 该瓦片时保持；`drawDisplayTiles` 后置 `TILE_DRAWN` |
| 缩放范围 | 0.75-1.35；验收 0.80/1.30 | 实际 clamp 0.70-1.40；验收覆盖 0.70/1.0/1.40 |
| QuickShape | “停顿 650ms 进入 QuickShapeEditor” | 650ms 只识别并弹 toast；`beginQuickShape` 在抬笔 `handleToolUp` |
| Popup/Dialog | “所有 Compose 面板都在主窗口 HWUI 层” | 改为“主窗口内的 Compose 面板”；`CanvasEditMenu`、`TimelineMenuPopup`、`ReferenceSettingsPopup`、`CanvasEditMenuCustomizeDialog` 是独立窗口；`SavedSelectionsPopup` 主 Box 在主树，只有重命名子对话框是 Dialog |
| 快捷窗布局 | `ROW` 180-240dp；下侧普遍幽灵区 | 默认 8 动作 `ROW` 约 344dp（无标签）/456dp（有标签）；默认 `COLUMN` 高约 412dp，底部是欠裁剪 |
| `MAX_UI_CLIPS` | 当前已静默丢弃 | 当前最多 6 项，`break` 不触发；补齐后才会触发 |
| 槽位容量 | “24 个槽位” | 池固定为 `UiPanelSlot.entries.size`（终版 21）；`MAX_UI_CLIPS=24` 只是 packet 上限冗余 |
| 历史归因 | 之前提交漏改 | 浅克隆无法验证；只确认当前不一致 |
| 长按吸色 | 漏调 rebuild 的缺陷 | 该路径不产生笔迹，不需要 rebuild |
| 零重组 | `graphicsLayer` lambda 读取状态 | 初版代码用 `by` 在 composition 阶段读取，会逐帧重组；终版改用 `State` 对象 + draw 阶段读取 |

---

## 2. 总体架构

### 2.1 Phase 1：精确裁剪底座（必做，零行为风险）

目标：在不引入自动渐隐的前提下，100% 修好遮挡与右侧/下侧空白。

1. 建立单一不可变 `UiClipSnapshot`，替换 `uiExclusionRects` / `uiExclusionPool` 双轨。
2. 21 个槽位全部接入 `reportUiSlot` / `UiSlotModifierNode`，用 `boundsInRoot()` 实测 px 取代所有 dp 估算。
3. 所有落笔路径（手写笔、手指、边缘延迟、`armRealInkHold`）在提交前刷新并发布快照。
4. `renderInkScreenRegions` 接收 `count`，只遍历有效项；每次提交重置 `packet.uiClipCount`。
5. `#81` 预览包携带快照并执行 `clipOutUi`。
6. `presentPending` 改为“取快照即消费”，失败时把本次快照归还 `pendingRects`。
7. `ShiftTrace` 在 `armRealInkHold` 阶段按不透明背景门控，保留 `#81` 预览。
8. 修复所有绕过 `handleToolUp` 的恢复出口（Phase 1 只做裁剪与状态清理，不做渐隐）。

**Phase 1 完成后即可关闭初版报告中的全部遮挡/空白 Bug。**

### 2.2 Phase 2：双圈同步前馈渐隐避让

1. 外圈 `R_warn = R_P` 外扩 `64dp + 有效笔刷屏幕半径`：置位 `isEvading`，70ms 极速淡出；此时面板仍在 `uiClips` 中。
2. 内圈 `R_contact = R_P` 外扩 `有效笔刷屏幕半径 + 4dp`：在把本帧任何触摸点提交给引擎之前，同步：
   - `snapZero = true`（当帧 alpha=0，避免半透明面板上出现锯齿底色）；
   - 从快照中移除该槽位并 `@Volatile` 发布；
   - 再执行 `historySize` 循环与当前 `v.touchMove`。
3. 因为第一个落入面板的 dab 从未被裁剪，主路径**不需要** `uncoverScreenRect`。
4. 抬笔后 180ms 防抖恢复；新笔落下时若不在该面板预警圈内，立即清除该面板的隐藏位（per-stroke re-evaluation）。
5. `restoreEvadedPanelsRunnable` 若发现 `strokeStarted || realInk.held`，`postDelayed(this, 60L)` 自重排，不丢弃。
6. 完全透明期间通过 `UiSlotModifierNode` 把面板移出命中测试，保证“看不见就点不到”。

### 2.3 为什么主路径不再需要 `uncoverScreenRect`

- 初版的问题链是：面板在 `uiClips` 中 → 笔迹进入面板 → 被 `clipOutRect` 挖空 → 90ms 后动画回调 → 跨线程补烤 → `pendingCount = 0` 吞掉 / `slotBitmap` 膨胀。
- v3 把“撤 Clip”提前到第一个 dab 之前：笔迹从未在面板区域被裁剪，因此没有“需要补烤的历史空洞”。
- 唯一可能的例外是：面板在笔画中途才出现/移动，或触摸点到引擎的提交顺序被其他路径打乱。为此保留 `strokeLayer` 安全网（2.4），但它不是主路径，不参与正确性闭环。

### 2.4 `strokeLayer` GL 线程安全网（可选，默认关闭，先做 spike）

- 强制 `wantsExtras = cursorWhileDrawing || predictedTipEligible(v) || autoHidePanelsOnStroke`，使 `strokeLayer` 从首帧起可用。
- 面板 `alpha <= 0.03f` 时，UI 线程调用 `frontBufferOverlay.requestUncoverRect(l, t, r, b, snapshot)`；在 `submitLock` 内并入 `pendingUncover`。
- GL 线程在 `drawInkPacket` 末尾：
  - 计算 `pendingUncover ∩ strokeLayerDirty`；
  - 从 `strokeLayer` 直接 `drawBitmap` 到屏幕 `canvas`；
  - 清空 `pendingUncover`。
- 这样不经过 `pendingRects`，不触发 `presentPending` 的 `pendingCount` 清零，也不产生大位图分配。
- **必须实测**：全屏 `strokeLayer` 的内存峰值、首帧分配耗时、`drawBitmap` 拷贝耗时。若不可接受，改为按脏区分块备份层，或直接放弃安全网（Phase 2 的主路径不依赖它）。

### 2.5 数据流与线程协议

```
UI 线程 (CanvasTouchView / Compose)
  ├─ handleToolDown/Move:
  │    ├─ 物理轨迹包络 → UiPanelOcclusionTracker
  │    ├─ 外圈: 置 isEvading (per-slot State) → Compose 70ms fade
  │    ├─ 内圈: 置 snapZero + 从快照移除槽位
  │    └─ @Volatile publish UiClipSnapshot
  ├─ armRealInkHold: realInk.arm(allowed, extras, snapshot)
  └─ reportUiSlot: onGloballyPositioned → tracker.update(slot, ownerToken, bounds)

渲染线程 (reverie-render)
  ├─ presentTileFrame → appendDiscrete(pendingRects)
  ├─ presentPending:
  │    ├─ 锁内: append reclaimed + snapshotRects + pendingCount = 0 (消费)
  │    ├─ 锁外: bakeRect
  │    └─ 失败: 锁内把 snapshot 归还 pendingRects
  └─ renderInkScreenRegions(snapshot): 只读 [0, count)，packet.uiClipCount = count

GL 线程
  ├─ drawInkPacket: clipOutUi(packet) + blitInk
  ├─ drawPreviewPacket: clipOutUi(packet) + path/stamp
  └─ (可选) pendingUncover: strokeLayer → canvas
```

---

## 3. 数据模型

### 3.1 `model/UiPanelSlot.kt`（21 个槽位，零共享冲突）

```kotlin
package com.reverie.paint.model

enum class UiPanelSlot(
    val evadable: Boolean = false,
    /** 仅 5 个 Quick* 小窗默认参与自动渐隐。 */
    val defaultEvade: Boolean = false,
    /** 钉住大面板/参考窗：受 autoHidePinnedAndRefOnStroke 二级开关控制。 */
    val pinnedOrReference: Boolean = false,
) {
    // 常驻/非渐隐（10）
    TOP_BAR,
    TOOL_RAIL,
    SLIDER_RAIL,
    ANIMATION_TIMELINE,
    BOTTOM_TOOL_PANEL,      // ToolFloatPanel 通用槽位；ToolFloatPanel 增加 slot 参数
    SOLO_MODE_BAR,          // Solo-mode ToolFloatPanel 专用
    PICKER_SOURCE_BAR,      // PickerLayerSourceBar (PaintingPage.kt:2037)
    ACTIVE_SELECTION_PILL,
    SHIFT_TRACE_CAPSULE,
    TOP_HUD_TOAST,          // ActionToast 与 TransformIndicatorHud 互斥

    // 快捷小窗（7；其中 SAVED_SELECTIONS_POPUP / ANIMATION_SETTINGS_CARD 默认值待确认）
    QUICK_ACTION(evadable = true, defaultEvade = true),
    QUICK_BRUSH(evadable = true, defaultEvade = true),
    QUICK_COLOR(evadable = true, defaultEvade = true),
    QUICK_OKLCH(evadable = true, defaultEvade = true),
    QUICK_LAYER(evadable = true, defaultEvade = true),
    SAVED_SELECTIONS_POPUP(evadable = true, defaultEvade = false),
    ANIMATION_SETTINGS_CARD(evadable = true, defaultEvade = false),

    // 钉住大面板与参考窗（4）
    REFERENCE_WINDOW(evadable = true, pinnedOrReference = true),
    PINNED_BRUSH_PANEL(evadable = true, pinnedOrReference = true),
    PINNED_LAYER_PANEL(evadable = true, pinnedOrReference = true),
    PINNED_COLOR_PANEL(evadable = true, pinnedOrReference = true),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        val COUNT: Int get() = entries.size
    }
}
```

说明：

- `TOP_HUD_TOAST` 共享是安全的，因为 `PaintingPage.kt:2125` 在 `hasActionToast` 时隐藏 `TransformIndicatorHud`；若未来允许同时显示，必须拆成两个槽位。
- `BOTTOM_TOOL_PANEL` 共享是安全的，因为工具面板同一时刻只显示一个；但 `ToolFloatPanel` 必须增加 `slot: UiPanelSlot = BOTTOM_TOOL_PANEL` 参数，Solo 调用传 `SOLO_MODE_BAR`。若发现两个工具面板可同时可见，再拆分。
- **槽位总数是 21，不是 23**。初版讨论中的 22/23 是把 `TOOL_FLOAT_PANEL`/`SELECTION_FLOAT_PANEL`/`ACTION_TOAST_PILL`/`TRANSFORM_HUD_PILL` 分别计数；终版按实际互斥关系合并。`MAX_UI_CLIPS = 24` 留 3 个冗余。
- `SAVED_SELECTIONS_POPUP` 与 `ANIMATION_SETTINGS_CARD` 的默认渐隐值存在产品口径冲突（见 §7），先按 `defaultEvade = false` 落地，待确认。

### 3.2 `model/UiClipSnapshot.kt`

```kotlin
package com.reverie.paint.model

/**
 * 不可变裁剪快照。flat 每项 4 个 float: left, top, right, bottom（root 屏幕像素）。
 * 构建后禁止修改；UI 线程构建并通过 @Volatile 发布，渲染线程只读。
 */
class UiClipSnapshot private constructor(
    val flat: FloatArray,
    val count: Int,
) {
    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean {
        var i = 0
        while (i < count * 4) {
            if (r >= flat[i] && l <= flat[i + 2] && b >= flat[i + 1] && t <= flat[i + 3]) return true
            i += 4
        }
        return false
    }

    fun contains(x: Float, y: Float): Boolean {
        var i = 0
        while (i < count * 4) {
            if (x >= flat[i] && x <= flat[i + 2] && y >= flat[i + 1] && y <= flat[i + 3]) return true
            i += 4
        }
        return false
    }

    companion object {
        val EMPTY = UiClipSnapshot(FloatArray(0), 0)

        /** 从工作数组复制有效项；仅在低频边沿调用。 */
        fun copyOf(src: FloatArray, count: Int, max: Int): UiClipSnapshot {
            val n = count.coerceIn(0, minOf(max, src.size / 4))
            if (n == 0) return EMPTY
            val out = FloatArray(n * 4)
            System.arraycopy(src, 0, out, 0, n * 4)
            return UiClipSnapshot(out, n)
        }
    }
}
```

### 3.3 `model/UiPanelOcclusionTracker.kt`（纯 Kotlin，可单测）

职责：

- 维护 21 个槽位的 `FloatArray(COUNT * 4)` 与 `IntArray(COUNT)` 的 `ownerToken`；
- `update(slot, ownerToken, l, t, r, b)`：只有 token 匹配才写入；bounds 变化时递增 `version`；
- `clear(slot, ownerToken)`：只有 token 匹配才清零，并清掉该槽位的 evading/snapZero 位；
- `snapshot(evadedMask, unclippedMask)`：把“激活且未被 unclip”的槽位复制成 `UiClipSnapshot`；
- `hitTest(x, y)` / `intersects(l,t,r,b)`：供 `isHoverOverUi` 与 `previewEnvelopeHitsUi` 使用；
- `ringState(x, y, brushRadiusPx, warnPx, contactPx)`：返回 `WARN` / `CONTACT` / `NONE`，纯标量、零分配；
- 所有方法不依赖 Android；`RectF` 只在 UI 层做临时转换，或完全用 `FloatArray`。

单测覆盖：

- 槽位生命周期：打开/移动/折叠/关闭/重开；
- `ownerToken` 竞争：旧节点 `clear` 不影响新节点；
- 快照：4 项→3 项、池尾不残留、count 正确；
- 双圈：外圈只 warn、内圈 contact、笔刷半径/密度/缩放；
- 掩码：evaded/unclipped 位在抬笔/取消/切工具后归零。

### 3.4 `PaintViewModel` 状态

```kotlin
// core/PaintViewModel.kt（只持有纯数据与 Compose 状态，不引用 CanvasTouchView）
val panelOcclusion = com.reverie.paint.model.UiPanelOcclusionTracker()

/** 每个槽位独立的 Compose 状态，避免全局 mask 牵连所有面板重组。 */
internal val slotEvading = Array(UiPanelSlot.COUNT) { mutableStateOf(false) }
internal val slotSnapZero = Array(UiPanelSlot.COUNT) { mutableStateOf(false) }

/** 供渲染线程读取的不可变裁剪快照。 */
@Volatile
internal var uiClipSnapshot: UiClipSnapshot = UiClipSnapshot.EMPTY

/** 本笔已进入内圈、已从快照移除的面板位。 */
@Volatile
internal var unclippedPanelMask: Int = 0

/** 本笔已进入外圈的面板位。 */
@Volatile
internal var evadingPanelMask: Int = 0

/** 自动渐隐总开关（默认 true，持久化）。 */
var autoHidePanelsOnStroke by mutableStateOf(true)

/** 钉住面板/参考窗是否参与渐隐（默认 false，持久化）。 */
var autoHidePinnedAndRefOnStroke by mutableStateOf(false)
```

设置持久化：

- `updateAutoHidePanelsOnStroke(enabled)` / `updateAutoHidePinnedAndRefOnStroke(enabled)` 写 `paint_prefs`；
- 启动时读回；默认值如上；
- 新增中英文 `strings.xml` 文案（`values/` 与 `values-en/`）。

### 3.5 `canvasBackgroundOpaque`（替代不存在的 `isBackgroundTransparent`）

源码中**没有** `isBackgroundTransparent` 字段。终版用背景图层镜像派生：

```kotlin
/** 画布背景是否不透明：背景层可见且 opacity≈1（背景层是 alpha-locked 的白色层）。 */
val canvasBackgroundOpaque: Boolean
    get() = layers.firstOrNull { it.isBackground }
        ?.let { it.visible && it.opacity >= 0.999 } ?: false
```

- `LayerUiState` 已有 `isBackground` / `visible` / `opacity`（`PaintViewModel.kt:4611-4638`）。
- 若后续发现背景层可能是非白色/半透明，再补 `fillColor` 的 alpha 判断。
- 该属性只在 `armRealInkHold` 与 ShiftTrace 门控中读取，不在 240Hz 热路径。

---

## 4. 任务拆解

### Task 0（Phase 2 前置）：Compose 命中穿透 spike

**目的**：验证 `UiSlotModifierNode` 的“完全透明时把内层 placeable 移到屏幕外”是否真的能让子树不参与命中测试，同时不影响 `boundsInRoot`、布局、无障碍与动画。

**验收**：

1. 写一个最小 Compose demo：`Box` 里放一个 `zIndex` 高的可点击面板，下面放一个可点击画布；面板 alpha=0 且移出屏幕后，点击必须落到画布；alpha=0.5 时点击被面板的 `Initial` 消费，不落到画布。
2. `onGloballyPositioned` 上报的 bounds 在透明/非透明两种状态下都与真实视觉位置一致。
3. 用有界偏移（如 `-screenWidth - width`），不要用 `1_000_000f`；确认 RenderNode 无异常、无过度绘制。
4. 若 spike 失败：回退方案为 `LocalUiPanelInteractive` CompositionLocal + 面板交互层条件禁用（见 Task 6），并在文档记录结论。

**只有 Task 0 通过，Phase 2 的“输入穿透”才可开工。**

### Task 1（Phase 1）：纯 Kotlin 数据模型与单测

**文件**：

- 新建 `app/src/main/java/com/reverie/paint/model/UiPanelSlot.kt`
- 新建 `app/src/main/java/com/reverie/paint/model/UiClipSnapshot.kt`
- 新建 `app/src/main/java/com/reverie/paint/model/UiPanelOcclusionTracker.kt`
- 新建 `app/src/test/java/com/reverie/paint/model/UiPanelOcclusionTrackerTest.kt`
- 新建 `app/src/test/java/com/reverie/paint/model/UiClipSnapshotTest.kt`
- 新建 `app/src/test/java/com/reverie/paint/model/UiRingDetectorTest.kt`

**验收**：

- `./gradlew :app:testDebugUnitTest` 全绿；
- 覆盖 §3.3 列出的边界；
- 不引入 Robolectric / Android 依赖。

### Task 2（Phase 1）：槽位接入

**原则**：

- 全部用 `boundsInRoot()` 实测 px，不再用 `dp * density` 估算；
- `reportUiSlot` / `UiSlotModifierNode` 必须挂在**最外层视觉容器首部**（在 `.shadow`、`.clip`、`.background`、`.hazeChild` 之前），确保 alpha 作用于阴影、背景、边框与内容整体；
- 每个槽位带 `ownerToken`，避免新旧节点竞争；
- `DisposableEffect.onDispose` 只在 token 匹配时清槽。

**接入点（当前源码）**：

| 槽位 | 组件 / 位置 | 备注 |
|---|---|---|
| `TOP_BAR` | `TopBar.kt:78` | 替换 `reportUiBounds` |
| `TOOL_RAIL` | `ToolRail.kt:283` | 替换 `reportUiBounds` |
| `SLIDER_RAIL` | `ToolRail.kt:584` | 替换 `reportUiBounds` |
| `ANIMATION_TIMELINE` | `AnimationTimelinePanel.kt` 内层容器（`174` 的 `StoryboardReferenceBar` + `176` 的 `TimelinePanelSurface` 外层，**不要挂 `152` 的外层 Column**） | 避免全宽空洞 |
| `ANIMATION_SETTINGS_CARD` | `TimelineSettingsCard.kt:73` 的 `AnimationSettingsCard` 根容器 | evadable |
| `BOTTOM_TOOL_PANEL` | `ToolFloatPanel` 增加 `slot` 参数，默认 `BOTTOM_TOOL_PANEL`；各工具调用点不变 | 通用工具面板 |
| `SOLO_MODE_BAR` | `PaintingPage.kt:1785` 的 Solo 调用传 `slot = SOLO_MODE_BAR` | 与通用面板分离 |
| `PICKER_SOURCE_BAR` | `PaintingPage.kt:2037` / `PaintingSelectionPanels.kt:95` | |
| `ACTIVE_SELECTION_PILL` | `PaintingPage.kt:1985` 的 Box | |
| `SHIFT_TRACE_CAPSULE` | `ShiftTraceCapsule.kt:57` / `PaintingPage.kt:2142` | |
| `TOP_HUD_TOAST` | `PaintingPage.kt:2059` ActionToast 与 `2122` TransformIndicatorHud | 二者互斥；若同时显示需拆分 |
| `QUICK_ACTION` | `QuickActionWindow.kt:162` 实体 Box | 替换 380dp 硬编码 |
| `QUICK_BRUSH` | `QuickBrushWindow.kt:150` 实体 Box | |
| `QUICK_COLOR` | `QuickColorWindow.kt:188` 内层实体 Box | |
| `QUICK_OKLCH` | `QuickOklchWindow.kt:211` 内层实体 Box | |
| `QUICK_LAYER` | `QuickLayerWindow.kt:181` 内层实体 Box | |
| `SAVED_SELECTIONS_POPUP` | `panels/SavedSelectionsPopup.kt:75` 根 Box | 主 Box 在主树；重命名 Dialog 不注册 |
| `REFERENCE_WINDOW` | `ReferenceWindow.kt:155` 的 `.size(...)` 容器 | evadable，默认不渐隐 |
| `PINNED_COLOR_PANEL` | `ColorPanel.kt:254` 的 `if (vm.isColorPanelPinned)` 分支 | **注意：没有 `panelPinningEnabled` 前缀** |
| `PINNED_LAYER_PANEL` | `LayerPanel.kt:424` 的 `if (vm.panelPinningEnabled && vm.isLayerPanelPinned)` 分支 | |
| `PINNED_BRUSH_PANEL` | `BrushPanel.kt:1450` 的 `if (vm.panelPinningEnabled && vm.isBrushPanelPinned)` 分支 | |

**不注册的独立窗口**：`CanvasEditMenu`、`TimelineMenuPopup`、`ReferenceSettingsPopup`、`CanvasEditMenuCustomizeDialog`，以及 `SavedSelectionsPopup` 的重命名子 `Dialog`。它们是 WindowManager 独立窗口，`boundsInRoot` 是弹窗局部坐标，不能直接当画布坐标。

### Task 3（Phase 1）：`CanvasTouchView` 单一快照与全落笔路径同步

1. 删除 `uiExclusionRects` 与 `uiExclusionPool` 双轨，改为 `@Volatile var uiClipSnapshot: UiClipSnapshot`。
2. `rebuildUiExclusionSnapshot()`：
   - 从 `vm.panelOcclusion.snapshot(evadingMask, unclippedMask)` 构建；
   - 只在 `slotVersion` 变化、`arm`、内圈撤 Clip、槽位 `onDispose` 时调用；
   - 构建后 `realInk.updateActiveUiClips(snapshot)`（若 `held`）。
3. `armRealInkHold` 首行 `rebuildUiExclusionSnapshot()`，并把 snapshot 传给 `realInk.arm(allowed, extras, snapshot)`。
4. 手写笔 `ACTION_DOWN`（`4409-4416`）、手指 `ACTION_DOWN`（`5033-5105`）、边缘延迟落笔（`5131-5144`、`5249-5256`）统一在提交前刷新。
5. `previewEnvelopeHitsUi` 改读同一份 snapshot；`isHoverOverUi` 改为读 tracker / snapshot，不再读 `measured*Rect`。
6. `measuredTopBarRect` / `measuredToolRailRect` / `measuredSliderRailRect` 保留为兼容 getter（从 tracker 派生）或彻底删除并同步改 `isHoverOverUi`；二选一，不能留下“只删写入点、不补读取”的半实现。

### Task 4（Phase 1）：`FrontBufferPreviewOverlay` / `RealInkController` 线程协议

1. `RealInkController.uiClips` 改为 `@Volatile var uiClips: UiClipSnapshot = UiClipSnapshot.EMPTY`；`arm` 接收 snapshot。
2. `renderInkScreenRegions(..., uiClips: UiClipSnapshot, ...)`：
   - 只遍历 `[0, min(uiClips.count, MAX_UI_CLIPS))`；
   - 每次提交无条件 `packet.uiClipCount = n`（n 可为 0），并 `System.arraycopy` 有效项；
   - 禁止残留上一包的 `uiClips`。
3. `presentPending`：
   - 锁内完成 `reclaim` + `appendDiscrete` + `System.arraycopy` 到 `snapshotRects` 后，**在锁内 `pendingCount = 0`**（取快照即消费）；
   - 若后续 `!allBaked` / `!shown` / `claimSlot()==null` / OOM，在锁内把 `snapshotRects[0 until snapCount]` 通过 `appendDiscrete` 归还 `pendingRects`；
   - 这样 UI 线程期间新增的矩形不会被无条件清零吞掉。
4. `#81` 预览：
   - `FrontBufferPathPacket` 增加/复用 `uiClipCount`，每次提交重置；
   - `renderPreviewPath` / `renderStampPreview` 增加 `clips: UiClipSnapshot` 参数，全部调用点（`CanvasTouchView.kt:5971-5978`）同步；
   - `drawPreviewPacket` 增加 `canvas.save(); clipOutUi(canvas, param); ... finally restore()`；
   - `CanvasTouchView.kt:5965` 的 `overUi` 改为“起点与终点都落在未渐隐 UI 内才整帧跳过”，靠近边缘时由 `clipOutUi` 精准裁剪，消除预览线闪烁。
5. `clipOutUi` 增加包围盒相交预筛：只有 `uiClip` 与当前包绘制包围盒相交才 `canvas.clipOutRect`，把每帧裁剪调用从 16~32 次压到 0~2 次。

### Task 5（Phase 1）：ShiftTrace 门控

1. 在 `PaintViewModel` 增加 `canvasBackgroundOpaque`（§3.5）。
2. `armRealInkHold`：
   ```kotlin
   val shiftTraceOpaqueConflict = v.anim.shiftTraceActive && v.canvasBackgroundOpaque
   val allowed = v.frontBufferPredictionEnabled &&
       (!hasSymmetry || v.mirrorGate.isQueueing) &&
       v.displayTiles.drawable() != null &&
       FrontBufferProbe.realInkHoldAllowed() &&
       frontBufferSettled(v) &&
       !shiftTraceOpaqueConflict
   ```
3. 若 `shiftTraceOpaqueConflict`：`realInk.release()`（或确保 `arm` 返回 false）、`frontBufferOverlay?.clearPreview()`；因为 `held=false`，`#81` 预览门控 `!realInk.held` 仍然成立。
4. **不要只在 `wantsHold()` 里返回 false**：那会让 `arm` 已置 `held=true`，导致旧前缓冲墨不清、`#81` 预览不画、主画布可能整屏重绘。
5. 若 `canvasBackgroundOpaque` 不可靠，保守回退为“`shiftTraceActive` 时始终 `allowed=false`”，并在验收中记录对位模式下的主画布重绘成本。

### Task 6（Phase 2）：双圈同步前馈

**位置**：

- `handleToolDown` 首行；
- `handleToolMove` 的 `startedNow`（`5659`）与 `historySize` 循环（`5712`）**之前**；
- 预测点算出后、`#81` 预览与当前 `v.touchMove`（`6162`）之前（只做外圈预警）。

**算法**：

```kotlin
// 纯标量、零分配
val brushR = effectiveBrushScreenRadius(v, density) // eraser override 用 getToolEffectiveSize("eraser")
val warnR = 64f * density + brushR
val contactR = brushR + 4f * density

// 包络 = priorStrokeScreenPos + 全部 history 点 + 当前点 + 辅助绘图落点
val env = expandEnvelope(prior, history, cur, assist, max(warnR, contactR))

for (slot in evadableSlots) {
    val b = tracker.bounds(slot)
    if (!b.active) continue
    if (env intersects b.expand(warnR)) {
        if (!isEvading(slot)) setEvading(slot, true)
        if (env intersects b.expand(contactR)) {
            setSnapZero(slot, true)
            tracker.unclip(slot)              // 从快照移除
            publishSnapshot()                 // @Volatile 发布
            // 必须在 v.touchMove 之前完成
        }
    }
}
```

**规则**：

- 外圈只启动淡出，不撤 Clip；
- 内圈撤 Clip 必须在本帧所有 `v.touchMove` 之前；
- 预测点只参与外圈，不参与内圈（避免误撤）；
- `DOWN` 落在面板内时，外圈+内圈同时命中，首帧即撤 Clip；
- `snapZero=true` 时 `graphicsLayer` 当帧 alpha=0；不做 0ms 硬跳之外的花哨过渡，避免半透明面板上出现底色；
- 抬笔后 180ms 防抖恢复；新笔落下时 per-stroke re-evaluation：若新笔不在该面板预警圈内，立即清 `isEvading/snapZero` 并恢复；
- `restoreEvadedPanelsRunnable` 用 `strokeStarted || realInk.held` 判定，活跃时 `postDelayed(this, 60L)` 自重排；
- 所有绕过 `handleToolUp` 的路径（§1.6 盲区 5）统一调度恢复；
- `fullyEvaded` 期间通过 `UiSlotModifierNode` 移出命中测试（Task 0 通过后）。

### Task 7（Phase 2）：`reportUiSlot` / `UiSlotModifierNode` 与命中穿透

**代码草案（`composed` 回退版；Task 0 通过后优先用 `Modifier.Node`）**：

```kotlin
private val EvadeFadeOutSpec = tween<Float>(70, easing = FastOutLinearInEasing)
private val EvadeFadeInSpec = tween<Float>(160, easing = FastOutSlowInEasing)

internal fun Modifier.reportUiSlot(
    vm: PaintViewModel?,
    slot: UiPanelSlot,
    ownerToken: Int,
): Modifier = composed {
    if (vm == null) return@composed this
    DisposableEffect(vm, slot, ownerToken) {
        onDispose { vm.panelOcclusion.clear(slot, ownerToken) }
    }
    val isEvading = if (slot.evadable) vm.slotEvading[slot.ordinal].value else false
    val snapZero = if (slot.evadable) vm.slotSnapZero[slot.ordinal].value else false
    // 关键：用 `=` 而非 `by`，不在 composition 阶段读取 Float
    val alphaState = animateFloatAsState(
        targetValue = if (isEvading) 0f else 1f,
        animationSpec = if (isEvading) EvadeFadeOutSpec else EvadeFadeInSpec,
        label = "EvadeAlpha",
    )
    this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                val b = coords.boundsInRoot()
                vm.panelOcclusion.update(slot, ownerToken, b.left, b.top, b.right, b.bottom)
            }
        }
        .then(
            if (slot.evadable) {
                Modifier.graphicsLayer {
                    // 仅在 draw/RenderNode 阶段读取
                    alpha = if (snapZero) 0f else alphaState.value
                }
            } else Modifier
        )
}
```

**注意**：

- `slotEvading` / `slotSnapZero` 必须是 `Array(UiPanelSlot.COUNT) { mutableStateOf(false) }`，不能每次调用新建；
- `ownerToken` 由 `UiPanelOcclusionTracker` 分配单调递增值；
- `reportUiSlot` 必须挂在卡片最外层视觉容器首部；
- 非 evadable 槽位也读取 `isEvading=false`，但不要创建无意义的动画（可在 `if (slot.evadable)` 内调用 `animateFloatAsState`；Compose 不允许条件调用 composable，需拆成两个 modifier 分支或始终调用但 target=1）；
- 若用 `Modifier.Node`：`GlobalPositionAwareModifierNode` 上报 bounds，`DrawModifierNode` 读 `alphaState.value`，`LayoutModifierNode` 在 `alpha <= 0.03f` 时把 placeable 移到屏幕外，`PointerInputModifierNode` 在半透明期消费 `Initial`。

### Task 8（Phase 2）：设置、i18n、恢复状态机

1. `autoHidePanelsOnStroke` 默认 true，持久化；
2. `autoHidePinnedAndRefOnStroke` 默认 false，持久化；
3. `SAVED_SELECTIONS_POPUP` / `ANIMATION_SETTINGS_CARD` 默认值待确认；
4. `values/strings.xml` 与 `values-en/strings.xml` 同步；
5. 恢复状态机：
   - 每笔 DOWN 重判；
   - 180ms 防抖；
   - `strokeStarted || realInk.held` 自重排；
   - 所有中断出口统一；
   - `onDetachedFromWindow` 移除 callbacks；
6. 关闭总开关时立即清 `evading/snapZero` 并恢复所有面板。

---

## 5. 测试与验收

### 5.1 机械门禁

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew assembleDebug
```

### 5.2 纯 Kotlin 单测（不引入 Robolectric）

- `UiPanelOcclusionTrackerTest`：
  - 21 槽位写入/清空/重开；
  - `ownerToken` 竞争；
  - 快照 4→3 项、池尾无残留、count 正确；
  - evading/unclipped 位在抬笔/取消/切工具后归零。
- `UiClipSnapshotTest`：
  - `intersects` / `contains` 边界（含边界相等）；
  - `EMPTY`；
  - `copyOf` clamp。
- `UiRingDetectorTest`：
  - 外圈只 warn、内圈 contact；
  - 笔刷半径/密度/缩放；
  - eraser override 的有效尺寸；
  - 历史点/预测点的时序（用纯函数模拟）。

### 5.3 真机矩阵

- 手写笔 / 手指 / 边缘延迟落笔 / 长按吸色；
- `paintingUiScale` 0.70 / 1.0 / 1.40；
- 竖屏 / 横屏 / 左手模式；
- 5 个 Quick* 全部打开 + 参考窗 + 3 个钉住面板 + 时间轴 + 动画设置卡 + 选区胶囊 + 透光台胶囊 + Action Toast / Transform HUD + Picker 来源条 + Solo + Fill；
- 透明画布 / 不透明画布；
- ShiftTrace 对位模式；
- 快速运笔 2000 / 4000 px/s；
- 双指撤销 / 三指菜单 / ACTION_CANCEL / 切工具 / 后台切换；
- 面板拖动 / 折叠 / 展开 / 关闭 / 重开；
- Popup/Dialog 打开时不误裁；
- 连续排线（抬笔间隔 < 150ms）不闪烁；
- 隐身面板区域点击必须穿透到画布。

### 5.4 性能指标

- PerfHud 前缓冲帧耗时：Phase 1 前后无回退；Phase 2 渐隐期间不超预算；
- 每帧 `clipOutRect` 次数：扩容后 0~2 次（相交预筛后）；
- GC：240Hz 移动期间零分配；
- `TILE_UNDRAWN` 命中率：不低于改动前；
- `strokeLayer` 内存峰值：若启用安全网，记录并设上限；
- 面板渐隐期间无 Compose 逐帧重组（可用 Layout Inspector / recomposition counter 验证）。

---

## 6. 实施顺序与回滚

1. **Phase 1**：Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → 机械门禁 + 真机矩阵。
2. **Phase 2**：Task 0 spike → Task 6 → Task 7 → Task 8 → 机械门禁 + 真机矩阵。
3. 每个 Phase 独立 commit/PR；Phase 1 可单独发布，Phase 2 通过设置开关灰度。
4. 回滚：Phase 2 只需关闭 `autoHidePanelsOnStroke`；Phase 1 的裁剪修复可保留，不回退。

---

## 7. 风险与待拍板项

| # | 事项 | 现状 | 建议 |
|---|---|---|---|
| 1 | `UiSlotModifierNode` 移出命中测试 | 未验证的 Compose 行为 | **Task 0 spike 必须先做**；失败则回退到 `LocalUiPanelInteractive` 条件禁用交互层 |
| 2 | `strokeLayer` 全屏 ARGB 内存 | 约 18MB（1440×3200） | 先测内存/首帧分配；不可接受则改分块备份层，或放弃安全网（主路径不依赖） |
| 3 | 槽位数 | 终版 21，不是 23 | 统一 `COUNT = entries.size`；`MAX_UI_CLIPS=24` 留冗余 |
| 4 | `SAVED_SELECTIONS_POPUP` / `ANIMATION_SETTINGS_CARD` 默认渐隐 | 产品文案写“仅 5 个 Quick*”，终版槽位表曾列 7 个 | **待你确认**；当前按 `defaultEvade=false` 落地 |
| 5 | 钉住面板/参考窗默认渐隐 | 你已拍板默认关闭，二级开关控制 | 按此落地 |
| 6 | ShiftTrace 不透明背景判定 | 源码无 `isBackgroundTransparent` | 用 `layers.firstOrNull { it.isBackground }` 的 `visible && opacity≈1` 派生；不可靠时保守关闭 hold |
| 7 | Popup/Dialog | 独立窗口，`boundsInRoot` 是弹窗局部坐标 | 明确排除；若未来要纳入，需 window/screen 坐标换算 |
| 8 | 快速运笔 70ms 淡出 | 4000px/s 时 64dp 仅约 48ms | 内圈接触 `snapZero` 兜底；真机 2000/4000 px/s 验证 |
| 9 | `BOTTOM_TOOL_PANEL` 共享 | 工具面板同一时刻一个；Solo 可并存 | `ToolFloatPanel` 增加 `slot` 参数；Solo 用 `SOLO_MODE_BAR`；若发现两个工具面板并存再拆分 |
| 10 | 90ms/70ms 视觉验收 | 快速运笔可能仍有极短过渡 | 以 `snapZero` 保证无半透明锯齿；若视觉突兀，再评估 32ms 加速淡出 |

---

## 8. 终版交付定义（Definition of Done）

- [ ] Phase 1：21 槽位全部用 `boundsInRoot` 实测；无 380dp/90dp 硬编码；所有落笔路径发布快照；`renderInkScreenRegions` 按 count 遍历；`#81` 预览裁剪；`presentPending` 消费/归还协议；ShiftTrace 门控。
- [ ] Phase 2：双圈状态机；内圈接触前同步撤 Clip；无 `uncoverScreenRect` 主路径；抬笔 180ms 防抖 + per-stroke 重判 + 自重排；完全透明移出命中测试（Task 0 通过）。
- [ ] 纯 Kotlin 单测全绿；`compileDebugKotlin` / `testDebugUnitTest` / `lintDebug` / `assembleDebug` 全绿。
- [ ] 真机矩阵全部通过；PerfHud 无回退；GC 零分配；`TILE_UNDRAWN` 命中率不降。
- [ ] 文档行号/机制/路径与源码一致；所有开放产品项已确认。

---

## 附：本次审查中已确认的关键源码锚点

| 主题 | 锚点 |
|---|---|
| 前缓冲层级 | `FrontBufferPreviewOverlay.kt:108-111, 232-242` |
| 挂载条件 | `CanvasView.kt:393`；`FrontBufferProbe.kt:23-37` |
| 软件预测开关 | `PaintViewModel.kt:1857, 1876-1877` |
| 抬笔清层 | `RealInkController.kt:177-201, 279-287`；`CanvasTouchView.kt:2996` |
| SRC 贴图 | `FrontBufferPreviewOverlay.kt:197-201, 782-819` |
| bakeRect 不铺棋盘格 | `RealInkController.kt:434-478` |
| appendDiscrete | `model/RealInkPresent.kt:21, 24, 171-211, 221-257` |
| 512 瓦片 / TILE_UNDRAWN | `DisplayTileGrid.kt:113-115`；`DisplayTileState.kt:27-35`；`CanvasTouchView.kt:2864-2865` |
| overlayPanelsOpen | `PaintingPage.kt:700-703`；`CanvasView.kt:345-349`；`RealInkController.kt:207, 222` |
| 漏登/硬编码 | `CanvasTouchView.kt:1018-1093, 1064-1069, 2269-2297` |
| 手指落笔 | `CanvasTouchView.kt:5033-5105, 5131-5144, 5249-5256` |
| 手写笔落笔 | `CanvasTouchView.kt:4409-4416` |
| arm | `CanvasTouchView.kt:3589-3600`；`RealInkController.kt:143-163` |
| renderInkScreenRegions | `FrontBufferPreviewOverlay.kt:513-580` |
| presentPending | `RealInkController.kt:293-432` |
| strokeLayer | `FrontBufferPreviewOverlay.kt:181-187, 718-770, 921-946` |
| 预览路径 | `FrontBufferPreviewOverlay.kt:342-368, 388-452`；`CanvasTouchView.kt:5964-5988` |
| 历史点时序 | `CanvasTouchView.kt:5647, 5659, 5712, 5722, 6162` |
| 时间轴结构 | `AnimationTimelinePanel.kt:152, 164, 174, 176`；`TimelineSettingsCard.kt:73, 83` |
| 钉住条件 | `ColorPanel.kt:254`；`LayerPanel.kt:424`；`BrushPanel.kt:1450` |
| ToolFloatPanel | `ToolFloatPanel.kt:62-105`；`PaintingPage.kt:1785` |
| 图层镜像 | `PaintViewModel.kt:4611-4638, 5886` |
| 设置持久化模式 | `PaintViewModel.kt:2886-2899, 3808-3813` |
| 缩放范围 | `PaintViewModel.kt:3333`；`PaintingDialogs.kt:481-484`；`ThemeSettingsSubPage.kt:469-473` |
