# Phase v5.0 施工交付文档

> 分支：`feat/front-buffer-panel-evasion`
> 计划文档：`docs/FRONT-BUFFER-PANEL-EVASION-PLAN.md`（v5.0，基线提交 `9668caa4`）
> Phase 1 基线：`fcfbc7dd`
> 施工日期：2026-10-19 ~ 2026-10-20
> 状态：代码、单测、四道机械门禁、APK 构建全部完成；真机视觉/性能验收待用户执行

---

## 1. 提交列表

| 提交 | 说明 |
|---|---|
| `01f898d2` | `feat(front-buffer): 重写 UI 槽位模型为 50% 双向滞后与阴影孔径裁剪` |
| `0801f266` | `feat(ui): 在视图显示中加入运笔半透明避让总开关` |
| `c3e168a5` | `feat(ui): 9 个可避让面板接入 50% 三明治分层与毛玻璃交叉淡化` |
| `1c760637` | `feat(render): 前缓冲孔径局部主画布协同刷新与 12 条避让复位` |

> 说明：四个提交按 Step 1~4 的逻辑切分。由于模型层 API 迁移贯穿 `CanvasTouchView` /
> `ReComponents`，单独检出中间提交不可编译；最终 `1c760637` 是完整、可编译、四道门禁
> 全绿的验证状态。

---

## 2. 修改 / 新建文件清单

### 新建

- `app/src/test/java/com/reverie/paint/model/UiPanelEvasionStateMachineTest.kt`
- `docs/PHASE-V5-DELIVERY.md`（本文档）

### 修改

- `app/src/main/java/com/reverie/paint/model/UiPanelSlot.kt`
- `app/src/main/java/com/reverie/paint/model/UiClipSnapshot.kt`
- `app/src/main/java/com/reverie/paint/model/UiPanelOcclusionTracker.kt`
- `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt`
- `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/canvas/RealInkController.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/panels/SettingsTabPage.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/quickaction/QuickActionWindow.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/quickbrush/QuickBrushWindow.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/quickcolor/QuickColorWindow.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/quickoklch/QuickOklchWindow.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/quicklayer/QuickLayerWindow.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/ReferenceWindow.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/panels/ColorPanel.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/layers/LayerPanel.kt`
- `app/src/main/java/com/reverie/paint/ui/painting/brush/BrushPanel.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-en/strings.xml`
- `app/src/test/java/com/reverie/paint/model/UiPanelOcclusionTrackerTest.kt`

### 删除

- `app/src/test/java/com/reverie/paint/model/UiRingDetectorTest.kt`（旧 Phase 2 双圈测试，
  已由 `UiPanelEvasionStateMachineTest.kt` 的 32/40dp 双向滞后测试替代）

### 明确未提交

- `docs/4K-LOW-LATENCY-OPTIMIZATION-ARCHITECTURE.md`（按要求保留未跟踪）

---

## 3. 实际实现的架构与关键代码路径

### 3.1 模型层（`model/`，纯 Kotlin）

- `UiPanelSlot`：21 槽位；移除 `defaultEvade` / `pinnedOrReference`；新增
  `shadowPadHDp` / `shadowPadTopDp` / `shadowPadBottomDp`。9 个 `evadable` 槽位。
- `UiPanelOcclusionTracker`：
  - 双矩形存储：`slotBounds`（owner 并集本体，供 32dp/40dp 距离与 `hitTest`）与
    `slotClipBounds`（本体 + 阴影外扩，供 `snapshot()`）。
  - `evaluateSlotEvasion(...)`：`<= enter` 进入、`> exit` 离开、滞后带保持。
  - `snapshot()` **不再接受任何排除掩码**：所有激活槽位始终导出孔径，50% 面板绝不移出。
  - 删除 `UiRingState`、`unclippedMask`、`setUnclipped`、`ringState*`。
- `UiClipSnapshot.accumulateIntersection(...)`：零分配计算脏区与孔径的精确交集包围盒。

### 3.2 ViewModel 与设置

