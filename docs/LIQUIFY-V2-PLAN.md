# 液化 V2 改造清单(文件级)

> 面向"把现有 Liquify 对齐到 *Snapshot + Operation Log + 局部 GPU Warp + Dirty Tile* 架构"的落地文档。
> 与 [`docs/RENDER-OPTIMIZATION.md`](RENDER-OPTIMIZATION.md) §4 / §9.1 配套;冲突时以代码与
> RENDER-OPTIMIZATION.md 的实测结论为准。
> 行号锚点以本文写下时的源码为准,代码改动后请同步。

## 0. 一句话结论

提案里的三个核心问题,在 ReveriePaint **只有两个仍然成立**:

| 提案中的问题 | 在本仓库是否成立 | 依据 |
|---|---|---|
| 每帧处理整张画布 | **成立**(仅限交互态预览的"整屏重绘",文档态已是脏区增量) | §2.3、§2.4 |
| 反复从上一帧结果采样导致模糊 | **不成立**(权威路径每个 dab 都从未形变 src 重算) | [`ReverieCoreMiscTools.cpp:116`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:116) |
| CPU↔GPU 同步进入笔刷热路径 | **部分成立**(交互期反复 rebase 的全量物化 = 主导卡顿) | §4.16 真机 `物化 41/820ms max47ms` |

**因此本清单不是"把 GLES ping-pong 搬进来",而是把提案的分层语义映射到现有四个真实链路上**,
并且明确两条不可照搬的边界(§3)。

## 1. 现状:真实数据流(带源码锚点)

### 1.1 文档态(权威路径,决定最终像素与撤销)

```
触摸(touchDown)
   → PaintViewModel.liquifyBegin()                    core/PaintViewModelTools.kt:953
   → JNI liquifyBegin(layers)                          cpp/reverie_jni_tools.cpp:234
   → ReverieCore::liquifyBegin()                       cpp/ReverieCoreMiscTools.cpp:485   (开一条 KisTransaction/目标)

每个补点
   → PaintViewModel.liquify(fx,fy,tx,ty,mode,strength) core/PaintViewModelTools.kt:994
   → ReverieCore::liquify()                            cpp/ReverieCoreMiscTools.cpp:900
        ├─ 需要时 rebase: 重建 src 克隆 + worker + 网格精度   :922-993
        ├─ 网格增量: translatePoints/scalePoints/rotatePoints :1009-1034
        └─ 节流/预算触发 liquifyApplyLocked()                  :1074-1077
   → liquifyApplyLocked(): warp(可并行) → 补洞 → 回写 → 同步合成   cpp/ReverieCoreMiscTools.cpp:357

抬笔
   → PaintViewModel.liquifyEnd()                       core/PaintViewModelTools.kt:970
   → ReverieCore::liquifyEnd(): 全 bounds materialize + 合并成一条 undo   cpp/ReverieCoreMiscTools.cpp:531
```

要点:

- **真身是 Krita 的 `KisLiquifyTransformWorker` 网格**,它是 build-up 的:每个 dab 只往网格累加位移,
  `run()` 每次都从**未形变的 src 克隆**重新变换([`ReverieCoreMiscTools.cpp:397`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:397))。
  "不要反复从上一帧结果采样"这条**已经满足**,不要再为此引入第二套真身。
- worker 只覆盖笔刷邻域 `bounds`(`R = max(192, 1.9×size)`),笔刷移出内边距就 **rebase** ——
  这是"拖动中反复物化"的来源(§4.16)。

### 1.2 交互态预览(AGSL 覆盖层,不碰文档)

