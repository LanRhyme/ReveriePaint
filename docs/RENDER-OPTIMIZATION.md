# 渲染 / 保存 / 液化 性能优化记录

> 适用分支: `fix/performance`(基于上游 1.3.1 合并后)。**行号锚点以写下这段文字时的源码为准**,
> 代码改动后请同步; 与代码冲突时以代码为准。
> 相关: [开发规范](../AGENTS.md) · [滤镜/图层研究](FILTER-LAYER-RESEARCH.md)
> 组织方式: 按**主题**分节(不再是逐轮日志); 历史轮次与旧编号对照见 §11。

## 1. 一页速览

| 域 | 改动 | 状态 | 开关 / 入口 |
|---|---|---|---|
| 渲染 | 1:1 与缩放两条路径都走脏区增量; 缩放路径以复用缓冲作 QImage 后端就地 swizzle; 局部 `invalidate` 带安全回退 | 已落地 | 无(自动) |
| 渲染 | 视图变换缓存 + 像素网格按视口裁剪 + 覆盖层零分配 | 已落地 | 无(自动) |
| 保存 | 流式管线: 图层/关键帧 COW 快照 + 按块并行"取图→编码"+ 逐块释放; PNG 档位 70; 预压缩资产跳过 deflate | 已落地 | `setprop debug.reverie.pngq <1..89>` |
| 保存 | 阶段计时(快照/编码/写盘/体积) → HUD 与 logcat | 已落地 | 见 §6 |
| 液化 | 多目标 warp 并行; 白线伪影修复; 三个闪退成因修复; 大笔刷精度档 32 + 分辨率保底 | 已落地 | `kLiquifyParallelTargets`(编译期) |
| 液化 | 四段耗时打点(总/形变/补洞/回写/合成) + 网格导出与箭头可视化 | 已落地 | `setprop debug.reverie.lqprec` / `debug.reverie.lqgrid` |
| 液化预览 | 手势期间不 `run()`/不写图层/不触投影, 由显示层叠加低分辨率形变预览; 抬笔仍精确 materialize | 原型已落地, 待真机 | `setprop debug.reverie.liquifyPreview 1` |
| 液化预览 | AGSL 版: 同一份位移场交给 GPU 在显示分辨率上采样(引擎只给源裁剪 + 网格) | 原型已落地, 待真机 | `setprop debug.reverie.liquifyPreviewGpu 1` |
| 液化预览 | 交互态会话(Phase 3A/3B): latest-state-wins 收敛成纯 Kotlin 状态机, 补 backlog/lag 指标(HUD 第 4.5 行) | 已落地, 待真机 | 同 2C(`debug.reverie.lqcoalesce` / `-PlqTestProfile`) |
| 液化预览 | 实验 A: 代理分辨率旋钮(源纹理按比例下采样) + HUD `/代理` 读数 | 已落地, 待测量 | `-PlqProxy=<10..100>` / `setprop debug.reverie.lqproxy` / **设置→诊断 内应用内切换** |
| 液化预览 | 干预实验(§4.15): 预览"状态暂存"与"纹理上传"解耦, 上传 ≤ 1/帧; HUD 拆出 暂存/网格上传/源上传 + frame p95 | 已落地, 待测量 | 无(自动); 设置→诊断 内的合并步数/代理旋钮仍可用 |
| 液化预览 | 覆盖层局部失效(V2 Phase 2, §4.17): 由前后两帧位移场差异推文档脏区, 不再每个 dab 整屏重绘 | 已落地, 待真机 | `debug.reverie.liquifyPreviewGpu` + `partialInvalidateEnabled` |
| 液化 | V2 Phase 3 Commit 1(§4.18): rebase / materialize 生命周期埋点 —— 把"拖动中物化"拆成 rebase 边与节流边 | 已落地, 待真机取数 | HUD 第 4.5 行; 无行为变更 |
| 内存 | 帧缓存按字节预算淘汰 + `ComponentCallbacks2` 内存压力释放; 缩略图给笔画让路 | 已落地 | 无(自动) |
| 量测 | `PerfTrace`(Kotlin, 分桶统计) + `revpSaveStats`(C++ 阶段计时) + debug 专属 HUD | 已落地 | `setprop debug.reverie.perf 1` / debug 包设置页 |
| 未做 | tile 化显示缓冲(整张纹理重传是最大带宽项) · 视口尺寸渲染缓冲 · 液化按 tile 增量 `run()` | 待立项 | 见 §9 |

**目前仍有真机未回归项**: 液化分辨率保底窗口(132~134px) 与 release 包的整体回归 → §11 清单。

## 2. 渲染链路

### 2.1 现状

```
触摸 → CanvasTouchView 派发 → VM.queueStrokeMove → 引擎 HandlerThread(单实例 g_core)
     → C++ renderToBuffer: 1:1 路径 / 缩放回退路径, 均按脏区增量写显示缓冲
     → 双缓冲轮转 + lastWrittenRect 回传 → 局部 postInvalidate(不满足安全条件则整屏)
     → CanvasTouchView.onDraw: 棋盘格 + 位图 + 像素网格 + 封面层(镜像/预测/光标)
```

关键实现(见 [`ReverieCoreRender.cpp`](../app/src/main/cpp/ReverieCoreRender.cpp:90)):

- **1:1 路径**: 持久显示缓冲 + 脏区 `readBytes` + `blitBgraToRgbaFast`(NEON, [`ReverieCoreInternal.h`](../app/src/main/cpp/ReverieCoreInternal.h:891))。
- **缩放回退路径**: 以复用的 `m_subRegionBuffer` 直接作 `QImage` 后端并**就地** BGRA→RGBA, 再 `scaled`
  到视口尺寸后逐行拷入显示缓冲; 每帧不再有脏区尺寸的堆分配。
  读区比脏区四周各外扩 1px, 避免平滑缩放时边缘像素权重错误形成"接缝/鬼影"。
- **笔画期间**绕过 Krita 后台调度器, 对本层投影做**小块同步合成**(µs 级), 保证落笔当帧就能看到像素。
- 液化期间的投影同步合成**不在**渲染路径上(理由见 §4.2), 渲染路径因此只保留笔画这一支。

### 2.2 局部失效与回退条件

