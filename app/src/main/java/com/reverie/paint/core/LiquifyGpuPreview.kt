/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import com.reverie.paint.BuildConfig
import com.reverie.paint.model.CanvasViewTransform
import com.reverie.paint.model.LiquifyDirtyRegion
import com.reverie.paint.model.LiquifyGridMeta
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * Phase 2B: 液化交互态预览的 **AGSL(RuntimeShader)** 版本。
 *
 * 放在 `core/` 的原因: 数据(裁剪像素/网格)由引擎线程取, 绘制在 `ui/painting/canvas` 里发生,
 * 而依赖方向只能 `ui/ → core/ → model/`, 所以共享状态必须留在 core(ui 只调用 [draw])。
 *
 * 与 Phase 2A-2(引擎侧 CPU 反向采样)的分工:
 *  - 引擎只"给料": 未形变的 `bounds` 裁剪(源纹理) + 位移网格(位移纹理);
 *  - 位移采样在显示分辨率上做 `dst(p) = src(p - offset(p))`, 因此预览清晰度不再受
 *    Phase 2A-2 的 192px 上限约束, 每次 dab 也不再需要 CPU 采样;
 *  - 网格双线性插值交给纹理采样器(硬件线性过滤); 网格点与 `run()` 是同一批 ⇒ 几何同源。
 *
 * 三条硬约束:
 *  1. 所有 JNI 取数都在引擎线程(`PaintViewModel.pollLiquifyGpuPreview`)做, UI 线程只 [draw];
 *  2. 开关关闭 / API < 33 / 初始化抛错 → [prepareForGesture] 让引擎继续自己叠加 CPU 预览,
 *     绝不出现"引擎不画、UI 也不画"的空窗;
 *  3. 不改文档: 抬笔仍由 Krita 精确 materialize(撤销仍是"一次手势一条 KisTransaction")。
 *
 * 已知近似(与已验证的 CPU 版语义一致, 因此可逐图对照):
 *  - overlay 是 source-over 叠加, 半透明内容会与底层未形变像素叠加(与 `blendLiquifyPreview` 同);
 *  - 预览始终画在画布最上层, 多图层混合的精确顺序仍由抬笔后的 Krita 决定。
 */
internal object LiquifyGpuPreview {

    private const val PROP_GPU = "debug.reverie.liquifyPreviewGpu"

    /** [hostDrawOverride] 的取值 —— 不改默认 / 强制引擎 CPU 叠加 / 强制 AGSL 覆盖层 / 强制 GLES 覆盖层。 */
    const val HOST_OVERRIDE_AUTO = 0
    const val HOST_OVERRIDE_ENGINE = 1
    const val HOST_OVERRIDE_AGSL = 2
    const val HOST_OVERRIDE_GLES = 3

    /**
     * 应用内覆盖(debug 设置页; **没有数据线时唯一的切换入口**, 见 `PerfHud.SettingsSection`)。
     *
     * 0 = 不改(跟 property / 构建期档位); 1 = 强制引擎侧 CPU 叠加; 2 = 强制 AGSL 覆盖层;
     * 3 = 强制 GLES 覆盖层(不可用时退回默认判定, 绝不出现"没人画")。
     * 判定发生在**手势开始**, 所以改完下一段手势生效。
     */
    @Volatile
    var hostDrawOverride: Int = HOST_OVERRIDE_AUTO

    /** 实验 A: 代理分辨率百分比(10..100)。运行时覆盖构建期档位。 */
    private const val PROP_PROXY = "debug.reverie.lqproxy"
    private const val MIN_PROXY_PERCENT = 10

    /**
     * 构建期注入的实验档位(`-PlqTestProfile=<n>`, 见 `app/build.gradle.kts`):
     * 0 = 不改默认行为; 1 = 默认 AGSL 预览 + latest-state-wins; 2 = 默认 CPU 预览 + latest-state-wins;
     * 3 = 默认 AGSL 预览但不做 latest-state-wins。**只改默认值**, 任一 property 仍可覆盖。
     * 存在的意义: 没有数据线时也能直接装包对照(见 docs/RENDER-OPTIMIZATION.md §4.11)。
     */
    private val testProfile = BuildConfig.LQ_TEST_PROFILE

