# 真湿墨层 (Wet Overlay v2) 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 笔画 dab 出来后 ~1 帧内上屏：把图层设备脏区直接导出为覆盖位图叠画在画布上，跳过多图层投影合并；全笔刷像素级正确。

**Architecture:** C++ 在 flush 后累计湿墨脏区并提供 `renderWetOverlay` 单层导出（复用 readBytes + blitBgraToRgbaFast）；Kotlin 排水 runnable 内同步取数到预分配位图，CanvasTouchView 按文档变换叠加绘制，水位线判定真墨上屏后清除。折线预览退役，预测点只画短 streak。

**Tech Stack:** Kotlin + JNI + Krita KisPaintDevice readBytes + 既有 NEON 转换器。

**Spec:** docs/superpowers/specs/2026-08-25-wet-overlay-v2-design.md

## Global Constraints

- 缩进 4 空格、行宽 ≤120、中文 UI 文案、Morandi 语义色。
- 热路径零分配：湿墨位图预分配复用；JNI 数组/Rect 复用。
- 引擎调用禁止 UI 线程：fetch/clear 只在 renderHandler 线程。
- 不改既有绘制语义（直绘/撤销/事务/录制全不动）。
- 验证：`:app:compileDebugKotlin :app:testDebugUnitTest` + `scripts/build_native.sh` 全量。

---

### Task 1: C++ 湿墨脏区累计与导出接口

**Files:**
- Modify: `app/src/main/cpp/ReverieCore.h` (成员 + 方法声明)
- Modify: `app/src/main/cpp/ReverieCoreStroke.cpp` (累计点 + 导出实现放此文件末尾)
- Modify: `app/src/main/cpp/reverie_jni_brush.cpp` (两个 JNI 包装)

**Interfaces:**
- Produces: `bool renderWetOverlay(JNIEnv 位图, jintArray outRect)` 语义 —— 有未取脏区则把「当前图层设备」该区域转 RGBA 拷入位图并返回 true/outRect=[x,y,w,h]，否则 false；`clearWetOverlay()` 清空累计。成员 `QRect m_wetDirtyAccum`。

- [ ] **Step 1: ReverieCore.h 加声明**

在 `brushTipKind()` 声明后加:

```cpp
    // ---- 湿墨层 (v2): 图层设备脏区直接导出, 供 Kotlin 叠加即时显示 ----
    // BRUSH 非擦除的 flush/tap/kick 产出后调用; outRect=[x,y,w,h]。
    // 位图须为 文档宽×高 ARGB_8888。返回 false 表示无新内容。
    // 仅渲染线程调用; env 由 JNI 包装透传供 AndroidBitmap_* 使用。
    bool renderWetOverlay(JNIEnv *env, jobject bitmap, jintArray outRect);

    // Kotlin 判定真墨已上屏后清除累计 (下一笔 start 也会自动重置)。
    void clearWetOverlay();
```

私有成员区 (`m_idleKickPainted` 附近) 加:

```cpp
    // 湿墨层: 自上次导出以来 BRUSH 非擦除笔迹写入的图层设备区域并集
    QRect m_wetDirtyAccum;
```

- [ ] **Step 2: 累计点接入 flushStrokeBatch**

三处产出 dirty 的位置，在 `markRegionDirty(strokeDirty)` / tap 路径 `markRegionDirty(tr)` 之后各加一行（tap 与 kick 共用 tap 路径；airbrushTick 不加——它走独立路径且属持续出墨，纳入亦可，此处保持最小面）:

```cpp
        if (m_toolMode == ToolBrush && !erasing) {
            m_wetDirtyAccum |= strokeDirty;
        }
```

tap-dot 路径用 `tr` 替代 `strokeDirty`。同时 `touchStrokeStart` 重置: `m_wetDirtyAccum = QRect();`，`touchStrokeCancel` 里同样清空。

- [ ] **Step 3: ReverieCoreStroke.cpp 末尾实现**

