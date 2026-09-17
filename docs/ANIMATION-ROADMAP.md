# ReveriePaint 动画功能技术方案

> 状态: 方案草案, 待评审
> 日期: 2026-09-15
> 目标水准: FlipaClip / Procreate Dreams / CSP 级位图逐帧动画

## 1. 结论摘要

三句话版本:

1. **不要在 Kotlin 侧自建帧模型。** Krita 的动画内核（`KisImageAnimationInterface` / `KisRasterKeyframeChannel` / `KisOnionSkinCompositor`）已完整编译进 APK，只是从未被调用。动画的脏活——帧↔时间映射、瓦片级脏矩形、投影缓存失效、后台帧再生——引擎早已解决。
2. **不要搬运 pencil-dream 的图层/帧管理层。** 考古确认它的 `BitmapImage` 内部只有一个 `QImage`，无瓦片、无金字塔、无脏矩形。这套结构在移动端多帧场景下必然内存爆炸，比 Krita 的方案落后一整代。它对我们是**交互标本**，不是代码基座。
3. **真正的门槛是构建链与移动端内存。** 任何新增 JNI 方法都必须 `-PbuildNative` 重编译 C++，这决定了开发节奏；而 Krita 桌面向假设内存充足，需要为移动端加约束。

## 2. 考古：pencil-dream 到底能给我们什么

浅克隆至 `/home/lanrhyme/Projects/pencil-dream-ref`（40 MB）。结论基于源码实读。

### 2.1 帧模型——确认无用

| 事实 | 位置 |
|---|---|
| 帧数据 = `BitmapImage : KeyFrame`，内部仅 `QImage mImage` | `core_lib/src/graphics/bitmap/bitmapimage.h:189` |
| 工程根容器 `Object::paintImage(QPainter&,...)` 深绑 QPainter | `core_lib/src/structure/object.cpp:1304` |
| 有曝光概念：`KeyFrame::mLength` / `mLengthExplicit` 双模语义 | `core_lib/src/structure/keyframe.h:41-48` |
| 块尾计算 `Layer::getBlockEnd()` | `core_lib/src/structure/layer.cpp:765` |

它的暴露长度是**显式长度 + hold 到下一帧**的二义模型，而 Krita 的 raster keyframe 天然就是 hold 语义（关键帧持续到下一关键帧）。两者等价，但 Krita 附带瓦片回收和脏区追踪。**结论：帧管理层不用它的。**

### 2.2 可直接移植的资产（Qt 解耦，零障碍）

以下模块只依赖 `QImage / QRgb / QVector / QtMath`，剥掉 Qt 类型后可直接接 `KisPaintDevice`：

| 模块 | 文件 | 行数 | 价值 |
|---|---|---|---|
| **自动中割** | `core_lib/src/graphics/bitmap/inbetween.cpp` | 288 | **核心**。手写 3-4 chamfer 距离场，双源双向传播，位移插值 + rad盘光栅化 + boxBlur + 8连通去噪 |
| MLS 变形 | `.../bitmap/mlswarp.cpp` | 740 | 中割工具的配套：移动最小二乘刚体/仿射变形 |
| 镂空填充 | `.../bitmap/holefiller.cpp` | 327 | 连通域 + 闭运算 + 多源 BFS，参考色填充 |
| 扫描线填充 | `.../bitmap/scanlinefill.cpp` | 367 | 油漆桶 mask 的扫描线区间构造 |
| 油漆桶 | `.../bitmap/bitmapbucket.cpp` | 430 | 与上者配合的 mask 管线 |

**自动中割**（`interpolate(a, b, t, Options)`，`inbetween.cpp:191`）详查：
- 依赖: 仅 `QVector / QtMath / algorithm / limits`，**无 OpenCV、无 Eigen**
- 输入: 两张同尺寸图，**只读 alpha 通道**（阈值 >16 二值化，`:173`）
- 核心: 手写两遍扫描 chamfer（`chamferWithSources:33`，relax 步长 3/4，携带最近源点传播）
- 步骤: 双向 DT → A 线像素按 `t·(nearestB−a)`、B 线按 `(1−t)·(nearestA−b)` 位移 → 撒盘 `stamp:216` → `boxBlur:103` → 去噪 `:123` → 输出 ARGB32_Premultiplied
- 移植方式: QImage 读写换成按 alpha 阈值访问 `KisPaintDevice`，其余原样。**这是本方案里唯一值得跨 repo 搬运的核心算法。**