    /** 实验 A: 构建期注入的代理分辨率百分比(`-PlqProxy=<n>`, 默认 100)。 */
    private val proxyTestPercent = BuildConfig.LQ_PROXY_PERCENT

    /**
     * 位移采样 shader。输入:
     *  - `uSrc`  : 未形变的 bounds 裁剪(1 纹素 = 1 文档像素, CLAMP + 线性);
     *  - `uGrid` : 位移纹理(R = dx, G = dy, 单位文档像素, 线性插值即双线性);
     *  - `uOrigin`/`uEx`/`uEy`: 文档→屏幕仿射(原点 + 两个基向量), 这里做 2x2 求逆变成
     *    屏幕→文档, 于是缩放/旋转/平移自动跟随画布;
     *  - `uCropOrigin`/`uGridOrigin`/`uGridStep`/`uGridSize`: 裁剪与网格在文档坐标里的原点与步长;
     *  - `uSrcScale`: 代理分辨率比例(1.0 = 全分辨率源纹理, <1 = 下采样后的代理)。
     */
    private val SHADER_SRC = """
        uniform shader uSrc;
        uniform shader uGrid;
        uniform float2 uOrigin;
        uniform float2 uEx;
        uniform float2 uEy;
        uniform float2 uCropOrigin;
        uniform float2 uGridOrigin;
        uniform float2 uGridStep;
        uniform float2 uGridSize;
        uniform float uSrcScale;

        half4 main(float2 fragCoord) {
            float2 d = fragCoord - uOrigin;
            float det = uEx.x * uEy.y - uEx.y * uEy.x;
            if (abs(det) < 0.000001) {
                return half4(0.0);
            }
            // 屏幕 -> 文档: [uEx uEy] 的逆矩阵(列向量分别是 dx=1 / dy=1 的屏幕位移)
            float2 doc = float2((uEy.y * d.x - uEy.x * d.y) / det,
                                (uEx.x * d.y - uEx.y * d.x) / det);
            float2 g = (doc - uGridOrigin) / uGridStep;
            float2 gMax = uGridSize - 1.0;
            if (g.x < 0.0 || g.y < 0.0 || g.x > gMax.x || g.y > gMax.y) {
                return half4(0.0);
            }
            float2 off = uGrid.eval(g + 0.5).rg;
            // 代理分辨率: uSrc 可能是按比例下采样后的源纹理, 采样坐标要同比缩放(100% 时为 1.0)
            return uSrc.eval(((doc - uCropOrigin) - off) * uSrcScale);
        }
    """.trimIndent()

    /** 本次手势是否走 GPU 预览(由 [prepareForGesture] 设定; UI 线程读)。 */
    @Volatile
    var requested = false

    /** 是否已有可绘制内容(UI 线程按帧读取)。 */
    @Volatile
    var active = false

    /** Phase 3B: 本次手势累计的源纹理上传次数。正常应恒为 1 —— 源裁剪只在 rebase 时变,
     *  拖动期间不重传; 若高速操作中发现 > 1, 说明 rebase 过频, 是需要优先修的问题。 */
    @Volatile
    var sourceUploadCount: Long = 0L
        private set

    /** 干预实验(§4.15): 本次手势累计"暂存"的预览状态更新次数(一帧内可能多次)。 */
    @Volatile
    var previewUpdates: Long = 0L
        private set

    /** 干预实验(§4.15): 本次手势累计构建(上传)网格纹理的次数 —— 目标 ≤ 帧数(即 ≤1/帧)。 */
    @Volatile
    var gridUploads: Long = 0L
        private set

    // VSYNC 绑定: 引擎线程只"暂存"最新状态, 纹理构建/上传推迟到 draw()(每帧一次)。
    // JNI 每次返回新数组, 直接持有引用即可, 不复制(热路径零分配)。
    private var pendingCrop: IntArray? = null
    private var pendingSrc: ByteArray? = null
    private var pendingGrid: FloatArray? = null
    private var stateDirty = false

