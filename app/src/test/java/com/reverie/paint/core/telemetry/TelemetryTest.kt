/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryScrubberTest {

    @Test
    fun `scrubs external storage path and project file name`() {
        val raw = "W/RP_IO: failed to open /storage/emulated/0/Pictures/我的画.revp"
        val out = TelemetryScrubber.scrub(raw)
        assertFalse("路径整段应被替换", out.contains("我的画"))
        assertFalse(out.contains("/storage/emulated/0/Pictures/"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun `scrubs bare chinese file name keeping extension`() {
        val out = TelemetryScrubber.scrub("Failed to decode 日落.png from cache")
        assertFalse(out.contains("日落"))
        assertTrue(out.contains("*.png"))
    }

    @Test
    fun `keeps credentials redaction from diagnostics sanitizer`() {
        val out = TelemetryScrubber.scrub("Authorization: Bearer abcdef1234567890abcdef")
        assertFalse(out.contains("abcdef1234567890abcdef"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `scrubs lan hosts so nas names do not leak`() {
        val ip = TelemetryScrubber.scrub("GET http://192.168.1.5:8080/remote.php/dav/files/x failed")
        assertFalse(ip.contains("192.168.1.5"))
        assertTrue(ip.contains("<lan>"))

        val name = TelemetryScrubber.scrub("sync target https://my-nas.lan/dav unreachable")
        assertFalse(name.contains("my-nas"))
        assertTrue(name.contains("<lan>"))
    }

    @Test
    fun `keeps own app private paths which carry no user content`() {
        val raw = "D/RP_IO: cache at /data/user/0/com.reverie.paint/cache/swap/abc.bin"
        assertEquals(raw, TelemetryScrubber.scrub(raw))
    }

    @Test
    fun `is idempotent`() {
        val raw = "open /storage/emulated/0/DCIM/作品.jpg then https://nas.lan/x 与 我的画.revp"
        val once = TelemetryScrubber.scrub(raw)
        val twice = TelemetryScrubber.scrub(once)
        assertEquals(once, twice)
    }

    @Test
    fun `cap marks truncation`() {
        val out = TelemetryScrubber.cap("a".repeat(500), 100)
        assertTrue(out.length <= 200)
        assertTrue(out.contains("[truncated 400 chars]"))
    }
}

class BreadcrumbTagsTest {

    @Test
    fun `maps known breadcrumb lines to tags without leaking names`() {
        val entry = BreadcrumbTags.parseLine("[14:20:30.500] [Brush] Load preset [3]: 我的笔刷 (v2)")
        assertNotNull(entry)
        entry!!
        assertEquals("Brush", entry.category)
        assertEquals("load_preset", entry.tag)
        assertEquals(14L * 3600_000 + 20 * 60_000 + 30_500L, entry.atMsOfDay)
    }

    @Test
    fun `maps project io lines and failure suffix`() {
        val ok = BreadcrumbTags.parseLine("[14:20:30.500] [IO] loadProject SUCCESS: 春天的画 (healed=false)")
        assertEquals("load_project", ok?.tag)

        val bad = BreadcrumbTags.parseLine("[14:20:30.500] [Save] saveProject FAILED for: 春天的画")
        assertEquals("save_project_fail", bad?.tag)
    }

    @Test
    fun `unknown call sites degrade to other instead of leaking`() {
        val entry = BreadcrumbTags.parseLine("[14:20:30.500] [Weird] something: with user data")
        assertEquals("other", entry?.tag)
        assertEquals("Weird", entry?.category)
    }

    @Test
    fun `malformed line yields null`() {
        assertNull(BreadcrumbTags.parseLine("no timestamp here"))
    }

    @Test
    fun `maps main thread stall to a tag carrying only app code frames`() {
        val entry = BreadcrumbTags.parseLine(
            "[14:20:30.500] [MainThread] stall 4200ms canvas.CanvasTouchView.onTouchEvent",
        )
        assertEquals("MainThread", entry?.category)
        assertEquals("stall:4200ms:canvas.CanvasTouchView.onTouchEvent", entry?.tag)

        // 帧缺失时退化成裸标签, 不会把整行消息带出去
        val bare = BreadcrumbTags.parseLine("[14:20:30.500] [MainThread] stall")
        assertEquals("stall", bare?.tag)
    }
}

class StallDetectorTest {

    @Test
    fun `records a stall only after the beat goes quiet for the threshold`() {
        val d = StallDetector(stallMs = 2_500L)
        assertFalse("还没打过卡, 不该判定", d.poll(0L))
        d.onBeat(1_000L)
        assertFalse("2.4s 还不够", d.poll(3_400L))
        assertTrue("2.6s 应该判定卡顿", d.poll(3_600L))
        assertEquals(2_600L, d.stalledMs(3_600L))
    }

    @Test
    fun `one stall episode reports once and re-arms after the next beat`() {
        val d = StallDetector(stallMs = 2_500L)
        // 打卡时间取非零基准: uptimeMillis 在应用运行期恒大于 0, 0 是"还没打过卡"的哨兵
        d.onBeat(10_000L)
        assertTrue("第一次判定到卡顿就该记", d.poll(13_000L))
        assertFalse(d.poll(14_000L))
        assertFalse("同一轮不该重复记", d.poll(19_000L))
        d.onBeat(19_500L)
        assertTrue("恢复后再次卡住要能再记", d.poll(22_500L))
    }

    @Test
    fun `rate limits bursts to the per-window cap`() {
        val d = StallDetector(stallMs = 2_500L, maxPerWindow = 3, windowMs = 60_000L)
        var t = 10_000L
        repeat(3) {
            d.onBeat(t)
            assertTrue(d.poll(t + 3_000L))
            t += 4_000L
        }
        d.onBeat(t)
        assertFalse(d.poll(t + 3_000L))
        // 窗口过去之后恢复
        d.onBeat(t + 90_000L)
        assertTrue(d.poll(t + 93_000L))
    }
}

class MainThreadWatchdogTest {

    @Test
    fun `stall record only counts when it belongs to the anr time window`() {
        val anr = 1_700_000_000_000L
        val minute = 60_000L
        assertTrue(MainThreadWatchdog.isStallWithinWindow(anr - 5_000L, anr))
        assertTrue(MainThreadWatchdog.isStallWithinWindow(anr - 9 * minute, anr))
        assertTrue(MainThreadWatchdog.isStallWithinWindow(anr + 30_000L, anr))
        // 陈旧记录 / 未来记录 / 缺时间戳都不认
        assertFalse(MainThreadWatchdog.isStallWithinWindow(anr - 11 * minute, anr))
        assertFalse(MainThreadWatchdog.isStallWithinWindow(anr + 2 * minute, anr))
        assertFalse(MainThreadWatchdog.isStallWithinWindow(0L, anr))
        assertFalse(MainThreadWatchdog.isStallWithinWindow(anr, 0L))
    }
}

class CrashSignatureTest {

    @Test
    fun `signature is stable for same anchor`() {
        val a = CrashSignature.of("native_crash", 48, "SIGSEGV;libreverie_jni.so+0x1a2b3c")
        val b = CrashSignature.of("native_crash", 48, "SIGSEGV;libreverie_jni.so+0x1a2b3c")
        assertEquals(a, b)
        assertEquals(32, a.length)
    }

    @Test
    fun `signature differs across version and kind`() {
        val base = CrashSignature.of("native_crash", 48, "anchor")
        assertFalse(base == CrashSignature.of("native_crash", 49, "anchor"))
        assertFalse(base == CrashSignature.of("java_crash", 48, "anchor"))
    }
}

class TelemetryPayloadTest {

    private val ctx = TelemetryPayload.Context(
        installId = "11111111-2222-3333-4444-555555555555",
        appVer = "1.6.1",
        appCode = 48,
        abi = "arm64-v8a",
        fallbackDevice = TelemetryPayload.TelemetryDevice(
            mfr = "OnePlus", model = "PLQ110", dev = "OP5D3BL1",
            sdk = 36, rel = "16", fp = "fp", ramMb = 12288, harmony = "",
        ),
    )

    private val javaReport = """
        ===================================================
                 ReveriePaint Java Crash Report
        ===================================================
        Time: 2026-10-30 14:20:31.123
        App Version: 1.6.1 (48)

        --- [Device & OS Information] ---
        Brand: OnePlus
        Manufacturer: OnePlus
        Model: PLQ110
        Product: PLQ110
        Device: OP5D3BL1
        Hardware: qcom
        Android Release: 16 (SDK 36)
        Fingerprint: OnePlus/PLQ110/OP5D3BL1:16/AP3A.240905.015/1234:user/release-keys

        --- [Memory State] ---
        Total RAM: 12288 MB
        Available RAM: 4096 MB

        --- [App Context & Canvas State] ---
        Current Page: PAINTING
        Active Tool: brush
        Document: 2400 x 3200
        Layers Count: 12, Active Layer: 0
        Stylus Mode: huawei_mpen

        --- [User Action Breadcrumbs (Last 100 Events)] ---
        [14:20:29.001] [Tool] Switch tool to: brush
        [14:20:30.500] [Brush] Load preset [3]: 我的笔刷 (v2)
        [14:20:31.000] [History] Undo

        --- [Thread & Stack Trace] ---
        Crashed Thread: main (id=2, priority=5)
        Exception: java.lang.NullPointerException: on null object

        java.lang.NullPointerException: on null object
        ${'\t'}at com.reverie.paint.core.PaintViewModelBrush.applyPreset(PaintViewModelBrush.kt:812)
        ${'\t'}at com.reverie.paint.ui.painting.brush.BrushPanel.render(BrushPanel.kt:120)
        ${'\t'}at android.view.View.draw(View.java:23456)

        --- [Recent Process Logcat (Last 200 Lines, Sanitized)] ---
        D/RP_IO: saveRevp to /storage/emulated/0/Pictures/日落.revp
        W/libc: noise

        ===================================================
    """.trimIndent()

    private val nativeReport = """
        ===================================================
                  ReveriePaint Native Crash Report
        ===================================================
        App Version: 1.6.1
        Signal: SIGSEGV (code=1)
        Fault Address: 0x0000000000000010
        Crashed Thread PID: 1234, TID: 5678 (RenderThread)
        --- [Registers ARM64] ---
        pc: 0x7f8a1c2b3c  sp: 0x7f0000000000
        --- [Native Stack Trace] ---
        #00 pc 0x7f8a1c2b3c  +0x1a2b3c  /data/app/~~abc==/com.reverie.paint-xyz/lib/arm64/libreverie_jni.so
        #01 pc 0x7f8a9c2b90  +0x2f19c  /data/app/~~abc==/com.reverie.paint-xyz/lib/arm64/libc.so
        #02 pc 0x7f8a9c2c00  +0x3  /data/app/~~abc==/com.reverie.paint-xyz/lib/arm64/libQt6Core_arm64-v8a.so  (QString::fromLatin1+20)
        #03 pc 0x7f0000001111  -  -

        --- [App State Snapshot] ---
        App: 1.6.1 (48)
        Device: OnePlus/OnePlus PLQ110 (OP5D3BL1)
        Android: 16 (SDK 36)
        Fingerprint: OnePlus/PLQ110/OP5D3BL1:16/AP3A.240905.015/1234:user/release-keys
        HarmonyOS/EMUI:
        FrontBuffer: pref=false graphicsCore=1.1.0-beta01 supported=true quarantined=false

        --- [Recent Breadcrumbs] ---
        - [14:20:30.500] [Brush] Load preset [3]: 我的笔刷 (v2)
    """.trimIndent()

    @Test
    fun `parses java report into contract envelope`() {
        val env = TelemetryPayload.fromReport(
            "crash_java_20261030_142031.log", javaReport, 1_700_000_000_000L, ctx,
        )
        assertNotNull(env)
        env!!
        assertEquals("java_crash", env.kind)
        assertEquals(48, env.appCode)
        assertEquals("PAINTING", env.doc.page)
        assertEquals(2400, env.doc.w)
        assertEquals(3200, env.doc.h)
        assertEquals(12, env.doc.layers)
        assertEquals("OnePlus", env.device.mfr)
        assertEquals("PLQ110", env.device.model)
        assertEquals(12288, env.device.ramMb)
        assertEquals(36, env.device.sdk)
        assertEquals(3, env.crumbs.size)
        assertEquals("load_preset", env.crumbs[1].e)
        assertEquals(0L, env.crumbs[0].t)
        assertEquals(1_499L, env.crumbs[1].t)
        assertTrue(env.stack.contains("NullPointerException"))
        assertEquals(32, env.sig.length)
    }

    @Test
    fun `json never contains user content`() {
        val env = TelemetryPayload.fromReport(
            "crash_java_20261030_142031.log", javaReport, 1_700_000_000_000L, ctx,
        )!!
        val json = env.toJson()
        assertFalse("笔刷名不得出现", json.contains("我的笔刷"))
        assertFalse("画作名不得出现", json.contains("日落"))
        assertFalse("用户存储路径不得出现", json.contains("/storage/emulated/0/Pictures"))
        assertTrue(json.contains("\"kind\":\"java_crash\""))
        assertTrue(json.contains("\"page\":\"PAINTING\""))
    }

    @Test
    fun `parses native report with module offsets`() {
        val env = TelemetryPayload.fromReport(
            "crash_native_20261030_142031.log", nativeReport, 1_700_000_000_000L, ctx,
        )
        assertNotNull(env)
        env!!
        assertEquals("native_crash", env.kind)
        assertEquals("SIGSEGV", env.native?.signal)
        assertEquals(1, env.native?.code)
        assertEquals(
            listOf(
                "libreverie_jni.so+0x1a2b3c",
                "libc.so+0x2f19c",
                "libQt6Core_arm64-v8a.so+0x3",
            ),
            env.native?.frames,
        )
        // 符号相对偏移不得被当成模块偏移
        assertFalse(env.native!!.frames.any { it.contains("symbol") })
        assertEquals("OnePlus", env.device.mfr)
        assertEquals("PLQ110", env.device.model)
        assertEquals("OP5D3BL1", env.device.dev)
        assertEquals(48, env.appCode)
        assertEquals(1, env.crumbs.size)
        assertFalse(env.toJson().contains("我的笔刷"))
    }

    @Test
    fun `signature is reproducible across two identical crashes`() {
        val a = TelemetryPayload.fromReport("crash_native_20261030_142031.log", nativeReport, 0L, ctx)!!
        val b = TelemetryPayload.fromReport("crash_native_20261031_090000.log", nativeReport, 5L, ctx)!!
        assertEquals(a.sig, b.sig)
    }

    @Test
    fun `legacy native frames without module offset still parse`() {
        val legacy = """
            App Version: 1.6.1
            Signal: SIGABRT (code=-6)
            Fault Address: 0x0
            --- [Native Stack Trace] ---
            #00 pc 0x7f8a1c2b3c  /data/app/lib/arm64/libreverie_jni.so (+0x1a2b3c)
            #01 pc 0x7f8a9c2b90  /data/app/lib/arm64/libQt6Core.so (QString::fromLatin1+20)
            --- [App State Snapshot] ---
            App: 1.6.1 (48)
        """.trimIndent()
        val env = TelemetryPayload.fromReport("crash_native_20261030_142031.log", legacy, 1L, ctx)!!
        assertEquals("libreverie_jni.so+0x1a2b3c", env.native?.frames?.get(0))
        assertEquals("libQt6Core.so+?", env.native?.frames?.get(1))
    }

    @Test
    fun `anr envelope keeps trace and derives signature`() {
        val trace = "----- pid 1234 at 2026-10-30 -----\n\"main\" prio=5\n" +
            "  at com.reverie.paint.core.PaintViewModel.onTouch(PaintViewModel.kt:99)\n" +
            "  at android.os.Looper.loop(Looper.java:1)\n"
        val env = TelemetryPayload.fromAnr(1_700_000_000_000L, trace, ctx)
        assertEquals("anr", env.kind)
        assertEquals(1_700_000_000L, env.atSec)
        assertTrue(env.stack.contains("PaintViewModel.onTouch"))
        assertEquals(32, env.sig.length)
    }

    @Test
    fun `blank report yields null instead of empty upload`() {
        assertNull(TelemetryPayload.fromReport("crash_java_x.log", "   ", 0L, ctx))
    }

    /**
     * 回归夹具: 这份日志是用宿主机 mock(把 ReverieCrashHandler.cpp 编译成本地可执行文件,
     * 制造真实段错误)真实产出的, 不是手写样例 —— 之前手写样例就漏掉了
     * `Signal: SIGSEGV (Segmentation violation) (code=1)` 与 native 面包屑的 `- [时间] 类别: 消息` 格式。
     */
    @Test
    fun `parses real native log captured from a host crash`() {
        val text = javaClass.getResourceAsStream("/telemetry/crash_native_host.log")!!
            .bufferedReader().readText()
        val env = TelemetryPayload.fromReport("crash_native_20261030_144054.log", text, 0L, ctx)!!

        assertEquals("native_crash", env.kind)
        assertEquals("SIGSEGV", env.native?.signal)
        assertEquals(1, env.native?.code)
        assertEquals("0x0000000000000000", env.native?.fault)
        assertEquals("crashhost+0x18d6", env.native?.frames?.first())
        assertTrue("真实崩溃点(render_frame)在帧里", env.native!!.frames.contains("crashhost+0x2e0e"))
        assertEquals(listOf("load_preset", "switch"), env.crumbs.map { it.e })
        assertEquals("Host", env.device.mfr)
        assertEquals(36, env.device.sdk)
        assertFalse("面包屑里的笔刷名不得出现在上报体", env.toJson().contains("my-brush"))
    }
}
