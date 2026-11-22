/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import com.reverie.paint.BuildConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存失败的可行动原因。
 *
 * 只用于给用户选一条最可能有效的提示文案 + 给诊断报告分类，不参与保存逻辑本身；
 * 判据都来自保存失败那一刻的快照（errno / 剩余空间 / native 堆），因此对本进程内
 * 一次保存失败是稳定的，也不会把上一次失败的原因串到这一次（旧实现从磁盘读最近记录，
 * 会出现"保存失败但提示磁盘满"的误报）。
 */
enum class SaveFailureKind {
    DISK_FULL,
    OUT_OF_MEMORY,
    IO,
    UNKNOWN,
}

data class SaveErrorInfo(
    val ok: Boolean,
    val stage: String = "",
    val errno: Int = 0,
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val targetPath: String = "",
    val isAutoSave: Boolean = false,
    val docWidth: Int = 0,
    val docHeight: Int = 0,
    val layerCount: Int = 0,
    val availableDiskBytes: Long = 0L,
    val totalDiskBytes: Long = 0L,
    val availableRamBytes: Long = 0L,
    val totalRamBytes: Long = 0L,
    val nativeHeapAllocatedBytes: Long = 0L,
    val jvmHeapAllocatedBytes: Long = 0L,
    val undoCount: Int = -1,
    val fdCount: Int = -1,
    val appVersion: String = BuildConfig.VERSION_NAME,
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("ok", ok)
        obj.put("stage", stage)
        obj.put("errno", errno)
        obj.put("detail", detail)
        obj.put("timestamp", timestamp)
        obj.put("targetPath", targetPath)
        obj.put("isAutoSave", isAutoSave)
        obj.put("docWidth", docWidth)
        obj.put("docHeight", docHeight)
        obj.put("layerCount", layerCount)
        obj.put("availableDiskBytes", availableDiskBytes)
        obj.put("totalDiskBytes", totalDiskBytes)
        obj.put("availableRamBytes", availableRamBytes)
        obj.put("totalRamBytes", totalRamBytes)
        obj.put("nativeHeapAllocatedBytes", nativeHeapAllocatedBytes)
        obj.put("jvmHeapAllocatedBytes", jvmHeapAllocatedBytes)
        obj.put("undoCount", undoCount)
        obj.put("fdCount", fdCount)
        obj.put("appVersion", appVersion)
        return obj.toString()
    }

    /**
     * 归类失败原因。优先级：磁盘满 -> 内存不足 -> IO -> 未知。
     *
     * - `availableDiskBytes == 0` 表示取不到 StatFs（未知），此时不能当成磁盘满；
     * - 内存判据要求 native 堆绝对值够大、占总内存比例够高、availRam 过低或 errno=ENOMEM；
     * - [failureKind] 只依赖本对象字段，便于单测覆盖，不在 UI 线程做任何 IO/系统查询。
     */
    fun failureKind(): SaveFailureKind {
        if (errno == ERRNO_ENOSPC) return SaveFailureKind.DISK_FULL
        if (availableDiskBytes in 1L until LOW_DISK_BYTES) return SaveFailureKind.DISK_FULL
        if (stage == STAGE_DISK_PRECHECK) return SaveFailureKind.DISK_FULL
        if (errno == ERRNO_ENOMEM) return SaveFailureKind.OUT_OF_MEMORY
        val lowAvailRam = availableRamBytes in 1L until LOW_RAM_BYTES
        val heapPressure = nativeHeapAllocatedBytes >= LARGE_NATIVE_HEAP_BYTES ||
            (totalRamBytes > 0 && nativeHeapAllocatedBytes * 100 >= totalRamBytes * NATIVE_HEAP_PRESSURE_PERCENT)
        if (stage == STAGE_OOM || heapPressure || lowAvailRam) return SaveFailureKind.OUT_OF_MEMORY
        if (errno == ERRNO_EMFILE || errno == ERRNO_ENFILE) return SaveFailureKind.IO
        if (stage in IO_STAGES) return SaveFailureKind.IO
        return SaveFailureKind.UNKNOWN
    }

    companion object {
        /** Linux ENOSPC：写盘途中空间耗尽，C++ 侧 write_entry 失败时 errno 即此值 */
        const val ERRNO_ENOSPC = 28
        /** Linux ENOMEM：内存分配失败 */
        const val ERRNO_ENOMEM = 12
        /** Linux ENFILE / EMFILE：系统或进程文件句柄耗尽 */
        const val ERRNO_ENFILE = 23
        const val ERRNO_EMFILE = 24
        /** 低于该剩余空间时，即使 errno 没带出来也按磁盘满引导用户 */
        const val LOW_DISK_BYTES = 50L * 1024 * 1024
        /** 低于该可用 RAM 时视为严重内存压力 */
        const val LOW_RAM_BYTES = 256L * 1024 * 1024
        /** native 堆超过该绝对值视为内存压力（接近 32 位地址空间上限 / 大画布峰值） */
        const val LARGE_NATIVE_HEAP_BYTES = 2L * 1024 * 1024 * 1024
        const val NATIVE_HEAP_PRESSURE_PERCENT = 30
        const val STAGE_DISK_PRECHECK = "disk_precheck"
        const val STAGE_NO_DOCUMENT = "no_document"
        const val STAGE_OOM = "oom"
        const val STAGE_VERIFY_FAILED = "verify_failed"
        const val STAGE_OP_FAILED = "op_failed"
        const val STAGE_AUTOSAVE_TIMEOUT = "autosave_timeout"
        const val STAGE_ASYNC_REJECTED = "async_rejected"

        private val IO_STAGES = setOf(
            "create_store",
            "open_entry",
            "write_entry",
            "close_entry",
            "png_encode",
            "commit_failed",
            "commit_verify",
            "verify_failed",
            "tmp_missing",
            "tmp_truncated",
            "tmp_open_read",
            "seek_failed",
            "eocd_corrupt",
        )

        fun fromJson(jsonStr: String): SaveErrorInfo? = runCatching {
            val obj = JSONObject(jsonStr)
            SaveErrorInfo(
                ok = obj.optBoolean("ok", false),
                stage = obj.optString("stage", ""),
                errno = obj.optInt("errno", 0),
                detail = obj.optString("detail", ""),
                timestamp = obj.optLong("timestamp", 0L),
                targetPath = obj.optString("targetPath", ""),
                isAutoSave = obj.optBoolean("isAutoSave", false),
                docWidth = obj.optInt("docWidth", 0),
                docHeight = obj.optInt("docHeight", 0),
                layerCount = obj.optInt("layerCount", 0),
                availableDiskBytes = obj.optLong("availableDiskBytes", 0L),
                totalDiskBytes = obj.optLong("totalDiskBytes", 0L),
                availableRamBytes = obj.optLong("availableRamBytes", 0L),
                totalRamBytes = obj.optLong("totalRamBytes", 0L),
                nativeHeapAllocatedBytes = obj.optLong("nativeHeapAllocatedBytes", 0L),
                jvmHeapAllocatedBytes = obj.optLong("jvmHeapAllocatedBytes", 0L),
                undoCount = obj.optInt("undoCount", -1),
                fdCount = obj.optInt("fdCount", -1),
                appVersion = obj.optString("appVersion", BuildConfig.VERSION_NAME),
            )
        }.getOrNull()

        fun parseNativeJson(
            nativeJson: String,
            targetPath: String,
            isAutoSave: Boolean,
            docWidth: Int,
            docHeight: Int,
            layerCount: Int,
            context: Context,
            undoCount: Int = -1,
        ): SaveErrorInfo {
            val obj = runCatching { JSONObject(nativeJson) }.getOrNull() ?: JSONObject()
            val ok = obj.optBoolean("ok", false)
            val stage = obj.optString("stage", if (ok) "none" else "unknown")
            val errno = obj.optInt("errno", 0)
            val detail = obj.optString("detail", "")
            val ts = obj.optLong("timestamp", System.currentTimeMillis())

            val targetFile = File(targetPath)
            val dirToCheck = if (targetFile.parentFile?.exists() == true) targetFile.parentFile!! else context.filesDir
            var freeDisk = 0L
            var totalDisk = 0L
            try {
                val stat = StatFs(dirToCheck.absolutePath)
                freeDisk = stat.availableBlocksLong * stat.blockSizeLong
                totalDisk = stat.blockCountLong * stat.blockSizeLong
            } catch (_: Throwable) {}

            var availRam = 0L
            var totalRam = 0L
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    availRam = mi.availMem
                    totalRam = mi.totalMem
                }
            } catch (_: Throwable) {}

            val runtime = Runtime.getRuntime()
            val jvmHeap = runtime.totalMemory() - runtime.freeMemory()
            val nativeHeap = try { Debug.getNativeHeapAllocatedSize() } catch (_: Throwable) { 0L }
            val fdCount = try { File("/proc/self/fd").list()?.size ?: -1 } catch (_: Throwable) { -1 }

            return SaveErrorInfo(
                ok = ok,
                stage = stage,
                errno = errno,
                detail = detail,
                timestamp = ts,
                targetPath = targetPath,
                isAutoSave = isAutoSave,
                docWidth = docWidth,
                docHeight = docHeight,
                layerCount = layerCount,
                availableDiskBytes = freeDisk,
                totalDiskBytes = totalDisk,
                availableRamBytes = availRam,
                totalRamBytes = totalRam,
                nativeHeapAllocatedBytes = nativeHeap,
                jvmHeapAllocatedBytes = jvmHeap,
                undoCount = undoCount,
                fdCount = fdCount,
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }
}

