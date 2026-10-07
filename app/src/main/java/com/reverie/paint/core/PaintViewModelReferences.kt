/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.ProjectReferences
import com.reverie.paint.model.ReferenceViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private fun PaintViewModel.referenceState() = ReferenceViewState(referenceWindowOpen, referenceIsGrayscale,
    referenceIsFlipped, referenceActiveTab, referenceBarsCollapsed, referenceZoom, referenceRotation,
    referencePanX, referencePanY)

internal fun PaintViewModel.projectReferenceBitmap(uri: Uri): Bitmap? =
    referenceImages.getOrNull(referenceAlbumSelectedUris.indexOf(uri))

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
    referenceSavedState = referenceState()
}

/** Invalidate UI callbacks; an abandoned unsaved canvas must not acquire another canvas's profile. */
internal fun PaintViewModel.resetProjectReferences(loading: Boolean = false) {
    val oldJob = referenceImportJob
    referenceSession++
    referenceOperation++
    oldJob?.cancel()
    referenceRestoreJob?.cancel()
    referenceImportJob = null
    if (referenceImportActive) isImportingMedia = false
    referenceImportActive = false
    referenceLoading = loading
    referenceBitmapLoading = false
    referenceDeferredFiles = null
    referenceImages = emptyList()
    referenceAlbumSelectedUris = emptyList()
    referenceProfileId = UUID.randomUUID().toString()
    applyReferenceState(ReferenceViewState())
    // An asynchronous autosave may still bind this profile. Unbound working copies are
    // reclaimed by the explicit cache action, never while leaving a canvas.
}

internal fun PaintViewModel.persistProjectReferenceState() {
    if (!hasAppContext()) return
    appContext.getSharedPreferences("paint_prefs", 0).edit {
        putFloat("ref_window_x", referenceWindowX); putFloat("ref_window_y", referenceWindowY)
        putFloat("ref_window_w", referenceWindowWidth); putFloat("ref_window_h", referenceWindowHeight)
        putBoolean("ref_allow_rotation", referenceAllowRotation)
    }
    if (currentPage != Page.PAINTING || referenceLoading || referenceCacheClearing) return
    if (referenceWindowOpen) ensureReferenceImagesLoaded()
    currentProjectFile?.let { referenceStore.bind(it, referenceProfileId) }
    val state = referenceState()
    if (state == referenceSavedState) return
    referenceSavedState = state
    referenceStore.saveState(referenceProfileId, state)
}

/** Called on the UI thread after a successful document load. No reference bytes pass through JNI. */
internal fun PaintViewModel.restoreLocalProjectReferences(path: String) {
    val session = referenceSession
    val fallbackProfile = referenceProfileId
    referenceLoading = true
    referenceRestoreJob = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            referenceIoMutex.withLock {
                runCatching {
                    ensureActive()
                    val profile = referenceStore.profile(path) ?: fallbackProfile.also {
                        referenceStore.bind(path, it)
                    }
                    val files = referenceStore.images(profile)
                    Triple(profile, files, referenceStore.state(profile))
                }
            }
        }
        if (referenceSession != session) return@launch
        result.onSuccess { (profile, files, state) ->
            referenceProfileId = profile
            referenceDeferredFiles = files.takeIf { it.isNotEmpty() }
            referenceAlbumSelectedUris = files.map { Uri.fromFile(it) }
            applyReferenceState(state)
        }.onFailure { showActionToast(R.string.reference_restore_failed, R.drawable.ic_image) }
        referenceLoading = false
        if (referenceWindowOpen) ensureReferenceImagesLoaded()
    }
}