渲染完成后发布本帧脏区快照([`publishRenderDirtySnapshot()`](../app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:3047)) →
视图侧映射成屏幕矩形做局部 `postInvalidate`([`invalidateFromRender()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:1074))。

- 脏区 → 屏幕矩形按 `1 + 1/scale` 在位图空间外扩再映射(画布位图走 GPU 双线性采样, 脏区之外的源
  像素影响范围随放大倍数增长), 屏幕空间再外扩 1px; 由 `CanvasViewTransformTest` 锁死。
- [`canPartialInvalidate()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:1107)
  **安全条件不满足一律回退整屏**(宁可多画一次, 不允许边缘残影): 画布旋转 / 像素网格可见 / 覆盖面板
  打开 / 光标或预测或镜像笔迹活动 / 变换会话进行中 / 回放页或动画播放中 / 脏区超过视口一半。

### 2.3 已探明的带宽上限: 整张纹理重传

Android 侧 `Bitmap` 一旦被写入, HWUI 下次绘制会把**整张**纹理重新上传 —— 4096² 画布就是 64MB/帧,
且**局部失效救不了它**(纹理以整张 bitmap 为单位)。这条只有 tile 化缓冲(或换成 GL 管线)才能突破,
是 §9 待办里收益最大的一项; 量测口径与判据见 §6。

### 2.4 覆盖层与视图变换(Kotlin 侧)

- [`CanvasViewTransform`](../app/src/main/java/com/reverie/paint/model/CanvasViewTransform.kt:24): 缓存视图参数,
  三角函数只在参数变化时算一次, 结果写调用方提供的 `out` 数组(零分配); 与 `CanvasView` 的
  `imageToWidget`/`widgetToImage` 同一套公式, 由单测锁死往返一致性。
- 像素网格按视口裁剪: 只画可见网格线, 并设 `MAX_VISIBLE_GRID_LINES = 6000` 兜底
  ([`MAX_VISIBLE_GRID_LINES`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:43))。4096 画幅 4x 放大时
  绘制线条数从约 8192 → 数百。
- 镜像笔迹改为扁平 `FloatArray`(`[x, y, pressure]` 三元组, 容量按需翻倍), 绘制/回放/失效判定三条
  路径零分配; 变换预览的 `Matrix`/`Path`/`Rect` 由 `remember` 复用, 拖动期间零分配。
- 画笔颜色解析缓存(字符串 → `Paint`), 压力曲线按 1/256 量化缓存(原落笔期间每帧一次 JNI), 吸色由
  `getPixel` 改为复用数组 + `getPixels`。

## 3. 保存管线(.revp)

### 3.1 流式管线

大项目保存慢的根因不是 PNG 压缩, 而是**每个图层都先造一张整幅 QImage 并全部驻留**:

| 事实(源码依据) | 代价(4096² 画幅 / 单层) |
|---|---|
| `KisPaintDevice::convertToQImage()`: `new quint8[w*h*pixelSize]` → `readBytes` → `convertPixelsTo` | 64MB 临时分配 + 两遍整幅内存流量 |
| 旧代码在其后还 `.copy()` 一次 | 再 64MB 分配 + 两遍流量(而它本已是独立新图) |
| 所有图层 QImage 同时常驻 | 15 图层 ≈ 960MB 常驻, 编码期再叠加 PNG 字节 |

现在改为流式([`RevpPngJob`](../app/src/main/cpp/ReverieCoreIO.cpp:299)):

| 项 | 做法 |
|---|---|
| 不再物化整幅图 | 条目携带 `std::function<QByteArray(int)>` 工厂, 在**工作线程**"取图 → 编码" |
| 引擎线程只做瓦片级快照 | 图层用 `makeCloneFrom`(COW 指针拷贝, µs 级), 关键帧用 `writeToDevice` 到独立设备 |
| 按块并行 + 逐块释放 | 块 = 池线程数(≤4), 块内并行"转换+编码", 块间串行写 zip; 写完立即释放该块的图与工厂 |
| 峰值内存 | ≈ min(线程数, 条目数) × (临时缓冲 + QImage + PNG), 与图层/关键帧总数解耦 |
| 预览 / 缩略图 | 仅这一项仍整幅物化一次(缩略图要由它缩放), 两者共享同一份图 |

等价性: 条目名、条目顺序、PNG 档位与像素内容都不变。空图层(无瓦片)的克隆是安全空操作
(`KisTiledDataManager::bitBltImpl` 首行即 `if (rect.isEmpty()) return;`), 转换结果仍是"默认像素铺满整幅";
`makeCloneFrom` 会一并继承 `defaultBounds` 与 `defaultPixel`, 故**背景层默认像素不会丢**。

风险与回退: 异步保存时引擎线程仍在绘制, 图层快照与工作线程读的是同一批瓦片(COW)。这是 Krita 自身
快照机制的前提: 瓦片引用计数是原子的(`libs/image/tiles3/kis_tile_data.h:61`), 写方 detach、读方拿旧瓦片;
且 `makeCloneFrom` 的官方约束是"目的设备不得被其它线程访问" —— 本实现每个快照只交给**一个**编码任务。
若真机出现异常, 把 [`makeSnapshotLayerJob()`](../app/src/main/cpp/ReverieCoreIO.cpp:379) 里的克隆换成
`dev->convertToQImage(...)`(退回引擎线程转换)即回到改动前语义。

### 3.2 PNG 档位: 用实测换 2.6~4.4 倍编码速度

Qt 的 `quality` 对 PNG 的真实语义由宿主基准实测(基准程序见配套的开发工具改动, 不在本改动范围内;
用 ICU 56 桩库绕开 Qt 官方 linux 二进制在新发行版上的缺失依赖), 三类典型图层内容(2048²):

| 内容 | Qt 默认(`quality=-1`) | `quality=70` | 结论 |
|---|---|---|---|
| 线稿(透明底 + 抗锯齿笔迹) | 393 ms / 3.93 MB | **149 ms / 4.22 MB** | 快 2.6x, 体积 +7% |
| 铺色(渐变 + 实心形状) | 72 ms / 202 KB | **51 ms / 456 KB** | 快 1.4x, 绝对增量仅 +254 KB |
| 厚涂/噪点 | 1032 ms / 10.6 MB | **235 ms / 9.0 MB** | 快 4.4x, 体积反而小 15% |

即 Qt 默认档恰是最慢的一档; 70 档"时间大赢、体积基本打平"。因此
[`kRevpPngQualityDefault = 70`](../app/src/main/cpp/ReverieCoreIO.cpp:261), 上界压在 89(≥90 时 Qt 写不压缩
PNG, 体积暴涨)。真机 A/B: `setprop debug.reverie.pngq <1..89>`。

**兼容性**: PNG 无损, 档位只影响压缩率; `.revp` 仍是同一 zip 容器、同一批条目名与顺序, 旧版本打开
新文件读到的像素逐字节一致。KRA 导出路径未改动, 与 Krita 的格式兼容性不受影响。

### 3.3 容器压缩策略

PNG 本身已是 deflate 流, 容器再压一遍体积几乎不变却白烧 CPU ⇒ 写 PNG 条目时
`store->setCompressionEnabled(false)`(KoQuaZipStore 的 level 每次 `open()` 时读取, 可逐条目切换);
资产按扩展名判断, 已压缩媒体(mp4/mp3/jpg/png…)直存; meta / layers.xml / 录制流仍正常压缩。

### 3.4 阶段计时(供 HUD 与 logcat)

[`publishRevpStats()`](../app/src/main/cpp/ReverieCoreIO.cpp:221) 把上一次保存拆成四段写入原子量, JNI
[`revpSaveStats`](../app/src/main/cpp/reverie_jni_io.cpp:92) 每次返回 8 个 qint64:
`[total, snapshot, encode, write, pngCount, pngBytes, fileBytes, async]`。

- **snapshot**: 引擎线程(元数据 + 预览转换 + 图层/关键帧快照 + 选区掩码读取)
- **encode**: 工作线程(`readBytes` + 色彩转换 + PNG 编码)
- **write**: zip deflate + 文件写入 + 关闭改名

判据: 快照段高 ⇒ 引擎线程仍被文档操作占着(下一步该把快照也异步化); 编码段高 ⇒ PNG 仍是瓶颈;
写盘段高 ⇒ 是 IO/zip。

### 3.5 Kotlin 侧旁路开销(同期清理)

| 问题 | 处理 |
|---|---|
| [`PaintRecorder.serialize()`](../app/src/main/java/com/reverie/paint/core/PaintRecorder.kt:234) 把事件流整份拷贝四次 | 按精确容量一次性分配、顺序写入, 容量算准后直接交出内部数组(零拷贝) |
| 每次 `serialize()` 都重读几十 MB 快照文件 | 快照字节缓存(键 = 路径 + mtime), 命中即零 IO 零分配; 会话结束与内存压力时清理 |
| 保存后在主线程扫目录 + 解析刚写出的 .revp | `refreshProjects()` 拆成"协程 + IO 解析"与"纯 IO 构建列表", 刷新期间保留旧列表 |
| 首启在主线程拷贝几百个笔刷预设与资源 | 拆成 IO 拷贝 + 主线程写状态两步, 首启/清数据不再卡主线程 |

录制 blob 结构未变(同顺序、同长度字段), 旧工程回放不受影响。

## 4. 液化

### 4.1 架构(与 Krita 变换工具同源)

用 Krita 自己的 `KisLiquifyTransformWorker`(`libkritaimage`)而不是自写重采样:

- **build-up**: 一个持久网格 worker 累积每一 dab 的位移, 每次 apply 都从**未变形的 src 副本**重新变换
  (逐 dab 在"已变形结果"上再变换会重复重采样, 拉出接缝/空白线)。
- **局部网格**: worker 只覆盖笔刷邻域 `bounds`(见 §4.4), `src` 是等大克隆; 笔刷将要离开内边距时
  **rebase**(先 flush 再按新位置重建)。
- **增量回写**: 旧位移在 build-up 下不再变化 ⇒ 只回写新 dab 影响到的 delta 区, 单次 apply 成本不随
  拖动长度线性增长。
- **多图层**: 每个目标各自 src/dst/worker, 同一批网格操作发给所有 worker, 整段手势合成**一条撤销**。
- **选区 / Alpha 锁**: 写回受选区约束(选区外冻结), 层 `alphaLocked` 时只让颜色通道跟随形变。

### 4.2 白线伪影(上游 1.3.1 同样存在)

真机截图证实: 纯上游 1.3.1 的液化也会出现 1px 白色细线与环绕回写区的白色矩形轮廓。两条成因:

| 成因 | 修复 |
|---|---|
| `run()` 只覆盖它实际遍历到的瓦片, 其余保持 `clear()` 后的透明; 回写用 `COMPOSITE_COPY` 会把这些**假透明擦进图层** → 透明处露出画布白底 | [`seedTransparentFromSource()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:180): 回写前把 `area` 内 `alpha==0` 的目标像素补回 `src` 原内容(已映射像素逐像素不变; thread_local 复用缓冲, 参数按值传 `KisPaintDeviceSP` 才能调非 const 的 `writeBytes`) |
| 渲染路径读的是 Krita **异步投影**, 可能在重组合中途被读到 | 投影同步合成改由 [`liquifyApplyLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:221) 在写回后立即执行(按 20~64ms 节流) |

> 同步合成一度放在渲染路径上(`m_drawing || m_liquifyTxnActive`), 白线确实修好, 但"每个输入事件一次的
> 渲染"都要承担一次大区域合成 → 大笔刷下吃满渲染线程。挪进 apply 后语义等价(写回后立刻合成, 渲染时
> 投影已是最新), 频率却降了一个数量级。

**残留取舍**: 补洞用的是"未变形的源像素", 所以强位移时孔洞处表现为"未形变残影"而不是白线; 且每次
apply 要额外读 dst + src 各一份(2M px 约 24MB 流量)。若将来要再省, 可在 rebase 时缓存 src 原始字节。

### 4.3 闪退(三个独立成因, 全部修掉)

| 成因 | 说明与修复 |
|---|---|
| 累积脏区无界 | 单 dab 影响半径 `infl = 3.2×size+8`(200px → ~1300² ≈ 1.7M px); 原先每 dab 都 `united()`, 快速拖动会退化成整条轨迹的包围盒。现在设**面积预算**([`768K px` 默认, 256K~2M 自适应](../app/src/main/cpp/ReverieCoreMiscTools.cpp:145)) + 新 dab 与已累积区脱开即 flush, 且预算至少容 2 个 dab(否则退化成"每 dab 都 apply")。build-up 语义下提前回写逐像素等价 |
| **网格精度越界** | Krita 要求 `pixelPrecision` 必须是 **2 的幂**(判据不是"16 的倍数"): `libs/image/kis_grid_interpolation_tools.h:33` 的 `calcGridDimension` 用 `alignmentMask = ~(pixelPrecision - 1)` 做位掩码对齐, 非 2 的幂算出的网格尺寸与网格点容器容量不一致 ⇒ 越界访问(崩)。上游 `qBound(4, size/8, 16)` 对 **68px** 算出 `9`、78px 算出 `10` —— 都是非法值(真机 68px 必崩, 属**上游既有 bug**)。本分支吸附到合法档 `{4,8,16,32}` |
| 节流上限放宽加剧网格退化 | 试过把 apply 间隔上限 64ms → 120ms: 一次 apply 前累积更多 dab、网格形变更剧烈 → 更易触发退化。已回退到 [`20~64ms` 自适应](../app/src/main/cpp/ReverieCoreMiscTools.cpp:307) |

注: worker 构造函数里**没有** precision 断言(只有 `KIS_ASSERT_RECOVER_RETURN(!srcBounds.isEmpty())`),
所以"非法精度必崩"这条要理解成**实现依赖**(位掩码对齐), 而不是断言保护。

### 4.4 性能: 真正的成本是网格单元数

`run()` 对每个网格单元做一次多边形裁剪填充 + 瓦片读写, 单元数 = `(bounds / 精度)²`:

| 参数 | 值 | 说明 |
|---|---|---|
| `bounds` 半径 | `R = max(192, 1.9×size)` | 必须容纳高斯影响半径; 压到 1.6σ 省面积会让强位移更频繁地取 bounds 外像素、网格更易退化(真机更不稳, 已回退) |
| 网格精度 | `{4,8,16,32}` + **分辨率保底** | 见下 |
| dab 影响半径 | `infl = 3.2×size + 8` | 决定累积区与回写区 |
| apply 间隔 | `qBound(20, max(elapsed×2, 20), 64)` ms | 单次超帧预算就退避, 保证渲染线程能服务输入 |
| 脏区预算 | 默认 768K px, 按 apply 实测耗时 ± 倍调整 | 目标: 一次 apply 的 warp + 回写 + 同步合成贴着帧预算 |

精度档位([`ReverieCoreMiscTools.cpp:501`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:501)): 先按 `size/8` 吸附到
合法档, 再施加保底:

```cpp
const int rawPrecision = qBound<int>(4, qRound(size / 8.0), 32);
int precision = rawPrecision > 16 ? 32 : (rawPrecision > 12 ? 16 : (rawPrecision > 6 ? 8 : 4));
const int resolutionFloor = qMax<int>(16, R / 8);   // 每半径 ≥8 单元
while (precision > resolutionFloor && precision > 4) precision /= 2;
```

- 大笔刷用 32: 760² 的 bounds 从精度 16 的 47×47 = **2209 单元**降到 24×24 = **576** ⇒ 约 **4× 提速**
  (用户验收版 200px / 200% 快速拖动可接受)。
- 保底档保证**每半径 ≥8 个单元**(单元内位移是分段线性的, 单元过粗时高斯曲率会被折成平面 ⇒ 边缘硬折)。
  代价只有 132~134px 这一窄区间由 32 回落到 16; ≥135px 仍是 32。**这一窗口需要真机主观确认**(§11)。
- 去掉重复的 `dst->clear()`: worker 的 `run()` 内部本来就会清(`libs/image/kis_liquify_transform_worker.cpp:605`),
  每次 apply 每目标少清一整块 bounds。
- 多目标 warp 并行: [`kLiquifyParallelTargets`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:151)(编译期开关,
  置 false 回退逐层串行), 只并行"纯计算"阶段; 写回/脏区标记/同步合成保持在引擎线程。

### 4.5 被否掉的方案: "推拉模式绕开网格做整块 memcpy"

复核 Krita 源码后确认前提不成立 —— `KisLiquifyTransformWorker::translatePoints()` 构造
`TranslateOp(offset)`, 其 `operator()` 返回 **`pt + lambda * m_offset`**
(`libs/image/kis_liquify_transform_worker.cpp:511`), 而 `lambda = exp(-0.5·(dist/sigma)²)` 逐网格点计算、
仅 `maxDist = 3.0·sigma` 内生效(同文件 `:423` / `:438` / `:520`)。

即"推拉"是一条**高斯衰减加权的位移场**, 不是刚性平移: 整块 memcpy 会把用户未触碰的像素一并搬走。
要再降推拉成本只剩 ①按 tile 增量 `run()`(需碰 Krita 内部 API) ②更粗网格(质量取舍, 即保底的反方向)。

### 4.6 已知遗留

1. **缓存"除被液化层以外的合成结果"**(手势开始时算一次, apply 时只叠被液化层) → N 层合成降为 1 层;
   需正确处理混合模式/裁剪/图层组/图层样式并逐项回归。
2. **按 tile 增量重算**(只重算真正变化的格子) → 需碰 Krita 内部 API。
3. **rebase 频率与 `R`/`margin`**: `R = max(192, 1.9·size)`、`margin = max(40, 0.7·size)` 直接决定形变
   裁切质量与重建成本, 未盲调; 若大笔刷仍偶发卡顿, 值得做"增大 R 同时增大 margin"的真机 A/B。
4. **每次 rebase 重建 src/dst 设备**(760² ≈ 2.3MB ×2 ×目标数): 可复用设备以减少分配抖动。

### 4.7 Phase 0 量测: 单次 apply 的四段耗时

液化"大笔刷卡顿"的下一步不该凭感觉加线程, 所以先把一次 apply 拆成四段计时。实现是
[`liquifyApplyLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:221) 内的局部量(每次 apply 8 个 relaxed
原子写, 不改变行为)→ JNI `liquifyStats()` → `PerfTrace.liquifyApply()` → HUD 第 4 行:

| 段 | 内容 | 成本 ∝ |
|---|---|---|
| `形变` | 各目标的 `KisLiquifyTransformWorker::run()`(多目标并行) | 网格单元数 = `(bounds/精度)²` |
| `补洞` | `seedTransparentFromSource()` 的 dst/src 读写 | 区域面积 × 3 次内存流量 |
| `回写` | `KisPainter::bitBlt(COMPOSITE_COPY)` + `setDirty` | 区域面积 |
| `合成` | `markRegionDirty` + `compositeLayersRange(所有可见层)` | 区域面积 × 可见图层数 |

**判读(决定投 Phase 1 还是 Phase 2)**:

1. `合成` / `回写` 占大头 ⇒ "把预览态从文档流水线里剥离"收益最大(交互期间不写图层、不重合成);
2. `形变` 占大头 ⇒ 瓶颈在 Krita 网格本身, 先做"位移场导出 + GPU 预览"(§9.1 Phase 2);
3. `补洞` 占大头 ⇒ 是补洞实现的内存流量问题, 缓存一份 src 原始字节即可省掉每次的区域读。

取样: 同一尺寸各画一段(30 / 60 / 120 / 200px), 并在 1 层与 10 层文档上各重复一次 —— "合成"那段的
`× 可见图层数` 只有在多图层文档上才会暴露出来。

**HUD 第 4 行格式**: `液化 总ms 形变/补洞/回写/合成 N层 xK px 精度P/单元C`, 其中 `单元C` 是
`(bounds/精度)²` 的估算值 —— 与 `形变` 的毫秒数相除即得"每个网格单元的成本"。

**诊断开关(不需重编译)**: `setprop debug.reverie.lqprec <4|8|16|32>` 强制网格精度档(只接受 2 的幂),
用来在真机上量"单元数 → 耗时 / 形变边缘质量"的曲线; `0` 或未设 = 自动档(档位 + 分辨率保底)。

> **首组真机数据**(小笔刷量级 / 1 层 / 脏区 112K px): `液化 29ms 形变 28/补洞 0/回写 1/合成 0`
> ⇒ **96% 花在 Krita 网格 `run()`**。按上面的判据: 剥离"回写 + 合成"(Phase 1)在当前尺度只能省约
> 1ms, 不值得先做; 瓶颈侧在"形变"。
> 另一个要点: `R` 的 192px 下限使**小笔刷的单元数反而更大** —— 36~51px 的自动档只有 4
> (因为 `round(size/8)` 落在 5~6 只能吸附到 4), 单元数约 9.2K; 而 200px 用精度 32 只有 576 单元,
> 相差 16 倍。这解释了"小笔刷也卡", 也正是 `lqprec` 开关要量的那条曲线。

### 4.8 Phase 2 · Commit 1: 网格导出与可视化 (已落地)

写 Preview 之前必须先确认"网格 → 位移场 → 屏幕"这条链路的几何与方向, 所以先只做导出:

- **C++**: [`liquifyGridExport()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:559) 直接读 worker 自己的
  `originalPoints()` / `transformedPoints()` —— 也就是 `run()` 做分段线性 warping 用的**同一份网格**。
  - **row-major**: `index = row * columns + col`, 顺序与 Krita `GridIterationTools::processGrid` 的迭代
    顺序一致(`AllPointsFetcherOp` 逐行逐列 append; worker 内另有 `numPoints == cols * rows` 的断言);
  - 点坐标是**文档坐标**, `offset = transformed - original`;
  - 多目标图层的位移由同一批操作算出, 因此只导出第一个目标即可代表全部;
  - 数据不完整(无 worker / 尺寸不匹配)时 `count = 0` —— 宁可没有预览, 也不给上层一份错位的位移场。
- **JNI**: [`liquifyGrid()`](../app/src/main/cpp/reverie_jni_tools.cpp:279) 返回 float 数组
  `[bx, by, bw, bh, columns, rows, precision, count, (origX, origY, dx, dy) × count]`。
- **可视化/量测**(仅 debug 标尺, 见 §6.2):
  - `setprop debug.reverie.lqgrid 1` 打开后, 画布叠加**位移场箭头**(琥珀=位移方向, 浅蓝=位移后位置;
    用 `CanvasViewTransform.docToScreen`, 与手势/光标同一套映射), 最多采样约 40×40 个点;
  - HUD 追加第 5 行: `网格 47x47 精度16 Δmax 12.4 Δmean 3.1px`;
  - 正式版里这条链路不存在(`PerfHud` release 空实现 + `gridOverlayEnabled = false`), 包内亦无标尺文案。

**目视自检清单(写 CPU Preview 之前先过一遍)**:

1. 箭头只在笔刷邻域非零, 覆盖范围 ≈ `bounds`(不是整幅画布);
2. 向右拖动后, 笔刷中心的 `dx > 0` 且量级与 `strength × 拖动位移` 相符;
3. 位移沿拖动方向平滑衰减(高斯), 不应出现指向随机的孤立箭头;
4. 网格步长在屏幕上看起来 = `precision × 当前缩放`;
5. 松手或重开液化后网格清空(随 worker 重建)。

### 4.9 Phase 2A-2: CPU 低分辨率预览原型 (已落地, 待真机)

目的只有一个: 证明"**把 worker 的网格状态拿出来, 在显示层独立预览**"这条路成立 —— 手势期间
不 `run()`、不写图层、不触投影合成, 也能得到几何与方向正确的形变画面。**不追求画质, 也不追求速度**
(2B 才把采样核换成 GPU)。开关: `setprop debug.reverie.liquifyPreview 1`(默认关, 关闭时整条路径不可见)。

