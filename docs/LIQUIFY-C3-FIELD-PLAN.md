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

## 7. C3-1 实现注记 (已落地)

代码落点(全部 Kotlin/GLSL, **不改 C++、不加 JNI**):

| 文件 | 改动 |
|---|---|
| [`model/LiquifyPath.kt`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt) | 新增 `MODE_INFLATE/SHRINK/TWIRL_CW/TWIRL_CCW`、`fieldDabRadius()`、`fieldDabGain()`(模式系数 × `0.2+0.8·min(1,dist/size)`, 与 `applyLiquifyDab` 逐项对齐) |
| [`core/LiquifyGlesPreview.kt`](../app/src/main/java/com/reverie/paint/core/LiquifyGlesPreview.kt) | 新增 `pushDab()` 补点缓冲(7 float/补点, 容量 256, 热路径零分配)、`Frame.{fieldArmed,srcGen,dabCount,dabs}`、`takeDabs()`、场开关与降采样旋钮、`FIELD_MAX_PX = 2M` |
| [`canvas/CanvasTouchView.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt) | `liquifyFlushNow()` 的 JNI 循环里多推一份补点(场未 armed 时只有一次 volatile 读) |
| [`canvas/LiquifyGlesOverlay.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/LiquifyGlesOverlay.kt) | 常驻 RGBA16F 场 + FBO、dab 累加 pass(`DAB_FS`)、呈现 pass 的场分支(`uUseField`)、自动回退、标尺读数 |
| [`core/PerfTrace.kt`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt) | 第 4.5 行新增"场"一格: `场 3200x2400/1 (16MB) dab=128` / `场 已回退网格(原因)` / `场 关` |
| debug 源集 | 设置 → 诊断 → `液化位移场`(自动 / 常驻浮点场 / Krita 网格), 写偏好持久化(无数据线也能 A/B) |

验证(机械): `compileDebugKotlin` ✅ / `testDebugUnitTest` 17 项(含 4 项新增: dab 增益三态 + 影响半径) ✅ /
`assembleDebug` ✅。`lintDebug` 在本仓库本就是红的(129 errors, 全是既有项:
`NewApi`/`LocalContextGetResourceValueCall`/`RestrictedApi`/C2 的 `HalfFloat` 等), 本次新增代码**未产生任何新条目**。

### 7.1 与本文档 §2 的三处实现差异(均为实测约束下的取舍)

1. **单张场 + 加性混合, 不做 ping/pong**(§2.3 原文): 位移增量只由 `gl_FragCoord` 推出的文档坐标决定,
   着色器**不采样旧场** ⇒ 用 `GL_ONE/GL_ONE` 输出增量即可, 既没有"同纹理读写"的反馈环,
   也省掉了"每个 dab 把未绘制部分拷到另一张纹理"那份 O(场) 搬运(全分辨率时 4M px/dab,
   恰好会把局部化的收益吃光)。内存也从 `2×` 降到 `1×`(2M px = 16MB)。
2. **补点"用多少取多少"**(§2.2 原文"取走即清"): 补点不在 `awaitFrame` 里取, 而是渲染线程
   真正要累加前调 `takeDabs()`。原因是渲染线程可能先于"裁剪到达"醒过来(手势刚开始的帧
   `valid = false`), 那时取走的补点既没地方画、又已经离开缓冲 ⇒ **每段手势首笔的形变会凭空少一截**。
3. **影响半径取 `2.5 × brushSize`**(§2.3 未给数值): 引擎的 dab 包围盒是 `3.2 × size`(含几乎无位移的高斯尾),
   预览场按 2.5 倍铺开既覆盖可见形变又省填充率;真机 A/B 若发现形变范围与网格路径不符, **只调这一个常数**
   (`LiquifyPath.FIELD_DAB_RADIUS_RATIO`)。

### 7.2 已知残余(留给 C3-2)

手势**中途** rebase 的那一批补点里, 早于 rebase 的几个(dab 已被物化进新源像素)会在场重建后
再累加一次 ⇒ rebase 瞬间笔尖处可能有一次轻微"重了一下"。原因: 场的清零时刻由"引擎发布新源"
决定, 而补点的推送发生在同一个 `liquifyFlushNow` 循环里,**边界在推送时无法得知**(引擎侧
`m_liquifyPendingDabs` 能给出精确边界, 但要新增 JNI 取数, 与 §2.2 的"不加 JNI"冲突)。
C3-2 把拖动期的 rebase 整条停掉后, 这个窗口自然消失。

