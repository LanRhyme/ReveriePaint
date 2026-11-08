# 大画布渲染性能：6400x10240 为什么"基本不能动"

> 来源：用户反馈（2026-11）
> 「我给 PS 导出来的 6400＊10240 大画布导入进 krita 里就不卡，导进这软件里基本不能动了」

## 1. 现象

| | Krita 桌面 | ReveriePaint（改前） |
|---|---|---|
| 6400x10240（65.5 MP / 250 MB ARGB）| 缩放视图下流畅（LoD 平面按 1/4~1/16 合成）| 导入后基本无法操作 |
| 4000x6000（24 MP / 96 MB）| 流畅 | 可用（走 512 显示瓦片） |

关键分界线是 **100 MB**：`DisplayTileGrid.shouldUse()` 里有一条
"超过 HWUI 单图阈值（100MB）就维持整图双缓冲 + 2048 分块"的判定
（RFC 106 引入，当时的目的是"其余情况行为不变"）。6400x10240 = 250 MB 正好跨过它。

## 2. 改前的成本模型（按代码路径推算，非猜测）

`renderW/renderH` 恒等于文档尺寸（`applyRenderViewport`，注释写着 "kept at full native
document resolution"），所以 65.5 MP 文档在改前会走**整图双缓冲 + 2048 分块**：

| 项 | 字节 | 说明 |
|---|---|---|
| `frontBuffer` | 250 MB | `Bitmap.createBitmap(6400, 10240)` |
| `backBuffer` | 250 MB | 同上（双缓冲铁律） |
| `largeBitmapTiles` | 20 x 16 MB = 313 MB | `LargeBitmapTilingPolicy.TILE_DIM = 2048`，4x5 块 |
| 合计 | **~813 MB** | 仅为"显示"这一件事 |

每帧（有脏区的帧）还要在 **UI 线程** `onDraw` 里做：

1. `consumeLargeBitmapDirtyRect()` 取累计脏区；
2. 逐块 `Canvas.drawBitmap(fullBitmap, srcRect, dstRect, SRC)` 把脏区从 250MB 整图
   memcpy 进 16MB 的分块副本 —— 一个 100x100 的 dab 也要动到一整块 2048²；
3. 分块位图被改写 ⇒ HWUI 下一帧要把**整块 16 MB** 重新上传纹理（HWUI 不做局部纹理更新）。

于是单次落笔 ≈ 16 MB 内存拷贝 + 16 MB 纹理上传，再叠加 813 MB 常驻带来的
swap / GC 压力 —— 这就是"基本不能动"。

对照 4000x6000（96 MB，未过线）：走 512 瓦片网格，命中一次只 lockPixels 1 MB，
未命中的瓦片位图引用不变、纹理直接复用。两条路径的差距是两个数量级。

## 3. 改动

### 3.1 大画布也走 512 显示瓦片网格

`model/DisplayTileGrid.kt`

- 删掉 `!LargeBitmapTilingPolicy.isLargeBitmap(...)` 这条判定。它的理由是"HWUI 单图
  100MB 阈值"，而瓦片单张固定 512x512 = **1 MB**，与单图阈值无关；反过来说，整图路径
  在大画布上才是那个"要 3 份全幅副本"的方案。
- 改为按**总显存预算**判定：`displayBytes(w, h) <= budgetBytes`，
  `DEFAULT_TILE_BUDGET_BYTES = 320 MB`（≈80 MP，含用户这份 65.5 MP）。
  超预算（例如 10000x20000 = 764 MB）仍回退整图路径 —— 不赌 `createBitmap` 不 OOM。

### 3.2 OOM 熔断

`PaintViewModelTiles.ensureDisplayTileGrid()` 分配失败时不再每帧重试：
置 `displayTilesUnavailableForDoc = true`（这份文档整份退回整图路径）、
`displayBufferInvalid = true`（让整图路径下一帧做全量）、`scheduleRender(immediate = true)`。
换文档时由 `discardTileGridForDocumentSwitch()` 复位。没有这条熔断，
渲染线程会在"分配 250 MB → OOM → 回收 → 再分配"的循环里空转，比整图路径更糟。

