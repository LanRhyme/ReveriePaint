/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

/**
 * 面包屑 -> 上报标签的白名单映射。
 *
 * 现有 [com.reverie.paint.core.Breadcrumbs] 的 15 个调用点里有 5 个把用户数据(
 * 笔刷预设名、画作名)插进了消息串。上传侧**永远不使用消息原文**, 只认这张表:
 * 命中则给动作标签, 未命中一律 `other`。新增调用点忘记登记, 结果是丢标签而**不是**泄漏。
 *
 * 三种日志行格式都要能吃:
 *  - Java 侧: `[14:20:30.500] [Brush] Load preset [3]: <笔刷名>`
 *  - native 新: `- [14:20:30] Brush: Load preset [3]: <笔刷名>`
 *  - native 旧(1.6.x): `- Brush: Load preset [3]: <笔刷名>`(无时间戳)
 *
 * 契约见 docs/TELEMETRY.md §5.4。
 */
object BreadcrumbTags {

    private const val UNKNOWN = "other"

    fun tagOf(category: String, message: String): String = when (category) {
        "Brush" -> when {
            message.startsWith("Load preset") -> "load_preset"
            message.startsWith("Restore preset") -> "restore_preset"
            else -> UNKNOWN
        }

        "Save" -> if (message.startsWith("saveProject")) withFail(message, "save_project") else UNKNOWN

        "IO" -> if (message.startsWith("loadProject")) withFail(message, "load_project") else UNKNOWN

        "Layer" -> when {
            message.startsWith("Add layer") -> "add"
            message.startsWith("Remove layer") -> "remove"
            message.startsWith("Set current layer") -> "select"
            else -> UNKNOWN
        }

        "Tool" -> if (message.startsWith("Switch tool to")) "switch" else UNKNOWN

        "History" -> when {
            message == "Undo" -> "undo"
            message == "Redo" -> "redo"
            else -> UNKNOWN
        }

        "Navigation" -> if (message.startsWith("Current page")) "page" else UNKNOWN

        "FrontBuffer" -> if (message.contains("quarantined")) "quarantined" else UNKNOWN

        // 主线程卡顿看门狗 (MainThreadWatchdog): "stall 4200ms pkg.Class.method"
        // 只带应用自己的类/方法名, 不含文件路径、行号与任何用户内容
        "MainThread" -> when {
            message.startsWith("stall") -> {
                val frame = message.substringAfterLast(' ', "").trim()
                val ms = message.removePrefix("stall ").substringBefore('m').trim().toIntOrNull()
                val prefix = if (ms != null) "stall:${ms}ms:" else "stall:"
                if (frame.isEmpty()) "stall" else (prefix + frame).take(96)
            }
            else -> UNKNOWN
        }

        else -> UNKNOWN
    }

    /** "失败"是有诊断价值的粗粒度信号, 且不含用户内容 */
    private fun withFail(message: String, tag: String): String =
        if (message.contains("FAILED", ignoreCase = true)) "${tag}_fail" else tag

    /**
     * 从一条面包屑里取出时间(D 内毫秒偏移; 拿不到时为 0)与标签; 格式不认识时返回 null。
     */
    fun parseLine(line: String): BreadcrumbEntry? {
        val s = line.trim().let { if (it.startsWith("-")) it.removePrefix("-").trim() else it }

        JAVA_LINE.matchEntire(s)?.let { m ->
            val g = m.groupValues
            return BreadcrumbEntry(
                atMsOfDay(ms(g[1], g[2], g[3]) + g[4].toInt()),
                g[5],
                tagOf(g[5], g[6]),
            )
        }

        NATIVE_LINE.matchEntire(s)?.let { m ->
            val g = m.groupValues
            return BreadcrumbEntry(
                atMsOfDay(ms(g[1], g[2], g[3])),
                g[4],
                tagOf(g[4], g[5]),
            )
        }

        BARE_LINE.matchEntire(s)?.let { m ->
            return BreadcrumbEntry(0L, m.groupValues[1], tagOf(m.groupValues[1], m.groupValues[2]))
        }

        return null
    }

    private fun ms(h: String, m: String, s: String): Long {
        val hh = h.toIntOrNull() ?: return -1
        val mm = m.toIntOrNull() ?: return -1
        val ss = s.toIntOrNull() ?: return -1
        return ((hh * 60L + mm) * 60L + ss) * 1000L
    }

    private fun atMsOfDay(base: Long): Long = if (base < 0) 0L else base

    private val JAVA_LINE = Regex("""\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})]\s*\[([^\]]+)]\s*(.*)""")
    private val NATIVE_LINE = Regex("""\[(\d{2}):(\d{2}):(\d{2})]\s*([^:\s]+):\s*(.*)""")
    private val BARE_LINE = Regex("""([^:\s]+):\s*(.*)""")

    data class BreadcrumbEntry(val atMsOfDay: Long, val category: String, val tag: String)
}
