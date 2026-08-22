# 笔刷/画布对齐 Krita 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复三工具(笔刷/橡皮擦/混合涂抹)数据隔离与持久化、大笔刷不跟手、涂抹参数不下发引擎三类问题, 行为对齐 Krita。

**Architecture:** Phase A 纯 Kotlin 状态层 (ToolBrushState 扩展 per-(工具×预设) 参数记忆, 对应 Krita `savedBrushSize/savedEraserSize/savedBrushOpacity/savedEraserOpacity`, kis_paintop_settings.cpp:441-476); Phase B 输入管线 (C++ 采样间距 + Kotlin 热路径零分配 + 持久化防抖); Phase C 引擎对齐 (涂抹参数 JNI 下发至 colorsmudge settings、橡皮判定元数据覆盖、压力下限放宽)。

**Tech Stack:** Kotlin 2.4 + Jetpack Compose / C++17 NDK + Krita libs / JNI (`ReverieCoreBridge.kt` ↔ `reverie_jni_*.cpp`)

**Spec:** 根因调查结论 (对话确认):
- 问题1 大笔刷不跟手: `Stroke.cpp:207` 采样间距 max(1.5px, 直径×20%) 产生盲区; 滑条每 tick 全量 JSON 写 SharedPreferences; 热路径每样本 lambda+Triple 分配。
- 问题2 渲染: 涂抹工具无原生实现且 smudgeRate/smudgeLength 只存 JSON 不下发; 橡皮判定靠名字启发式; 压力下限 15% 直径失真。已排除合成模式逻辑 (对照 Krita effectivePaintOpCompositeOp 功能等价)。
- 问题3 隔离: 全局单份 brushSize/Opacity/Flow 字段 + ToolBrushState 无数值字段 + 同预设互通 + 空分类回退预设0继承画笔值 + PaintingPage 本地 tool 不随 currentToolId 恢复。
- 用户决策: 全部 A+B+C 都做; buildNative 可本地构建; 涂抹保持"默认 Blender 预设 + 允许自由切换任意预设", 不强制切引擎。

## Global Constraints

- **手势保护清单 (禁止触碰)**: CanvasTouchView 双指判定/150ms宽限/距离配对/isPinchMotion 阈值(625-678)、双指撤销/三指重做(739-776)、长按吸色 token(544-572)、onTouchEvent 不得 key 在 zoom/pan/rotation 上、镜像回灌门控(CanvasView.kt:299)、选区裁剪逻辑(Stroke.cpp:462-533)、变换路径、runCore 单线程串行契约、三缓冲不写 displayed/pending 位图。
- 依赖单向 `ui → core → model`; JNI 改动必须 Kotlin/C++ 两侧同步并全文检索签名引用。
- 缩进 4 空格, 行宽 ≤120; Conventional Commits 中文。
- C++ 文件已有 SPDX 头, 新增函数无需新文件。
- org.json / SharedPreferences 在 JVM 单测中是桩 (抛 RuntimeException), 相关代码不做单测, 以编译+真机回归验证。

---

### Task A1: ToolBrushState 增加 paramMemory 字段 + JSON 持久化

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:294-301`
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:552-569` (persistToolBrushStates)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:645-658` (loadBrushParams 内 tool_brush_states 解析)

- [ ] **Step 1: 扩展数据类**

```kotlin
    data class ToolBrushState(
        val presetIndex: Int = -1,
        val category: String = "全部",
        val categoryScrollIndex: Int = 0,
        val categoryScrollOffset: Int = 0,
        val presetScrollIndex: Int = 0,
        val presetScrollOffset: Int = 0,
        // Krita saved{Brush,Eraser}{Size,Opacity} 对应物:
        // 预设名 -> [size, opacity, flow], 按 (工具 × 预设) 粒度隔离记忆,
        // 使"笔刷调大的尺寸"不会在切到橡皮擦后仍然生效。
        val paramMemory: Map<String, List<Double>> = emptyMap(),
    )
```

- [ ] **Step 2: persistToolBrushStates 序列化** — 在 `o.put("pso", s.presetScrollOffset)` 之后追加:

```kotlin
                if (s.paramMemory.isNotEmpty()) {
                    val pm = org.json.JSONArray()
                    for ((n, v) in s.paramMemory) {
                        if (v.size >= 3) {
                            pm.put(org.json.JSONArray().apply {
                                put(n); put(v[0]); put(v[1]); put(v[2])
                            })
                        }
                    }
                    o.put("pm", pm)
                }
