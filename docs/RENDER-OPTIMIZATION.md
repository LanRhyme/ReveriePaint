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

口径修正: `flip x/s` 加了 **500ms 窗口门槛** —— 否则"窗口刚重置 + 1 次翻转"会被折算成 140/s 这类不可能
读数(真机见过, 会误导判断), 窗口不足时显示 `--`。

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
- [ ] 真机: 若多图层液化出现异常, 把 [`kLiquifyParallelTargets`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:151)
      置 false 重编, 对照逐层串行的结果与耗时
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
