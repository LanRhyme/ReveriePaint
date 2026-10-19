# ReveriePaint 前缓冲覆盖规则与 50% 面板避让执行计划（v4.0）

> **适用工程**：`E:\gongju\ReveriePaint`（`com.reverie.paint`）
> **分支**：`feat/front-buffer-panel-evasion`
> **Phase 1 基线**：`fcfbc7dd`（已提交的精确裁剪底座）
> **状态**：需求与实施计划终版；本轮只改文档，不改代码
> **重要**：本版取代 v3.1 的 Phase 2“双圈渐隐/整面板消失”设计。当前工作区 9 个未提交文件是旧 Phase 2 草稿，施工前必须按本版重写或清理。

---

## 0. v4.0 需求变更（最高优先级）

### 0.1 用户最终确认的行为

1. **不是整个面板避让，不是方向性渐隐，不是“先消失 1/2”。**
2. 笔进入范围时：面板**内容与背景 50% 透明度**；**边框与阴影保持 100%**，保留轮廓。
3. 笔离开范围时：**立即恢复 100%**，不再等抬笔；用 70ms 短过渡避免硬跳。
4. 进入范围：面板 bounds 外扩 **32dp + 笔刷屏幕半径**；笔已经在面板内部也算进入。
5. 离开范围：外扩 **40dp（32dp + 8dp 滞后）+ 笔刷屏幕半径**；运笔中途离开立即恢复。
6. 每个可避让面板独立判定；同时靠近几个，就几个各自 50%。
7. 渐隐态**去掉毛玻璃实时模糊**；离开时恢复毛玻璃；用交叉淡化过渡，不做 blurRadius 动画。
8. 交互规则改为**绘画态优先**：只要当前有笔画在产生（`strokeStarted` / 引擎笔画会话活跃），面板进入被动避让态，不消费指针，只做 50%；没有笔画时面板正常可点可拖。
9. 总开关放在**“视图显示”**设置；只控制 50% 行为；覆盖/合成修复不受开关影响。
10. 总开关默认**开启**。
11. 总开关与预测模式无关：**无论“都不开（原生）/硬件算法/APP 软件算法”，只要开关开就避让**。用户原话保留，不进一步解释“原生模式”。
12. Popup/Dialog 是独立窗口，不参与 50% 行为；验收只要求不被前缓冲盖住。
13. **不接受近似**：圆角、软阴影、毛玻璃、半透明背景必须视觉一致；不能用矩形/圆角矩形近似冒充。
14. **不能增加延迟和性能开销**：以 Phase 1 真机 PerfHud 为基线，前缓冲帧耗时、端到端延迟、GC、`TILE_UNDRAWN` 命中率不退化；不允许每帧额外拷贝/离屏合成/整屏重绘。

### 0.2 用户明确否定的做法

- 整个面板淡出到 0%。
- 方向性渐隐（从进入边向中心渐变）。
- “先消失 1/2，再整个消失”。
- 锁死到抬笔后 180ms 才恢复。
- `snapZero` 瞬移、`translationX` 移出屏幕。
- **把新笔迹以 50% 直接叠在面板上方**——这不是“透过 50% 面板看到笔迹”，视觉上仍然是墨盖在面板上。

### 0.3 本版最关键的视觉约束

> **目标合成顺序必须是“面板在墨之上”：面板以 50% alpha 叠在墨上，墨从面板下方透出来；不是墨以 50% alpha 叠在面板上。**

如果实现只能做到“墨 50% 叠在面板上”，即使数学上某些情况下颜色接近，也不满足本需求，施工方必须继续寻找正确合成顺序或给出逐像素等价证明。

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

## 2. 当前架构事实（已核实，作为施工前提）

### 2.1 图层栈

```
[最高] 独立 Popup / Dialog 窗口（WindowManager 独立窗口）
[前缓冲] FrontBufferPreviewOverlay（SurfaceView, setZOrderOnTop(true), TRANSLUCENT）
         位于宿主窗口 HWUI 内容之上，但不高于独立 Popup/Dialog
[主窗口] Activity Window / HWUI
         ├── Compose 悬浮面板（圆角、阴影、hazeChild）
         ├── CanvasOverlay / ShiftTraceOverlay
         └── CanvasTouchView（普通 View，绘制棋盘格 + 512 显示瓦片）
[最底] Window 背景
```