```

- [ ] **Step 3: loadBrushParams 反序列化** — 在构造 `ToolBrushState(...)` 前解析:

```kotlin
                val pmJson = o.optJSONArray("pm")
                var paramMemory: Map<String, List<Double>> = emptyMap()
                if (pmJson != null) {
                    val m = mutableMapOf<String, List<Double>>()
                    for (j in 0 until pmJson.length()) {
                        val e = pmJson.optJSONArray(j) ?: continue
                        if (e.length() >= 4) {
                            m[e.optString(0)] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                        }
                    }
                    paramMemory = m
                }
```
并在 `ToolBrushState(presetIndex = ..., ..., presetScrollOffset = ...)` 构造参数末尾加 `paramMemory = paramMemory`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit** (与本任务 A2/A3/A4 合并为一个 commit, 见 Task A4 Step 4)

---

### Task A2: 参数记忆读写助手 + 接入滑条/预设加载/工具切换

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:866-893` 附近 (updateCurrentToolBrushState 之后新增两个函数)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:895-899` (updateBrushSize), `:924-928` (updateBrushOpacity), `:193-197` (updateBrushFlow)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:838` (selectBrushPreset 的 after 块末尾)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt:297-310` (applyTool else 分支)

- [ ] **Step 1: 新增两个内部函数** (放在 updateCurrentToolBrushState 函数之后)

```kotlin
    /** Krita saved{Mode}Size 语义: 把当前 size/opacity/flow 快照进
     *  (当前工具 × 当前预设) 的记忆, 让每个工具各自记住自己的数值。 */
    internal fun PaintViewModel.rememberToolParamSnapshot() {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t != com.reverie.paint.model.Tool.BRUSH && t != com.reverie.paint.model.Tool.ERASER &&
            t != com.reverie.paint.model.Tool.SMUDGE
        ) return
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        updateCurrentToolBrushState { st ->
            st.copy(
                paramMemory = st.paramMemory.toMutableMap().apply {
                    put(name, listOf(brushSize, brushOpacity, brushFlow))
                }
            )
        }
    }

    /** 预设参数加载完成后, 用当前工具对该预设的记忆覆盖 size/opacity/flow。
     *  无记忆时保持预设参数不变 (首次使用语义)。 */
    internal fun PaintViewModel.applyToolParamMemoryOverlay() {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t != com.reverie.paint.model.Tool.BRUSH && t != com.reverie.paint.model.Tool.ERASER &&
            t != com.reverie.paint.model.Tool.SMUDGE
        ) return
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        val mem = toolBrushStates[t.id]?.paramMemory?.get(name) ?: return
        if (mem.size < 3) return
        brushSize = mem[0]
        brushOpacity = mem[1]
        brushFlow = mem[2]
        runCore(render = false) {
            ReverieCoreBridge.setBrushSize(mem[0])
            ReverieCoreBridge.setBrushOpacity(mem[1])
            ReverieCoreBridge.setBrushFlow(mem[2])
        }
    }
```

- [ ] **Step 2: 滑条写入记忆** — updateBrushSize/updateBrushOpacity/updateBrushFlow 三个函数体内在 `saveBrushParam()` 之后各加一行 `rememberToolParamSnapshot()`:

```kotlin
    internal fun PaintViewModel.updateBrushSize(v: Double) {
        brushSize = v
        saveBrushParam()
        rememberToolParamSnapshot()
        runCore(render = false) { ReverieCoreBridge.setBrushSize(v) }
    }
```
(updateBrushOpacity / updateBrushFlow 同型)

- [ ] **Step 3: selectBrushPreset 加载后叠加记忆** — after 块内, `if (saved != null) { setBrush* 批量 } else { setBrushCompositeOp }` 的整段之后 (runCore body 结束前) 追加:

```kotlin
            applyToolParamMemoryOverlay()
```
注意: applyToolParamMemoryOverlay 内部自己 runCore, 而 selectBrushPreset 的 after 是 mainHandler 回调 — 无嵌套问题。

- [ ] **Step 4: applyTool 同预设分支叠加记忆** — Tools.kt else 分支 (L297-310) 的 `if (saved != null) {...}` 整体之后追加:

```kotlin
                applyToolParamMemoryOverlay()
```

- [ ] **Step 5: 编译验证** — `./gradlew :app:compileDebugKotlin`

---

### Task A3: applyTool 空分类回退修复

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt:282-284`

- [ ] **Step 1: 分类为空不再落预设 0** — 替换:

```kotlin
            var defaultIdx = brushPresets.indexOfFirst { it.group == cat }
            if (defaultIdx < 0) defaultIdx = 0
```
为:

```kotlin
            var defaultIdx = brushPresets.indexOfFirst { it.group == cat }
            // 分类为空时保持 -1 ("未选预设"), 不回退到预设 0 —— 否则橡皮擦/混合
            // 会继承 b)_Basic-1 画笔的全部数值。引擎侧沿用已加载预设, 由
            // m_toolMode 决定擦除语义。
```
下方 `if (state.presetIndex >= 0)` 已天然跳过 -1, 无需其他改动。

- [ ] **Step 2: 编译验证** — `./gradlew :app:compileDebugKotlin`

---

### Task A4: PaintingPage 工具状态派生修复

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt:190` (声明)
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt:564` (BackHandler)
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt:672-673` (ToolRail onTool)
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt:1264-1265` (AllToolsPanel onTool)

- [ ] **Step 1: 声明改为派生值**

```kotlin
    // 当前工具: 直接从 ViewModel 派生, 保证重启恢复与引擎内自动切换
    // (如选橡皮擦分组预设反向触发 applyTool) 时 UI 高亮始终一致
    val tool = Tool.fromId(vm.currentToolId)
```

- [ ] **Step 2: 删除所有写入点** — BackHandler 分支改为:

```kotlin
                vm.currentToolId != "brush" -> vm.applyTool("brush")
```
ToolRail 与 AllToolsPanel 的 onTool 回调里删除 `tool = it` 行, 只保留 `vm.applyTool(it.id)` 及周边逻辑 (REFERENCE 分支、selectionPanelOpen、moreToolsOpen 不动)。

- [ ] **Step 3: 全文检索确认无残留写入** — Run: `rg -n "tool = " app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt`
Expected: 仅剩非 tool 变量赋值 (如 `liquifyMode =`) 或无输出。

- [ ] **Step 4: 编译 + 提交 Phase A**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "feat(tools): 笔刷/橡皮擦/混合涂抹按工具×预设分离记忆大小与不透明度并持久化"
```

---

### Task B1: C++ 采样间距修复 (大笔刷盲区根因)

**Files:**
- Modify: `app/src/main/cpp/ReverieCoreStroke.cpp:196-207`

- [ ] **Step 1: 外层过滤阈值改固定小步长** — 替换 L207 一行及上方注释 (L196-198):

```cpp
    // Krita emits dabs via KisDistanceInformation at the preset's own spacing;
    // this outer filter only gates SAMPLE emission into the batch, so keep it
    // small and fixed. The old max(1.5px, 20% diameter) left a blind zone of
    // up to 20% of the brush diameter during slow strokes (ink only appeared
    // on pen-up). Path engines keep fine 1.5px sampling for smooth contours.
    const qreal spacing = isPathEngine ? 1.5 : 0.75;
```
保留 L211-213 亚间距压力改写 (维持压力渐变), 其余不动。

- [ ] **Step 2: 构建 C++**

Run: `./scripts/build_native.sh`
Expected: 编译通过产出 libreverie_core.so (若环境变量缺失报错, 记录错误并在最终汇报注明)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/ReverieCoreStroke.cpp
git commit -m "fix(stroke): 大笔刷外层采样间距改为固定 0.75px, 消除慢速运笔盲区与收笔甩尾"
```

---

### Task B2: 输入热路径零分配 (合帧 post + 去 Triple + 稳定器降级普通字段)

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:271-272` 附近 (smoothed* 声明), `:1667-1682` 附近 (新增合帧机制)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt:179-217` (touchMove/touchEnd)

- [ ] **Step 1: smoothed* 三字段降级为普通字段** (先 `rg -n "smoothedStroke" app/src` 确认全仓库仅 Tools.kt 写、无人读 Compose state)。替换声明:

```kotlin
    // Stroke stabilizer working set: written every touch sample, read by no
    // composable — plain fields by design (zero allocation, zero recomposition).
    internal var smoothedStrokeX = 0f
    internal var smoothedStrokeY = 0f
    internal var smoothedStrokePressure = 0.0
```
(若 smoothedStrokePressure 声明在别处, 一并迁移到此处并删除原声明)

- [ ] **Step 2: PaintViewModel 新增合帧笔画样本通道** (放在 runCore 定义之后):

