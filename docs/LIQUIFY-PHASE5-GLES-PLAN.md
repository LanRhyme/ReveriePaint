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
| C2 | ✅ 上传 `uSrc`(复用 `liquifyPreviewSourcePixels()`)并用**现有网格位移场**输出, 复刻 AGSL 版画面 — 见 §4.1 | 待真机 A/B: 与 AGSL 路径视觉等价(代码已就绪, 未出包) |
| C3 | `uField` 浮点纹理 + **局部 ping/pong 累积**(每 dab 一次局部 draw), 位移场不再走 Krita 网格 | 拖动期 `ReverieCore::liquify()` 不再产生像素工作;形变边缘无 32px 块 |
| C4 | 抬笔一次性回读 + 写回图层(事务/选区/Alpha 锁与现状一致) | 抬笔 < 100ms;撤销/取消完全回退 |
| C5 | 选区掩码、多图层、低端降级 | 与现状行为一致 |

## 4.1 C2 实现记录(2026-09-26)

### 数据转发层(`core/LiquifyGlesPreview.kt`, 对应 C2-1)

复用**现有取数**(`PaintViewModel.pollLiquifyGpuPreview` 里的 `liquifyPreviewSourceMeta/Pixels/Grid`,
不加任何 JNI), 只多一个消费端。三条通道, 与 AGSL 版取数语义逐条对齐:

| 通道 | 谁写 | 内容 | 频率 |
|---|---|---|---|
| `update(crop, src, grid)` | 引擎线程 | 裁剪元信息 + 未形变源像素(rebase 时才有)+ 位移网格 | 每 dab(暂存, 不碰 GL) |
| `pushAffine(vt)` | UI 线程(每帧) | 文档→视图像素的仿射: `screen = O + docX·Ex + docY·Ey` | 每帧; 值没变则不加 revision |
| `awaitFrame(frame, timeout)` | 渲染线程 | 零分配快照; 无新状态则阻塞等待(不空烧 GPU) | 每帧 |

- **依赖方向**: 判定(`enabled`)放在 core 是因为取数侧也要读它(ui/ 不能反向被 core 引用);
  `LiquifyGlesOverlay.enabled` 只是转发同一个判定, 避免两侧不一致。
- **生命周期**: 由 `LiquifyGpuPreview.decideForGesture()` / `clear()` 转发 —— 手势开始开新手势
  (`beginGesture`, 计数归零), 抬笔/取消清暂存并让覆盖层下一帧清成全透明。
- **分流**: `CanvasTouchView.drawCanvas` 里 `LiquifyGlesPreview.requested` 为真时**不画 AGSL、
  也不上传纹理**, 只喂仿射。AGSL 侧仍会收到同一条 `update` —— 它不再建纹理, 只为"本帧文档脏区"
  基线服务, 让 `onLiquifyPreviewUpdated` 的局部失效优化继续生效。

### GLES 侧(`ui/painting/canvas/LiquifyGlesOverlay.kt`, 对应 C2-2)

与 AGSL(`LiquifyGpuPreview.SHADER_SRC`)**逐行对应**的片元着色器:

```
doc  = inverse(uEx,uEy) · (frag - uOrigin)
g    = (doc - uGridOrigin) / uGridStep          # 越界 → 全透明(与 AGSL 同)
off  = texture(uGrid, (g + 0.5) / uGridSize).rg # 硬件线性过滤 = 双线性
rgb  = texture(uSrc,  (doc - uCropOrigin - off) / uCropSize)
```

三条容易搞错的口径, 取值与理由(真机 A/B 时重点看这三处):

1. **视口与 y 轴**: 覆盖层与 `CanvasTouchView` 是同一个 Box 里的兄弟 View(都是 `fillMaxSize`),
   像素坐标系相同; GL 的 `gl_FragCoord.y` 向上、画布 y 向下 ⇒ `frag.y = viewH - gl_FragCoord.y`。
2. **纹理 v 轴**: 源裁剪与位移网格都**自上而下**上传(C++ 的 `readBytes` 行序 + JNI 直接 memcpy),
   即 `v = 0` 就是文档 top, 所以采样坐标直接用 `(doc - cropOrigin)/cropSize`, **不做翻转**;
   网格 `(g + 0.5)/gridSize` 与 `BitmapShader.eval(g + 0.5)` 的纹素中心约定一致。
3. **混合**: 源像素是 Krita 的**预乘** BGRA(引擎侧已换序成 RGBA) ⇒ 用预乘 source-over
   (`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`), 与"AGSL 返回预乘色 + hwui SRC_OVER"同义。
   表面用 `ALPHA_8` 配置 + 全透明清屏, 交给系统与画布位图合成。

网格纹理格式与 AGSL **完全同构**: `RGBA16F` + `LINEAR` + `CLAMP_TO_EDGE`(网格数组的
"原点/步长"解析抽到 [`LiquifyGridMeta`](../../app/src/main/java/com/reverie/paint/model/LiquifyGridMeta.kt),
两条路径共用同一实现, 并有单测钉口径)。