### 3.3 A/B 开关（进程内只读一次，改完需重启）

```bash
adb shell setprop debug.reverie.display_tiles 0      # 0 = 全部画布回退整图双缓冲（对照旧行为）
adb shell setprop debug.reverie.tile_budget_mb 320   # 瓦片网格总显存预算（16~1024 MB）
adb shell setprop debug.reverie.perf 1               # 打开 PerfTrace / TilePerfLog
adb logcat -s RP-PERF
```

## 4. 真机实测（OPD2413 / Android 16 / 16GB）

同一份工程（`big6400x10240`，6400x10240 = 65.5 MP，250 MB，PSD 导入保留原始尺寸 —— 应用内
新建会被 8192 长边上限截到 6400x8192，PSD 导入不会），同一组 6 次滑动，只切换
`debug.reverie.display_tiles`：

| 指标 | 整图路径（`display_tiles=0`，改前行为） | 512 瓦片（默认，改后） |
|---|---|---|
| 描笔时渲染帧率 | **13~14 fps** | **94~114 fps** |
| 每帧纹理重传 | **250 MB/帧**（3200 MB/s 的分配+拷贝洪水） | **0.7~1.0 MB/帧** |
| 帧时间 p50 | 150 ms | **17 ms** |
| 帧时间 p90 | 250 ms | **27 ms** |
| 帧时间 p99 | 300 ms | 38 ms |
| Native Heap | 1.66 GB | **1.37 GB** |

（另一份 6400x8192 = 52 MP 工程上量过总 PSS：2.12 GB → 1.60 GB，同量级的降幅。）

瓦片路径的分段耗时（`TilePerf` 日志，每 60 帧一条）：composite 2.0~2.9 ms +
tiles 0.5~0.9 ms + present 0.4~1.6 ms，即**每帧 3~4 ms**，命中 1~4 张瓦片。

首帧（新建/打开，260 张瓦片一次性写满 65 MP）：

```
D ReveriePerf: displayTiles grid=6400x10240 tiles=260 bytes=250MB budget=320MB
I RP-PERF   : op=tileFrame ms=153 prepMs=0 tilesMs=153 hits=260 mp=65
```

即 **153 ms** 一次性开销（250 MB `readBytes` + alpha 转换），之后每帧只碰命中瓦片。
（更早一版估计 0.3~1 s，实测偏乐观了 2~6 倍。）

## 4.1 顺带量到的第二个瓶颈：延时摄影抓帧（已在引擎侧做快）

描一笔之后画布会冻结 ~90 ms，与画布大小**无关**：

```
I RP-PERF     : op=renderDocumentThumb total=95ms
W RP_Timelapse: ReverieCoreBridge.renderDocumentThumb took 92ms
```

| 文档 | 抓帧尺寸 | 输出像素 | 改前实测 | 改后实测 |
|---|---|---|---|---|
| 6400x10240 (65MP) | 675x1080 | 0.73 MP | 90~95 ms | **28~32 ms** |
| 2048x2048 | 1080x1080 | 1.17 MP | 110~117 ms | **11 ms** |

抓帧走 `renderDocumentThumb` → Krita 的 `createThumbnailUncached` → `createThumbnailDeviceInternal`，
按**输出**像素随机访问 tile，随后再建一张小 `KisPaintDevice` 走一遍 color management。
分段实测把 90 ms 拆开：随机 tile 抽样 ~35 ms + 小设备构造/写回 ~3 ms +
`convertToQImage` 的颜色管理转换 **~50 ms**。

引擎侧改法（`ReverieCore::sampleThumbnailToRgba`，不碰任何用户设置）：

1. 每条输出行只 `readBytes` 一条源行（tile 内连续），按列索引做最近邻抽样（与 Krita 的
   抽样点一致）；
2. 直接调用画布 1:1 上屏用的 `PixelAlpha::toDisplay`（NEON）把设备像素转成 **RGBA 预乘**
   —— 正是 Android Bitmap 要的格式，于是完全不需要 QImage 与小设备，也就省掉了那 50 ms
   的颜色管理转换；
