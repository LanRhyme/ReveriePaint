/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 上报体的组装与解析。纯 Kotlin、不依赖 Android(不用 org.json), 便于 JVM 单测。
 *
 * 输入是 [com.reverie.paint.core.CrashHandler] 与 [com.reverie.paint.cpp.ReverieCrashHandler]
 * 早就写到本地的那些报告, 输出是 docs/TELEMETRY.md §5 的契约 JSON。
 * 这里只做"搬运 + 规约", 不新增任何采集点 —— 采集层已经有 100 条面包屑与完整现场。
 */
object TelemetryPayload {

    const val MAX_STACK_CHARS = 16 * 1024
    const val MAX_LOGCAT_CHARS = 32 * 1024
    const val MAX_CRUMBS = 32
    const val MAX_LOGCAT_LINES = 100
    const val MAX_FRAMES = 24

    private const val ONE_DAY_MS = 86_400_000L

    private val SECTION_HEADER = Regex("""^--- \[(.+?)] ---\s*$""")

    /** 新格式: `#03 pc 0x7f..  +0x1a2b3c  /path/libfoo.so`(可选尾随 ` (symbol+0x14)`) */
    private val NATIVE_FRAME_NEW = Regex(
        """^#\d+\s+pc\s+0x[0-9a-fA-F]+\s+(\+0x[0-9a-fA-F]+|-)\s+(\S+)(?:\s+\(([^)]*)\))?\s*$""",
    )

    /** 旧格式(1.6.x 及以前写下的日志): `#03 pc 0x7f..  /path/libfoo.so (+0x1a2b3c)` */
    private val NATIVE_FRAME_OLD = Regex("""^#\d+\s+pc\s+0x[0-9a-fA-F]+\s+(\S+)(?:\s+\(([^)]*)\))?\s*$""")
    /** 真实格式: `Signal: SIGSEGV (Segmentation violation) (code=1)`, 名字与 code 分开抓 */
    private val NATIVE_SIGNAL_NAME = Regex("""(?m)^Signal:\s*(\S+)""")
    private val NATIVE_SIGNAL_CODE = Regex("""\(code=(-?\d+)\)""")
    private val JAVA_TIME = Regex("""(?m)^Time:\s*(.+)$""")
    private val VERSION_LINE = Regex("""App Version:\s*([\w.\-]+)\s*\((\d+)\)""")
    private val APP_LINE = Regex("""(?m)^App:\s*([\w.\-]+)\s*\((\d+)\)""")
    private val ANDROID_LINE = Regex("""Android(?: Release)?:\s*([\w.\-]+)\s*\(SDK\s*(\d+)[^)]*\)""")
    private val DOC_SIZE = Regex("""Document:\s*(\d+)\s*x\s*(\d+)""")
    private val LAYERS_LINE = Regex("""Layers Count:\s*(\d+)""")
    private val RAM_LINE = Regex("""Total RAM:\s*(\d+)\s*MB""")

    /** 判定"系统库帧"的模块前缀: 做签名时跳过, 否则每个崩溃的锚点都会落在 libc 里 */
    private val SYSTEM_MODULE = listOf(
        "libc", "libm", "libdl", "libart", "libhwui", "libgui", "libutils", "libcutils", "liblog",
        "libbinder", "libui", "libEGL", "libGLES", "libvulkan", "libnativeloader", "libc++_shared",
    )

    // ---------------------------------------------------------------- 模型

    data class TelemetryDevice(
        val mfr: String = "",
        val model: String = "",
        val dev: String = "",
        val sdk: Int = 0,
        val rel: String = "",
        val fp: String = "",
        val ramMb: Int = 0,
        val harmony: String = "",
    )

    data class TelemetryDoc(
        val w: Int = 0,
        val h: Int = 0,
        val layers: Int = 0,
        val page: String = "",
        val tool: String = "",
        val stylus: String = "",
    )

    data class TelemetryCrumb(val t: Long, val c: String, val e: String)

    data class TelemetryNative(
        val signal: String,
        val code: Int,
        val fault: String,
        val frames: List<String>,
    )

