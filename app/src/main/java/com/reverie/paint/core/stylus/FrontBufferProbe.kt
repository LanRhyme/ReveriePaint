/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.os.Build
import android.util.Log
import com.reverie.paint.core.PerfTrace

/**
 * 运行时前缓冲探针与兼容性诊断工具。
 * 验证当前系统及硬件环境是否满足 `androidx.graphics.lowlatency.CanvasFrontBufferedRenderer` 运行要求 (API 29+)。
 */
object FrontBufferProbe {
    private const val TAG = "FrontBufferProbe"

    /** 最小支持 API 版本: Android 10 (Q / API 29) */
    const val MIN_SUPPORTED_API = Build.VERSION_CODES.Q

    /**
     * 本机是否因"上一次原生崩溃命中前缓冲签名"而被隔离 (见 [setCrashQuarantined] /
     * [FrontBufferCrashSignature])。由 [com.reverie.paint.core.CrashHandler] 在进程启动时
     * 判定; 未判定前按不隔离处理。
     */
    @Volatile
    private var crashQuarantined = false

    /** 隔离期间仍想在前缓冲上排查问题的逃生门: `adb shell setprop debug.reverie.fb_force 1`。 */
    private val forceEnabledByProp: Boolean by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_force", 0) != 0
    }

    /**
     * 记录启用状态变更原因 (崩溃隔离 / 强制打开), 便于诊断时确认"为什么这台机器没走前缓冲"。
     * 只在启动时调用一次, 不参与任何热路径。
     */
    fun setCrashQuarantined(quarantined: Boolean) {
        crashQuarantined = quarantined
        if (quarantined) {
            Log.w(
                TAG,
                "front buffer disabled for this app version: previous native crash matched the " +
                    "front-buffer signature (libgraphics-core.so, or libgui + libandroid_runtime " +
                    "with FrontBuffer: pref=true; override with debug.reverie.fb_force=1)",
            )
        }
    }

    /** 崩溃隔离是否生效 (受 [forceEnabledByProp] 覆盖, 用于诊断输出)。 */
    fun isCrashQuarantined(): Boolean = crashQuarantined && !forceEnabledByProp

    /**
     * 已知存在系统级 libgui registerSurfaceControlForCallback 空指针缺陷的 ROM 组合:
     * Android 13 (SDK 33) 的小米/红米与 vivo/iQOO 机型 (线上 d8f74afe88 / 7f68bfcd44 / f047d7d9a2 / 8d5df3d6e2)。
     * 该系统缺陷在 SurfaceControl 拆解事务中解引用空指针导致 SIGSEGV (0x0/0x8), 需前置规避。
     */
    internal fun isKnownDefectiveVendorRom(
        sdk: Int = Build.VERSION.SDK_INT,
        brand: String = Build.BRAND ?: "",
        mfg: String = Build.MANUFACTURER ?: "",
    ): Boolean {
        if (sdk == 33) {
            val b = brand.lowercase()
            val m = mfg.lowercase()
            if (b.contains("xiaomi") || b.contains("redmi") || m.contains("xiaomi") ||
                b.contains("vivo") || b.contains("iqoo") || m.contains("vivo")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 判断当前设备是否具备运行 CanvasFrontBufferedRenderer 的基础软硬件条件。
     */
    fun isSupported(): Boolean {
        // 1. Android 版本要求 API 29+ (具备 SurfaceControl 与单缓冲支持)
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_API) {
            return false
        }

        // 2. 本机曾在前缓冲原生路径崩过:
        //    - androidx.graphics 的 libgraphics-core.so SurfaceControl 拆解竞态 (fault 0x0);
        //    - 以及"页面切换时 Surface 已销毁、事务仍打到 libgui"这一种 (栈里只有
        //      libgui + libandroid_runtime, 线上占比最大的一簇, 见 FrontBufferCrashSignature)。
        //    两者都无法在 Kotlin 侧捕获, 只能隔离。隔离按版本号生效, 升级版本后自动重试
        //    一次 (CrashHandler 负责读写)。
        if (isCrashQuarantined()) {
            return false
        }

        // 3. 针对已知存在系统级 libgui 空指针崩溃的厂商 ROM (Android 13 小米/vivo) 前置拦截
        if (isKnownDefectiveVendorRom() && !forceEnabledByProp) {
            return false
        }

        // 4. 探活 androidx.graphics 类库是否可正确加载
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
    private const val SOURCE_CACHE = "cache"
    private const val SOURCE_PROBE_FAILED = "probe_failed"
    private const val SOURCE_NOT_PROBED = "not_probed"

    /**
     * 纯函数缓存决策:
     * - 若探针已跑完, 以探针实际结果为准;
     * - 若探针未跑完但有缓存, 立即返回缓存值;
     * - 既无缓存又未探完, 返回 false。
     */
    fun resolveHoldAllowed(cached: Boolean?, probeDone: Boolean, probeResult: Boolean): Boolean = when {
        probeDone -> probeResult
        cached != null -> cached
        else -> false
    }

    @Volatile
    private var cachedResult: Boolean? = null

    @Volatile
    private var probeStarted = false

    @Volatile
    private var probeFinished = false

    private fun getCacheKey(): String {
        val fp = try { Build.FINGERPRINT ?: "unknown" } catch (_: Throwable) { "unknown" }
        return "fb_probe_v2_${fp.hashCode().toString(16)}"
    }

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
     * 先同步读取基于设备指纹的持久化缓存, 后台探针跑完后若不同则覆写更新。
     */
    fun warmUpPreservedContentCheck(context: android.content.Context? = null) {
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_API) return
        synchronized(this) {
            if (probeStarted) return
            probeStarted = true
        }

        val ctx = context ?: com.reverie.paint.core.FontManager.appContext
        val prefs = try {
            ctx?.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
        } catch (_: Throwable) { null }

        if (prefs != null) {
            val key = getCacheKey()
            if (prefs.contains(key)) {
                val cached = prefs.getBoolean(key, false)
                cachedResult = cached
                preservedContent = cached
                probeSource = SOURCE_CACHE
            }
        }

        Thread({ probePreservedContent(prefs) }, "fb-preserve-probe").apply { isDaemon = true }.start()
    }

    /**
     * 运笔落笔时读: 这台设备能不能把真墨留在前缓冲上。不阻塞。
     * 结合缓存与探针完成状态进行决策。
     */
    fun realInkHoldAllowed(): Boolean {
        return resolveHoldAllowed(
            cached = cachedResult,
            probeDone = probeFinished,
            probeResult = (preservedContent == true),
        )
    }

    /** 诊断用: `<结论>/<来源>`。 */
    fun preservedContentState(): String = "${preservedContent}/$probeSource"

    private fun probePreservedContent(prefs: android.content.SharedPreferences? = null) {
        val result =
            runCatching { reflectPreservedContentSupport() }
                .getOrElse { e ->
                    Log.w(TAG, "preserved-content probe failed, fallback to allowed: ${e.message}")
                    probeSource = SOURCE_PROBE_FAILED
                    true
                }
        if (probeSource != SOURCE_PROBE_FAILED) probeSource = SOURCE_PROBE
        preservedContent = result
        probeFinished = true
        Log.i(TAG, "preserve_probe supported=$result source=$probeSource sdk=${Build.VERSION.SDK_INT}")

        if (prefs != null && probeSource == SOURCE_PROBE) {
            val key = getCacheKey()
            if (!prefs.contains(key) || prefs.getBoolean(key, !result) != result) {
                prefs.edit().putBoolean(key, result).apply()
            }
        }
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
        val defective = isKnownDefectiveVendorRom()
        return "FrontBuffer[supported=$supported, sdk=$sdk, device=$manufacturer $model, " +
            "crashQuarantined=$crashQuarantined, defectiveRom=$defective, force=$forceEnabledByProp]"
    }
}
