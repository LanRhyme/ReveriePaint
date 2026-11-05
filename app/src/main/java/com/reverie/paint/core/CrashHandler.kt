/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.reverie.paint.BuildConfig
import com.reverie.paint.MainActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常捕获与诊断日志记录器。
 * 结合 Native C++ 信号拦截与 Java 未捕获异常双重捕获，集成 Breadcrumbs 行为追踪。
 * 支持崩溃日志一键复制、导出至公共下载目录 (Downloads) 与系统分享。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "ReverieCrashHandler"
    private const val MAX_LOG_FILES = 15
    private const val PREF_NAME = "reverie_crash_handler"
    private const val KEY_LAST_SEEN_CRASH_TIME = "last_seen_crash_time"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    data class CrashLogInfo(
        val file: File,
        val content: String,
        val timestamp: Long,
        val isNative: Boolean
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler != this) {
            defaultHandler = currentHandler
            Thread.setDefaultUncaughtExceptionHandler(this)
            Log.i(TAG, "Global Java CrashHandler registered")
        }

        // 初始化 Native 信号处理器 (SIGSEGV / SIGABRT / SIGBUS 等)
        val logDir = getCrashLogDirectory(context)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        try {
            ReverieCoreBridge.initNativeCrashHandler(logDir.absolutePath, BuildConfig.VERSION_NAME)
            Log.i(TAG, "Native crash handler initialized (path=${logDir.absolutePath})")
        } catch (t: Throwable) {
            Log.w(TAG, "Native crash handler init deferred or failed: ${t.message}")
        }

        // 前缓冲原生路径隔离判定 (必须在任何画布挂载之前完成)
        evaluateFrontBufferQuarantine(context, logDir)
        // 原生崩溃报告本身没有设备信息, 这里把环境写进它的状态快照
        NativeCrashContext.attach(context)

        cleanupStaleSwapFiles(context)
    }

    /**
     * 前缓冲 (androidx.graphics / 系统 libgui) 崩过一次就按"本次安装"隔离。
     *
     * 该崩溃发生在系统图形栈里 (fault addr 0x0/0x8), Kotlin 侧无法捕获, 只能在下次启动时
     * 把前缓冲入口关掉, 让笔画退回主画布软件预览。判据见 [FrontBufferCrashSignature]:
     * 除了栈里点名 `libgraphics-core.so`, 也认"前缓冲开着 + libgui/libandroid_runtime
     * + 页面上 Surface 拆解"这一种 (线上占比最大的一簇)。
     *
     * 隔离与 `PackageManager.lastUpdateTime` 绑定: 发新版本 (或升级 graphics-core) 后
     * 旧日志失效, 自动重试一次。
     */
    private fun evaluateFrontBufferQuarantine(context: Context, logDir: File) {
        val hit = hasFrontBufferCrashSinceInstall(context, logDir)
        com.reverie.paint.core.stylus.FrontBufferProbe.setCrashQuarantined(hit)
        if (hit) {
            Log.w(TAG, "Front buffer quarantined: previous native crash matched the front-buffer signature")
            Breadcrumbs.record("FrontBuffer", "quarantined after front-buffer native crash")
        }
    }

    /** 最近的原生崩溃日志里是否有前缓冲签名 (只读少量小文件, 启动期一次性开销)。 */
    private fun hasFrontBufferCrashSinceInstall(context: Context, logDir: File): Boolean {
        val installedAt = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val logs = logDir.listFiles { f ->
            f.isFile && f.name.startsWith("crash_native_") && f.name.endsWith(".log")
        } ?: return false
        return logs.asSequence()
            .filter { it.lastModified() >= installedAt }
            .sortedByDescending { it.lastModified() }
            .take(3)
            .any { file ->
                runCatching {
                    com.reverie.paint.core.stylus.FrontBufferCrashSignature.matches(file.readText())
                }.getOrDefault(false)
            }
    }

    /**
     * 把设备/系统/前缓冲开关状态写进 Native 崩溃报告的状态快照。
     * 原生信号处理器拿不到 `android.os.Build`, 只能由 Kotlin 侧提前灌进去。
     */
    /**
     * 拼原生崩溃处理器用的状态快照。除了设备/系统/前缓冲开关, 还带上"崩的时候在干什么":
     * 页面、工具、画布尺寸、图层数 —— 这些以前是缺的, 导致上报的 doc 字段全 0。
     */
    internal fun buildNativeState(context: Context): String {
        val prefs = context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        val predictionPref = prefs.getBoolean(
            "frontBufferPredictionEnabled",
            prefs.getBoolean("stylusFrontBufferPreviewEnabled", false),
        )
        val sb = StringBuilder()
        sb.append("App: ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append('/').append(Build.BRAND)
            .append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')
        val harmony = detectHarmonyOsVersion()
        if (harmony.isNotEmpty()) sb.append("HarmonyOS/EMUI: ").append(harmony).append('\n')
        sb.append("FrontBuffer: pref=").append(predictionPref)
            .append(" graphicsCore=").append(BuildConfig.GRAPHICS_CORE_VERSION)
            .append(" supported=")
            .append(runCatching { com.reverie.paint.core.stylus.FrontBufferProbe.isSupported() }.getOrDefault(false))
            .append(" quarantined=")
            .append(com.reverie.paint.core.stylus.FrontBufferProbe.isCrashQuarantined())
            .append('\n')

        // 崩溃时的应用上下文(ViewModel 还没创建时只有设备信息)
        val vm = MainActivity.currentViewModel
        if (vm != null) {
            runCatching {
                sb.append("Current Page: ").append(vm.currentPage.name).append('\n')
                vm.currentToolId.takeIf { it.isNotBlank() }
                    ?.let { sb.append("Active Tool: ").append(it).append('\n') }
                // 没有打开文档时 docWidth/docHeight 是默认值(1080x1920), 别当成真实画布
                if (vm.layers.isNotEmpty() && vm.docWidth > 0 && vm.docHeight > 0) {
                    sb.append("Document: ").append(vm.docWidth).append(" x ").append(vm.docHeight).append('\n')
                }
                sb.append("Layers Count: ").append(vm.layers.size).append('\n')
            }
        }
        return sb.toString()
    }

    private fun cleanupStaleSwapFiles(context: Context) {
        Thread {
            try {
                val cacheDir = context.cacheDir ?: return@Thread
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("KRITA_SWAP_FILE_")
                }?.forEach { it.delete() }

                val swapDir = File(cacheDir, "swap")
                if (swapDir.exists()) {
                    swapDir.listFiles { file ->
                        file.isFile && file.name.startsWith("KRITA_SWAP_FILE_")
                    }?.forEach { it.delete() }
                }
            } catch (_: Throwable) {}
        }.start()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 崩溃抢救：在进程死亡前同步保存当前作画草稿
            PaintViewModel.currentInstance?.emergencySaveOnCrash()

            appContext?.let { ctx ->
                try {
                    val marker = File(getCrashLogDirectory(ctx), "CRASH_MARKER")
                    marker.writeText("JAVA_CRASH: ${throwable.javaClass.simpleName} - ${throwable.message}\nTime: ${System.currentTimeMillis()}\n")
                } catch (_: Throwable) {}
            }

            val report = buildCrashReport(thread, throwable)
            Log.e(TAG, "FATAL JAVA CRASH DETECTED:\n$report")
            saveCrashReport(report)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to build or save crash report", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        sb.append("===================================================\n")
        sb.append("         ReveriePaint Java Crash Report            \n")
        sb.append("===================================================\n")
        sb.append("Time: $timestamp\n")
        sb.append("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n")

        // 1. 设备与操作系统信息
        sb.append("--- [Device & OS Information] ---\n")
        sb.append("Brand: ${Build.BRAND}\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Device: ${Build.DEVICE}\n")
        sb.append("Hardware: ${Build.HARDWARE}\n")
        sb.append("Android Release: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        // 进程位数决定加载的是哪一套 .so: 排查 UnsatisfiedLinkError / ABI 装错包的第一判据
        sb.append(
            "ABI: ${Build.SUPPORTED_ABIS.joinToString(",")} " +
                "(process ${if (Process.is64Bit()) "64-bit" else "32-bit"})\n",
        )
        sb.append("Fingerprint: ${Build.FINGERPRINT}\n")

        val harmonyVersion = detectHarmonyOsVersion()
        if (harmonyVersion.isNotEmpty()) {
            sb.append("HarmonyOS / EMUI: $harmonyVersion\n")
        }

        // 2. 内存状态
        sb.append("\n--- [Memory State] ---\n")
        val ctx = appContext
        if (ctx != null) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    sb.append("Total RAM: ${memInfo.totalMem / (1024 * 1024)} MB\n")
                    sb.append("Available RAM: ${memInfo.availMem / (1024 * 1024)} MB\n")
                    sb.append("Low Memory Warning: ${memInfo.lowMemory}\n")
                    sb.append("Threshold: ${memInfo.threshold / (1024 * 1024)} MB\n")
                    sb.append("App Memory Class: ${am.memoryClass} MB (Large: ${am.largeMemoryClass} MB)\n")
                }
            } catch (_: Throwable) {}
        }
        val runtime = Runtime.getRuntime()
        sb.append("JVM Heap: Allocated=${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)} MB, ")
        sb.append("Total=${runtime.totalMemory() / (1024 * 1024)} MB, Max=${runtime.maxMemory() / (1024 * 1024)} MB\n")
        sb.append("Native Heap: Allocated=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB, ")
        sb.append("Free=${Debug.getNativeHeapFreeSize() / (1024 * 1024)} MB\n")

        // 3. 应用上下文与画布状态
        sb.append("\n--- [App Context & Canvas State] ---\n")
        val vm = MainActivity.currentViewModel
        if (vm != null) {
            sb.append("Current Page: ${vm.currentPage}\n")
            sb.append("Active Tool: ${vm.currentToolId}\n")
            sb.append("Document: ${vm.docWidth} x ${vm.docHeight}\n")
            sb.append("Layers Count: ${vm.layers.size}, Active Layer: ${vm.currentLayerIndex}\n")
            sb.append("Stylus Mode: ${vm.huaweiPencilModel}\n")
        } else {
            sb.append("ViewModel: null (App initializing or backgrounded)\n")
        }

        // 4. 最近操作轨迹 (Breadcrumbs)
        sb.append("\n--- [User Action Breadcrumbs (Last 100 Events)] ---\n")
        val crumbs = Breadcrumbs.dump()
        if (crumbs.isEmpty()) {
            sb.append("(No breadcrumb records available)\n")
        } else {
            crumbs.forEach { crumb ->
                sb.append(crumb).append("\n")
            }
        }

        // 5. 崩溃线程与调用栈
        sb.append("\n--- [Thread & Stack Trace] ---\n")
        @Suppress("DEPRECATION")
        val threadId = thread.id
        sb.append("Crashed Thread: ${thread.name} (id=$threadId, priority=${thread.priority})\n")
        sb.append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n\n")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        sb.append(sw.toString())

        // 6. 最近进程 Logcat (脱敏输出，捕获崩溃前 C++ 与系统日志现场)
        sb.append("\n\n--- [Recent Process Logcat (Last 200 Lines, Sanitized)] ---\n")
        try {
            val logs = DiagnosticsManager.captureProcessLogcat(200)
            if (logs.isEmpty()) {
                sb.append("(No process logcat entries captured)\n")
            } else {
                logs.forEach { line ->
                    sb.append(DiagnosticsManager.sanitize(line)).append("\n")
                }
            }
        } catch (_: Throwable) {
            sb.append("(Failed to capture logcat on crash)\n")
        }

        sb.append("\n===================================================\n")
        return sb.toString()
    }

    private fun detectHarmonyOsVersion(): String {
        val details = mutableListOf<String>()
        val propKeys = listOf(
            "hw_sc.build.platform.version",
            "ro.build.version.emui",
            "ro.build.hw_emui_api_level",
            "ro.huawei.build.version.security",
            "ro.build.version.magic",
        )
        for (key in propKeys) {
            val v = getSystemProperty(key)
            if (v.isNotBlank()) {
                details.add("$key=$v")
            }
        }
        try {
            val buildExClass = Class.forName("com.huawei.system.BuildEx")
            val osBrandMethod = buildExClass.getMethod("getOsBrand")
            val osBrand = osBrandMethod.invoke(null)?.toString()
            if (!osBrand.isNullOrBlank()) {
                details.add("osBrand=$osBrand")
            }
        } catch (_: Throwable) {}

        return details.joinToString("; ")
    }

    private fun getSystemProperty(key: String): String {
        return DeviceInfo.getSystemProperty(key)
    }

    private fun saveCrashReport(report: String, prefix: String = "crash_java_") {
        val ctx = appContext ?: return
        val fileName = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".log"

        val dirs = mutableListOf<File>()
        ctx.getExternalFilesDir("crash_logs")?.let { dirs.add(it) }
        dirs.add(File(ctx.filesDir, "crash_logs"))

        for (dir in dirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, fileName)
                targetFile.writeText(report)
                rotateLogs(dir)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing to ${dir.absolutePath}: ${t.message}")
            }
        }
    }

    private fun rotateLogs(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?: return
        if (files.size > MAX_LOG_FILES) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_LOG_FILES)
                .forEach { it.delete() }
        }
    }

    /**
     * 记录一个**被兜住**的非致命异常 (例如触摸派发里抛出、但已 catch 住不让进程 abort 的那种)。
     *
     * 写进 crash_logs 让遥测下次一并带出 (文件名带 handled, 不会弹"上次崩溃"提示), 因为这类
     * 异常否则完全不可见: 它发生在 Android 输入管线里, 既不进 UncaughtExceptionHandler,
     * 也没有 Java 崩溃报告 —— 1.6.5 遥测的 SIGABRT (art::Thread::AssertNoPendingException)
     * 就是这种形态, 只能靠这里把异常类型捞出来。
     *
     * 限流 60s 一条, 避免一次手势连抛把日志刷满。
     */
    @Volatile
    private var lastHandledReportAt = 0L

    fun recordHandledException(context: Context, throwable: Throwable, where: String) {
        val now = System.currentTimeMillis()
        if (now - lastHandledReportAt < 60_000L) return
        lastHandledReportAt = now
        // 调用点常常在输入/UI 线程: 报告组装会执行 logcat 与设备信息采集, 绝不能就地做,
        // 否则为了兜一个异常反而制造一次 ANR。这里只在当前线程取栈 (便宜), 其余丢后台。
        val stack = Log.getStackTraceString(throwable)
        val threadName = Thread.currentThread().name
        Thread({
            runCatching {
                val header = "[handled] $where (进程未退出, 线程 $threadName)\n$stack\n"
                val report = header + buildCrashReport(Thread.currentThread(), throwable)
                saveCrashReport(report, "crash_java_handled_")
            }.onFailure { Log.e(TAG, "recordHandledException failed: ${it.message}") }
        }, "revp-handled-report").start()
        Log.e(TAG, "[handled] $where: ${throwable.javaClass.name}: ${throwable.message}")
    }

    fun getCrashLogDirectory(context: Context): File {
        return context.getExternalFilesDir("crash_logs") ?: File(context.filesDir, "crash_logs")
    }

    /**
     * 获取最新的一条崩溃日志内容 (无论是否已查看过)
     */
    fun getLatestCrashLog(context: Context): String? {
        val dir = getCrashLogDirectory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?: return null
        val latest = files.maxByOrNull { it.lastModified() } ?: return null
        return try {
            latest.readText()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取未提示过的最新崩溃日志
     */
    fun getUnseenCrashLog(context: Context): CrashLogInfo? {
        val dir = getCrashLogDirectory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?: return null
        // handled 日志是被兜住的异常, 只给诊断/遥测用, 不该弹"上次崩溃"
        val latest = files.filter { !it.name.contains("handled") }.maxByOrNull { it.lastModified() } ?: return null
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN_CRASH_TIME, 0L)
        if (latest.lastModified() <= lastSeen) {
            return null
        }
        val content = try { latest.readText() } catch (_: Throwable) { return null }
        val isNative = latest.name.contains("native") || content.contains("Native Signal")
        return CrashLogInfo(latest, content, latest.lastModified(), isNative)
    }

    /**
     * 标记最新日志为已查看
     */
    fun markLatestLogAsSeen(context: Context, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SEEN_CRASH_TIME, timestamp).apply()
    }

    const val MAX_COPYABLE_CRASH_CHARS = 3000

    /**
     * 判断崩溃日志是否允许复制 (仅当长度不长时允许复制)
     */
    fun canCopy(content: String): Boolean {
        return content.length <= MAX_COPYABLE_CRASH_CHARS
    }

    /**
     * 复制崩溃日志到剪贴板 (仅当长度不超过阈值时允许复制)
     */
    fun copyToClipboard(context: Context, text: String): Boolean {
        if (!canCopy(text)) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("ReveriePaint Crash Log", text)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to copy crash log to clipboard", e)
            false
        }
    }

    /**
     * 导出崩溃日志到公共下载目录 (Downloads/ReveriePaint_CrashReports/)
     */
    fun exportCrashLogToDownloads(context: Context, fileName: String, content: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReveriePaint_CrashReports")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                "Download/ReveriePaint_CrashReports/$fileName"
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "ReveriePaint_CrashReports")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                targetFile.writeText(content)
                targetFile.absolutePath
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to export crash log to Downloads", e)
            null
        }
    }

    /**
     * 调用系统分享面板分享崩溃日志文件 (通过 FileProvider 进行真实文件分享)
     */
    fun shareCrashLog(context: Context, logFile: File, title: String = "分享崩溃诊断日志") {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, logFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ReveriePaint 崩溃诊断报告")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to share crash log file", e)
        }
    }

    /**
     * 兼容接口：通过内容分享，优先写入临时日志文件并以文件形式调起分享
     */
    fun shareCrashLog(context: Context, logContent: String, title: String = "分享崩溃诊断日志") {
        try {
            val cacheDir = File(context.cacheDir, "crash_reports").apply { if (!exists()) mkdirs() }
            val fileName = "crash_share_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.log"
            val file = File(cacheDir, fileName)
            file.writeText(logContent)
            shareCrashLog(context, file, title)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cache and share crash log", e)
        }
    }

    fun clearCrashLogs(context: Context) {
        val dirs = listOfNotNull(
            context.getExternalFilesDir("crash_logs"),
            File(context.filesDir, "crash_logs"),
        )
        for (dir in dirs) {
            dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
                ?.forEach { it.delete() }
        }
    }

    /**
     * 检查是否存在待恢复的崩溃草稿或异常退出标记
     */
    fun checkCrashRecovery(context: Context): File? {
        val marker = File(getCrashLogDirectory(context), "CRASH_MARKER")
        if (!marker.exists()) return null

        val autoSaveDir = File(context.filesDir, "autosave")
        if (autoSaveDir.exists()) {
            val emergencyFiles = autoSaveDir.listFiles { f -> f.isFile && f.name.endsWith(".emergency.revp") }
            val newestEmergency = emergencyFiles?.maxByOrNull { it.lastModified() }
            if (newestEmergency != null && newestEmergency.length() > 0) {
                return newestEmergency
            }

            val autoSaveFiles = autoSaveDir.listFiles { f -> f.isFile && f.name.endsWith(".autosave.revp") }
            val newestAutoSave = autoSaveFiles?.maxByOrNull { it.lastModified() }
            if (newestAutoSave != null && newestAutoSave.length() > 0) {
                return newestAutoSave
            }
        }

        val snapshots = AutoSaveHistoryManager.getSnapshots(context)
        if (snapshots.isNotEmpty()) {
            val snapFile = File(AutoSaveHistoryManager.getHistoryDir(context), snapshots.first().fileName)
            if (snapFile.exists() && snapFile.length() > 0) {
                return snapFile
            }
        }
        return null
    }

    /**
     * 清理崩溃标记文件
     */
    fun clearCrashMarker(context: Context) {
        try {
            val marker = File(getCrashLogDirectory(context), "CRASH_MARKER")
            if (marker.exists()) {
                marker.delete()
            }
        } catch (_: Throwable) {}
    }
}
