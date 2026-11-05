/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.reverie.paint.BuildConfig
import com.reverie.paint.core.Breadcrumbs
import com.reverie.paint.core.CrashHandler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * 崩溃现场的上报通路。
 *
 * 设计原则(见 docs/TELEMETRY.md):
 *  - 只在后台线程跑, 一律 daemon; 启动后延迟 30s 触发, 不参与首帧
 *  - 端点为空(未注入 BuildConfig)时整条链路编译期短路成 no-op
 *  - 任何异常都不允许冒泡到调用方; 上报失败永不删除用户的本地日志
 *  - 服务端 401/403/410 视为全局停用, 本机永久关闭直到用户重新打开开关
 */
object TelemetryReporter {

    private const val TAG = "ReverieTelemetry"
    // 30s 太长: 真机上切后台后进程会被 cgroup freezer 冻住, 定时器根本不跑(实测 WCHAN=do_freezer_trap)
    private const val STARTUP_DELAY_SEC = 8L
    private const val MAX_UPLOADS_PER_RUN = 3
    private const val MAX_REPORT_BYTES = 2L * 1024 * 1024
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val DAILY_LIMIT = 5
    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 15L

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scanRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "reverie-telemetry").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.apply { removeOnCancelPolicy = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 构建时没注入端点 = 这个渠道不发遥测(纯净构建/第三方再分发) */
    val isConfigured: Boolean
        get() = BuildConfig.TELEMETRY_ENDPOINT.isNotBlank()

    /** Application.onCreate 里调用: 延迟触发一次扫描, 不阻塞启动 */
    fun scheduleStartupScan(context: Context) {
        Log.d(TAG, "scheduleStartupScan configured=$isConfigured")
        if (!isConfigured) return
        val app = context.applicationContext
        runCatching {
            executor.schedule(
                {
                    scanRequested.set(true)
                    safely("startup-scan") { performScan(app) }
                },
                STARTUP_DELAY_SEC,
                TimeUnit.SECONDS,
            )
        }.onFailure { Log.w(TAG, "schedule failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 立刻触发一次扫描(不延迟)。给 Activity.onStop 用: 用户离开应用时进程还没被冻结,
     * 这是"延迟定时器可能永远不触发"的兜底。同一进程内只会真正跑一次。
     */
    fun requestScanNow(context: Context) {
        if (!isConfigured) return
        if (!scanRequested.compareAndSet(false, true)) return
        val app = context.applicationContext
        runCatching { executor.execute { safely("onstop-scan") { performScan(app) } } }
    }

    /**
     * 手动上报(崩溃弹窗里的"上报这条")。用户显式点击视为当场同意, 因此不受开关限制,
     * 但仍尊重服务端停用。结果回调在主线程。
     */
    fun uploadNow(context: Context, file: File, onResult: (Boolean) -> Unit = {}) {
        if (!isConfigured) {
            postResult(onResult, false)
            return
        }
        val app = context.applicationContext
        runCatching {
            executor.execute {
                val ok = safely("manual-upload") {
                    val prefs = TelemetryPrefs(app)
                    if (prefs.serverDisabled) return@safely false
                    val text = readReport(file) ?: return@safely false
                    val envelope = buildEnvelope(app, prefs, file, text) ?: return@safely false
                    val code = post(envelope.toJson())
                    if (code != null && code in 200..299) {
                        prefs.markUploaded(file.name)
                        prefs.bumpToday()
                        true
                    } else {
                        if (code != null && isFatal(code)) prefs.serverDisabled = true
                        false
                    }
                } ?: false
                postResult(onResult, ok)
            }
        }.onFailure { postResult(onResult, false) }
    }

    // ------------------------------------------------------------ 主流程

    private fun performScan(context: Context) {
        val prefs = TelemetryPrefs(context)
        Log.d(TAG, "performScan enabled=${prefs.enabled} serverDisabled=${prefs.serverDisabled} today=${prefs.todayCount()}")
        if (!prefs.enabled || prefs.serverDisabled) return
        if (System.currentTimeMillis() < prefs.nextAttemptAtMs) return

        uploadPendingReports(context, prefs)
        uploadAnrs(context, prefs)
    }

    private fun uploadPendingReports(context: Context, prefs: TelemetryPrefs) {
        val dir = runCatching { CrashHandler.getCrashLogDirectory(context) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val all = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
            ?: return
        // 超过保留期的日志直接销号为"不再尝试", 否则每次启动都要重新列一遍、判一遍, 永远发不出去
        all.filter { now - it.lastModified() > MAX_AGE_MS && !prefs.isUploaded(it.name) }
            .forEach { prefs.markUploaded(it.name) }
        val pending = all
            .filter { !prefs.isUploaded(it.name) }
            .sortedByDescending { it.lastModified() }
            .take(MAX_UPLOADS_PER_RUN)
        Log.d(TAG, "scan all=${all.size} pending=${pending.size}")

        for (file in pending) {
            if (prefs.todayCount() >= DAILY_LIMIT) {
                Log.i(TAG, "daily limit reached, stop")
                return
            }
            val text = readReport(file) ?: continue
            val envelope = buildEnvelope(context, prefs, file, text) ?: continue
            val code = post(envelope.toJson())
            when {
                code != null && code in 200..299 -> {
                    prefs.markUploaded(file.name)
                    prefs.bumpToday()
                    prefs.failCount = 0
                    prefs.nextAttemptAtMs = 0L
                    Log.i(TAG, "uploaded ${file.name} (${code})")
                }

                code != null && isFatal(code) -> {
                    prefs.serverDisabled = true
                    Log.w(TAG, "server disabled telemetry ($code)")
                    return
                }

                else -> {
                    onFailure(prefs, file, code)
                    return
                }
            }
        }
    }

    private fun onFailure(prefs: TelemetryPrefs, file: File, code: Int?) {
        val fails = prefs.failCount + 1
        prefs.failCount = fails
        // 不再"失败 N 次就永久放弃": 国内网络抖动/污染很常见, 丢一条崩溃现场比多试几次贵得多。
        // 退避封顶 24h, 真正的丢弃只由 30 天保留期决定。
        val delayMs = when (fails) {
            1 -> TimeUnit.HOURS.toMillis(1)
            2 -> TimeUnit.HOURS.toMillis(6)
            else -> TimeUnit.HOURS.toMillis(24)
        }
        prefs.nextAttemptAtMs = System.currentTimeMillis() + delayMs
        Log.w(TAG, "upload failed (code=$code, fails=$fails), retry in ${delayMs / 1000}s")
    }

    private fun uploadAnrs(context: Context, prefs: TelemetryPrefs) {
        if (!AnrCollector.isSupported()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val entries = runCatching { AnrCollector.collect(context, prefs.lastAnrTs) }.getOrNull() ?: return
        if (entries.isEmpty()) return

        val liveCrumbs = runCatching { Breadcrumbs.dump() }.getOrDefault(emptyList())
        for (entry in entries) {
            if (prefs.todayCount() >= DAILY_LIMIT) return
            // ANR 是系统 kill 整个进程: 本进程的面包屑随进程一起没了, 现在能读到的都是
            // 重启后新会话的记录。看门狗在卡死时会把"主线程卡在哪"落盘一份, 这里对着 ANR
            // 时间窗取出来, 让报告带上真正的卡点。
            val stallCrumb = MainThreadWatchdog.lastStallLineFor(context, entry.timestampMs)
            val crumbsForReport =
                if (stallCrumb.isNullOrBlank()) liveCrumbs else liveCrumbs + stallCrumb
            val envelope = TelemetryPayload.fromAnr(
                entry.timestampMs,
                entry.trace,
                payloadContext(context, prefs),
                crumbsForReport,
            )
            val code = post(envelope.toJson())
            if (code != null && code in 200..299) {
                prefs.lastAnrTs = entry.timestampMs
                prefs.bumpToday()
                Log.i(TAG, "uploaded anr @${entry.timestampMs}")
            } else {
                if (code != null && isFatal(code)) prefs.serverDisabled = true
                return
            }
        }
    }

    // ------------------------------------------------------------ 组装与发送

    private fun buildEnvelope(
        context: Context,
        prefs: TelemetryPrefs,
        file: File,
        text: String,
    ): TelemetryPayload.TelemetryEnvelope? {
        val liveCrumbs = runCatching { Breadcrumbs.dump() }.getOrDefault(emptyList())
        return runCatching {
            TelemetryPayload.fromReport(
                fileName = file.name,
                text = text,
                fileModifiedMs = file.lastModified(),
                ctx = payloadContext(context, prefs),
                liveBreadcrumbs = liveCrumbs,
            )
        }.onFailure { Log.w(TAG, "payload build failed (${file.name})", it) }.getOrNull()
    }

    private fun payloadContext(context: Context, prefs: TelemetryPrefs): TelemetryPayload.Context =
        TelemetryPayload.Context(
            installId = prefs.installId,
            appVer = BuildConfig.VERSION_NAME,
            appCode = BuildConfig.VERSION_CODE,
            abi = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
            fallbackDevice = deviceProfile(context),
        )

    private fun deviceProfile(context: Context): TelemetryPayload.TelemetryDevice {
        var ramMb = 0
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            ramMb = (info.totalMem / (1024 * 1024)).toInt()
        }
        return TelemetryPayload.TelemetryDevice(
            mfr = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            dev = Build.DEVICE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            rel = Build.VERSION.RELEASE.orEmpty(),
            fp = Build.FINGERPRINT.orEmpty(),
            ramMb = ramMb,
            harmony = "",
        )
    }

    private fun readReport(file: File): String? = runCatching {
        if (!file.isFile || file.length() > MAX_REPORT_BYTES) return null
        file.readText()
    }.getOrNull()

    /** @return HTTP 状态码; 网络异常返回 null */
    private fun post(json: String): Int? = runCatching {
        val endpoint = BuildConfig.TELEMETRY_ENDPOINT.trim().trimEnd('/')
        if (endpoint.isEmpty()) return null

        val compressed = java.io.ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { gz ->
            gz.write(json.toByteArray(Charsets.UTF_8))
            gz.finish()
        }
        val gzipped = compressed.toByteArray()

        val builder = Request.Builder()
            .url("$endpoint/t/v1/crash")
            .header("Content-Encoding", "gzip")
            .header("User-Agent", "ReveriePaint/${BuildConfig.VERSION_NAME} (Android; telemetry/1)")
            .post(gzipped.toRequestBody(JSON))
        val key = BuildConfig.TELEMETRY_KEY
        if (key.isNotBlank()) builder.header("X-RP-Key", key)

        client.newCall(builder.build()).execute().use { it.code }
    }.onFailure {
        // 打全栈: 真机上光看类名分不清是 DNS 污染、证书、还是超时
        Log.w(TAG, "post failed", it)
    }.getOrNull()

    private fun isFatal(code: Int): Boolean = code == 401 || code == 403 || code == 410

    private fun postResult(callback: (Boolean) -> Unit, ok: Boolean) {
        mainHandler.post { runCatching { callback(ok) } }
    }

    private inline fun <T> safely(name: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        // 打全栈而不是只打类名: 真机上的 ExceptionInInitializerError 必须能看到 Caused by
        Log.w(TAG, "$name failed", t)
        null
    }
}
