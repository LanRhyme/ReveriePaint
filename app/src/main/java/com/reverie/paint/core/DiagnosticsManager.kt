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
import android.widget.Toast
import com.reverie.paint.BuildConfig
import com.reverie.paint.MainActivity
import com.reverie.paint.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 全景系统运行诊断与混合日志提取器。
 * 动态抓取当前进程 Logcat（含 C++ 引擎 qWarning/qDebug 及 Kotlin 日志）、用户行为轨迹、
 * 设备与运行期画布状态快照，并自动进行敏感密码/凭据脱敏，确保用户分享日志时安全且完整。
 */
object DiagnosticsManager {
    private const val TAG = "DiagnosticsManager"
    private const val DEFAULT_LOGCAT_LINES = 500

    /**
     * 对文本中的密码、Token 和私密凭据进行正则脱敏
     */
    fun sanitize(raw: String): String {
        var result = raw
        // 1. URL 嵌入认证密码: http://user:password@host
        result = Regex("""(?i)(https?://[^:/@\s]+:)([^@/\s]+)(@)""").replace(result) {
            "${it.groupValues[1]}[REDACTED]@"
        }
        // 2. Authorization 标头: Authorization: Bearer xxx / Basic xxx
        result = Regex("""(?i)(Authorization\s*[:=]\s*(?:Bearer|Basic)\s+)([^\s"';,]+)""").replace(result) {
            "${it.groupValues[1]}[REDACTED]"
        }
        // 3. 独立 Bearer / Basic 凭据
        result = Regex("""(?i)\b(Bearer|Basic)\s+([A-Za-z0-9+/=._-]{8,})""").replace(result) {
            "${it.groupValues[1]} [REDACTED]"
        }
        // 4. key=value 或 key: "value" 键值对中的敏感数据
        result = Regex("""(?i)("?(?:password|passwd|pwd|token|access_token|secret|api_key|app_secret)"?\s*[:=]\s*["']?)([^"'\s,;]{3,})(["']?)""").replace(result) {
            "${it.groupValues[1]}[REDACTED]${it.groupValues[3]}"
        }
        return result
    }

