# 液化 Phase 5 · C3: 常驻位移场替代 Krita 网格

> 目标: **位移场常驻、逐 dab 局部累加、不再有窗口重锚定**; 拖动期引擎零解算, 抬笔一次性落盘。
> 状态: C3-1 / C3-2 与收尾**均已落地, 场通路默认开启**; 经典 Krita 网格路径保留为**回退路径**。
> 配套: [Phase 5 GLES 计划](LIQUIFY-PHASE5-GLES-PLAN.md) · [rebase 成因调查](LIQUIFY-REBASE-INVESTIGATION.md) ·
> [渲染优化记录](RENDER-OPTIMIZATION.md) §4。

## 1. 背景: 网格路径的两个硬伤

C2 结束时, GLES 覆盖层已经能复刻 AGSL 预览, 但它**每帧重建 `uGrid`(RGBA16F 网格纹理)**, 位移场仍来自
`KisLiquifyTransformWorker` ⇒ 仍被量化在 16px 格点上, 于是:

1. **台阶感与窗口接缝**: 形变精度受格点限制; 窗口重锚定(rebase)会在笔尖处留下一次位移场重采样接缝;
2. **拖动期成本落在 CPU**: 真机读数显示单次 `liquify()` 峰值约 40ms(≈2.4 帧), 一秒内 `rebase + 调用`
   合计耗时超过 1s ⇒ 必卡。构成是"每个 dab 在 CPU 网格上形变一次"(平均 0.65ms)与"窗口重锚定时把整窗
   重新形变 + 回写 + 同步合成"(平均 22ms、峰值 39ms), 与语言无关, 是**数据流**问题
   (细节见 [rebase 成因调查](LIQUIFY-REBASE-INVESTIGATION.md) §3/§11)。

C3 的方向因此不是"把 CPU 网格做得更快", 而是**把位移场的真身搬到 GPU 并让它常驻**: 逐 dab 局部累加,
不重锚定、不量化、拖动期不写文档。核函数与幅度口径只借鉴参考实现的**类型名**
(`SMEAR / PINCH / INFLATE / SHRINK / TWIRL_LEFT / TWIRL_RIGHT / RECREATE`, 见
[Phase 5 计划](LIQUIFY-PHASE5-GLES-PLAN.md) §8), 公式为本项目自行推导。

## 2. C3-1: 常驻场 + 每 dab 一次局部 pass

```text
引擎线程: pollLiquifyGpuPreview → LiquifyGlesPreview.update(crop, src, ...)   // 暂存引用, 不复制
UI 线程:   CanvasTouchView.drawCanvas → LiquifyGlesPreview.pushAffine(vt)     // 每帧
           CanvasTouchView.liquifyFlushNow → LiquifyGlesPreview.pushDab(...)  // 每个补点
渲染线程: LiquifyGlesOverlay.renderLoop → awaitFrame → render(f)
                       └─ uploadSrc(仅新源世代) + dab 累加 pass + 呈现 pass
```

### 2.1 场的表示与预算

| 项 | 取值 |
|---|---|
| 数据结构 | **一张 RGBA16F 纹理 + FBO**, `.rg` = (dx, dy), 单位 = 文档像素 |
| 尺寸/原点 | 与源裁剪**逐像素对齐**: `w = cropW`, `h = cropH`, 原点 = `cropOrigin` ⇒ `场纹素 (i,j) ↔ 文档 (cropOrigin + i, cropOrigin + j)` |
| 过滤 | `LINEAR`(双线性由硬件做)+ `CLAMP_TO_EDGE`(与网格路径的夹紧采样同语义, 消除形变区里的未形变补丁) |
| 初始值 | 全 0;在**新源世代**(新手势 / 引擎发布新裁剪)时清零 |
| 累加方式 | **加性混合**(`GL_ONE/GL_ONE`), 不做 ping/pong —— 见下 |
| 内存 | `w × h × 8B`;2M px 场 = 16MB。**上限 `FIELD_MAX_PX = 2M`**, 超过则按 1/2 分辨率分配(`lqfieldRes`), 仍超则**回退网格路径**(开关自动置 0 并在标尺上报"场超预算") |

