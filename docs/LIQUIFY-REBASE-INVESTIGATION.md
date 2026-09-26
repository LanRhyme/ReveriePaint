# 液化 Phase 3 调查: 拖动中反复物化的成因、判据与最终处置

> 定位: 回答"拖动期为什么会反复 rebase/materialize、贵在哪一段", 并给出**真机上可直接读出的判据**。
> 成因已由本仓库源码与 Krita 源码双重确认, 无遗留待确认项。
> 配套: [液化 V2 改造清单](LIQUIFY-V2-PLAN.md) · [C3 常驻位移场设计](LIQUIFY-C3-FIELD-PLAN.md) ·
> [渲染优化记录](RENDER-OPTIMIZATION.md) §4。

**最终处置(先看结论)**: 成因是"局部窗口跟随笔尖"的重新锚定, 而处置方向不是"让 materialize 更快",
而是**让拖动期完全不走网格** —— 形变改由常驻 GPU 位移场逐 dab 累加, 引擎只在抬笔时收一次结果
(见 [C3 文档](LIQUIFY-C3-FIELD-PLAN.md) §3)。因此本文描述的经典网格路径已退居**回退路径**,
但它的成因、埋点与判据仍是排查该路径问题时的权威依据。

## 1. 结论速览

| 问题 | 结论 | 依据 |
|---|---|---|
| rebase 是 Krita 要求的吗? | **不是**。`KisLiquifyTransformWorker` 构造时接收固定 `rect` + `pixelPrecision`, **没有**自我 rebase 的机制 | [`ReverieCoreMiscTools.cpp:991`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:991) 构造点; worker 无 `rebase` 调用 |
| 那是谁 rebase? | **我们**。`ReverieCore::liquify()` 里的 `needRebase` 分支 | [`ReverieCoreMiscTools.cpp:925`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:925) |
| 触发条件是什么? | 只有两条: ① worker 尚未创建(首个 dab / 重建后); ② 笔尖**走出** `bounds` 内缩 `margin` 后的内框 | 同上 `:925-932` |
| 为什么必须配 `margin` 内框? | 因为 `worker->run(src,dst)` **没有 rect 参数**, 每次都跑整块 `bounds` 并拷整个补集 ⇒ `bounds` 必须贴着笔刷附近, 否则每个 dab 都在拷大区域 | [`ReverieCoreMiscTools.cpp:397`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:397)、注释 `:132-136` |
| rebase 时为什么一定要 materialize? | **只有一个原因**: 新 worker 的网格从 identity 开始, 旧 worker 里**尚未落盘**的位移会丢, 所以必须先 flush | [`ReverieCoreMiscTools.cpp:934-938`](../app/src/main/cpp/ReverieCoreMiscTools.cpp:934) |
| 是"数值稳定性"或"精度变化"迫使 rebase 吗? | **都不是**。精度只在 rebase 时按笔刷尺寸重算, 拖动中笔刷尺寸不变 ⇒ 精度恒定, 不会触发; worker 也没有精度自校正 | `:973-990` |
| 那是"网格边界不够"吗? | **是, 但"边界"是我们选的局部窗口**, 不是全画布限制。且真正的硬约束是高斯影响半径 `3.0·sigma` **大于** `bounds` 半径 `1.9·sigma` ⇒ 笔尖离开窗口后新 dab 的形变会**静默失效** | `R = max(192, 1.9·size)` `:943`; Krita `maxDist = 3.0·sigma` 见 RENDER-OPTIMIZATION §4.5 |

**一句话**: rebase 是"局部窗口跟随笔尖"的重新锚定动作, 而 materialize 只是为了不让未落盘位移丢失而付的代价。

> 现状(场通路默认开之后): 拖动期 `物化 / rebase / 调用` 三格读数全部归零, 本节描述的两条物化边
> 在拖动期都不再被走到; 只有**回退到经典网格路径**时, 下述结论与判据才重新生效。

## 2. 调用图(经典网格路径)