    // Liquify V2 · Phase 2 (docs/LIQUIFY-V2-PLAN.md §4): 本帧**文档脏区** —— 由前后两帧位移场的
    // 差异推出, 交给 UI 侧做局部失效(覆盖层不再每 dab 整屏重绘)。只在引擎线程写(update),
    // UI 线程读(经 CanvasTouchView.post), 故全部 @Volatile。
    // JNI 每次返回新数组 ⇒ 直接持有上一帧引用做差分, 不复制。
    private var dirtyPrevGrid: FloatArray? = null
    private var dirtyPrevCropKey = 0L
    private val dirtyRectScratch = IntArray(4)

    /** 本帧脏区是否可用(UI 线程读)。false = 无需重绘。 */
    @Volatile
    var overlayDirtyValid = false
        private set

    /** true = 脏区不可用(首次 / rebase 换了源纹理 / 数据不完整), UI 侧必须整屏回退。 */
    @Volatile
    var overlayDirtyFull = true
        private set

    @Volatile
    var overlayDirtyX = 0
        private set

    @Volatile
    var overlayDirtyY = 0
        private set

    @Volatile
    var overlayDirtyW = 0
        private set

    @Volatile
    var overlayDirtyH = 0
        private set

    /** -1 未判定 / 0 不可用 / 1 可用。判定失败后不再重试, 免得每帧都抛异常。 */
    private var supported = -1

    // 更新(引擎线程)与绘制(UI 线程)共用: 两者都是短操作, 用一把锁换掉全部数据竞争
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var runtime: RuntimeShader? = null
    private var srcBitmap: Bitmap? = null
    private var gridBitmap: Bitmap? = null
    private var srcShader: BitmapShader? = null
    private var gridShader: BitmapShader? = null

    private var cropKey = 0L
    private var cropW = 0
    private var cropH = 0
    private var gridCols = 0
    private var gridRows = 0
    private var gridOriginX = 0f
    private var gridOriginY = 0f
    private var gridStepX = 1f
    private var gridStepY = 1f

    /** [`LiquifyGridMeta.of`] 的输出缓冲 (只在引擎线程用, 免得每个 dab 分配一次)。 */
    private val gridMeta = FloatArray(LiquifyGridMeta.FIELD_COUNT)
    private var cropOriginX = 0f
    private var cropOriginY = 0f
    // 实验 A: 源纹理代理比例(1.0 = 全分辨率)。下面三个只读量供 HUD 对照。
    private var srcScale = 1f
    var proxyWidth: Int = 0
        private set
    var proxyHeight: Int = 0
        private set
    var proxyUploadBytes: Long = 0L
        private set

    private val scratch = FloatArray(2)
    private val corners = FloatArray(8)
    private var drawLeft = 0f
    private var drawTop = 0f
    private var drawRight = 0f
    private var drawBottom = 0f
    private var baseX = 0f
    private var baseY = 0f