- `PaintViewModel`：
  - `slotEvading: Array<MutableState<Boolean>>`
  - `slotEvadeAlpha: Array<Animatable<Float>>`（**surface 与 content 唯一共享动画源**）
  - `autoHidePanelsOnStroke`（默认 `true`，`paint_prefs` 键 `autoHidePanelsOnStroke`）
  - `isStrokeSessionActive`（`@Volatile`，`strokeStarted` setter 单点同步）
  - `updateAutoHidePanelsOnStroke(false)` 立即清空避让并 `snapTo(1f)`
- `SettingsTabPage`「视图显示」：在“面板固定与自由平移”下方新增开关行。
- `StylusSettingsSubPage` 已恢复 `fcfbc7dd` 干净状态。
- 中英文文案更新为 50% 半透明避让描述。

### 3.3 Compose 三明治分层

`ReComponents.kt`：

- `Modifier.reportUiSlot`：纯边界注册；按 `LocalDensity` 将阴影 dp 换算为像素后传入
  tracker。
- `Modifier.evadablePanelSurface`：
  - `LaunchedEffect(isEvading)` 驱动**唯一** `Animatable`：`1.0f <-> 0.5f`，70ms。
  - `EVADING` 时卸载 `hazeEffect`；`drawWithContent` 先画背景（`baseOpacity * alpha`），
    再 `drawContent()`，最后以 `alpha = 1.0f` 沿 `shape` 画 1dp 边框。
  - 返回 `NORMAL` 的 70ms 内用 `(1 - factor) * 2` 对纯色层做交叉淡化，无 `blurRadius` 动画。
- `Modifier.evadablePanelContent`：`graphicsLayer { alpha = evadeAlpha.value }`；
  笔画会话活跃时在 `PointerEventPass.Initial` 消费额外触点，保证绘画态优先。

9 个面板接入：5 个 `QUICK_*`、`REFERENCE_WINDOW`、`PINNED_COLOR_PANEL`、
`PINNED_LAYER_PANEL`、`PINNED_BRUSH_PANEL`。

### 3.4 前缓冲孔径协同合成

- `RealInkController.presentPending`：前缓冲 `renderInkScreenRegions` 成功后，遍历本帧
  `screenRects`，用 `accumulateIntersection` 检测孔径相交；仅当相交时
  `view.postInvalidateOnAnimation(交集包围盒)`。未相交时不触发主画布失效，
  `PublishedTiles` 保持 `TILE_UNDRAWN`。
- `CanvasTouchView`：
  - `EVADE_ENTER_DP = 32f`、`EVADE_EXIT_DP = 40f`、`POST_UP_CONTINUATION_MS = 80L`。
  - `evaluateUiEvasion50(...)` 在 `handleToolDown` / `handleToolMove`（任何 `touchMove`
    之前）调用；包络包含当前点、历史点、`priorStrokeScreenPos`、辅助绘图点与预测点。
  - 正常抬笔启动 80ms 延续；下一笔落点仍在 `40dp + brushR` 内则无缝保持 50%。
  - `clearAllEvasionState(...)` 接通 12 条复位路径：抬笔 80ms、`ACTION_CANCEL`、笔画中
    撤销/重做中止、长按吸色、禁用画布触控、三指编辑菜单/重做、双指缩放/旋转/撤销、
    QuickShapeEditor、切工具、浮层打开、视图卸载、总开关关闭。

### 3.5 合成顺序

```
final = panel_border_shadow(alpha = 1.0)
        over ( panel_content_bg(alpha = 0.5)
               over ( new_ink
                      over old_canvas ) )
```

孔径 `F_p` 内前缓冲被 `clipOutUi` 裁空，主窗口 HWUI 按原生画家算法合成；
不存在 `ink(0.5) over panel`。

---

## 4. 与 v5.0 文档的偏差

1. **共享动画的实现位置**：文档建议 ViewModel 持有 `Animatable`/`State`；实际在
   ViewModel 持有 `Animatable`，由 `evadablePanelSurface` 内**唯一**一个
   `LaunchedEffect(isEvading)` 驱动。没有第二处 `animateFloatAsState`，满足红线。
2. **边框绘制门控**：`evadablePanelSurface` 新增 `drawBorder` 参数。`ColorPanel` /
   `LayerPanel` / `BrushPanel` 仅在“已钉住”时绘制 1dp 边框，避免未钉住的模态浮层
   在 NORMAL 态凭空多出边框。5 个 `QUICK_*` 与 `REFERENCE_WINDOW` 始终绘制。