```text
Kotlin  CanvasTouchView.liquifyFlushNow()           ui/painting/canvas/CanvasTouchView.kt
   └─ PaintViewModel.liquify(fx,fy,tx,ty,mode,strength)   core/PaintViewModelTools.kt:994
        └─ JNI  ReverieCoreBridge.liquify()               cpp/reverie_jni_tools.cpp:228
             └─ ReverieCore::liquify()                    cpp/ReverieCoreMiscTools.cpp:900
                  ├─ needRebase?                          :925-932   ← 唯一的判定点
                  │    ├─ YES → liquifyApplyLocked(pendingDelta)  :935  ← ★ materialize
                  │    │         ├─ worker->run(src,dst)   ← ★ 全 bounds 网格形变(最大项)
                  │    │         ├─ seedTransparentFromSource()     (补洞)
                  │    │         ├─ KisPainter::bitBlt(deltaRect)   (回写, 只脏区)
                  │    │         ├─ markRegionDirty + 同步合成
                  │    │         └─ 自适应节流/预算
                  │    ├─ 重建 src = device.makeCloneFrom(bounds)  :952-953  ← 新窗口的原始像素
                  │    ├─ 重建 dst                                  :954
                  │    ├─ 重算 precision(2 的幂 + 分辨率保底)  :973-990
                  │    │   └─ new KisLiquifyTransformWorker(bounds, nullptr, precision) :991
                  │    └─ 预览模式下 liquifyPreviewCaptureLocked()  :996-999  ← 源纹理重传来源
                  ├─ 网格增量(translatePoints/scalePoints/rotatePoints) :1009-1034
                  └─ 节流触发 liquifyApplyLocked(pendingDelta)      :1074-1077  ← 另一条 materialize 边
```

`needRebase == false` 时也会 materialize, 但那条边**已被节流**(`m_liquifyApplyIntervalMs ∈ [20,64]ms`),
且 preview 模式下完全不落盘(`if (!m_liquifyPreview)`)⇒ **拖动中的物化基本全部来自 rebase 那条边**。

场通路(默认开)下, `CanvasTouchView` 的补点循环**不再调用 `liquify()`** —— 它只把补点推给 GPU 场并记本地
列表, 因此上图中两条物化边在拖动期都不存在; 抬笔才走一次 `liquifyFieldCommit()`(见 C3 文档 §3)。

## 3. 触发频率的精确量化

```cpp
const int margin = qMax(40, qRound(size * 0.7));      // 200px → 140
const int R      = qMax<int>(192, qRound(size * 1.9)); // 200px → 380
QRect bounds(tx - R, ty - R, 2*R, 2*R);                // 760 × 760  (0.58M px)
QRect inner = m_liquifyWorkerBounds.adjusted(margin, margin, -margin, -margin); // 480 × 480
if (!inner.contains(QPoint(tx, ty))) needRebase = true;
```

⇒ **笔尖只要离开锚点中心 240px(任一轴)就 rebase**。200px 笔刷高速拖动时, 一次 `ACTION_MOVE`
位移就常达 100~300px ⇒ 每几个输入事件一次 rebase, 与真机读数(约 1 秒 20~40 次物化)吻合。

⇒ 顺带解释了"单纯放大 `margin` 降低 rebase 频率"这条路**有上限** —— `margin` 逼近 `R` 时, 窗口边缘的
高斯截断会让笔尖一侧的形变先失真(因为 `3.0·sigma > 1.9·sigma`)。

## 4. 排除法: 不是这些原因

| 候选原因 | 是否成立 | 依据 |
|---|---|---|
| Krita 要求周期性重置网格以保数值稳定 | ✗ | worker 内部对每个网格点按 `lambda = exp(-0.5·(d/sigma)²)` 解析计算, 无迭代求解、无累积误差项(见 RENDER-OPTIMIZATION §4.5) |
| 精度档位在拖动中变化 | ✗ | `precision` 只在 rebase 时算, 输入是 `size`; 拖动中 `size` 不变 |
| 脏区预算(`s_liquifyDeltaBudgetPx`)触发 | ✗(但会触发另一条 flush 边) | 预算决定"何时 flush pendingDelta", 不决定 rebase |
| 网格点数量超出容器容量 | ✗ | `new KisLiquifyTransformWorker(bounds, nullptr, precision)` 一次定容; 无扩容路径 |
| 画布/图层尺寸变化 | ✗ | 拖动中不变 |

## 5. 为什么 materialize 目前"跑不掉"

新 worker 的 `originalPoints == transformedPoints`(identity)。旧 worker 网格里的位移有**两种**状态:

1. **已落盘**(`liquifyApplyLocked` 已经 bitBlt 进图层)⇒ 已进入 `device` 像素, 新窗口的 `src` 克隆会带上它 ⇒ 不需要再处理;
2. **未落盘**(还在 `m_liquifyPendingDelta` 里, 受预算/节流拖延)⇒ 只存在于旧 worker 网格 ⇒ **丢了就真的丢了**。

