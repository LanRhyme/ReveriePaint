# 液化 Phase 5 · C3 设计: 常驻位移场(浮点 ping/pong)替掉 Krita 网格

> 前置: C2 已提交(`94f88ed`, tag `liquify-v2-p5c2`)—— GLES 覆盖层已能复刻 AGSL 预览,
> 但它**每帧重建 uGrid(RGBA16F 网格纹理)**, 位移场仍然来自 Krita 的 `KisLiquifyTransformWorker`,
> 也就是仍然被量化在 16px 格点上 ⇒ 台阶感与"窗口重锚定接缝"仍在。
>
> C3 的目标(**正是参考实现 HuaShiJiePro 的核心结构**, 见 [Phase 5 计划](LIQUIFY-PHASE5-GLES-PLAN.md) §8):
> **位移场常驻、逐 dab 局部累加、不再有窗口重锚定**。

## 1. 现状(C2 结束时)

```
引擎线程: pollLiquifyGpuPreview → LiquifyGlesPreview.update(crop, src, grid)   // 暂存引用, 不复制
UI 线程:   CanvasTouchView.drawCanvas → LiquifyGlesPreview.pushAffine(vt)      // 每帧
渲染线程: LiquifyGlesOverlay.renderLoop → awaitFrame → render(f)              // 每帧
                       └─ uploadSrc(仅 rebase) + uploadGrid(每 dab) + 呈现 pass
```

呈现 pass 的片元(`LiquifyGlesOverlay` 的 `FS`, 与 AGSL 逐行对应):

```glsl
doc  = inverse(uEx,uEy) * (frag - uOrigin);
g    = (doc - uGridOrigin) / uGridStep;
off  = texture2D(uGrid, (g + 0.5) / uGridSize).rg;      // ← 场来自 Krita 网格
gl_FragColor = texture2D(uSrc, (doc - uCropOrigin - off) / uCropSize);
```

## 2. C3-1: 常驻场 + 每 dab 一次局部 pass

### 2.1 场的表示

| 项 | 取值 |
|---|---|
| 数据结构 | **两张 RGBA16F 纹理 + 各自 FBO**(ping/pong), `.rg` = (dx, dy), 单位 = 文档像素 |
| 尺寸/原点 | 与源裁剪**逐像素对齐**: `w = cropW`, `h = cropH`, 原点 = `cropOrigin` ⇒ `场纹素 (i,j) ↔ 文档 (cropOrigin + i, cropOrigin + j)` |
| 过滤 | `LINEAR`(双线性由硬件做)+ `CLAMP_TO_EDGE`(与已验收的夹紧语义一致) |
| 初始值 | 全 0;在**源上传时**(即 rebase/新手势)清零 |
| 内存 | `2 × w × h × 8B`;4M px 裁剪 ⇒ 64MB。**上限:`FIELD_MAX_PX = 2M`**,超过则场按 1/2 分辨率分配(位移精度损失极小, 因为尾端是双线性),仍超则**回退网格路径**(开关自动置 0 并在标尺上报"场超预算") |

### 2.2 数据源: dab 参数从哪来(本设计的关键决策)

