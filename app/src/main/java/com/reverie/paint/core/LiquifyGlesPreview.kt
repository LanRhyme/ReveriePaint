/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.Build
import com.reverie.paint.BuildConfig
import com.reverie.paint.model.CanvasViewTransform
import com.reverie.paint.model.LiquifyGridMeta
import com.reverie.paint.model.LiquifyPath
import kotlin.math.hypot

/**
 * Phase 5 · C2: 液化 GLES 覆盖层的**数据转发层** (见 docs/LIQUIFY-PHASE5-GLES-PLAN.md §4 · C2-1)。
 *
 * 为什么需要它 (以及为什么放在 `core/`):
 *  - 取数必须在**引擎线程**(`PaintViewModel.pollLiquifyGpuPreview`, 复用现成的 JNI 取数, 不加新接口);
 *  - 绘制在另一条线程 (`LiquifyGlesOverlay` 的渲染线程) 上, 且它只认 EGL/GLES;
 *  - 依赖方向只能 `ui/ → core/`, 所以"引擎线程写、UI 线程写仿射、渲染线程读"的共享状态必须留在 core。
 *
 * 三条通道 (与 AGSL 版 [LiquifyGpuPreview] 的取数语义**完全一致**, 便于 A/B 对照):
 *  1. 引擎线程 [update]: 暂存最新一帧的 `crop`(源裁剪元信息) / `src`(未形变像素, 只在 rebase 后
 *     出现一次) / `grid`(位移网格, 每个 dab 都变)。**只存引用, 不复制像素**;
 *  2. UI 线程 [pushAffine]: 每帧喂一次"文档 → 视图像素"的仿射 (原点 + 两个基向量),
 *     于是缩放/旋转/平移自动跟随, 且与 AGSL 版是同一套公式;
 *  3. 渲染线程 [awaitFrame]: 阻塞取一帧快照 (零分配: 写进调用方复用的 [Frame])。
 *
 * Phase 5 · C3 追加的第四条通道: [pushDab] —— 液化提交点 (`CanvasTouchView.liquifyFlushNow`) 在跑
 * JNI 循环时**多推一份同样的补点参数**给 GLES 层, 覆盖层据此在自己的**常驻浮点位移场**上逐 dab
 * 局部累加 (见 docs/LIQUIFY-C3-FIELD-PLAN.md §2)。它不新增任何 JNI, 也不参与引擎网格; 目的是让
 * 屏幕上的形变不再被 Krita 的 16px 网格量化 —— 台阶感 / 窗口重锚定接缝 / 拖动期 rebase 尖峰
 * 三者的共同根源都在那次量化上。
 *
 * 线程与生命周期约定:
 *  - 所有字段读写都在 [lock] 下, 或明确标注单写者;
 *  - 手势开始 [beginGesture] → 结束/取消 [clear] (由 [LiquifyGpuPreview] 转发), `clear` 之后
 *    渲染线程会收到一次 revision, 把覆盖层清成全透明 —— 不能让上一次的形变留在屏幕上。
 *
 * 健壮性: GLES 侧任何一环失败都只调 [markFailed] (本对象内, **不碰 JNI**), 由引擎线程在下一帧
 * 把主机侧绘制判定交回引擎 CPU 预览 (见 `PaintViewModel.pollLiquifyGpuPreview`); 绝不允许出现
 * "引擎不画、GPU 也不画"的空窗。
 */
internal object LiquifyGlesPreview {

    private const val PROP_GLES = "debug.reverie.liquifyGles"

    /** C3: 常驻位移场开关 (**默认关**; 关掉即回到 C2 的网格路径)。 */
    private const val PROP_FIELD = "debug.reverie.lqfield"

    /** C3: 场的降采样比(1/2/4, 默认 1)。内存/带宽不够时下压。 */
    private const val PROP_FIELD_RES = "debug.reverie.lqfieldRes"

    /** C3: 场纹理的像素预算 —— 2M px × 8B = 16MB。超预算先提高降采样比, 再超则回退网格。 */
    const val FIELD_MAX_PX = 2_000_000

    /** C3: 补点参数缓冲的步长与容量(每补点 7 个 float, 见 [Frame.dabs])。 */
    const val DAB_STRIDE = 7
    const val DAB_CAPACITY = 256

