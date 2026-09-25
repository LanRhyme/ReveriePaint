# 画布渲染路径深度优化记录

> 参考项目: [SpeedyNote](https://github.com/alpha-liu-01/SpeedyNote) (GPL-3.0, Qt/C++/Android)
> 日期: 2026-09-24
> 范围: 渲染路径分离、按视口裁剪、热路径零分配、脏区局部失效
> 约束: 不破坏 Krita 内核、不破坏既有功能、不做大范围重构 (见 Plan.md)

## 1. SpeedyNote 的可迁移结论

读其 `source/core/DocumentViewport.h` + `ViewportPerfMonitor.h` 后提炼出三条与本项目
直接相关的做法:

| SpeedyNote 做法 | 出处 | 对本项目的含义 |
|---|---|---|
| `paintEvent` 按**路径分支**, 手势/拖拽走缓存帧快路径, 只有内容真变才全量合成 | `DocumentViewport.h:4908` `renderEdgelessMode(painter, dirtyRect)` | 本项目"变换手势不重渲染引擎"已经做到; 缺口在**绘制侧的按视口裁剪**与**失效区域** |
| 每帧 paint 都带 `dirtyRect`, 瓦片遍历被限制在它覆盖的瓦片内 | 同上 | 高倍率下的像素网格原先整幅遍历, 是同一类问题的放大版 |
| `ViewportPerfMonitor` 把帧**分桶统计**(pan/zoom、整屏合成、局部更新), 零分配环形缓冲 | `ViewportPerfMonitor.h:42-191` | 本项目已有 [`PerfTrace`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt:24), 但缺"按渲染路径分桶" |

SpeedyNote 是自绘 `QWidget` + 矢量笔画, 其"整帧缓存搬运"无法照搬; 可搬的是
**"渲染路径要分开、每帧要知道自己该画哪块"** 这一原则。

## 2. 本项目渲染链路现状 (改动前)

```
触摸 → CanvasTouchView 派发 → VM.queueStrokeMove → 引擎 HandlerThread
     → C++ 脏区合成 (renderToBuffer, 已支持 1:1 / 缩放两路 + 脏区增量)
     → 双缓冲交换 → tv.postInvalidate()  ← 整屏重绘
     → CanvasTouchView.onDraw: 棋盘格 + 位图 + 像素网格 + 预测笔迹 + 镜像 + 光标
```

已经做得很到位的地方 (本次不动):

- C++ 侧绘制中绕过 Krita 后台调度器, 直接按脏区同步合成 (`ReverieCoreRender.cpp:110-118`);
- 1:1 与缩放两条路径都走脏区增量, 并回传 `lastWrittenRect`;
- `blitBgraToRgbaFast` 已是 NEON 实现 (`ReverieCoreInternal.h:827`);
- 输入批处理、落笔 kick、双缓冲轮转均已零分配。

现存的三处浪费:

1. **失效区域 = 整屏**: 一小段笔画的脏区 (几百像素) 也让整屏重绘;
2. **像素网格整幅遍历**: `scale >= 4` 时对 `0..bmp.width` / `0..bmp.height` 全遍历,
   4096 画幅每帧上万条 `drawLine`, 而屏幕可见的只有几百条;
3. **覆盖层逐点重算变换**: 镜像笔迹每个点都现算一次三角函数 (且每段重复变换前一点),
   预测笔迹每帧 `parseColor` + 一次 JNI 压力曲线调用。

## 3. 首轨已落地 (纯 Kotlin/View, 本机可编译验证)

### 3.1 视图变换缓存 —— `model/CanvasViewTransform.kt` (新增)

- 缓存视图参数, 三角函数只在参数变化时算一次; 结果写入 `out` 数组, 调用侧零分配;
- 与 `CanvasView.kt` 的 [`imageToWidget()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasView.kt:406) /
  [`widgetToImage()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasView.kt:373) 同一套公式, 由
  [`CanvasViewTransformTest`](../app/src/test/java/com/reverie/paint/model/CanvasViewTransformTest.kt:1) 锁死往返一致性与旋转包围盒行为;
- 另提供 `bitmapRectToScreenBounds` (脏区 → 屏幕轴对齐包围盒) 与 `screenToBitmap` (视口裁剪用)。

### 3.2 像素网格按视口裁剪 —— `CanvasTouchView.onDraw`

只绘制视口内可见的网格线, 并设线条数上限 `MAX_VISIBLE_GRID_LINES = 6000` 兜底
(超限时宁可不画网格也不掉帧)。收益随"画幅 / 缩放比"放大: 4096 画幅 4x 放大时,
绘制线条数从约 8192 条降到数百条。

### 3.3 覆盖层热路径零分配 —— `CanvasTouchView`

- 镜像笔迹: 整条笔迹共用一份变换, 逐点只写复用数组 (旧实现每点算三角函数 + 每段重复变换);
- 画笔颜色解析缓存 (`brushColor` 是字符串, 原先每帧 `parseColor`);
- 压力曲线查表按 1/256 量化缓存 (原先落笔期间每帧一次 JNI 调用);
- 吸色采样由 `Bitmap.getPixel` 改为复用数组 + `getPixels` (每采样点少一趟 JNI)。

### 3.4 脏区局部失效 —— `PaintViewModel` + `CanvasTouchView.invalidateFromRender()`

- 渲染线程写完后发布本帧 `renderDirtySnapshot` (双缓冲交替发布, 零分配);
- 视图侧把它映射成屏幕矩形做 `postInvalidate(l,t,r,b)`;
- **安全条件不满足一律回退整屏** (宁可多画一次, 不允许边缘残影):
  画布已旋转 / 像素网格可见 / 覆盖面板打开 / 光标或预测或镜像笔迹活动 /
  变换会话进行中 / 回放页或动画播放中 / 脏区超过视口一半。

## 4. 第二轨: C++ 补丁 (需 `-PbuildNative` 验证)

| 文件 | 改动 |
|---|---|
| [`ReverieCore.h`](../app/src/main/cpp/ReverieCore.h:955) | 新增复用 scratch: `QImage m_scaledSrc` |
| [`ReverieCoreRender.cpp`](../app/src/main/cpp/ReverieCoreRender.cpp:199) | 缩放回退路径不再每帧 `new QImage` 源图, 改为尺寸不变即复用 |

效果: 缩放视图下每帧少一次 (带 1px 外扩的) 脏区尺寸分配与释放。像素内容、格式转换、
缩放算法完全不变 —— 属于"零行为差异"的性能修正。

> ⚠️ **未验证**: 本机无 Qt for Android 6.6.3 与 Krita 源码, 无法编译。落地前必须:
> `./scripts/build_native.sh`, 然后按 `docs/GEMINI-BRIEF.md` 的步骤把
> `libreverie_jni.so` 同步到 `third_party/android-native-libs/` 与 `app/src/main/jniLibs/`,
> 否则 prebuilt 模式仍用旧库, 源码与二进制不一致。

## 5. 待验证的后续方向 (按预期收益排序)

1. **视口尺寸渲染缓冲 (最大的单点收益)**
   现状渲染缓冲 = 文档分辨率 (仅按 4096 clamp, 见 `PaintViewModel.setRenderViewport`)。
   Android 侧 `Bitmap` 每次被写入后, 硬件加速渲染器需要把整张纹理重新上传:
   4096×4096×4B = 64MB/帧, 这是"大画幅不跟手"的根因, 且**局部失效救不了它**
   (纹理以整张 bitmap 为单位)。
   方案: C++ 新增"只读可见区域 → 缩放到视口尺寸"的渲染入口 (扩而不改, Kotlin 侧
   用开关控制)。收益: 上传量与合成量按 (视口面积 / 文档面积) 下降。
2. **缩放回退路径的目标图复用**
   用持久目标 `QImage` + `QPainter(SmoothPixmapTransform)` 或手写双线性取代
   每帧 `QImage::scaled()` 的分配。注意 Qt 在非预乘格式上的绘制会内部转换,
   必须先量测再定夺。
3. **渲染路径分桶统计** (借鉴 `ViewportPerfMonitor`)
   给 `PerfTrace` 增加 `render.path` 标签 (full / incremental / skipped / viewport),
   输出近 1 秒的均值与 p95, 用来量化上述改动是否真的生效。
4. **笔画进行中的 tile 级上传**
   若 1 号方案仍不足, 可把渲染缓冲切成若干 tile 位图, 只让被写到的 tile 失效,
   从根上避免整张纹理重传 (改动面较大, 需与双缓冲轮转一起重新设计)。

## 5.5 液化专项 (Plan.md 优化思路 2 + 用户实测反馈)

用户实测 (华为 MatePad Pro 13.2 / 麒麟 9020): 大画布 + 多图层 + 混合模式下液化
"严重卡顿、发热、画面撕裂, 液化区域周边出现白色线条"。

根因分析 (按可解释性排序):

1. **apply 阻塞渲染线程**: `liquifyApplyLocked` 在渲染线程执行, 每个 apply 要对
   **每个选中图层** 做一次全 worker-bounds 的 `clear + run`; 大画布多图层时单次可达
   数十毫秒, 期间触摸与渲染全部排队 → 卡顿 + 撕裂感。
2. **多图层串行**: N 个图层的 warp 依次执行, 而它们彼此完全独立 (`src`/`dst`/worker
   各自一份), 却只用一个核。
3. **投影中途被读取 (白线)**: 液化期间 `m_drawing == false`, 渲染走 Krita 异步投影。
   apply 刚 `setDirty` 完就渲染时, 投影可能还处于更新中, 于是形变区域边界读到旧像素,
   表现为沿形变边缘的白/亮线。

已落地补丁 (均需 `-PbuildNative` 验证):

| 位置 | 改动 |
|---|---|
| [`ReverieCoreMiscTools.cpp`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:10) | `#include <QtConcurrent/QtConcurrentMap>` (Qt6Concurrent 已在 CMake 的 `QT_LIBS`/include 路径里) |
| [`ReverieCoreMiscTools.cpp`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:177) | apply 拆成两阶段: 先并行 `clear+run` 各目标, 再串行写回 (painter 与脏区标记保持在渲染线程) |
| [`ReverieCoreRender.cpp`](../app/src/main/cpp/ReverieCoreRender.cpp:110) | 液化事务期间与笔画一样走"同步脏区合成"分支, 不再读异步投影 |

回归要点: 单图层液化手感/形变结果应与改动前一致; 多图层液化墙钟时间明显下降;
形变边缘白线消失 (若仍存在, 说明根因不在此, 下一步应查 `COMPOSITE_COPY` 写回时
`dst` 未填充像素对边缘的影响)。

仍待做 (需先量测): 单图层大画布时 `run` 仍是整 bounds 单线程, 若要进一步提速,
要么缩小 worker bounds, 要么改用 Krita 的 tile 级 API 自行分块 (触碰内核, 风险高,
暂缓)。

## 5.6 第二轮: 内存与调度 (Plan.md 优化思路 4 部分落地)

SpeedyNote 在这三处的做法直接可用: 缓存按**内存预算**管理而不是固定条目数; 在
**内存压力回调**里主动丢可重建的缓存而不是等 OOM; 把**重活推迟到交互停下之后**
(它的 scroll-settle 定时器)。

| 项 | 落地内容 |
|---|---|
| 回放帧缓存 | 新增 [`FrameCachePolicy`](../app/src/main/java/com/reverie/paint/model/FrameCachePolicy.kt:1): 预算 = 堆上限/16, 夹在 32MB~256MB, 淘汰按"距当前帧最远优先"。原先写死"最多 120 帧", 4096 画幅下单帧 64MB → 120 帧 7.7GB (必然触顶) |
| 内存压力 | [`PaintViewModelMemory.kt`](../app/src/main/java/com/reverie/paint/core/PaintViewModelMemory.kt:1): 注册 `ComponentCallbacks2`, `TRIM_MEMORY_RUNNING_LOW` 起释放回放帧缓存, CRITICAL/`onLowMemory` 再带上图层缩略图; 由 [`MainActivity`](../app/src/main/java/com/reverie/paint/MainActivity.kt:171) 在注入 appContext 时注册, VM 销毁时解绑 |
| 缩略图让路 | [`refreshLayerThumbs()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelLayers.kt:68): 引擎忙 (`pendingCoreOps` / 渲染队列非空) 时最多延后 3 秒再刷, 避免与笔画抢渲染线程; 面板关闭则不刷 |
| 日志开销 | C++ 新增 `RPC_TRACE` (默认关闭, `setprop debug.reverie.trace 1` 打开), 液化 apply/begin 与填充的热路径日志改走它; Kotlin 侧 [`PerfTrace`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt:33) 默认关闭, `setprop debug.reverie.perf 1` 可开 |

仍待量测后再动: Krita 投影瓦片的回收上限 (移动端未设限, 但收紧它要碰
`KisTiledDataManager` 的预算接口, 风险高于收益, 先看实测)。

### 5.5.1 代码审查修正 (2026-09-25)

对上表逐条复核后修正了三处 C++ 缺陷 —— 上表把白线归因于"投影中途被读取"并不准确:

| 发现 | 判定 | 处理 |
|---|---|---|
| `run()` 只把"有源像素映射过来"的目标像素写进 dst, 其余保持透明; 写回用 `COMPOSITE_COPY` 会把这些透明像素**擦进图层** —— 这才是形变边缘白线的直接成因 | 正确性 bug | 写回前把 area 内 alpha=0 的目标像素补回 src 原内容 ([`seedTransparentFromSource`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:169)): thread_local 复用缓冲、4 字节/像素守卫。**已映射像素的结果逐像素不变** |
| 并行 lambda 里 `m_liquifyTargets[i]` 走非 const `operator[]`, 多线程并发会触发 detach 检查 = 数据竞争 | 并发缺陷 | 改为预先取出裸指针数组; 并行改用引擎**专用 `QThreadPool`**(不借全局池, 避免与 Krita 内部并行争池), 并留 `LIQUIFY_PARALLEL_TARGETS` 开关一键回退串行 |
| `m_liquifyPendingDelta` 一直 `united()` 会退化成"整条轨迹的包围盒"(快速斜向拖动尤甚), 写回与投影合成面积随拖动长度增长 | 性能缺陷 | 新 dab 与已累积区域脱开时先 flush; build-up 语义下提前写回与最终结果逐像素一致 |

Kotlin 侧同轮修正:

| 发现 | 处理 |
|---|---|
| 脏区 -> 屏幕矩形只外扩 1px, 但画布位图走 GPU 双线性采样: 脏区**之外**的源像素影响范围随放大倍数增长 (8 倍放大时边上会留 8 像素宽旧像素) | [`bitmapRectToScreenBounds`](../app/src/main/java/com/reverie/paint/model/CanvasViewTransform.kt:150) 改为先按 `1 + 1/scale` 在位图空间外扩再映射, 屏幕空间再外扩 1px; 补用例锁死"外扩随缩放增长" |
| 回放/动画播放按帧整体切换画面, 引擎回报的脏区不代表全部变化 | 已排除在局部失效之外 (`Page.REPLAY` / `anim.isPlaying`) |

仍未修复 (均需真机量测后再决定):

- **单图层大笔刷**: `run()` 仍对整个 worker bounds 重算 (成本 ∝ (1.9×笔刷直径)²)。根治要按 tile 增量重算, 属 Krita 内部实现, 风险高于当前收益
- **rebase 频率**: `R = max(192, 1.9·size)` 与 `margin = max(40, 0.7·size)` 直接决定形变裁切质量, 未盲调; 若大笔刷下仍见偶发卡顿, 建议做"增大 R 同时增大 margin"的真机 A/B
- **液化与 Krita 后台投影同时写**: 液化期间走 `m_drawing` 分支绕过调度器 (笔画路径一直如此), 两边写入的是同一份结果, 但值得真机确认无撕裂

## 5.7 C++ 改动的处置 (2026-09-25)

上游已有 PR 修复液化工具的问题，因此本仓库的 C++ 改动**全部撤回**，只保留 Kotlin 侧优化。
撤回的部分（液化白线补齐、跨图层并行 warp、pendingDelta flush、缩放 scratch 复用、
`RPC_TRACE` 按需日志、`m_liquifyTxnActive` 同步合成分支）已存成
[`cpp-render-optimizations.patch`](cpp-render-optimizations.patch:1)，需要时 `git apply` 取回，
或作为对照上游 PR 实现的参考。

撤回的两个理由：① 避免与上游 PR 冲突；② 本机没有 Qt for Android + Krita 源码，无法编译
验证，在仓库里留一份"未经编译的引擎改动"本身就是隐患（源码与 prebuilt `.so` 不一致）。

> 2026-09-25 更新: ② 的阻塞已解除（[`docs/BUILD-ANDROID-NATIVE.md`](../docs/BUILD-ANDROID-NATIVE.md:1)），
> 且上游 PR 已修掉液化白线/断线/选区/Alpha 锁等问题。C++ 改动以 **§5.12** 的形式重新落地
> （保存并行编码、液化多目标并行、缩放路径零分配、热路径日志按需），全部经交叉编译 + ABI 比对验证。

## 5.8 第三轮: Kotlin 热路径零分配 (2026-09-25)

| 位置 | 改动 |
|---|---|
| [`CanvasTouchView`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:347) 对称绘制 | 镜像笔迹采样由 `MutableList<SymStrokeSample>`（每采样点一个对象 + 列表扩容）改为扁平 `FloatArray` + 计数（`[x, y, pressure]` 三元组，容量按需翻倍）；绘制、回放、失效判定三条路径全部零分配 |
| [`CanvasOverlay`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasOverlay.kt:87) 变换预览 | 网格（DISTORT）模式每帧原本要 new 9 组 `floatArray`/`Matrix`/`Path`/`Rect`/`RectF` 再加 1 个 `Paint`（约 50+ 对象）；透视模式同样每帧 new `Matrix`/`Paint`/两个矩形。改为 `remember` 复用同一组缓冲，拖动变换框期间零分配 |

等价性说明：镜像缓冲只是把"每点的对象"换成"每点的 3 个 float"，绘制与回放读到的坐标、
压力完全一致（压力由 `Double` 存为 `Float`，量级 0~1，误差远小于可见阈值）；变换预览的
`Matrix`/`Path`/`Rect` 复用前都显式 `reset()`/`set()`，语义与原"每帧新建"逐像素一致。

## 5.9 保存路径优化 (2026-09-25, 回应"保存项目要花大量时间")

先厘清耗时构成: 保存主体是 `ReverieCoreBridge.saveRevp()` —— C++ 侧把所有图层
写成 KRA 结构再 zip 压缩, 这段必须在引擎线程执行、且属于 C++ 域, Kotlin 侧改不了。
Kotlin 侧能做的是**去掉旁路开销与卡顿**, 本次清掉三处:

| 问题 | 处理 |
|---|---|
| [`PaintRecorder.serialize()`](../app/src/main/java/com/reverie/paint/core/PaintRecorder.kt:185) 把事件流**整份拷贝四次** (used → merged → out → copyOf), 长时绘画的录制流几十 MB, 等于每次保存白付 3 次全量复制 + 2 个大缓冲的 GC 峰值 | 改为按精确容量一次性分配、顺序写入, 容量算准后直接交出内部数组 (零拷贝); 只保留"锁内 used 快照"这一份必要拷贝 |
| 保存完成后 [`refreshProjects()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelProjects.kt:527) 在**主线程**同步扫描目录并解析 .revp 元数据 —— 刚保存的文件 mtime 变了、元数据缓存必然失效, 于是每次保存都要在主线程重解析一遍刚写出的 .revp (大画布几十 MB 的 ZIP), 工程一多就明显卡顿 | 拆成 `refreshProjects()`(协程 + `Dispatchers.IO` 解析, 结果回主线程赋值) 与 `buildProjectList()`(纯 IO); 刷新期间保留旧列表不置空, 数据就绪一次性替换; 并去掉并发重复刷新 (旧 job 取消) |
| 每次 `serialize()` 都 `snapshotFiles.readBytes()` —— 同一个会话内快照内容根本不变, 自动保存却每隔几分钟就重读几十 MB | 新增快照字节缓存 (键 = 绝对路径 + mtime), 命中即零 IO 零分配; `endSession()` 与会话切换时清理, 并接入内存压力释放 ([`releaseReclaimableCaches`](../app/src/main/java/com/reverie/paint/core/PaintViewModelMemory.kt:78) 调 `dropSnapshotCache()`) |

**仍然慢的话, 剩下的大头在 C++ 域**, 需要引擎侧改动才能继续压: ① zip 压缩级别
(现在是 zlib 默认级别, 大画布多图层时压缩本身占大头); ② 图层图像的增量写入
(只写出变化过的图层而不是整份重写)。这两项都要 buildNative 验证, 不在 Kotlin 侧范围。

## 5.10 启动路径优化 (2026-09-25)

| 问题 | 处理 |
|---|---|
| [`loadBrushPresets()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelProjects.kt:1007) 在调用线程 (主线程, 来自 MainActivity 的 `LaunchedEffect`) 同步把 assets 里**几百个笔刷预设 + 笔刷资源 (.gbr/.gih/.png/.svg, 可达几十 MB)** 拷进 `filesDir` | 拆成 `copyBundledBrushAssets()`(纯 IO) + `loadBrushPresetsAfterAssets()`(主线程写状态 + 渲染线程读 JNI), 外层用 `viewModelScope.launch { withContext(IO) { copy } }` 串起来。首启/清数据后的主线程卡顿消除 |

顺带说明: `refreshProjects()`(§5.9) 也已异步化, 启动链上不再有"扫目录 + 解压 ZIP"这类重活跑在主线程。

## 5.11 Kotlin/Android 侧待办清单 (2026-09-25 扫描结果)

按"确定性高 → 需实测"排序, 全部属于 Kotlin 侧, 不碰 C++/Krita。

**A. 主线程有界阻塞**

| 项 | 说明 |
|---|---|
| [`contentBounds()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt:685) | 引擎忙时主线程最多阻塞 **500ms** 等渲染线程回值。调用方已有 null 回退(变换框退化为整幅), 可把超时收到 ~120ms; 更彻底是改成回调/挂起函数 |
| [`previewLassoSync()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt:1273) | 60ms 有界等待, 影响小, 可保留 |

**B. 图片解码**

| 项 | 说明 |
|---|---|
| 画廊缩略图 [`HomePage`](../app/src/main/java/com/reverie/paint/ui/home/HomePage.kt:97) | 已走 `ThumbnailCache`(IO), 但 `decodeFile` 未设 `inSampleSize`; 若缩略图偏大建议统一降采样 + `RGB_565` |
| 赞助/贡献者头像 | 已在 IO 解码, 同样建议降采样(列表头像不需要原尺寸) |
| 调色板图片 [`ColorPalettesPage`](../app/src/main/java/com/reverie/paint/ui/painting/panels/ColorPalettesPage.kt:74) | 用户导入的调色板图直接全尺寸解码, 建议加上界(如 ≤2048) |

**C. 内存预算**

| 项 | 说明 |
|---|---|
| 参考图列表 | 最多 50 张**原尺寸** Bitmap 常驻, 大图时可达数百 MB。可照 [`FrameCachePolicy`](../app/src/main/java/com/reverie/paint/model/FrameCachePolicy.kt:1) 加字节预算 + 降采样 |
| 相册/笔刷缩略图缓存 | 已有 LRU(`BrushThumbCache`), 但未见字节上限; 建议补预算并在 `TRIM_MEMORY` 时收缩 |

**D. Compose 重组(需实测定位)**

- 各 `LazyColumn`/`LazyVerticalGrid` 是否都带稳定 `key`(无 key 时数据变化整列重建、滚动位置易丢)
- 大 Composable(如 `PaintingPage`)是否把高频状态读进了组合作用域(应保持"读在绘制/lambda 内")
- 动画是否用 lambda 版本以减少重组

**E. 绘制热路径(与已完成的 CanvasOverlay 同类)**

- 套索采样点 `lassoPoints` 仍是 `MutableList<Offset>`(每点装箱 + 预览每帧构造 List), 可照镜像笔迹改成扁平缓冲
- 时间轴/色板/曲线图等 Compose 自绘 Canvas 是否有每帧新建 `Path`/`Brush`/`RectF`

**F. 端侧省电(Android 11+)**

- 静止无输入时主动 `surface.setFrameRate(...)` 降频(已有"落笔自愈恢复最高刷新率", 可补静止降频)

## 5.12 第四轮: C++ 侧落地 (2026-09-25, 交叉编译环境打通后)

前提: [`docs/BUILD-ANDROID-NATIVE.md`](../docs/BUILD-ANDROID-NATIVE.md:1) 记录的 WSL 交叉编译环境
已可用, §5.7 里"本机无法编译验证"的阻塞消失。本轮全部改动**编译通过**(`scripts/build_native_wsl.sh`),
NEEDED 依赖闭包与改动前逐条一致, JNI 导出符号**零增删**(新增的只是 QtConcurrent 模板实例等内部符号)。

### 5.12.1 保存加速: 并行 PNG 编码 + 去掉容器二次压缩

`.revp` 的保存耗时几乎全在 [`writeRevpStore()`](../app/src/main/cpp/ReverieCoreIO.cpp:329): 每个图层/
关键帧/预览各编码一张 PNG, 再塞进 zip。原先的写法有两个纯浪费:

| 问题 | 处理 |
|---|---|
| N 个图层/关键帧的 PNG **串行**编码, 大画布多图层时是秒级墙钟 | 新增 [`writeRevpPngJobs()`](../app/src/main/cpp/ReverieCoreIO.cpp:266): 条目先汇总成一张表, 用引擎专用池(上限 4 线程, [`reverieBackgroundPool()`](../app/src/main/cpp/ReverieCoreDocument.cpp:16))并行编码, 再按**原顺序**串行写 zip。按块编码(块 = 线程数), 峰值内存 ≈ 线程数 × 单图 PNG, 不是"整份工程 PNG 总和" |
| PNG 本身已是 deflate 流, zip 再按 zlib 默认级别压一遍, 体积几乎不变却白烧 CPU | 写 PNG 条目时 `store->setCompressionEnabled(false)`(KoQuaZipStore 的 level 是**每次 open 时读取**, 因此可逐条目切换); meta / layers.xml / 资产 / 录制流仍走正常压缩 |

### 5.12.2 PNG 档位: 用实测换 2.6~4.4 倍编码速度

Qt 的 `quality` 参数对 PNG 的真实语义此前无人量过, 因此先写了一个宿主基准
([`scripts/native-bench/`](../scripts/native-bench/png_compression_bench.cpp:1), 用 ICU 56 桩库绕开
Qt 官方 linux 二进制在新发行版上的缺失依赖), 测三类典型图层内容 (2048²):

| 内容 | Qt 默认 (`quality=-1`) | `quality=70` | 结论 |
|---|---|---|---|
| 线稿 (透明底 + 抗锯齿笔迹) | 393 ms / 3.93 MB | **149 ms / 4.22 MB** | 快 2.6x, 体积 +7% |
| 铺色 (渐变 + 实心形状) | 72 ms / 202 KB | **51 ms / 456 KB** | 快 1.4x, 绝对增量仅 +254 KB |
| 厚涂/噪点 | 1032 ms / 10.6 MB | **235 ms / 9.0 MB** | 快 4.4x, **体积反而小 15%** |

即默认档恰是最慢的一档; 70 档在三类内容上"时间大赢、体积基本打平"。因此
[`kRevpPngQualityDefault = 70`](../app/src/main/cpp/ReverieCoreIO.cpp:203), 上界压在 89
(≥90 时 Qt 直接写不压缩的 PNG, 体积暴涨)。真机可 A/B: `setprop debug.reverie.pngq <1..89>`。

**兼容性**: PNG 是无损格式, 档位只影响压缩率与体积, 不影响像素; `.revp` 仍是同一个 zip 容器、
同一批条目名与顺序, 旧版本打开新文件读到的像素与改动前**逐字节一致**。KRA 路径
(`saveKra` → `kis_store_paintdevice_writer`)**未做任何改动**, 与 Krita 的格式兼容性不受影响。

### 5.12.3 液化: 多目标 warp 并行 (真机反馈的剩余瓶颈)

§5.5.1 提到的"多图层液化"原先仍是**逐层串行**跑 `KisLiquifyTransformWorker::run()`, 单次 apply
的墙钟随图层数线性增长。本轮拆成两阶段([`liquifyApplyLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:160)):