现在的 rebase 用一次 `liquifyApplyLocked(pendingDelta)` 把第 2 类落到图层, 于是新窗口重新克隆即可。
**代价不是"回写 delta"(那本来就小), 而是那次 `run()` 必须按整个 `bounds` 跑**(760² 网格 + 全 bounds
补集拷贝) —— 这就是单个 `形变` 段动辄数十毫秒的来源。

## 6. 可行路线与最终选择

| 路线 | 做法 | 收益 | 前提 / 风险 |
|---|---|---|---|
| A. 加大 `margin`(只调参) | 让内框更贴近 `bounds`, 减少 rebase 次数 | rebase 次数 ↓, 总物化时间 ↓ | 极其有限(§3), 且窗口边缘形变先失真 |
| B. 延迟 rebase(`deferRebase`) | 交互期只置 `rebasePending = true`, 不 flush; 抬笔一次 | 拖动中 materialize → **0** | **单独用会静默失效**: 笔尖离开窗口后新 dab 形变≈0(§1 表)。只能与 C 配对 |
| C. 逻辑 rebase / 扩展网格(`growGrid`) | 需要重新锚定时构造**更大**的 worker, 把旧网格位移场按双线性采样写进新网格, 不跑 `run()`、不落盘 | 拖动中 materialize → **0**, 且不丢形变 | API 可行(§7), 但 worker 内部另有**两个 `KisSpatialContainer` 空间索引**必须同步; 且"位移场重采样"实测会**糊化**(见状态列) |
| D. 会话级位移场自持 | 把位移场移出 worker(自己维护), 抬笔时才把它交给 Krita 做一次 materialize | 同上 | 等于把真身移出 Krita ⇒ 撤销/选区/Alpha 锁/多层语义要重做 |
| E. 碰 Krita 内部做 tile 增量 `run()` | 只重算变化格子 | `run()` 成本 ↓ | 需改 Krita 内部 API, 风险最高; RENDER-OPTIMIZATION §9 已列为"不建议提前做" |

**最终选择与结果**:

- **C 已完整实现过并实测否决**: 扩窗口 + 补点重放上线后, 位移场重采样导致**糊化**, 且每 3 秒一次的
  重建把引擎线程拖垮 ⇒ 该路径**默认关闭**(代码与开关保留, 见 C3 文档 §6)。
- **D 以另一种形态落地并被采纳**: 位移场的真身**不再由 CPU 网格承载**, 而是由覆盖层持有的
  **常驻 GPU 位移场**(RGBA16F)承担拖动期累加, 抬笔时一次性回读提交 —— 这就是 C3
  (见 [C3 文档](LIQUIFY-C3-FIELD-PLAN.md) §2/§3)。撤销/选区/Alpha 锁语义由"抬笔一次写回"沿用经典路径, 不必重做。
- A / E 不做。

## 7. 三条待确认项 —— 已用 Krita 源码回答

源码: `krita-source/libs/image/kis_liquify_transform_worker.h` 与同名 `.cpp`(两处源码树
`/opt/krita-source` 与 `~/reverie-deps/krita-source` 的该文件 **md5 一致**, 见 §10)。

| 问题 | 答案 | 影响 |
|---|---|---|
| 1. `transformedPoints()` 可写吗? | **可写**。头文件里 `QVector<QPointF>& transformedPoints();` 是**非常量**访问器(与 `const QVector<QPointF>& originalPoints() const` 形成对照) | 路线 C 的前提成立(并在实现中被使用) |
| 2. `rect` / `pixelPrecision` 的语义 | 构造参数只决定 `gridSize = GridIterationTools::calcGridSize(srcBounds, pixelPrecision)` 与初始格点(`processGrid` 填充 `originalPoints == transformedPoints`), 之后**不参与**形变运算。断言 `numPoints == gridSize.width()*gridSize.height()` 是唯一不变量 | 因此"旧网格 → 新网格"的重采样是**纯几何搬运**, 不触碰精度语义 |
| 3. `run()` 对 bounds 补集的处理 | 见 RENDER-OPTIMIZATION §4.2: 未遍历瓦片保持 `clear()` 后的透明, 所以我们才需要 `seedTransparentFromSource()` 补洞 | **新旧窗口不重叠区域**仍必须走 `run()`/补洞路径 ⇒ 网格路径无法回避抬笔那一次 |

**路线 C 的硬约束(实现时已确认)**: worker 内部除两个 `QVector<QPointF>` 之外, 还维护**两个空间索引**
`originalPointsContainer` / `transformedPointsContainer`(`KisSpatialContainer`), 所有 dab 都用
`findAllInRange(indexes, base, maxDist)` 做**邻域查询**; 各算子对 `movePoint(index, oldPos, newPos)` 的
调用点如下:

- 有的算子在 `transformedPointsContainer` 上查询(如 `translatePoints` 系, `base` 是**已形变**坐标);
- 有的算子在 `originalPointsContainer` 上查询(源码里留了 `// TODO: remove the originalPointsContainer entirely`,
  `base` 是**原始**坐标)。

⇒ **直接写 `transformedPoints` 而不同步 `transformedPointsContainer` 会让后续 dab 静默失效**(查询不到邻域,
表现为"笔刷不动了", 而不是崩溃)。

**另外两个可直接利用的 API**:

- `accumulatedStrokesBounds()`: Krita 自己记录的"被笔迹影响过的区域" ⇒ 可作为"何时该 flush/提交"的判据,
  比我们自己累积待落盘位移更权威;
- `approxChangeRect(rc)` / `approxNeedRect(rc, fullBounds)`: 给定 rect 的**结果改变区 / 需要区** ⇒
  是路线 E(按 tile 做增量 `run()`)的现成入口, 风险比"碰 Krita 内部 API"的预期低得多, 值得重新评估优先级。

## 8. 埋点读数方案

目标: 真机上直接读出"rebase 到底为什么发生、贵在哪一段"。全部走 relaxed 原子写, 不改变执行路径。

| 指标 | 采集点 | 用途 |
|---|---|---|
| `rebaseCount` | `needRebase` 分支进入时 | 与 `物化 count` 对照, 确认"物化 ≈ rebase" |
| `rebaseReason` | 分支内区分 `FirstDab` / `BrushLeftInnerBox` | 回答"为什么" |
| `rebaseFlushMs` | `liquifyApplyLocked(pendingDelta)` 前后 | 确认"贵在 run() 还是回写" |
| `rebaseCloneMs` | `makeCloneFrom` + `new KisLiquifyTransformWorker` 前后 | 重建成本 |
| `rebaseBoundsAreaPx` | 旧/新 `m_liquifyWorkerBounds` 面积 | 验证 §3 的"每次 0.58M px" |
| `rebaseOffsetPx` | 触发点相对锚点的偏移 | 验证 §3 的 240px 阈值 |
| `gridPoints` | `gridSize()` 乘积 | 与 `形变 ms` 相除得"每格成本" |
| `flushCount` / `flushMs` | 节流那条 apply 边 | 与 rebase 边分开, 避免误读 |

落点:

- C++: [`ReverieCoreMiscTools.cpp`](../app/src/main/cpp/ReverieCoreMiscTools.cpp) 的 rebase 分支 +
  `liquifyApplyLocked`; 独立入口 `ReverieCore::liquifyRebaseStats(qint64*)`
  (声明进 [`ReverieCore.h`](../app/src/main/cpp/ReverieCore.h):576)
- JNI: [`reverie_jni_tools.cpp`](../app/src/main/cpp/reverie_jni_tools.cpp) 的 `liquifyRebaseStats()`
  (**不改** 既有 `liquifyStats` 的返回长度, 避免动契约)
- Kotlin: [`ReverieCoreBridge.kt`](../app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt) +
  [`PerfTrace.kt`](../app/src/main/java/com/reverie/paint/core/PerfTrace.kt) + debug
  [`PerfHud.kt`](../app/src/debug/java/com/reverie/paint/perf/PerfHud.kt) 的调度行
- 验证: `scripts/build_native.sh` → 导出符号 / `NEEDED` 闭包比对 → 真机只跑 Test A/B(§11)

## 9. 判据清单

把"拖动期不该发生的事"做成可读数判据; 场通路已让前三项在默认配置下达成。

| 判据 | 期望读数 | 说明 |
|---|---|---|
| 拖动阶段 materialize 不增长 | HUD `物化 count` 在 drag 中恒为 0 | 场通路下拖动期无 apply |
| 拖动阶段 rebase 不触发 `run()` | `rebaseCount == 0`; 回退路径允许 `> 0` 但 `rebaseFlushMs ≈ 0` | 回退路径仍会 rebase, 只是不再 flush |
| 必要时 rebase 可以 defer | `rebasePending` 计数 > 0 且无 materialize | 与路线 B 配套 |
| 抬笔最多一次最终 materialize | 抬笔后 `物化 count` 恰 +1 | 场通路走 `liquifyFieldCommit` |
| 结果与旧路径一致 | 同笔同向 A/B 视觉一致(允许亚像素差) | 场与网格共用同一套幅度口径 |
| Reset / Undo / Redo 与旧路径一致 | 一条撤销即可整体回退 | 提交只写一次文档 |
| 预览关闭时行为与改动前逐像素一致 | 关 AGSL/GLES 预览后走经典路径 | 回退路径未改行为 |
| Native ABI / NEEDED 闭包无异常 | `verify_abi` 通过 | 新增 JNI 入口已同步 |
| Unit tests 全绿 | `testDebugUnitTest` | 纯 Kotlin 逻辑 |
| 4096² + 200px 长笔画 p95/p99 改善 | 不只看均值 | 真机回归 |