```cpp
// 湿墨层导出: 把当前图层设备自上次导出以来的脏区读出并转成
// Android ARGB_8888 位图内容 (坐标即文档坐标)。仅支持 RGBA8 文档,
// 其余色彩空间静默返回 false (显示回退到正常管线)。
// 线程契约: 仅在渲染线程 (与 flush 串行) 调用。
bool ReverieCore::renderWetOverlay(JNIEnv *env, jobject bitmap, jintArray outRect)
{
    if (!bitmap || !outRect || m_wetDirtyAccum.isNull() || !m_document) {
        return false;
    }
    KisImageSP image = m_document;
    const QRect docBounds(0, 0, image->width(), image->height());
    const QRect r = m_wetDirtyAccum.intersected(docBounds);
    if (r.isEmpty()) {
        m_wetDirtyAccum = QRect();
        return false;
    }
    if (image->colorSpace()->colorChannelCount() != 4
        || image->colorSpace()->pixelSize() != 4) {
        return false;
    }
    KisPaintDeviceSP dev = currentPaintDevice();
    if (!dev) {
        return false;
    }

    // AndroidBitmap 锁定与尺寸校验
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    if (info.width != (uint32_t)image->width() || info.height != (uint32_t)image->height()
        || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return false;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS
        || !pixels) {
        return false;
    }

    const size_t rowBytes = size_t(r.width()) * 4;
    if (size_t(m_subRegionBuffer.size()) < rowBytes * r.height()) {
        m_subRegionBuffer.resize(rowBytes * r.height());
    }
    dev->readBytes(reinterpret_cast<quint8 *>(m_subRegionBuffer.data()),
                   r.x(), r.y(), r.width(), r.height());
    quint8 *dst = reinterpret_cast<quint8 *>(pixels)
        + size_t(r.y()) * info.stride + size_t(r.x()) * 4;
    blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(m_subRegionBuffer.constData()),
                       rowBytes, dst, info.stride, r.width(), r.height());
    AndroidBitmap_unlockPixels(env, bitmap);

    jint rect[4] = { r.x(), r.y(), r.width(), r.height() };
    env->SetIntArrayRegion(outRect, 0, 4, rect);
    m_wetDirtyAccum = QRect();
    return true;
}

void ReverieCore::clearWetOverlay()
{
    m_wetDirtyAccum = QRect();
}
```

注意: `m_subRegionBuffer` 为 ReverieCore 类内既有私有成员 (ReverieCoreRender.cpp 在用), 本文件同类成员函数可直接复用; `AndroidBitmap_*` 需在 ReverieCoreInternal.h 的 Q_OS_ANDROID 区块加 `#include <android/bitmap.h>`; JNI 包装处 JNIEXPORT 签名带 `JNIEnv *env` 透传。

- [ ] **Step 4: JNI 包装 (reverie_jni_brush.cpp)**

```cpp
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderWetOverlay(JNIEnv *env, jobject, jobject bitmap, jintArray outRect)
{
    return core()->renderWetOverlay(env, bitmap, outRect) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_clearWetOverlay(JNIEnv *, jobject)
{
    core()->clearWetOverlay();
}
```

- [ ] **Step 5: 编译验证 (延后至 Task 5 统一 buildNative)**

本任务以代码评审自查为主: 三处累计齐全(start重置/tap/main/cancel)、线程契约注释、色彩空间守卫。

### Task 2: Kotlin VM 取数管线

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt`
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt`

**Interfaces:**
- Consumes: Task1 两个 JNI。
- Produces: `@Volatile var wetOverlayBitmap: Bitmap?`、`@Volatile var wetOverlayDirty: Rect?`(文档坐标)、内部 `fetchWetOverlay()`、`internal fun clearWetOverlayDisplay()`; Bridge 增加 `external fun renderWetOverlay(bitmap: Bitmap, outRect: IntArray): Boolean` / `external fun clearWetOverlay()`。

- [ ] **Step 1: Bridge 声明**

```kotlin
    /** 湿墨层: 导出图层设备未取脏区到 bitmap (须为文档尺寸 ARGB_8888),
     *  outRect=[x,y,w,h]。返回 false 表示无新内容或环境不支持。 */
    external fun renderWetOverlay(bitmap: Bitmap, outRect: IntArray): Boolean

    external fun clearWetOverlay()
```

- [ ] **Step 2: VM 字段与方法**

PaintViewModel.kt 湿墨字段区追加:

```kotlin
    // 真湿墨层 (v2): 图层设备脏区即时快照, CanvasTouchView 每帧叠加;
    // 位图按文档尺寸惰性分配复用, 仅渲染线程写入
    @Volatile internal var wetOverlayBitmap: Bitmap? = null
    @Volatile internal var wetOverlayDirty: android.graphics.Rect? = null
    private val wetOutRect = IntArray(4)

    internal fun ensureWetOverlayBitmap(): Bitmap? {
        val w = docWidth
        val h = docHeight
        if (w <= 0 || h <= 0) return null
        var b = wetOverlayBitmap
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wetOverlayBitmap = b
        }
        return b
    }

    /** 渲染线程调用: 引擎有未取湿墨脏区则拷入位图并记录脏区。 */
    internal fun fetchWetOverlay() {
        if (!wetInkEnabled) return
        val b = ensureWetOverlayBitmap() ?: return
        try {
            if (ReverieCoreBridge.renderWetOverlay(b, wetOutRect)) {
                wetOverlayDirty = android.graphics.Rect(
                    wetOutRect[0], wetOutRect[1],
                    wetOutRect[0] + wetOutRect[2], wetOutRect[1] + wetOutRect[3],
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** 真墨已上屏后由视图侧触发: 抹平位图并通知引擎清累计。 */
    internal fun clearWetOverlayDisplay() {
        wetOverlayDirty = null
        wetOverlayBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
        try {
            ReverieCoreBridge.clearWetOverlay()
        } catch (_: Throwable) {
        }
    }
```