- **阶段 1 (并行)**: 各目标的 `dst->clear()` + `run(src, dst)` 丢进引擎专用池。目标之间完全独立
  (各自的 src/dst/worker), 并行期间只操作预先取出的裸指针数组, 不触碰 `m_liquifyTargets` 容器
  (QVector 非 const `operator[]` 带 detach 检查, 并发即数据竞争 —— §5.5.1 记过的坑)。
- **阶段 2 (串行)**: 写回、`setChannelFlags`、`setDirty`、脏区合并全部保持原样, 在渲染线程执行。

写回逻辑一个字没改 ⇒ 像素结果与改动前**逐像素一致**(选区约束、Alpha 锁、透明像素语义都不变)。
开关 [`kLiquifyParallelTargets`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:141) 可一键回退串行对照。

### 5.12.4 缩略路径零分配 + 热路径日志改为按需

| 项 | 改动 |
|---|---|
| 缩放视图的脏区 blit | 原先每帧 `new QImage(rs)` 再把 swizzle 结果拷进去; 现在直接以复用的 `m_subRegionBuffer` 作 QImage 后端并**就地** BGRA→RGBA(1:1 路径一直这么做, in-place 安全)。每帧少一次堆分配 + 一次整块拷贝 |
| 填充日志 | `floodFillAt` 原先每次填充写 2 条 logcat **并顺带调用两次 `exactBounds()`**(全瓦片扫描); 现已并入 `RPC_TRACE`(默认关闭, `setprop debug.reverie.trace 1` 打开), 关掉时连参数求值都不发生 |
| 液化 apply 日志 | 从无条件 `RPC_LOG` 改走 `RPC_TRACE`: 该路径按 20~64ms 节流, 等于每秒数十条 logcat |