```
引擎线程(doRender 之前)                                core/PaintViewModel.kt:3087
   → pollLiquifyGpuPreview()                            core/PaintViewModel.kt:322
        ├─ liquifyPreviewSourceMeta() / SourcePixels()   JNI :339 / :353  (仅 rebase 时变)
        └─ liquifyGrid()                                  JNI :278         (每 dab 变)
   → LiquifyGpuPreview.update()  **只暂存**              core/LiquifyGpuPreview.kt:263
   → postInvalidate()

UI 线程(drawCanvas)
   → LiquifyGpuPreview.draw(canvas, vt)                  core/LiquifyGpuPreview.kt:413
        ├─ commitLocked(): 每帧最多一次纹理构建/上传      :289
        ├─ 源纹理 = rebase 时的未形变裁剪(可代理下采样)   :344
        └─ 位移纹理 = cols×rows 网格点(RGBA_F16)          :371
   → 一次 RuntimeShader 采样: dst(p) = src(p - offset(p))  :70-100
```

- **这是唯一"GPU 侧"的东西**,但它不是 FBO ping-pong:它是把一张 shader 画到
  `Canvas`(HWUI)上,**没有中间 FBO、没有 readback**。
- 采样核已经"从原始纹理取":`uSrc` 是未形变裁剪,`uGrid` 是累计位移 ⇒ 提案的
  "Original + Operations → Result"语义**在这里已经实现**,只是"Operations"被压成了一个位移场。
- 已知代价(§4.10):主机侧绘制时**引擎不写显示缓冲 ⇒ 没有脏区 ⇒ 每个 dab 触发一次整屏重绘**。
  这正是提案 §7/§25 要解决的"局部 Composite"。

### 1.3 调度(输入 → 提交的合并)

- [`LiquifyInteractionSession`](../app/src/main/java/com/reverie/paint/model/LiquifyInteractionSession.kt:31):
  latest-state-wins 纯状态机,只保留**最新目标位置**,每帧最多推进 `maxDabsPerFlush` 个补点。
  已经是"零分配 + 单一职责 + 可单测"的形态,提案的 `LiquifyStrokeCollector` / `PointerRingBuffer`
  在这一层**已存在**(输入缓冲就是 `MotionEvent` 的历史点遍历 + 原生类型字段计划)。
- 唯一 JNI 提交点:[`CanvasTouchView.liquifyFlushNow()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:2636),
  由 [`flushLiquifyPending()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:2667) 按帧调度。
- 补点几何/强度折算:[`LiquifyPath`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt:17)(含有单测)。

### 1.4 渲染/合成(脏区已是既有能力)

- 引擎侧脏区:[`m_dirtyRect`](../app/src/main/cpp/ReverieCore.h:876) + `markRegionDirty()`;渲染路径按脏区增量
  ([`ReverieCoreRender.cpp:183`](../app/src/main/cpp/ReverieCoreRender.cpp:183) / [:256](../app/src/main/cpp/ReverieCoreRender.cpp:256))。
- Kotlin 侧局部失效:[`invalidateFromRender()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:1074) +
  [`canPartialInvalidate()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:1107)(带"安全条件不满足一律整屏"的回退)。
- **缺口:AGSL 覆盖层不参与这套脏区** —— 它每帧重画裁剪区的屏幕包围盒,且 `postInvalidate()` 是整屏。

## 2. 提案 → 现状 → 落点(逐模块对照)

