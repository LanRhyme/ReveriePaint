package com.reverie.paint.core.brushstore

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔刷状态结构化日志（W-01）。
 *
 * 每条事件一行：`<时间> <event> k=v k=v ...`，同时发往 logcat（tag [TAG]）和
 * `filesDir/brush_diag/brush.log`（超过 [MAX_BYTES] 滚动为 `brush.log.1`）。
 * 事件名约定见执行文档 W-01：select / build / reload / readbackMismatch / import / migrate / upgrade。
 *
 * 未调用 [init] 时只发 logcat；logcat 调用包在 try/catch 里，JVM 单测环境下也不会抛。
 */
object BrushDiagnostics {
    const val TAG = "ReverieBrush"
    const val MAX_BYTES = 512 * 1024L
    const val DIR = "brush_diag"
    const val LOG_NAME = "brush.log"

    @Volatile
    private var logDir: File? = null
    private val lock = Any()

    /** 测试或自检时可以注入时钟 */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    fun init(filesDir: File) {
        logDir = File(filesDir, DIR).also { it.mkdirs() }
    }

    /** 仅测试用：解除文件落盘 */
    fun reset() {
        logDir = null
        clock = { System.currentTimeMillis() }
    }

    fun logFile(): File? = logDir?.let { File(it, LOG_NAME) }

    fun format(event: String, kv: List<Pair<String, Any?>>): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(clock()))
        val sb = StringBuilder().append(ts).append(' ').append(event)
        for ((k, v) in kv) {
            sb.append(' ').append(k).append('=').append(formatValue(v))
        }
        return sb.toString()
    }

    private fun formatValue(v: Any?): String {
        val s = v?.toString() ?: "null"
        // 值里有空格或换行时加引号，保持一行一事件、可 grep
        val oneLine = s.replace("\r", "\\r").replace("\n", "\\n")
        return if (oneLine.isEmpty() || oneLine.any { it == ' ' || it == '"' || it == '=' }) {
            "\"" + oneLine.replace("\"", "\\\"") + "\""
        } else {
            oneLine
        }
    }

    fun log(event: String, vararg kv: Pair<String, Any?>) {
        val line = format(event, kv.toList())
        try {
            android.util.Log.i(TAG, line)
        } catch (_: Throwable) {
            // JVM 单测里 android.util.Log 是桩实现
        }
        val dir = logDir ?: return
        synchronized(lock) {
            try {
                dir.mkdirs()
                val f = File(dir, LOG_NAME)
                if (f.exists() && f.length() + line.length + 1 > MAX_BYTES) {
                    val rolled = File(dir, "$LOG_NAME.1")
                    rolled.delete()
                    f.renameTo(rolled)
                }
                f.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    /** 报告用：取 brush.log（不足时连带 brush.log.1）末尾 [maxBytes] 字节，经调用方脱敏后附进诊断报告 */
    fun tail(maxBytes: Int = 64 * 1024): String {
        val dir = logDir ?: return ""
        synchronized(lock) {
            val cur = File(dir, LOG_NAME).takeIf { it.exists() }?.readText().orEmpty()
            val prev = File(dir, "$LOG_NAME.1").takeIf { it.exists() }?.readText().orEmpty()
            val all = prev + cur
            if (all.length <= maxBytes) return all
            val cut = all.substring(all.length - maxBytes)
            // 从下一行开头截，避免半行
            val nl = cut.indexOf('\n')
            return if (nl >= 0 && nl + 1 < cut.length) cut.substring(nl + 1) else cut
        }
    }

    fun md5(file: File): String = file.inputStream().buffered().use { md5(it) }

    fun md5(stream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(64 * 1024)
        var n: Int
        while (stream.read(buf).also { n = it } > 0) {
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 把某把笔刷的快照 md5、有效 md5、覆盖 JSON、有效 XML 写到 `brush_diag/<id>/`，供真机取证。
     * 返回写入目录；未 init 时返回 null。
     */
    fun dumpPreset(
        id: String,
        snapshotBytes: ByteArray?,
        effectiveBytes: ByteArray?,
        overridesJson: String,
        effectiveXml: String?,
    ): File? {
        val dir = logDir ?: return null
        val out = File(dir, id.replace(':', '_').replace('/', '_'))
        return try {
            out.mkdirs()
            File(out, "snapshot.md5").writeText(snapshotBytes?.let { md5(it) } ?: "none")
            File(out, "effective.md5").writeText(effectiveBytes?.let { md5(it) } ?: "none")
            File(out, "overrides.json").writeText(overridesJson)
            File(out, "effective.xml").writeText(effectiveXml.orEmpty())
            log("dump", "id" to id, "dir" to out.name)
            out
        } catch (_: Throwable) {
            null
        }
    }
}
