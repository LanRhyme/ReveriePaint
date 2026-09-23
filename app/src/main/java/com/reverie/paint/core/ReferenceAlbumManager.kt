/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AlbumPhoto(
    val id: Long,
    val uri: Uri,
    val dateAdded: Long,
    val displayName: String
)

object ReferenceAlbumManager {
    private val thumbnailCache = LruCache<Uri, Bitmap>(200)

    suspend fun queryAlbumPhotos(context: Context): List<AlbumPhoto> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<AlbumPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateAdded = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                    val displayName = if (nameColumn >= 0) cursor.getString(nameColumn) ?: "" else ""
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    photos.add(AlbumPhoto(id, contentUri, dateAdded, displayName))
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("ReveriePaint", "Failed to query album photos from MediaStore", e)
        }

        photos
    }

    suspend fun loadThumbnail(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        thumbnailCache.get(uri)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else {
                    decodeSampled(contentResolver, uri, 256)
                }
            } catch (_: Throwable) {
                decodeSampled(contentResolver, uri, 256)
            }

            if (bmp != null) {
                thumbnailCache.put(uri, bmp)
            }
            bmp
        }
    }

    private fun decodeSampled(contentResolver: ContentResolver, uri: Uri, targetSize: Int): Bitmap? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { s ->
                BitmapFactory.decodeStream(s, null, options)
            }
            val w = options.outWidth
            val h = options.outHeight
            if (w <= 0 || h <= 0) return null

            var inSampleSize = 1
            while (w / (inSampleSize * 2) >= targetSize && h / (inSampleSize * 2) >= targetSize) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { s ->
                BitmapFactory.decodeStream(s, null, decodeOptions)
            }
        }.getOrNull()
    }
}