- `FrontBufferPreviewOverlay.kt:238`：`setZOrderOnTop(true)`。
- `FrontBufferPreviewOverlay.kt:239`：`PixelFormat.TRANSLUCENT`。
- 前缓冲用 `PorterDuff.Mode.SRC` 逐块贴不透明瓦片：`FrontBufferPreviewOverlay.kt:197-202`、`753-850`。
- Phase 1 用轴对齐 `canvas.clipOutRect` 挖洞：`FrontBufferPreviewOverlay.kt:852-874`。
- 面板 bounds 来自 `boundsInRoot()`：`ReComponents.kt:1907-1911`；当前隐含假设 root = view = screen 坐标。
- 圆角是 `clip(RoundedCornerShape)`，阴影是 `Modifier.shadow`，毛玻璃是 `hazeChild`，都在 Compose 面板层。

### 2.2 为什么会出现“直角边框残影”

- `boundsInRoot()` 只给轴对齐外接矩形 `[L,T,R,B]`。
- `clipOutRect(L,T,R,B)` 挖出的是 90° 直角矩形洞。
- 面板本身是圆角；圆角弧线与矩形四角之间的 4 个月牙区在矩形洞内，前缓冲不画新墨；底下的 `CanvasTouchView` 在 held 期间又不刷新，于是月牙区保留“抬笔前画布 + 阴影颜色”，形成直角边框残影。

### 2.3 为什么底部阴影会被覆盖

- `Modifier.shadow` 的软阴影在 `boundsInRoot()` 之外。
- 前缓冲在面板 bounds 之外没有裁剪保护，`SRC` 贴上的不透明画布底色块直接把阴影盖死。
- 这就是“阴影像被刀切掉”的根因。

### 2.4 为什么当前架构不能天然遵守“普通画布覆盖规则”

- 普通画布和 Compose UI 都在主窗口同一棵 HWUI 渲染树里，按画家算法天然从底到顶合成。
- 前缓冲为了 4~8ms 直出，使用独立 `SurfaceView` + 单缓冲直写；它要么在窗口内容之下（会被画布挡住），要么 `setZOrderOnTop(true)` 在窗口内容之上（会盖住所有 Compose UI）。
- 同窗口内没有公开、稳定的机制把 SurfaceView 夹在“CanvasTouchView 画布”和“Compose UI”之间。
- 因此“前缓冲在最上层 + 轴对齐矩形挖洞”无法表达圆角、软阴影、毛玻璃、50% 半透明面板的正确合成。

---

## 3. 核心问题：如何实现“面板在墨之上”

### 3.1 目标合成公式

面板内部（内容/背景 50%，边框/阴影 100%）：

```
final = panel_border_shadow(alpha=1.0)
        over ( panel_content_bg(alpha=0.5)
               over ( ink
                      over old_canvas ) )
```

- 墨必须在面板下方；面板以 50% alpha 覆盖墨。
- 边框/阴影 100%，所以面板轮廓始终清晰。
- 渐隐态毛玻璃关闭，面板内部是 50% 纯色背景；离开时毛玻璃交叉淡化恢复。

### 3.2 明确禁止

- 禁止 `ink(alpha=0.5) over panel`。
- 禁止用矩形/圆角矩形 clip 近似软阴影/毛玻璃。
- 禁止“看起来差不多”就宣布完成。

### 3.3 施工方必须先做的覆盖架构 Spike

施工方不能直接改业务代码；先做一个最小 Spike，证明所选方向能同时满足视觉与性能。允许创造性方案，不限定路线。至少评估以下方向（可以提出更好的）：

1. **图层反转**：把浮动 UI 放到前缓冲之上。
   - 透明子窗口 / PopupWindow（位于 `setZOrderOnTop` SurfaceView 之上）；
   - 画布 SurfaceView + 前缓冲 `setZOrderMediaOverlay` + Compose UI 夹层；
   - `SurfaceControl` / `SurfaceControlViewHost` 等。
2. **像素等价合成**：前缓冲仍在上层，但把面板的圆角/阴影/50% alpha 正确渲染进前缓冲，使最终像素等于“面板 over 墨”。
   - 需要面板的真实 alpha/圆角/阴影数据，或把面板渲染成 mask/RenderNode；
   - 必须证明逐像素等价，并量化每帧成本。
3. **局部交接**：笔在面板外时走前缓冲；笔真正进入面板内部时，把被画到的局部瓦片交给主画布合成，让 Compose 面板天然盖在墨上。
4. **混合方案**：外圈走前缓冲，内部局部交接；或按面板类型分层。
5. **任何其他能证明视觉一致且不增加可测开销的方案。**

Spike 必须输出：