    /**
     * 手势开始时的判定(任意线程可调用, **不碰 JNI**): 本次手势由 AGSL / GLES 还是引擎侧 CPU 画预览。
     *
     * 调用方必须在同一手势的 `runCore` 里把返回值写进引擎(`setLiquifyPreviewHostDrawMode`),
     * 这样即使 property 与 Kotlin 侧判定不一致(例如反射读属性失败), 也不会两边都不画。
     *
     * Phase 5 · C2: **GLES 覆盖层共用这一套判定** —— 它同样要求"引擎不自己叠加 CPU 预览",
     * 只是最终出图的是 GLES 渲染线程(见 `LiquifyGlesPreview`)。两者同时打开时 GLES 优先
     * (绘制分流在 `CanvasTouchView.drawCanvas`)。
     *
     * @return -1 = 跟随 property(GPU 可用且开关打开); 0 = 强制引擎侧 CPU 叠加
     */
    fun decideForGesture(): Int {
        // 是否启用 GLES(property/构建档位, **或**设置页强制) + 覆盖层必须真的活着 ——
        // 否则"由它画"会变成没人画(见 LiquifyGlesPreview.setAlive / isOn)
        val glesReady =
            LiquifyGlesPreview.isOn(hostDrawOverride) &&
                LiquifyGlesPreview.alive &&
                !LiquifyGlesPreview.failed
        // 应用内覆盖优先: 没有数据线时 setprop 用不了, 只能靠设置页切换(见 hostDrawOverride)
        val wantGles = when (hostDrawOverride) {
            HOST_OVERRIDE_ENGINE, HOST_OVERRIDE_AGSL -> false
            else -> glesReady
        }
        val wantGpu = when (hostDrawOverride) {
            // 强制引擎侧 CPU 叠加: 连 property 都不看
            HOST_OVERRIDE_ENGINE -> false
            // 强制 AGSL: 仍要过 API 门槛(AGSL 本身就是 API 33 起才有)
            HOST_OVERRIDE_AGSL -> ensureSupported()
            // C3-2: 走 GLES 覆盖层时**不**受 AGSL 的 API 门槛约束 —— 它自己的门槛是
            // API≥26 + ES3(见 LiquifyGlesPreview.platformSupported); 只有落到 AGSL 才需要 API≥33。
            // 否则 API 26~32 的设备会被白挡在 GPU 预览之外(低端机的完整解耦仍见 C5)。
            else -> if (wantGles) true else agslRequested() && ensureSupported()
        }
        requested = wantGpu
        sourceUploadCount = 0L
        previewUpdates = 0L
        gridUploads = 0L
        resetDirtyBaseline()
        synchronized(lock) { clearStagingLocked() }
        if (!wantGpu) {
            active = false
        }
        // GLES 侧的生命周期跟着同一次判定走: 本次由它画就开新手势, 否则确保它不再持有旧内容
        if (wantGles) {
            if (wantGpu) {
                LiquifyGlesPreview.beginGesture()
            } else {
                LiquifyGlesPreview.clear()
            }
        }
        // 1 = 强制主机侧(AGSL)绘制; 0 = 强制引擎侧 CPU 叠加; -1 = 交给引擎按 property 判断。
        // 档位 2 是"CPU 预览对照", 用 0 显式打开 —— 否则"引擎要预览"这件事只能靠 property 传达,
        // 无数据线的设备就没法测。
        return when {
            wantGpu -> 1
            // 显式选了"引擎 CPU"就显式写 0: 否则 property 还开着时引擎会自己走 AGSL, 白选
            hostDrawOverride == HOST_OVERRIDE_ENGINE -> 0
            testProfile == 2 -> 0
            else -> -1
        }
    }

    /** property / 构建期档位是否要求走 AGSL 覆盖层(与 GLES 覆盖层无关)。 */
    private fun agslRequested(): Boolean =
        propBool(PROP_GPU) || testProfile == 1 || testProfile == 3

    /** 平台是否支持(只看 API 等级; shader 编译在 [update] 里做, 失败即回退)。 */
    private fun ensureSupported(): Boolean {
        if (supported >= 0) return supported == 1
        supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 1 else 0
        return supported == 1
    }

    private fun fail() {
        supported = 0
        active = false
        requested = false
        synchronized(lock) {
            clearStagingLocked()
            runtime = null
            srcShader = null
            gridShader = null
            // 不 recycle(): 上一帧可能仍被渲染线程引用, 交给 GC 更安全
            srcBitmap = null
            gridBitmap = null
            cropKey = 0L
            paint.shader = null
        }
        try {
            // 让引擎立刻回到 CPU 叠加, 宁可预览降级也不能没有预览
            ReverieCoreBridge.setLiquifyPreviewHostDrawMode(0)
        } catch (_: Throwable) {
            // 引擎不可达则忽略
        }
    }

    /** 手势结束/取消, 或引擎报告没有源裁剪时调用。 */
    fun clear() {
        active = false
        resetDirtyBaseline()
        // GLES 覆盖层走同一条生命周期(它自己不会收到"手势结束"的通知)。
        // 判定用 isOn: 设置页强制 GLES(无数据线场景)时 enabled 仍为 false, 也要能清掉。
        if (LiquifyGlesPreview.isOn(hostDrawOverride)) LiquifyGlesPreview.clear()
        synchronized(lock) {
            gridShader = null
            gridBitmap = null
            cropKey = 0L
            clearStagingLocked()
        }
    }

    /** 丢弃尚未提交的暂存状态(调用方需持有 [lock])。 */
    private fun clearStagingLocked() {
        pendingCrop = null
        pendingSrc = null
        pendingGrid = null
        stateDirty = false
    }

