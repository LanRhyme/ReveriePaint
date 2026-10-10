/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.net.Uri
import com.reverie.paint.R
import com.reverie.paint.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun PaintViewModel.importTargetLayer(): Int = selectedTrackIndex()

/**
 * 导入图像作为关键帧序列: 从当前帧起依次插入当前轨道。
 * 解码在 IO 线程, 写入投递到 render 线程; 采用信号量背压限制在途未写入帧, 防止多大图瞬间 OOM。
 * 全部完成后回调 onDone。
 */
internal fun PaintViewModel.animationImportImages(
    uris: List<android.net.Uri>,
    onDone: (Int) -> Unit = {},
) {
    if (uris.isEmpty()) return
    if (isImportingMedia) {
        showActionToast(R.string.toast_importing_media, R.drawable.ic_image)
        return
    }
    isImportingMedia = true
    val targetDim = maxOf(docWidth, docHeight, 2048)
    val semaphore = java.util.concurrent.Semaphore(2) // 限制在途未写入帧最多 2 张，防 OOM 内存背压

    CoroutineScope(Dispatchers.IO).launch {
        val inserted = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = java.util.concurrent.atomic.AtomicInteger(1) // 末位为"解码完成"标记
        var time = anim.currentTime
        try {
            for (uri in uris) {
                semaphore.acquire()
                val bmp = decodeSampledBitmapFromUri(uri, targetDim)
                if (bmp == null) {
                    semaphore.release()
                    continue
                }
                val t = time++
                pending.incrementAndGet()
                runCore(after = {
                    bmp.recycle()
                    semaphore.release()
                    if (pending.decrementAndGet() == 0) {
                        isImportingMedia = false
                        mainHandler.post { onDone(inserted.get()) }
                    }
                }) {
                    if (ReverieCoreBridge.importKeyframeFromBitmap(importTargetLayer(), t, bmp)) {
                        inserted.incrementAndGet()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("RP_Import", "images import failed", t)
        } finally {
            if (pending.decrementAndGet() == 0) {
                isImportingMedia = false
                mainHandler.post { onDone(inserted.get()) }
            }
        }
    }
}

/** 导入视频: 按文档帧率抽帧 (上限 300 帧) 作为关键帧序列插入当前轨道。
 *  抽帧与引擎写入重叠进行并施加信号量背压与降采样, 全部完成后回调 onDone。 */
internal fun PaintViewModel.animationImportVideo(
    uri: android.net.Uri,
    fps: Int,
    onDone: (Int) -> Unit = {},
) {
    if (isImportingMedia) {
        showActionToast(R.string.toast_importing_media, R.drawable.ic_image)
        return
    }
    isImportingMedia = true
    val context = appContext
    val targetDim = maxOf(docWidth, docHeight, 1920).coerceIn(512, 2048)
    val semaphore = java.util.concurrent.Semaphore(2) // 限制在途未写入帧最多 2 张，防 OOM

    CoroutineScope(Dispatchers.IO).launch {
        val inserted = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = java.util.concurrent.atomic.AtomicInteger(1)
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            val stepUs = 1_000_000L / fps.coerceIn(1, 60)
            val maxFrames = 300
            var frame = 0
            var time = anim.currentTime
            while (frame * stepUs / 1000 <= durationMs && frame < maxFrames) {
                semaphore.acquire()
                var rawBmp: Bitmap? = null
                try {
                    rawBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            frame * stepUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                            targetDim,
                            targetDim,
                        )
                    } else {
                        retriever.getFrameAtTime(
                            frame * stepUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                        )
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("RP_Import", "getFrameAtTime error", e)
                }

                if (rawBmp == null) {
                    semaphore.release()
                    break
                }

                // 尺寸安全兜底: 若抽出的帧仍大于 targetDim, 做缩放
                val bmp = if (rawBmp.width > targetDim || rawBmp.height > targetDim) {
                    val scale = targetDim.toFloat() / maxOf(rawBmp.width, rawBmp.height)
                    val sw = (rawBmp.width * scale).toInt().coerceAtLeast(1)
                    val sh = (rawBmp.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(rawBmp, sw, sh, true)
                    rawBmp.recycle()
                    scaled
                } else {
                    rawBmp
                }

                val t = time++
                pending.incrementAndGet()
                runCore(after = {
                    bmp.recycle()
                    semaphore.release()
                    if (pending.decrementAndGet() == 0) {
                        isImportingMedia = false
                        mainHandler.post { onDone(inserted.get()) }
                    }
                }) {
                    if (ReverieCoreBridge.importKeyframeFromBitmap(importTargetLayer(), t, bmp)) {
                        inserted.incrementAndGet()
                    }
                }
                frame++
            }
        } catch (t: Throwable) {
            android.util.Log.e("RP_Import", "video import failed", t)
        } finally {
            runCatching { retriever.release() }
            if (pending.decrementAndGet() == 0) {
                isImportingMedia = false
                mainHandler.post { onDone(inserted.get()) }
            }
        }
    }
}

/** 导入音频: 字节存入 .revp 的 assets/<name> (随保存/加载持久化)。 */
internal fun PaintViewModel.animationImportAudio(uri: android.net.Uri, name: String) {
    runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null && bytes.isNotEmpty()) {
            ReverieCoreBridge.storeRevAsset(name, bytes)
        }
    }
}

/**
 * 当前生效的轨道索引: 优先时间轴选中, 否则跟随画布当前图层。
 *
 * **只能在 reverie-render 线程调用** (内部会回退到 JNI 查当前图层)。
 * 主线程需要这个值时, 请把它写进 runCore 的 op 块, 或直接读
 * [AnimationState.selectedTrack] —— 后者是 UI 镜像, 线程安全。
 */