3. **测试文件处理**：除按计划重写 `UiRingDetectorTest` 为
   `UiPanelEvasionStateMachineTest` 外，保留了 Phase 1 的
   `UiPanelOcclusionTrackerTest` 并将涉及已删除 API 的用例改写为等价的新行为断言
   （23 个用例），没有删除仍有效的 owner 栈/并集/hitTest/版本测试。
4. **额外测试**：新增 `inactive_or_non_evadable_slot_never_evades`，覆盖未激活/不可避让
   槽位强制回到非避让态。
5. **局部失效裁剪**：`postInvalidateOnAnimation` 的矩形额外夹到 `[0, viewW] × [0, viewH]`，
   避免越界参数。
6. **阴影外扩值**：按文档初始值落地（侧 16~18dp、顶 10~12dp、底 22~26dp），
   尚未真机校准。

---

## 5. 四道机械门禁

均在工程根目录 `<workdir>\ReveriePaint` 执行，最终提交 `1c760637`：

```bash
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:compileDebugKotlin
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:testDebugUnitTest
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:lintDebug
JAVA_HOME='D:/c-move/.jdks/jdk-17.0.20.1+1' ./gradlew :app:assembleDebug
```

| 门禁 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL**（仅既有 deprecated 警告） |
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL**，906 tests / 0 failures / 0 errors / 0 skipped |
| `:app:lintDebug` | **BUILD SUCCESSFUL**；265 warnings / 5 hints（无新增 error；改动文件 0 error） |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** |

### 单测明细

| 测试类 | 用例数 | 失败 |
|---|---|---|
| `UiPanelEvasionStateMachineTest` | 9 | 0 |
| `UiPanelOcclusionTrackerTest` | 23 | 0 |
| 全工程合计 | 906 | 0 |

新增状态机测试覆盖：32dp 进入、40dp 离开滞后、面板内部距离 0、多面板独立判定、
阴影外扩只扩孔径不虚增触发距离、渐隐态保留孔径、脏区交集精确包围盒、全量复位、
未激活/不可避让槽位。

### APK

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：约 123 MB（arm64-v8a，预编译 native libs）

### adb / 真机

- 本机 `adb` 不在 PATH，默认 SDK 路径下也未找到 `platform-tools/adb.exe`。
- 结果：**未连接 / adb 不可用**，未执行安装、启动与 logcat 抓取。

---

## 6. 已知风险与未验证项

1. **真机视觉未验证**：圆角四角残影、底部软阴影切断、50% 面板与墨的合成顺序、
   毛玻璃交叉淡化均需真机确认；静态代码已按 v5.0 架构落地，但“抬笔前后像素 diff = 0”
   尚未实测。
2. **阴影外扩值未校准**：`shadowPad*Dp` 为文档初始值。若真机仍有阴影边缘被切，
   需按实际 `Modifier.shadow` 外溢微调（侧 16~18、顶 10~12、底 22~26）。
3. **场景 B 性能未实测**：画笔真正穿过面板孔径内部时，主窗口局部
   `postInvalidateOnAnimation` 与瓦片级 COW 的耗时/帧率影响未知；需 `PerfHud` 对比
   `fcfbc7dd`。
4. **80ms 抬笔延续**：逻辑位于 Android `View` 层，未做 Robolectric/JVM 单测；
   高频排线防闪烁需真机验证。
5. **新增 1dp 边框**：按计划为可避让面板绘制 `Morandi.border` 1dp 轮廓。NORMAL 态
   与旧版（`glassBorder` 为空实现）存在细微视觉差异，需用户确认是否符合“视觉一致”。
6. **绘画态被动穿透**：`isStrokeSessionActive == true` 时面板在 Initial pass 消费触点；
   意味着笔画进行中无法点击/拖拽任何面板。这是需求预期行为，但未真机验证多点触控
   边界（手掌/第二支笔）。
7. **`overlayPanelsOpen` setter 副作用**：由 `CanvasView` 的 AndroidView update 块写入，
   会同步清理避让状态；未观察到重组异常，但未真机长时验证。
8. **预测点合并**：预测点只并入包络提前触发，不会单独把已避让面板判为离开；仍需
   在三种预测模式下逐一验收。