    /** C3: [fieldOverride] 的取值 —— 跟随 property / 强制常驻场 / 强制网格。 */
    const val FIELD_OVERRIDE_AUTO = 0
    const val FIELD_OVERRIDE_ON = 1
    const val FIELD_OVERRIDE_OFF = 2

    /**
     * 开关 (**默认关**)。诊断 property 只读一次, 避免每帧反射。
     * 无数据线时用构建期档位: `./gradlew :app:assembleDebug -PlqTestProfile=4`。
     *
     * 门槛: API ≥ 26 —— 位移场要上传成半精度纹理, 浮点→半精度的转换用 `android.util.Half`。
     * **注意**: "是否接管绘制"另有 AGSL 侧的门槛 (API 33, 见 [LiquifyGpuPreview.decideForGesture]:
     * 主机侧绘制判定与 AGSL 共用); 低端设备的解耦与降级放在 C5。
     */
    /** 平台能力门槛(与任何开关无关): 半精度位移纹理的转换用 `android.util.Half` ⇒ API ≥ 26。 */
    val platformSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    val enabled: Boolean by lazy {
        if (!platformSupported) return@lazy false
        if (BuildConfig.LQ_TEST_PROFILE == 4) return@lazy true
        propInt(PROP_GLES, 0) != 0
    }

    /**
     * C3: 应用内"位移场来源"覆盖 (0 = 自动 / 1 = 常驻场 / 2 = Krita 网格)。
     *
     * 与 [LiquifyGpuPreview.hostDrawOverride] 同性质: **没有数据线时**做"场 vs 网格"A/B 的唯一入口
     * (判定在**手势开始**, 见 [fieldArmed]); 正式版没有该设置项, 恒为"自动"。
     */
    @Volatile
    var fieldOverride: Int = FIELD_OVERRIDE_AUTO

    /** C3: property 是否要求常驻场(只读一次, 避免每帧反射)。 */
    private val fieldByProp: Boolean by lazy { propInt(PROP_FIELD, 0) != 0 }

    /** C3: 常驻场是否启用(读 [fieldOverride] 与 property)。 */
    val fieldEnabled: Boolean
        get() = when (fieldOverride) {
            FIELD_OVERRIDE_ON -> true
            FIELD_OVERRIDE_OFF -> false
            else -> fieldByProp
        }

    /** C3: 场的降采样比(property, 只读一次; 1..4)。 */
    val fieldRes: Int by lazy { propInt(PROP_FIELD_RES, 1).coerceIn(1, 4) }

    /**
     * C3: 本段手势是否走常驻场 —— **在手势开始时冻结** (与"预览由谁画"同理)。
     *
     * 为什么必须冻结: 场是从 0 开始逐 dab 累加的, 半路切换会让已经累加的形变凭空消失
     * (网格路径没有这个累积过程, 因为它每次都从 Krita 重取完整位移)。
     */
    @Volatile
    var fieldArmed = false
        private set

    /** C3: 补点缓冲溢出的丢弃数 (HUD 读数; 渲染线程跟得上时应恒为 0)。 */
    @Volatile
    var dabsDropped = 0L
        private set

    /** GLES 侧已确认不可用 (EGL/着色器/交换失败)。置上之后本次会话不再尝试, 由引擎 CPU 预览兜底。 */
    @Volatile
    var failed = false
        private set

    /**
     * 这条路径是否被启用(property / 构建期档位, **或** 设置页强制 GLES)。
     *
     * 为什么"设置页强制"要单独认: 没有数据线时 setprop 用不了, 应用内开关是唯一入口, 而那时
     * `enabled` 仍是 false —— 于是覆盖层既不挂载也不接管, 开关看着像坏的。
     * 平台门槛永远有效(拿不到 `Half` 就不能上传半精度位移场)。
     *
     * @param hostOverride 见 [LiquifyGpuPreview.HOST_OVERRIDE_GLES]
     */
    fun isOn(hostOverride: Int): Boolean =
        enabled ||
            (platformSupported &&
                (
                    // C3-2: "位移场 = 常驻浮点场"本身就意味着必须有 GPU 覆盖层来承载它 ⇒ 它同时
                    // 打开"挂载"与"由谁画"两条判定, 用户只需要一个开关(不必再单独选 GLES)。
                    fieldOverride == FIELD_OVERRIDE_ON ||
                        hostOverride == LiquifyGpuPreview.HOST_OVERRIDE_GLES
                    ))