**新增/改动文件**:
- 新增 `model/LiquifyGridMeta.kt`(+ `LiquifyGridMetaTest`)、`core/LiquifyGlesPreview.kt`;
- 改 `core/LiquifyGpuPreview.kt`(判定/生命周期转发; `applyGridLocked` 改用共用解析)、
  `core/PaintViewModel.kt`(取数分流 + GLES 失败后的一次性回退)、
  `ui/painting/canvas/CanvasTouchView.kt`(绘制分流 + 喂仿射)、`ui/painting/canvas/LiquifyGlesOverlay.kt`(C2 渲染)。

**与 AGSL 路径的既有差异(有意为之, 不是遗漏)**:
- 源纹理恒为 **1:1**(不做代理降采样) ⇒ 用 `debug.reverie.lqproxy` 做 AGSL 代理对照时, 两边画质
  本来就该**不一样**; 比画质请把代理设回 100;
- 未移植"按文档脏区局部失效"(GLES 侧是独立 surface, 每帧自己重画); C3 接管这条路径后它才是瓶颈相关项;
- **门槛**: GLES 路径要求 OpenGL **ES 3.0** 上下文(16F 可线性过滤)且 API ≥ 26(`android.util.Half`);
  "是否接管绘制"另沿用主机侧绘制判定(API ≥ 33)。低端解耦与降级放在 C5。

**失败回退链(不可破的底线)**: EGL/着色器/swap 任一失败 ⇒ `LiquifyGlesPreview.markFailed()`
(不碰 JNI) ⇒ 引擎线程在下一帧 `setLiquifyPreviewHostDrawMode(0)` 并把 `failed` 置位 ⇒
本次会话后续手势不再尝试 GLES, 由引擎 CPU 预览兜底。**永远不会出现"引擎不画、GPU 也不画"。**

同一底线还有两条守门:
- **判定带存活状态**: 手势开始的判定要求覆盖层 `alive`(SurfaceTexture 就绪)。若覆盖层根本不在
  (例如 property 是运行中改的、页面没有重组), 判定不会选 GLES, 而是照旧交给引擎/AGSL;
- **手势中途销毁**: Surface 销毁时 `setAlive(false)` 会顺带清暂存 ⇒ 预览当场交回 AGSL(AGSL 那份
  暂存在 GLES 模式下也一直在喂), 引擎仍处于"主机侧绘制"判定, 于是不会留真空期。

**C2-2 真机验收清单** —— **不需要 adb**: 切换在应用内(设置 → 诊断 → 液化预览方式), 读数在标尺上。

1. **切换与就绪**: 打开"性能标尺"(同一分组)并把"液化预览方式"选成 GLES; 拖一笔, 标尺 4.5 行的
   `预览` 一格应显示 **GLES**。若显示 AGSL/引擎, 后面的 `(失败 …)` / `(已卸载)` 会给出原因
   (没有数据线时这就是报错出口); 选"自动"则回到 property/构建档位判定。
2. **坐标/纹理**: 标尺会多出一行 `gles首帧 vp=… 裁剪=…@(…) 网格=…x… 步长=(…) O=(…) Ex=(…) Ey=(…) NDC=[…]`
   (每段手势一次; 有数据线时同样会进 logcat)。同一行的 `源上传` 应恒为 **1/手势**、`网格上传 ≤ 帧数`;
   镜像/整体偏移一类的问题, 直接对着这行的 `Ex/Ey` 与 `NDC` 判读。
3. **混合/层级**: 覆盖层只在形变区出画(网格越界处透明), 画布能透过来, 面板/标尺/HUD 不被遮、不吞手势。
4. **画质等价(A/B)**: 同一笔刷、同一拖动方向, 分别在 `AGSL 覆盖层` 与 `GLES 覆盖层` 下各拖一笔对照 ——
   允许的差别只有"网格量化台阶"; **不允许**出现上下镜像 / 旋转 / 整体偏移(那就是第 2 条口径不对)。
   建包对照(`-PlqTestProfile=4`)仍可用于"完全不做 A/B 切换"的隔离实验, 但已不是必要条件。

## 5. 开关与回退

**应用内(推荐, 不需要数据线)**: 设置 → 诊断 → `液化预览方式` = 自动 / 引擎 CPU / AGSL 覆盖层 /
GLES 覆盖层。写进偏好持久化, 判定在**手势开始**发生 ⇒ 改完下一段手势生效; 标尺 4.5 行的
`预览` 一格显示实际生效的那条(以及 GLES 侧的就绪/失败状态)。

```text
setprop debug.reverie.liquifyGles 1     # 打开 Phase 5 路径(默认关)
setprop debug.reverie.liquifyGles 0     # 回到现有 AGSL 覆盖层
```
- 现有 AGSL 路径(`LiquifyGpuPreview`)**保留为 fallback**, Phase 5 期间不删。
- 每个阶段一个 tag(`liquify-v2-p5c1` …), 出问题 `git reset --hard` 即可。
- C3 之后 `warpFromGrid()` 仍保留: 它是"CPU 兜底 + 回读校验"的参照实现。
- 无数据线时用构建期档位: `-PlqTestProfile=4` = 只开 GLES 路径、AGSL 预览保持关(单变量对照);
  档位 1/2/3 仍是 AGSL 侧的对照档位(见 `LiquifyGpuPreview` 注释)。

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