| 提案模块 | 现状对应物 | 差距 | 落点(本仓库文件) |
|---|---|---|---|
| `LiquifyController` | 分散在 `PaintViewModelTools.kt` + `LiquifyGpuPreview` + `CanvasTouchView` | 无统一会话编排 | **新增** `core/liquify/LiquifyController.kt`(聚合会话状态,内部转发既有 JNI) |
| `LiquifySession` | `LiquifyInteractionSession`(调度)+ 引擎 `m_liquifyTxnActive`(事务) | 会话边界分居两侧 | 保留 `LiquifyInteractionSession`;**新增** `LiquifySession.kt` 承载体/脏区/快照世代 |
| `LiquifyStrokeCollector` | `CanvasTouchView` 的 pointer 分支 + `MotionEvent` 历史点 | 已零分配 | **保留**(必要时抽 `LiquifyStrokeCollector.kt`,不改变调用方) |
| `PointerRingBuffer` | `MotionEvent#getHistoricalX/Y` + `LiquifyInteractionSession` 原生字段 | 已零分配;无独立环 | 保留;若真要独立环,**新增** `model/PointerRingBuffer.kt`(低优先) |
| `LiquifyOperation` / `OperationLog` | **无**(真身在网格,增量累加) | 语义级缺失 | **不新增为真身**;只作为**可选**的"预览位移重建源",见 §3.2 与 Phase 4 |
| `LiquifyRenderer` | 无;单次 `LiquifyGpuPreview.draw()` | 无 pass 分层 | **新增** `core/liquify/LiquifyRenderer.kt`(编排 pass),AGSL/GLES 二选一,见 §3.1 |
| `LiquifyRenderPass` / `WarpPass` | `SHADER_SRC` + `draw()` | 单 pass 打满 | **新增** `LiquifyWarpPass.kt` / `LiquifyCompositePass.kt` / `LiquifySnapshotPass.kt`(基于 AGSL `RuntimeShader`) |
| `LiquifyCopyPass` | `buildSourceBitmap()` 的下采样 | 只有代理下采样 | 同上,`LiquifyCopyPass.kt`(源裁剪 → 纹理) |
| `LiquifyTextureSet` | `srcBitmap` / `gridBitmap` / `cropKey` | 无 FBO,无 ping/pong | **新增** `LiquifyTextureSet.kt`(Bitmap + `BitmapShader` 生命周期封装;GLES 轨道再补 FBO) |
| `LiquifyPingPong` | 无(位移场是单纹理) | 语义上不需要 | **Phase 4 才评估**;若位移改用多 dab 批处理则退化为"单 pass 多 dab"而非 ping-pong |
| `LiquifySnapshot` | `liquifyPreviewCaptureLocked()` 的 `m_liquifyPreviewSrcRgba` | **已存在**(rebase 时一次) | 保留;仅补"按 tile 增量快照"能力(C++ 轨道) |
| `LiquifyDirtyRegion` | C++ `m_dirtyRect` / Kotlin `canPartialInvalidate` | 覆盖层未接入 | **已落地** [`model/LiquifyDirtyRegion.kt`](../app/src/main/java/com/reverie/paint/model/LiquifyDirtyRegion.kt)(前后两帧位移场差异 → 文档脏区)+ `CanvasTouchView` 局部失效 |
| `LiquifyShader` | `SHADER_SRC` 单段 | 无多 dab | **新增** `LiquifyShader.kt`;多 dab 见 Phase 4 |
| `LiquifyBrushKernel` | 引擎侧 `translatePoints/scalePoints/rotatePoints` | 内核在 C++,不在 shader | **Phase 4** 才考虑在 shader 复刻;当前以网格为内核源 |
| `LiquifyRenderCommand` | 无 | 无命令对象 | **Phase 5**(GLES 轨道才有意义) |
| `LiquifyProfiler` | `PerfTrace` + HUD 第 4/4.5/4.6 行 | 缺 input→submit→GPU 的分段 | **扩展** `core/PerfTrace.kt`(新增 `liquifyStage()`),见 Phase 3 |

## 3. 三条不可照搬的边界(必须先决策)

### 3.1 画布后端不是 GLES —— 提案的 FBO/`glViewport`/`glScissor` 无落点

现状:画布 = `View` + `Canvas`(HWUI)位图;GPU 只用 AGSL `RuntimeShader`(API 33+,
[`LiquifyGpuPreview.kt:11`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt:11))。
仓库里唯一的 EGL 代码在
[`Mp4VideoEncoder.kt:229`](../app/src/main/java/com/reverie/paint/core/export/Mp4VideoEncoder.kt:229)(离屏编码,与画布无关)。

- **含义**:提案 §17~§26 的 Pass/FBO/ping-pong/scissor 需要先引入 **SurfaceView + GLES 画布管线**,
  这是比液化本体大得多的一次改造(还要接管棋盘格/网格/光标/覆盖层)。