设计原文是"两张纹理 ping/pong", 实现改为**单张 + 加性混合**: 位移增量只由 `gl_FragCoord` 推出的文档坐标
决定, 着色器**不采样旧场** ⇒ 既没有"同纹理读写"的反馈环, 也省掉了"每个 dab 把未绘制部分拷到另一张
纹理"那份 O(场) 搬运(全分辨率时 4M px/dab, 恰好会把局部化的收益吃光)。内存也随之从 `2×` 降到 `1×`。

### 2.2 数据源: 补点从提交点直接推给 GLES

C3 不需要"引擎算好的网格", 只需要**每个 dab 的参数** —— 它本来就在 Kotlin 侧:
[`CanvasTouchView.liquifyFlushNow()`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt)
是液化的**唯一 JNI 提交点**, 循环里每个补点都有 `(px,py,nx,ny,mode,strength)` 与 `liquifyBrushSize`。

⇒ **不新增任何 JNI**: 在同一个循环里多推一份给 GLES 层

```kotlin
LiquifyGlesPreview.pushDab(px, py, nx, ny, liquifyMode, strength.toFloat(), liquifyBrushSize)
```

[`LiquifyGlesPreview`](../app/src/main/java/com/reverie/paint/core/LiquifyGlesPreview.kt) 侧:

- `pushDab(...)`: 每 7 个 float 一组追加进环形数组(容量 256), `revision++`;**只在 `requested` 时收集**
  (场未挂载时只有一次 volatile 读, 热路径零分配);
- `Frame` 增加 `dabs: FloatArray?` 与 `dabCount`, 渲染线程真正要累加前调 `takeDabs()` **用多少取多少**;
- `beginGesture()` / `clear()` 清空补点与场世代。

> 为什么不让引擎转发: 引擎的 `m_liquifyPendingDabs` 是**落盘用的**, 语义是"尚未写回图层的补点";
> 而 GLES 需要的是"尚未画进屏幕的补点"。两者生命周期不同, 混用会在 rebase 处丢帧。
>
> 为什么是"用多少取多少"而不是"取走即清": 渲染线程可能先于"裁剪到达"醒过来(手势刚开始的帧
> `valid = false`), 若在那时就把补点取走, 它们既没地方画、又已离开缓冲 ⇒ **每段手势首笔的形变会凭空少一截**。

### 2.3 每 dab 的累加 pass

```text
输入: uField(场, sampler2D), uDab*(中心/半径/强度/模式/位移向量), uFieldOrigin, uFieldSize
输出: 同一张场(加性混合 GL_ONE/GL_ONE)
视口: 只覆盖该 dab 的场包围盒 = 中心 ± (影响半径 / 场降采样比)   —— 局部化, 不碰整场
片元:
    doc  = uFieldOrigin + gl_FragCoord.xy * uFieldScale;   // 场纹素 -> 文档坐标
    d    = doc - uDabCenterDoc;
    r    = length(d);
    if (r > uDabRadius) { 输出 0; return; }                 // 圈外不产生增量
    t    = 1.0 - smoothstep(0.0, 1.0, r / uDabRadius);     // 高斯族衰减(与网格路径同族)
    delta = kernel(mode, d, t, uDabStrength, uDabGain);    // 见 §4
    输出 vec4(delta, 0, 0);                                 // 加性累加进场
```

一次 dab = 一次 `glDrawArrays(TRIANGLE_STRIP, 0, 4)` + 一次 FBO 绑定。
**不做整场重建, 也不做网格量化** —— 场自身就是全分辨率的浮点数据。

> 影响半径取 **`2.5 × brushSize`**(`LiquifyPath.FIELD_DAB_RADIUS_RATIO`): 引擎的 dab 包围盒是 `3.2 × size`
> (含几乎无位移的高斯尾), 预览场按 2.5 倍铺开既覆盖可见形变又省填充率。真机 A/B 若发现形变范围与
> 网格路径不符, **只调这一个常数**。

### 2.4 呈现 pass 只换一行

```glsl
// 改前(网格路径)
off = texture2D(uGrid, (g + 0.5) / uGridSize).rg;
// 改后(C3)
off = texture2D(uField, (doc - uFieldOrigin) / uFieldSize).rg;
```

