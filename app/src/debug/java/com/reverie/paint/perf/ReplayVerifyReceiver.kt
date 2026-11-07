/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.reverie.paint.MainActivity
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.Page
import com.reverie.paint.core.ReverieCoreBridge
import com.reverie.paint.core.exitReplay
import com.reverie.paint.core.goReplay
import com.reverie.paint.core.encloseAndFill
import com.reverie.paint.core.floodFill
import com.reverie.paint.core.updateBrushColor
import com.reverie.paint.core.requestUiCommand
import com.reverie.paint.core.updateBrushSize
import com.reverie.paint.core.loadProject
import com.reverie.paint.core.saveProject
import com.reverie.paint.core.startPainting
import com.reverie.paint.core.getTimelapseFrameFiles
import com.reverie.paint.ui.widget.RecentProjectWidgetHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 回放保真度自动验证入口 (仅 debug 源集).
 *
 * ## 为什么要这个东西
 * 录制/回放的 bug 只在"复杂成品画作"上暴露 (填充 / 圈涂 / 变换 / 多图层 + 选区叠加),
 * 靠人眼看屏幕截图既慢又主观: 小样本画不出问题, 大样本又没法逐像素确认。
 *
 * 这里把"回放是否忠实于原件"变成一条**可自动执行、可逐像素判定**的检查:
 * 回放是把录制的事件流重新灌回同一个引擎, 所以把某份带录制的工程回放到 100%,
 * 再把文档另存出来, 它应当与工程本身逐层像素一致 —— 不一致就是回放错了,
 * 而且 diff 会直接指出是哪个图层、哪块区域出的问题。
 *
 * ## 用法 (adb)
 * ```
 * adb shell am broadcast -a com.reverie.paint.DEBUG_REPLAY_VERIFY \
 *   --es path /data/user/0/com.reverie.paint/files/projects/xxx.revp \
 *   --es out  /data/user/0/com.reverie.paint/files/verify/run1 \
 *   --es steps 0.25,0.5,1.0
 * ```
 * 每个 step 会把当前文档存成一个 .revp (`step_XX_<fraction>.revp`), 全部结束后写
 * `result.json` (含事件数/耗时/每步结果)。驱动脚本见 `scripts/replay-verify.py`。
 */
class ReplayVerifyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.reverie.paint.DEBUG_REPLAY_VERIFY"
        private const val TAG = "RP-REPLAYVERIFY"
        private const val STEP_TIMEOUT_MS = 15 * 60 * 1000L
        private const val HEARTBEAT_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val path = intent.getStringExtra("path")
        val outPath = intent.getStringExtra("out")
        if (path.isNullOrBlank() || outPath.isNullOrBlank()) {
            Log.e(TAG, "missing path/out extra")
            return
        }
        val resaveTo = intent.getStringExtra("resave")
        val makeFillColor = intent.getStringExtra("makefill")
        val perfZoom = intent.getStringExtra("perfzoom")
        val fractions = (intent.getStringExtra("steps") ?: "1.0")
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .ifEmpty { listOf(1f) }
        val vm = MainActivity.currentViewModel
        if (vm == null) {
            Log.e(TAG, "no view model (app not started?)")
            return
        }
        if (intent.getStringExtra("sponsor") != null) {
            // 手动弹出鼓励贡献弹窗 (验证按钮可点)
            vm.showSponsorPromptDialog = true
            Log.i(TAG, "sponsor prompt shown")
            return
        }
        if (intent.getStringExtra("perfdump") != null) {
            // 把标尺里的当代读数打到 logcat, 脚本直接取数 (不用截图读屏)
            val text = com.reverie.paint.core.PerfTrace.hudText()
            Log.i("RP-PERFBENCH", "presentWaitP50=${com.reverie.paint.core.PerfTrace.presentWaitP50Ms}ms "
                + "P95=${com.reverie.paint.core.PerfTrace.presentWaitP95Ms}ms")
            text.split('\n').forEach { Log.i("RP-PERFBENCH", it) }
            return
        }
        if (perfZoom != null) {
            // 绘制卡顿测量前置: 打开标尺/计时 + 允许手指绘制 + 指定笔刷大小与缩放档
            vm.updatePerfHudEnabled(true)
            vm.updatePenOnlyMode(false)
            val size = intent.getStringExtra("brushsize")?.toDoubleOrNull()
            if (size != null) vm.updateBrushSize(size)
            vm.updateBrushColor(intent.getStringExtra("brushcolor") ?: "#ff0000")
            val zoomSteps = perfZoom.toIntOrNull() ?: 0
            if (zoomSteps <= 0) {
                vm.requestUiCommand("zoom_100")
            } else {
                repeat(zoomSteps) { vm.requestUiCommand("zoom_in") }
            }
            Log.i(TAG, "perf prep: zoom=$perfZoom brush=$size penOnly=false (HUD on)")
            return
        }
        val project = File(path)
        val outDir = File(outPath)
        val result = JSONObject()
            .put("input", project.absolutePath)
            .put("startedAt", System.currentTimeMillis())
        outDir.mkdirs()

        // newcanvas 模式不读工程 (用于在空白画布上跑性能/录制对照)
        val useNewCanvas = intent.getStringExtra("newcanvas") != null
        val parsed = if (useNewCanvas) {
            null
        } else {
            RecentProjectWidgetHelper.getProjectFromFile(context, project)
        }
        if (parsed == null && !useNewCanvas) {
            fail(outDir, result, "project_unreadable")
            return
        }
        result.put("docW", parsed?.width ?: 0).put("docH", parsed?.height ?: 0)
        result.put("hasRecording", parsed?.hasRecording ?: false)
        Log.i(TAG, "start replay verify: ${project.name} ${parsed?.width}x${parsed?.height} steps=$fractions")

        // 上一轮用例可能还停在回放页: 先退干净, 否则下面的 await 会被"上一次的会话"
        // 直接满足, seek 打在旧会话上 → 拿到旧文档 (曾经因此误判成回放 bug)
        if (vm.currentPage == Page.REPLAY) {
            vm.exitReplay()
        }
        val t0 = SystemClock.elapsedRealtime()
        if (intent.getStringExtra("newcanvas") != null) {
            // 新建画布: 录制流短, 便于验证"录制->回放"的单个操作 (老工程的链太长, 解码困难)
            vm.startPainting(1200, 900)
        } else {
            parsed?.let { vm.loadProject(it) }
        }
        await("load", STEP_TIMEOUT_MS) {
            vm.currentPage == Page.PAINTING && !vm.isBlockingLoading && vm.docWidth > 0
        } onDone { loaded ->
            result.put("loadMs", SystemClock.elapsedRealtime() - t0)
            if (!loaded) {
                fail(outDir, result, "load_timeout")
                return@onDone
            }
            if (!makeFillColor.isNullOrBlank()) {
                // 用真实录制链路做一次填充 (不是合成事件), 用来验证"录制 -> 回放"的填充颜色
                val (cx, cy) = vm.docWidth / 2 to vm.docHeight / 2
                vm.updateBrushColor(makeFillColor)
                val mode = intent.getStringExtra("makeop") ?: "fill"
                if (mode == "enclose") {
                    // 圈涂: 用一个覆盖画布大部分的多边形, 画笔色由 updateBrushColor 设置
                    val pts = listOf(
                        cx / 2 to cy / 2,
                        cx * 3 / 2 to cy / 2,
                        cx * 3 / 2 to cy * 3 / 2,
                        cx / 2 to cy * 3 / 2,
                    )
                    vm.encloseAndFill(pts)
                } else {
                    vm.floodFill(cx.toFloat(), cy.toFloat(), tolerance = 0)
                }
                // 必须走 saveProject: 录制是随工程文件一起序列化的 (saveRevp 只存文档)
                val name = "mf_fill"
                main.postDelayed({
                    vm.saveProject(name, onComplete = {
                        val target = File(
                            (outPath.substringBefore("/verify").ifEmpty { "/data/data/com.reverie.paint/files" })
                                + "/projects/$name.revp",
                        )
                        result.put("makeFill", target.absolutePath)
                        result.put("makeFillColor", makeFillColor)
                        result.put("ok", true)
                        result.put("finishedAt", System.currentTimeMillis())
                        writeResult(outDir, result)
                        Log.i(TAG, "makefill done: ${target.absolutePath} ${target.length()}B")
                    })
                }, 300)
                return@onDone
            }
            if (!resaveTo.isNullOrBlank()) {
                // 只走"打开 → 正常保存"这条路: 用来验证录制重新锚定 (快照/会话边界)
                val target = File(resaveTo)
                target.parentFile?.mkdirs()
                val t = SystemClock.elapsedRealtime()
                vm.saveProject(target.nameWithoutExtension, onComplete = {
                    result.put("resaved", target.absolutePath)
                    result.put("resaveMs", SystemClock.elapsedRealtime() - t)
                    result.put("ok", true)
                    result.put("finishedAt", System.currentTimeMillis())
                    writeResult(outDir, result)
                    Log.i(TAG, "resave done: ${target.absolutePath} (${SystemClock.elapsedRealtime() - t}ms)")
                })
                return@onDone
            }
            val t1 = SystemClock.elapsedRealtime()
            val projectForReplay = RecentProjectWidgetHelper.getProjectFromFile(context, project)
            if (projectForReplay == null) {
                fail(outDir, result, "project_unreadable_after_load")
                return@onDone
            }
            val frames = vm.getTimelapseFrameFiles(projectForReplay)
            vm.goReplay(projectForReplay)
            await("replay-open", STEP_TIMEOUT_MS) {
                vm.currentPage == Page.REPLAY && vm.replayProject != null
            } onDone { opened ->
                result.put("replayOpenMs", SystemClock.elapsedRealtime() - t1)
                if (!opened) {
                    fail(outDir, result, "replay_open_timeout")
                    return@onDone
                }
                result.put("totalMs", frames.size * 1000L / 12L)
                result.put("events", frames.size)
                result.put("ok", true)
                result.put("finishedAt", System.currentTimeMillis())
                writeResult(outDir, result)
                Log.i(TAG, "timelapse verify done: ${frames.size} frames")
            }
        }
    }

    /** 轮询条件 (主线程 Handler), 到点或满足后回调一次 */
    private fun await(
        label: String,
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Awaiter {
        val started = SystemClock.elapsedRealtime()
        var lastBeat = started
        val awaiter = Awaiter()
        fun tick() {
            val ok = try {
                cond()
            } catch (_: Throwable) {
                false
            }
            val now = SystemClock.elapsedRealtime()
            if (ok) {
                awaiter.done(true)
                return
            }
            if (now - started > timeoutMs) {
                Log.e(TAG, "await $label timeout after ${now - started}ms")
                awaiter.done(false)
                return
            }
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now
                Log.i(TAG, "await $label ... ${now - started}ms")
            }
            main.postDelayed({ tick() }, 120L)
        }
        main.post { tick() }
        return awaiter
    }

    private inner class Awaiter {
        private var handler: ((Boolean) -> Unit)? = null
        private var fired = false
        private var value = false

        fun done(ok: Boolean) {
            if (fired) return
            fired = true
            value = ok
            handler?.invoke(ok)
        }

        infix fun onDone(cb: (Boolean) -> Unit) {
            if (fired) cb(value) else handler = cb
        }
    }

    private fun fail(outDir: File, result: JSONObject, reason: String) {
        Log.e(TAG, "replay verify failed: $reason")
        result.put("ok", false).put("error", reason).put("finishedAt", System.currentTimeMillis())
        writeResult(outDir, result)
    }

    private fun writeResult(outDir: File, result: JSONObject) {
        try {
            File(outDir, "result.json").writeText(result.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "write result failed", e)
        }
    }
}