    /**
     * 动态抓取当前进程最近的 Logcat 输出
     */
    fun captureProcessLogcat(maxLines: Int = DEFAULT_LOGCAT_LINES): List<String> {
        val pid = Process.myPid()
        val deque = ArrayDeque<String>(maxLines)
        var process: java.lang.Process? = null
        try {
            // Android 4.1+ 支持 --pid 参数限定抓取本进程日志
            val cmd = arrayOf("logcat", "-d", "-v", "time", "--pid", pid.toString())
            process = Runtime.getRuntime().exec(cmd)
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (deque.size >= maxLines) {
                        deque.pollFirst()
                    }
                    deque.addLast(line)
                    line = reader.readLine()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to capture logcat with --pid, falling back", t)
            try {
                // 兼容兜底: 无 --pid 时读取全部并按当前 pid 过滤
                val fallbackCmd = arrayOf("logcat", "-d", "-v", "time")
                process = Runtime.getRuntime().exec(fallbackCmd)
                val pidStr = pid.toString()
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.contains(pidStr)) {
                            if (deque.size >= maxLines) {
                                deque.pollFirst()
                            }
                            deque.addLast(line)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (fallbackEx: Throwable) {
                Log.e(TAG, "Logcat capture failed completely", fallbackEx)
                deque.addLast("Logcat capture failed: ${fallbackEx.message}")
            }
        } finally {
            process?.destroy()
        }
        return deque.toList()
    }

    /**
     * 生成全景诊断报告文本
     */
    fun generateDiagnosticsReport(context: Context): String {
        val sb = StringBuilder()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = timeFormat.format(Date())

        sb.append("===================================================\n")
        sb.append("         ReveriePaint Full Diagnostics Report      \n")
        sb.append("===================================================\n")
        sb.append("Time: $timestamp\n")
        sb.append("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("Build Type: ${if (BuildConfig.DEBUG) "Debug" else "Release"}\n")

        // 1. 设备与系统
        sb.append("\n--- [Device & OS Info] ---\n")
        sb.append("Brand: ${Build.BRAND}\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Device: ${Build.DEVICE}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Fingerprint: ${Build.FINGERPRINT}\n")

        // 2. 内存状态
        sb.append("\n--- [Memory State] ---\n")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                sb.append("Total RAM: ${memInfo.totalMem / (1024 * 1024)} MB\n")
                sb.append("Available RAM: ${memInfo.availMem / (1024 * 1024)} MB\n")
                sb.append("Low Memory Warning: ${memInfo.lowMemory}\n")
                sb.append("Threshold: ${memInfo.threshold / (1024 * 1024)} MB\n")
            }
        } catch (_: Throwable) {}
        val runtime = Runtime.getRuntime()
        sb.append("JVM Heap: Allocated=${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)} MB, ")
        sb.append("Total=${runtime.totalMemory() / (1024 * 1024)} MB, Max=${runtime.maxMemory() / (1024 * 1024)} MB\n")
        sb.append("Native Heap: Allocated=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB, ")
        sb.append("Free=${Debug.getNativeHeapFreeSize() / (1024 * 1024)} MB\n")

        // 3. 画布与运行期状态
        sb.append("\n--- [Active Canvas & Context] ---\n")
        val vm = MainActivity.currentViewModel
        if (vm != null) {
            sb.append("Current Page: ${vm.currentPage}\n")
            sb.append("Document: ${vm.docName} (${vm.docWidth} x ${vm.docHeight}, DPI: ${vm.docDpi})\n")
            sb.append("Layers Count: ${vm.layers.size}, Active Layer: ${vm.currentLayerIndex}\n")
            sb.append("Active Tool: ${vm.currentToolId}\n")
            sb.append("Color Mode: ${vm.colorMode}\n")
            sb.append("Total Strokes: ${vm.totalStrokes}, Elapsed: ${vm.elapsedSeconds}s\n")
            sb.append("Stylus Model: ${vm.huaweiPencilModel}\n")
        } else {
            sb.append("ViewModel: Not attached or in background\n")
        }

        // 4. 用户行为轨迹 (Breadcrumbs)
        sb.append("\n--- [User Action Breadcrumbs (Last 100)] ---\n")
        val crumbs = Breadcrumbs.dump()
        if (crumbs.isEmpty()) {
            sb.append("(No breadcrumb records available)\n")
        } else {
            crumbs.forEach { crumb ->
                sb.append(crumb).append("\n")
            }
        }

        // 5. 最近进程 Logcat
        sb.append("\n--- [Recent Process Logcat (Last 500 Lines, Sanitized)] ---\n")
        val logs = captureProcessLogcat(DEFAULT_LOGCAT_LINES)
        if (logs.isEmpty()) {
            sb.append("(No process logcat entries captured)\n")
        } else {
            logs.forEach { rawLine ->
                sb.append(sanitize(rawLine)).append("\n")
            }
        }

        // 6. 最近一次崩溃日志 (如有)
        val latestCrash = CrashHandler.getLatestCrashLog(context)
        if (!latestCrash.isNullOrBlank()) {
            sb.append("\n--- [Recent Crash Record (Sanitized)] ---\n")
            sb.append(sanitize(latestCrash)).append("\n")
        }

        sb.append("\n===================================================\n")
        return sb.toString()
    }

    /**
     * 将诊断日志保存到系统的 Downloads/ReveriePaint_Diagnostics 目录
     */
    fun exportReportToDownloads(context: Context, content: String): String? {
        val fileName = "reverie_diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReveriePaint_Diagnostics")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                "Download/ReveriePaint_Diagnostics/$fileName"
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, "ReveriePaint_Diagnostics")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                targetFile.writeText(content)
                targetFile.absolutePath
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to export diagnostics to Downloads", e)
            null
        }
    }

    /**
     * 复制诊断报告到剪贴板
     */
    fun copyReportToClipboard(context: Context, content: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("ReveriePaint Diagnostics", content))
        Toast.makeText(context, R.string.settings_export_log_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * 调用系统分享面板分享诊断报告
     */
    fun shareReport(context: Context, content: String, title: String = "分享诊断日志") {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ReveriePaint 诊断与运行日志")
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to share diagnostics report", e)
        }
    }
}