k-means 色卡：`extractPaletteColors(const QImage&, int)`，`app/src/referencecardpanel.cpp:64`，约 130 行自由函数，桶众数播种 + Lloyd 迭代，易剥。

### 2.3 许可证

仓库**全仓无 SPDX 标识**，仅 GPL-2 样板块头（根 `LICENSE.TXT`）。该文件是标准 GPLv2 全文，**未出现 "only" 限制**，故属 `GPL-2.0-or-later`；or-later 条款允许接收方以 GPLv3 条款分发并入后的作品，与本项目的 `GPL-3.0-or-later` 单向兼容。

移植规矩三条：
1. 搬入的文件**保留原 Pencil2D 版权头**（2005-2007 Corrieri & Naidon；2012-2020 Matthew Chiawen Chang）不动
2. 在其后追加本项目的 `SPDX-License-Identifier: GPL-2.0-or-later` 并注明来源仓库与 commit
3. **绕开两处有 Krita 血统的文件**：`gapmap.h`（明载改编自 Krita `KisGapMap`，Maciej Jesionowski）与 `colorizeengine.cpp`（`KisWatershedWorker` 等，Dmitry Kazakov）——这两块我们用引擎原生即可，不必反向引入

## 3. 本地环境（已确认）

```
Qt for Android : /home/lanrhyme/Qt6/6.6.3/android_arm64_v8a
Krita 源码      : /home/lanrhyme/Projects/krita-source
```

## 4. 目标架构与改动坐标

### 4.1 C++ 侧

| 项 | 位置 |
|---|---|
| 新 JNI 文件 | `app/src/main/cpp/reverie_jni_animation.cpp` |
| 新引擎域文件 | `app/src/main/cpp/ReverieCoreAnimation.cpp` |
| CMakeLists 登记 | JNI 源加在 **186 行后**；引擎源加在 **200-207 之间**（kritaimage 已在 `KRITA_LIBS`，无需改链接） |
| JNI 命名模板 | `Java_com_reverie_paint_core_ReverieCoreBridge_<name>`，参考 `reverie_jni_core.cpp:128` |
| 取动画接口 | `m_document->animationInterface()`（`m_document` 是 `KisImageSP`，`ReverieCore.h:533`） |
| 渲染入口 | `renderToBuffer(quint8*, w, h, forceFull=false)`（`ReverieCoreRender.cpp:14`），支持脏矩形，`lastWrittenRect()` 回传增量区域 |
| 统一撤销入口 | `pushUndoCommand(KUndo2Command*)`（`ReverieCoreStroke.cpp:692`） |

> **注意**: `reverie_jni_common.h` 只有 16 行，无任何工具函数。字符串/位图转换在各 .cpp 里手写，新增文件照抄现有写法即可。

### 4.2 Kotlin 侧

| 项 | 位置 |
|---|---|
| 新增 ViewModel 扩展 | `core/PaintViewModelAnimation.kt`（顶层 `internal fun PaintViewModel.*`，与主文件同包） |
| 引擎调用唯一入口 | `PaintViewModel.kt:2259 runCore(render, after, op)` → `renderHandler.post { op(); scheduleRender(); mainHandler.post{ after() } }` |
| 渲染触发 | `:2448 scheduleRender(immediate)` → `:2481 doRender()` |
| UI 插入点 | `ui/painting/PaintingPage.kt:1324` 之后、`:1327` 之前，`Modifier.align(BottomCenter).zIndex(20f)` |
| 手势屏蔽同步 | `CanvasView.kt:632` 的 `overlayPanelsOpen` 布尔式需加 `animationPanelOpen` |
| 帧缩略图复用链路 | `Bridge.renderLayerThumb(index, bitmap)` + `PaintViewModelLayers.kt:38 thumbFor()`（56×56，双键 LRU 缓存，`:2630-2637`） |
| 导出单帧原语 | `PaintViewModelProjects.kt:604 exportDocument(...)` |
| 主题 | `Morandi.panel / panelHi / accent / border / text / subText / icon`，配 `Glass.barStyle` + `Motion.enterSpring()`；容器可参照 `LayerPanel.kt:232` 的最大 3/4 屏高约束 |

## 5. 分阶段路线

### Phase 0 — 构建链验证（必须先做，半天）

在一行业务逻辑之前，先证明能改 C++：

