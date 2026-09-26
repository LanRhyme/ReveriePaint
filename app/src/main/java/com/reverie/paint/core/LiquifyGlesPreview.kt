/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.Build
import com.reverie.paint.BuildConfig
import com.reverie.paint.model.CanvasViewTransform
import com.reverie.paint.model.LiquifyGridMeta

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
        try {
            if (!platformSupported) return@lazy false
            if (BuildConfig.LQ_TEST_PROFILE == 4) return@lazy true
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val raw = get.invoke(null, PROP_GLES, "") as? String
            (raw?.trim()?.toIntOrNull() ?: 0) != 0
        } catch (_: Throwable) {
            false
        }
    }

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
        enabled || (platformSupported && hostOverride == LiquifyGpuPreview.HOST_OVERRIDE_GLES)

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
        active = false
        requested = true
        synchronized(lock) {
            cropW = 0
            cropH = 0
            gridCols = 0
            gridRows = 0
            pendingSrc = null
            pendingGrid = null
            bumpLocked()
        }
    }

    /** 手势结束 / 取消 / 失败: 清空暂存, 让覆盖层下一帧清成全透明。 */
    fun clear() {
        requested = false
        active = false
        synchronized(lock) {
            cropW = 0
            cropH = 0
            gridCols = 0
            gridRows = 0
            pendingSrc = null
            pendingGrid = null
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
            if (src != null && src.size >= need) pendingSrc = src
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
            out.valid = cropW > 0 && cropH > 0 && gridCols >= 2 && gridRows >= 2
            out.cropW = cropW
            out.cropH = cropH
            out.cropOriginX = cropOriginX
            out.cropOriginY = cropOriginY
            // 增量语义: 取走即清, 下一次只有真的变了才会再带数据
            out.src = pendingSrc
            out.grid = pendingGrid
            pendingSrc = null
            pendingGrid = null
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
}