/** A hidden window restores metadata only. Decode on demand, without overwriting newer UI choices. */
internal fun PaintViewModel.ensureReferenceImagesLoaded() {
    if (referenceLoading || referenceBitmapLoading || referenceImportActive || referenceCacheClearing) return
    val files = referenceDeferredFiles ?: return
    val session = referenceSession
    referenceBitmapLoading = true
    referenceRestoreJob = viewModelScope.launch {
        try {
            val images = withContext(Dispatchers.IO) {
                referenceIoMutex.withLock {
                    var budget = ProjectReferences.MAX_BYTES.toLong()
                    files.map { file ->
                        ensureActive()
                        decodeReferenceImage(file, budget).also {
                            budget -= it.byteCount
                            it.prepareToDraw()
                        }
                    }
                }
            }
            if (referenceSession != session) return@launch
            referenceImages = images
            referenceDeferredFiles = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (referenceSession == session) showActionToast(R.string.reference_restore_failed, R.drawable.ic_image)
        } finally {
            if (referenceSession == session) referenceBitmapLoading = false
        }
    }
}

private fun decodeReferenceImage(file: File, budget: Long): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val estimate = ((bounds.outWidth.toLong() + sample - 1) / sample) *
        ((bounds.outHeight.toLong() + sample - 1) / sample) * 4
    require(estimate <= budget)
    return (BitmapFactory.decodeFile(file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        ?: error("Invalid reference image")).also { require(it.byteCount <= budget) }
}

/** Stream source bytes to a temporary file instead of retaining the whole encoded image in memory. */
private fun PaintViewModel.copyReferenceSource(uri: Uri, file: File) {
    val stream = if (uri.scheme == "http" || uri.scheme == "https") {
        java.net.URL(uri.toString()).openConnection().apply {
            connectTimeout = 10000; readTimeout = 15000
        }.getInputStream()
    } else appContext.contentResolver.openInputStream(uri) ?: error("Cannot open reference image")
    stream.use { input ->
        file.outputStream().use { output ->
            val block = ByteArray(8192)
            var total = 0L
            while (true) {
                val size = input.read(block)
                if (size < 0) break
                total += size
                require(total <= ProjectReferences.MAX_BYTES)
                output.write(block, 0, size)
            }
        }
    }
}

