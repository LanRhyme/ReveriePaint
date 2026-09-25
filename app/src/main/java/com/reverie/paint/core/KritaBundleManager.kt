package com.reverie.paint.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object KritaBundleManager {

    /**
     * Builds and exports a standard Krita .bundle file containing the specified presets and brush tips.
     */
    fun exportBundle(
        context: Context,
        bundleName: String,
        groupName: String,
        presets: List<Pair<String, File>>,
        tipAssets: List<File>,
    ): File {
        val outDir = File(context.cacheDir, "export_bundles").apply { if (!exists()) mkdirs() }
        val bundleFile = File(outDir, "${bundleName.trim().ifEmpty { "bundle" }}.bundle")
        if (bundleFile.exists()) bundleFile.delete()

        ZipOutputStream(FileOutputStream(bundleFile)).use { zos ->
            // 1. First entry MUST be uncompressed 'mimetype' (STORED method)
            val mimetypeBytes = "application/x-krita-bundle".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimetypeBytes) }
            val mEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(mEntry)
            zos.write(mimetypeBytes)
            zos.closeEntry()

            // 2. Preset files in paintoppresets/
            for ((pName, pFile) in presets) {
                if (!pFile.exists()) continue
                val entry = ZipEntry("paintoppresets/$pName.kpp")
                zos.putNextEntry(entry)
                pFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 3. Tip asset image files in brushes/
            val writtenBrushes = mutableSetOf<String>()
            for (tipFile in tipAssets) {
                if (!tipFile.exists() || writtenBrushes.contains(tipFile.name)) continue
                writtenBrushes.add(tipFile.name)
                val entry = ZipEntry("brushes/${tipFile.name}")
                zos.putNextEntry(entry)
                tipFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 4. Tag metadata file: <groupName>.tag
            val tagContent = """[Desktop Entry]
Type=Tag
Name=$groupName
Name[zh_CN]=$groupName
"""
            val tagEntry = ZipEntry("$groupName.tag")
            zos.putNextEntry(tagEntry)
            zos.write(tagContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 5. META-INF/manifest.xml
            val manifestSb = StringBuilder()
            manifestSb.append("""<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
""")
            for ((pName, _) in presets) {
                manifestSb.append("""  <manifest:file-entry manifest:media-type="" manifest:full-path="paintoppresets/$pName.kpp">
    <manifest:tag>$groupName</manifest:tag>
  </manifest:file-entry>
""")
            }
            for (brushName in writtenBrushes) {
                manifestSb.append("""  <manifest:file-entry manifest:media-type="" manifest:full-path="brushes/$brushName"/>
""")
            }
            manifestSb.append("""  <manifest:file-entry manifest:media-type="" manifest:full-path="$groupName.tag"/>
</manifest:manifest>
""")
            val manifestEntry = ZipEntry("META-INF/manifest.xml")
            zos.putNextEntry(manifestEntry)
            zos.write(manifestSb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        return bundleFile
    }

    /**
     * Share a bundle or kpp file via Android ACTION_SEND sheet
     */
    fun shareFile(context: Context, file: File, mimeType: String, title: String): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("KritaBundleManager", "shareFile failed", e)
            false
        }
    }
}