/**
 * 记录与持久化工程保存失败详情。
 * 针对 #211 / #212 场景：保存失败时记录具体 stage、errno、磁盘剩余空间及内存状态，
 * 写入应用私有存储目录 `save_failures/`。即使用户清后台重启应用，诊断提取器仍能读取并注入报告。
 */
object SaveFailureRecorder {
    private const val DIR_NAME = "save_failures"
    private const val MAX_RECORDS = 5
    private val lock = Any()
    internal var dirOverrideForTest: File? = null

    fun getDir(context: Context? = null): File {
        val override = dirOverrideForTest
        if (override != null) {
            if (!override.exists()) override.mkdirs()
            return override
        }
        val base = context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "reverie_save_failures")
        return File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    fun recordFailure(context: Context? = null, info: SaveErrorInfo) {
        if (info.ok) return
        synchronized(lock) {
            try {
                val dir = getDir(context)
                val file = File(dir, "failure_${info.timestamp}.json")
                val json = info.toJson()
                // 先写 .tmp 再 rename：避免进程在写 JSON 途中被杀留下半截文件，
                // 半截文件虽然解析会被跳过，但会占着 MAX_RECORDS 名额挤掉真正的失败记录
                val tmp = File(dir, "${file.name}.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
                val files = dir.listFiles { f -> f.isFile && f.name.startsWith("failure_") && f.name.endsWith(".json") }
                if (files != null && files.size > MAX_RECORDS) {
                    files.sortedBy { it.name }
                        .take(files.size - MAX_RECORDS)
                        .forEach { it.delete() }
                }
            } catch (t: Throwable) {
                android.util.Log.w("RP_IO", "Failed to persist save failure record", t)
            }
        }
    }

    fun getAllFailures(context: Context? = null): List<SaveErrorInfo> = synchronized(lock) {
        val dir = getDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("failure_") && f.name.endsWith(".json") }
            ?: return emptyList()
        files.mapNotNull { f -> runCatching { SaveErrorInfo.fromJson(f.readText()) }.getOrNull() }
            .sortedByDescending { it.timestamp }
    }

