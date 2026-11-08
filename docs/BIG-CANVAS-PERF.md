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

## 4. 改后

| 项 | 改前 | 改后 |
|---|---|---|
| 显示显存（65.5 MP） | ~813 MB（2 x 250 + 313） | ~250 MB（只此一份，且随脏区逐块分配） |
| 单次落笔的拷贝量 | 16 MB（整块 2048²）+ 16 MB 纹理上传 | 1 MB（512² 瓦片）+ 1 MB 上传 |
| 每帧 UI 线程工作 | 从全幅位图 memcpy 进分块 | 无（瓦片由渲染线程直接写） |

首次进入（或换文档后第一帧）仍要写满整网格：65.5 MP = 260 张瓦片 ≈ 250 MB
`readBytes` + alpha 转换，真机上是一帧内的 0.3~1 s 一次性开销，之后每帧只碰命中瓦片。

## 5. 未做（下一步收益排序）

| 优先级 | 项 | 说明 |
|---|---|---|
| 1 | **视口内按需分配瓦片（懒瓦片）** | 现在 `ensureDisplayTileGrid` 一次性把整份文档的瓦片全部分配 + 全量写入（250 MB / 首帧 0.3~1 s）。把"当前视口"（`CanvasTouchView.visibleBitmapBounds()`，已有）投给渲染线程后，只分配/只写视口内的瓦片（100% 缩放下 ≈20 张 = 20 MB），视口外的瓦片标脏留待滚动进来再写。需要小心 COW 状态机与 `publishStaging` 的"整网格写完才发布"闸门 |
| 2 | **视口 LoD 合成（缩放到 1/4~1/16）** | 适配"整幅 65 MP 缩到屏幕"这个最常见场景：此时视口覆盖整份文档，懒瓦片也帮不上。桌面 Krita 的"打开大图感觉即时"靠的就是 LoD 平面。引擎侧可以用 Krita 的 `createThumbnailDeviceOversampled` 思路（开销按**输出**像素算，不按源像素）做 1/2^k 采样，但仍需按缩放级别切网格，且要处理笔画在 LoD 层上的增量 |
| 3 | 选区浮层按文档尺寸分配 | `buildSelectionOverlayLocked()` 用 `renderW/renderH`（= 文档尺寸）向 JNI 要叠加像素：65 MP 画布上是一次 250 MB `IntArray` + 250 MB 位图（`selectionOverlayScaled`）。套索/魔棒/羽化在超大画布上就是这个量级，应改成按屏幕分辨率采样 |
| 4 | 自动保存 / 变换预览的全幅位图 | `startTransformPreview()` 仍 `createBitmap(docWidth, docHeight)`（250 MB）；`autoSaveProject()` 走引擎线程整份编码。两者都是"一次性长任务"，应改成可见区域/分块，或至少在超大画布上延后 + 提示 |
| 5 | 参考窗口整图快照 | `composeTileSnapshot()` 在参考窗口/液化需要整图时拼一份全幅位图（三缓冲，超大画布 = 3 x 250 MB）。仅在那些功能打开时才发生 |

## 6. 验证

- 机械：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（`DisplayTileGridTest` 新增
  65.5 MP / 超预算 / 预算可配三组断言）。
- 真机（待补）：
  1. 新建 6400x10240（或在 PS 导出同尺寸 PNG 后导入），等加载完成；
  2. `adb logcat -s RP-PERF` 看 `tileFrame` 行的 `prepMs/tilesMs/hits/mp`，确认每笔只命中 1~2 张瓦片；
  3. 全程 `adb shell dumpsys meminfo com.reverie.paint` 看 Graphics 峰值是否 ~250 MB 量级而非 ~800 MB；
  4. 对照实验：`setprop debug.reverie.display_tiles 0` 后重启，确认退回旧行为（应明显更卡）；
  5. 回归：4000x6000 / 4K 画布的笔画、撤销、图层显隐、动画播放、回放页、参考窗口、液化。