    /**
     * 覆盖层自己是否**真的活着**(SurfaceTexture 就绪 ↔ 销毁/脱离), 由 `LiquifyGlesOverlay` 置位。
     *
     * 为什么判定要带上它: "由谁画"发生在**手势开始**, 而画面要等渲染线程出图才存在 ——
     * 若覆盖层根本不在(例如 property 是运行中改的、页面没有重组)或 Surface 已销毁, 判定却选了 GLES,
     * 就会出现"引擎不画、GPU 也不画"。带上存活状态后, 这种时刻 GLES 主动让位, 由引擎 CPU 预览兜底。
     */
    @Volatile
    var alive = false
        private set

    /**
     * SurfaceTexture 就绪 / 销毁。销毁时顺带 [clear]: 若手势仍在进行, 预览当场交回 AGSL 或引擎。
     * (引擎侧仍处于"主机侧绘制"判定, 所以理论上不会有真空期: 要么 AGSL 接手, 要么显式恢复 CPU 预览。)
     */
    fun setAlive(value: Boolean) {
        alive = value
        if (!value) clear()
    }

    /** 本次手势是否由 GLES 覆盖层绘制 (引擎线程与 UI 线程都读)。 */
    @Volatile
    var requested = false
        private set

    /** 是否已有可绘制的暂存内容 (HUD / 诊断用)。 */
    @Volatile
    var active = false
        private set

    // ---------------- HUD 计数 (语义与 LiquifyGpuPreview 的同名字段一致) ----------------

    /** 引擎侧的"暂存"次数 (一帧内可能多次)。 */
    @Volatile
    var previewUpdates = 0L
        private set

    /** 位移网格纹理**实际上传**次数 (渲染线程写; 目标 ≤ 帧数)。 */
    @Volatile
    var gridUploads = 0L
        private set

    /** 源纹理**实际上传**次数 (目标 = 1/手势; > 1 说明 rebase 过频)。 */
    @Volatile
    var sourceUploadCount = 0L
        private set

    /** 渲染线程实际提交的帧数 (诊断: 判断"是等状态还是空转")。 */
    @Volatile
    var renderedFrames = 0L
        private set

    /** 源纹理尺寸 (= 裁剪尺寸; GLES 路径不做代理降采样, 所以这里是 1:1)。 */
    @Volatile
    var textureWidth = 0
        private set

    @Volatile
    var textureHeight = 0
        private set

    // ---------------- 共享状态 ----------------

    private val lock = Any()

    /** 数据或仿射任一变化 +1; 渲染线程据此判断"要不要重画"。 */
    private var revision = 0L

    // C3-2: 覆盖层要绘制的文档矩形(受影响区并集)。0 尺寸 = 用整块裁剪(经典路径的语义)。
    // 场通路的"裁剪"是**整篇文档**(源像素必须覆盖被拉进来的区域), 若覆盖层照旧按裁剪铺满,
    // 整屏都会被目标图层盖住(其它图层的合成就看不见了) ⇒ 由 UI 侧给出真正受影响的矩形。
    private var drawX = 0f
    private var drawY = 0f
    private var drawW = 0f
    private var drawH = 0f

    // C3-2: 抬笔回读的 rendezvous(UI 线程请求 → 渲染线程离屏渲染 + glReadPixels → 唤醒 UI)。
    private var commitReq: IntArray? = null
    private var commitPixels: ByteArray? = null
    private var commitDone = true

    // C3: 补点缓冲 (UI 线程追加, 渲染线程 [takeDabs] 取走) + 源裁剪世代。
    // srcGen 的作用只有一个: 告诉渲染线程"源换了 ⇒ 场要按新裁剪重建并清零"。它同时表示
    // "此前的补点已经物化进源像素"(rebase 的语义), 所以渲染线程在清零后才累加本帧拿到的补点。
    private val pendingDabs = FloatArray(DAB_CAPACITY * DAB_STRIDE)
    private var pendingDabCount = 0
    private var srcGen = 0L