- 400% 放大截图：圆角四角、底部阴影、50% 内容/边框、墨透过面板、毛玻璃关闭/恢复；
- 抬笔前 vs 抬笔后的像素 diff；
- PerfHud 前缓冲帧耗时、端到端延迟、GC、`TILE_UNDRAWN` 命中率；
- 手写笔/手指事件、Popup/Dialog、IME、insets、横竖屏、左手模式；
- 结论：该方向是否满足“视觉一致 + 零可测回退”。不满足就换方向，不能静默近似。

**Spike 通过并经用户确认前，不进入业务代码实现。**

---

## 4. 50% 避让状态机

### 4.1 状态

- `NORMAL`：内容/背景 100%，边框/阴影 100%，毛玻璃正常，面板可交互。
- `EVADING`：内容/背景 50%，边框/阴影 100%，毛玻璃关闭（交叉淡化到 50% 纯色背景），面板被动。

### 4.2 进入 / 离开

- 进入：`distance(tip, panelBounds) <= 32dp + brushScreenRadius`；面板内部 `distance = 0`。
- 离开：`distance > 40dp + brushScreenRadius`（32dp + 8dp 滞后）。
- 过渡：70ms `tween`；进入/离开目标值实时切换。
- 运笔中途离开立即恢复，不等抬笔。
- 抬笔后不需要额外 180ms 锁存；按距离状态自然恢复。
- 连续排线/点画：如果下一笔在 40dp 内，保持 EVADING；不要在 50%/100% 之间闪烁。可实现为 80ms 内的下一笔继承状态，但不得恢复成“抬笔才刷新”。

### 4.3 多面板

- 每个可避让面板独立判定；`evadingPanelMask` 仅用于诊断/批量复位。
- 同时靠近多个面板，各自 50%。

### 4.4 交互：绘画态优先

- 单一真源：`strokeStarted`（或等价的“引擎笔画会话活跃”标志）。
- `strokeStarted == true`：所有面板进入被动避让态，不消费指针；笔迹继续画到画布。即使笔从画布移入面板，面板也不得中断笔画。
- `strokeStarted == false`：面板正常响应点击/拖拽。
- 触点在面板上开始且没有产生笔画：视为 UI 操作，面板消费事件。
- 触点在画布上开始：产生笔画，之后经过任何面板都不转移给面板。
- 如果用户希望“从面板内部起笔也能画画”，那是后续独立需求，本版不实现，避免与按钮点击冲突。

### 4.5 复位路径

在以下路径统一把所有面板恢复 `NORMAL`：

- `ACTION_UP`、`ACTION_CANCEL`；
- 双指撤销、三指菜单、多指手势接管；
- 切工具、退出绘画页、`onDetachedFromWindow`；
- 打开模态/半模态面板（`overlayPanelsOpen = true`）；
- 进入 QuickShapeEditor / 速创成形；
- 关闭总开关。

不得出现“面板卡在 50%”或“面板锁死到抬笔”。

### 4.6 渐隐态的毛玻璃

- `EVADING` 时：关闭该面板的 `hazeChild`/`hazeEffect`，背景切到 50% 纯色（沿用主题 `Morandi.panel` 等）。
- `NORMAL` 时：恢复毛玻璃。
- 过渡：70ms 交叉淡化（模糊层淡出、50% 纯色淡入；反向恢复）。
- 不做 `blurRadius` 动画；不增加每帧模糊开销。
- 只影响被触发的面板，不影响其他面板。

---

## 5. Compose 侧 50% 的实现约束

- 不允许整节点 `layer.alpha = 0.5`，否则阴影/边框也变 50%。
- 建议结构：
  - 外层容器：`Modifier.shadow(...)` + 边框保持 100%；
  - 背景层：`drawBehind { drawOutline(outline, color, alpha = evadeAlpha) }`，在 Draw 阶段读取 alpha；
  - 内容层：`Modifier.graphicsLayer { alpha = evadeAlpha }`，只影响按钮/图标/文字；
  - 毛玻璃层：`EVADING` 时交叉淡化到 50% 纯色。
- `evadeAlpha` 用 per-slot `State<Float>`；在 draw 阶段读取，避免逐帧重组。
- 不得使用 `val alpha by animateFloatAsState(...)` 在 composition 阶段拆箱。
- 不得使用 `translationX` 移出屏幕；面板始终可见。
- 面板在 50% 时仍保持圆角/阴影的真实形状。

---

## 6. 数据模型与 Phase 1 基线

### 6.1 保留

