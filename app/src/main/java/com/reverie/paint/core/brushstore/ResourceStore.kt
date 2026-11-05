/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 内容寻址资源库（v3 T1，W-02）。
 *
 * 以 (类型, md5(hex)) 为主键管理笔尖（brushes/）和纹理（patterns/）资源文件：
 * - 文件一旦写入永不改写；同类型同 md5 复用已有 Ref；
 * - 同字节分别作为笔尖和纹理登记时各自落盘到自己的目录，互不复用；
 * - 同名不同内容时生成不冲突的新文件名（例如 name_<md5前8位>.ext），旧文件字节保持不变；
 * - 目录里已有但不在索引里的同名文件也算占用，不能覆盖；
 * - jpg/jpeg 输入转为 png 落盘，md5 按落盘的 png 字节计算；
 * - 索引持久化在 `brush_store/resources.json`，写盘使用 [AtomicFiles] 保证原子性。
 */
class ResourceStore(
    val storeRoot: File,
    val brushesDir: File = ResourceDirs.tips(storeRoot.parentFile ?: storeRoot),
    val patternsDir: File = ResourceDirs.patterns(storeRoot.parentFile ?: storeRoot),
    val pngTranscoder: (ByteArray) -> ByteArray = DEFAULT_PNG_TRANSCODER,
) {
    enum class Kind(val dirName: String) {
        TIP("brushes"),
        PATTERN("patterns");
    }

    data class Ref(
        val md5: String,
        val file: String,
        val kind: Kind = Kind.TIP,
    )

    private val lock = Any()
    val indexFile: File = File(storeRoot, RESOURCES_FILE_NAME)

    private val byKindAndMd5 = LinkedHashMap<Pair<Kind, String>, Ref>()
    private val byKindAndFileName = HashMap<Pair<Kind, String>, Ref>()

    init {
        storeRoot.mkdirs()
        brushesDir.mkdirs()
        patternsDir.mkdirs()
        synchronized(lock) {
            loadLocked()
        }
    }

    fun dirFor(kind: Kind): File = when (kind) {
        Kind.TIP -> brushesDir
        Kind.PATTERN -> patternsDir
    }

    fun fileFor(ref: Ref): File = File(dirFor(ref.kind), ref.file)

    fun fileFor(md5: String, kind: Kind): File? = resolve(md5, kind)?.let { fileFor(it) }

    fun resolve(md5: String, kind: Kind): Ref? = synchronized(lock) {
        byKindAndMd5[kind to md5.lowercase()]
    }


    fun all(): List<Ref> = synchronized(lock) {
        byKindAndMd5.values.toList()
    }

    /**
     * 注册资源字节：
     * - 若输入为 jpg/jpeg，通过 [pngTranscoder] 转为 png，扩展名改为 .png；
     * - 若同类型下相同 md5 已存在，直接复用已有 Ref，不重复写盘；
     * - 若已有文件的磁盘字节被外部改写（重新哈希不等于 md5），不覆盖该文件，另起名写入新文件；
     * - 若同名已占用（索引内或磁盘已有），生成 `name_<md5前8位>.ext` 等防重名文件名；
     * - 写入实际文件并更新 `brush_store/resources.json`。
     */
    fun register(bytes: ByteArray, kind: Kind, preferredName: String): Ref {
        val isJp = isJpeg(bytes, preferredName)
        val (finalBytes, effectivePreferredName) = if (isJp) {
            val transcoded = pngTranscoder(bytes)
            val base = if (preferredName.contains('.')) preferredName.substringBeforeLast('.') else preferredName
            transcoded to "$base.png"
        } else {
            bytes to preferredName
        }

        val md5 = BrushDiagnostics.md5(finalBytes).lowercase()
        val targetDir = dirFor(kind)

        synchronized(lock) {
            val existing = byKindAndMd5[kind to md5]
            if (existing != null) {
                val existingFile = fileFor(existing)
                if (!existingFile.exists()) {
                    AtomicFiles.write(existingFile, finalBytes)
                    return existing
                }
                if (BrushDiagnostics.md5(existingFile.readBytes()).lowercase() == md5) {
                    return existing
                }
                // 磁盘上的文件已被外部改写、字节不再匹配 md5：不覆盖它，按新文件另起名落盘
            }

            val cleanPreferred = File(effectivePreferredName).name.ifBlank { "resource" }
            val base = if (cleanPreferred.contains('.')) cleanPreferred.substringBeforeLast('.') else cleanPreferred
            val ext = if (cleanPreferred.contains('.')) "." + cleanPreferred.substringAfterLast('.') else ""

            val existingOnDisk = File(targetDir, cleanPreferred)
            val matchesDisk = existingOnDisk.exists() &&
                BrushDiagnostics.md5(existingOnDisk.readBytes()).lowercase() == md5

            val chosenName: String = if (matchesDisk || !isTakenLocked(kind, targetDir, cleanPreferred)) {
                cleanPreferred
            } else {
                val md5Prefix = md5.take(8)
                val firstChoice = "${base}_${md5Prefix}$ext"
                if (!isTakenLocked(kind, targetDir, firstChoice)) {
                    firstChoice
                } else {
                    var n = 2
                    while (isTakenLocked(kind, targetDir, "${base}_${md5Prefix}_$n$ext")) {
                        n++
                    }
                    "${base}_${md5Prefix}_$n$ext"
                }
            }

            val targetFile = File(targetDir, chosenName)
            if (!targetFile.exists()) {
                AtomicFiles.write(targetFile, finalBytes)
            }

            val ref = Ref(md5 = md5, file = chosenName, kind = kind)
            byKindAndMd5[kind to md5] = ref
            byKindAndFileName[kind to chosenName] = ref

            persistLocked()
            return ref
        }
    }

    private fun isTakenLocked(kind: Kind, targetDir: File, name: String): Boolean {
        return File(targetDir, name).exists() || byKindAndFileName.containsKey(kind to name)
    }

    private fun loadLocked() {
        byKindAndMd5.clear()
        byKindAndFileName.clear()
        if (!indexFile.exists()) return
        try {
            val rootObj = JSONObject(indexFile.readText())
            val entries = rootObj.optJSONArray("resources")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entryObj = entries.getJSONObject(i)
                    putLoadedLocked(entryObj.getString("md5"), entryObj)
                }
            } else {
                // schema 1：以 md5 为键的对象（每个 md5 只有一条）
                val legacyObj = rootObj.optJSONObject("resources") ?: return
                for (md5 in legacyObj.keys()) {
                    putLoadedLocked(md5, legacyObj.getJSONObject(md5))
                }
            }
        } catch (t: Throwable) {
            BrushDiagnostics.log("resourcesLoadFailed", "err" to t.javaClass.simpleName)
        }
    }

    private fun putLoadedLocked(md5: String, entryObj: JSONObject) {
        val file = entryObj.getString("file")
        val kindStr = entryObj.optString("kind", Kind.TIP.name)
        val kind = try {
            Kind.valueOf(kindStr)
        } catch (_: Throwable) {
            Kind.TIP
        }
        val ref = Ref(md5 = md5.lowercase(), file = file, kind = kind)
        byKindAndMd5[kind to ref.md5] = ref
        byKindAndFileName[kind to file] = ref
    }

    private fun persistLocked() {
        val entries = JSONArray()
        for (ref in byKindAndMd5.values) {
            entries.put(
                JSONObject()
                    .put("md5", ref.md5)
                    .put("file", ref.file)
                    .put("kind", ref.kind.name),
            )
        }
        val rootObj = JSONObject()
            .put("schema", RESOURCES_SCHEMA)
            .put("resources", entries)
        AtomicFiles.writeText(indexFile, rootObj.toString(1))
    }
}