### 5.12.5 本轮验证结果

- `scripts/build_native_wsl.sh` 通过; 产物 strip 后 3,799,416 B(改动前 3,772,376 B)
- [`scripts/native-bench/verify_abi.sh`](../scripts/native-bench/verify_abi.sh:1): NEEDED 闭包**完全一致**;
  导出符号仅有新增(QtConcurrent 模板实例、`QList<LiquifyTarget*>` 辅助函数等内部符号),
  `Java_com_reverie_paint_core_*` 入口**无增删**
- `:app:compileDebugKotlin` / `:app:testDebugUnitTest` / `:app:assembleDebug` 均通过,
  APK 内 `lib/arm64-v8a/libreverie_jni.so` = 3,799,416 B(确认打进的是新库)

### 5.12.6 仍未做 (按预期收益排序)

1. **动画关键帧的 `convertToQImage` 仍串行**(在写盘线程里逐个设备转换)。帧数多时它可能比
   PNG 编码还贵; 并行化的前提是确认不同 `KisPaintDevice` 上的并发色彩转换安全 (Krita 的颜色
   转换缓存有锁, 但这条没有实测数据支撑), 故本轮不动。
2. **手动保存仍占用渲染线程**: [`saveProject`](../app/src/main/java/com/reverie/paint/core/PaintViewModelProjects.kt:51)
   走同步 `saveRevp`(快照 + 编码 + 写 zip 全在 `reverie-render` 线程)。本轮已把编码时长压下来,
   若要彻底释放渲染线程, 需改成"异步保存 + 轮询完成"(要新增一个 JNI 查询), 属 Kotlin 侧改动。