    /**
     * 丢弃脏区基线: 下一次 [update] 必然判为"不可比较", 于是 UI 侧整屏重绘一次。
     * 手势开始 / 结束 / 失败回退时调用 —— 宁可多画一次整屏, 也不让旧基线算出错误的局部矩形。
     */
    private fun resetDirtyBaseline() {
        dirtyPrevGrid = null
        dirtyPrevCropKey = 0L
        overlayDirtyValid = false
        overlayDirtyFull = true
        overlayDirtyX = 0
        overlayDirtyY = 0
        overlayDirtyW = 0
        overlayDirtyH = 0
    }

    /**
     * Phase 2: 由前后两帧位移场算出本帧文档脏区(引擎线程, 纯本地数组运算, 无分配)。
     *
     * 判为 [LiquifyDirtyRegion.CHANGED] 时 UI 侧只重绘该矩形映射到屏幕的包围盒;
     * [LiquifyDirtyRegion.NO_CHANGE] 时连重绘都可以省掉; 其余情况整屏回退。
     */
    private fun updateDirtyRegion(crop: IntArray, grid: FloatArray?) {
        val key = cropKeyOf(crop)
        val code =
            if (grid == null || key != dirtyPrevCropKey) {
                // 换了源裁剪(rebase)或没有网格: 位移场不可比, 必须整屏
                LiquifyDirtyRegion.INCOMPARABLE
            } else {
                LiquifyDirtyRegion.changedDocRect(dirtyPrevGrid, grid, dirtyRectScratch)
            }
        dirtyPrevCropKey = key
        dirtyPrevGrid = grid
        when (code) {
            LiquifyDirtyRegion.CHANGED -> {
                overlayDirtyX = dirtyRectScratch[0]
                overlayDirtyY = dirtyRectScratch[1]
                overlayDirtyW = dirtyRectScratch[2]
                overlayDirtyH = dirtyRectScratch[3]
                overlayDirtyFull = false
                overlayDirtyValid = true
            }
            LiquifyDirtyRegion.NO_CHANGE -> {
                overlayDirtyW = 0
                overlayDirtyH = 0
                overlayDirtyFull = false
                overlayDirtyValid = false
            }
            else -> {
                overlayDirtyW = 0
                overlayDirtyH = 0
                overlayDirtyFull = true
                overlayDirtyValid = true
            }
        }
    }

    /**
     * 引擎线程调用: **只暂存**最新一帧的裁剪与网格, 不构建任何纹理。
     *
     * 干预实验(§4.15): 把"预览状态更新"与"GPU 上传"解耦 —— 输入可以 120/240Hz, 纹理构建与上传
     * 推迟到 [draw](UI 线程, 每帧一次), 于是**每帧最多上传一次**。这是把"输入频率"从"上传频率"
     * 里剥离出来的关键一步。
     *
     * @param crop `[cropW, cropH, docX, docY, docW, docH, seq]`
     * @param src  仅当裁剪发生变化时非空(RGBA8888, 整段手势只上传一次纹理)
     * @param grid `liquifyGrid()` 的原始数组(每个 dab 都会变; JNI 每次返回新数组, 直接持有即可)
     */
    fun update(crop: IntArray, src: ByteArray?, grid: FloatArray?) {
        if (crop.size < 7 || crop[0] <= 0 || crop[1] <= 0) {
            clear()
            return
        }
        if (!ensureSupported()) {
            fail()
            return
        }
        try {
            // Phase 2: 脏区先算(锁外, 只碰本类私有字段), 再暂存状态给 UI 线程提交
            updateDirtyRegion(crop, grid)
            synchronized(lock) {
                pendingCrop = crop
                if (src != null) pendingSrc = src
                if (grid != null && grid.size >= 8) pendingGrid = grid
                stateDirty = true
                previewUpdates++
            }
        } catch (t: Throwable) {
            fail()
        }
    }

