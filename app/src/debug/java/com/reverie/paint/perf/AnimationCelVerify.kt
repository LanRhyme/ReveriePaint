/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.perf

import android.graphics.Bitmap
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ReverieCoreBridge as Core
import com.reverie.paint.core.initializeNewAnimationCel
import com.reverie.paint.core.prepareAnimationCel
import com.reverie.paint.core.syncAnimationFromNative
import com.reverie.paint.model.SrtCue
import com.reverie.paint.model.SrtStoryboard
import org.json.JSONObject
import java.io.File

/** Debug-only native integration check. The receiver requires a NEW disposable 64x64 canvas. */
internal object AnimationCelVerify {
    fun run(vm: PaintViewModel, out: File) {
        vm.runCore(after = {}) {
            out.mkdirs()
            val result = JSONObject()
            try {
                check(Core.docWidth() == 64 && Core.docHeight() == 64)
                val cues = (0..19).map { SrtCue(it * 1000L, (it + 1) * 1000L, "Caption $it") }
                Core.addLayer("SRT storyboard")
                val srt = Core.currentLayerIndex()
                check(Core.enableLayerAnimation(srt))
                for (t in 0..19) check(Core.addKeyframe(srt, t))
                Core.storeRevAsset(SrtStoryboard.ASSET, SrtStoryboard.encode(cues))
                Core.storeRevAsset(SrtStoryboard.ASSET + ".layout", "single-frame".toByteArray())
                Core.storeRevAsset(SrtStoryboard.ASSET + ".track", srt.toString().toByteArray())
                vm.anim.storyboard.reset()
                vm.syncAnimationFromNative()

                Core.setAnimationCurrentTime(11, false)
                Core.addLayer("Independent cels")
                val layer = Core.currentLayerIndex()
                vm.initializeNewAnimationCel()
                stamp(0xffff0000.toInt())
                val eleven = pixels(layer, 11)
                check(eleven.any { (it ushr 24) != 0 })
                check(pixels(layer, 0).all { (it ushr 24) == 0 })
                check(pixels(layer, 12).all { (it ushr 24) == 0 })
                result.put("new_layer_only_frame_11", true)

                Core.setAnimationCurrentTime(12, false)
                vm.prepareAnimationCel(layer, 12, preserveDrawing = true)
                stamp(0xff0000ff.toInt())
                val twelve = pixels(layer, 12)
                check(!eleven.contentEquals(twelve))
                check(eleven.contentEquals(pixels(layer, 11)))
                check(pixels(layer, 13).all { (it ushr 24) == 0 })
                result.put("frame_12_independent", true)

                val count = Core.layerCount()
                check(vm.prepareAnimationCel(layer, 12, erase = true))
                check(Core.layerCount() == count)
                check(eleven.contentEquals(pixels(layer, 11)))
                check(pixels(layer, 12).all { (it ushr 24) == 0 })
                result.put("delete_only_frame_12", true)

                Core.undo()
                check(twelve.contentEquals(pixels(layer, 12)))
                check(eleven.contentEquals(pixels(layer, 11)))
                Core.redo()
                check(pixels(layer, 12).all { (it ushr 24) == 0 })
                result.put("delete_undo_redo", true)

                val srtTimes = Core.keyframeTimes(srt)
                check(vm.prepareAnimationCel(srt, 19, erase = true))
                check(srtTimes.contentEquals(Core.keyframeTimes(srt)))
                vm.syncAnimationFromNative()
                check(vm.anim.storyboard.frames.size == 20)
                result.put("last_subtitle_timing_preserved", true)

                val saved = File(out, "independent-cels.revp")
                check(Core.saveRevp(saved.absolutePath))
                check(Core.loadRevp(saved.absolutePath))
                vm.anim.storyboard.reset()
                vm.syncAnimationFromNative()
                check(eleven.contentEquals(pixels(layer, 11)))
                check(pixels(layer, 12).all { (it ushr 24) == 0 })
                check(vm.anim.storyboard.frames.size == 20)
                result.put("save_reopen_pixels_and_subtitles", true)
                result.put("passed", true)
            } catch (error: Throwable) {
                result.put("passed", false).put("error", android.util.Log.getStackTraceString(error))
            } finally {
                File(out, "cel-result.json").writeText(result.toString(2))
                android.util.Log.i("RP-CELVERIFY", result.toString())
            }
        }
    }

    private fun stamp(color: Int) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            Core.stampBitmap(0, 0, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun pixels(layer: Int, time: Int): IntArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            check(Core.renderKeyframeFull(layer, time, bitmap))
            return IntArray(64 * 64).also { bitmap.getPixels(it, 0, 64, 0, 0, 64, 64) }
        } finally {
            bitmap.recycle()
        }
    }
}