其余(仿射求逆、裁剪采样、预乘 source-over、透明清屏、局部 quad)**一行不动** —— 这样 A/B 时差异只会来自
"场怎么来的", 而不是坐标/混合口径变了。

### 2.5 开关与自动回退

| 开关 | 含义 |
|---|---|
| `debug.reverie.lqfield`(**三态**) | 未设 = 平台支持就**默认开**; `0` = 强制关; `1` = 强制开。设置里的"液化位移场"同理(自动 / 常驻浮点场 / Krita 网格) |
| `debug.reverie.lqfieldRes 1/2/4` | 场降采样比(默认 1;内存/带宽不够时下压) |
| 自动回退 | 场超预算 / FBO 建立失败 / 浮点纹理不可用 / 非 8bit BGRA ⇒ 本会话置 `fieldUnavailable`, 报"已回退网格"并继续用经典路径(不会"没有预览") |

标尺新增读数: `场 3200x2400/1 (16MB) dab=128` / `场 已回退网格(原因)` / `场 关`。

一个开关即进场通路: `位移场 = 常驻浮点场` 同时打开"覆盖层挂载"与"由谁画"两条判定
([`LiquifyGlesPreview.isOn`]), `CanvasView` 的挂载条件读 `vm.liquifyField` 以便页内切换即时重组。
同时**解开了 C2 遗留的一处耦合**: GLES 覆盖层不再被 AGSL 的 API≥33 门槛挡住
(它自己的门槛是 API≥26 + ES3)⇒ API 26~32 的设备也能用 GPU 预览。

## 3. C3-2: 拖动期与 Krita 网格解耦(抬笔一次性提交)

C3-1 让**屏幕**上的形变不再依赖 Krita 网格; C3-2 把**提交**这一半也搬走 —— 拖动期一个 dab 都不进引擎。