- **建议**:V2 先做 **AGSL 轨道**(等价语义,零新管线):Snapshot/Warp/Composite 三 pass 用
  `RuntimeShader` + `Bitmap` 表达;真正的 FBO 化留给 §9.1 Phase 5,且**必须先用数据证明**
  呈现层是瓶颈(当前反向证据:`draw p95 0.10ms`)。

### 3.2 "Operation Log 当真身"会与现有架构冲突

- 引擎每个 dab 是**对网格的增量累加**,Kotlin 侧刻意**不保存历史操作**
  ([`LiquifyInteractionSession.kt:19`](../app/src/main/java/com/reverie/paint/model/LiquifyInteractionSession.kt:19))。
- 把真身搬到 Kotlin 的 Operation Log,等于**放弃 Krita 的最终 materialize**(多边形填充语义/选区/
  Alpha 锁/多层合成,§4.1),抬笔精度与撤销语义都要重做 —— 风险高于收益。
- **结论**:Operation Log 只作为**交互态预览的可选重建源**(用于 Phase 4 的"局部化 + 批处理"),
  权威路径仍是网格 + `liquifyApplyLocked()`。

### 3.3 局部化要的是"tile 集合",现有基建是"单矩形"

C++ 已有脏区预算(`LIQUIFY_DELTA_BUDGET_*`, [`ReverieCoreMiscTools.cpp:145`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:145))
与累积矩形 `m_liquifyPendingDelta`,但都是**单矩形**。提案 §8~§11 的 tile 化与"dirty rect 落地反而更慢"
的坑一致:小 viewport + 状态切换开销可能倒挂。详见 §4.3。

## 4. 分阶段改造清单(按提交粒度)

> 铁律约束(AGENTS.md §4/§5):扩而不改、单向依赖 `ui/ → core/ → model/`、热路径零分配、
> 手势 `pointerInput` 不 key 在视口状态、每个阶段留回退开关。
> 机械验证:每阶段至少 `:app:compileDebugKotlin`(+ 纯逻辑 `:app:testDebugUnitTest`);
> 改 C++ 需 `-PbuildNative` + ABI 比对(本机工具链已具备,见 §6)。
>
> **落地顺序说明**:实际先做了 **Phase 2**(纯 Kotlin、且直击一处已确证的浪费 —— 每个 dab 整屏重绘),
> 再回头补 Phase 1 的 `input→submit` 打点(需要改触摸路径的时间戳传递)。Phase 3 仍是收益最大项。

### Phase 1 — 量测补全(无行为变更)

