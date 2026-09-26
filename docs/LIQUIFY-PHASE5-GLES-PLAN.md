# 液化 Phase 5 计划: 自持 GLES 管线(SurfaceView + 局部 warp)

> 触发依据: 真机三轮取数已把瓶颈链走完 —— 拖动延迟修好了(Phase 3 Commit 1/2), `run()` 成本压下去了
> (Commit 3: 1459ms → 26ms), 但 **Krita CPU 网格路径仍有两处结构性上限**:
> 1. 位移场被量化在 `precision=32` 的格点上(真机上表现为"割裂成大面积像素块");
> 2. 抬笔那一次物化仍是一次 CPU 全局工作(实测单次调用 100.7ms)。
> 参照实现(画世界Pro)之所以"丝滑且锐", 是因为它的图层就是 GPU 纹理, 液化在笔刷热路径上**根本不碰 CPU 图像**。
>
> 配套: [液化 V2 改造清单](LIQUIFY-V2-PLAN.md) · [rebase 调查](LIQUIFY-REBASE-INVESTIGATION.md) ·
> [渲染优化记录](RENDER-OPTIMIZATION.md) §4

## 0. 先决条件(必须先满足再开 Phase 5)

Commit 3 引入过一个**采样 bug**: `warpFromGrid()` 只把源像素读到 `deltaRect`, 位移稍大时源落到该矩形之外 ⇒
写成 alpha=0 ⇒ 补洞逻辑又用**未形变**像素填回 ⇒ 形变区里嵌进大块"未形变补丁"。真机观感就是
"画面割裂成大面积像素块、像液化的算法都不对了"。

该 bug 已单独修掉(`warpFromGrid` 的源区域按 `|最大位移|` 外扩后再读)。
**在它修掉之前不要开 Phase 5** —— 否则等于把同一个 bug 从 CPU 搬到 GPU 上, 更难查。

## 1. 目标 / 非目标

| | 内容 |
|---|---|
| 目标 1 | 拖动期 **CPU 侧像素工作 = 0**:`ReverieCore::liquify()` 不再碰像素(只剩网格点运算或完全不碰) |
| 目标 2 | 抬笔物化 < 50ms, 或彻底后台化("松手不卡") |
| 目标 3 | 位移场以浮点纹理累积 ⇒ **不再有 32px 网格量化**, 形变边缘不再成块 |
| 非目标 | 不改 Krita 的文档/撤销/选区/多层语义(权威仍是 Krita); 不引入 Vulkan / Compute Shader; **不动整块画布的渲染方式**(棋盘格/像素网格/光标/覆盖层/参考图一律保持现状) |

## 2. 架构(ReveriePaint 约束下的最小侵入版)

现在: 画布 = `CanvasTouchView`(HWUI 位图) + `CanvasOverlay`(Compose 覆盖层) +
`LiquifyGpuPreview`(AGSL `RuntimeShader` 叠加层, 唯一 GPU 部分)。

Phase 5 只替换最后一环: 把 AGSL 叠加层换成一块**自持的 `SurfaceView` + GLES**:

```
CanvasTouchView (位图/棋盘格/网格/光标)          ← 不动
        │
        ▼
LiquifySurfaceView (新增, 透明, 置于画布之上)     ← Phase 5 的唯一新角色
   ├─ uSrc   : 手势开始时的图层裁剪纹理(未形变, 只在窗口变化时上传)
   ├─ uField : RGBA16F/HDR 位移场纹理 ×2 (ping/pong, 全分辨率)
   ├─ 每个 dab: 只在该 dab 的屏幕 bbox 内 draw 一次
   │            读 uField → 施加该 dab 的核 → 写 uField'
   └─ 每帧: 用 uField 采样 uSrc 输出到屏幕
        │
        ▼
CanvasOverlay / 面板 / 光标覆盖 (Compose)        ← 不动
```

抬笔收口: 一次性把结果交给 Krita —— 沿用现有事务与写回链路
(`KisTransaction` + `bitBlt` + 选区约束 + Alpha 锁), 但**输入像素来自 GPU 结果**而不是 `run()`。

## 3. 关键难点(按风险排序)