3. **视口尺寸渲染缓冲**(§5 第 1 条, 仍是最大单点收益): 需要 Kotlin 画布的绘制/变换一起改,
   不是 C++ 单侧能完成的。

## 5.13 第五轮: 流式保存管线 (大型项目保存的真正瓶颈)

真机反馈: "大型项目保存 .revp 速度没有明显提升"。回去逐行读 Krita 源码后确认 §5.12 改的地方
不是大项目的主要成本 —— 真正的成本是**每次保存都为每个图层造一张整幅 QImage, 并把它们全部
压在内存里**:

| 事实 (源码依据) | 代价 (4096² 画幅, 单图层) |
|---|---|
| [`KisPaintDevice::convertToQImage()`](../reverie-deps/krita-source/libs/image/kis_paint_device.cc:1651): `new quint8[w*h*pixelSize]` → `readBytes` → `convertPixelsTo` | 一次 64MB 临时缓冲分配 + 两遍整幅内存流量 |
| 保存代码在其后又 `.copy()` 一次 (异步路径) | 再一个 64MB 分配 + 两遍流量, 而 `convertToQImage` 返回的**本就是独立新图** |
| 所有图层的 QImage 同时常驻 (`QVector<QPair<int,QImage>>`) | 15 图层 = 960MB 常驻, 编码期再叠加 PNG 字节 |

