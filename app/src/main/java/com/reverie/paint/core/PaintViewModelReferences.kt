/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.ProjectReferences
import com.reverie.paint.model.ReferenceViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

private fun PaintViewModel.referenceState() = ReferenceViewState(referenceWindowOpen, referenceIsGrayscale,
    referenceIsFlipped, referenceActiveTab, referenceBarsCollapsed, referenceZoom, referenceRotation,
    referencePanX, referencePanY)

// In-memory selection identities, never serialized: portable references need no gallery permission or URI.
private fun PaintViewModel.updateReferenceSelection() {
    referenceAlbumSelectedUris = referenceImages.indices.map {
        Uri.parse("reverie-reference://$referenceSession/$referenceOperation/$it")
    }
}

internal fun PaintViewModel.projectReferenceBitmap(uri: Uri): Bitmap? {
    val index = referenceAlbumSelectedUris.indexOf(uri)
    return referenceImages.getOrNull(index)
}

private fun PaintViewModel.applyReferenceState(state: ReferenceViewState) {
    referenceWindowOpen = state.open
    referenceIsGrayscale = state.grayscale
    referenceIsFlipped = state.flipped
    referenceActiveTab = state.tab
    referenceBarsCollapsed = state.collapsed
    referenceZoom = state.zoom
    referenceRotation = if (referenceAllowRotation) state.rotation else 0f
    referencePanX = state.panX
    referencePanY = state.panY
    referenceSavedState = ProjectReferences.encodeState(referenceState())
}

/** UI-thread lifecycle fence: a completed decode may only publish to its original document. */
internal fun PaintViewModel.resetProjectReferences(loading: Boolean = false) {
    referenceSession++
    referenceOperation++
    referenceImportJob?.cancel()
    referenceImportJob = null
    if (referenceImportActive) isImportingMedia = false
    referenceImportActive = false
    referenceLoading = loading
    referenceImages = emptyList()
    referenceAlbumSelectedUris = emptyList()
    applyReferenceState(ReferenceViewState())
}

/** Render thread only. storeRevAsset ignores empty data, so use a valid, empty archive. */
internal fun resetNativeProjectReferences() {
    ReverieCoreBridge.storeRevAsset(ProjectReferences.IMAGES_ASSET, ProjectReferences.encodeImages(emptyList()))
    ReverieCoreBridge.storeRevAsset(ProjectReferences.STATE_ASSET, ProjectReferences.encodeState(ReferenceViewState()))
}

internal fun PaintViewModel.persistProjectReferenceState() {
    if (!hasAppContext()) return
    // Window placement and rotation permission are device preferences, not portable document coordinates.
    appContext.getSharedPreferences("paint_prefs", 0).edit()
        .putFloat("ref_window_x", referenceWindowX).putFloat("ref_window_y", referenceWindowY)
        .putFloat("ref_window_w", referenceWindowWidth).putFloat("ref_window_h", referenceWindowHeight)
        .putBoolean("ref_allow_rotation", referenceAllowRotation).apply()
    if (currentPage != Page.PAINTING || referenceLoading) return
    val bytes = ProjectReferences.encodeState(referenceState())
    if (bytes.contentEquals(referenceSavedState)) return
    referenceSavedState = bytes
    val session = referenceSession
    isModified = true
    hasPendingMajorOp = true
    runCore(render = false) {
        if (referenceSession == session) ReverieCoreBridge.storeRevAsset(ProjectReferences.STATE_ASSET, bytes)
    }
}

/** Called after the document is loaded on the render thread; decoding stays off both render and UI threads. */
internal fun PaintViewModel.restoreProjectReferencesOnRender(session: Long) {
    val imageBytes = ReverieCoreBridge.revAssetBytes(ProjectReferences.IMAGES_ASSET)
    val stateBytes = ReverieCoreBridge.revAssetBytes(ProjectReferences.STATE_ASSET)
    mainHandler.post {
        if (referenceSession != session) return@post
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { decodeReferenceImages(ProjectReferences.decodeImages(imageBytes)) }
            }
            if (referenceSession != session) return@launch
            referenceImages = result.getOrDefault(emptyList())
            updateReferenceSelection()
            applyReferenceState(ProjectReferences.decodeState(stateBytes))
            referenceLoading = false
            if (result.isFailure) showActionToast(R.string.reference_restore_failed, R.drawable.ic_image)
        }
    }
}

private fun decodeReferenceImages(images: List<ByteArray>, budget: Long = ProjectReferences.MAX_BYTES.toLong()): List<Bitmap> {
    val decoded = mutableListOf<Bitmap>()
    var used = 0L
    for (bytes in images) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val estimate = ((bounds.outWidth.toLong() + sample - 1) / sample) *
            ((bounds.outHeight.toLong() + sample - 1) / sample) * 4
        require(used + estimate <= budget)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Invalid reference image")
        used += bitmap.byteCount
        require(used <= budget)
        decoded.add(bitmap)
    }
    return decoded
}