| 文件 | 动作 | 内容 |
|---|---|---|
| [`core/PerfTrace.kt`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt:387) | 修改 | 新增 `liquifyStage(inputToSubmitMs, submitToPresentMs)`;`draw p95` 已有,只需补"输入时间戳 → JNI 提交 → 显示"两段 |
| [`model/LiquifyInteractionSession.kt`](../app/src/main/java/com/reverie/paint/model/LiquifyInteractionSession.kt:31) | 保留 | 已记录 `lag/backlogDabs`;若需 p95 取输入时间戳,新增只读字段,不改现有语义 |
| [`ui/painting/canvas/CanvasTouchView.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:2636) | 修改 | 在唯一提交点 `liquifyFlushNow()` 打点(每帧一次,非每 dab) |
| `app/src/debug/java/.../perf/PerfHud.kt` | 修改 | HUD 追加第 4.7 行:`in→sub <ms> p95 / sub→present <ms> p95` |
| [`core/PaintViewModel.kt`](../app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:322) | 保留 | `pollLiquifyGpuPreview()` 已在渲染前取数 |

产出:回答"卡在 input→submit 还是 submit→present",决定 Phase 3/4 的优先级。

### Phase 2 — 覆盖层脏区(已落地 ★)

> 目标:AGSL 覆盖层不再整屏 `postInvalidate`,只失效"位移场变化区 ∪ 光标环前后位置"。
> 详细记录见 [RENDER-OPTIMIZATION.md §4.17](RENDER-OPTIMIZATION.md)。

| 文件 | 动作 | 内容 |
|---|---|---|
| [`model/LiquifyDirtyRegion.kt`](../app/src/main/java/com/reverie/paint/model/LiquifyDirtyRegion.kt) | **新增(已落地)** | 纯逻辑:由前后两帧 `liquifyGrid()` 的位移差异推出文档脏区(变化点的单元 ±1 格 = 保守超集);三态 `NO_CHANGE`/`CHANGED`/`INCOMPARABLE`;零分配 |
| [`app/src/test/.../LiquifyDirtyRegionTest.kt`](../app/src/test/java/com/reverie/paint/model/LiquifyDirtyRegionTest.kt) | **新增(已落地)** | 7 例:无变化 / 单点变化 / 多点并集 / 阈值内抖动 / null 与规模不符判不可比 / 截断数组不崩 |
| [`core/LiquifyGpuPreview.kt`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt) | 修改(已落地) | `update()`(引擎线程)算脏区,`@Volatile` 暴露 `overlayDirtyValid/Full/X/Y/W/H`;rebase 换源裁剪 ⇒ `INCOMPARABLE` ⇒ 整屏回退 |
| [`core/PaintViewModel.kt`](../app/src/main/java/com/reverie/paint/core/PaintViewModel.kt) | 修改(已落地) | `pollLiquifyGpuPreview()` 收尾由 `postInvalidate()` 改为 `onLiquifyPreviewUpdated()` |
| [`ui/painting/canvas/CanvasTouchView.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt) | 修改(已落地) | 新增 `onLiquifyPreviewUpdated()` / `scheduleLiquifyInvalidate()` / `canLiquifyPartialInvalidate()`;`onDraw` 记录光标环本帧屏幕包围盒,失效矩形并入环的前后位置 |
| `core/liquify/LiquifyTextureSet.kt` / `LiquifyWarpPass.kt` | **不做** | 原计划的"把 `LiquifyGpuPreview` 拆薄"**刻意放弃** —— 该文件不到 500 行、职责已单一,此时拆分属"为拆分而拆分"(AGENTS.md §5 最小 diff)。等功能继续长大再拆 |

回退开关:`debug.reverie.liquifyPreviewGpu`(关掉即整条 GPU 预览路径不存在)+ `partialInvalidateEnabled`,
再加安全门(旋转 / 像素网格 / 面板 / 多指 / 对称镜像)任一不满足即整屏。**生产默认不启用该路径。**

真机验证点(未验证):① 开开关推拉拖动, 环无残影、形变区边缘无"未更新的旧像素块";
② 与关闭开关时的最终结果逐像素一致(局部失效只改交互态呈现, 不改提交)。

### Phase 3 — 拖动物化消除(C++ 轨道,收益最大)

> 依据 §4.16:主导卡顿是 rebase 时的**全 bounds 物化**(`物化 41/820ms max47ms`)。
> **成因已调查定论**:见 [液化 Phase 3 调查](LIQUIFY-REBASE-INVESTIGATION.md)。
> 一句话结论:**rebase 是我们自己写的局部窗口重锚定,不在 Krita worker 里**;
> 触发条件只有"首个 dab / 笔尖离开锚点 240px(200px 笔刷)"两条,materialize 的唯一目的是
> 不让**尚未落盘**的网格位移丢失(新 worker 从 identity 开始)。
> ⇒ 正确方向是"**让重锚定不需要 materialize**"(把旧网格位移场平移到新网格),不是"让 materialize 更快"。

**Commit 1(本阶段第一步,已就绪待编译):只加埋点,不改行为**