```kotlin
    // Coalesced stroke-sample transport: at most one runnable queued at a
    // time, latest x/y/pressure win. Removes the per-sample lambda+Message
    // allocations and collapses input bursts between handler executions.
    private var pendingSampleX = 0.0
    private var pendingSampleY = 0.0
    private var pendingSampleP = 1.0
    @Volatile private var sampleQueued = false
    private val sampleRunnable = Runnable {
        sampleQueued = false
        if (pendingCoreOps > 0) pendingCoreOps--
        ReverieCoreBridge.touchStrokeMove(pendingSampleX, pendingSampleY, pendingSampleP)
    }

    internal fun queueStrokeMove(x: Float, y: Float, p: Double) {
        val h = renderHandler ?: return
        pendingSampleX = x.toDouble()
        pendingSampleY = y.toDouble()
        pendingSampleP = p
        if (!sampleQueued) {
            sampleQueued = true
            pendingCoreOps++
            h.post(sampleRunnable) // 预建 Runnable 直接投递, 每样本零分配
        }
    }
```
说明: pendingCoreOps 计数与 runCore 保持同一约定 (doRender 输入让行依赖它); 合帧后每 8-16ms 才有一次真实 post, 且无 lambda 分配。

- [ ] **Step 3: touchMove 去 Triple 并改走合帧通道** — 重写 L179-204 中段:

```kotlin
    internal fun PaintViewModel.touchMove(
        x: Float,
        y: Float,
        pressure: Double = 1.0,
    ) {
        onPaintingActivity()
        val effPressure = computeEffectivePressure(pressure)
        val stabFactor = maxOf(strokeStabilizer.toDouble(), brushStreamline).coerceIn(0.0, 0.98)
        var effX = x
        var effY = y
        var effP = effPressure
        if (stabFactor > 0.0) {
            // Krita weighted stabilizer: adaptive distance & exponential smoothing
            val alpha = (1.0 - stabFactor * 0.88).coerceIn(0.04, 1.0).toFloat()
            smoothedStrokeX += (x - smoothedStrokeX) * alpha
            smoothedStrokeY += (y - smoothedStrokeY) * alpha
            smoothedStrokePressure += (effPressure - smoothedStrokePressure) * alpha.toDouble()
            effX = smoothedStrokeX
            effY = smoothedStrokeY
            effP = smoothedStrokePressure
        } else {
            smoothedStrokeX = x
            smoothedStrokeY = y
            smoothedStrokePressure = effPressure
        }
        if (recorder.recording) {
            recorder.strokeMove(effX, effY, effP.toFloat())
        }
        queueStrokeMove(effX, effY, effP)
    }
```

- [ ] **Step 4: touchEnd 尾部补偿改走同通道** — L216 替换:

```kotlin
        queueStrokeMove(smoothedStrokeX, smoothedStrokeY, smoothedStrokePressure)
```
(FIFO 保证其仍先于紧随的 touchStrokeEnd 执行)

- [ ] **Step 5: 编译验证** — `./gradlew :app:compileDebugKotlin`

---

### Task B3: 笔刷参数持久化防抖

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt` (mainHandler 附近新增调度)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:487` (saveBrushParam 尾), `:686-750` resetBrushParams 尾如有直接 persist 调用一并换
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:1645-1652` (onCleared flush)

- [ ] **Step 1: PaintViewModel 新增防抖调度** (放 startRenderThread 前):

```kotlin
    private val persistParamsRunnable = Runnable { persistBrushParams() }
    internal fun schedulePersistBrushParams(delayMs: Long = 250L) {
        mainHandler.removeCallbacks(persistParamsRunnable)
        mainHandler.postDelayed(persistParamsRunnable, delayMs)
    }
```
(mainHandler 若无可空性差异按实际字段调整; persistBrushParams 为 Brush.kt 现有 internal fun)

- [ ] **Step 2: saveBrushParam 尾部换防抖** — L487 `persistBrushParams()` → `schedulePersistBrushParams()`。

- [ ] **Step 3: 关键点立即落盘** — onCleared 开头加:

```kotlin
        mainHandler.removeCallbacks(persistParamsRunnable)
        persistBrushParams()
```
其余调用方 (loadBrushParams 后首次、resetBrushParams 等) 先 `rg -n "persistBrushParams\\(" app/src` 列出, 仅把**滑条热路径来源** (saveBrushParam) 换防抖, 其余保留同步写。

- [ ] **Step 4: 编译验证 + Commit B2+B3**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "perf(ui): 笔画输入热路径合帧去分配, 笔刷参数持久化防抖消除滑条掉帧"
```

---

### Task C1: 涂抹参数 JNI 下发 (C++ + Bridge + ViewModel)