15 图层的大项目, 光"造图 + 拷贝"就有约 4GB 内存流量与约 2GB 新页分配, PNG 编码只占小头 ——
这正是"调了 PNG 档位与并行度却感觉不出提升"的原因。

本轮把保存改成**流式管线** ([`RevpPngJob`](../app/src/main/cpp/ReverieCoreIO.cpp:222) /
[`writeRevpPngJobs()`](../app/src/main/cpp/ReverieCoreIO.cpp:303)):

| 项 | 做法 |
|---|---|
| 图层/关键帧不再物化整幅图 | 条目携带 `std::function<QByteArray(int)>` 工厂, 在工作线程"取图 → 编码" |
| 引擎线程只做瓦片级快照 | 图层用 `makeCloneFrom`(COW 指针拷贝, 微秒级), 关键帧用 `writeToDevice` 到独立设备。已核对 `prepareCloneImpl`: 它会一并继承 `defaultBounds` 与 `defaultPixel`, 因此**转换结果与直接转换原设备等价**, 背景层的默认像素不会丢 |
| 按块并行 + 逐块释放 | 块 = 线程数(≤4), 块内并行"转换+编码", 块间串行写 zip; 写完立刻释放该块的图与工厂 |
| 峰值内存 | ≈ min(线程数, 条目数) × (临时缓冲 + QImage + PNG), 与图层/关键帧总数解耦 |
| 已压缩资源的容器压缩 | mp4/mp3/jpg/png 等再让 zip deflate 一遍纯浪费, 改为直存 |
| 预览 / 缩略图 | 仅这一项仍整幅物化一次(缩略图要由它缩放), 两者共享同一份图 |