    // 引擎线程写: 最新的源裁剪 + 位移网格
    private var cropW = 0
    private var cropH = 0
    private var cropOriginX = 0f
    private var cropOriginY = 0f
    private var pendingSrc: ByteArray? = null
    private var pendingGrid: FloatArray? = null
    private var gridCols = 0
    private var gridRows = 0
    private var gridOriginX = 0f
    private var gridOriginY = 0f
    private var gridStepX = 1f
    private var gridStepY = 1f
    private val gridMeta = FloatArray(LiquifyGridMeta.FIELD_COUNT)

    // UI 线程写: 文档 → 视图像素的仿射 (screen = O + docX * Ex + docY * Ey)
    private var affineOx = 0f
    private var affineOy = 0f
    private var exX = 0f
    private var exY = 0f
    private var eyX = 0f
    private var eyY = 0f
    private val affineScratch = FloatArray(2)

    /**
     * 渲染线程持有的一帧快照 (可复用 ⇒ 零分配)。字段与 shader uniform 一一对应。
     *
     * `src` / `grid` 为**增量**语义: 非空表示"这一帧需要重传对应纹理"。它们的元信息
     * (尺寸/原点/步长) 则每帧都是最新值, 所以渲染线程不用自己攒状态。
     */
    class Frame {
        /** 已取走的 revision (内部用)。 */
        internal var taken = Long.MIN_VALUE

        /** false = 本帧没有可画内容, 调用方只清屏。 */
        var valid = false
        var cropW = 0
        var cropH = 0
        var cropOriginX = 0f
        var cropOriginY = 0f

        /** 非空 = 本帧需要重传源纹理 (rebase 后只出现一次)。 */
        var src: ByteArray? = null

        /** 非空 = 本帧需要重传位移网格 (每个 dab 都会出现)。 */
        var grid: FloatArray? = null

        var gridCols = 0
        var gridRows = 0
        var gridOriginX = 0f
        var gridOriginY = 0f
        var gridStepX = 1f
        var gridStepY = 1f
        var affineOx = 0f
        var affineOy = 0f
        var exX = 0f
        var exY = 0f
        var eyX = 0f
        var eyY = 0f

        /** C3: 本帧是否走常驻场([fieldArmed] 的快照)。 */
        var fieldArmed = false

        /** C3-2: 本帧要绘制的文档矩形([pushDrawRect]; 0 宽高 = 退回用整块裁剪)。 */
        var drawX = 0f
        var drawY = 0f
        var drawW = 0f
        var drawH = 0f

        /** C3: 源裁剪世代(snapshot)。与渲染线程自己记的场世代不一致 ⇒ 先重建/清零场。 */
        var srcGen = 0L

        /** C3: 本帧待累加的补点数 (0 = 无; 由 [takeDabs] 填)。 */
        var dabCount = 0

        /**
         * C3: 补点参数缓冲 (复用 ⇒ 零分配), 有效数据是前 `dabCount * DAB_STRIDE` 个 float。
         * 每个补点 7 个: `px, py, nx, ny, mode, gain, radius` —— gain 已含模式系数与幅度曲线
         * (见 [LiquifyPath.fieldDabGain]), 渲染线程只负责几何与光栅化。
         *
         * 注意: 补点**不在 [awaitFrame] 里取走**, 而是由渲染线程在真正要累加前调 [takeDabs]
         * —— 无效帧(手势结束瞬间 / 裁剪还没到)不会把补点吞掉。
         */
        val dabs = FloatArray(DAB_CAPACITY * DAB_STRIDE)
    }

    // ---------------- 手势生命周期 (由 LiquifyGpuPreview 转发, 见其 decideForGesture/clear) ----------------

    /** 手势开始: 丢掉上一段手势的暂存, 计数归零, 并声明"本次手势由 GLES 画"。 */
    fun beginGesture() {
        previewUpdates = 0L
        gridUploads = 0L
        sourceUploadCount = 0L
        renderedFrames = 0L
        textureWidth = 0
        textureHeight = 0
        dabsDropped = 0L
        active = false
        requested = true
        // C3: 场判定与"预览由谁画"同口径 —— 手势开始时冻结, 之后改开关只影响下一段手势
        fieldArmed = fieldEnabled
        synchronized(lock) {
            cropW = 0
            cropH = 0
            gridCols = 0
            gridRows = 0
            drawW = 0f
            drawH = 0f
            pendingSrc = null
            pendingGrid = null
            pendingDabCount = 0
            commitReq = null
            commitDone = true
            bumpLocked()
        }
    }