/** Imported images are written once; subsequent artwork saves do not encode or copy reference pixels. */
internal fun PaintViewModel.importProjectReferences(uris: List<Uri>, replace: Boolean) {
    if (!hasAppContext() || currentPage != Page.PAINTING) return
    if (referenceLoading || referenceBitmapLoading || isImportingMedia || referenceCacheClearing) {
        showActionToast(R.string.toast_importing_media, R.drawable.ic_image)
        return
    }
    if (uris.isEmpty() && !replace) return
    val session = referenceSession
    val operation = ++referenceOperation
    val profile = referenceProfileId
    currentProjectFile?.let { referenceStore.bind(it, profile) }
    val retained = referenceAlbumSelectedUris.zip(referenceImages).toMap()
    val retainedFiles = referenceAlbumSelectedUris.associateWith { File(requireNotNull(it.path)) }
    val selected = (if (replace) uris else referenceAlbumSelectedUris + uris).distinct().take(MAX_REFERENCE_IMAGES)
    isImportingMedia = true
    referenceImportActive = true
    referenceImportJob = viewModelScope.launch {
        val created = mutableListOf<File>()
        var committed = false
        try {
            val (files, images) = withContext(Dispatchers.IO) {
                referenceIoMutex.withLock {
                    var budget = ProjectReferences.MAX_BYTES.toLong()
                    val files = mutableListOf<File>()
                    val images = selected.map { uri ->
                        ensureActive()
                        val existing = retained[uri]
                        val retainedFile = retainedFiles[uri]
                        var file = retainedFile ?: referenceStore.newImageFile(profile).also { created.add(it) }
                        val bitmap = existing ?: if (retainedFile != null) decodeReferenceImage(retainedFile, budget) else run {
                            val source = File(file.parentFile, file.name + ".incoming")
                            try {
                                copyReferenceSource(uri, source)
                                ensureActive()
                                decodeReferenceImage(source, budget).also { bitmap ->
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(source.absolutePath, bounds)
                                    val extension = when (bounds.outMimeType) {
                                        "image/png" -> "png"
                                        "image/jpeg" -> "jpg"
                                        "image/webp" -> "webp"
                                        else -> null
                                    }
                                    if (extension != null && bounds.outWidth == bitmap.width &&
                                        bounds.outHeight == bitmap.height) {
                                        // No resampling: retain source bytes instead of inflating JPEG into PNG.
                                        file = File(file.parentFile, file.nameWithoutExtension + "." + extension)
                                        created.add(file)
                                        check(source.renameTo(file))
                                    } else {
                                        file.outputStream().use {
                                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                        }
                                    }
                                }
                            } finally { source.delete() }
                        }
                        budget -= bitmap.byteCount
                        require(budget >= 0)
                        if (existing == null) bitmap.prepareToDraw()
                        files.add(file)
                        bitmap
                    }
                    ensureActive()
                    referenceStore.saveImages(profile, files)
                    committed = true
                    files to images
                }
            }
            if (referenceSession != session || referenceOperation != operation) return@launch
            referenceImages = images
            referenceDeferredFiles = null
            referenceAlbumSelectedUris = files.map { Uri.fromFile(it) }
            referenceActiveTab = 0
            referenceWindowOpen = true
            referenceZoom = 1f; referenceRotation = 0f; referencePanX = 0f; referencePanY = 0f
            persistProjectReferenceState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (referenceSession == session && referenceOperation == operation) {
                showActionToast(R.string.reference_import_failed, R.drawable.ic_image)
            }
            android.util.Log.e("ReveriePaint", "Reference import failed", error)
        } finally {
            if (!committed) withContext(NonCancellable + Dispatchers.IO) { created.forEach { it.delete() } }
            if (referenceSession == session && referenceOperation == operation) {
                referenceImportActive = false
                isImportingMedia = false
            }
        }
    }
}

internal fun PaintViewModel.clearProjectReferences() {
    if (referenceLoading || referenceBitmapLoading) return
    referenceImportJob?.cancel()
    if (referenceImportActive) isImportingMedia = false
    referenceImportActive = false
    importProjectReferences(emptyList(), replace = true)
}

internal fun PaintViewModel.importLegacyProjectReferences() {
    val count = appContext.getSharedPreferences("paint_prefs", 0).getInt("ref_images_count", 0)
        .coerceIn(0, MAX_REFERENCE_IMAGES)
    val dir = File(appContext.filesDir, "ref_images")
    importProjectReferences((0 until count).map { File(dir, "ref_$it.png") }
        .filter { it.isFile }.map { Uri.fromFile(it) }, replace = false)
}

internal fun PaintViewModel.refreshReferenceCacheSize() {
    viewModelScope.launch {
        referenceCacheBytes = withContext(Dispatchers.IO) {
            referenceIoMutex.withLock { referenceStore.cacheBytes() }
        }
    }
}

internal fun PaintViewModel.clearReferenceCache() {
    if (referenceCacheClearing) return
    referenceCacheClearing = true
    // Invalidate pending restore/import callbacks before clearing files, including the active artwork.
    resetProjectReferences()
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            referenceIoMutex.withLock { runCatching { referenceStore.clearCache() } }
        }
        hasLegacyReferenceImages = File(appContext.filesDir, "ref_images").isDirectory
        referenceCacheClearing = false
        refreshReferenceCacheSize()
        showActionToast(if (result.isSuccess) R.string.reference_cache_cleared else R.string.reference_cache_failed,
            R.drawable.ic_image)
    }
}

/** Called only after a successful gallery delete; disk cleanup stays off the UI thread. */
internal fun PaintViewModel.forgetLocalReferences(file: File) {
    viewModelScope.launch(Dispatchers.IO) {
        referenceIoMutex.withLock { referenceStore.forget(file) }
    }
}