| 文件 | 动作 | 内容 |
|---|---|---|
| [`ReverieCoreMiscTools.cpp`](../app/src/main/cpp/ReverieCoreMiscTools.cpp) | 修改 | rebase 分支 + `liquifyApplyLocked` 内新增 relaxed 原子量:`rebaseCount` / `rebaseReason`(FirstDab/LeftInnerBox) / `rebaseFlushMs` / `rebaseCloneMs` / 旧新 bounds 面积 / 触发偏移 / `gridPoints` / 节流 flush 的 count+ms |
| [`ReverieCore.h`](../app/src/main/cpp/ReverieCore.h) | 修改 | 新增 `void liquifyRebaseStats(qint64 *out)`(独立读取口,**不动** 既有 `liquifyStats` 的 10 元契约) |
| [`reverie_jni_tools.cpp`](../app/src/main/cpp/reverie_jni_tools.cpp) | 修改 | 新增 `liquifyRebaseStats()` 返回 `LongArray` |
| [`core/ReverieCoreBridge.kt`](../app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt) | 修改 | 新增 `external fun liquifyRebaseStats(): LongArray?`(纯新增,无签名变更) |
| [`core/PaintViewModel.kt`](../app/src/main/java/com/reverie/paint/core/PaintViewModel.kt) | 修改 | `pollLiquifyStats()` 顺带取一次 rebase 统计 |
| [`core/PerfTrace.kt`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt) | 修改 | 新增 `liquifyRebase(...)`;HUD 第 4.5 行追加 `rebase<n>/<ms> max<ms> 因<reason>` |
| `app/src/debug/java/.../perf/PerfHud.kt` | 修改 | 上述读数渲染(debug 专属,release 侧保持空实现) |

**Commit 2(埋点读数出来之后再定):路线 B+C**

| 路线 | 内容 | 前提 |
|---|---|---|
| C. 逻辑 rebase / 扩网格 | 需重锚定时构造**更大** worker,把旧网格位移场按双线性采样写进新网格;**不跑 `run()`、不落盘** | 待调查 §7 三条(尤其 `transformedPoints()` 是否可写) |
| B. 延迟 rebase | 交互期只置 `rebasePending`,永不 flush;抬笔一次 | **必须与 C 配对**,否则笔尖出窗口后新 dab 形变静默失效 |

风险:高(碰 Krita 网格生命周期)。必须"一键回退到当前 rebase 行为",并真机回归
"抬笔后与开关关闭时逐像素一致"(§11 液化清单)。

### Phase 4 — 局部化 + 操作批处理(AGSL 轨道)

> 依据 §4.14:先用代理分辨率旋钮判断瓶颈在"带宽"还是"覆盖面积"。
> 若是覆盖面积 → 做"局部 deformation 更新";若是带宽 → 做下述批处理。

| 文件 | 动作 | 内容 |
|---|---|---|
| `model/LiquifyOperation.kt` | **新增** | 纯 Kotlin 数据类(type/x/y/radius/strength/angle/pressure);**仅用于预览重建**,不参与撤销 |
| `model/LiquifyOperationLog.kt` | **新增** | 环形缓冲,零分配 append;`LiquifyController` 持有 |
| `ui/liquify/LiquifyBrushKernel.kt` | **新增** | AGSL 函数片段(push/pinch/twirl),与引擎侧幅度曲线对齐(参考 [`LiquifyPath.amplitude()`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt:72)) |
| `core/liquify/LiquifyShader.kt` | 修改 | 单 pass 支持"一次处理 N 个 dab"(uniform 数组 / 位移纹理编码),把 `draw calls ≈ 1` |
| `core/liquify/LiquifyCompositePass.kt` | **新增** | 只合成 dirty tile 到画布覆盖层 |

前置:必须先在 Phase 1 拿到 `in→sub / sub→present` 的分段,否则无法判断批处理是否有收益。
**Phase 4 之前不要动 shader**(AGENTS.md §5 "先查波及面再动手")。

### Phase 5 — 仅当数据指向呈现层才做

`SurfaceView + GLES` 画布管线、FBO ping-pong、`glScissor`、GPU readback 归零、
`LiquifyRenderCommand`/`LiquifyPingPong`/`LiquifyProfiler` 的 GLES 版实现。
**当前明确不做**(反向证据 `draw p95 0.10ms`)。触发条件见 §5。