1. 加一个一次性 JNI 探针 `nativeAnimationProbe(): Int`
2. `./gradlew assembleDebug -PbuildNative -PcmakeArgs="-DQT_ANDROID_DIR=/home/lanrhyme/Qt6/6.6.3/android_arm64_v8a"`
3. 装真机跑通，确认「编译 → 打包 → 安装 → JNI 调用」全链路可用

**这一步不通过，后面所有阶段都不成立。**

### Phase 1 — 引擎单图层帧打通（能增删关键帧、能拨时间）

目标：单个位图图层上能增删关键帧，能切换当前时间并正确显示对应帧内容。

- C++: `ReverieCoreAnimation` 域，封装 `enableAnimationForLayer` / `addKeyframe` / `removeKeyframe` / `setTime`
- Kotlin: `PaintViewModelAnimation.kt` + 最小帧条 UI（上一帧 / 下一帧 / 加帧 / 删帧）
- 验证真机：10 帧循环，涂抹内容不串帧，撤销正确回帧

### Phase 2 — 洋葱皮与播放

- 洋葱皮：`KisOnionSkinCompositor::instance()` 配置前后帧数 + 着色，走现有 `renderToBuffer` 路径
- 播放器：新写 `AnimationSession`，**调度结构照抄 `PlaybackEngine.kt:310-356`**（stepGen 代际令牌、`postDelayed` 自重投、`scheduleRender(immediate=true)` 触发点、`isPlaying/elapsedMs` 三件套 State）
  - 必须改：`PlaybackEngine.kt:355` 硬编码 16 ms 换成按 fps 算的 `frameDurationMs`
  - 不能复用它的语义：它是事件流顺序消费，帧动画需要按索引随机 seek

### Phase 3 — 完整时间轴（TVPaint 语义）

- 曝光块 / trim ripple / 一拍 N / 循环克隆 / 多帧拖动
- 这部分 pencil-dream 帮不上忙（见 §6），需要自研 + UI 重写
- 数据层可用的参考实现清单见 §2.1 表格

### Phase 4 — 高级创作能力

按价值排序，逐项评估：

1. **自动中割**（移植 `inbetween.cpp`）— 这是 pencil-dream 移植的最高价值项
2. 摄影表 X-sheet 面板（纯 UI，Compose 重写，参考其原画圆圈 / 中割圆点语义）
3. 音轨层 + 波形
4. 导出 GIF / MP4 / 序列帧（注意：`runCore` 是异步的，逐帧导出必须串行回调驱动，不能 for 循环 post）
5. k-means 色卡提取
6. MLS 变形辅助

## 6. 必须自研的交互语义（pencil-dream 帮不上）

时间轴交互的语义**散落在 4148 行的鼠标事件处理里**，README 是唯一成文规格：

| 能力 | 它写在哪 | 我们要做什么 |
|---|---|---|
| 一拍 N | `app/src/timeline.cpp:591-640` | 自写：重排规则 + 不删帧保证 |
| 循环克隆 | `app/src/timeline.cpp:222-232` | 自写 |
| trim 拖拽 | `app/src/timelinecells.cpp:856-880 / 1064-1183 / 2617-2620 / 2861-2880` | 自写：触控拖拽 + ripple 预览 |
| "+" 把手建帧 | UI 层 | 自写 |

而**模型层这几处可以照抄语义**（它们是 Qt 解耦的）：

`moveKeyFrame:246` / `swapKeyFrames:304` / `moveSelectedFrames:638` / `absorbGapAt:917` / `absorbGapsAt:941` / `setExposureForSelectedFrames:533`（含 ripple 推挤）/ `getBlockEnd:765` / `captureKeyFrameLayout:854` / `applyKeyFrameLayout:870`（布局事务，单步撤销的关键设计）

## 7. 关键技术风险

### 7.1 坑：KisPaintLayer 不会自动创建关键帧通道

这是整个 Phase 1 最容易踩的暗雷，已由源码确证：

- `kis_paint_layer.cc:86-97` 主构造函数**只**建 `KisPaintDevice`，**完全不涉及 keyframe channel**；只有 copy 构造分支（`:114`）才有 `enableAnimation()`
- 唯一创建路径是 lazy：`KisBaseNode::getKeyframeChannel(id, true)`（`kis_base_node.cpp:436`）→ `KisPaintLayer::requestKeyframeChannel`（`kis_paint_layer.cc:330-340`）
- `KisPaintDevice::createKeyframeChannel`（`kis_paint_device.cc:2052`）会**自动 `addKeyframe(0)`**——frame 0 永远存在
- **致命顺序约束**：`KisNode::addKeyframeChannel`（`kis_node.cpp:351`）内部访问 `graphListener->keyframeChannelHasBeenAdded`，因此**必须在 layer 已挂到 image 之后**调用，否则 `m_d->graphListener` 为空 → 崩溃