    /**
     * 把暂存状态落到纹理(每帧最多一次, 由 [draw] 调用)。返回本次是否可绘制。
     * 上传计数只在这里增加 ⇒ [gridUploads] ≤ 帧数。
     */
    private fun commitLocked(): Boolean {
        if (!stateDirty) return active
        stateDirty = false
        val crop = pendingCrop
        val src = pendingSrc
        val grid = pendingGrid
        pendingSrc = null
        if (crop == null || crop.size < 7) return false
        val key = cropKeyOf(crop)
        if (src != null && key != cropKey) {
            val bmp = buildSourceBitmap(crop, src)
            // 换新位图而不是原地覆写: copyPixelsFromBuffer 不保证推进 generation id,
            // 原地改内容可能让 GPU 继续用旧纹理; 每次新建则可确定会上传。
            srcBitmap = bmp
            srcShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            cropOriginX = crop[2].toFloat()
            cropOriginY = crop[3].toFloat()
            cropW = crop[0]
            cropH = crop[1]
            cropKey = key
            sourceUploadCount++
            // 实验 A: 源纹理坐标缩放(代理 <100% 时按比例下采样)
            srcScale = bmp.width.toFloat() / crop[0].toFloat()
            proxyWidth = bmp.width
            proxyHeight = bmp.height
            proxyUploadBytes = bmp.width.toLong() * bmp.height.toLong() * 4L
        }
        if (grid != null && grid.size >= 8) {
            applyGridLocked(grid)
            gridUploads++
        }
        val r = runtime
        val ss = srcShader
        val gs = gridShader
        if (r != null && ss != null && gs != null) {
            r.setInputShader("uSrc", ss)
            r.setInputShader("uGrid", gs)
            r.setFloatUniform("uCropOrigin", cropOriginX, cropOriginY)
            r.setFloatUniform("uGridOrigin", gridOriginX, gridOriginY)
            r.setFloatUniform("uGridStep", gridStepX, gridStepY)
            r.setFloatUniform("uGridSize", gridCols.toFloat(), gridRows.toFloat())
            r.setFloatUniform("uSrcScale", srcScale)
            paint.shader = r
            active = true
            return true
        }
        return false
    }

    /**
     * 实验 A: 按"代理分辨率"百分比构建源纹理。
     *
     * 100 = 原始 1:1(与历史行为逐像素一致); <100 时先按比例下采样再上传 —— 形变几何不变,
     * 但纹理带宽/显存随之下降。下采样只在 rebase(整段手势一次)发生, 拖动期间不重算。
     */
    private fun buildSourceBitmap(crop: IntArray, src: ByteArray): Bitmap {
        val full = Bitmap.createBitmap(crop[0], crop[1], Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(ByteBuffer.wrap(src))
        val pct = proxyPercent()
        if (pct >= 100) return full
        val pw = (crop[0] * pct / 100).coerceAtLeast(1)
        val ph = (crop[1] * pct / 100).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(full, pw, ph, true)
    }

    /**
     * 应用内覆盖(debug 设置页): 0 = 不覆盖。>0 时优先于 property 与构建期档位 ——
     * 这是**没有数据线**(无法 setprop)时做实验 A 对照的唯一入口。
     */
    @Volatile
    var proxyPercentOverride: Int = 0

    /** 生效的代理分辨率百分比: 应用内覆盖 > 运行时 property > 构建期档位(默认 100)。 */
    private fun proxyPercent(): Int {
        val ov = proxyPercentOverride
        if (ov in MIN_PROXY_PERCENT..100) return ov
        val prop = PerfTrace.debugPropInt(PROP_PROXY, -1)
        val pct = if (prop >= 0) prop else proxyTestPercent
        return pct.coerceIn(MIN_PROXY_PERCENT, 100)
    }

    /** 网格 → 位移纹理(R=dx, G=dy, 半精度浮点), 并从真实网格点推原点和步长。 */
    private fun applyGridLocked(grid: FloatArray) {
        // 布局解析与"原点/步长"的口径见 LiquifyGridMeta —— 它与 GLES 侧(Phase 5 · C2)共用
        // 同一个实现, 这是"两条预览路径画质等价"的前提。
        if (!LiquifyGridMeta.of(grid, gridMeta)) return
        val cols = gridMeta[0].toInt()
        val rows = gridMeta[1].toInt()
        val count = gridMeta[6].toInt()

        val shorts = ShortArray(count * 4)
        for (i in 0 until count) {
            val base = LiquifyGridMeta.HEADER + i * LiquifyGridMeta.STRIDE
            shorts[i * 4] = Half.toHalf(grid[base + 2])     // dx
            shorts[i * 4 + 1] = Half.toHalf(grid[base + 3]) // dy
        }
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.RGBA_F16)
        bmp.copyPixelsFromBuffer(ShortBuffer.wrap(shorts))

        gridOriginX = gridMeta[2]
        gridOriginY = gridMeta[3]
        gridStepX = gridMeta[4]
        gridStepY = gridMeta[5]
        gridCols = cols
        gridRows = rows

        // 用局部变量传给 setInputShader: 成员是 var(可为空), Kotlin 不会对它做智能转换
        val gs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        gridShader = gs
        gridBitmap = bmp
        val r = runtime
        if (r == null) {
            val created = RuntimeShader(SHADER_SRC)
            created.setInputShader("uGrid", gs)
            runtime = created
        } else {
            r.setInputShader("uGrid", gs)
        }
    }

