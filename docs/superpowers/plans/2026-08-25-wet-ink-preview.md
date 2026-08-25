# 湿墨预览层实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落笔当帧即见墨——未渲染采样以简化笔触叠画在画布上，真墨到达后按时间戳替换；API≥31 叠加系统笔迹预测。

**Architecture:** 纯 Kotlin 湿墨采样队列（环形缓冲）由 CanvasTouchView 持有并在其 onDraw 绘制；PaintViewModel 通过注入回调喂入平滑后采样、翻转时记录提交时间戳；引擎与双缓冲零改动。

**Tech Stack:** Kotlin + Jetpack Compose（仅开关 UI）+ android.view.PointerPredictor (API 31+) + JUnit4。

**Spec:** docs/superpowers/specs/2026-08-25-wet-ink-preview-design.md

## Global Constraints

- 缩进 4 空格，行宽 ≤120；UI 文案直接中文字面量；主题色用 Morandi 语义色。
- 热路径零分配：所有缓冲预分配，onDraw 内禁止创建对象（Paint/数组全部复用）。
- 依赖单向 ui→core：core 不 import ui；注入回调签名只用基本类型。
- 手势处理不得 key 在视口状态上；不改 JNI/C++。
- 引擎调用禁止上 UI 线程（本计划不新增任何引擎调用）。
- 验证命令：`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`。

---

### Task 1: WetInkQueue 纯逻辑核心 (TDD)

**Files:**
- Create: `app/src/main/java/com/reverie/paint/ui/painting/canvas/WetInkPreview.kt`
- Test: `app/src/test/java/com/reverie/paint/ui/painting/canvas/WetInkQueueTest.kt`