```text
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

| 入口 | 作用 |
|---|---|
| [`liquifyFieldSource()`](../app/src/main/cpp/ReverieCoreMiscTools.cpp) | 只读图层像素当源(不碰网格、不形变、不写回) |
| `liquifyFieldCommit()` | GPU 结果一次写回; 写回语义与 `liquifyApplyLocked` 逐条一致 |
| `liquifyFieldMode()` | 查询/设置场通路状态 |

关键取舍:

- **源纹理 = 整篇文档**(每段手势一次 4B/px 拷贝 + 上传, 上限沿用 `LIQUIFY_HOST_DRAW_MAX_PX` = 4M px)。
  这样场与源都不需要重锚定, 也就没有"窗口接缝"; 代价是超大画布直接回退经典路径(不冒险)。
- **提交用 GPU 结果而不是回读位移场**: 与屏幕上看到的是**同一支着色器、同一套采样口径**, 不必在 CPU 上
  再实现一遍位移场重采样, 传输量也从 8B/px 降到 4B/px。
- 拖动期 `调用 / rebase / 物化` 三格归零(标尺可验): 场通路的失效改由**补点**驱动,
  `scheduleLiquifyInvalidate` 在该路径直接让路(它拿不到网格差分, 照旧算只会退化成每帧整屏)。
- **抬笔零阻塞**: 回读 + 写回 + `liquifyEnd()` 整段搬到**引擎线程**(`liquifyFieldEndFromOverlay`),
  UI 线程不再等那最多 250ms;覆盖层在提交完成后才摘(避免"预览 → 旧像素"闪一下)。
- **撤销/取消语义不变**: 取消 = 图层从未被改写(拖动期零解算), 直接回滚事务即可。
- **录制与经典路径一致**: 场通路拖动期引擎没收到补点, 但回放走经典路径 ⇒ 补点同时写进录制流
  (`recordLiquifyDab`), 保证"同一份录制回放出同样的形变"。
- **两端硬预算**(针对高压连续测试): Kotlin 侧文档像素 > `FIELD_PATH_MAX_PX`(4M)不进场通路, 低内存设备
  (`ActivityManager.isLowRamDevice`)压到 1M, 抬笔回读矩形同样受此约束(超了走**流式重放补点**);
  C++ 侧 `liquifyFieldSource` / `liquifyFieldCommit` 都要求"手势进行中"(`m_liquifyTxnActive`),
  且提交像素与字节数都过上限闸(`LIQUIFY_FIELD_COMMIT_MAX_PX` = 4M, 与 Kotlin 侧同值)。

预期读数(逐格对照即可判定"丝滑"):

- 拖动期: `调用 0 / rebase 无 / 物化 0`, `暂存≈1 / 源上传 1 / 网格上传 0`, `场 … dab=N` 逐秒增长;
- `frame` 稳定在 ~16ms(而不是偶发 83ms 尖峰), `draw p95` 仍是 0.0x ms;
- 抬笔后: 标尺"液化"那一行出现一次提交(形变段 0, 回写 + 合成段为真实耗时), 画面换成精确结果。

## 4. 核函数与幅度口径

以 `d` = 像素到笔心向量、`t` = 衰减、`v` = 本 dab 的拖动向量、`s` = 强度:

| 模式 | 位移增量 |
|---|---|
| 推拉(smear/push) | `off += v * s * t` |
| 膨胀(inflate) | `off += normalize(d) * s * t * uDabRadius * 0.35` |
| 收缩(shrink) | 同上取负 |
| 顺/逆时针(twirl±) | 把 `d` 旋转 `±s * t`, 位移增量 = `rotate(d, θ) - d` |
| 重建(recreate) | `off += (0 - off) * s * t`(向"无形变"插值) —— **未实现**(见 §9) |
| 锐化(sharpen) | 在采样处对源纹理做一次 3×3 反锐化, 混合系数按 `t` 加权 —— **未实现**(见 §9) |

口径与网格路径**逐项对齐**: 幅度曲线沿用 `0.2 + 0.8·min(1, dist/size)`
(见 [`LiquifyPath.amplitude()`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt)),
系数与曲线在 Kotlin 侧已折进 `uDabGain`(见 `fieldDabGain()` / `fieldDabRadius()`), 着色器只负责乘上衰减,
于是"同一笔该有多大形变"在两条路径上一致。

## 5. 实现落点

全部 Kotlin / GLSL 为主, C++ 只增加三个写回入口(**不改既有 JNI 契约**)。

| 文件 | 改动 |
|---|---|
| [`model/LiquifyPath.kt`](../app/src/main/java/com/reverie/paint/model/LiquifyPath.kt) | 新增 `MODE_INFLATE/SHRINK/TWIRL_CW/TWIRL_CCW`、`fieldDabRadius()`、`fieldDabGain()`(模式系数 × `0.2+0.8·min(1,dist/size)`, 与 `applyLiquifyDab` 逐项对齐)、`FIELD_DAB_RADIUS_RATIO = 2.5` |
| [`core/LiquifyGlesPreview.kt`](../app/src/main/java/com/reverie/paint/core/LiquifyGlesPreview.kt) | `pushDab()` 补点缓冲(7 float/补点, 容量 256, 热路径零分配)、`Frame.{fieldArmed,srcGen,dabCount,dabs}`、`takeDabs()`、场开关与降采样旋钮、`FIELD_MAX_PX`、抬笔回读的 rendezvous |
| [`canvas/CanvasTouchView.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt) | `liquifyFlushNow()` 的 JNI 循环里多推一份补点; 手势开始试进场通路; 拖动期只记补点 + 推场 + 局部失效; 抬笔回读提交, 失败自动重放 |
| [`canvas/LiquifyGlesOverlay.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/LiquifyGlesOverlay.kt) | 常驻 RGBA16F 场 + FBO(整篇文档对齐, 不随 rebase 重建)、dab 累加 pass(`DAB_FS`)、呈现 pass 的场分支(`uUseField`)与绘制矩形分流、离屏提交 pass + 回读、自动回退、标尺读数 |
| [`canvas/CanvasView.kt`](../app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasView.kt) | 覆盖层挂载条件读 `vm.liquifyField`(页内切换即时重组) |
| [`core/PaintViewModelTools.kt`](../app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt) | `liquifyFieldSource()` / `liquifyFieldEnd()`(覆盖层在提交之后才摘) |
| [`core/PerfTrace.kt`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt) | 第 4.5 行新增"场"一格;标尺行位置固定、`预览`/`场` 单独成行 |
| [`ReverieCoreMiscTools.cpp`](../app/src/main/cpp/ReverieCoreMiscTools.cpp) | `liquifyFieldSource()` / `liquifyFieldCommit()`(+ 手势事务与预算闸) |
| [`ReverieCore.h`](../app/src/main/cpp/ReverieCore.h) / [`reverie_jni_tools.cpp`](../app/src/main/cpp/reverie_jni_tools.cpp) / [`ReverieCoreBridge.kt`](../app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt) | 三个新入口 + 收尾用的 `liquifyPreviewSourcePixelsInto(ByteArray)` |
| debug 源集 | 设置 → 诊断 → `液化位移场`(自动 / 常驻浮点场 / Krita 网格), 写偏好持久化(无数据线也能 A/B);标尺块并入局部失效区(`PerfHud.fillHudBounds()`) |

验证(机械): `ninja -j2`(WSL `~/reverie-deps/jni-build`)重编原生库 + `llvm-strip` 刷新
`third_party/android-native-libs/libreverie_jni.so` 与 `app/src/main/jniLibs/arm64-v8a/` ✅;
`compileDebugKotlin` ✅;`testDebugUnitTest` 17 项(含 4 项新增: dab 增益三态 + 影响半径) ✅;
`assembleDebug` ✅。`lintDebug` 在本仓库本就是红的(129 errors, 全是既有项:
`NewApi` / `LocalContextGetResourceValueCall` / `RestrictedApi` / C2 的 `HalfFloat` 等), 本轮代码**未产生任何新条目**。

## 6. 真机验证记录(标尺读数时间线)

标尺行含义: 第 1 行 `液化 总/形变/补洞/回写/合成`, 第 2 行 `预览 + 场`, 第 3 行 `泵 …`,
第 4 行 `frame/draw/重传/脏比`。

| 阶段 | 读数 | 说明 |
|---|---|---|
| `test11.jpg`(C3-1 之前) | `物化 3/90ms max33ms rebase2/29ms max36ms 因越内框 越界350px`、`调用 12/62ms max39.2ms`、`frame 33.5ms p95 15.9ms`、`暂存 0`、`场 关` | 预览走引擎 CPU;`暂存 0` 说明本轮**不构成**"场 vs 网格"A/B;卡点确认在提交路径的原生尖峰 |
| `test12.jpg` | `物化24/70ms`、`rebase23/517ms max39ms`、`调用90/550ms max40.4ms`、`frame 83.6ms p95 16.1ms` | 引擎线程一秒干了 >1s 的活 ⇒ 必卡;帧循环与 UI 绘制均正常 |
| `test_13.jpg` | `预览 引擎 场 --` | **用户还没走进场通路** ⇒ 为避免"开关看着没反应", 补上"一个开关即进场通路 / 抬笔零阻塞 / 录制一致"三件事 |
| `test_14`(高压连续测试) | `frame 8.0ms p95 16.0ms`, 但**仍是 `预览 引擎`** | 经典 CPU 路径在真机上有"大笔刷极端时闪退"的历史(200px 连续拖动时它一秒要在引擎线程上干 1.07s 的活) ⇒ 场通路改为**默认开**, 把这条有崩溃史的路径从高压场景里挪开 |
| 默认开之后 | 拖动期 `调用 0 / rebase 无 / 物化 0`, `场 … dab=N` 逐秒增长;抬笔出现唯一一次提交 | 由维护者验证效果并确认无闪退 |

标尺可读性修复(否则 A/B 无法判读 —— 截图上整行文字缺头/缺尾):

1. **行位置固定**: `液化` / `预览+场` / `泵` 三行改为**始终输出**, 无数据时 `--` 占位(行数随数据出现/消失,
   而"局部失效重绘"只刷新损坏区 ⇒ 屏幕上会留下两代文本拼接的残迹);
2. **`预览` + `场` 单独成一行且短**(原先是长行尾部, 长行尾部在局部失效里最先被截断);
3. **标尺块并进失效区**: `PerfHud.fillHudBounds()` → `CanvasTouchView.invalidateFromRender()` /
   `scheduleLiquifyInvalidate()`, 每次局部重绘都把整块标尺刷新一遍;正式版该函数恒返回 false(零成本),
   标尺关闭时同样零成本;
4. `frame` 行移到"GLES 首帧快照"之前, 不会被快照行挤走。

## 7. 验收清单

- [x] 场路径与网格路径**同笔同向 A/B**: 形变位置/方向一致, 允许的差别只有"台阶感"
- [x] **台阶与窗口接缝消失**(对着同一条强弱交替的拖动来回 10 秒)
- [x] 拖动期 `物化/rebase/调用` 三格归 0
- [x] 抬笔后图层像素与网格路径视觉一致(允许亚像素差)
- [x] 撤销/取消完全回退;多图层/选区/Alpha 锁行为不变
- [x] 场超预算时自动回退且标尺有读数;低端设备不崩(高压连续测试无闪退)
- [x] 录制回放与经典路径一致(补点同步进录制流)
- [x] 机械验证: `compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug` 全绿, lint 无新增条目

## 8. 风险与已消除的残余

| 风险 | 处置 / 现状 |
|---|---|
| 128 dab × 2 pass 的 draw call 数上升 | 单 dab 一次局部 draw;若帧率掉, 把同一帧的多个 dab 合并成"一次 pass 多 dab"(uniform 数组)。当前真机 `frame` 稳定 |
| 场内存(4M px → 32MB) | `FIELD_MAX_PX = 2M` 上限 + `lqfieldRes` 降采样旋钮 + 超预算自动回退 |
| `pushDab` 与 `pushAffine` 的时序 | 都在同一把锁下 + `revision` 单调;渲染线程只在 `revision` 变化时工作 |
| 抬笔物化面积变大 | 保留"超上限就中途落一次盘 / 流式重放补点"的分支 |
| 手势**中途** rebase 造成的"笔尖处重了一下" | **已消除**: 成因是"早于 rebase 的补点在场重建后又被累加一次", 而场通路把拖动期的 rebase 整条停掉后, 这个窗口自然消失 |

## 9. 收尾与明确不做

### 9.1 收尾: 源像素缓冲复用

新增 `liquifyPreviewSourcePixelsInto(ByteArray)`(直接写进 Java 数组, 不再多一次 `QVector` 拷贝),
Kotlin 侧**两块轮换**复用:

- 以前每段手势都新分配一份 `cropW*cropH*4`(4M px 文档 = 16MB), 连续压测时是持续的大对象垃圾;
  现在这块只在尺寸变化时重建;
- 为什么是两块而不是一块: 上一段手势的数组可能还被覆盖层暂存着(等渲染线程消费), 复用同一块会让
  那张源纹理读到"新一段手势的像素"(画面错位); 轮换一块就够避开这个时间窗。

### 9.2 明确不做(以及为什么) —— 液化线到此收口

| 项 | 原因 |
|---|---|
| 场/源"按需扩块"(省掉每段手势的整篇上传) | 源一变大就会重建场(场按源裁剪对齐)⇒ 等于把已累加的形变清零; 要做必须先解耦"场世代 / 源世代", 属结构性改动。收益(省一次上传)与风险不成比例 |
| 抬笔提交的投影合成改异步 | 提交已复用经典写回语义(选区 / Alpha 锁 / 脏区 / 同步合成); 改异步要动 C++ 写回时序, 收益只在大画布抬笔那一下 |
| `recreate` / `sharpen` 两种核 | 引擎 `applyLiquifyDab` 没有这两种模式; 只在场通路支持会让"场 vs 网格"两路结果不一致, 需要引擎 + 面板一起动 |
| 选区掩码上 GPU(预览里显示选区裁切) | 提交侧已严格按选区约束(与经典路径一致), 缺的只是预览的视觉提示, 属独立小特性 |
| 崩溃前"最后操作"面包屑 | 诊断基础设施, 跨领域, 不属于液化分支 |
| 画布带宽线(tile 化显示缓冲 / 视口尺寸渲染缓冲) | 跨领域(渲染缓冲 + 失效路径), 收益最大但要单独立项再动 |