等价性: 条目名、条目顺序、PNG 档位与像素内容完全不变, 容器结构与 §5.12 一致。空图层(无瓦片)
的克隆是安全空操作([`KisTiledDataManager::bitBltImpl`](../reverie-deps/krita-source/libs/image/tiles3/kis_tiled_data_manager.cc:433)
首行即 `if (rect.isEmpty()) return;`), 转换结果仍是"默认像素铺满整幅"。

风险与回退: 异步保存时引擎线程仍在绘制, 图层快照与工作线程读的是同一批瓦片(COW)。这是 Krita
自身投影/撤销快照的机制, 也是本文件既有"关键帧拷到写盘线程再转换"的同一前提; 若真机出现异常,
把 [`makeSnapshotLayerJob()`](../app/src/main/cpp/ReverieCoreIO.cpp:370) 里的克隆换成
`dev->convertToQImage(...)`(退回引擎线程转换) 就回到 §5.12 的语义。

## 5.14 性能标尺 (2026-09-25, 为"要不要做 tile 化 / 动态分辨率"立标尺)

保存提速落地后, 决定后续投入的那几个数字一直没有出处: **纹理重传多少 MB/帧、渲染走的是哪条路径、
脏区占比多少、保存耗时的三段各占多少**。用户设备连不上 adb(logcat 拿不到), 只靠手感无法验收
"改了 5% 还是 50%"。所以先立一套**默认关闭**的量测设施:

| 层次 | 内容 | 位置 |
|---|---|---|
| 分桶统计 | 渲染路径 `full/incr/skip` 的次数与均耗时、缩放路径次数、**每帧纹理重传 MB**(翻转次数 × 缓冲字节; HWUI 不做局部纹理更新, 故这是真值而非估算)、**脏区占比**、`onDraw` p95 | [`PerfTrace`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt:24) |
| 保存阶段 | C++ 按阶段计时: 引擎线程快照 / 工作线程编码 / 写盘(zip + IO + 改名), 外加 PNG 条目数、PNG 字节、最终体积 | [`ReverieCoreIO.cpp`](../app/src/main/cpp/ReverieCoreIO.cpp:202) + JNI [`revpSaveStats`](../app/src/main/cpp/reverie_jni_io.cpp:91) |
| 呈现 | 每秒一行窗口摘要写 logcat(`ReveriePerf`); 画布左侧偏中叠加 3 行 HUD | [`PerfHud`](../app/src/debug/java/com/reverie/paint/perf/PerfHud.kt:1) |

**可见性按构建类型隔离**(按用户意见修订): 标尺是研发工具、不是绘画功能 —— 入口、HUD 与文案都放在
**debug 专属源集** ([`app/src/debug/...`](../app/src/debug/java/com/reverie/paint/perf/PerfHud.kt:1)),
正式版由 [`app/src/release/.../PerfHud.kt`](../app/src/release/java/com/reverie/paint/perf/PerfHud.kt:1)
的空实现顶上 —— 正式包里既没有设置入口, 也不含 HUD 绘制代码与字符串资源。
不用 `if (BuildConfig.DEBUG)` 的原因: release 目前 `isMinifyEnabled = false`, 常量分支不会被 R8
消除, 代码与文案仍会留在包里。

**开销**: 关闭时热路径只剩一次布尔判断; 开启时每次 render/draw 各一次 `@Synchronized` 记账(零分配,
p95 用固定环形缓冲 + 原地排序)。打开方式: debug 包 → 设置 → 通用 → 性能标尺;
或在任意包上 `setprop debug.reverie.perf 1`(那种情况下只有 logcat, 不显示 HUD)。

**怎么用这组数字做决定**:

- 脏比很小(几 %)而重传 MB/帧 很大 ⇒ 瓶颈就是"整张纹理重传", tile 化显示缓冲收益最大(§5 待办 1/4);
- 脏比接近 100% ⇒ 每次都在写整幅, 分块上传救不了, 先查为什么全量脏(`full` 占比为何高);
- 保存的"快照"段占比高 ⇒ 引擎线程仍被文档操作占着, 下一步该把快照也异步化或做增量(见 §5.13);
- "编码"段占比高 ⇒ PNG 仍是瓶颈(可继续调档位/并行度); "写盘"段占比高 ⇒ 是 IO / zip, 考虑直存或分卷。

## 5.16 第七轮: 液化白线 / 闪退 / 大笔刷性能 (真机迭代, 2026-09-25)

第 5~11 个对照包全部来自真机反馈驱动的迭代。**最终版本 = 第 11 个包** (用户确认性能满意)。

### 5.16.1 白线伪影 (上游 1.3.1 同样存在, 非本分支回归)