    /** UI 线程调用: 按当前视图变换把形变后的内容叠到画布之上(仅覆盖网格范围, 其余透明)。 */
    fun draw(canvas: Canvas, vt: CanvasViewTransform) {
        synchronized(lock) {
            // VSYNC 绑定: 一帧内的多次 update() 合并在这一次提交里, 纹理上传 ≤ 1/帧
            if (!commitLocked()) return
            val r = runtime ?: return
            if (srcShader == null || gridShader == null) return
            if (cropW <= 0 || cropH <= 0) return
            // 文档 -> 屏幕的仿射: 用原点 + 两个基向量表达, 缩放/旋转/平移自然包含在内
            vt.docToScreen(0f, 0f, scratch)
            baseX = scratch[0]
            baseY = scratch[1]
            r.setFloatUniform("uOrigin", baseX, baseY)
            vt.docToScreen(1f, 0f, scratch)
            r.setFloatUniform("uEx", scratch[0] - baseX, scratch[1] - baseY)
            vt.docToScreen(0f, 1f, scratch)
            r.setFloatUniform("uEy", scratch[0] - baseX, scratch[1] - baseY)
            // 只画裁剪区的屏幕包围盒(留 8px 余量): shader 用绝对坐标取值, 缩小绘制范围
            // 只省填充率、不改结果 —— 4K 屏上整屏跑一遍是白白的带宽
            screenBoundsOfCrop(vt)
            canvas.drawRect(
                drawLeft - 8f, drawTop - 8f, drawRight + 8f, drawBottom + 8f, paint,
            )
        }
    }

    /** 裁剪矩形(文档坐标)四角映射到屏幕后的轴对齐包围盒。 */
    private fun screenBoundsOfCrop(vt: CanvasViewTransform) {
        val x0 = cropOriginX
        val y0 = cropOriginY
        val x1 = cropOriginX + cropW
        val y1 = cropOriginY + cropH
        vt.docToScreen(x0, y0, scratch)
        corners[0] = scratch[0]
        corners[1] = scratch[1]
        vt.docToScreen(x1, y0, scratch)
        corners[2] = scratch[0]
        corners[3] = scratch[1]
        vt.docToScreen(x0, y1, scratch)
        corners[4] = scratch[0]
        corners[5] = scratch[1]
        vt.docToScreen(x1, y1, scratch)
        corners[6] = scratch[0]
        corners[7] = scratch[1]
        var minX = corners[0]
        var maxX = corners[0]
        var minY = corners[1]
        var maxY = corners[1]
        var i = 2
        while (i < 8) {
            val x = corners[i]
            val y = corners[i + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
            i += 2
        }
        drawLeft = minX
        drawTop = minY
        drawRight = maxX
        drawBottom = maxY
    }

    /** 裁剪指纹: 只用于判断"是否需要重新上传源纹理", 冲突最坏是多上传一次, 无正确性风险。 */
    fun cropKeyOf(crop: IntArray): Long {
        var k = crop[0].toLong() * 1009L + crop[1]
        k = k * 1009L + crop[2]
        k = k * 1009L + crop[3]
        return k
    }

    /** 读取引擎侧同名诊断 property(只读, 不写); 反射不可用/属性不存在都当作"关"。 */
    private fun propBool(key: String): Boolean = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        val raw = get.invoke(null, key, "") as? String
        (raw?.trim()?.toIntOrNull() ?: 0) != 0
    } catch (_: Throwable) {
        false
    }
}