- `UiPanelSlot`：21 槽位，9 个可避让（5 个 `QUICK_*` + 4 个钉住/参考）。
- `UiClipSnapshot` / `UiPanelOcclusionTracker` / `reportUiSlot`：Phase 1 的实测 bounds 与不可变快照底座保留，作为非圆角/非阴影场景的 fallback，以及 50% 状态机的几何来源。
- `CanvasTouchView` 的单一快照、`renderInkScreenRegions` 的 count 遍历、`presentPending` 的消费/归还、`#81` 预览裁剪：保留。
- `ShiftTrace` 的 `!shiftTraceActive` 门控：保留。

### 6.2 需要重写/移除

当前工作区 9 个未提交文件是旧 Phase 2 草稿，与新需求冲突：

| 文件 | 旧实现 | 处理 |
|---|---|---|
| `ReComponents.kt` | `target=0f` 整面板淡出、`translationX` 移出屏幕、半透明期无条件 `consume()`、整节点 alpha | 删除这些逻辑；改为 50% 内容/背景 + 100% 边框/阴影 |
| `CanvasTouchView.kt` | `evaluateUiEvasion` 双圈 `WARN/CONTACT`、`snapZero` 0% 瞬移、`setUnclipped`、抬笔后 180ms 锁存 | 删除双圈/0% 逻辑；改为 32dp/40dp 双向滞后 + 50% |
| `UiPanelOcclusionTracker.kt` | `evadedMask` 单向 `or` 锁存、`unclippedMask`、`ringState` | 改为双向状态；50% 模型不依赖 `unclippedMask` |
| `UiRingDetectorTest.kt` | 双圈 0% 测试 | 改为 32dp/40dp 50% 状态机测试 |
| `StylusSettingsSubPage.kt` | 开关放在“手写笔设置” | 移到“视图显示” |
| `PaintViewModel.kt` | `autoHidePanelsOnStroke` / `autoHidePinnedAndRefOnStroke` 默认值与位置 | 按本版：视图显示、默认开启、总开关与预测模式无关 |
| `UiPanelSlot.kt` | 3 行旧状态辅助 | 按 50% 模型保留/调整 |
| `values/strings.xml`、`values-en/strings.xml` | 旧“自动渐隐”文案 | 改为 50% 避让文案 |

清理前先 `git diff` 逐项确认；不要误删 Phase 1 已提交的底座。

### 6.3 设置

- 位置：**“视图显示”**设置。
- 名称：建议“运笔靠近面板时半透明避让”/“Auto-fade panels near stroke”（文案可再定）。
- 默认：**开启**。
- 总开关控制所有 9 个可避让面板的 50% 行为。
- 与预测模式无关：原生/硬件/APP 软件算法下都生效。
- 覆盖/合成修复不受总开关影响。
- 旧“钉住面板/参考窗”二级开关：本版按“只要开就避让”处理，默认移除；如用户后续要求保留，再单独加。

---

## 7. 施工任务拆解

### Task A（前置，必须最先做）：覆盖架构 Spike

**目标**：证明“面板在墨之上 + 圆角/软阴影/毛玻璃/50% 内容背景 + 零可测回退”在所选方向上成立。

**产出**：

- 方向说明与为什么能正确合成；
- 400% 截图与像素 diff（抬笔前/后）；
- PerfHud/延迟/GC/TILE_UNDRAWN 数据；
- 输入、Popup/Dialog、insets、横竖屏、左手模式验证；
- 结论：通过 / 不通过 / 需要用户裁决。

**门禁**：Spike 通过并经用户确认前，不进入 Task B。

### Task B：50% 状态机

**文件**：

- `model/UiPanelOcclusionTracker.kt`
- `CanvasTouchView.kt`
- `PaintViewModel.kt` / `PaintViewModelTools.kt`
- `ReComponents.kt`
- 相关测试

**步骤**：

1. 实现 32dp 进入 / 40dp 离开的双向滞后距离判定（笔尖 + 历史点 + 预测点；笔刷半径用有效工具尺寸）。
2. 每个可避让槽位独立 `evadeAlpha` 目标值（1.0/0.5），70ms tween。
3. 删除 0%/snapZero/translationX/180ms 锁存/单向 `or`。
4. 多面板独立；复位路径统一。
5. 绘画态优先的交互规则（`strokeStarted` 单一真源）。

**验收**：

- 进入 32dp 内 50%，离开 40dp 外立即恢复；
- 运笔中途离开不抬笔也恢复；
- 快速排线/点画不闪烁；
- 多面板各自独立；
- 面板内部按钮在非绘画态可点，在绘画态不拦截笔画；
- 无 0% 消失、无移出屏幕。