| 环节 | 实现 | 关键约束 |
|---|---|---|
| 缓存源像素 | [`liquifyPreviewCaptureLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:598) 在每次 rebase 后 `readBytes` 一次 bounds 原始像素 | 整段手势只读一次; 只支持 8bit RGBA/BGRA 文档(`pixelSize ≤ 16`), 其它色彩空间直接放弃预览 |
| 生成预览 | [`liquifyPreviewBuildLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:629) 每次 dab 后用**网格点双线性插值**得到位移场, 再**反向采样** `dst(p) = src(p - offset(p))` | 最长边 ≤ 192px([`LIQUIFY_PREVIEW_MAX_EDGE`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:210)); 位移场与 `run()` 同源(同一批网格点), **几何一致、只有采样核是近似** |
| 叠加显示 | [`blendLiquifyPreview()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:757) 在 `renderToBuffer` 写完缓冲后, 把预览按显示缩放映射进缓冲并 source-over | 复用现有缩放/旋转/双缓冲; 只处理 `written ∩ 预览矩形`, 成本与脏区同阶(1:1 路径见 [`ReverieCoreRender.cpp:183`](../app/src/main/cpp/ReverieCoreRender.cpp:183)) |
| 收口 | 抬笔时 `liquifyEnd()` 强制按整个 `workerBounds` materialize; rebase 与预算分支同样落回完整 Krita 路径 | **预览近似、提交精确**: 撤销仍是"一次手势一条 `KisTransaction`" |
| 旁路 | 预览态下 `liquifyApplyLocked()` 不会因"超预算/脱开"被触发(位移只留在网格里) | 避免手势期间意外写盘, 否则"不触投影"的前提就没了 |

三个容易踩的坑(代码注释里已写明, 后续做 2B 时别再踩):

1. **预览区必须进脏区**: `renderToBuffer` 的增量路径只重读脏区, 不标脏则预览永远叠不上去;
2. **预览失效时必须再标一次脏**: 否则旧预览永久留在显示缓冲里(看起来像"形变没提交");
3. `m_liquifyPreviewSeq` **只增不减**(归零会被调用方误判成"没有新数据"), 用 `meta[0] == 0` 表示预览结束。

**与 2B 的衔接**: 这条链路把"位移场 → 显示"的接口固定下来了 —— 开关、生命周期、失效/收口语义都不必再改,
2B 只需把 `liquifyPreviewBuildLocked()` 的 CPU 采样换成 AGSL `RuntimeShader`(API 33+; 低版本自动退回
Krita 原路径)。**在 2A-2 的真机结论(几何是否正确、手感是否改善)出来之前不要动 2B。**

### 4.10 Phase 2B: AGSL(RuntimeShader)预览原型 (已落地, 待真机)

2A-2 已经证明"交互态可以脱离 Krita `run()`", 2B 只换一件事: **把同一个预览的采样核从 CPU 换成 GPU**。
网格生成 / rebase / 抬笔 materialize / undo / JNI 契约一律不动。开关:
`setprop debug.reverie.liquifyPreviewGpu 1`(关掉 = 2A-2 的引擎侧 CPU 预览; API < 33 自动回退)。

| 角色 | 谁做 | 说明 |
|---|---|---|
| 给料 | 引擎(C++) | rebase 后给出**未形变**的 bounds 裁剪(1 纹素 = 1 文档像素, RGBA8888)与网格 |
| 采样 | UI 侧 AGSL | `dst(p) = src(p - offset(p))` 在**显示分辨率**上做: 预览清晰度不再受 192px 上限约束 |
| 收口 | 引擎(C++) | 抬笔仍按整个 `workerBounds` materialize; 撤销仍是"一次手势一条 `KisTransaction`" |

- shader 只有两张纹理 + 8 个 uniform: 源裁剪、位移纹理(R = dx, G = dy, `RGBA_F16`)、文档→屏幕仿射
  (由 [`CanvasViewTransform.docToScreen()`](../app/src/main/java/com/reverie/paint/model/CanvasViewTransform.kt:121)
  取三个点得到: 原点 + 两个基向量)以及网格原点/步长。**不上传整幅文档的位移场** ——
  位移纹理只有 `cols × rows`(≤40×40 ≈ 12KB)。
- 双线性插值两处都由硬件完成: 位移纹理的线性过滤 = 网格插值, 源纹理的线性过滤 = 采样。网格原点与步长
  取自**真实网格点**([`liquifyPreviewSourceMeta()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:817) 与 `liquifyGrid`
  的第一、相邻点), 所以几何与 CPU 版同源。
- 绘制范围只有裁剪区的屏幕包围盒(+8px): shader 用绝对坐标取值, 缩小绘制范围只省填充率。
- 回退链(任何一环失败都不允许"两边都不画"): property 未开 → 引擎 CPU 叠加; 开了但 Kotlin 判定不可用
  (API < 33 / shader 编译抛错 / 源裁剪超预算 `> 4M px`, 见 [`LIQUIFY_HOST_DRAW_MAX_PX`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:236))
  → 显式把引擎绘制模式置 0, 回到 CPU 预览。

数据通路(逐帧): `doRender`(引擎线程) 先取 `liquifyPreviewSourceMeta` / `SourcePixels`(源裁剪只在 rebase 时取)
与 `liquifyGrid`(每 dab 6~25KB)刷新资源, 再 `postInvalidate()`; UI 线程在 `drawCanvas` 里用
[`LiquifyGpuPreview.draw()`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt:277) 叠一层。
取数入口 [`pollLiquifyGpuPreview()`](../app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:292) 放在 `renderToBuffer`
**之前**, 免得"无脏区 ⇒ 直接 return"的分支把这帧的预览更新吞掉。

已知代价与近似(与"原型"定位相符, 后续再收):
- 主机侧绘制模式下引擎不写显示缓冲 ⇒ 没有脏区 ⇒ **重绘由预览更新驱动**, 每个 dab 触发一次整屏重绘;
  按视口裁剪/分区失效留作后续优化(需要一个 doc 矩形 → 屏幕包围盒的失效入口)。
- 半透明内容会与底层未形变像素叠加(source-over 语义, 与 CPU 版一致); 多图层的精确混合顺序仍由 Krita 决定。
- 每 dab 会新建一张小位图(位移纹理)而不是原地覆写: `copyPixelsFromBuffer` 不保证推进 generation id,
  原地改内容可能让 GPU 继续用旧纹理。

### 4.11 Phase 2C: 交互调度 latest-state-wins (实验开关)

前两阶段已经把"最终物化"与"交互态呈现"分开: Phase 0 证明代价几乎全在 Krita 网格 `run()`
(60px ≈ 20ms/次、200px ≈ 41ms/次, 其中 `形变` 占 19/39ms), 2A-2/2B 证明交互态可以不碰 `run()`。
剩下的最后一个工程问题不是"采样再快一点", 而是**输入 → 预览的调度会不会积压**:

```
一次 ACTION_MOVE 可以带多个历史点; 旧实现 = 每个历史点 × 每个补点 立即提交一次
        ⇒ 队列里排的是"历史状态", 手越快 / 笔刷越大积压越多 ⇒ 预览越来越落后
```

另一条结论同样来自真机数据: `draw p95 0.10ms` —— 呈现层不是瓶颈, 所以**不要回头改脏区/绘制层**;
AGSL 模式下引擎既不 apply 也不写显示缓冲(`脏比 0.0%` 是正常现象), 覆盖层由预览更新驱动重绘。

**实验内容**: 交互态只保证"预览追上最新位置", 中间位置全部丢弃 —— 每帧最多推进 `n` 个补点, 方向始终
指向**最新**位置, 没追完下一帧继续; 抬笔时把剩余段按常规补点规则一次性补齐(不丢形变)。补点数与强度
折算复用同一套规则, 因此"分帧"只改变调度节奏, 不改变总量口径。

| 项 | 值 |
|---|---|
| 开关 | `setprop debug.reverie.lqcoalesce <n>`(n = 每帧最多推进的补点数; `0` = 关闭; 未设 = AGSL 预览时默认 2, 其它模式关闭) |
| 实现 | [`flushLiquifyPending()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:2635) + [`LiquifyPath.chaseSubsteps()`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt:53) |
| 度量 | logcat 每秒窗口出现 `liquify.input`(输入事件数) / `liquify.flush`(推进次数) / `liquify.dabs`(补点总数); 三者之比就是实际的合并倍率 |
| 边界 | 抬笔/取消前先 `removeCallbacks` 再补齐剩余段; `n = 0` 或距离 < 0.5px 时不推进 |

**为什么默认不激进**: 无预览与 CPU 预览这两条路径直接决定"最终提交的形变", 默认保持逐点处理不变;
AGSL 模式下引擎每帧的液化成本已接近 0, 这个开关只影响"网格调用次数", 默认 2 步/帧即可, 不会影响
已经验收过的 2A-2 基线。

**没有数据线时的测法(构建期档位)**: `./gradlew :app:assembleDebug -PlqTestProfile=<n>` —— 只改默认值,
装包即可对照, 不需要 adb 也不需要 logcat:

| 档位 | 默认行为 | 用途 |
|---|---|---|
| 0(默认) | 完全不变 | 一切照旧由 debug property 控制 —— 提交与 PR 用的就是它 |
| 1 | AGSL 预览 + latest-state-wins(2 步/帧) | **目标形态**, 先看这个 |
| 2 | 引擎侧 CPU 预览 + latest-state-wins(2 步/帧) | 2A-2 基线对照(看是否仍有 192px 块感) |
| 3 | AGSL 预览 + 不做调度合并 | 对照"调度合并到底帮不帮到手感" |

HUD 新增的第 4.5 行就是这台实验的读数: `泵 输入<i>/推进<f>/补点<d>/物化<a>`(上一秒窗口)。
`i/f` 即合并倍率; `d ≤ n×f` 验证"每帧最多 n 个补点"; **`a` = 这一秒发生了多少次 `apply`** ——
预览模式下引擎只在 rebase 与抬笔时 apply, 所以 `a` 就是"拖动中 rebase 的次数", 它决定下一步
是去优化 rebase(把物化从"整块 bounds 全分辨率"降下来), 还是交互侧已经收工。

真机首组读数(200px/90%, 档位 1): `泵 输入110/推进89/补点127/物化…`, `液化 59ms 形变 54/补洞 2/
回写 0/合成 3, 543K px` —— 543K px 恰好是 200px 笔刷的 bounds 面积, 即那 54ms 是一次**物化**
而不是拖动中的每次提交。若 `物化` 在拖动中还明显 > 0, 说明 rebase 是下一个瓶颈。

### 4.12 Phase 3A/3B: 交互态会话与 backlog 指标 (已落地, 待真机)

2A-2 / 2B / 2C 已经把"最终物化"与"交互态呈现"分开, 2C 又验证了"只追最新位置"的调度合并。但 2C
的实现散落在触摸视图里的五个临时字段(`liquifyPrevPos` / `liquifyPendingTo` / `liquifyInputSinceFlush`
/ `liquifyMaxDabsPerFlush` / `liquifyFlushPosted`), 既难单测, 也读不出"到底积压了多少"。3A/3B 把它
收敛成一个纯 Kotlin 状态机 —— **不改任何默认行为**(开关口径与 2C 完全一致)。

| 项 | 内容 |
|---|---|
| 状态机 | [`LiquifyInteractionSession.kt`](../app/src/main/java/com/reverie/paint/model/LiquifyInteractionSession.kt): 交互态只保留"最新目标位置", 记录 `inputSequence` / `renderedSequence` 与每帧推进计划; 不持有像素、不调 JNI |
| 提交点 | [`CanvasTouchView.liquifyFlushNow()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt): 液化的**唯一** JNI 提交点, 按会话的推进计划跑补点循环 |
| 为什么不重放操作栈 | 形变真身在 C++ 引擎网格, 每个 dab 都是**增量**累加(见 `ReverieCoreMiscTools.cpp`) ⇒ Kotlin 侧无需保存历史操作, 只记"还剩多少距离" |
| backlog 指标 | `lag = inputSequence - renderedSequence`(距上次推进累积的输入事件); `backlogDabs` = 上一帧推进后仍未提交的补点数(真实"还差多少") |
| HUD | 第 4.5 行扩为 `泵 输入<i>/推进<f>/补点<d>/物化<a>/上传<u>/滞后<cur>峰<max>步 lag<lag>`(上传正常恒为 1) |
| 默认行为 | **不变**: `lqcoalesce=0` 或关闭预览时仍逐点全量推进; AGSL 预览默认 2 步/帧(与 2C 相同) |
| 单测 | [`LiquifyInteractionSessionTest.kt`](../app/src/test/java/com/reverie/paint/model/LiquifyInteractionSessionTest.kt): 调度上限 / backlog 口径 / 分帧不改总量 / 抬笔精确落点 |