private const val RESOURCES_FILE_NAME = "resources.json"
private const val RESOURCES_SCHEMA = 2

/** 默认 jpg -> png 转码器（Android Bitmap）；JVM 单测通过构造参数注入替代实现。 */
internal val DEFAULT_PNG_TRANSCODER: (ByteArray) -> ByteArray = { bytes ->
    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("failed to decode image bytes as bitmap")
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
}

private fun isJpeg(bytes: ByteArray, preferredName: String): Boolean {
    if (preferredName.endsWith(".jpg", ignoreCase = true) ||
        preferredName.endsWith(".jpeg", ignoreCase = true)
    ) {
        return true
    }
    return bytes.size >= 3 &&
        (bytes[0].toInt() and 0xFF == 0xFF) &&
        (bytes[1].toInt() and 0xFF == 0xD8) &&
        (bytes[2].toInt() and 0xFF == 0xFF)
}

typealias Ref = ResourceStore.Ref

/**
 * 笔刷资源在 filesDir 下的唯一位置：全工程拼 paintoppresets/brushes/patterns 只许走这里
 * （SourceInvariantsTest.gap7）。无状态纯函数，不是进程级单例。
 */
object ResourceDirs {
    const val PRESETS_DIR_NAME = "paintoppresets"
    fun presets(filesDir: File) = File(filesDir, PRESETS_DIR_NAME)
    fun tips(filesDir: File) = File(filesDir, ResourceStore.Kind.TIP.dirName)
    fun patterns(filesDir: File) = File(filesDir, ResourceStore.Kind.PATTERN.dirName)
}