**Files:**
- Modify: `app/src/main/cpp/ReverieCore.h` (~L365 声明区, ~L471 成员区)
- Modify: `app/src/main/cpp/ReverieCoreBrush.cpp:143-145` (loadBrushPreset 重应用块), `:249-255` 后新增两 setter
- Modify: `app/src/main/cpp/reverie_jni_brush.cpp:153-157` 后新增两个 JNI
- Modify: `app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt:241` 附近声明
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:383-391` (update 两个函数), selectBrushPreset 两处 JNI 批量
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt:304-308` (applyTool else 分支批量)
- Delete: 死代码 `ReverieCore::commitStrokeToLayer` (Stroke.cpp:628-671) + `ReverieCore.h:322` 声明 + `:536` `m_strokeBuffer` 成员 + `ReverieCoreDocument.cpp:54`、`ReverieCoreIO.cpp:314` 两处置空

- [ ] **Step 1: C++ 成员与声明** — ReverieCore.h 公有方法区 (setBrushFlow/setBrushSpacing 附近) 加:

```cpp
    void setBrushSmudgeRate(qreal v);
    void setBrushSmudgeLength(qreal v);
```
私有成员区 (m_strokeOpacity 附近) 加:

```cpp
    qreal m_smudgeRate = 0.5;   // color mixing rate -> ColorRateValue/MixValue
    qreal m_smudgeLength = 0.5; // smudge length -> SmudgeRateValue
```

- [ ] **Step 2: C++ 实现** (ReverieCoreBrush.cpp, setBrushFlow 之后):

```cpp
// Smudge engine parameters. Key names verified against bundled presets:
// k)_Blender_Basic.kpp exposes SmudgeRateValue (length), ColorRateValue and
// legacy MixValue (color mixing rate), paintop="colorsmudge".
void ReverieCore::setBrushSmudgeRate(qreal v)
{
    m_smudgeRate = v;
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    s->setProperty("ColorRateValue", v);
    s->setProperty("MixValue", v); // legacy key for older-generation presets
}

void ReverieCore::setBrushSmudgeLength(qreal v)
{
    m_smudgeLength = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("SmudgeRateValue", v);
    }
}
```
loadBrushPreset 重应用块 (`setBrushFlow(m_brushFlow);` L145 后) 追加:

```cpp
    setBrushSmudgeRate(m_smudgeRate);
    setBrushSmudgeLength(m_smudgeLength);
```

- [ ] **Step 3: JNI** (reverie_jni_brush.cpp, setBrushRotation 后):

```cpp
JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSmudgeRate(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSmudgeRate(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSmudgeLength(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSmudgeLength(v);
}
```

- [ ] **Step 4: Bridge 声明** (ReverieCoreBridge.kt, `external fun setBrushSpacing` 附近):

```kotlin
    external fun setBrushSmudgeRate(rate: Double)

    external fun setBrushSmudgeLength(length: Double)
```

- [ ] **Step 5: Kotlin 接线** — updateBrushSmudgeRate/updateBrushSmudgeLength 各加:

```kotlin
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushSmudgeRate(v) }   // Length 同型
```
selectBrushPreset: saved!=null JNI 批量末尾 (setBrushCompositeOp(effectiveCompOp) 后) 加:

```kotlin
                ReverieCoreBridge.setBrushSmudgeRate(saved.smudgeRate)
                ReverieCoreBridge.setBrushSmudgeLength(saved.smudgeLength)
```
else 分支改为:

```kotlin
            } else {
                ReverieCoreBridge.setBrushCompositeOp(effectiveCompOp)
                ReverieCoreBridge.setBrushSmudgeRate(brushSmudgeRate)
                ReverieCoreBridge.setBrushSmudgeLength(brushSmudgeLength)
            }
```
applyTool 同预设分支 runCore 内 (三个 setter 后) 加同样两行 (用全局字段值)。

- [ ] **Step 6: 删除死代码 commitStrokeToLayer** — 先 `rg -n "commitStrokeToLayer|m_strokeBuffer" app/src/main/cpp` 确认仅剩计划内 6 处引用, 然后删除: Stroke.cpp:628-671 整个函数、ReverieCore.h:322 声明、ReverieCore.h:536 成员、Document.cpp:54 与 IO.cpp:314 的置空语句。

- [ ] **Step 7: 构建 + 编译 + Commit**

```bash
./scripts/build_native.sh
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "feat(jni): 下发涂抹混色比率/延伸长度至 colorsmudge 引擎并清理死代码 commitStrokeToLayer"
```