**Interfaces:**
- Produces: `class WetInkQueue(capacity: Int = 96)`，方法 `append(x: Float, y: Float, p: Float, tMs: Long, predicted: Boolean)`、`dropCommittedBefore(commitMs: Long)`、`dropOlderThan(cutoffMs: Long)`、`snapshot(out: MutableList<WetInkQueue.MutableSample>)`(池化零分配)、`clear()`、`val count: Int`；嵌套 `class MutableSample(x,y,pressure,tMs,predicted)`(可变字段复用)。后续任务依赖以上精确签名。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.reverie.paint.ui.painting.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetInkQueueTest {

    @Test
    fun `追加样本按序快照且字段完整`() {
        val q = WetInkQueue()
        q.append(1f, 2f, 0.5f, 100L, false)
        q.append(3f, 4f, 0.7f, 110L, true)
        val out = ArrayList<WetInkQueue.MutableSample>()
        q.snapshot(out)
        assertEquals(2, out.size)
        assertEquals(1f, out[0].x, 0f)
        assertEquals(0.7f, out[1].pressure, 0f)
        assertTrue(out[1].predicted)
    }

    @Test
    fun `按提交时间丢弃旧样本保留新样本`() {
        val q = WetInkQueue()
        q.append(0f, 0f, 1f, 100L, false)
        q.append(1f, 0f, 1f, 200L, false)
        q.append(2f, 0f, 1f, 300L, false)
        q.dropCommittedBefore(200L)
        val out = ArrayList<WetInkQueue.MutableSample>()
        q.snapshot(out)
        assertEquals(1, out.size)
        assertEquals(300L, out[0].tMs)
    }

    @Test
    fun `容量回绕丢弃最旧样本`() {
        val q = WetInkQueue(capacity = 4)
        repeat(6) { q.append(it.toFloat(), 0f, 1f, it.toLong(), false) }
        assertEquals(4, q.count)
        val out = ArrayList<WetInkQueue.MutableSample>()
        q.snapshot(out)
        assertEquals(2f, out.first().x, 0f)
        assertEquals(5f, out.last().x, 0f)
    }

    @Test
    fun `过期清理与清空`() {
        val q = WetInkQueue()
        q.append(0f, 0f, 1f, 100L, false)
        q.dropOlderThan(150L)
        assertEquals(0, q.count)
        q.append(1f, 1f, 1f, 200L, false)
        q.clear()
        assertEquals(0, q.count)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.reverie.paint.ui.painting.canvas.WetInkQueueTest"`
Expected: 编译失败 `Unresolved reference: WetInkQueue`

- [ ] **Step 3: 最小实现**

```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

/**
 * 湿墨预览采样队列: 预分配环形缓冲, 保存尚未被真墨渲染覆盖的笔画采样,
 * 由 CanvasTouchView 在每帧 onDraw 中绘制为简化笔触尾巴。
 * 纯 Kotlin 无 Android 依赖, 可 JVM 单测。单线程访问 (UI 线程), 无锁零分配。
 */
class WetInkQueue(private val capacity: Int = DEFAULT_CAPACITY) {

    /** 池化可变样本: snapshot 复用实例, 绘制热路径零分配。 */
    class MutableSample(
        var x: Float = 0f,
        var y: Float = 0f,
        var pressure: Float = 0f,
        var tMs: Long = 0L,
        var predicted: Boolean = false,
    )

    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val ps = FloatArray(capacity)
    private val ts = LongArray(capacity)
    private val preds = BooleanArray(capacity)
    private var head = 0 // 最旧样本下标
    private var size = 0

    val count: Int get() = size

    fun append(x: Float, y: Float, p: Float, tMs: Long, predicted: Boolean) {
        val slot = (head + size) % capacity
        if (size == capacity) head = (head + 1) % capacity else size++
        xs[slot] = x; ys[slot] = y; ps[slot] = p; ts[slot] = tMs; preds[slot] = predicted
    }

    /** 移除 tMs <= commitMs 的头部样本 (队列按时间有序)。 */
    fun dropCommittedBefore(commitMs: Long) {
        var dropped = 0
        while (dropped < size && ts[(head + dropped) % capacity] <= commitMs) dropped++
        head = (head + dropped) % capacity
        size -= dropped
    }

    /** 泄漏兜底: 清除早于 cutoffMs 的样本 (正常情况下提交规则会先清掉它们)。 */
    fun dropOlderThan(cutoffMs: Long) = dropCommittedBefore(cutoffMs)

    /** 池化快照: 复用 out 中已有条目, 不足时才新建 (稳态零分配)。 */
    fun snapshot(out: MutableList<MutableSample>) {
        out.clear()
        for (i in 0 until size) {
            val idx = (head + i) % capacity
            while (out.size <= i) out.add(MutableSample())
            val m = out[i]
            m.x = xs[idx]; m.y = ys[idx]; m.pressure = ps[idx]
            m.tMs = ts[idx]; m.predicted = preds[idx]
        }
    }

    fun clear() { head = 0; size = 0 }

    companion object {
        /** 240Hz 采样 × 250ms 兜底窗口上限再留余量。 */
        const val DEFAULT_CAPACITY = 96
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2。Expected: PASS (4 tests)

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/reverie/paint/ui/painting/canvas/WetInkPreview.kt app/src/test/java/com/reverie/paint/ui/painting/canvas/WetInkQueueTest.kt
git commit -m "feat(ui): 湿墨预览采样队列纯逻辑核心与单元测试"
```

### Task 2: PaintViewModel 注入点、提交时间戳与开关

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelTools.kt` (touchMove, ~236 行 queueStrokeMove 前)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt` (doRender 翻转块 + 手势开关字段区 ~704 行)
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModelShortcuts.kt` (saveViewSettings/loadViewSettings + 新 update 函数)

**Interfaces:**
- Consumes: 无
- Produces: `PaintViewModel.strokeSampleListener: ((Float, Float, Float) -> Unit)?`(internal var, 参数为平滑后 effX/effY/effP)、`lastRenderCommitElapsedMs: Long`(@Volatile internal)、`wetInkEnabled/wetInkPredict: Boolean`(mutableStateOf, 默认 true)、`updateWetInkEnabled/updateWetInkPredict(Boolean)`。

- [ ] **Step 1: 加状态与持久化**

PaintViewModel.kt 手势开关区 (`gestureTwoFingerUndo` 字段旁) 加:

```kotlin
var wetInkEnabled by mutableStateOf(true) // 落笔即时预览 (湿墨层)
var wetInkPredict by mutableStateOf(true) // Android 12+ 笔迹预测 (湿墨层配套)
```

PaintViewModelShortcuts.kt 仿照 `updateCanvasRotationEnabled` 加两个 update 函数 (内部调 saveViewSettings)；save/load 的 JSONObject 各加 `"wet_ink"` 与 `"wet_ink_predict"` 键 (默认 true)。

- [ ] **Step 2: touchMove 注入点**

PaintViewModelTools.kt `touchMove` 中 `queueStrokeMove(effX, effY, effP)` 之前插入:

```kotlin
// 湿墨预览: 平滑后的采样同步喂给显示层快速路径 (ui 层通过注入接收, core 不反向依赖)
strokeSampleListener?.invoke(effX, effY, effP.toFloat())
```

- [ ] **Step 3: 渲染提交时间戳**

PaintViewModel.kt 类内加 `@Volatile internal var lastRenderCommitElapsedMs = 0L`；doRender 的 `mainHandler.post { ... displayRevision++ ... }` 块内加:

```kotlin
lastRenderCommitElapsedMs = android.os.SystemClock.elapsedRealtime()
```

同时加 `internal var strokeSampleListener: ((Float, Float, Float) -> Unit)? = null` 字段。

- [ ] **Step 4: 编译验证 + 提交**

Run: `./gradlew :app:compileDebugKotlin` Expected: BUILD SUCCESSFUL

```bash
git add -A app/src/main/java/com/reverie/paint/core
git commit -m "feat(core): 湿墨层采样注入点/渲染提交时间戳与开关持久化"
```

### Task 3: 控制器与 CanvasTouchView 绘制接线

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/canvas/WetInkPreview.kt` (追加 android 依赖控制器)
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt`

**Interfaces:**
- Consumes: Task1 WetInkQueue 全部签名; Task2 strokeSampleListener/lastRenderCommitElapsedMs/wetInkEnabled。
- Produces: `class WetInkPreviewController(vm: PaintViewModel)` 方法 `onFrame(revision: Long, commitMs: Long)`、`draw(canvas, viewW, viewH, zoom, fitScale, panX, panY, rotation, tool)`、`onStrokeCancel()`、`dispose()`; 顶层 `internal fun wetInkGate(vm: PaintViewModel, tool: Tool): Boolean`。

- [ ] **Step 1: 控制器实现**

```kotlin
/**
 * 湿墨预览控制器: 持有采样队列与复用的绘制资源, 由 CanvasTouchView 拥有。
 * 门控不满足时静默不出墨, 引擎真墨路径完全不受影响。
 */
internal class WetInkPreviewController(private val vm: PaintViewModel) {
    val queue = WetInkQueue()
    private val drawList = ArrayList<WetInkQueue.MutableSample>()
    private val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 8f
    }
    private var colorKey = ""
    private var lastRevision = -1

    fun onFrame(revision: Int, commitMs: Long) {
        if (revision != lastRevision) {
            lastRevision = revision
            queue.dropCommittedBefore(commitMs)
        }
    }

    fun onStrokeCancel() {
        queue.clear()
    }

    fun dispose() {
        queue.clear()
    }

    /**
     * 绘制湿墨尾巴。坐标变换与 CanvasOverlay 的文档位图一致:
     * 屏幕 = rotate(scale·(位图点 − 位图中心)) + 视图中心 + pan。
     * 全部资源复用, 本方法零分配 (MutableSample 池化见 Task 1)。
     */
    fun draw(
        canvas: android.graphics.Canvas,
        viewW: Float,
        viewH: Float,
        zoom: Float,
        fitScale: Float,
        panX: Float,
        panY: Float,
        rotation: Float,
        tool: Tool,
    ) {
        if (!wetInkGate(vm, tool)) return
        queue.dropOlderThan(android.os.SystemClock.elapsedRealtime() - STALE_MS)
        queue.snapshot(drawList)
        val n = drawList.size
        // 单样本尾巴交给引擎 kick 出真墨点, 这里不画
        if (n < 2 || vm.docWidth <= 0 || vm.docHeight <= 0) return

        val bmp = vm.displayBitmap
        val bmpW = bmp?.width ?: vm.docWidth
        val bmpH = bmp?.height ?: vm.docHeight
        if (bmpW <= 0 || bmpH <= 0) return
        val toBmpX = bmpW.toFloat() / vm.docWidth
        val toBmpY = bmpH.toFloat() / vm.docHeight

        val key = vm.brushColor
        if (key != colorKey) {
            colorKey = key
            paint.color = runCatching { android.graphics.Color.parseColor(key) }
                .getOrDefault(android.graphics.Color.BLACK)
        }
        val layerOpacity = vm.layers
            .firstOrNull { it.index == vm.currentLayerIndex }?.opacity?.toFloat() ?: 1f
        paint.alpha = (255f * vm.brushOpacity.toFloat() * layerOpacity * PREVIEW_ALPHA)
            .toInt().coerceIn(0, 255)

        val s = (zoom * fitScale).coerceAtLeast(0.0001f)
        canvas.save()
        canvas.translate(viewW / 2f + panX, viewH / 2f + panY)
        canvas.rotate(rotation)
        canvas.scale(s, s)
        var prev = drawList[0]
        for (i in 1 until n) {
            val cur = drawList[i]
            val fraction =
                com.reverie.paint.core.ReverieCoreBridge.brushPressureFraction(
                    (prev.pressure + cur.pressure) * 0.5f,
                )
            // canvas 已按 s 缩放: 文档单位宽度即屏幕宽 ÷ s, 与光标环同源
            paint.strokeWidth = (vm.brushSize * fraction).coerceAtLeast(0.5f)
            canvas.drawLine(
                prev.x * toBmpX - bmpW / 2f,
                prev.y * toBmpY - bmpH / 2f,
                cur.x * toBmpX - bmpW / 2f,
                cur.y * toBmpY - bmpH / 2f,
                paint,
            )
            prev = cur
        }
        canvas.restore()
    }

    companion object {
        /** 预览透明度系数: 与即将到达的真墨差异最小化。 */
        const val PREVIEW_ALPHA = 0.9f

        /** 泄漏兜底: 超过此窗口的样本必然早已被引擎渲染提交覆盖。 */
        const val STALE_MS = 250L
    }
}

/** 门控: 非 BRUSH/纹理/路径引擎/气笔刷/选区/关闭 时禁用预览。 */
internal fun wetInkGate(vm: PaintViewModel, tool: Tool): Boolean {
    if (!vm.wetInkEnabled || tool != Tool.BRUSH) return false
    if (vm.hasSelection || vm.brushAirbrush || vm.brushTextureEnabled) return false
    return when (vm.brushPaintOpId) {
        "experimentbrush", "curvebrush", "sketchbrush", "gridbrush", "particlebrush" -> false
        else -> true
    }
}
```

draw() 完整代码已内联在上块。`MutableSample` 池化快照已在 Task 1 的 WetInkQueue 中定义（`snapshot(out)` 复用条目，稳态零分配），本任务直接使用。

- [ ] **Step 2: CanvasTouchView 接线**

- 字段区加 `private val wetPreview = WetInkPreviewController(vm!!)` 不行 (vm 可空) → 改为懒建: `private var wetPreview: WetInkPreviewController? = null`，在 `onAttachedToWindow` 里 `vm?.let { wetPreview = WetInkPreviewController(it); it.strokeSampleListener = { x, y, p -> wetPreview?.queue?.append(x, y, p, android.os.SystemClock.elapsedRealtime(), false) } }`；`onDetachedFromWindow` 里 `vm?.strokeSampleListener = null; wetPreview?.dispose(); wetPreview = null`。
- `onTouchEvent` 笔画分支 `ACTION_UP/ACTION_CANCEL` 处理末尾调 `wetPreview?.onStrokeCancel()` 仅当 `isCancel`; 抬笔成功不清队列 (等最终渲染提交自然清)。
- `onDraw` 光标绘制之前插入:

```kotlin
wetPreview?.let { wp ->
    val v = vm
    if (v != null && wetInkGate(v, tool)) {
        wp.onFrame(v.displayRevision, v.lastRenderCommitElapsedMs)
        wp.draw(canvas, width.toFloat(), height.toFloat(), canvasZoom, canvasFitScale,
            canvasPanX, canvasPanY, canvasRotation, tool)
    }
}
```

- [ ] **Step 3: 编译验证 + 提交**

Run: `./gradlew :app:compileDebugKotlin` Expected: SUCCESS

```bash
git add app/src/main/java/com/reverie/paint/ui/painting/canvas/
git commit -m "feat(ui): 湿墨预览控制器接入画布触控视图, 落笔当帧出墨"
```

### Task 4: PointerPredictor 笔迹预测

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt`

**Interfaces:**
- Consumes: Task3 wetPreview/wetInkGate; Task2 vm.wetInkPredict。
- Produces: 无新接口。

- [ ] **Step 1: 预测器接入**

字段区: `private var pointerPredictor: Any? = null` (API31 类型经 `@Suppress("NewApi")` 局部转换, 或直接声明平台类型并全程 SDK 判断)。手写笔 `ACTION_MOVE` 分支 `handleToolMove(...)` 之后:

```kotlin
val v2 = vm
if (v2 != null && v2.wetInkEnabled && v2.wetInkPredict &&
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && tool == Tool.BRUSH
) {
    val predictor = ensurePredictor()
    if (predictor != null) {
        try {
            val predictedEvent = predictor.predictMotionEvent(event)
            if (predictedEvent != null) {
                val li = predictedEvent.pointerCount - 1
                val pd = screenToDoc(Offset(predictedEvent.getX(li), predictedEvent.getY(li)))
                wetPreview?.queue?.append(pd.x, pd.y, pressure,
                    android.os.SystemClock.elapsedRealtime(), predicted = true)
            }
        } catch (_: Throwable) { /* 杂牌 ROM 静默降级 */ }
    }
    invalidate()
}
```

`ensurePredictor()` 懒建并 try/catch 返回 null 兜底; `ACTION_UP/ACTION_CANCEL` 与 `onDetachedFromWindow` 清空预测尾巴 (queue.clear() 于 cancel/dispose 已覆盖, UP 时无需额外)。预测点只进显示队列, 不进 vm.touchMove/录制。

- [ ] **Step 2: 编译验证 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt
git commit -m "feat(ui): Android 12+ PointerPredictor 笔迹预测接入湿墨预览"
```

### Task 5: 设置面板开关

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/panels/SettingsTabPage.kt` (~461 行三指重做行之后)

**Interfaces:** Consumes Task2 的 vm.wetInkEnabled/updateWetInkEnabled、vm.wetInkPredict/updateWetInkPredict。

- [ ] **Step 1: 复制 ReSwitch 行模式加两行**

「双指点击屏幕撤销」Row 结构原样复制两份: 文案分别为 标题「落笔即时预览」副文「笔尖位置当帧显示简化墨迹，真墨随后无缝替换」与 标题「笔迹预测」副文「系统预测笔尖轨迹进一步降低延迟 (Android 12+)」，绑定对应 vm 字段与 update 函数。

- [ ] **Step 2: 编译 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/reverie/paint/ui/painting/panels/SettingsTabPage.kt
git commit -m "feat(ui): 设置面板新增湿墨预览与笔迹预测开关"
```

### Task 6: 全量验证与真机交付

- [ ] `./gradlew :app:testDebugUnitTest` 全绿
- [ ] `./gradlew assembleDebug` 成功 (无 C++ 改动, 预编译模式即可)
- [ ] `adb install -r app/build/outputs/apk/debug/app-debug.apk` 并启动
- [ ] 回归清单反馈用户: 快速运笔跟随感 / 纹理笔刷无跳变(自动降级) / 两开关生效 / 抬笔预测尾巴消失 / 录制回放不受污染 / 橡皮与选区行为不变
