# 打开工程时画布一直显示上一个文件的画面

> 来源：用户反馈（2026-11）
> 「加载文件时候显示的一直都是上一次打开文件的样子，即使删掉不在画廊里的也一样，
> 这个 krita 原版好像也有类似问题」

## 1. 根因

画布取文档像素只有两个来源，都在 `CanvasTouchView.drawCanvas()` 里：

```kotlin
val tiles = v.displayTiles.drawable()                     // ① 512 显示瓦片网格
val bmp = if (tiles != null) null else (v.displayBitmap ?: docBitmap)?.takeIf { !it.isRecycled }  // ② 整图双缓冲
```

换文档时 [`discardTileGridForDocumentSwitch()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelTiles.kt)
只作废了 ①，**② 从来没有人放掉**：

- `loadProject()` / `startPainting()` 都不碰 `displayBitmap`；上一次有人试过在加载前 `displayBitmap = null`
  （commit 16437ef7），因为"首帧空白"被 2d528de1 撤掉了；
- 唯一的释放点是 `releaseFullCanvasBuffers()`，而它只在**瓦片网格发布成功**时才被调用；
- `goHome()` 关掉 native 文档，`deleteProject()` 删掉文件，两者也都不碰内存里的位图。

于是 `drawable()` 变成 null 之后，`drawCanvas` 退到 ② —— 那是上一份文档的像素，
整个加载过程中一直挂在画布上。而阻塞遮罩是 `Color.Black.copy(alpha = 0.45f)`
（`PaintingPage.kt`），只压暗不透光，55% 的旧画面看得清清楚楚。工程被删掉也一样：
画的是内存里的位图，与文件在不在无关。

反面症状同理：把 ② 提前清空 → `sceneW/sceneH == 0` → 整块画布（含棋盘格/阴影）都不画，
遮罩在 `after()` 里就撤了，于是"白屏闪一下"。

## 2. 改法：让绘制路径知道"换文档还没结束"

`PaintViewModel` 新增一个概念，只此一处真相：

| 成员 | 线程 | 作用 |
|---|---|---|
| `documentSwitchPending: Boolean` (`@Volatile`) | UI 线程置位，渲染线程/主线程复位 | 换文档进行中 |
| `awaitingFirstFrameAfterLoad: Boolean` (`mutableStateOf`) | 主线程 | 上面那个的 Compose 镜像（遮罩用） |

生命周期：

1. `discardTileGridForDocumentSwitch()` 置 `documentSwitchPending = true`
   —— 从这一刻起 `drawCanvas` 不画任何文档像素，画布只剩背景色；
2. 同一个 post 里（渲染线程）`clearDisplayTileGrid() + releaseFullCanvasBuffers() + displayBufferInvalid = true`
   —— 顺手把上一份文档的两份全幅位图放掉（也降低加载期内存峰值）；
3. 加载/新建成功后 `beginAwaitingFirstFrameAfterLoad()`：
   - 遮罩用**不透明**画布底色（半透明黑下面透出来的只会是残影）；
   - `scheduleRender(immediate = true)` 要一帧，然后往**同一个 Handler 队列**再 post 一个收尾
     —— 引擎操作与渲染帧同队列，所以收尾一定跑在那一帧之后。不用猜"哪一帧才是新文档的"
     （加载期间也会有帧，那帧画的还是旧文档）；
4. 收尾（主线程）清两个标记 → 画布开始画瓦片、遮罩撤掉；
5. 兜底：`FIRST_FRAME_GUARD_TIMEOUT_MS = 8s` 超时、加载失败分支、`goHome()` 都调
   `cancelAwaitingFirstFrameAfterLoad()`，不会把画布永久锁在"不画"状态。

改动点：`PaintViewModel.kt`（标志 + 使能/收尾）、`PaintViewModelTiles.kt`（作废时释放缓冲）、
`CanvasTouchView.kt`（`drawCanvas` 在 `documentSwitchPending` 期间不取整图来源）、
`PaintingPage.kt`（遮罩可见期 = `isBlockingLoading || awaitingFirstFrameAfterLoad`，
换文档期不透明）、`PaintViewModelProjects.kt`（`loadProject` / `startPainting` 收尾、
加载失败、`goHome`）。

## 3. 画廊缩略图的磁盘缓存（同一份反馈里的第二个洞）

画廊预览是**按工程名**缓存在 `cacheDir/<工程名>_thumb.png`
（`buildProjectList` 从 `.revp` 的 `thumbnail.png` 解出来），刷新条件是
`cacheThumb.lastModified() < f.lastModified()`。而 `deleteProject` / `deleteProjects` /
`renameProject` 从来没有清过这个文件，于是：

- 删掉某个工程后，同名工程再出现（恢复备份、从别处拷回同一个 `.revp`、WebDAV 还原——
  这些场景文件 mtime 常常更老），刷新条件不成立，画廊里就一直挂着**已删除的那个工程**的图；
- 改名同理：旧名的缓存留在 `cacheDir` 里，改回旧名时命中改名前的旧预览。

现在 `deleteProject` / `deleteProjects` / `renameProject` 都会调
[`dropProjectThumbCache()`](../app/src/main/java/com/reverie/paint/core/PaintViewModelProjects.kt)
清掉对应缓存（`.autosave` 后缀的两种命名都清）。

内存里还有一层 `ui/home/GalleryCard.kt` 的 `LruCache<String, Bitmap>`（键 `path:lastModified`），
键里带 mtime，删除后不可达；同路径同 mtime 的重建（备份还原）理论上仍会命中旧图，
暂未处理（需要给缓存加版本戳，收益小于风险）。

## 4. 验证

- 机械：`:app:compileDebugKotlin`、`:app:testDebugUnitTest` 全绿。
- 真机（待补）：
  1. 打开工程 A → 返回画廊 → 打开工程 B：遮罩期间画布**不应**出现 A 的画面；
     遮罩撤掉的瞬间 B 的内容就应该是完整的（大画布上遮罩会多留 0.3~1s，属预期）；
  2. 删除 A（含同名重建）后画廊不再显示 A 的预览图；
  3. 回归：`loadProject` 失败（不支持的格式）→ 遮罩撤掉、画布可交互；
     返回画廊再进画布 → 画布正常出图（`documentSwitchPending` 没有残留）；
  4. 保存 / 复制 / 笔刷预设加载这些**非换文档**的长任务仍是原来的 45% 半透明遮罩（能看到画面）。