private fun readReferenceUri(vm: PaintViewModel, uri: Uri): ByteArray {
    val stream = if (uri.scheme == "http" || uri.scheme == "https") {
        java.net.URL(uri.toString()).openConnection().apply {
            connectTimeout = 10000; readTimeout = 15000
        }.getInputStream()
    } else vm.appContext.contentResolver.openInputStream(uri) ?: error("Cannot open reference image")
    return stream.use { input ->
        val out = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val size = input.read(block)
            if (size < 0) break
            require(out.size().toLong() + size <= ProjectReferences.MAX_BYTES)
            out.write(block, 0, size)
        }
        out.toByteArray()
    }
}

/** Reference imports are atomic: an invalid image leaves the previous selection intact. */
internal fun PaintViewModel.importProjectReferences(uris: List<Uri>, replace: Boolean) {
    if (!hasAppContext() || currentPage != Page.PAINTING) return
    if (referenceLoading || isImportingMedia) {
        showActionToast(R.string.toast_importing_media, R.drawable.ic_image)
        return
    }
    if (uris.isEmpty() && !replace) return
    val session = referenceSession
    val operation = ++referenceOperation
    val selected = uris.distinct().take(MAX_REFERENCE_IMAGES)
    val original = if (replace) emptyList() else referenceImages
    val retained = referenceAlbumSelectedUris.zip(referenceImages).toMap()
    isImportingMedia = true
    referenceImportActive = true
    referenceImportJob = viewModelScope.launch {
        try {
            val (images, bundle) = withContext(Dispatchers.IO) {
                val added = mutableListOf<Bitmap>()
                var budget = ProjectReferences.MAX_BYTES - original.sumOf { it.byteCount.toLong() }
                for (uri in selected) {
                    val bitmap = retained[uri] ?: decodeReferenceImages(
                        listOf(readReferenceUri(this@importProjectReferences, uri)), budget).single()
                    budget -= bitmap.byteCount
                    require(budget >= 0)
                    added.add(bitmap)
                }
                val images = (original + added).takeLast(MAX_REFERENCE_IMAGES)
                require(images.sumOf { it.byteCount.toLong() } <= ProjectReferences.MAX_BYTES)
                val encoded = images.map { bitmap ->
                    ByteArrayOutputStream().also {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }.toByteArray()
                }
                images to ProjectReferences.encodeImages(encoded)
            }
            if (referenceSession != session || referenceOperation != operation) return@launch
            // Keep the job pending until both native assets and the UI have been published.
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                var stored = false
                runCore(render = false, after = {
                    if (stored && referenceSession == session && referenceOperation == operation) {
                        referenceImages = images
                        updateReferenceSelection()
                        referenceActiveTab = 0
                        referenceWindowOpen = true
                        referenceZoom = 1f; referenceRotation = 0f; referencePanX = 0f; referencePanY = 0f
                        isModified = true
                        hasPendingMajorOp = true
                        persistProjectReferenceState()
                    } else if (!stored && referenceSession == session && referenceOperation == operation) {
                        showActionToast(R.string.reference_import_failed, R.drawable.ic_image)
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }) {
                    if (referenceSession == session && referenceOperation == operation) {
                        ReverieCoreBridge.storeRevAsset(ProjectReferences.IMAGES_ASSET, bundle)
                        stored = true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (referenceSession == session && referenceOperation == operation) {
                showActionToast(R.string.reference_import_failed, R.drawable.ic_image)
            }
            android.util.Log.e("ReveriePaint", "Reference import failed", error)
        } finally {
            if (referenceSession == session && referenceOperation == operation) {
                referenceImportActive = false
                isImportingMedia = false
            }
        }
    }
}

internal fun PaintViewModel.clearProjectReferences() {
    if (referenceLoading) return
    referenceImportJob?.cancel()
    // Invalidate even a native commit already queued by a cancelled import.
    referenceOperation++
    referenceImportJob = null
    if (referenceImportActive) isImportingMedia = false
    referenceImportActive = false
    referenceImages = emptyList()
    referenceAlbumSelectedUris = emptyList()
    val session = referenceSession
    isModified = true
    hasPendingMajorOp = true
    runCore(render = false) {
        if (referenceSession == session) {
            ReverieCoreBridge.storeRevAsset(ProjectReferences.IMAGES_ASSET, ProjectReferences.encodeImages(emptyList()))
        }
    }
    referenceSavedState = ByteArray(0)
    resetReferenceTransform()
}

internal fun PaintViewModel.importLegacyProjectReferences() {
    val count = appContext.getSharedPreferences("paint_prefs", 0).getInt("ref_images_count", 0)
        .coerceIn(0, MAX_REFERENCE_IMAGES)
    val dir = File(appContext.filesDir, "ref_images")
    val files = (0 until count).map { File(dir, "ref_$it.png") }.filter { it.isFile }
    importProjectReferences(files.map { Uri.fromFile(it) }, replace = false)
}

/** A save requested while importing must include the completed import, not close over the old document. */
internal fun PaintViewModel.deferForReferenceImport(action: () -> Unit): Boolean {
    val job = referenceImportJob?.takeIf { it.isActive } ?: return false
    val session = referenceSession
    viewModelScope.launch {
        job.join()
        if (referenceSession == session && currentPage == Page.PAINTING) action()
    }
    return true
}