## 5. 决策点(需要真机数据才能定)

| 决策 | 需要的读数 | 判据 |
|---|---|---|
| 是否做 Phase 4 批处理 | HUD 4.7 行 `in→sub / sub→present p95` | `in→sub` 高 ⇒ 批处理无效,回到 Phase 3;`sub→present` 高 ⇒ 呈现层问题 |
| 是否做 Phase 5(GL 管线) | `frame p95` 与 `draw p95` 的差额 | `draw p95` 仍 ≈0.1ms 而 `frame p95` 30ms+ ⇒ 是 HWUI pacing,不是绘制命令 |
| 代理分辨率的取舍 | §4.14 的 100/75/50/25 曲线 | 曲线平坦 ⇒ 瓶颈是覆盖面积(先 Phase 2/4),不是带宽 |
| tile 尺寸 | 32/64/128/256 的 `dirty tiles / submit ms` | 现在不必选:Phase 2 先复用现有整屏回退门,观察 tile 数分布后再定 |
| Operation Log 是否必需 | Phase 2 后"覆盖层位移场是否已足够" | 位移场是网格点的函数 ⇒ **大概率不需要** Operation Log(见 §3.2) |

## 6. 工作区限制与验证方式

- **C++ 轨道(Phase 3)需要 Qt for Android 6.6.3 + Krita 源码 + KF6**(AGENTS.md §2、RENDER-OPTIMIZATION.md §7)。
  本机已具备该工具链 ⇒ 走 [`scripts/build_native.sh`](../scripts/build_native.sh)(两次 gradle 构建:
  先 CMake 编译 jni 产出闭包, 再补齐 `jniLibsNativeEmpty` 出完整 APK), 并做**导出符号/NEEDED 闭包比对**
  与 `third_party/android-native-libs` / `app/src/main/jniLibs` 同步 —— 改 C++ 而不重编会让源码与预编译库不一致。
- Kotlin 轨道(Phase 1/2/4)只需 `:app:compileDebugKotlin` + `:app:testDebugUnitTest`。
- 每阶段机械验证:

```bash
./gradlew :app:compileDebugKotlin      # 改 Kotlin 必跑
./gradlew :app:testDebugUnitTest       # 改 model/ 或纯逻辑必跑
./gradlew :app:lintDebug               # 大范围改动
./gradlew :app:assembleDebug -PlqTestProfile=1   # 无数据线档位: AGSL + 2 步/帧
```

- 新增纯逻辑(`LiquifyDirtyRegion` / `LiquifyOperationLog`)必须补
  `app/src/test/java/com/reverie/paint/model/` 下的 JUnit4 测试(反引号方法名,见 `LiquifyPathTest`)。

## 7. 建议的提交序列

1. `perf(liquify): 补 input→submit→present 两段打点, 为 V2 优先级定位` (Phase 1)
2. `refactor(liquify): 预览拆出 LiquifyTextureSet/WarpPass, 对外行为不变` (Phase 2 · 1/2)
3. `perf(liquify): AGSL 覆盖层接入 tile 脏区, 不再每 dab 整屏重绘` (Phase 2 · 2/2)
4. `perf(liquify): 交互期消除 rebase 全量物化(带回退开关)` (Phase 3,C++ 轨道)
5. `perf(liquify): 局部 deformation + 多 dab 批处理` (Phase 4,视数据而定)
6. `docs(liquify): V2 架构与量测结论成文` (每阶段更新本文与 RENDER-OPTIMIZATION.md §9.1)

## 8. 明确不做(第一阶段)

按提案 §38 与本仓库实证:不做 Compute Shader、Vulkan、多线程 GL、GPU readback、
全面 ECS 化、复杂 Undo checkpoint、自定义 GPU 内存分配器、把 Operation Log 当真身、
在无数据支撑前重写 shader。