C3 不再需要"引擎算好的网格", 而需要**每个 dab 的参数**。它本来就在 Kotlin 侧:
[`CanvasTouchView.liquifyFlushNow()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt)
是液化的**唯一 JNI 提交点**, 循环里每个补点都有 `(px,py,nx,ny,mode,strength)` 与 `liquifyBrushSize`。

⇒ **不新增任何 JNI**:在同一个循环里多推一份给 GLES 层

```kotlin
LiquifyGlesPreview.pushDab(px, py, nx, ny, liquifyMode, strength.toFloat(), liquifyBrushSize)
```

`LiquifyGlesPreview` 新增:
- `pushDab(...)`: 每 7 个 float 一组追加进环形数组(容量 256), `revision++`;**只在 `requested` 时收集**;
- `Frame` 增加 `dabs: FloatArray?`(一次性取走待处理补点, 取走即清);
- `beginGesture()` 清空补点与场世代;`clear()` 同理。

> 为什么不让引擎转发:引擎的 `m_liquifyPendingDabs` 是**落盘用的**(C2 前的扩窗口方案留下),
> 语义是"尚未写回图层的补点";而 GLES 需要的是"尚未画进屏幕的补点"。两者生命周期不同,
> 混用会在 rebase 处丢帧。直接从提交点推最干净。

### 2.3 每 dab 的累加 pass

```
输入: uField(旧场, sampler2D), uDab* (中心/半径/强度/模式/位移向量), uFieldOrigin, uFieldSize
输出: 新场(ping/pong 的另一张, 绑到 FBO)
视口: 只覆盖该 dab 的场包围盒 = 中心 ± (影响半径 / 场降采样比)  —— 局部化, 不碰整场
片元:
    doc  = uFieldOrigin + gl_FragCoord.xy * uFieldScale;    // 场纹素 -> 文档坐标
    d    = doc - uDabCenterDoc;
    r    = length(d);
    if (r > uDabRadius) { 原样输出旧场; return; }
    t    = 1.0 - smoothstep(0.0, 1.0, r / uDabRadius);      // 高斯族衰减(与 Krita 同族)
    off  = texture2D(uField, ...).rg;
    off += kernel(mode, d, t, uDabStrength, uDabDelta);      // 见 §4
    输出 vec4(off, 0, 0);
```

一次 dab = 一次 `glDrawArrays(TRIANGLE_STRIP, 0, 4)` + 一次 FBO 绑定 + 一次 ping/pong 交换。
**不做整场重建, 也不做网格量化** —— 场自身就是全分辨率的浮点数据。

### 2.4 呈现 pass 只换一行

```glsl
// 改前
off = texture2D(uGrid, (g + 0.5) / uGridSize).rg;
// 改后(C3)
off = texture2D(uField, (doc - uFieldOrigin) / uFieldSize).rg;
```
其余(仿射求逆、裁剪采样、预乘 source-over、透明清屏、局部 quad)**一行不动** —— 这样 A/B 时
差异只会来自"场怎么来的", 而不是坐标/混合口径变了。

### 2.5 开关与回退

| 开关 | 含义 |
|---|---|
| `debug.reverie.lqfield 1`(默认**关**) | 用常驻场;关掉即回到 C2 的网格路径 |
| `debug.reverie.lqfieldRes 1/2/4` | 场降采样比(默认 1;内存/带宽不够时下压) |
| 自动回退 | 场超预算 / FBO 建立失败 / 浮点纹理不可用 ⇒ 本会话置 `fieldUnavailable`, 报"已回退网格", 继续用 C2 路径 |

标尺新增一格:`场 3200x2400/1 (32MB) dab=128` 或 `场 已回退网格`。

## 3. C3-2: 拖动期与 Krita 网格解耦

C3-1 完成后, 屏幕上的形变已经完全不依赖 Krita 网格 ⇒ 可以把**拖动期的 rebase/落盘整条停掉**:

- 新增 `debug.reverie.liquifyNoRebase` 直接**默认开**(C2 之前试过、被回退的原因是"位移场重采样导致糊",
  而 C3 之后场根本不由 Krita 承载, 那个副作用**不再存在**);
- Krita 侧只保留"抬笔一次性物化":`liquifyEnd()` 按整段 `accumulatedStrokesBounds()` 落一次盘
  (仍走 `warpFromGrid`, CPU 反向采样, 与 GPU 场同数学);
- 预期:拖动期 `物化/rebase/调用` 三格读数全部归 0, 只剩 `覆盖层`。

## 4. C3-3: 核函数(按参考语义, 我们自己的高斯族)

参考实现只留给我们**类型名**(`SMEAR/PINCH/INFLATE/SHRINK/TWIRL_LEFT/TWIRL_RIGHT/RECREATE`, 见
[Phase 5 计划](LIQUIFY-PHASE5-GLES-PLAN.md) §8), 公式要自己写。以 `d` = 像素到笔心向量、
`t` = 衰减、`v` = 本 dab 的拖动向量、`s` = 强度:

| 模式 | 位移增量 |
|---|---|
| 推拉(smear/push) | `off += v * s * t` |
| 膨胀(inflate) | `off += normalize(d) * s * t * uDabRadius * 0.35` |
| 收缩(shrink) | 同上取负 |
| 顺/逆时针(twirl±) | 把 `d` 旋转 `±s * t`, 位移增量 = `rotate(d, θ) - d` |
| 重建(recreate) | `off += (0 - off) * s * t`(向"无形变"插值) |
| 锐化(sharpen) | 在采样处对源纹理做一次 3×3 反锐化, 混合系数按 `t` 加权(最后做) |

口径与现有 Krita 路径**对齐**:幅度曲线沿用 `0.2 + 0.8·min(1, dist/size)`(见
[`LiquifyPath.amplitude()`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt)), 这样 A/B 时
"同一笔该有多大形变"是一致的。

## 5. 验收清单

- [ ] 场路径与网格路径**同笔同向 A/B**:形变位置/方向一致, 允许的差别只有"台阶感";
- [ ] **台阶与窗口接缝消失**(对着同一条强弱交替的拖动来回 10 秒);
- [ ] 拖动期 `物化/rebase/调用` 三格归 0(C3-2 后);
- [ ] 抬笔后图层像素与网格路径**视觉一致**(允许亚像素差);
- [ ] 撤销/取消完全回退;多图层/选区/Alpha 锁行为不变;
- [ ] 场超预算时自动回退且标尺有读数;低端设备不崩。

## 6. 风险

| 风险 | 处置 |
|---|---|
| 128 dab × 2 pass 的 draw call 数上升 | 单 dab 一次局部 draw;若帧率掉, 把同一帧的多个 dab 合并成"一次 pass 多 dab"(uniform 数组) |
| 场内存(4M px → 64MB) | §2.1 的上限 + 降采样旋钮 |
| `pushDab` 与 `pushAffine` 的时序 | 都在同一把锁下 + `revision` 单调;渲染线程只在 `revision` 变化时工作 |
| 抬笔物化面积变大 | C3-2 保留"超上限就中途落一次盘"的分支 |