    /** 手势结束 / 取消 / 失败: 清空暂存, 让覆盖层下一帧清成全透明。 */
    fun clear() {
        requested = false
        active = false
        fieldArmed = false
        synchronized(lock) {
            cropW = 0
            cropH = 0
            gridCols = 0
            gridRows = 0
            drawW = 0f
            drawH = 0f
            pendingSrc = null
            pendingGrid = null
            pendingDabCount = 0
            commitReq = null
            commitDone = true
            bumpLocked()
        }
    }

    /** GLES 侧自己躲掉: 只置标志 + 清暂存, **不碰 JNI** (恢复动作留给引擎线程)。 */
    fun markFailed() {
        failed = true
        alive = false
        clear()
    }

    // ---------------- 引擎线程 ----------------

    /**
     * 暂存最新一帧的源裁剪与位移网格 (**不碰 GL**, 不复制像素)。
     *
     * @param crop `[cropW, cropH, docX, docY, docW, docH, seq]` (见 `liquifyPreviewSourceMeta`)
     * @param src  仅当裁剪指纹变化时非空 (RGBA8888, 整段手势只上传一次纹理)
     * @param grid `liquifyGrid()` 的原始数组 (每个 dab 都变; JNI 每次返回新数组, 直接持有即可)
     */
    fun update(crop: IntArray, src: ByteArray?, grid: FloatArray?) {
        if (crop.size < 7 || crop[0] <= 0 || crop[1] <= 0) return
        val need = crop[0] * crop[1] * 4
        synchronized(lock) {
            cropW = crop[0]
            cropH = crop[1]
            cropOriginX = crop[2].toFloat()
            cropOriginY = crop[3].toFloat()
            // 长度不符的源像素一律丢弃: 宁可这一帧不画, 也不能把错的长度当纹理传上去
            if (src != null && src.size >= need) {
                pendingSrc = src
                // C3: 源像素一变 ⇒ rebase 已经把这些补点的形变物化进像素, 引擎网格也重置了。
                // 场必须同期归零并从新世代重新累加(渲染线程据 srcGen 丢弃跨世代的补点)。
                srcGen++
            }
            if (grid != null && LiquifyGridMeta.of(grid, gridMeta)) {
                gridCols = gridMeta[0].toInt()
                gridRows = gridMeta[1].toInt()
                gridOriginX = gridMeta[2]
                gridOriginY = gridMeta[3]
                gridStepX = gridMeta[4]
                gridStepY = gridMeta[5]
                pendingGrid = grid
            }
            previewUpdates++
            bumpLocked()
        }
        active = true
    }

    // ---------------- UI 线程 ----------------

    /**
     * Phase 5 · C3: 推一个补点给 GLES 常驻位移场 (调用点: `CanvasTouchView.liquifyFlushNow`)。
     *
     * 与 `PaintViewModel.liquify()` 是**同一次循环里的同一份参数** ⇒ 场与引擎网格逐 dab 同源;
     * 场没有 armed 时这里只有一次 volatile 读, 不产生任何工作(热路径零分配: 全部写进复用缓冲)。
     *
     * 增益在这里算完(模式系数 × 幅度曲线), 渲染线程只做几何; 影响半径同理由
     * [LiquifyPath.fieldDabRadius] 给出。缓冲溢出(渲染线程落后 256 个补点以上)只丢**新**补点
     * 并在标尺上计数 —— 宁可少画一点, 也不能在热路径上阻塞。
     *
     * @param px,py      dab 起笔点(文档坐标, 引擎侧 `translatePoints` 的 base)
     * @param nx,ny      dab 终点(文档坐标; 推拉模式的方向与幅度都由它决定)
     * @param strength   本补点的强度(已含分段折算, 见 `LiquifyInteractionSession.planStrengthScale`)
     */
    fun pushDab(
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        mode: Int,
        strength: Float,
        brushSize: Float,
    ) {
        if (!fieldArmed) return
        if (!px.isFinite() || !py.isFinite() || !nx.isFinite() || !ny.isFinite()) return
        val distance = hypot(nx - px, ny - py)
        val gain = strength * LiquifyPath.fieldDabGain(mode, distance, brushSize)
        val radius = LiquifyPath.fieldDabRadius(brushSize)
        synchronized(lock) {
            if (pendingDabCount >= DAB_CAPACITY) {
                dabsDropped++
                return
            }
            val base = pendingDabCount * DAB_STRIDE
            pendingDabs[base] = px
            pendingDabs[base + 1] = py
            pendingDabs[base + 2] = nx
            pendingDabs[base + 3] = ny
            pendingDabs[base + 4] = mode.toFloat()
            pendingDabs[base + 5] = gain
            pendingDabs[base + 6] = radius
            pendingDabCount++
            bumpLocked()
        }
    }

