/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
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
 * 针对鸿蒙 (HarmonyOS / EMUI) 等深度定制系统提供专属环境指纹采集，
 * 发生崩溃时自动将异常调用栈与系统状态持久化至外部存储，便于用户反馈与排查。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "ReverieCrashHandler"
    private const val MAX_LOG_FILES = 10
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler != this) {
            defaultHandler = currentHandler
            Thread.setDefaultUncaughtExceptionHandler(this)
            Log.i(TAG, "Global CrashHandler registered")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildCrashReport(thread, throwable)
            Log.e(TAG, "FATAL CRASH DETECTED:\n$report")
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
        sb.append("              ReveriePaint Crash Report            \n")
        sb.append("===================================================\n")
        sb.append("Time: $timestamp\n")
        sb.append("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n")

        // 1. 设备与操作系统信息 (含鸿蒙/华为专属指纹)
        sb.append("--- [Device & OS Information] ---\n")
        sb.append("Brand: ${Build.BRAND}\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Device: ${Build.DEVICE}\n")
        sb.append("Hardware: ${Build.HARDWARE}\n")
        sb.append("Android Release: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
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

        // 4. 崩溃线程与调用栈
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

        sb.append("\n===================================================\n")
        return sb.toString()
    }

    /**
     * 采集鸿蒙 / 华为 EMUI 专属系统属性。
     */
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
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun saveCrashReport(report: String) {
        val ctx = appContext ?: return
        val fileName = "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".log"

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

    fun getCrashLogDirectory(context: Context): File {
        return context.getExternalFilesDir("crash_logs") ?: File(context.filesDir, "crash_logs")
    }

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
}