    data class TelemetryEnvelope(
        val kind: String,
        val installId: String,
        val atSec: Long,
        val sig: String,
        val appVer: String,
        val appCode: Int,
        val abi: String,
        val device: TelemetryDevice,
        val doc: TelemetryDoc,
        val crumbs: List<TelemetryCrumb>,
        val stack: String,
        val logcat: String,
        val native: TelemetryNative?,
    ) {
        fun toJson(): String {
            val sb = StringBuilder(2048)
            sb.append("{\"v\":1")
            sb.append(",\"kind\":"); str(sb, kind)
            sb.append(",\"install\":"); str(sb, installId)
            sb.append(",\"at\":").append(atSec)
            sb.append(",\"sig\":"); str(sb, sig)
            sb.append(",\"app\":{\"ver\":"); str(sb, appVer)
            sb.append(",\"code\":").append(appCode)
            sb.append(",\"abi\":"); str(sb, abi); sb.append('}')
            sb.append(",\"dev\":{\"mfr\":"); str(sb, device.mfr)
            sb.append(",\"model\":"); str(sb, device.model)
            sb.append(",\"dev\":"); str(sb, device.dev)
            sb.append(",\"sdk\":").append(device.sdk)
            sb.append(",\"rel\":"); str(sb, device.rel)
            sb.append(",\"fp\":"); str(sb, device.fp)
            sb.append(",\"ram_mb\":").append(device.ramMb)
            sb.append(",\"harmony\":"); str(sb, device.harmony); sb.append('}')
            sb.append(",\"doc\":{\"w\":").append(doc.w)
            sb.append(",\"h\":").append(doc.h)
            sb.append(",\"layers\":").append(doc.layers)
            sb.append(",\"page\":"); str(sb, doc.page)
            sb.append(",\"tool\":"); str(sb, doc.tool)
            sb.append(",\"stylus\":"); str(sb, doc.stylus); sb.append('}')
            sb.append(",\"crumbs\":[")
            crumbs.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                sb.append("{\"t\":").append(c.t).append(",\"c\":")
                str(sb, c.c)
                sb.append(",\"e\":")
                str(sb, c.e)
                sb.append('}')
            }
            sb.append(']')
            sb.append(",\"stack\":"); str(sb, stack)
            sb.append(",\"logcat\":"); str(sb, logcat)
            if (native != null) {
                sb.append(",\"native\":{\"signal\":"); str(sb, native.signal)
                sb.append(",\"code\":").append(native.code)
                sb.append(",\"fault\":"); str(sb, native.fault)
                sb.append(",\"frames\":[")
                native.frames.forEachIndexed { i, f ->
                    if (i > 0) sb.append(',')
                    str(sb, f)
                }
                sb.append("]}")
            } else {
                sb.append(",\"native\":null")
            }
            sb.append('}')
            return sb.toString()
        }

