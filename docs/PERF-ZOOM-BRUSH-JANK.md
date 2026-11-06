# 放大画布 + 大笔刷 卡顿：测量与结论

## 怎么量（不用截图）

`scripts/perf-draw.py`：debug 接收器把标尺（PerfTrace）打开、设定缩放档与笔刷直径，
用 adb 注入滑动笔画，再把当代读数从 logcat 取回（`RP-PERFBENCH`）。

```bash
python3 scripts/perf-draw.py --serial <serial> --zooms 0,8,11 --brush 400
```

同时看三处日志：
- `ReveriePerf: win ...` —— 圆环缓冲的 draw p95 / 重传 MB/帧 / 脏比（每 ~1s 窗口）
- `ReveriePerf: TilePerf: ... composite/tiles/present` —— 渲染线程三段耗时
- `ReveriePerf: StrokePerf: ... queueWait/krita/render` —— 笔画入队与渲染延迟

## 读数（OPPO Pad，1200×900 画布，400px 笔刷）

| zoom | frame p95（修前） | frame p95（修后） |
|---|---|---|
| 1.0x | 58.3ms | 57.4ms |
| 4.3x | 208.5ms | **74.9ms** |
| 7.4x | 274.5ms | **82.7ms** |

`draw p95` 全程 0.2~0.5ms（UI 线程不忙），`composite`/`tiles` 各 1~2ms —— 卡的是
**前缓冲"真墨"这一路**：`TilePerf` 里 `present` 33ms(1x) → 92ms(4.3x)。

## 结论

1. 放大后每帧脏区在**屏幕**上的覆盖面积按 zoom² 增长，前缓冲要烤/贴的面积同步增长。
2. 已加保护：单帧屏幕面积超过 `FrontBufferFeatureFlags.maxInkAreaPx`
   （默认 1.2M 像素，`debug.reverie.fb_max_ink_kpx` 可调）就不走前缓冲，交给主画布
   按瓦片贴 —— 顺滑优先于最后一点延迟。上式即保护后的读数。
3. 剩下 75~83ms 的去向已定位到 `presentTileFrame` 进入 `presentPending` 之前：
   `tileTail pub=55~57ms present≈0 notify≈0`，且 `isIdleForInk()` 本身不阻塞，
   所以嫌疑集中在 `reclaimQueuedInkRegions` / `claimSlot` 对 `submitLock` 的等待 ——
   GL 线程在画大包时持锁，渲染线程只能等。下一步是把这段锁等待改成"忙就这一帧放弃"，
   或在持锁期间不做整包提交。

## 更正（#175 合并后）

上面这张表里的"修后"数字**不能算面积守卫的功劳**：守卫当初拿**文档**包围盒面积去比阈值
（`dirtyRects` 是位图坐标，`bmpRectToScreenBounds` 之后才是屏幕矩形），1200×900 画布上
文档包围盒最大 1.08M < 默认 1.2M，**守卫从未触发**。#175 把它改成按屏幕面积比较，
现在才会真正生效；阈值定多少仍要用 `scripts/perf-draw.py` 重新标定。

复测（main，白天温度，400px 笔刷）：zoom 1x `frame p95 16.8ms`、4.3x `8.7ms`，
期间没有任何 `inkSkip` 日志 —— 即"高倍率并不必然更慢"，之前的 275ms 是特定构建/热状态下的读数。