### 7.3 下一步

- **真机 A/B**(出包后): 设置 → 诊断 → 开"性能标尺" + `预览方式 = GLES`, 再切 `液化位移场`;
  同一笔刷/同一方向各拖一笔对照, 标尺 4.5 行应出现/消失"场 …"一格。
- **C3-2**: `liquifyRebaseNoFlush()` 默认开(需重编 C++, `scripts/build_native.sh`),
  拖动期只留覆盖层;同时消掉 7.2 的残余。
- **C3-3**: 补齐 `recreate`(乘法衰减, 单张场也能做: `dst *= (1-k)`)与 `sharpen`
  (需在呈现 pass 里对源纹理做 3×3 反锐化)。

### 7.4 第一张真机标尺(`test11.jpg`, 200px 笔刷 / 强度 100% / 推拉)与标尺可读性修复

```
液化 33ms 形变 27/补洞 2/回写 1/合成 3 1层 563K px 精度16/单元2401 (上次0.0s前)
泵 输入12/推进12/补点12 物化3/90ms max33ms rebase2/29ms max36ms 重建2ms 因越内框 越界350px
   节流1/28ms 调用12/62ms max39.2ms 暂存0/网格上传0/…
frame 33.5ms p95 15.9ms n256      draw p95 0.08ms      重传 3.9MB/帧  脏比 155.6%
```

读数结论:

| 读数 | 含义 |
|---|---|
| `暂存 0` | 本次手势**不是** GPU 覆盖层在画(AGSL/GLES 都会把"暂存"打到 ≈ 补点数) ⇒ **预览 = 引擎 CPU**。这份截图里的形变与 C3-1 无关, 场也没 armed(该格应为 `场 关`) ⇒ **本轮截图不构成"场 vs 网格"A/B** |
| `rebase 2 / 29ms max36ms 因越内框 越界350px` | 引擎侧窗口重锚定: 两次 rebase, 峰值 36ms。`越界 350px` + 内框边距 `max(40, 0.7×200)=140px` ⇒ 200px 笔刷的窗口只有 ~784px(精度16/单元2401), 笔尖很快越出内框 |
| `物化 3 / 90ms max33ms`、`调用 12 / 62ms max39.2ms` | 拖动期原生侧成本: 单次 `liquify()` 峰值 **39.2ms ≈ 2.4 帧** ⇒ 顿挫来自提交路径上的尖峰, **不是**渲染 |
| `形变 27` / `563K px` | 一次 apply 的网格形变段(四段里最大头) |
| `frame p95 15.9ms` / `draw p95 0.08ms` | 帧循环本身没问题(≈63fps): 卡的是原生尖峰 |

结论与 §3 一致: **C3-1 按设计不动这些数**(它只改"预览从哪采位移"), 要让 `物化/rebase/调用` 归零必须 C3-2。

标尺可读性修复(本轮一并做掉, 否则 A/B 根本没法判读 —— 截图上整行文字缺头/缺尾):

1. **行位置固定**: `液化` / `预览+场` / `泵` 三行改为**始终输出**, 无数据时 `--` 占位。原因是行数
   会随数据出现/消失(如首次 apply 后才多出"液化"行), 而"局部失效重绘"只刷新损坏区 ⇒ 屏幕上
   留下两代文本拼接的残迹。
2. **`预览` + `场` 单独成一行且短**: 原先是 4.5 长行的尾部, 长行尾部在局部失效里最先被截断。
3. **标尺块并进失效区**: `PerfHud.fillHudBounds()` → `CanvasTouchView.invalidateFromRender()` /
   `scheduleLiquifyInvalidate()`。每次局部重绘都把整块标尺刷新一遍, 同一帧的读数不再新旧混拼;
   正式版该函数恒返回 false(零成本), 标尺关闭时同样零成本。
4. `frame` 行移到"GLES 首帧快照"之前: 快照行只在 GLES 路径出现, 排在其前面就不会被它挤走。

## 8. C3-2 实现注记: 拖动期零引擎解算 + 抬笔一次性提交(已落地)

### 8.1 第二张真机标尺(`test12.jpg`, 200px 笔刷 / 强度 90% / 推拉)说明了什么

```
液化 22ms 形变 19/补洞 1/回写 0/合成 2  1层 402K px 精度16/单元1750
预览 引擎  场 --
泵 输入25/推进25/补点39 物化24/70ms max26ms rebase23/517ms max39ms 重建15ms 因越内框 越界314px
   节流0/0ms 调用90/550ms max40.4ms 暂存…
frame 83.6ms p95 16.1ms n256
```