## 10. 原生工具链(已定位并验证)

宿主 Windows 侧看不到 Qt / Krita, 但它们都在 **WSL Debian** 里, 工作区经 `/mnt/d` 直达:

| 项 | 值 |
|---|---|
| WSL 发行版 | `Debian`(另有 `archlinux`) |
| 工作区 | `/mnt/d/Projects/ReveriePaint` |
| JNI 构建目录 | `/home/xuantree/reverie-deps/jni-build`(Ninja / Release, 已有 `libreverie_jni.so`) |
| NDK | `/home/xuantree/reverie-deps/android-ndk-r25c` |
| `DEPS_DIR` | `/home/xuantree/reverie-deps/deps` |
| `KRITA_SRC_DIR` | `/home/xuantree/reverie-deps/krita-source` |
| `KRITA_BIN_DIR` | `/home/xuantree/reverie-deps/krita-build` |
| `QT_ANDROID_DIR` | `/home/xuantree/reverie-deps/Qt/6.6.3/android_arm64_v8a` |
| toolchain file | `$NDK/build/cmake/android.toolchain.cmake` |

最快的 C++ 迭代(不必跑 `build_native.sh` 那两次 gradle 构建):

```bash
wsl -d Debian -e bash -lc "cd /home/xuantree/reverie-deps/jni-build && ninja -j2"
```

已实测返回 `ninja: no work to do.` ⇒ 现有 `.so` 与当前 C++ 源码一致、链路可用。

> 注意: 改完 C++ **必须把产物同步进 APK**, 否则源码与预编译库不一致(AGENTS.md §9):
> `third_party/android-native-libs/libreverie_jni.so` 与 `app/src/main/jniLibs/arm64-v8a/`
> (参考 `scripts/refresh-prebuilt-jni.sh`), 随后做导出符号 / `NEEDED` 闭包比对。

## 11. Test B 与"拖动热路径的单位成本"

真机首次读数出现 `物化 0` + `液化 56ms / 形变 52ms` 的组合, 极易被误读成"拖动中每帧 52ms"。
**该结论不成立**: HUD 第 4 行(`液化 总/形变/补洞/回写/合成`)读的是 `liquifyStats`, 即
**上一次 apply** 的四段拆分, 与统计窗口无关; `物化` 才是本窗口的 apply 次数。`物化 0` 说明这次拖动
**根本没触发 apply**(预览模式下拖动期不写文档), 那 52ms 很可能是**抬笔收口**那一次。

因此增加三处直接读数:

| 读数 | 含义 |
|---|---|
| HUD 第 4 行末尾 `(上次 N.Ns前)` | 给"上一次 apply 的拆分"标注新鲜度, 从根上消除上面这类误读 |
| `调用<n>/<ms> max<ms>` | 一次 `liquify()` 调用的次数 / 累计 µs / 峰值 µs ⇒ **拖动热路径的单位成本**(`LqrCallCount/CallUs/CallMaxUs`) |
| `覆盖层<ms> max<ms>` | UI 线程 `LiquifyGpuPreview.draw()` 的实际耗时(含位移纹理构建 + 上传);`draw p95` 看不到这部分 |

**Test B 开关**: `setprop debug.reverie.lqnodeform 1` —— C++ 在 `liquify()` 里**只跳过形变**
(不碰网格、不 apply、不生成预览), 输入 → 补点 → JNI → 失效 → 绘制的整条链路照常运行。

| 开 Test B 后 | 结论 | 下一步 |
|---|---|---|
| 明显变丝滑 | 主因在原生形变 | 走"位移场常驻"(即后来落地的 C3) |
| 仍然卡 | 形变不是主因 | 看 `覆盖层` 读数、JNI 取数(`liquifyGrid` 每帧新建数组)、invalidate/VSYNC |

Test B 下 `调用` 会降到极小(只剩计时与属性读取), 而 `覆盖层` 读数不受影响 —— 这正是它把两段分开的方式。