---

### Task C2: 橡皮判定元数据覆盖 + Task C3 压力下限放宽

**Files:**
- Modify: `app/src/main/cpp/ReverieCore.h` (~L562 成员区 + 方法声明区)
- Modify: `app/src/main/cpp/ReverieCoreBrush.cpp:115-165` (loadBrushPreset), `:317-323` (setToolMode 附近新增 setter)
- Modify: `app/src/main/cpp/ReverieCoreStroke.cpp:247-251` (erasing 判定), `:418-420/:567/:581/:589` (压力下限)
- Modify: `app/src/main/cpp/reverie_jni_brush.cpp` + `ReverieCoreBridge.kt` (setPresetIsEraser)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt:775-846` (selectBrushPreset 断言)

- [ ] **Step 1: C++ 覆盖标志** — 成员:

```cpp
    int m_presetIsEraserOverride = -1; // -1 unknown (use name heuristic), 0 false, 1 true
```
方法:

```cpp
    void setPresetIsEraser(bool eraser);
```
实现 (Brush.cpp):

```cpp
void ReverieCore::setPresetIsEraser(bool eraser)
{
    m_presetIsEraserOverride = eraser ? 1 : 0;
}
```
loadBrushPreset 成功路径开头 (`m_brushPreset = preset;` 前) 重置:

```cpp
    m_presetIsEraserOverride = -1; // new preset: heuristic governs until UI asserts
```

- [ ] **Step 2: Stroke.cpp 判定改造** (L247-251):

```cpp
    bool isEraserPreset;
    if (m_presetIsEraserOverride >= 0) {
        isEraserPreset = m_presetIsEraserOverride == 1;
    } else {
        isEraserPreset = m_brushPreset && (
            m_brushPreset->name().startsWith(QLatin1String("a)_")) ||
            m_brushPreset->name().contains(QLatin1String("Eraser"), Qt::CaseInsensitive)
        );
    }
```

- [ ] **Step 3: JNI + Bridge** — reverie_jni_brush.cpp:

```cpp
JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setPresetIsEraser(JNIEnv *, jobject, jboolean eraser)
{
    core()->setPresetIsEraser(eraser == JNI_TRUE);
}
```
Bridge.kt: `external fun setPresetIsEraser(isEraser: Boolean)` (加 @JvmName 不需要, Boolean→jboolean 自动映射)。

- [ ] **Step 4: Kotlin 断言** — selectBrushPreset 中, 计算 isEraserPreset 后传给 runCore body 开头:

```kotlin
            ReverieCoreBridge.setPresetIsEraser(isEraserPreset)
```
(loadBrushPreset JNI 之前或紧后均可, 同一 render-thread 批次内原子生效)

- [ ] **Step 5: 压力下限 15% → 2%** — Stroke.cpp 三处 `qMax<qreal>(1.0, m_brushSize * 0.15)` (:419, :567, :581, :589 共四处, 全部) 改为:

```cpp
qMax<qreal>(1.0, m_brushSize * 0.02)
```
并把 L415-417 注释更新为: 下限仅为防完全消失的兜底 (Krita 由 Size 曲线决定最小 dab), 15% 曾严重压扁轻压笔迹。

- [ ] **Step 6: 构建 + 编译 + Commit**

```bash
./scripts/build_native.sh
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "fix(brush): 橡皮判定支持分组元数据覆盖替代纯名字启发式, 压力可见下限 15%→2% 对齐 Krita"
```

---

### Task V: 总验证与真机回归

- [ ] **Step 1: 机械验证**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
```

- [ ] **Step 2: 回归审查对照影响清单** — 逐项检查: 三工具切换数值独立、滑条编辑互不污染、selectBrushPreset 自动切工具方向 (选 a)_ 组预设→eraser 工具)、快捷键 toggle_eraser、启动恢复 current_tool_id、PlaybackEngine 录制回放 (touchMove 改走合帧通道后事件顺序 FIFO 不变)、选区内绘制、双指手势、变换。

- [ ] **Step 3: 真机手动回归** (用户提供设备): 大笔刷慢速画线跟手性 / 收笔无甩尾; 拖大小滑条流畅度; 笔刷→橡皮擦尺寸隔离; 重启持久化; Blender_Basic+涂抹工具真混色; 混色比率/延伸长度滑条生效; 手势/选区/变换不受影响; 录制回放正常。

- [ ] **Step 4: 文档收尾** — `docs/PROGRESS.md` 补里程碑记录 (若适用)。