### Task C：Compose 50% 分离与毛玻璃交叉淡化

**文件**：

- `ReComponents.kt`
- 各面板的 modifier 链
- `Glass.kt` / haze 相关

**步骤**：

1. 外层 shadow/border 100%；背景/内容 50%。
2. `EVADING` 时 haze 交叉淡化到 50% 纯色；`NORMAL` 恢复。
3. 只影响被触发面板。
4. 避免逐帧重组/分配；draw 阶段读取 alpha。

**验收**：

- 边框/阴影 100%，内容/背景 50%；
- 毛玻璃在渐隐态关闭、离开恢复，过渡无硬跳；
- 无逐帧重组/GC；
- 圆角/阴影形状不变。

### Task D：设置与 i18n

**文件**：

- `PaintViewModel.kt` / `PaintViewModelTools.kt`
- “视图显示”设置页
- `values/strings.xml` / `values-en/strings.xml`
- 删除 `StylusSettingsSubPage.kt` 的旧开关

**步骤**：

1. 总开关移到“视图显示”，默认开启，持久化 `paint_prefs`。
2. 与预测模式无关；覆盖修复不受开关影响。
3. 清理旧二级开关/旧文案。
4. 中英文同步。

### Task E：清理旧 Phase 2 草稿

- 按 §6.2 清单逐项删除/重写。
- 不保留“整面板 0% / translationX / snapZero / 180ms / 双圈”代码。
- 清理后 `git diff` 确认只保留 Phase 1 底座 + 新 50% 实现。

### Task F：测试与验收

- 纯 Kotlin 单测：32/40dp 双向滞后、笔刷半径、多面板、复位、有效工具尺寸。
- `compileDebugKotlin` / `testDebugUnitTest` / `lintDebug` / `assembleDebug`。
- 真机矩阵见 §8。

---

## 8. 验收矩阵

### 8.1 视觉

1. 圆角四角：笔贴圆角外弧线运笔不抬笔，400% 放大，确认无直角边框残影。
2. 底部阴影：笔贴面板底部运笔不抬笔，确认阴影完整、无被切断。
3. 50% 内容/背景 + 100% 边框/阴影：进入 32dp，确认边框/阴影清晰、内容/背景半透明。
4. **墨从面板下方透出**：确认不是墨叠在面板上；抬笔前/后像素 diff 一致。
5. 毛玻璃：渐隐态关闭、离开恢复，过渡自然。
6. 不透明/透明画布、深色/浅色背景。
7. 多面板同时 50%，互不影响。
8. 快速排线/点画不闪烁。
9. 绘画态面板不拦截笔画；非绘画态面板按钮/拖拽正常。
10. ACTION_CANCEL、双指撤销、三指菜单、切工具、速创、退后台后面板恢复。
11. Popup/Dialog 不被前缓冲盖住。
12. 横竖屏、左手模式、`paintingUiScale` 0.70/1.0/1.40。

### 8.2 性能

以 Phase 1 `fcfbc7dd` 真机 PerfHud 为基线：

- 前缓冲帧耗时平均/P99 不退化；
- 端到端笔迹延迟不退化；
- 240Hz `ACTION_MOVE` 零分配、零 GC；
- `TILE_UNDRAWN` 命中率不降；
- 无每帧额外拷贝/离屏合成/整屏重绘；
- 任何新增成本必须量化并记录。

### 8.3 机械门禁

```bash
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:compileDebugKotlin
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:testDebugUnitTest
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:lintDebug
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:assembleDebug
```

---

## 9. 施工红线

1. 先 Spike，后业务代码；Spike 不通过不得开工。
2. 不接受“墨 50% 叠在面板上”的伪实现。
3. 不接受矩形/圆角矩形近似软阴影/毛玻璃。
4. 不接受任何可测的延迟/性能回退；如无法同时满足视觉与性能，停止并报告测量数据，由用户裁决，不得静默取舍。
5. 不保留旧 Phase 2 的 0%/snapZero/translationX/180ms/双圈逻辑。
6. 不修改与任务无关的代码；不顺手重构。
7. 不擅自解释“原生模式”；按“开关与预测模式无关”实现。
8. 每个中间步骤保持可编译；完成后按 AGENTS §10 提交。

---

## 10. 施工交接提示词（给隔壁会话）

见本轮聊天中提供的“施工会话提示词”。施工会话必须先读本文件、`AGENTS.md` 和 Phase 1 提交 `fcfbc7dd`，先做 Task A Spike，不得直接改业务代码。