- [ ] **Step 3: 三处取数接线**

- `strokeBatchRunnable` 中 `if (painted) { ...scheduleRender() }` 前插 `fetchWetOverlay()`。
- `strokeStartKickRunnable` painted 分支 scheduleRender 前插 `fetchWetOverlay()`。
- `touchEnd` 的 `runCore(after = {...})` 改为 `runCore(op = { touchStrokeEnd }); 再补一个 runCore { fetchWetOverlay(); scheduleRender(immediate = true) }` —— 即把现有 after 回调里的 scheduleRender 移入新的取数 runCore (FIFO 保证在 end 之后执行)。

- [ ] **Step 4: 编译验证 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A app/src/main/java/com/reverie/paint/core app/src/main/cpp
git commit -m "feat(core,jni): 图层设备湿墨脏区导出接口与取数管线"
```

### Task 3: 控制器重写为覆盖合成器 + 视图接线

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/canvas/WetInkPreview.kt`
- Modify: `app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt`

**Interfaces:**
- Consumes: Task2 wetOverlayBitmap/wetOverlayDirty/clearWetOverlayDisplay; 既有 WetInkQueue(仅预测样本)。
- Produces: `WetInkPreviewController.shouldPreview(tool): Boolean`(=BRUSH 且开关开)、`draw(...)`(叠加湿墨位图 + 预测 streak)、`onStrokeUp()`(记录待清水位)、`onFrame()`(水位线到达则 clearWetOverlayDisplay)。

- [ ] **Step 1: 控制器替换实现**

保留类名与 draw 签名; 删除 drawList/path/颜色缓存/shouldPreview 的 tipKind 查询; 新实现要点(完整写出):