9. **性能红线未量化**：区域 I 的 `TILE_UNDRAWN` 命中率、前缓冲帧耗时、`ACTION_MOVE`
   GC 分配均需真机 `PerfHud`/`TilePerfLog` 对比确认。
10. **Popup/Dialog 与 `paintingUiScale`/左手模式**：未注册槽位，理论上不受影响；
    需按第 12 项真机验收。

---

## 7. 明天真机测试清单（按文档 §9.1）

- [ ] **1. 圆角四角零残影**：不透明浅/深画布 + APP 软件预测，粗笔紧贴
      `QuickActionWindow` / `QuickColorWindow` 四个圆角外弧涂抹并按住不抬笔，
      400% 放大对比抬笔前后，确认无直角残影、像素 diff = 0。
- [ ] **2. 底部软阴影零切断**：不透明白纸 + 深色笔紧贴悬浮面板底部阴影区来回运笔
      不抬笔，确认阴影完整叠在新墨上方、无刀切感。
- [ ] **3. 50% 内容/背景 + 100% 边框/阴影**：笔尖进入 `32dp + brushR`，确认边框与
      阴影 100% 清晰，背景与内部图标平滑降至 50%。
- [ ] **4. 面板在墨之上**：运笔穿过 50% 面板内部并按住，确认半透明底色与图标压在
      新笔迹上方（墨从面板下方透出），抬笔瞬间无跳变。
- [ ] **5. 毛玻璃交叉淡化**：开启背景模糊，进入 32dp 时毛玻璃平滑淡出为 50% 纯色；
      移出 40dp 时平滑恢复，无卡顿/闪烁。
- [ ] **6. 运笔中途离开立即恢复**：一笔不抬起，从面板附近划向远处（`> 40dp + brushR`），
      确认运笔途中 70ms 内恢复 100%。
- [ ] **7. 多面板独立与并发**：快捷操作与快捷颜色并排，笔经过两者之间同时变 50%，
      随后偏向一侧，确认远离者先恢复 100%。
- [ ] **8. 高频排线/点画 80ms 防抖**：面板边缘快速排线（抬笔间隔 < 80ms），确认稳定
      保持 50%，不在 50%/100% 间闪烁。
- [ ] **9. 绘画态优先交互**：从画布起笔划入面板按钮区，笔画连续不断；抬笔后点击/拖拽
      面板 100% 正常响应。
- [ ] **10. 全预测模式与总开关**：总开关开启时分别切换“关闭预测（原生）/硬件算法/
      APP 软件算法”，三种模式均触发 50% 避让；关闭总开关后不避让，但圆角与底部阴影
      依然无覆盖破绽。
- [ ] **11. 异常中断复位**：面板处于 50% 时触发双指撤销、三指菜单、长按吸色、
      速创 650ms 停顿、切工具，确认均立即恢复 100%。
- [ ] **12. Popup/Dialog、左手模式与 `paintingUiScale`**：切换 `0.75x / 1.0x / 1.35x`
      与左手模式，打开任意 Popup/Dialog，确认裁剪/避让边界不差、Popup 不被前缓冲覆盖。

### 性能基线（文档 §9.2）

- [ ] **场景 A**（自由区 + 面板外预警圈）：前缓冲帧耗时变化 `<= ±0.05ms`，
      `TILE_UNDRAWN = 100%`，`ACTION_MOVE` GC 分配 = 0。
- [ ] **场景 B**（画笔穿过面板阴影/50% 本体）：记录 PerfHud 帧耗时与端到端延迟；
      若出现可感知波动，按 §9.2 上报用户裁决，**不得私自退回 `ink(0.5) over panel`**。

---

## 8. 用户裁决项

静态施工阶段未发现“视觉一致 + 零可测回退”的物理冲突，因此**当前没有需要用户立即裁决的
架构取舍**。以下两项需真机数据后再决定：

1. 场景 B 若在某设备出现可感知帧率波动：是否启用备选“浮动 UI 独立
   `TYPE_APPLICATION_PANEL` 硬件图层提升方案”（文档 §9.2）。
2. NORMAL 态新增的 1dp 面板轮廓是否符合“视觉一致”；若不符合，可改为仅在
   `EVADING` 时绘制边框（单点开关，不影响孔径裁剪与合成顺序）。