3. 只对「8 bit + profile 与目标 sRGB 一致」走快路径；16 bit / 自定义 ICC 文档回退原来的
   Krita 路径，颜色管理语义不变。

用户设置（抓帧分辨率 / 最小间隔）完全按用户选择执行 —— 之前那版"按画布大小自动降质降频"
已撤回（commit `1dcc5905`）。

## 4.2 选区叠加层：大画布上会直接崩，且一次 +1.1 GB

真机（6400x10240）做一次套索，进程被 HWUI 拒绝绘制并崩溃：

```
java.lang.RuntimeException: Canvas: trying to draw too large(262144000bytes) bitmap.
  at android.graphics.RecordingCanvas.throwIfCannotDraw(RecordingCanvas.java:267)
  at com.reverie.paint.ui.painting.canvas.CanvasOverlayKt ... (CanvasOverlay.kt:1258)
--- [App Context & Canvas State] ---
Active Tool: lasso   Document: 6400 x 10240   Native Heap: Allocated=2330 MB
```

根因：选区遮罩位图一直按 `renderW/renderH`（= 文档尺寸）要像素，于是一份叠加层是**三份 250 MB**：
C++ `selectionOverlayScaled` 里的 `QVector<quint32>`、JNI 回来的 `IntArray`、Kotlin 的
ARGB_8888 位图；而 250 MB 单张位图也超过 HWUI 单次绘制的体积上限（这就是崩溃那一行）。
自由套索拖动时这条路径还是**节流重入**的 —— 每次拖动预览都重来一遍。

改法（`model/SelectionOverlayPolicy` + `PaintViewModelSelection`）：

- 叠加层按"总像素 ≤ 2.1 MP（正好容纳 1080p）、保持长宽比、只缩不放"计算：
  6400x10240 → 1146x1834，每份 ~8 MB（C++ / JNI / Bitmap 合计 ~24 MB）；
- 叠加层本来就会被 `CanvasOverlay` 拉伸到画布矩形绘制（`drawBitmap(bmp, null, sceneRect)`），
  所以缩了不影响显示；**选区蚂蚁线轮廓**改成按画布空间（`renderW/docW`）生成，不再跟着
  叠加层分辨率走 —— 否则轮廓会缩到角落；
- 同一条预算用在三处：`buildSelectionOverlayLocked`、`previewLasso`、`previewLassoSync`。

真机回归：6400x10240 上魔棒选区正常上屏（斑马纹 + 蚂蚁线与笔画边缘严丝合缝），无崩溃；
整份文档做一次选区的 Native Heap 增量从 +1.1 GB 降到 ~+0.28 GB（其中大头是 Krita 自己的
65 MB 选区掩码与撤销数据，与叠加层无关）。

## 4.3 已知未修：变换工具的预览位图也是全幅的（同样会崩）

同一份 6400x10240 文档上点「变换」并拖动，进程崩溃（`crash_java_20261108_120426.log`）：

```
java.lang.RuntimeException: Canvas: trying to draw too large(262144000bytes) bitmap.
  at android.graphics.RecordingCanvas.throwIfCannotDraw(RecordingCanvas.java:267)
  at ... CanvasTransformOverlay.kt（drawBitmap / drawBitmapMesh 那条路径）
--- [App Context & Canvas State] ---
Active Tool: transform   Document: 6400 x 10240   Native Heap: Allocated=2598 MB
```

根因与选区叠加层同一族：`startTransformPreview()` 让引擎生成一张**全幅**
`QImage::Format_RGBA8888_Premultiplied`（`ReverieCoreTransform.cpp:632` 附近 `QImage qimg(iw, ih, ...)`），
Kotlin 侧再 `Bitmap.createBitmap(docWidth, docHeight)`（`PaintViewModelTools.kt:2052`）承接，
然后 `CanvasTransformOverlay` 用 `drawBitmap` / `drawBitmapMesh` 直接画这张 250 MB 位图 ——
超过 HWUI 单次绘制上限即抛异常。

### 修法设计（下一轮）