| # | 难点 | 处置 |
|---|---|---|
| 1 | **合成顺序/透明**: SurfaceView 是独立 layer, 与 HWUI 位图、Compose 覆盖层的叠加关系 | 阶段 C1 先只画纯色 quad, 专门验证顺序与不闪;失败即回退(开关关掉) |
| 2 | 需要自建 EGL/GLES 上下文 | 仓库已有样板: [`Mp4VideoEncoder`](../app/src/main/java/com/reverie/paint/core/export/Mp4VideoEncoder.kt)(离屏 EGL), 照抄初始化流程 |
| 3 | 手势分工: 谁收 `MotionEvent` | 保持 `CanvasTouchView` 收手势(它在最底层且已被大量逻辑依赖), SurfaceView 不接触摸(`setWillNotDraw` 语义 + 不消费事件) |
| 4 | 选区 / Alpha 锁在 GPU 的等价表达 | 用**掩码纹理**(把 `m_selection` 的 stride 传上去), 在最终输出 pass 里做 alpha 调制; 阶段 C5 |
| 5 | 回读成本与颜色空间 | 回读一次(不是每帧); BGRA/RGBA 顺序与 `warpFromGrid` 的假设一致 |
| 6 | 低端设备 / API 降级 | GLES 初始化失败或 API 太低 ⇒ 自动退回现有 AGSL 路径(仍然可用) |

## 4. 分阶段提交(每阶段可独立回退)

| 阶段 | 内容 | 验收 |
|---|---|---|
| C1a | ~~`SurfaceView`~~ 空覆盖层 + EGL + 纯色 quad | ❌ **真机黑屏**: `SurfaceView` 是**独立 surface 层**, `setZOrderMediaOverlay(true)` 让它在整个 window **之上** ⇒ 画布位图 / HUD / 面板全被盖住(连性能标尺都看不见)。**结论: 同窗口内做"夹层"用 SurfaceView 做不到。** |
| C1b | 改用 **`TextureView`**(普通 View, EGL 挂到它的 `SurfaceTexture`) | 开关打开后: ① 画布内容能透过覆盖层;② 不吞手势;③ 覆盖层/面板不被遮;关掉后逐像素与现状一致 |
| C2 | 上传 `uSrc`(复用 `liquifyPreviewSourcePixels()`)并用**现有网格位移场**输出, 复刻 AGSL 版画面 | 与 AGSL 路径 A/B 视觉等价 |
| C3 | `uField` 浮点纹理 + **局部 ping/pong 累积**(每 dab 一次局部 draw), 位移场不再走 Krita 网格 | 拖动期 `ReverieCore::liquify()` 不再产生像素工作;形变边缘无 32px 块 |
| C4 | 抬笔一次性回读 + 写回图层(事务/选区/Alpha 锁与现状一致) | 抬笔 < 100ms;撤销/取消完全回退 |
| C5 | 选区掩码、多图层、低端降级 | 与现状行为一致 |

## 5. 开关与回退

```text
setprop debug.reverie.liquifyGles 1     # 打开 Phase 5 路径(默认关)
setprop debug.reverie.liquifyGles 0     # 回到现有 AGSL 覆盖层
```
- 现有 AGSL 路径(`LiquifyGpuPreview`)**保留为 fallback**, Phase 5 期间不删。
- 每个阶段一个 tag(`liquify-v2-p5c1` …), 出问题 `git reset --hard` 即可。
- C3 之后 `warpFromGrid()` 仍保留: 它是"CPU 兜底 + 回读校验"的参照实现。

## 6. 明确不做

- 不用 Vulkan / Compute Shader(与 `AGENTS.md` §4 的"Qt 无 GUI / 单实例引擎"约束无关, 但收益不足以抵消复杂度)
- 不改画布位图/棋盘格/像素网格/覆盖层的渲染方式(Phase 5 只接管液化交互层)
- 不把 Operation Log 移到 Kotlin 当真身(见 [V2 计划 §3.2](LIQUIFY-V2-PLAN.md))

## 7. 预期的最终形态

```
手指 → CanvasTouchView(手势/补点调度, 已有) 
        → SurfaceView 局部 warp(每个 dab 一次局部 draw, ping/pong uField)
        → 屏幕(每帧一次合成)
抬笔 → 一次性回读 → KisTransaction + bitBlt(选区/Alpha 锁) → 一次撤销
```

此时拖动期 CPU ≈ 0(只剩补点调度与 uniform 更新), 抬笔一次回读;
形变质量由浮点位移场决定, 不再受网格精度限制。