        private fun str(sb: StringBuilder, value: String) {
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') {
                        sb.append("\\u").append(String.format(Locale.US, "%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            sb.append('"')
        }
    }

    /** 构造上下文: 版本/ABI/安装标识, 以及日志里读不到时的设备兜底 */
    data class Context(
        val installId: String,
        val appVer: String,
        val appCode: Int,
        val abi: String,
        val fallbackDevice: TelemetryDevice,
    )

    // ---------------------------------------------------------------- 入口

    /**
     * 从一份本地崩溃报告构造上报体; 完全解析不出东西时返回 null(宁可不发)。
     *
     * @param fileName 报告文件名, 用于判定 java/native 与兜底时间戳
     * @param text 报告全文
     * @param fileModifiedMs 文件 mtime, 兜底崩溃时刻
     * @param liveBreadcrumbs 当前进程的面包屑原文(仅报表里没有面包屑段落时才用)
     */
    fun fromReport(
        fileName: String,
        text: String,
        fileModifiedMs: Long,
        ctx: Context,
        liveBreadcrumbs: List<String> = emptyList(),
    ): TelemetryEnvelope? {
        if (text.isBlank()) return null
        val isNative = fileName.contains("native") || text.contains("Native Crash Report")
        return if (isNative) {
            fromNativeReport(fileName, text, fileModifiedMs, ctx, liveBreadcrumbs)
        } else {
            fromJavaReport(text, fileModifiedMs, ctx, liveBreadcrumbs)
        }
    }

    /** ANR: 系统给的 trace 是唯一线索 */
    fun fromAnr(atMs: Long, trace: String, ctx: Context, liveBreadcrumbs: List<String> = emptyList()): TelemetryEnvelope {
        val body = TelemetryScrubber.cap(TelemetryScrubber.scrub(trace), MAX_STACK_CHARS)
        val anchor = appFrames(body, 3).ifEmpty { listOf("unknown") }.joinToString(";")
        val sig = CrashSignature.of("anr", ctx.appCode, anchor)
        return TelemetryEnvelope(
            kind = "anr",
            installId = ctx.installId,
            atSec = atMs / 1000,
            sig = sig,
            appVer = ctx.appVer,
            appCode = ctx.appCode,
            abi = ctx.abi,
            device = ctx.fallbackDevice,
            doc = TelemetryDoc(),
            crumbs = crumbs(liveBreadcrumbs),
            stack = body,
            logcat = "",
            native = null,
        )
    }

    // ---------------------------------------------------------------- Java

    private fun fromJavaReport(
        text: String,
        fileModifiedMs: Long,
        ctx: Context,
        liveBreadcrumbs: List<String>,
    ): TelemetryEnvelope? {
        val parsed = sectionize(text)

        val exception = parsed.stackLine("Exception:")?.removePrefix("Exception:")?.trim().orEmpty()
        val stackRaw = parsed.section("Thread & Stack Trace")?.joinToString("\n")
            ?: parsed.header.joinToString("\n").takeIf { it.contains("Exception") }
            ?: return null

        val appVer = VERSION_LINE.find(text)?.groupValues?.get(1) ?: ctx.appVer
        val appCode = VERSION_LINE.find(text)?.groupValues?.get(2)?.toIntOrNull() ?: ctx.appCode

        val device = parseJavaDevice(parsed, ctx.fallbackDevice)
        val doc = parseDoc(parsed)
        val crumbLines = parsed.section("User Action Breadcrumbs") ?: liveBreadcrumbs
        val logcatRaw = parsed.section("Recent Process Logcat")
            ?.filter { it.isNotBlank() }
            ?.takeLast(MAX_LOGCAT_LINES)
            ?.joinToString("\n")
            .orEmpty()

        val stack = TelemetryScrubber.cap(TelemetryScrubber.scrub(exception + "\n" + stackRaw), MAX_STACK_CHARS)

        val anchor = buildList {
            add(exception.substringBefore(':').ifBlank { "unknown" })
            addAll(appFrames(stackRaw, 3))
            if (isEmpty()) add("no_app_frame")
        }.joinToString(";")

        val at = parseJavaTime(text) ?: fileModifiedMs

        return TelemetryEnvelope(
            kind = "java_crash",
            installId = ctx.installId,
            atSec = at / 1000,
            sig = CrashSignature.of("java_crash", appCode, anchor),
            appVer = appVer,
            appCode = appCode,
            abi = ctx.abi,
            device = device,
            doc = doc,
            crumbs = crumbs(crumbLines),
            stack = stack,
            logcat = TelemetryScrubber.cap(TelemetryScrubber.scrub(logcatRaw), MAX_LOGCAT_CHARS),
            native = null,
        )
    }

    private fun parseJavaDevice(parsed: Sectioned, fallback: TelemetryDevice): TelemetryDevice {
        val section = parsed.section("Device & OS Information") ?: return fallback
        val kv = section.associate { line ->
            line.substringBefore(':').trim() to line.substringAfter(':', "").trim()
        }
        val android = ANDROID_LINE.find(section.joinToString("\n"))
        val ram = RAM_LINE.find(parsed.section("Memory State")?.joinToString("\n").orEmpty())
        return TelemetryDevice(
            mfr = kv["Manufacturer"] ?: fallback.mfr,
            model = kv["Model"] ?: fallback.model,
            dev = kv["Device"] ?: fallback.dev,
            sdk = android?.groupValues?.get(2)?.toIntOrNull() ?: fallback.sdk,
            rel = android?.groupValues?.get(1) ?: fallback.rel,
            fp = kv["Fingerprint"] ?: fallback.fp,
            ramMb = ram?.groupValues?.get(1)?.toIntOrNull() ?: fallback.ramMb,
            harmony = kv["HarmonyOS / EMUI"] ?: fallback.harmony,
        )
    }

    private fun parseDoc(parsed: Sectioned): TelemetryDoc {
        val section = parsed.section("App Context & Canvas State") ?: return TelemetryDoc()
        val joined = section.joinToString("\n")
        val kv = section.associate { line ->
            line.substringBefore(':').trim() to line.substringAfter(':', "").trim()
        }
        val size = DOC_SIZE.find(joined)
        return TelemetryDoc(
            w = size?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            h = size?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            layers = LAYERS_LINE.find(joined)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            page = kv["Current Page"].orEmpty(),
            tool = kv["Active Tool"].orEmpty(),
            stylus = kv["Stylus Mode"].orEmpty(),
        )
    }

    private fun parseJavaTime(text: String): Long? = runCatching {
        val raw = JAVA_TIME.find(text)?.groupValues?.get(1) ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        fmt.parse(raw.trim())?.time
    }.getOrNull()

    // ---------------------------------------------------------------- Native

    private fun fromNativeReport(
        fileName: String,
        text: String,
        fileModifiedMs: Long,
        ctx: Context,
        liveBreadcrumbs: List<String>,
    ): TelemetryEnvelope? {
        val parsed = sectionize(text)

        val signal = NATIVE_SIGNAL_NAME.find(text)?.groupValues?.get(1) ?: "UNKNOWN"
        val code = NATIVE_SIGNAL_CODE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fault = Regex("""Fault Address:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "0x0"

        val frameLines = parsed.section("Native Stack Trace") ?: emptyList()
        val frames = frameLines.mapNotNull { parseFrame(it) }.take(MAX_FRAMES)

        val stackRaw = buildString {
            append("Signal: $signal (code=$code)\n")
            append("Fault Address: $fault\n")
            frameLines.take(MAX_FRAMES * 2).forEach { append(it).append('\n') }
        }

        val crumbLines = parsed.section("Recent Breadcrumbs") ?: liveBreadcrumbs
        val appState = parsed.section("App State Snapshot")?.joinToString("\n").orEmpty()

        val device = parseNativeDevice(appState, ctx.fallbackDevice)
        val appVer = Regex("""App Version:\s*([\w.\-]+)""").find(text)?.groupValues?.get(1) ?: ctx.appVer
        val appCode = APP_LINE.find(appState)?.groupValues?.get(2)?.toIntOrNull()
            ?: VERSION_LINE.find(appState)?.groupValues?.get(2)?.toIntOrNull()
            ?: ctx.appCode

        val anchor = buildList {
            add(signal)
            val appLevel = frames.filterNot { isSystemModule(it) }.take(3)
            addAll(if (appLevel.isNotEmpty()) appLevel else frames.take(3))
        }.joinToString(";")

        val at = parseNativeFileTime(fileName) ?: fileModifiedMs

        return TelemetryEnvelope(
            kind = "native_crash",
            installId = ctx.installId,
            atSec = at / 1000,
            sig = CrashSignature.of("native_crash", appCode, anchor),
            appVer = appVer,
            appCode = appCode,
            abi = ctx.abi,
            device = device,
            doc = docFromAppState(appState),
            crumbs = crumbs(crumbLines),
            stack = TelemetryScrubber.cap(TelemetryScrubber.scrub(stackRaw), MAX_STACK_CHARS),
            logcat = "",
            native = TelemetryNative(signal, code, fault, frames),
        )
    }

    private fun parseNativeDevice(appState: String, fallback: TelemetryDevice): TelemetryDevice {
        if (appState.isBlank()) return fallback
        // CrashHandler 写入的格式: `<MANUFACTURER>/<BRAND> <MODEL> (<DEVICE>)`
        val deviceLine = Regex("""(?m)^Device:\s*(.+)$""").find(appState)?.groupValues?.get(1).orEmpty()
        val mfr = deviceLine.substringBefore('/', "").trim()
        val rest = deviceLine.substringAfter('/', "")
        val brand = rest.substringBefore(' ').trim()
        val model = rest.substringAfter(' ', "").substringBefore('(').trim()
        val devName = deviceLine.substringAfter('(', "").substringBefore(')').trim()
        val android = ANDROID_LINE.find(appState)
        val fp = Regex("""(?m)^Fingerprint:\s*(.+)$""").find(appState)?.groupValues?.get(1).orEmpty()
        val harmony = Regex("""(?m)^HarmonyOS/EMUI:\s*(.+)$""").find(appState)?.groupValues?.get(1).orEmpty()
        return TelemetryDevice(
            mfr = mfr.ifBlank { brand.ifBlank { fallback.mfr } },
            model = model.ifBlank { fallback.model },
            dev = devName.ifBlank { fallback.dev },
            sdk = android?.groupValues?.get(2)?.toIntOrNull() ?: fallback.sdk,
            rel = android?.groupValues?.get(1) ?: fallback.rel,
            fp = fp.ifBlank { fallback.fp },
            ramMb = fallback.ramMb,
            harmony = harmony.ifBlank { fallback.harmony },
        )
    }

    /** 帧 -> `模块+偏移`; 解析不出模块/偏移时返回 null(该帧无诊断价值, 直接丢弃) */
    private fun parseFrame(line: String): String? {
        val trimmed = line.trim()
        NATIVE_FRAME_NEW.find(trimmed)?.let { m ->
            val module = m.groupValues[2].substringAfterLast('/')
            if (module == "-" || module.isBlank()) return null
            val offset = m.groupValues[1]
            return if (offset == "-") "$module+?" else "$module+${offset.removePrefix("+")}"
        }
        NATIVE_FRAME_OLD.find(trimmed)?.let { m ->
            val module = m.groupValues[1].substringAfterLast('/')
            if (module == "-" || module.isBlank()) return null
            val inner = m.groupValues[2]
            // 只有 `(+0x...)` 这种空符号形式才是模块相对偏移; `(func+0x..)` 是符号相对, 不可用作锚点
            if (inner.startsWith("+")) return "$module+${inner.removePrefix("+").trim()}"
            return "$module+?"
        }
        return null
    }

    private fun docFromAppState(appState: String): TelemetryDoc {
        if (appState.isBlank()) return TelemetryDoc()
        val size = DOC_SIZE.find(appState)
        val layers = LAYERS_LINE.find(appState)
        return TelemetryDoc(
            w = size?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            h = size?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            layers = layers?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            page = Regex("""(?m)^Current Page:\s*(.+)$""").find(appState)?.groupValues?.get(1).orEmpty(),
            tool = Regex("""(?m)^Active Tool:\s*(.+)$""").find(appState)?.groupValues?.get(1).orEmpty(),
        )
    }

    /** crash_native_20261030_120000.log */
    private fun parseNativeFileTime(fileName: String): Long? = runCatching {
        val m = Regex("""crash_native_(\d{8})_(\d{6})""").find(fileName) ?: return null
        val fmt = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        fmt.parse(m.groupValues[1] + " " + m.groupValues[2])?.time
    }.getOrNull()

    // ---------------------------------------------------------------- 共用

    private fun crumbs(lines: List<String>): List<TelemetryCrumb> {
        val entries = lines.mapNotNull { BreadcrumbTags.parseLine(it.removePrefix("- ").trim()) }
            .takeLast(MAX_CRUMBS)
        if (entries.isEmpty()) return emptyList()
        // 旧格式(docs/TELEMETRY.md §2.1)没有时间戳, 此时无法算真偏移, 统一给 0
        if (entries.any { it.atMsOfDay <= 0L }) {
            return entries.map { TelemetryCrumb(0L, it.category, it.tag) }
        }
        val base = entries.first().atMsOfDay
        return entries.map { e ->
            val delta = ((e.atMsOfDay - base) % ONE_DAY_MS + ONE_DAY_MS) % ONE_DAY_MS
            TelemetryCrumb(delta, e.category, e.tag)
        }
    }

    /** 堆栈里的 `at com.reverie.paint.X.y(...)` -> `com.reverie.paint.X.y`(去掉行号, 跨版本才稳定) */
    private fun appFrames(stack: String, limit: Int): List<String> = stack.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("at com.reverie.") }
        .map { it.removePrefix("at ").substringBefore('(') }
        .take(limit)
        .toList()

    private fun isSystemModule(frame: String): Boolean =
        SYSTEM_MODULE.any { frame.startsWith(it) }

    private class Sectioned(
        val header: List<String>,
        val sections: Map<String, List<String>>,
    ) {
        fun section(prefix: String): List<String>? =
            sections.entries.firstOrNull { it.key.startsWith(prefix) }?.value
                ?.dropWhile { it.isBlank() }
                ?.dropLastWhile { it.isBlank() }

        fun stackLine(prefix: String): String? =
            sections.entries.firstOrNull { it.key.startsWith("Thread & Stack Trace") }
                ?.value?.firstOrNull { it.trim().startsWith(prefix) }
                ?: header.firstOrNull { it.trim().startsWith(prefix) }
    }

    private fun sectionize(text: String): Sectioned {
        val header = ArrayList<String>()
        val sections = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in text.lineSequence()) {
            val m = SECTION_HEADER.find(line)
            if (m != null) {
                current = sections.getOrPut(m.groupValues[1].trim()) { ArrayList() }
                continue
            }
            if (current != null) current!!.add(line) else header.add(line)
        }
        return Sectioned(header, sections)
    }
}