不能只缩位图：`CanvasTransformOverlay` 的三种模式（FREE / PERSPECTIVE / DISTORT）都默认
"位图尺寸 == 文档尺寸"（`scX = bmp.width / docWidth` 恒为 1），要缩就得把"位图空间 ↔ 文档空间"
的比例显式化：

1. 引擎 `startTransformPreview` 增加目标尺寸参数，按 `max(prevW, prevH) ≤ 4096` 等比缩小做一次
   scaled blit（`KisPainter::drawDevice` + 变换，或逐行 1/N 抽样——与缩略图快路径同一套）；
2. Kotlin 承接位图按同比例缩小（≤ 64 MB，HWUI 安全）；
3. `CanvasTransformOverlay`：
   - FREE：`drawBitmap(previewBmp, null, RectF(0,0,docW,docH), paint)` 后再叠用户矩阵；
   - PERSPECTIVE：`drawBitmapMesh(bmp, 1, 1, dstQuad 顶点)` 与位图分辨率无关，只需顶点留在文档空间；
   - DISTORT：crop 矩形按 `doc/bitmap` 比例换算，mesh 顶点直接算在文档空间（现有 `scX/scY`
     这套折中就可以删掉）。

另一条更彻底的路：预览只覆盖**变换框内**的内容（`tfState.bounds` 与图层内容包围盒的交集），
这也是桌面 Krita 的做法（transform 的 preview device 只覆盖被变换区域，不是整幅画布）。

## 5. 未做（下一步收益排序）

| 优先级 | 项 | 说明 |
|---|---|---|
| 1 | **视口内按需分配瓦片（懒瓦片）** | 现在 `ensureDisplayTileGrid` 一次性把整份文档的瓦片全部分配 + 全量写入（250 MB / 首帧 153 ms，实测）。把"当前视口"（`CanvasTouchView.visibleBitmapBounds()`，已有）投给渲染线程后，只分配/只写视口内的瓦片（100% 缩放下 ≈20 张 = 20 MB），视口外的瓦片标脏留待滚动进来再写。需要小心 COW 状态机与 `publishStaging` 的"整网格写完才发布"闸门 |
| 2 | **视口 LoD 合成（缩放到 1/4~1/16）** | 适配"整幅 65 MP 缩到屏幕"这个最常见场景：此时视口覆盖整份文档，懒瓦片也帮不上。桌面 Krita 的"打开大图感觉即时"靠的就是 LoD 平面。引擎侧可以用 Krita 的 `createThumbnailDeviceOversampled` 思路（开销按**输出**像素算，不按源像素）做 1/2^k 采样，但仍需按缩放级别切网格，且要处理笔画在 LoD 层上的增量 |
| 4 | 自动保存 / 变换预览的全幅位图 | `startTransformPreview()` 仍 `createBitmap(docWidth, docHeight)`（250 MB）；`autoSaveProject()` 走引擎线程整份编码。两者都是"一次性长任务"，应改成可见区域/分块，或至少在超大画布上延后 + 提示 |
| 5 | 参考窗口整图快照 | `composeTileSnapshot()` 在参考窗口/液化需要整图时拼一份全幅位图（三缓冲，超大画布 = 3 x 250 MB）。仅在那些功能打开时才发生 |

## 6. 验证

- 机械：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（`DisplayTileGridTest` 新增
  65.5 MP / 超预算 / 预算可配三组断言）。
- 真机（已做，OPD2413 / Android 16 / 16GB，见 §4 与 §4.1 的实测表）：
  6400x10240 PSD 导入 → 描笔 94~114 fps（旧路径 13~14 fps）、p95 27ms（旧 150ms）、
  每帧纹理 0.7~1.0 MB（旧 250 MB）、Native Heap 1.37 GB（旧 1.66 GB）；
  延时摄影抓帧 28~32 ms（旧 90~95 ms），2048x2048 上 11 ms（旧 110~117 ms）；
  另已手测：高倍缩放无瓦片接缝、撤销/重做、图层显隐、画廊缩略图。
- 复现命令：
  ```bash
  adb shell setprop debug.reverie.perf 1        # RP-PERF / ReveriePerf 日志
  adb shell setprop debug.reverie.display_tiles 0   # 退回整图双缓冲（对照）
  ```