真机截图证实: 纯上游 1.3.1 的液化也会出现 1px 白色细线与环绕回写区的白色矩形轮廓。两条成因:

| 成因 | 修复 |
|---|---|
| `KisLiquifyTransformWorker::run()` 只覆盖它实际遍历到的瓦片, 其余保持 `clear()` 后的透明; 回写用 `COMPOSITE_COPY` 会把这些**假透明擦进图层** → 透明处露出白色画布底 | [`seedTransparentFromSource()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:170): 回写前把 `area` 内 `alpha==0` 的目标像素补回 `src` 原内容 (已映射像素逐像素不变, thread_local 缓冲) |
| 渲染路径读的是 Krita **异步投影**, 可能在重组合中途被读到 | 投影同步合成改由 [`liquifyApplyLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:243) 在写回后立即执行 (按 20~64ms 节流), 渲染路径保持"只有笔画走同步分支" |

> 同步合成最初放在渲染路径上 (`m_drawing \|\| m_liquifyTxnActive`), 白线确实修好了, 但每个
> 输入事件一次的渲染都承担一次大区域合成 → 大笔刷下直接吃满渲染线程。挪到 apply 内后语义
> 等价 (写回后立刻合成, 渲染时投影已是最新), 频率却降了一个数量级。

### 5.16.2 闪退 (三个独立成因, 全部修掉)

| 成因 | 说明与修复 |
|---|---|
| 累积脏区无界 | 单 dab 的影响半径 `infl = 3.2×size+8` (200px → ~1300² ≈ 1.7M px); 原先每 dab 都 `united()`, 快速拖动会退化成整条轨迹的包围盒。现在设**面积预算**(默认 768K px, 256K~2M 自适应) + 新 dab 与已累积区脱开即 flush, 且预算至少容 2 个 dab (否则退化成"每 dab 都 apply")。build-up 语义下提前回写逐像素等价 |
| **网格精度越界** | Krita 的 worker 要求 `pixelPrecision ∈ {1,2,4,8,16}` 或 16 的倍数 (源码注释 "should check if pixelPrecision is a power of 2")。上游 `qBound(4, size/8, 16)` 对 68px 会算出 **9**、78px 会算出 **10** —— 都是非法值 (真机 68px 必崩)。现在吸附到合法档 `{4,8,16,32}` |
| 节流上限放宽加剧网格退化 | 试过把 apply 间隔上限 64ms → 120ms, 会让一次 apply 前累积更多 dab、网格形变更剧烈 → 更易走 Krita 内部退化路径 (68px 崩)。已回退到 64ms |

### 5.16.3 大笔刷性能 (200px/200% 从趋势性恶化到可用)

- **真瓶颈是网格单元数, 不是像素数**: `run()` 每个单元要做一次多边形裁剪填充 + 瓦片读写。
  760² 的 bounds 在精度 16 时是 47×47 = **2209 个单元**, 精度 32 时 24×24 = **576** ⇒ 约 4× 提速。
  `32` 是合法值 (16 的倍数), 且 380px 半径上仍有 12 单元/半径, 高斯形状照旧解析得住。
- 去掉**重复的 `dst->clear()`** (run() 内部已清), 每次 apply 每目标少清一整块 bounds。
- 曾试过压小影响半径 (1.9σ → 1.6σ) 省面积, 但 bounds 变小后强位移更频繁地需要 bounds 外像素、
  网格更易退化, 真机上反而不稳, 已回退。

### 5.16.4 度量口径修正

PerfTrace HUD 的 `flip x/s` 新增 **500ms 窗口门槛** —— 否则"窗口刚重置 + 1 次翻转"会被折算成
140/s 这种不可能读数 (真机见过, 会误导判断)。窗口不足时显示 `--`。

### 5.16.5 真机结论与遗留