⇒ 实施时必须在 `addLayer` 的 `KisImageLayerAddCommand` 推送完成之后再打开动画通道。

### 7.2 坑：撤销必须传父命令

`KisKeyframeChannel::addKeyframe(time, parentUndoCmd=nullptr)`（`kis_keyframe_channel.h:55`）。默认 nullptr **不产生任何撤销记录**。必须把 `pushUndoCommand` 要 push 的顶层命令（或 macro）作为 parent 传进去，否则用户删除一个关键帧将无法撤销。

### 7.3 移动端内存

Krita 桌面版默认内存充足。多帧 + 帧缓存在平板/手机上会快速触顶。需要在 Phase 2 明确策略：
- 洋葱皮前后帧数上限（建议 ≤3）
- 是否引入 `KisAnimationFrameCache`（`libkritaui.so` 31 个符号）做播放预渲染，还是播放时不缓存
- 长片段时的降级路径

### 7.4 与项目铁律的校验

| 铁律 | 本方案如何满足 |
|---|---|
| 文档真身在 C++ | 帧数据完全落在 Krita `KisPaintDevice`，Kotlin 只持有帧号/时间的状态镜像 |
| 引擎调用不上 UI 线程 | 所有动画操作走现有 `runCore()` → `reverie-render` HandlerThread |
| 双缓冲渲染 | 复用现有 `frontBuffer/backBuffer`（`VM:2169-2170`），播放逐帧只走 `scheduleRender(immediate=true)` |
| 热路径零分配 | 这是**最大挑战**：Krita 的时间切换会触发 setGlobal / setRegenerate frames，需实测每帧是否产生大量临时对象 |
| 单实例引擎 | 动画同样假设单窗口，无重入防护，时序由 ViewModel 保证 |
| Qt 无 GUI | `KisOnionSkinCompositor` 在 `libkritaimage.so`（非 kritaui），不引入 QWidget 依赖 |

注：C++ 侧目前**只有一把锁** `m_sizeCurveMutex`（`ReverieCore.h:611`），不保护 `m_document` / `m_layers`。并发正确性完全依赖 Kotlin 侧单 HandlerThread 串行——新增动画操作**绝不能**绕过 `runCore()`。

## 8. 待解决问题

1. 时间切换与笔触并发的行为未定义：绘制中途调用 `requestTimeSwitchNonGUI` 是否会导致笔画串帧或投影撕裂，需 Phase 1 实测
2. 播放范围用 `setDocumentRange` 存引擎，还是 UI 独立维护（关系到 KRA 兼容性）
3. 导出格式优先级：GIF / MP4 / 序列 PNG，取决于是否引入 ffmpeg
4. 是否支持图层级循环模式（TVP 语义）——Krita 无直接等效物，需自研

## 9. 实施进展（2026-09-15）

分支 `feat/animation-timeline`。

### 参考对象调整

主参考改为 **Krita-TVP-Timeline**（`/home/lanrhyme/Projects/Krita-TVP-Timeline`）——
用户自己的 Krita Python 插件，10722 行，GPL-3.0（与本项目同许可，无兼容问题）。
其 `tvp_timeline/cpp_source/tvp_timeline_bridge.cpp`（987 行）是桌面端**已验证**的
Krita 关键帧 API 封装，直接 include 内部头调用（非反射），因此对 Android JNI 有直接参考价值。

pencil-dream 降级为「纯算法来源」，仅保留 §2.2 的中割/变形等移植清单。

从 TVP bridge 得到的两条硬结论（已用于实现）：
- `node->getKeyframeChannel(KisKeyframeChannel::Raster.id(), true)` 就是"开启动画"，**幂等安全**
- **仅 `KisPaintLayer` 支持 Raster 通道**：`KisBaseNode::requestKeyframeChannel`
  （`kis_base_node.cpp:469`）对其余 id 一律 `return 0`，故 group/调整/填充层上调用只得到空指针，不崩溃