    /**
     * C3-2: 喂一次"这一帧要绘制的文档矩形"(受影响区并集; 调用点: `CanvasTouchView` 的补点循环)。
     *
     * 不喂(0 宽高)时覆盖层退回"整块裁剪"的经典语义(网格路径就是这样: 裁剪本身就是影响范围)。
     * 只有场通路的裁剪 = 整篇文档, 才必须靠这个矩形把绘制范围收窄。
     */
    fun pushDrawRect(x: Float, y: Float, w: Float, h: Float) {
        synchronized(lock) {
            if (x == drawX && y == drawY && w == drawW && h == drawH) return
            drawX = x
            drawY = y
            drawW = w
            drawH = h
            bumpLocked()
        }
    }

    /**
     * C3-2: 抬笔回读 —— 让渲染线程把"已经算好的形变结果"渲染到离屏 FBO 并读成 RGBA8888。
     *
     * **只在抬笔时调用**(不在拖动热路径上): 阻塞等到渲染线程做完(最长 [timeoutMs] 毫秒),
     * 超时/覆盖层不在 ⇒ 返回 null, 调用方改走经典路径重放补点(绝不丢形变)。
     */
    fun readbackCommit(x: Int, y: Int, w: Int, h: Int, timeoutMs: Long): ByteArray? {
        if (x < 0 || y < 0 || w <= 0 || h <= 0) return null
        synchronized(lock) {
            if (!alive || failed) return null
            commitReq = intArrayOf(x, y, w, h)
            commitPixels = null
            commitDone = false
            requested = true
            bumpLocked()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (!commitDone) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0L) break
                try {
                    (lock as Object).wait(left)
                } catch (_: InterruptedException) {
                    break
                }
            }
            commitReq = null
            val out = if (commitDone) commitPixels else null
            commitPixels = null
            commitDone = true
            return out
        }
    }

    /** 渲染线程: 取走待处理的回读请求(没有则返回 null)。 */
    fun takeCommitRequest(): IntArray? {
        synchronized(lock) {
            val r = commitReq ?: return null
            commitReq = null
            return r
        }
    }

    /** 渲染线程: 回读完成(或失败)后交回结果并唤醒等待的 UI 线程。 */
    fun completeCommit(pixels: ByteArray?) {
        synchronized(lock) {
            commitPixels = pixels
            commitDone = true
            (lock as Object).notifyAll()
        }
    }

    /**
     * 每帧喂一次"文档 → 视图像素"的仿射 (调用点: `CanvasTouchView.drawCanvas`)。
     *
     * 用"原点 + 两个基向量"表达: `screen = O + docX * Ex + docY * Ey`, 于是缩放/旋转/平移
     * 全部自动跟随; 与 AGSL 版 (`LiquifyGpuPreview.SHADER_SRC` 求逆那两行) 是同一套公式。
     * 只有真的变了才 +revision, 免得拖着手不放时平白让渲染线程出帧。
     */
    fun pushAffine(vt: CanvasViewTransform) {
        vt.docToScreen(0f, 0f, affineScratch)
        val ox = affineScratch[0]
        val oy = affineScratch[1]
        vt.docToScreen(1f, 0f, affineScratch)
        val ex0 = affineScratch[0] - ox
        val ex1 = affineScratch[1] - oy
        vt.docToScreen(0f, 1f, affineScratch)
        val ey0 = affineScratch[0] - ox
        val ey1 = affineScratch[1] - oy
        synchronized(lock) {
            if (ox == affineOx && oy == affineOy &&
                ex0 == exX && ex1 == exY &&
                ey0 == eyX && ey1 == eyY
            ) {
                return
            }
            affineOx = ox
            affineOy = oy
            exX = ex0
            exY = ex1
            eyX = ey0
            eyY = ey1
            bumpLocked()
        }
    }

    // ---------------- 渲染线程 ----------------

    /**
     * 视口尺寸变化 / 线程刚起来 / 即将退出: 让渲染线程重画一帧。
     * (内容没变也必须重画 —— 否则新尺寸下会留着旧画面或退出前不落一帧透明。)
     */
    fun requestRender() {
        synchronized(lock) { bumpLocked() }
    }

    /**
     * C3: 取走当前待累加的补点(渲染线程, **只在确实要累加时调用**)。
     *
     * 为什么不在 [awaitFrame] 里一起取: 渲染线程可能先于"裁剪到达"醒过来(手势刚开始那几帧
     * `valid = false`), 那时取走的补点既没地方画、又已经离开缓冲 ⇒ 首笔形变会凭空少一截。
     * 取走即清, 所以调用方拿到之后必须真的把它们画进场里。
     *
     * @return 本次取到的补点数(同时写进 [Frame.dabCount])
     */
    fun takeDabs(out: Frame): Int {
        synchronized(lock) {
            val n = pendingDabCount
            if (n <= 0) {
                out.dabCount = 0
                return 0
            }
            System.arraycopy(pendingDabs, 0, out.dabs, 0, n * DAB_STRIDE)
            pendingDabCount = 0
            out.dabCount = n
            return n
        }
    }

    /**
     * 阻塞直到有新状态 (最长 [timeoutMs] 毫秒), 把快照拷进 [out] (零分配)。
     *
     * @return true = 取到新帧 (即使 [Frame.valid] 为 false, 也必须清一帧); false = 超时无变化
     */
    fun awaitFrame(out: Frame, timeoutMs: Long): Boolean {
        synchronized(lock) {
            if (out.taken == revision) {
                try {
                    // Kotlin 的 `Any` 没有 wait/notify, 但运行期就是 java.lang.Object
                    (lock as Object).wait(timeoutMs)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            if (out.taken == revision) return false
            out.taken = revision
            // C3-2: 场通路的裁剪是整篇文档、且**没有网格** —— 有效性判定不能再看网格; 同时还要求
            // "绘制矩形"已经就位: 否则第一帧会按整篇文档铺满屏幕(盖住其它图层的合成)。
            out.valid = cropW > 0 && cropH > 0 &&
                if (fieldArmed) (drawW > 0f && drawH > 0f) else (gridCols >= 2 && gridRows >= 2)
            out.cropW = cropW
            out.cropH = cropH
            out.cropOriginX = cropOriginX
            out.cropOriginY = cropOriginY
            // 增量语义: 取走即清, 下一次只有真的变了才会再带数据
            out.src = pendingSrc
            out.grid = pendingGrid
            pendingSrc = null
            pendingGrid = null
            // C3: 补点**不在这里取** —— 渲染线程真正要累加时再调 [takeDabs](无效帧不会吞掉它们)
            out.fieldArmed = fieldArmed
            out.srcGen = srcGen
            out.drawX = drawX
            out.drawY = drawY
            out.drawW = drawW
            out.drawH = drawH
            out.dabCount = 0
            out.gridCols = gridCols
            out.gridRows = gridRows
            out.gridOriginX = gridOriginX
            out.gridOriginY = gridOriginY
            out.gridStepX = gridStepX
            out.gridStepY = gridStepY
            out.affineOx = affineOx
            out.affineOy = affineOy
            out.exX = exX
            out.exY = exY
            out.eyX = eyX
            out.eyY = eyY
            return true
        }
    }

    /** 渲染线程: 源纹理已上传 (尺寸一并上报给 HUD)。 */
    fun noteSourceUpload(w: Int, h: Int) {
        textureWidth = w
        textureHeight = h
        sourceUploadCount++
    }

    /** 渲染线程: 位移网格纹理已上传。 */
    fun noteGridUpload() {
        gridUploads++
    }

    /** 渲染线程: 已提交一帧。 */
    fun noteFrameRendered() {
        renderedFrames++
    }

    private fun bumpLocked() {
        revision++
        (lock as Object).notifyAll()
    }

    /** 读一个诊断 property 的整数 (未设/不可读/非 root 时返回 [def])。 */
    private fun propInt(key: String, def: Int): Int = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        ((get.invoke(null, key, "") as? String)?.trim()?.toIntOrNull() ?: def)
    } catch (_: Throwable) {
        def
    }
}
