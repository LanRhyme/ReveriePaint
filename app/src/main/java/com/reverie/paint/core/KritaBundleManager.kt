package com.reverie.paint.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object KritaBundleManager {

    /**
     * Builds and exports a standard Krita .bundle file containing the specified presets and brush tips.
     */
    fun exportBundle(
        context: Context? = null,
        bundleName: String,
        groupName: String,
        presets: List<Pair<String, File>>,
        tipAssets: List<File>,
        patternAssets: List<File> = emptyList(),
        customOutDir: File? = null,
    ): File {
        val outDir = (customOutDir ?: File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp"), "export_bundles")).apply {
            if (!exists()) mkdirs()
        }
        val safeBundleName = bundleName.trim().ifEmpty { "bundle" }
        val bundleFile = File(outDir, "$safeBundleName.bundle")
        if (bundleFile.exists()) bundleFile.delete()

        val validPresets = presets.filter { it.second.exists() }
        val presetMd5Map = mutableMapOf<String, String>()
        val presetFiles = validPresets.associate { it.first to it.second.name }
        for ((pName, pFile) in validPresets) {
            presetMd5Map[pName] = calculateMd5(pFile)
        }

        val writtenBrushes = mutableListOf<Pair<String, String>>() // (fileName, md5)
        val writtenBrushNames = mutableSetOf<String>()
        for (tipFile in tipAssets) {
            if (!tipFile.exists() || writtenBrushNames.contains(tipFile.name)) continue
            writtenBrushNames.add(tipFile.name)
            writtenBrushes.add(tipFile.name to calculateMd5(tipFile))
        }

        val writtenPatterns = mutableListOf<Pair<String, String>>() // (fileName, md5)
        for (patFile in patternAssets) {
            if (!patFile.exists() || writtenPatterns.any { it.first == patFile.name }) continue
            writtenPatterns.add(patFile.name to calculateMd5(patFile))
        }

        val escapedBundleName = escapeXml(safeBundleName)
        val escapedGroupName = escapeXml(groupName.trim().ifEmpty { "General" })
        val dateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())

        ZipOutputStream(FileOutputStream(bundleFile)).use { zos ->
            // 1. First entry MUST be uncompressed 'mimetype' (STORED method) with standard resourcebundle type
            val mimetypeBytes = "application/x-krita-resourcebundle".toByteArray(Charsets.US_ASCII)
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
            for ((pName, pFile) in validPresets) {
                val entry = ZipEntry("paintoppresets/${pFile.name}")
                zos.putNextEntry(entry)
                pFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 3. Tip asset image files in brushes/
            for (tipFile in tipAssets) {
                if (!tipFile.exists()) continue
                val entry = ZipEntry("brushes/${tipFile.name}")
                try {
                    zos.putNextEntry(entry)
                    tipFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } catch (_: java.util.zip.ZipException) {
                    // Avoid duplicate entry if multiple presets reference same tip
                }
            }

            // 3b. Pattern (texture) files in patterns/
            for ((patName, _) in writtenPatterns) {
                val patFile = patternAssets.first { it.name == patName }
                zos.putNextEntry(ZipEntry("patterns/$patName"))
                patFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 4. Bundle preview image (preview.png at zip root)
            // A .kpp preset file is itself a valid PNG image containing the preset thumbnail（.myb 不是 PNG，不取）
            val firstPresetFile = validPresets.firstOrNull { it.second.extension.equals("kpp", true) }?.second
            if (firstPresetFile != null && firstPresetFile.exists()) {
                val previewEntry = ZipEntry("preview.png")
                zos.putNextEntry(previewEntry)
                firstPresetFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 5. meta.xml (Standard Krita / OpenDocument bundle metadata required by KoResourceBundle)
            val metaXml = """<?xml version="1.0" encoding="UTF-8"?>
<meta:meta xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
 <meta:generator>ReveriePaint</meta:generator>
 <meta:bundle-version>1</meta:bundle-version>
 <dc:author>ReveriePaint</dc:author>
 <dc:title>$escapedBundleName</dc:title>
 <dc:description></dc:description>
 <meta:initial-creator>ReveriePaint</meta:initial-creator>
 <dc:creator>ReveriePaint</dc:creator>
 <meta:creation-date>$dateIso</meta:creation-date>
 <meta:dc-date>$dateIso</meta:dc-date>
 <meta:meta-userdefined meta:name="tag" meta:value="$escapedGroupName"/>
</meta:meta>
"""
            val metaEntry = ZipEntry("meta.xml")
            zos.putNextEntry(metaEntry)
            zos.write(metaXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 6. META-INF/manifest.xml (Standard OASIS manifest required by KoResourceBundleManifest)
            val manifestSb = StringBuilder()
            manifestSb.append("""<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
  <manifest:file-entry manifest:media-type="application/x-krita-resourcebundle" manifest:full-path="/"/>
""")
            for ((pName, _) in validPresets) {
                val md5 = presetMd5Map[pName] ?: ""
                manifestSb.append("""  <manifest:file-entry manifest:media-type="paintoppresets" manifest:full-path="paintoppresets/${presetFiles[pName]}" manifest:md5sum="$md5">
    <manifest:tags>
      <manifest:tag>$escapedGroupName</manifest:tag>
    </manifest:tags>
  </manifest:file-entry>
""")
            }
            for ((brushName, brushMd5) in writtenBrushes) {
                manifestSb.append("""  <manifest:file-entry manifest:media-type="brushes" manifest:full-path="brushes/$brushName" manifest:md5sum="$brushMd5"/>
""")
            }
            for ((patName, patMd5) in writtenPatterns) {
                manifestSb.append("""  <manifest:file-entry manifest:media-type="patterns" manifest:full-path="patterns/$patName" manifest:md5sum="$patMd5"/>
""")
            }
            manifestSb.append("</manifest:manifest>\n")

            val manifestEntry = ZipEntry("META-INF/manifest.xml")
            zos.putNextEntry(manifestEntry)
            zos.write(manifestSb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        return bundleFile
    }

    private fun calculateMd5(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { inStream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (inStream.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun escapeXml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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