    fun getLatestFailure(context: Context? = null): SaveErrorInfo? = getAllFailures(context).firstOrNull()

    fun clear(context: Context? = null) = synchronized(lock) {
        getDir(context).deleteRecursively()
    }

    fun formatForDiagnostics(context: Context? = null): String {
        val failures = getAllFailures(context)
        val sb = StringBuilder()
        sb.append("\n--- [Recent Project Save Failures (Persistent, Survives Restart)] ---\n")
        if (failures.isEmpty()) {
            sb.append("(No save failures recorded)\n")
            return sb.toString()
        }
        sb.append("Total recorded failures: ${failures.size}\n")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        failures.forEachIndexed { idx, f ->
            val dateStr = sdf.format(Date(f.timestamp))
            val modeStr = if (f.isAutoSave) "AutoSave" else "Manual Save"
            sb.append("[$dateStr] #$idx $modeStr: stage=${f.stage}, errno=${f.errno}, reason=${f.failureKind().name}\n")
            if (f.detail.isNotBlank()) {
                sb.append("  Detail: ${f.detail}\n")
            }
            sb.append("  Target: ${f.targetPath}\n")
            sb.append("  Canvas: ${f.docWidth}x${f.docHeight}, ${f.layerCount} layers\n")
            val freeMb = f.availableDiskBytes / (1024 * 1024)
            val totalMb = f.totalDiskBytes / (1024 * 1024)
            sb.append("  Disk: $freeMb MB free / $totalMb MB total\n")
            val ramFreeMb = f.availableRamBytes / (1024 * 1024)
            val ramTotalMb = f.totalRamBytes / (1024 * 1024)
            val nativeMb = f.nativeHeapAllocatedBytes / (1024 * 1024)
            val jvmMb = f.jvmHeapAllocatedBytes / (1024 * 1024)
            sb.append("  RAM: $ramFreeMb MB avail / $ramTotalMb MB total, NativeHeap: $nativeMb MB, JVMHeap: $jvmMb MB")
            if (f.undoCount >= 0 || f.fdCount >= 0) {
                sb.append(", Undo: ${f.undoCount}, FDs: ${f.fdCount}")
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