```kotlin
internal class WetInkPreviewController(private val vm: com.reverie.paint.core.PaintViewModel) {
    val queue = WetInkQueue(capacity = 16) // 仅预测样本
    private val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private var lastPredicted: android.graphics.PointF? = null
    private var pendingClearAfterMs = Long.MIN_VALUE

    fun shouldPreview(tool: com.reverie.paint.model.Tool): Boolean =
        vm.wetInkEnabled && tool == com.reverie.paint.model.Tool.BRUSH

    fun appendPredicted(x: Float, y: Float, p: Float, tMs: Long) {
        queue.append(x, y, p, tMs, true)
    }

    fun onStrokeUp(nowMs: Long) {
        pendingClearAfterMs = nowMs + CLEAR_LAG_MS
    }

    fun onStrokeCancel() {
        queue.clear()
        lastPredicted = null
        pendingClearAfterMs = Long.MIN_VALUE
        vm.clearWetOverlayDisplay()
    }

    fun dispose() {
        queue.clear()
        lastPredicted = null
    }

    fun draw(
        canvas: android.graphics.Canvas,
        viewW: Float, viewH: Float,
        zoom: Float, fitScale: Float,
        panX: Float, panY: Float, rotation: Float,
        tool: com.reverie.paint.model.Tool,
    ) {
        if (!vm.wetInkEnabled) return
        // 1) 湿墨覆盖位图
        val bmp = vm.wetOverlayBitmap
        if (bmp != null && tool == com.reverie.paint.model.Tool.BRUSH) {
            val layerAlpha = (vm.layers.firstOrNull { it.index == vm.currentLayerIndex }
                ?.opacity?.toFloat() ?: 1f)
            paint.alpha = (255f * layerAlpha).toInt().coerceIn(0, 255)
            paint.strokeWidth = 1f
            val s = (zoom * fitScale).coerceAtLeast(0.0001f)
            canvas.save()
            canvas.translate(viewW / 2f + panX, viewH / 2f + panY)
            canvas.rotate(rotation)
            canvas.scale(s, s)
            canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, paint)
            canvas.restore()
        }
        // 2) 真墨上屏判定 → 清覆盖
        val now = android.os.SystemClock.elapsedRealtime()
        if (pendingClearAfterMs != Long.MIN_VALUE &&
            now >= pendingClearAfterMs &&
            vm.lastRenderCommitElapsedMs >= pendingClearAfterMs - CLEAR_LAG_MS
        ) {
            vm.clearWetOverlayDisplay()
            pendingClearAfterMs = Long.MIN_VALUE
        }
        // 3) 预测短 streak: 上帧预测点 → 本帧预测点
        queue.dropOlderThan(now - STALE_MS)
        val out = ArrayList<WetInkQueue.MutableSample>(4)
        queue.snapshot(out)
        if (out.size >= 2 && shouldPreview(tool)) {
            val fraction = com.reverie.paint.core.ReverieCoreBridge.brushPressureFraction(
                out.last().pressure,
            )
            paint.alpha = 200
            paint.strokeWidth = (vm.brushSize.toFloat() * fraction).coerceAtLeast(0.5f)
            val bmpW = (vm.displayBitmap?.width ?: vm.docWidth).toFloat()
            val bmpH = (vm.displayBitmap?.height ?: vm.docHeight).toFloat()
            val tx = if (vm.docWidth > 0) bmpW / vm.docWidth else 1f
            val ty = if (vm.docHeight > 0) bmpH / vm.docHeight else 1f
            val s = (zoom * fitScale).coerceAtLeast(0.0001f)
            canvas.save()
            canvas.translate(viewW / 2f + panX, viewH / 2f + panY)
            canvas.rotate(rotation)
            canvas.scale(s, s)
            for (i in 1 until out.size) {
                canvas.drawLine(
                    out[i - 1].x * tx - bmpW / 2f, out[i - 1].y * ty - bmpH / 2f,
                    out[i].x * tx - bmpW / 2f, out[i].y * ty - bmpH / 2f,
                    paint,
                )
            }
            canvas.restore()
        } else if (out.isEmpty()) {
            lastPredicted = null
        }
    }

    companion object {
        const val STALE_MS = 120L
        const val CLEAR_LAG_MS = 80L
    }
}
```

- [ ] **Step 2: CanvasTouchView 调整**

- 订阅侧删除真实采样喂队列逻辑: `v.strokeSampleListener = ...` 整块删除 (VM 侧 Task 4 一并删字段); attach 里只建控制器。
- 预测分支的 `wetPreview?.queue?.append(pd.x, pd.y, pressure, ..., true)` 改为 `wetPreview?.appendPredicted(pd.x, pd.y, pressure, elapsedRealtime)`。
- 笔画 ACTION_UP 成功路径调 `wetPreview?.onStrokeUp(elapsedRealtime)`; CANCEL 仍走 `onStrokeCancel()`。
- `onDraw` 中湿墨调用不变 (draw 内部已重构)。

- [ ] **Step 3: 编译验证 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/reverie/paint/ui/painting/canvas/
git commit -m "feat(ui): 湿墨层升级为真实 dab 覆盖合成器, 预测改为笔尖短 streak"
```

### Task 4: 退役折线专用代码

**Files:**
- Modify: `core/PaintViewModel.kt` (删 strokeSampleListener 字段)
- Modify: `core/PaintViewModelTools.kt` (删 touchMove 内 invoke)
- Modify: `core/ReverieCoreBridge.kt` + `cpp/reverie_jni_brush.cpp` + `cpp/ReverieCore.h/.cpp` (删 brushTipKind 四处, 先 grep 确认无引用)
- Modify: `ui/painting/panels/SettingsTabPage.kt` (「落笔即时预览」副文改为「笔画以真实笔触当帧显示，全部笔刷适用」)

- [ ] **Step 1: 全文检索 `strokeSampleListener`/`brushTipKind` 引用清单, 逐一删除**
- [ ] **Step 2: 编译 + 单元测试 + 提交**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
git add -A app/src
git commit -m "refactor(ui,core): 退役折线湿墨与笔尖类型门控, 由真湿墨层取代"
```

### Task 5: 全量构建与交付

- [ ] `./scripts/build_native.sh` 全量通过 → 同步 `third_party/android-native-libs/libreverie_jni.so`
- [ ] `assembleDebug` + `adb install -r` + 启动
- [ ] 真机回归清单交用户: 形状尖/颗粒尖/软边/低流量/空气笔刷 即时性与无缝替换; 橡皮涂抹无覆盖但行为不变; 选区内作画约束正确; 混合模式图层短暂近似可接受度; 录制回放不受污染; 双开关生效