**压力测试判据(方案 §20~§22)**: 200px 高速连续 30 秒, 看 HUD `滞后…峰…步` 是否**随时间增大**。
若峰值量级稳定(≈ 一帧位移折算出的补点数), 说明 latest-state-wins 真正消灭了长期积压; 若持续爬升,
则 backlog 仍在, 下一步才需要动"局部 deformation / 代理分辨率 / 提交优化"。

**下一步(未做)**: 局部 State(仅处理受影响区域) → 代理分辨率(1/2 → 1/4) → Commit 阶段优化。
Vulkan 后端与全画布 Flow Field 明确暂缓 —— `draw p95 ≈ 0.10ms` 已说明呈现层不是瓶颈。

### 4.14 实验 A: Proxy Resolution (代理分辨率旋钮, 已落地, 待测量)

Phase 3B 证明"输入积压"只是问题的一部分。下一阶段目标是把**交互帧成本与"笔刷大小 × 画布分辨率"
脱钩**。按"先做极简 benchmark, 不急着上 Phase 4 架构"的顺序, 本实验只加一个旋钮: 让 AGSL 预览的
**源纹理**按比例下采样(形变几何不变, 纹理带宽/显存随之下降), 用来回答"瓶颈是否在 preview
pixel/bandwidth workload"。

| 项 | 值 |
|---|---|
| 构建期档位 | `./gradlew assembleDebug -PlqProxy=<10..100>`(默认 100 = 全分辨率, 行为不变) |
| 运行时覆盖 | `adb shell setprop debug.reverie.lqproxy <n>`(改后下一次 rebase 生效) |
| **应用内(无数据线)** | **设置 → 诊断 → 「液化预览代理分辨率」下拉**(自动/100/75/50/25), 持久化, 下一段手势生效 |
| **应用内(无数据线)** | **设置 → 诊断 → 「液化交互合并步数」下拉**(自动/关闭/2/4/8), 即 latest-state-wins 的每帧补点上限 |
| 实现 | [`buildSourceBitmap()`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt) 在 rebase 时一次性下采样; shader 用 `uSrcScale` 同比缩放采样坐标 |
| HUD | 第 4.5 行新增 `/代理<w>x<h>(<KB>)` —— 直接读出"这一档把源纹理压到了多少" |
| 默认 | 100 = 与历史行为逐像素一致(几何、采样口径都不变) |

**跑法(固定 200px / 高速拖动 / 同一画布)**: 依次 `setprop debug.reverie.lqproxy 100 / 75 / 50 / 25`,
每次记录 HUD 的 `draw p95` / `flip/s`(FPS 代理) / `滞后…峰…步` / `代理` 尺寸。

**判读**:
- 曲线随分辨率下降明显改善 ⇒ 瓶颈确在 preview 像素/带宽 workload, 下一步做真正的 Phase 4
  (Proxy Surface: 只 warp 受影响区域, 连屏幕覆盖面积一起降下来);
- 曲线基本平坦 ⇒ 每帧成本主要由**屏幕覆盖面积**(裁剪区在屏幕上的包围盒)决定, 而非源纹理分辨率;
  此时应先做"局部 deformation 更新 + 只画受影响区域", 再谈代理分辨率。

> 注: 本旋钮降低的是**纹理带宽/显存**, 不改变 AGSL 的片元数量(片元数由裁剪区的屏幕包围盒决定)。
> 它是一把"便宜的探针": 用来判断下一步该投"带宽"还是"覆盖面积"。

### 4.15 干预实验: 预览"状态暂存"与"纹理上传"解耦 (上传 ≤ 1/帧)

真机读到 `输入 9 / 推进 7 / 上传 9 / lag 0` —— 输入没有积压, 但**上传/提交**仍与输入同频。
本实验只做一件事: 把"输入 → 状态"与"状态 → GPU 纹理"分开, 让上传跟着 VSYNC 走。

```
引擎线程:  update(crop, src, grid)  →  只暂存 pending 状态, stateDirty = true   (+1 暂存)
UI 线程:   draw()(每帧一次) → commitLocked() → 构建/上传纹理 (+1 网格上传) → 绘制
```

| 项 | 内容 |
|---|---|
| 实现 | [`LiquifyGpuPreview.update()`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt) 变为纯暂存; 新增 `commitLocked()` 在 [`draw()`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt) 内每帧提交一次 |
| 门控 | [`CanvasTouchView.onDraw`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt) 以 `requested` 为门(而非 `active`), 让帧内提交得以发生 |
| 指标 | 每帧 `PerfTrace.frameTick()`(帧间隔 p95); `previewUpdates`(暂存次数) / `gridUploads`(网格上传次数) / `sourceUploadCount`(rebase 次数) |
| HUD | 第 4.5 行 `泵 输入/推进/补点/物化 暂存N/网格上传M/源上传K 滞后…峰… 代理…`; 第 4.6 行 `frame <ms> p95 <ms>` |
| 预期 | `网格上传 ≤ 帧数`(原先 ≈ 推进次数) |

**读法(重跑 200px 高速拖动, 对比实验前后)**:
- `网格上传` 明显下降 + `frame p95` 接近刷新间隔 ⇒ 收敛方案成立(上传不再是变量);
- `网格上传` 已 ≤ 帧数但 `frame p95` 仍有 30ms+ 尖峰 ⇒ **停止调 AGSL**, 转做 SurfaceView + GLES,
  验证"CanvasTouchView + Android Canvas/HWUI 是不是帧 pacing 瓶颈"。

> 重要澄清: 上一版 HUD 的 `/上传` 是**源纹理(rebase)上传次数**, 不是每帧的网格纹理上传。它 > 1 说明
> 拖动中 rebase 过频(每次 rebase 一次全分辨率 Krita apply) —— 这是与"输入积压"不同的独立瓶颈。
> 本实验把它拆成 `源上传`(rebase) 与 `网格上传`(每帧), 避免误读。

### 4.16 干预实验: 拆开"物化 41" (物化 count / total / max)

真机读数 `物化 41` + `draw p95 0.14ms` + `lag 0` 指向一个反模式: **`lag=0` 不代表不卡** —— 预览推进很快,
但引擎在拖动中反复 materialize(rebase), 每次都做一次全 bounds 的 `KisLiquifyTransformWorker::run()`。

