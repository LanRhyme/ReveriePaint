/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.os.Build
import android.util.Log

/**
 * 运行时前缓冲探针与兼容性诊断工具。
 * 验证当前系统及硬件环境是否满足 `androidx.graphics.lowlatency.CanvasFrontBufferedRenderer` 运行要求 (API 29+)。
 */
object FrontBufferProbe {
    private const val TAG = "FrontBufferProbe"

    /** 最小支持 API 版本: Android 10 (Q / API 29) */
    const val MIN_SUPPORTED_API = Build.VERSION_CODES.Q

    /**
     * 判断当前设备是否具备运行 CanvasFrontBufferedRenderer 的基础软硬件条件。
     */
    fun isSupported(): Boolean {
        // 1. Android 版本要求 API 29+ (具备 SurfaceControl 与单缓冲支持)
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_API) {
            return false
        }

        // 2. 探活 androidx.graphics 类库是否可正确加载
        return runCatching {
            Class.forName("androidx.graphics.lowlatency.CanvasFrontBufferedRenderer")
            true
        }.getOrElse { e ->
            Log.w(TAG, "CanvasFrontBufferedRenderer class not found: ${e.message}")
            false
        }
    }

    // ---- 前缓冲内容保留能力 (真墨按住的前提) ----

    private const val SOURCE_PROBE = "probe"
    private const val SOURCE_PROBE_FAILED = "probe_failed"
    private const val SOURCE_NOT_PROBED = "not_probed"

    /**
     * 设备是否保留前缓冲上已绘制的内容。`CanvasFrontBufferedRenderer` 按设备能力二选一:
     * 支持保留时走单缓冲增量 (旧墨留在缓冲里, 每帧只追加); 不支持时每帧按参数重建缓冲。
     *
     * 真墨呈现每帧只贴本帧的脏区, 且包里的槽位图会被复用 —— 在不保留内容的设备上先贴的墨会丢,
     * 重建时还可能读到已换成别的内容的槽位图。所以只有探明"支持"时才按住前缓冲, 否则这一笔
     * 走主画布正常刷新 (前缓冲仍可画 #81 的假线)。
     *
     * 库内部用 `PreservedBufferContentsVerifier` 做同样的运行期验证但不外露结论; 这里反射调用
     * 同一个验证器, 判据与库的实际选择一致。反射失败 (类被裁剪/改名) 时按"未知"处理并放行,
     * 与引入这项检查之前的行为一致。
     */
    @Volatile
    private var preservedContent: Boolean? = null

    @Volatile
    private var probeSource: String = SOURCE_NOT_PROBED

    /**
     * 后台预热能力探测 (验证器要建 HardwareRenderer 并回读像素, 十毫秒级, 不能放主线程)。
     * 应用启动时调一次; API < 29 直接跳过 (前缓冲本来就不可用)。
     */
    fun warmUpPreservedContentCheck() {
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_API) return
        if (preservedContent != null) return
        Thread({ probePreservedContent() }, "fb-preserve-probe").apply { isDaemon = true }.start()
    }

    /**
     * 运笔落笔时读: 这台设备能不能把真墨留在前缓冲上。不阻塞。
     * 尚未探完 (启动后极短的窗口) 时返回 false, 这一笔先走主画布。
     */
    fun realInkHoldAllowed(): Boolean {
        val v = preservedContent ?: return false
        return v || probeSource == SOURCE_PROBE_FAILED
    }

    /** 诊断用: `<结论>/<来源>`。 */
    fun preservedContentState(): String = "${preservedContent}/$probeSource"

    private fun probePreservedContent() {
        if (preservedContent != null) return
        val result =
            runCatching { reflectPreservedContentSupport() }
                .getOrElse { e ->
                    Log.w(TAG, "preserved-content probe failed: ${e.message}")
                    probeSource = SOURCE_PROBE_FAILED
                    true
                }
        if (probeSource != SOURCE_PROBE_FAILED) probeSource = SOURCE_PROBE
        preservedContent = result
        Log.i(TAG, "preserve_probe supported=$result source=$probeSource sdk=${Build.VERSION.SDK_INT}")
    }

    private fun reflectPreservedContentSupport(): Boolean {
        val cls = Class.forName("androidx.graphics.lowlatency.PreservedBufferContentsVerifier")
        val verifier = cls.getDeclaredConstructor().newInstance()
        return try {
            val m = cls.getDeclaredMethod("supportsPreservedRenderedContent")
            (m.invoke(verifier) as? Boolean) ?: true
        } finally {
            runCatching { cls.getDeclaredMethod("release").invoke(verifier) }
        }
    }

    /**
     * 判断笔尖预览是否必须走软件回退 (in-window Canvas 绘制)。
     *
     * 回退空洞修复: [CanvasTouchView] 中"是否绘制软件回退预览"与"是否走前缓冲直出"
     * 两个门控必须严格互补 —— 只判 overlay 对象是否为空是不够的, 因为 overlay 存在
     * 但渲染器初始化失败 (`canRenderPreview == false`) 时, 直出调用是静默 no-op,
     * 若此时不再回退软件绘制, 开启开关后会"完全没有预览"。
     *
     * @param overlayPresent 前缓冲覆盖层是否已挂载
     * @param canRenderPreview 覆盖层渲染器是否真正可用 ([FrontBufferPreviewOverlay.canRenderPreview])
     * @return true 表示必须在 onDraw 中用软件路径绘制预览
     */
    fun softwareFallbackRequired(overlayPresent: Boolean, canRenderPreview: Boolean): Boolean {
        return !overlayPresent || !canRenderPreview
    }

    /**
     * 获取详细诊断信息字符串 (供 Debug / 性能日志使用)。
     */
    fun getDiagnosticSummary(): String {
        val sdk = Build.VERSION.SDK_INT
        val supported = isSupported()
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return "FrontBuffer[supported=$supported, sdk=$sdk, device=$manufacturer $model]"
    }
}