- **rebase 23 次 / 517ms + 调用 90 次 / 550ms ≈ 1.07s/s**: 引擎线程一秒里干了超过一秒的活 ⇒ 必卡。
  工作量的构成是"每次 dab 在 CPU 网格上形变一次"(平均 0.65ms)与"窗口重锚定时把整窗重新
  形变 + 回写 + 同步合成"(平均 22ms、峰值 39ms) —— 与语言无关, 是**数据流**问题。
- `frame p95 16.1ms` / `draw p95 0.09ms` ⇒ 帧循环与 UI 绘制都没问题, 卡的是提交路径上的原生尖峰。
- 结论: 参考实现之所以"不卡", 是因为它的形变**从不落在 CPU 网格上**。C3-1 已经把"预览"这一半
  搬到了 GPU 场; C3-2 把"提交"这一半也搬走 —— 拖动期一个 dab 都不进引擎。

### 8.2 实现(一条新通路, 与既有路径完全并存)

```
拖动期:  CanvasTouchView 的补点循环
           → 不再 v.liquify()(引擎零解算)
           → LiquifyGlesPreview.pushDab()  → GPU 场逐 dab 累加
           → 本地补点列表 + 受影响矩形(acc) → 覆盖层绘制范围 + 局部失效范围
抬笔:    覆盖层离屏渲染 bbox(与呈现同一支着色器) → glReadPixels
           → ReverieCoreBridge.liquifyFieldCommit(rect, rgba, bottomUp)
           → 引擎按既有语义一次写回(选区 / Alpha 锁 / 脏区 / 同步合成 / 一条撤销)
           → liquifyEnd() 提交事务
失败回退: 回读超时 / 覆盖层不在 / 范围为空 ⇒ 把本地补点按序重放给引擎再 materialize(形变不丢)
```

| 文件 | 改动 |
|---|---|
| `ReverieCoreMiscTools.cpp` | 新增 [`liquifyFieldSource()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp) (只读图层像素当源, 不碰网格) 与 `liquifyFieldCommit()` (GPU 结果一次写回; 写回语义与 `liquifyApplyLocked` 逐条一致) |
| `ReverieCore.h` / `reverie_jni_tools.cpp` / `ReverieCoreBridge.kt` | 三个新入口: `liquifyFieldSource` / `liquifyFieldCommit` / `liquifyFieldMode` |
| `CanvasTouchView.kt` | 手势开始试进"场通路"; 拖动期只记补点 + 推场 + 局部失效; 抬笔回读提交, 失败自动重放 |
| `LiquifyGlesOverlay.kt` | 场改为**整篇文档对齐**(不再随 rebase 重建)、呈现 pass 加"绘制矩形"分流、新增离屏提交 pass + 回读 |
| `LiquifyGlesPreview.kt` | 绘制矩形通道 + 抬笔回读的 rendezvous(渲染线程离屏渲染 + 读回) |
| `PaintViewModelTools.kt` | `liquifyFieldSource()` / `liquifyFieldEnd()`(覆盖层在提交之后才摘, 避免"预览→旧像素"闪一下) |

关键取舍:
- **源纹理 = 整篇文档**(每段手势一次 4B/px 拷贝 + 上传, 上限沿用 `LIQUIFY_HOST_DRAW_MAX_PX` = 4M px)。
  这样场与源都不需要重锚定, 也就没有"窗口接缝"; 代价是超大画布直接回退经典路径(不冒险)。
- **提交用 GPU 结果而不是回读位移场**: 与屏幕上看到的是**同一支着色器、同一套采样口径**,
  不必在 CPU 上再实现一遍位移场重采样, 传输量也从 8B/px 降到 4B/px。
- 拖动期 `调用 / rebase / 物化` 三格预期归零(标尺可验): 场通路的失效改由**补点**驱动,
  `scheduleLiquifyInvalidate` 在该路径直接让路(它拿不到网格差分, 照旧算只会退化成每帧整屏)。
- 撤销/取消语义不变: 取消 = 图层从未被改写(拖动期零解算), 直接回滚事务即可。

验证: `ninja -j2`(WSL `~/reverie-deps/jni-build`)重编原生库 ✅ + `llvm-strip` 刷新
`third_party/android-native-libs/libreverie_jni.so` 与 `app/src/main/jniLibs/arm64-v8a/` ✅ +
`compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug` ✅。真机 A/B 待出包后按 §7.3 步骤验。