引擎侧 [`liquifyApplyLocked()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp) 就是物化点; 预览模式下触发来源只有两处:
1. **rebase**: 笔刷移出 worker bounds 内边距时(`ReverieCoreMiscTools.cpp` 的 `needRebase`), 会先
   `liquifyApplyLocked()` 再重建 worker —— 这是拖动中物化的主要来源, 次数 ≈ `源上传`;
2. 抬笔 `liquifyEnd()` 的收口一次。

本实验**不改算法、不改渲染**, 只把物化拆开看:

| 项 | 内容 |
|---|---|
| 采样点 | [`PaintViewModel.doRender()`](../app/src/main/java/com/reverie/paint/core/PaintViewModel.kt) 内每渲染一次 `pollLiquifyStats()`(**引擎线程**) —— 原先只在 1s 定时器取, 只能拿到"最后一次"的耗时 |
| 累计 | [`PerfTrace.liquifyApply()`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt) 检测到 applyCount 增加时累加 `lqMatTotalMs` 并取 `lqMatMaxMs` 峰值; 窗口(1s)清零 |
| HUD | 第 4.5 行 `物化 <次数>/<累计ms> max<峰值ms>`(与 `源上传` 并列, 源上传 ≈ rebase 次数) |
| 口径 | 若两次采样间发生多次 apply, `total` 会低估(只记最后一次), `max` 仍是所采到的峰值 |

**判读(200px / 90% / 高速 / 30s)**:
- `物化 41/820ms max47ms` 这类读数 ⇒ 确认"`lag=0` 却卡"的根因是**拖动中反复 rebase 的全量物化**;
  下一步做 §9.1 的 **Phase 4 Interaction-State Compaction**: 交互期不碰 Krita Document, 只在超过阈值时
  压缩 deformation state, 抬笔才做唯一一次 materialize。
- 若 `max` 很小(几 ms)而 `frame p95` 仍高 ⇒ 物化不是主因, 回到 frame pacing 方向。

> 需要 `rebase` 分段的精确耗时、以及"交互期完全不 rebase"的开关(如 `debug.reverie.liquifyNoRebase`)时,
> 必须在具备 Qt for Android + Krita 源码的机器上 `-PbuildNative` 重编 C++(本工作区无该工具链, 故本轮先给
> Kotlin 侧可验证的量测)。C++ 侧只需在 `liquifyApplyLocked` / `needRebase` 分支加同样的累计原子量。

### 4.17 V2 Phase 2(已落地): 覆盖层局部失效, 消掉"每个 dab 整屏重绘"

§4.10 记的已知代价是"主机侧绘制模式下引擎不写显示缓冲 ⇒ 没有脏区 ⇒ 每个 dab 触发一次整屏重绘"。
本节把它接进既有脏区体系(目标与 §2.2 的 `invalidateFromRender` 相同, 只是换了个失效源)。
**只服务 AGSL 覆盖层(该路径默认关闭), 其它渲染路径一律不变。**

| 环节 | 实现 | 关键约束 |
|---|---|---|
| 脏区推导 | [`LiquifyDirtyRegion.changedDocRect()`](../app/src/main/java/com/reverie/paint/model/LiquifyDirtyRegion.kt): 比较前后两帧 `liquifyGrid()` 的位移场 | 覆盖层输出 = `src(p - offset(p))`, 源纹理整段不变 ⇒ **只有 offset 变了的地方输出才变**; 位移场是双线性采样 ⇒ 按"变化点的单元 ±1 格"取保守超集 |
| 逐帧暴露 | [`LiquifyGpuPreview.update()`](../app/src/main/java/com/reverie/paint/core/LiquifyGpuPreview.kt)(引擎线程)算好脏区, `@Volatile` 暴露 `overlayDirtyValid/Full/X/Y/W/H` | rebase 换了源裁剪(`cropKey` 变)必须判 `INCOMPARABLE` ⇒ 整屏回退; 否则局部矩形会漏掉"源纹理已换"这件事 |
| 失效计算 | [`CanvasTouchView.scheduleLiquifyInvalidate()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt): 文档脏区 → 屏幕包围盒(+8px, 与 `draw()` 余量一致) ∪ **光标环前后位置** | 光标环由 `CanvasTouchView.onDraw` 画在同一张画布上, 旧位置必须一起重绘, 否则留残影 —— 这也正是原 `canPartialInvalidate` 在触摸期一律整屏的原因 |
| 安全门 | [`canLiquifyPartialInvalidate()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt): 旋转 / 像素网格 / 面板 / 多指 / 对称镜像 → 整屏 | 与 `canPartialInvalidate` 的唯一区别是**不因光标活动而整屏**: 液化手势期间 `isInteracting` 恒为 true, 沿用旧条件等于该优化不存在 |
| 入口 | `pollLiquifyGpuPreview()` 收尾由 `postInvalidate()` 改为 `onLiquifyPreviewUpdated()`(可来自引擎线程, 内部回 UI 线程) | 不可比 / 无脏区信息 / 安全门不满足 / 失效矩形为空 ⇒ 一律整屏 |

**正确性依据**: HWUI 只重绘失效矩形, 矩形之外的旧覆盖层像素被保留 —— 而在"位移场没变"的区域, 旧像素
本来就是对的不变量。因此只要脏区是"变化输出"的超集, 画面就与整屏重绘等价。

**未做(刻意)**: 没有按 [液化 V2 改造清单](LIQUIFY-V2-PLAN.md) 把 `LiquifyGpuPreview` 拆成
`LiquifyTextureSet`/`LiquifyWarpPass` —— 该文件不到 500 行且职责已单一, 此时拆分属"为拆分而拆分"
(AGENTS.md §5 最小 diff); 等功能继续长大再拆。

**与 Phase 3 的关系**: 本节只降"重绘面积", 不降"拖动中 rebase 的全量物化"(§4.16)。两者独立, 可分别验证。

**机械验证**: `:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 通过(新增
`LiquifyDirtyRegionTest` 7 例: 无变化 / 单点变化 / 多点并集 / 阈值内抖动 / null 与规模不符判不可比 /
截断数组不崩)。**真机待验证**: ① 开 `debug.reverie.liquifyPreviewGpu 1` 推拉拖动, 环无残影、形变区边缘
无"未更新的旧像素块"; ② 与关闭开关时的最终结果逐像素一致(局部失效只改交互态呈现, 不改提交)。

### 4.18 V2 Phase 3 · Commit 1(已落地): rebase / materialize 生命周期埋点

Phase 3 的目标是把"拖动中反复 rebase 的全量物化"(§4.16)从交互热路径里拿掉。动手前先按调查结论
([LIQUIFY-REBASE-INVESTIGATION.md](LIQUIFY-REBASE-INVESTIGATION.md))只加**埋点**、不改行为。

关键前提(调查已定论):**rebase 是我们自己写的局部窗口重锚定**, 不在 `KisLiquifyTransformWorker` 里;
触发条件只有"首个 dab"与"笔尖走出 bounds 内框"两条; materialize 的唯一目的是不让**尚未落盘**的
网格位移丢失(新 worker 从 identity 开始)。200px 笔刷下锚点距内框边界只有 240px ⇒ 高速拖动必然频繁 rebase。

| 指标(C++ 原子量) | 含义 |
|---|---|
| `rebaseCount` / `reason` | rebase 次数 / 最近原因(`首dab` \| `越内框`) |
| `flushMs`(+`max`) | **rebase 前那次 flush 的耗时** —— 这一项才是"被 rebase 拖出来的物化" |
| `cloneMs` | 重建 src/dst + worker 的耗时(§4.6.4 提到的"设备重建抖动") |
| `oldAreaPx` / `newAreaPx` | 新旧 bounds 面积(验证"每次 0.58M px") |
| `innerOverflowPx` | 触发时越出内框的像素数(验证调查 §3 的"锚点 240px"判据) |
| `gridPoints` | rebase 后的网格点数(与 `形变 ms` 相除得每格成本) |
| `throttleCount` / `throttleMs`(+`max`) | **节流**那条 apply 边 —— 与 rebase 边分开, 避免误读 |

读数链路:`ReverieCore::liquifyRebaseStats()` → JNI `liquifyRebaseStats()`(12 元, **独立**于既有
`liquifyStats` 的 10 元契约)→ [`PerfTrace.liquifyRebase()`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt) →
HUD 第 4.5 行追加 `rebase<n>/<ms> max<ms> 重建<ms> 因<原因> 越界<px> 节流<n>/<ms>`。
`rebaseCount` / `throttleCount` 在引擎侧单调递增, Kotlin 侧按窗口取增量;首次取数只建基线。

**判读**:`rebase/flushMs` 占大头 ⇒ 确认必须做路线 B+C(逻辑 rebase / 扩网格);
`重建` 占大头 ⇒ 该治的是设备与 worker 重建;`原因=越内框` 且 `越界 ≈ 240px` ⇒ 调查 §3 的量化成立。

**行为不变**:只是若干 relaxed 原子写, 不改变任何判定与执行路径。
**机械验证**:C++ 走 WSL `jni-build` 的 `ninja` 增量编译通过; 产物 strip 后同步进
`third_party/android-native-libs` 与 `app/src/main/jniLibs/arm64-v8a`, JNI 导出符号集与 `NEEDED`
闭包与基线**逐条一致**;`:app:compileDebugKotlin` + `:app:testDebugUnitTest` + `:app:assembleDebug` 通过。

## 5. 内存与线程

### 5.1 帧缓存预算 + 内存压力

| 项 | 内容 |
|---|---|
| 帧缓存 | [`FrameCachePolicy`](../app/src/main/java/com/reverie/paint/model/FrameCachePolicy.kt:21): 预算 = 堆上限/16, 夹在 32MB~256MB; 淘汰按"距当前帧最远优先"; 上限 240 帧。原先写死 120 帧 —— 4096 画幅单帧 64MB ⇒ 7.7GB(必然触顶) |
| 内存压力 | [`PaintViewModelMemory.kt`](../app/src/main/java/com/reverie/paint/core/PaintViewModelMemory.kt:94): 注册 `ComponentCallbacks2`, `TRIM_MEMORY_RUNNING_LOW` 起释放回放帧缓存与录制快照缓存, CRITICAL/`onLowMemory` 再带上图层缩略图; 由 [`MainActivity`](../app/src/main/java/com/reverie/paint/MainActivity.kt:175) 注入 appContext 时注册, VM 销毁时解绑 |
| 缩略图让路 | [`refreshLayerThumbs()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelLayers.kt:82): 引擎忙时最多延后 3 秒再刷; 面板关闭则不刷 |

释放只碰"可重建"的东西: 回放帧缓存、录制快照缓存、图层缩略图。文档像素(真身在 C++ 的
`KisPaintDevice`)一律不动。

### 5.2 后台并行池与并发前提

[`reverieBackgroundPool()`](../app/src/main/cpp/ReverieCoreDocument.cpp:17): 引擎专用 `QThreadPool`(上限 4),
刻意独立于 `QThreadPool::globalInstance()` 与 Krita 内部池, 避免争池。当前有**两类**使用者:

1. 保存期 PNG 编码(§3.1) — 每次一个块, 块内并行;
2. 液化多目标 warp(§4.4) — 每次 apply 一轮。

同一时刻两者同时长跑只会互相争这 4 个线程(不会死锁), 但会拖慢彼此。若真机观察到"保存中途拖液化"
变慢, 应给两类任务留配额或让保存与液化互斥。

## 6. 量测设施

### 6.1 指标定义与口径

[`PerfTrace`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt:41) 默认**关闭**(关闭时热路径只剩一次
布尔判断); 打开后每秒往 logcat(`ReveriePerf`)打一行窗口摘要, debug 包还会在画布叠加 3 行 HUD。

| 指标 | 定义 | 备注 |
|---|---|---|
| `path full/incr/skip` | 引擎渲染落在这三条路径上的次数 | `skip` = 判定无脏区返回 false(最省一档) |
| `flip x/s` | 显示缓冲翻转次数 / 秒 | **每翻转一次, 下次绘制 HWUI 就要把整张 Bitmap 纹理重传一次** |
| `重传 xMB/帧` | `翻转字节 / 翻转次数` | 实测上限(HWUI 不做局部更新) |
| `脏比 x%` | 窗口内累计脏区像素 / **单帧**缓冲像素 | 因此 >100% 是正常的(例: 227.2%); 它就是 tile 化的收益上限 |
| `draw p95` | `CanvasTouchView.onDraw` 耗时 p95 | 只含 UI 侧绘制命令录制, 不含 GPU 上传; 固定环形缓冲 + 原地排序 |
| `save 总/快照/编码/写盘` | 上一次保存的 C++ 阶段耗时(§3.4) | `PNG n / xMB→yMB` 为条目数与体积 |
| `液化 总/形变/补洞/回写/合成` | 上一次液化 apply 的四段耗时(§4.7) | 定位"液化大笔刷卡在哪一段"的标尺; 用过液化后 HUD 会多出第 4 行 |

口径修正: `flip x/s` 加了 **500ms 窗口门槛** —— 否则"窗口刚重置 + 1 次翻转"会被折算成 140/s 这类不可能
读数(真机见过, 会误导判断), 窗口不足时显示 `--`。

HUD 平时是 3 行; **只要这次会话里用过液化, 就会多出第 4 行**(见 §4.7 的四段拆解), 用来回答
"液化到底卡在哪一段"。

### 6.2 可见性隔离(按构建类型)

标尺是研发工具, 不是绘画功能: HUD 绘制、设置入口与文案都在 **debug 专属源集**
([`app/src/debug/.../PerfHud.kt`](../app/src/debug/java/com/reverie/paint/perf/PerfHud.kt:35)), 正式版由
[`app/src/release/.../PerfHud.kt`](../app/src/release/java/com/reverie/paint/perf/PerfHud.kt:22) 的空实现顶上。
不用 `if (BuildConfig.DEBUG)` 的原因: release 目前 `isMinifyEnabled = false`, 常量分支不会被 R8 消除,
代码与文案仍会留在包里。

自查命令(应得 `release = 0 / debug ≥ 1`):

```
unzip -p app/build/outputs/apk/release/app-release.apk resources.arsc | grep -c "Performance HUD"
```

### 6.3 打开方式与取数

| 目的 | 操作 |
|---|---|
| HUD(debug 包) | 设置 → 通用 → 诊断 → 性能标尺 |
| logcat 窗口行(任意包) | `setprop debug.reverie.perf 1` 后 `adb logcat -s ReveriePerf` |
| 液化/填充热路径日志 | `setprop debug.reverie.trace 1`(默认关; 打开后 `RPC_TRACE` 生效, [`ReverieCoreInternal.h`](../app/src/main/cpp/ReverieCoreInternal.h:80)) |
| PNG 档位 A/B | `setprop debug.reverie.pngq <1..89>`(默认 70) |

**怎么用这组数字决策**: 脏比很小而重传 MB/帧 很大 ⇒ 瓶颈是整张纹理重传, tile 化收益最大; 脏比接近
100% ⇒ 先查为什么全量脏(`full` 占比); 保存"快照"段高 ⇒ 快照该异步化/增量; "编码"段高 ⇒ PNG 仍是瓶颈。

## 7. 验证流程

每次改动的机械验证(详见 [AGENTS.md](../AGENTS.md) §2/§9):

| 改动范围 | 必跑 |
|---|---|
| Kotlin | `:app:compileDebugKotlin`(+ 纯逻辑改动 `:app:testDebugUnitTest`) |
| C++ | 本机交叉编译 + `llvm-strip` + 同步 `third_party/android-native-libs` 与 `app/src/main/jniLibs`(脚本见配套的开发工具改动) |
| C++ 接口/依赖 | 导出符号集与 NEEDED 闭包比对(工具见配套的开发工具改动) |
| 出包 | `:app:assembleDebug` / `:app:assembleRelease`(`-PappIdSuffix=.beta` 可出独立包名的测试包) |

> Windows 上仓库未提交 `gradlew.bat`, 可用
> `java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <task>`,
> 并先设 `ANDROID_HOME`。

产物一致性核对(改过 C++ 后必做, 防止"源码与预编译库不一致"):

```
unzip -p <apk> lib/arm64-v8a/libreverie_jni.so | sha256sum    # 应与 third_party/... 一致
sha256sum third_party/android-native-libs/libreverie_jni.so
```

## 8. 与上游的关系

- `upstream/main`(1.3.1, `a2daae4`)**已合并**进本分支。上游这一版核心是笔刷系统(.bundle 打包导出、
  参数回跳、喷枪点频)、图层面板对齐 Procreate、画廊多选底栏、非 4 对齐画布选区斜切修复, 以及
  `9d2681f`(多核并行 PNG + 去掉 ZIP 二次压缩)。
- 冲突判定: `ReverieCoreIO.cpp` 取本分支(流式管线是上游 9d2681f 的超集); `CanvasTouchView.kt` 取本分支
  (上游删除了笔迹预测整条链路, 本分支改为把预测假线终点渐隐, 并额外带局部失效/网格裁剪/零分配);
  `libreverie_jni.so` 按合并后源码重编并随合并提交更新。
- **与上游的已知分歧**: ①保存路径 — 上游把所有图层整幅图与全部 PNG 字节一次性驻留后再并行编码,
  本分支按块流式(语义等价, 大项目峰值内存差一个数量级); ②笔迹预测 — 上游删除, 本分支保留并渐隐。
- **可回报给上游的 bug**: 液化网格精度 `qBound(4, size/8, 16)` 在 68px/78px 会给出非 2 的幂(9/10),
  触发越界闪退; 液化 `COMPOSITE_COPY` 回写假透明像素导致形变边缘白线(纯 1.3.1 可复现)。
- 历史记录: 早期 C++ 改动曾因"无法编译验证"整体撤回并存入 `cpp-render-optimizations.patch`
  (该补丁文件已不存在; 相关内容后来以 §3/§4 的形式重新落地并全部经交叉编译 + ABI 比对)。

## 9. 未做与下一步(按预期收益排序)

| 优先级 | 项 | 说明 / 前置条件 |
|---|---|---|
| 1 | **tile 化显示缓冲** | 把缓冲切成 N 块 Bitmap, 只让脏块失效重传, 直击 §2.3 的整张纹理重传。前置: 用 §6 的 `脏比 / 重传 MB/帧` 先确认收益空间 |
| 2 | **视口尺寸渲染缓冲** | 渲染缓冲改为"仅可见区域 + 缩放", 上传量按视口/文档面积下降。需 C++ 新入口 + Kotlin 绘制一起改 |
| 3 | **液化按 tile 增量 `run()`** | 只重算真正变化的格子。需碰 Krita 内部 API, 风险高 |
| 4 | **缓存非目标层的合成结果** | 液化手势期间 N 层合成降为 1 层(见 §4.6) |
| 5 | **动画关键帧转换并行** | 目前仍在写盘线程逐个 `convertToQImage`; 前提是先确认不同设备并发色彩转换安全 |
| 6 | **JNI 与拷贝面** | 复核 `renderToBuffer` 是否走 direct `ByteBuffer`; 笔画已按 ~8ms 批量 flush |
| 7 | Kotlin 侧待办 | `contentBounds()` 主线程有界阻塞 500ms 可收到 ~120ms; 参考图/调色板图片解码加降采样与字节预算; 静止时 `surface.setFrameRate` 降频 |
| — | **已论证不做** | "推拉绕开网格做 memcpy"(§4.5); 收紧 Krita 投影瓦片回收上限(要碰 `KisTiledDataManager`, 风险高于收益) |

### 9.1 交互态与文档态解耦 (Phase 0~5, 已定方向)

> 文件级改造清单已单独成文: [液化 V2 改造清单](LIQUIFY-V2-PLAN.md) —— 把"快照 + 操作日志 +
> 局部 GPU Warp + Dirty Tile"逐模块映射到本仓库现有文件, 并列出两条不可照搬的边界
> (画布后端无 GLES 管线; 形变真身在 Krita 网格而非 Kotlin 操作日志)。

核心判断:**预览不落盘、预览不触发投影重组合**。把"交互态"从 Krita 的文档流水线里剥离 ——
Krita 继续负责最终正确性(形变 / 采样 / 像素 / 事务 / 撤销 / 最终合成), Android 侧负责交互性能
(指针输入 / 形变预览 / 视口 / 帧节奏 / 呈现)。

| Phase | 内容 | 收益 | 风险 / 前置 |
|---|---|---|---|
| 0 | 四段打点(§4.7), 用数据定位瓶颈 | 决定后续投哪一步 | 无(纯诊断) |
| 1 | 手势期间 **Document 冻结**: 收笔开始时把"非目标图层的合成结果"缓存一次; 拖拽只更新"液化预览 Overlay", 不写 `KisPaintDevice`、不触发投影 | 砍掉"回写 + 合成"(通常最大的一环) | 中: 预览要正确复现 图层不透明度 / 混合模式 / Alpha 锁 / 选区 |
| 2 | **分 3 步走, 每步可独立回退**: **2A-1** 网格导出与可视化(§4.8, 已完成) → **2A-2** CPU 低分辨率 Preview(§4.9, 真机已验收) → **2B** AGSL `RuntimeShader` 替换 CPU warp(§4.10, 已落地待真机; API 33+ 才启用) | 预览成本与笔刷尺寸解耦 | 中: 采样核与坐标系统须与最终一致(允许"几何一致、采样近似"); 预览期若写文档/触发投影, 收益会被吃掉(见 Phase 1) |
| 3 | 预览分辨率随视口/缩放降级 | 计算与上传量按面积下降 | 低 |
| 4 | 抬笔后**同线程后台预计算 + 无感切换**, 用户一动即丢弃 | 消除"松手卡一下" | 低(仓库已有 `engineBusy` 让路模式可照搬) |
| 5 | tile 化 / 更深 GPU 化 | 超大画布的固定开销 | 高: 需碰 Krita tile 内部, **不建议提前做** |

铁律约束: 文档操作仍只能串行在唯一的引擎线程上, 所以 Phase 4 的"后台"指**同一引擎线程上的低优先级
任务**, 不是第二条线程; 撤销仍保持"一次手势一条 `KisTransaction`"; 每一步都要留"退回旧路径"的开关。

**当前状态**: Phase 0 已取到数据(§4.7: 形变占 96%, 回写与合成合计 ~1ms ⇒ 优先级下调); 2A-1 已落地且
2A-2 已通过真机验收(预览跟手、长拖稳定、rebase 正确、抬笔不跳变、开关 ON/OFF 最终图逐像素一致、
撤销完全回退、多层独立正确); 2B(§4.10)已落地, 待真机对比"CPU 预览 vs AGSL 预览"。**默认路径
(开关关闭)与上游逐像素一致, 因此这些原型即使不复用, 也不会给正式版带来风险。**

## 10. 附录: 真机对照包

`D:\Projects\ReveriePaint-debug-apks\`(仅供 A/B, 不入库):

| 包 | 内容 | 结论 |
|---|---|---|
| `1-pure-131-debug` | 纯上游 1.3.1 | 复现液化白线 ⇒ 上游既有问题 |
| `2-merged-current-debug` | 合并后基线 | — |
| `3-merged-liquify-serial-debug` | 液化逐层串行 | 对照并行收益 |
| `4-merged-fullrender-debug` | 每帧全量渲染 | 对照脏区增量 |
| `5-liquify-whitefix-debug` | 白线修复 | **白线消失(用户确认)** |
| `6-liquify-whitefix-perf-debug` | +累积区上限/节流 | 大笔刷仍卡 |
| `7-liquify-bigbrush-debug` | 大笔刷参数尝试 | — |
| `8-liquify-bigbrush-fast-debug` | 精度 24 | **闪退**(非 2 的幂) |
| `9-liquify-safe-debug` | 精度回退 16 | 不崩但慢 |
| `10-liquify-stable-debug` | 68px 崩溃修复 | 68/78px 不再崩 |
| `11-liquify-grid32-debug` | 精度 32 | **用户验收版**(性能满意) |
| `12-final-release` | 与 #11 同源的 release | 与 #11 逐项一致 |
| `13-liquify-precision-floor-debug` | 精度保底(`min(档, max(16, R/8))`) | 待真机确认 132~134px 窗口 |
| `14-liquify-precision-floor-release` | 同上 release | 同上 |
| `15/16-liquify-final-{debug,release}` | 合入前终审版 | 提交前基线 |
| `17/18-liquify-prof-{debug,release}` | +液化四段打点(§4.7) | Phase 0 首次取数: 29ms = 形变 28 + 补洞 0 + 回写 1 + 合成 0 |
| `19/20-liquify-knob-{debug,release}` | +`lqprec` 强制档与 precision/cells 上报 | 用于量"单元数 → 耗时"曲线 |
| `21/22-liquify-grid-{debug,release}` | +网格导出与箭头可视化(§4.8) | 目视核对位移场几何/方向 |
| `23/24-liquify-preview-{debug,release}` | +CPU 低分辨率预览(§4.9) | **真机验收通过**(几何/手感/rebase/抬笔/撤销/多层全部正确) |
| `25/26-liquify-gpu-preview-{debug,release}` | +AGSL 预览(§4.10) | 待真机对比 CPU 预览的清晰度与开销 |
| `27/28-liquify-latest-wins-{debug,release}` | +交互调度 latest-state-wins(§4.11) | 待真机看 `liquify.input/flush/dabs` 的合并倍率与手感 |
| `29-lq-p1-agsl-chase2-debug` | 档位 1: AGSL + 2 步/帧 | **无数据线首选**: 目标形态 |
| `30-lq-p2-cpu-chase2-debug` | 档位 2: CPU 预览 + 2 步/帧 | 2A-2 对照 |
| `31-lq-p3-agsl-nochase-debug` | 档位 3: AGSL + 不合并 | 调度对照 |

### 旧编号对照(供检索引擎/历史提交使用)

| 旧编号 | 新位置 |
|---|---|
| §1~§3 首轮结论与现状 | §2(首轮结论已并入各实现说明) |
| §4 第二轨 C++ | §2.1 |
| §5 待验证方向 | §9 |
| §5.5 / §5.5.1 液化专项 | §4 |
| §5.6 内存与调度 | §5.1 |
| §5.7 C++ 撤回 | §8 末条 |
| §5.8 零分配 | §2.4 |
| §5.9 / §5.10 保存·启动旁路 | §3.5 |
| §5.11 Kotlin 待办 | §9 |
| §5.12 C++ 落地(PNG 档位/并行编码/液化并行/日志) | §3.2 / §3.3 / §4.4 |
| §5.13 流式保存 | §3.1 |
| §5.14 性能标尺 | §6 |
| §5.15 合并记录 | §8 |
| §5.16 液化白线/闪退/大笔刷 | §4 |

## 11. 回归自检清单

勾选项为"已由代码/构建/真机确认"; 未勾选的需要真机验证。

**渲染与覆盖层**

- [x] 构建与单测: `:app:compileDebugKotlin`、`:app:testDebugUnitTest`(含 `CanvasViewTransform` 往返与包围盒、
      `FrameCachePolicy` 预算/淘汰)通过
- [ ] 真机: 平移/缩放/旋转画布后落笔, 笔画位置与光标环不错位
- [ ] 真机: 4x 以上放大开像素网格, 网格线与像素边界对齐, 线条数不超过上限
- [ ] 真机: 关闭光标、无预测笔迹时连续运笔, 画布边缘无残影(局部失效路径)
- [ ] 真机: 旋转画布后落笔与吸色仍落在指针位置
- [ ] 真机: 回放页 / 逐帧动画播放画面完整、无上一帧残影(该路径强制整屏重绘)
- [ ] 真机: 开启对称绘制(径向/四象限)连续运笔, 镜像笔迹与主笔迹一致、无缺段
- [ ] 真机: 自由变换 / 透视 / 网格变形拖动跟手、无闪烁

**保存与录制**

- [ ] 真机: 大项目(多图层/大画布/带动画关键帧)保存耗时明显下降, 保存期间内存不随图层数暴涨
- [ ] 真机: 保存后逐层对比像素(含**背景层默认像素**、空图层、透明图层), 混合模式与不透明度不变
- [ ] 真机: 自动保存(后台)期间继续绘画不卡顿、不出现半更新图层内容
- [ ] 真机: 保存后重开工程, 回放完整(录制事件与初始快照都没丢)
- [ ] 真机: 带动画项目保存后逐帧回放, 关键帧完整
- [ ] 真机: 带音频/视频资源的项目保存后资源可正常播放(容器压缩改直存)
- [ ] 真机: `.revp` 体积无明显膨胀(想复现旧档: `setprop debug.reverie.pngq 30`)
- [ ] 真机: 保存的文件能被旧版本 APK 打开且画面一致
- [ ] 真机: 导出 KRA 能被 Krita 桌面版打开(该路径未改动, 属回归确认)

**液化**

- [ ] 真机: 78px / 90% 推拉, 形变区**无白色细线与白色矩形轮廓**
- [ ] 真机: 68px / 78px / 200px 连续拖动均**不闪退**
- [ ] 真机: 200px / 200% 快速长拖、来回拉锯、多图层, 性能可接受且无趋势性恶化
- [ ] 真机: **130 / 132 / 134 / 135 / 140px** 推拉的手感与形变边缘(分辨率保底生效窗口; 134 与 136 之间
      有一次 4× 网格单元数跳变, 重点主观确认)
- [ ] 真机: 多图层液化结果与单图层一致, 选区 / Alpha 锁约束仍生效
- [ ] 真机: 抬笔后画面与图层最终一致(节流 flush + 投影同步合成)
- [ ] 真机(取数): 液化四段耗时 —— 30 / 60 / 120 / 200px 各画一段, 并在 1 层与 10 层文档上各重复一次,
      记录 HUD 第 4 行的 `液化 总/形变/补洞/回写/合成`(§4.7 的判读依据)
- [ ] 真机: 若多图层液化出现异常, 把 [`kLiquifyParallelTargets`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:151)
      置 false 重编, 对照逐层串行的结果与耗时
- [ ] 真机: 开 `setprop debug.reverie.liquifyPreview 1` 推拉拖动 —— 画面应跟手出现形变预览, 且**抬笔后
      与关掉开关时的最终结果逐像素一致**(预览只是近似, 提交必须精确; 不一致说明收口有漏)
- [ ] 真机: 同一次手势里把笔刷移出 rebase 边界继续拖(多次 rebase), 预览不应出现错位/撕裂
- [ ] 真机: 预览态下取消手势(撤销), 图层应完全回到手势前(预览像素不得进图层)
- [ ] 真机: 关掉 `liquifyPreviewGpu` 再画同一段, 与开启时的最终结果一致(GPU 只换采样核, 不改提交路径)
- [ ] 真机: 开启 `liquifyPreviewGpu` 后预览应比 192px 版更清晰(不再有低分辨率块感), 且拖动手感不劣于 CPU 版
- [ ] 真机: API 33 以下设备(或把开关打开后强制 shader 失败)应自动回到 CPU 预览, 不能出现"没有预览"
- [ ] 真机: 大笔刷(200px+)开启 AGSL 后不应出现花屏/错位(源裁剪纹理会到 MB 级; 超预算时应自动回退)
- [ ] 真机: `setprop debug.reverie.trace 0` 确认填充/液化不再刷 logcat, 置 1 后日志恢复

**内存与启动**

- [ ] 真机: 长动画播放不 OOM, 大画幅下帧缓存被限制在预算内(`dumpsys meminfo`)
- [ ] 真机: 连续多次自动保存, 内存不持续增长(快照缓存受预算与内存压力约束)
- [ ] 真机: 清除应用数据后首启主线程不卡(笔刷资产拷贝已移出主线程), 笔刷列表正常加载
- [ ] 真机: 保存工程后界面立即可用(列表刷新已异步)

**量测设施**

- [ ] 真机(debug 包): 设置 → 通用 → 诊断 → 性能标尺 打开后画布出现 3 行实时数据, 关闭后消失
- [ ] 真机: 正式版(`assembleRelease`)设置页**没有**标尺入口, APK 内不含相关字符串资源(命令见 §6.2)
- [ ] 真机: 标尺关闭时画布手感与开启前一致
- [ ] 真机(取数): 大画布连续绘制一段, 记录 `脏比 / 重传 MB/帧 / draw p95` 与 logcat 窗口行
- [ ] 真机(取数): 保存一次大项目, 记录 `save 总/快照/编码/写盘` 与 `PNG→体积`