- 其"一拍N"用四阶段停车重排（停放区 -> 删原地 -> 落位 -> 清理停放区），本实现沿用

### 已完成

| 项 | 位置 |
|---|---|
| C++ 动画域 | `app/src/main/cpp/ReverieCoreAnimation.cpp`（新建） |
| 引擎 API 声明 | `ReverieCore.h` 图层区之后新增 Animation 区块（29 个方法） |
| JNI 层 | `app/src/main/cpp/reverie_jni_animation.cpp`（新建，25 个导出） |
| Kotlin 契约 | `core/ReverieCoreBridge.kt` 追加 25 个 external fun |
| 状态镜像与操作 | `core/PaintViewModelAnimation.kt`（新建） |
| 主类挂载 | `PaintViewModel.kt` 新增 `internal val anim = AnimationState()` |
| 动画画布后端 | `startPainting` 新增可选参数 `animation` / `animationFps`（扩而不改） |
| 构建登记 | `CMakeLists.txt` 登记两个新源文件 |

### 与桌面版的两处有意差异

1. **撤销**：桌面版 `addKeyframe(time)` / `removeKeyframe(time)` 未传 `parentUndoCmd`，
   因此这两处无撤销记录。Android 版全部操作都构建 `KUndo2Command` 并走 `pushUndoCommand`，
   帧的增删改均可单步撤销。
2. **播放调度**：桌面版用 `KisPlaybackEngine`（`libkritaui.so`，QObject）。
   Android 版在 Kotlin 侧自建调度：直接投 `reverie-render` 线程，步进间隔按帧率换算。
   原因有二——避免 `libkritaui.so` 的 UI 依赖，以及过程回放引擎里硬编码的 16 ms
   在 12 fps 下会让播放快数倍。

### 构建验证

```
BUILD SUCCESSFUL in 36s
nm -D 确认新 libreverie_jni.so: 217 个 Java JNI 符号 (原 191 + 动画 25)
     引擎侧 25 个 ReverieCore 动画方法全部导出
```

root 身份运行 gradle 需显式指定 `GRADLE_USER_HOME` 与 `ANDROID_USER_HOME`（见 §3 备注）。

**真机回归尚未执行**——需要时间轴 UI 落地后才能手动验证增删帧、撤销回帧、播放不串帧。

### 第二波：UI（同一分支，紧随其后）

| 项 | 位置 |
|---|---|
| 时间轴面板 | `ui/painting/animation/AnimationTimelinePanel.kt`（新建） |
| 挂载点 | `PaintingPage.kt` 的 `PickerLayerSourceBar` 之后，`zIndex(20f)`，`AnimatedVisibility` 受 `vm.anim.enabled && vm.anim.panelOpen` 控制 |
| 动画画布开关 | `CreatePage.kt` 的 `CreateCanvasActions` 内新增开关行，三个布局分支共用该组件，故一处改动全覆盖 |

时间轴面板能力：
- 顶部把手上下拖拽调高度（140–460 dp），拖到底自动收起；双击在大小档间切换
- 轨道区双指捏合缩放帧宽（8–160 px，锚定双指中心）+ 单指平移；垂直滚动自己维护 `scrollY`，
  左右两列同步偏移（用 `verticalScroll` 会导致左右不同步，故不用）
- 关键帧块宽 = 曝光长度（hold 语义：持续到下一个关键帧），当前帧高亮
- 底部控制条：上一帧 / 播放 / 下一帧 / 新建空白帧 / 复制帧 / 删除帧 + 帧率与帧号显示
- 图标全部用 Canvas 自绘，不引入图标库依赖

### 下一步

1. 竖屏快速创建入口 `PortraitPresetBottomBar` 尚未接入动画开关（主路径的自定义页已支持）
2. 洋葱皮接引擎（`KisOnionSkinCompositor`），`AnimationState` 字段已预留
3. 帧缩略图（可复用 `Bridge.renderLayerThumb` + `thumbFor()` 的 56×56 双键缓存，但需先切时间）
4. 撤销后的关键帧缓存同步（需挂到 undo 流程）
5. 音频/视频导入（图层即轨道的模型天然容纳音轨层）
6. 一拍 N / 批量移动等 TVPaint 语义的 UI 入口（引擎侧已实现，缺交互）
7. 动画导出（已完成：纯 Kotlin GIF89a 动图编码器、MediaCodec H.264 MP4 视频硬件编码器、PNG 序列帧 Zip 归档打包器）