- 最终版 (#11): 白线消失, 68/78/200px 均不崩, 200px/200% 快速拖动性能被用户接受
- 仍然存在、但需要谨慎立项的后续 (未做):
  1. **缓存"除被液化层以外的合成结果"** (gesture 开始时算一次, apply 时只叠被液化层) → N 层合成降为 1 层;
     需正确处理混合模式/裁剪/图层组/图层样式并逐项回归
  2. **按 tile 增量重算** (只重算真正变化的格子) → 需碰 Krita 内部 API
- 教训 (写进流程): 一次只改一个变量; 改动若要越过上游契约 (如网格精度集合), 必须先查上游断言

## 5.15 上游 1.3.1 合并记录 (2026-09-25)

`upstream/main` (a2daae4, 发布 1.3.1) 已合并进 `fix/performance`。上游这一版的核心是
**笔刷系统**（Krita `.bundle` 打包导出 KppHelper/KritaBundleManager、参数回跳修复、喷枪点频）、
**图层面板对齐 Procreate**（多选批量拖拽、图层组嵌套、全局浮层动效）、画廊多选底栏、
非 4 对齐画布选区斜切修复，以及 **`9d2681f` 保存优化**（多核并行 PNG + 去掉 ZIP 二次压缩）。

### 冲突判定 (3 处)

| 冲突 | 判定 |
|---|---|
| `ReverieCoreIO.cpp` | 上游 9d2681f 与本分支做的是同一件事，但本分支是**超集**：流式管线（图层/关键帧 COW 快照 + 按块并行 + 逐块释放，峰值内存与条目数解耦）、PNG 档位按实测定为 70、已压缩条目跳过 deflate、保存阶段耗时统计 ⇒ **取本分支版本** |
| `CanvasTouchView.kt` | 上游删除了笔迹预测整条链路（189 行）与其引起的指示圆伪影；本分支用"渐变淡出"解决同一伪影，并额外带脏区局部失效/像素网格按视口裁剪/镜像笔迹零分配 ⇒ **取本分支版本**（上游那 2 行光标重绘条件依赖已删的预测状态，不适用） |
| `third_party/.../libreverie_jni.so` | 二进制冲突：按**合并后的源码**重新交叉编译（3,821,360 B）并随合并提交更新，避免源码与预编译库不一致 |

其余（`ReverieCore.h`/`ReverieCoreInternal.h`/`PaintViewModel*.kt`/`ReverieCoreBridge.kt`/`strings.xml` 等）
git 自动合并成功，无残留冲突标记。

### 验证

- `scripts/build_native_wsl.sh`：合并后源码全量重编通过
- [`verify_abi.sh`](../scripts/native-bench/verify_abi.sh:1) 相对合并前基线 (4ce4396)：**仅新增 8 个 JNI 入口**
  （`moveLayersRelative`/`moveLayersToGroup`/`layerId`/`setBrushJitter`… 均为上游新接口），
  无删除；NEEDED 闭包一致。本分支的 `revpSaveStats` 与它们共存
- `:app:compileDebugKotlin` / `:app:compileReleaseKotlin` / `:app:testDebugUnitTest`（含上游新增的
  `KppHelperTest`）/ `:app:assembleDebug` / `:app:assembleRelease` 全部通过
- debug APK 内 `libreverie_jni.so` 与 `third_party/` 产物 sha256 一致 (`4c905d62…`)

### 与上游的已知分歧 (如后续要跟随上游, 可在此对照)

1. **保存路径**：上游把全部图层整幅图与全部 PNG 字节一次性驻留再并行编码；本分支按块流式处理。
   语义等价（同样的 PNG 档位 70、同样禁用二次 deflate），但大项目峰值内存差一个数量级。
2. **笔迹预测伪影**：上游选择"删除预测矢量假线"；本分支保留预测并把假线终点渐隐到 0 透明度。
   两者都消除"指示圆里的黑色半圆杂点"，取舍不同（本分支保留低延迟预览的引导线）。

液化专项 (第七轮结果):

- [ ] 真机: 78px / 90% 推拉, 形变区无白色细线与白色矩形轮廓 (修复①)
- [ ] 真机: 68px / 78px / 200px 连续拖动均不闪退 (修复②)
- [ ] 真机: 200px / 200% 快速长拖、来回拉锯、多图层 — 性能可接受且无趋势性恶化
- [ ] 真机: 大笔刷形变细节可接受 (精度 32 的粗化代价)
- [ ] 真机: 多图层液化结果与单图层一致, 选区/Alpha 锁约束仍生效 (上游 PR #5/#7 语义)
- [ ] 真机: 液化抬笔后画面与图层最终一致 (节流 flush + 投影同步合成)

## 6. 回归自检清单

- [x] `:app:compileDebugKotlin` 通过 (首轨全部 Kotlin 改动)
- [x] `:app:testDebugUnitTest` 覆盖 `CanvasViewTransform` 的坐标往返与包围盒
- [ ] 真机: 平移/缩放/旋转画布后落笔, 笔画位置与光标环不错位
- [ ] 真机: 4x 以上放大开启像素网格, 网格线与像素边界对齐
- [ ] 真机: 关闭光标、无预测笔迹时连续运笔, 画布无边缘残影 (局部失效路径)
- [ ] 真机: 旋转画布后落笔与吸色仍落在指针位置 (变换公式与旧路径等价)
- [ ] 真机: 回放页播放与逐帧动画播放画面完整, 无上一帧残影 (该路径已强制整屏重绘)
- [ ] 真机 (第二轨): `build_native.sh` 后缩放到 <100% 绘制无残影、无闪屏
- [ ] 真机: 长动画播放不触发 OOM, 大画幅下帧缓存被限制在预算内 (dumpsys meminfo)
- [ ] 真机: 面板钉住时连续绘制, 笔迹不掉段 (缩略图已让路)
- [ ] 真机: 开启对称绘制 (径向/四象限) 连续运笔, 镜像笔迹与主笔迹一致、无缺段
- [ ] 真机: 自由变换 / 透视 / 网格变形拖动时画面跟手、无闪烁
- [ ] 真机: 保存工程后界面立即可用 (不再因刷新列表卡住), 画廊列表稍后自动更新
- [ ] 真机: 保存后重新打开工程, 回放完整 (录制事件与初始快照都没丢)
- [ ] 真机: 连续多次自动保存, 内存不持续增长 (快照缓存已被预算与内存压力约束)
- [ ] 真机: 清除应用数据后首次启动, 主线程不卡 (笔刷资产拷贝已移出主线程), 笔刷列表正常加载
- [ ] 真机 (第二轨): 液化形变边缘**无白色线条** (含正片叠底等多图层场景), 与单图层结果一致
- [ ] 真机 (第二轨): 多图层液化墙钟时间下降; 若出现异常, 把 `LIQUIFY_PARALLEL_TARGETS` 置 false 回退串行再验
- [ ] 真机 (第二轨): 快速斜向拉伸液化不卡顿、画面不撕裂, 形变结果与拖动方向一致

第四轮 (C++ 已编译验证, 需真机回归):

- [ ] 真机: 多图层选中后液化, 墙钟时间较改动前明显下降; 逐像素结果与单次串行一致
      (异常时把 [`kLiquifyParallelTargets`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:141) 置 false 对照)
- [ ] 真机: 大画布多图层**手动保存**, 耗时明显下降; 保存的文件能被本机旧版本 APK 打开且画面一致
- [ ] 真机: 保存后重新打开工程, 图层/关键帧/预览/录制回放全部正常 (并行编码没打乱条目顺序)
- [ ] 真机: `.revp` 体积与改动前对比无明显膨胀 (档位 70; 想复现旧档的耗时/体积可
      `setprop debug.reverie.pngq 30` 对照)
- [ ] 真机: 用 `setprop debug.reverie.trace 0` 确认填充/液化不再刷 logcat, 置 1 后日志恢复
- [ ] 真机: 画布缩放到 <100% 连续绘制无残影/无错色 (就地 swizzle 路径)
- [ ] 真机: 导出 KRA 后能被 Krita 桌面版打开 (该路径未改动, 属回归确认)

第五轮 (流式保存管线):

- [ ] 真机: **大项目 (多图层/大画布/带动画关键帧) 保存耗时明显下降**, 保存期间内存占用
      (dumpsys meminfo) 不再随图层数暴涨
- [ ] 真机: 保存后逐层对比 —— 每个图层/关键帧的像素与改前一致 (含**背景层默认像素**、
      空图层、透明图层), 混合模式与不透明度不变
- [ ] 真机: 自动保存(后台)期间继续绘画, 不卡顿、不出现半更新的图层内容
- [ ] 真机: 带动画的项目保存后逐帧回放, 关键帧画面完整无丢失
- [ ] 真机: 带音频/视频资源的项目保存后资源可正常播放 (容器压缩改直存)

性能标尺 (调试项, 用 debug 包):

- [ ] 真机: 设置 → 通用 → 诊断 → 性能标尺 打开后, 画布左侧出现 3 行实时数据; 关闭后消失
- [ ] 真机: 正式版 (`assembleRelease`) 设置页**没有**"诊断/性能标尺"入口, APK 内不含相关字符串资源
- [ ] 真机: 标尺关闭时画布操作手感与开启前一致 (记账不在热路径上留开销)
- [ ] 真机 (取数): 大画布连续绘制一段, 记录 HUD 的 `脏比 / 重传MB/帧 / draw p95` 与 logcat 窗口行
- [ ] 真机 (取数): 保存一次大项目, 记录 HUD 的 `save 总/快照/编码/写盘` 与 `PNG→体积`
