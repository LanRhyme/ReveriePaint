/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 统一导入管线（执行文档 W-08）：.kpp / .myb / .bundle / .zip（含根目录 kpp、嵌套 bundle）。
 *
 * - 每个导入预设分配 `imp:<uuid>`，显示名与 内置 ∪ 已有 ∪ 本批 撞名时改为 "名字 (包名)"、"名字 (包名) 2"…
 *   **绝不覆盖已有文件**（旧实现会直接覆盖同名内置笔刷）。
 * - 快照存原始字节；笔尖/纹理与已有同名但内容不同时改名为 `<包名>__<原名>`，
 *   并以 rawKeys 改写该批预设有效副本里的引用（快照不动，重置保留 rawKeys，见 [BrushEditSession.reset]）；内容相同则复用。
 * - v2 未激活时（开关关闭/启动失败）只按同样的规则落盘文件，不建快照。
 *
 * 纯 JVM 实现（不依赖 Android）；zip 的 manifest 标签解析也在这里（[parseManifestTags]）。
 */
object BrushImportPipeline {
    const val PROVENANCE_IMPORT = "import"
    const val MAX_DEPTH = 2
    const val SINGLE_FILE_PACK = "导入"

    /** [tag]：bundle 的 META-INF/manifest.xml 里该预设的标签（Krita bundle 的分组），无则 null */
    data class Incoming(val wantedName: String, val ext: String, val bytes: ByteArray, val pack: String, val tag: String? = null)

    data class Resource(val kind: String, val name: String, val bytes: ByteArray, val relPath: String = "")

    data class Parsed(
        val presets: List<Incoming>,
        val resources: List<Resource>,
        /** 跳过的条目（嵌套 .abr 等），只记日志 */
        val skipped: List<String>,
    )

    data class Imported(val name: String, val ext: String, val id: String?, val wantedName: String, val pack: String, val tag: String? = null)

    data class Result(val imported: List<Imported>, val resourceRenames: Map<String, String>)

    // ------------------------------------------------------------------ 解析

    /** 预设文件名判断只有这一份：后缀取 [PRESET_EXTS] */
    fun isPreset(n: String) = PRESET_EXTS.any { n.endsWith(".$it", ignoreCase = true) }

    private fun isArchive(n: String) = n.endsWith(".bundle", true) || n.endsWith(".zip", true)

    /**
     * 解析 bundle/zip：`paintoppresets/` 子目录或根目录下的 .kpp/.myb、`brushes/`、`patterns/` 资源；
     * 嵌套 .bundle/.zip 递归（深度 ≤ [MAX_DEPTH]）。`_prev.png` 等其它条目忽略。
     */
    fun parseArchive(bytes: ByteArray, pack: String, depth: Int = 0): Parsed {
        val presets = ArrayList<Incoming>()
        val resources = ArrayList<Resource>()
        val skipped = ArrayList<String>()
        val own = ArrayList<Int>() // 本层预设在 presets 里的下标（manifest 可能排在预设之后）
        var tags: Map<String, String> = emptyMap()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                val path = e.name.replace('\\', '/')
                if (!e.isDirectory && !path.contains("../") && !path.startsWith("__MACOSX/")) {
                    val file = path.substringAfterLast('/')
                    val parent = path.substringBeforeLast('/', "").substringAfterLast('/')
                    val brushIdx = path.indexOf("brushes/", ignoreCase = true)
                    val isBrush = brushIdx == 0 || (brushIdx > 0 && path[brushIdx - 1] == '/')
                    val patIdx = path.indexOf("patterns/", ignoreCase = true)
                    val isPattern = patIdx == 0 || (patIdx > 0 && path[patIdx - 1] == '/')
                    when {
                        file.isBlank() || file.startsWith(".") -> Unit
                        path.equals(MANIFEST_PATH, true) -> tags = parseManifestTags(zin.readBytes())
                        isPreset(file) && (parent.isEmpty() || parent.equals("paintoppresets", true)) -> {
                            own += presets.size
                            val raw = zin.readBytes()
                            val clean = if (file.endsWith(".kpp", true)) KppHelper.stripKpp(raw) else raw
                            presets += Incoming(file.substringBeforeLast('.'), file.substringAfterLast('.').lowercase(), clean, pack)
                        }
                        isBrush -> {
                            val relPath = path.substring(brushIdx + 8)
                            resources += Resource("brushes", file, zin.readBytes(), relPath)
                        }
                        isPattern -> {
                            val relPath = path.substring(patIdx + 9)
                            resources += Resource("patterns", file, zin.readBytes(), relPath)
                        }
                        isArchive(file) && depth < MAX_DEPTH -> {
                            val nested = parseArchive(zin.readBytes(), pack, depth + 1)
                            presets += nested.presets
                            resources += nested.resources
                            skipped += nested.skipped
                        }
                        file.endsWith(".abr", true) || isArchive(file) -> skipped += path
                        else -> Unit
                    }
                }
                e = zin.nextEntry
            }
        }
        for (i in own) presets[i] = presets[i].let { it.copy(tag = tags[it.wantedName]) }
        return Parsed(presets, resources, skipped)
    }

    const val MANIFEST_PATH = "META-INF/manifest.xml"

    /**
     * Krita bundle 的 manifest（KisResourceBundle，OpenDocument manifest 形式）：
     * `file-entry[full-path]` 下的 `tag`。返回 预设名(无扩展名) → 标签；同一条目多个标签取最后一个
     * （与旧的内联解析一致，一个预设在本应用里只属于一个分组）。
     * 解析失败返回空——标签丢了不应让整包导入失败。DOCTYPE 一律拒绝（防 XXE）。
     */
    internal fun parseManifestTags(bytes: ByteArray): Map<String, String> = runCatching {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
            .parse(ByteArrayInputStream(bytes))
        val out = LinkedHashMap<String, String>()
        val entries = doc.getElementsByTagName("*")
        for (i in 0 until entries.length) {
            val el = entries.item(i) as org.w3c.dom.Element
            if (!el.tagName.substringAfterLast(':').equals("file-entry", true)) continue
            val path = el.getAttribute("manifest:full-path").ifEmpty { el.getAttribute("full-path") }
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            if (name.isBlank()) continue
            val tagEls = el.getElementsByTagName("*")
            for (j in 0 until tagEls.length) {
                val t = tagEls.item(j) as org.w3c.dom.Element
                if (t.tagName.substringAfterLast(':').equals("tag", true)) {
                    t.textContent?.trim()?.takeIf { it.isNotBlank() }?.let { out[name] = it }
                }
            }
        }
        out
    }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------ 命名

    /** 内置 ∪ 已注册 ∪ 磁盘上已有的预设名 */
    fun takenNames(presetDir: File): MutableSet<String> {
        val out = HashSet<String>()
        BrushIdentity.all().forEach { out += it.name }
        presetDir.listFiles()?.forEach { f -> if (f.isFile && isPreset(f.name)) out += f.nameWithoutExtension }
        return out
    }

    /** 复制/新建用：撞名时 "名字 2"、"名字 3"… */
    fun uniqueCopyName(base: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (base.lowercase() !in lower) return base
        var n = 2
        while ("$base $n".lowercase() in lower) n++
        return "$base $n"
    }

    private fun safePack(pack: String): String =
        pack.substringBeforeLast('.').replace(Regex("""[^\w\u4e00-\u9fa5-]"""), "_").ifBlank { "import" }

    // ------------------------------------------------------------------ 资源

    /**
     * 写入笔尖/纹理。目标已存在且内容不同 → 改名 `<包名>__<原名>`（仍冲突则加序号）；内容相同 → 复用。
     * 返回 原名 → 新名（只含改了名的）。
     */
    fun installResources(resources: List<Resource>, brushDir: File, patternDir: File, pack: String): Map<String, String> {
        val root = BrushStoreBootstrap.storeRootDir ?: File(brushDir.parentFile ?: brushDir, BrushStoreBootstrap.STORE_DIR)
        val store = BrushStoreBootstrap.resourceStore ?: ResourceStore(root, brushDir, patternDir)
        val renames = LinkedHashMap<String, String>()
        for (r in resources) {
            val kind = if (r.kind == "patterns") ResourceStore.Kind.PATTERN else ResourceStore.Kind.TIP
            val preferredName = if (File(store.dirFor(kind), r.name).exists() &&
                !File(store.dirFor(kind), r.name).readBytes().contentEquals(r.bytes)
            ) {
                "${safePack(pack)}__${r.name}"
            } else {
                r.name
            }
            val ref = store.register(r.bytes, kind, preferredName)
            if (ref.file != r.name) {
                renames[r.name] = ref.file
                BrushDiagnostics.log("import_resource_renamed", "kind" to r.kind, "from" to r.name, "to" to ref.file)
            } else {
                BrushDiagnostics.log("import_resource_reuse", "kind" to r.kind, "name" to r.name)
            }
            if (r.relPath.isNotEmpty() && r.relPath != r.name) {
                renames[r.relPath] = ref.file
                BrushDiagnostics.log("import_resource_rel_mapped", "kind" to r.kind, "rel" to r.relPath, "to" to ref.file)
            }
        }
        return renames
    }

    /** 该预设引用了被改名资源时，返回改写引用所需的 rawKeys（不引用则为空） */
    fun rewriteRefs(xml: String, renames: Map<String, String>): Map<String, String> {
        if (renames.isEmpty()) return emptyMap()
        val raw = LinkedHashMap<String, String>()
        val tip = KppXml.brushAttr(xml, "filename")
        val newTip = tip?.let { renames[it] }
        if (newTip != null) {
            KppXml.mainBrushDef(KppXml.setBrushAttr(xml, "filename", newTip))?.let { raw[KppXml.BRUSH_DEF] = it }
            if (KppXml.get(xml, "requiredBrushFile") == tip) raw["requiredBrushFile"] = newTip
            KppXml.get(xml, "requiredBrushFilesList")?.let { l ->
                val parts = l.split(',', ';').map { it.trim() }
                if (tip in parts) raw["requiredBrushFilesList"] = l.replace(tip, newTip)
            }
        }
        val maskDef = KppXml.get(xml, KppXml.MASK_BRUSH_DEF)
        if (maskDef != null) {
            val maskTip = KppXml.tagAttr(maskDef, "Brush", "filename")
            val newMaskTip = maskTip?.let { renames[it] }
            if (newMaskTip != null) {
                raw[KppXml.MASK_BRUSH_DEF] = KppXml.setTagAttr(maskDef, "Brush", "filename", newMaskTip)
            }
        }
        val patFile = KppXml.get(xml, "Texture/Pattern/PatternFileName")
        val patName = KppXml.get(xml, "Texture/Pattern/Name")
        // 真实 kpp 的 PatternFileName 常是作者机器上的绝对路径（/home/…/patterns/x.png），按文件名匹配
        val newPat = (patFile?.substringAfterLast('/')?.substringAfterLast('\\')?.let { renames[it] })
            ?: (patFile?.let { renames[it] })
            ?: (patName?.let { renames[it] })
        if (newPat != null) {
            val refKeys = PresetPropertyMap.patternRefKeys(ResourceStore.Ref(md5 = "", file = newPat, kind = ResourceStore.Kind.PATTERN))
            for ((k, v) in refKeys) {
                if (KppXml.get(xml, k) != null) raw[k] = v
            }
        }
        return raw
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 落盘一批预设：每个预设（.kpp / .myb）都建快照 + identity，kpp 需要时带 rawKeys 覆盖，最后 materialize。
     */
    fun commit(presets: List<Incoming>, renames: Map<String, String>, presetDir: File): List<Imported> {
        if (BrushStoreBootstrap.readOnly) return emptyList()
        presetDir.mkdirs()
        val taken = takenNames(presetDir)
        val out = ArrayList<Imported>()
        for (p in presets) {
            val name = BrushIdentity.uniqueDisplayName(p.wantedName, p.pack, taken)
            taken += name
            val isKpp = p.ext.equals("kpp", true)
            val xml = if (isKpp) KppHelper.readPresetXml(p.bytes, stripEmbeddedResources = true) else null
            if (!isReadablePreset(p.ext, p.bytes)) {
                BrushDiagnostics.log("import_skipped", "name" to p.wantedName, "reason" to "unreadable")
                continue
            }
            val raw = if (xml != null) rewriteRefs(xml, renames) else emptyMap()
            val id = BrushIdentity.newImportedId()
            PresetSnapshotStore.putImported(
                id, p.ext, p.bytes,
                JSONObject().put("provenance", PROVENANCE_IMPORT).put("pack", p.pack)
                    .put("file", "${p.wantedName}.${p.ext}").put("md5", BrushDiagnostics.md5(p.bytes)),
            )
            BrushIdentity.register(BrushIdentity.Entry(id, name, p.ext, BrushIdentity.Kind.IMPORTED, p.pack))
            if (raw.isNotEmpty()) BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(rawKeys = raw))
            EffectivePresetBuilder.materialize(id, presetDir)
            out += Imported(name, p.ext, id, p.wantedName, p.pack, p.tag)
            BrushDiagnostics.log(
                "import", "name" to name, "wanted" to p.wantedName, "pack" to p.pack, "id" to id,
                "renamed" to (name != p.wantedName), "rawKeys" to raw.size,
            )
        }
        return out
    }

    /** 单文件 .kpp/.myb */
    fun importFile(fileName: String, bytes: ByteArray, presetDir: File): Result {
        // 单文件没有"包"：撞名后缀用 "(导入)"；原文件名记在 source.json 的 file 里
        val inc = Incoming(fileName.substringBeforeLast('.'), fileName.substringAfterLast('.').lowercase(), bytes, SINGLE_FILE_PACK)
        return Result(commit(listOf(inc), emptyMap(), presetDir), emptyMap())
    }

    /** .bundle/.zip */
    fun importArchive(
        archiveFile: File,
        fileName: String = archiveFile.name,
        presetDir: File,
        brushDir: File,
        patternDir: File,
        depth: Int = 0,
    ): Result {
        val root = BrushStoreBootstrap.storeRootDir ?: File(brushDir.parentFile ?: brushDir, BrushStoreBootstrap.STORE_DIR)
        val store = BrushStoreBootstrap.resourceStore ?: ResourceStore(root, brushDir, patternDir)
        val renames = LinkedHashMap<String, String>()
        val imported = ArrayList<Imported>()
        val skipped = ArrayList<String>()

        var tags: Map<String, String> = emptyMap()
        val nestedFiles = ArrayList<Pair<String, File>>()

        try {
            java.util.zip.ZipFile(archiveFile).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_PATH)
                    ?: zip.entries().asSequence().firstOrNull { it.name.replace('\\', '/').equals(MANIFEST_PATH, true) }
                if (manifestEntry != null) {
                    tags = zip.getInputStream(manifestEntry).use { parseManifestTags(it.readBytes()) }
                }

                val entries1 = zip.entries()
                while (entries1.hasMoreElements()) {
                    val e = entries1.nextElement()
                    val path = e.name.replace('\\', '/')
                    if (e.isDirectory || path.contains("../") || path.startsWith("__MACOSX/")) continue
                    val file = path.substringAfterLast('/')
                    if (file.isBlank() || file.startsWith(".")) continue

                    val brushIdx = path.indexOf("brushes/", ignoreCase = true)
                    val isBrush = brushIdx == 0 || (brushIdx > 0 && path[brushIdx - 1] == '/')
                    val patIdx = path.indexOf("patterns/", ignoreCase = true)
                    val isPattern = patIdx == 0 || (patIdx > 0 && path[patIdx - 1] == '/')

                    when {
                        isBrush || isPattern -> {
                            val kind = if (isPattern) ResourceStore.Kind.PATTERN else ResourceStore.Kind.TIP
                            val prefixLen = if (isPattern) patIdx + 9 else brushIdx + 8
                            val relPath = path.substring(prefixLen)
                            val rBytes = zip.getInputStream(e).use { it.readBytes() }
                            val existingFile = File(store.dirFor(kind), file)
                            val preferredName = if (existingFile.exists() && (existingFile.length() != rBytes.size.toLong() || !existingFile.readBytes().contentEquals(rBytes))) {
                                "${safePack(fileName)}__$file"
                            } else {
                                file
                            }
                            val ref = store.register(rBytes, kind, preferredName)
                            if (ref.file != file) {
                                renames[file] = ref.file
                                BrushDiagnostics.log("import_resource_renamed", "kind" to kind.name.lowercase(), "from" to file, "to" to ref.file)
                            } else {
                                BrushDiagnostics.log("import_resource_reuse", "kind" to kind.name.lowercase(), "name" to file)
                            }
                            if (relPath.isNotEmpty() && relPath != file) {
                                renames[relPath] = ref.file
                                BrushDiagnostics.log("import_resource_rel_mapped", "kind" to kind.name.lowercase(), "rel" to relPath, "to" to ref.file)
                            }
                        }
                        isArchive(file) && depth < MAX_DEPTH -> {
                            val tempNested = File.createTempFile("nested_bundle_", ".tmp")
                            zip.getInputStream(e).use { input ->
                                tempNested.outputStream().buffered().use { out ->
                                    input.copyTo(out, bufferSize = 64 * 1024)
                                }
                            }
                            nestedFiles += file to tempNested
                        }
                        file.endsWith(".abr", true) || isArchive(file) -> skipped += path
                        else -> Unit
                    }
                }

                for ((nestedName, tempFile) in nestedFiles) {
                    try {
                        val nestedResult = importArchive(tempFile, nestedName, presetDir, brushDir, patternDir, depth + 1)
                        renames.putAll(nestedResult.resourceRenames)
                        imported.addAll(nestedResult.imported)
                    } finally {
                        tempFile.delete()
                    }
                }
                nestedFiles.clear()

                if (!BrushStoreBootstrap.readOnly) {
                    presetDir.mkdirs()
                    val taken = takenNames(presetDir)
                    val entries2 = zip.entries()
                    while (entries2.hasMoreElements()) {
                        val e = entries2.nextElement()
                        val path = e.name.replace('\\', '/')
                        if (e.isDirectory || path.contains("../") || path.startsWith("__MACOSX/")) continue
                        val file = path.substringAfterLast('/')
                        val parent = path.substringBeforeLast('/', "").substringAfterLast('/')
                        if (file.isBlank() || file.startsWith(".")) continue

                        if (isPreset(file) && (parent.isEmpty() || parent.equals("paintoppresets", true))) {
                            val wantedName = file.substringBeforeLast('.')
                            val ext = file.substringAfterLast('.').lowercase()
                            val rawBytes = zip.getInputStream(e).use { it.readBytes() }
                            val cleanBytes = if (ext == "kpp") KppHelper.stripKpp(rawBytes) else rawBytes
                            val tag = tags[wantedName]

                            val name = BrushIdentity.uniqueDisplayName(wantedName, fileName, taken)
                            taken += name
                            val isKpp = ext == "kpp"
                            val xml = if (isKpp) KppHelper.readPresetXml(cleanBytes, stripEmbeddedResources = true) else null
                            if (!isReadablePreset(ext, cleanBytes)) {
                                BrushDiagnostics.log("import_skipped", "name" to wantedName, "reason" to "unreadable")
                                continue
                            }
                            val raw = if (xml != null) rewriteRefs(xml, renames) else emptyMap()
                            val id = BrushIdentity.newImportedId()
                            PresetSnapshotStore.putImported(
                                id, ext, cleanBytes,
                                JSONObject().put("provenance", PROVENANCE_IMPORT).put("pack", fileName)
                                    .put("file", "$wantedName.$ext").put("md5", BrushDiagnostics.md5(cleanBytes)),
                            )
                            BrushIdentity.register(BrushIdentity.Entry(id, name, ext, BrushIdentity.Kind.IMPORTED, fileName))
                            if (raw.isNotEmpty()) BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(rawKeys = raw))
                            EffectivePresetBuilder.materialize(id, presetDir)
                            imported += Imported(name, ext, id, wantedName, fileName, tag)
                            BrushDiagnostics.log(
                                "import", "name" to name, "wanted" to wantedName, "pack" to fileName, "id" to id,
                                "renamed" to (name != wantedName), "rawKeys" to raw.size,
                            )
                        }
                    }
                }
            }
        } finally {
            for ((_, f) in nestedFiles) {
                f.delete()
            }
        }

        skipped.forEach { BrushDiagnostics.log("import_entry_skipped", "pack" to fileName, "entry" to it) }
        return Result(imported, renames)
    }

    fun importArchive(fileName: String, bytes: ByteArray, presetDir: File, brushDir: File, patternDir: File): Result {
        val temp = File.createTempFile("reverie_import_", ".tmp")
        return try {
            temp.writeBytes(bytes)
            importArchive(temp, fileName, presetDir, brushDir, patternDir)
        } finally {
            temp.delete()
        }
    }

    const val PROVENANCE_ABR = "abr"

    /**
     * ABR 转换结果入库（W-09）：快照 = 转换后的 kpp 字节；source.json 记 abrParams（AbrParser 原始结构）与
     * 转换器版本。覆盖为空，所以重置后字节 == 导入时。[name] 由调用方保证唯一。
     */
    fun importAbr(
        name: String,
        kppBytes: ByteArray,
        abrParams: JSONObject,
        converterVersion: Int,
        pack: String,
        presetDir: File,
        appBaseline: JSONObject = JSONObject(),
    ): String {
        val id = BrushIdentity.newImportedId()
        val source = JSONObject().put("provenance", PROVENANCE_ABR).put("pack", pack).put("file", "$name.kpp")
            .put("md5", BrushDiagnostics.md5(kppBytes))
            .put("abrConverterVersion", converterVersion).put("abrParams", abrParams)
            .put("appBaseline", appBaseline)
        PresetSnapshotStore.putImported(id, "kpp", kppBytes, source)
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, pack))
        EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("import_abr", "name" to name, "id" to id, "pack" to pack, "converter" to converterVersion)
        return id
    }

    /**
     * App 自己落盘的新 kpp（新建 / ABR 转换 / 图片笔尖）纳入 v2：当前文件即快照（provenance=[provenance]）。
     * 已有 identity 或非 kpp 时不做事；返回 id。
     */
    fun adoptFile(name: String, presetDir: File, provenance: String, pack: String = "", ext: String? = null): String? {
        val known = BrushIdentity.entryForName(name)
        // 已登记且有快照：什么都不做。已登记的导入笔却没有快照（跨设备备份恢复只带了 identity 与预设文件）：
        // 沿用原 id 补一份快照，常用/分组才继续指着这支笔，且它之后可编辑、可重置
        if (known != null && (known.kind != BrushIdentity.Kind.IMPORTED || PresetSnapshotStore.has(known.id))) return known.id
        val f = if (ext != null) File(presetDir, "$name.$ext")
        else PRESET_EXTS.map { File(presetDir, "$name.$it") }.firstOrNull { it.isFile } ?: return null
        if (!f.isFile) return known?.id
        val fext = f.extension.lowercase()
        val bytes = f.readBytes()
        if (!isReadablePreset(fext, bytes)) return known?.id
        val id = known?.id ?: BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(
            id, fext, bytes,
            JSONObject().put("provenance", provenance).put("pack", pack).put("file", f.name).put("md5", BrushDiagnostics.md5(bytes)),
        )
        BrushIdentity.register(BrushIdentity.Entry(id, name, fext, BrushIdentity.Kind.IMPORTED, known?.pack?.ifEmpty { pack } ?: pack))
        BrushDiagnostics.log("adopt", "name" to name, "id" to id, "provenance" to provenance, "ext" to fext)
        return id
    }

    /** 库里管理的预设扩展名：kpp（Krita）与 myb（MyPaint，引擎内转换） */
    val PRESET_EXTS = listOf("kpp", "myb")

    /** kpp 要能读出 preset XML；myb 要是 JSON 对象；其他扩展名不收 */
    fun isReadablePreset(ext: String, bytes: ByteArray): Boolean = when (ext.lowercase()) {
        "kpp" -> KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) != null
        "myb" -> runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.isSuccess
        else -> false
    }

    const val DEFAULT_BUILTIN_BASE_NAME = "b)_Basic-5_Size_Opacity"

    /**
     * 新建笔刷的基底：给定 id 是有快照的 kpp 就用它（.myb 不能当 kpp 基底），否则退回默认内置基底；都没有返回 null。
     * 只认 id——磁盘文件 → id 的对应在启动 reconcile（[BrushStoreBootstrap]）里做一次，不在这里按名字再查。
     */
    fun resolveBaseId(baseId: String?): String? {
        if (!baseId.isNullOrEmpty() && BrushIdentity.entry(baseId)?.ext.equals("kpp", ignoreCase = true) && PresetSnapshotStore.has(baseId)) return baseId
        val fallback = BrushIdentity.builtinId(DEFAULT_BUILTIN_BASE_NAME)
        if (PresetSnapshotStore.has(fallback)) {
            BrushDiagnostics.log("base_fallback", "wanted" to (baseId ?: ""), "used" to fallback)
            return fallback
        }
        return null
    }

    /** 复制：新快照 = 源快照，覆盖 = 源覆盖的拷贝；返回新 id */
    fun duplicate(srcId: String, newName: String, presetDir: File, author: String? = null): String? {
        if (BrushStoreBootstrap.readOnly) return null
        val src = BrushIdentity.entry(srcId) ?: return null
        val snap = PresetSnapshotStore.read(srcId) ?: return null
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(
            id, src.ext, snap,
            JSONObject().put("provenance", "duplicate").put("from", srcId).put("pack", src.pack).put("md5", BrushDiagnostics.md5(snap))
                .also { j ->
                    PresetSnapshotStore.source(srcId)?.optJSONObject("appBaseline")?.let { j.put("appBaseline", it) }
                },
        )
        BrushIdentity.register(BrushIdentity.Entry(id, newName, src.ext, BrushIdentity.Kind.IMPORTED, src.pack))
        BrushOverrideStore.copy(srcId, id)
        if (author != null) {
            val cur = BrushOverrideStore.get(id)
            val updated = cur.controls + mapOf(
                ControlId.AUTHOR.key to author,
                ControlId.AUTHOR_LOCKED.key to false,
            )
            BrushOverrideStore.replace(id, cur.copy(controls = updated))
        }
        BrushThumbStore.copy(srcId, id)
        EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("duplicate", "from" to srcId, "id" to id, "name" to newName)
        return id
    }

    /**
     * 新建预设（从基准预设派生）：
     * 1. 读 baseId 的 snapshot 与 entry，ext 不是 kpp 时返回 null。
     * 2. 新 snapshot 字节：若指定 tipAsset 或 paintOpId，改写进 snapshot（本体属性，reset 不清）；
     * 3. 注册新 imported identity 与快照；
     * 4. 设置 AUTHOR / AUTHOR_LOCKED 元数据 controls；
     * 5. materialize 并返回新 id。
     */
    fun create(
        baseId: String,
        newName: String,
        presetDir: File,
        tipAsset: String = "",
        paintOpId: String = "",
        author: String = "原创创作者",
        authorLocked: Boolean = false,
        /** 笔尖文件自带间距（gbr/gih 头）；null = 沿用基底预设 */
        spacing: Double? = null,
    ): String? {
        if (BrushStoreBootstrap.readOnly) return null
        val src = BrushIdentity.entry(baseId) ?: return null
        if (!src.ext.equals("kpp", ignoreCase = true)) return null
        val snap = PresetSnapshotStore.read(baseId) ?: return null

        var bytes = snap
        val controls = mutableMapOf<ControlId, Any>()
        if (spacing != null) controls[ControlId.SPACING] = spacing
        if (tipAsset.isNotEmpty()) {
            controls[ControlId.TIP_ASSET] = tipAsset
        }
        if (paintOpId.isNotEmpty() && paintOpId != "defaultpaintop") {
            controls[ControlId.PAINTOP_ID] = paintOpId
        }
        if (controls.isNotEmpty()) {
            val xml = KppHelper.readPresetXml(snap, stripEmbeddedResources = false) ?: return null
            val strippedXml = KppHelper.readPresetXml(snap, stripEmbeddedResources = true) ?: xml
            val base = PresetPropertyMap.readParams(strippedXml)
            val eff = PresetPropertyMap.applyToParams(base, controls)
            val x = PresetPropertyMap.applyToXml(xml, controls, eff)
            bytes = if (x == xml) snap else KppHelper.replacePresetXml(snap, x)
        }

        val id = BrushIdentity.newImportedId()
        val md5 = BrushDiagnostics.md5(bytes)
        val sourceJson = JSONObject()
            .put("provenance", "create")
            .put("from", baseId)
            .put("pack", src.pack)
            .put("md5", md5)
            .also { j ->
                PresetSnapshotStore.source(baseId)?.optJSONObject("appBaseline")?.let { j.put("appBaseline", it) }
            }
        PresetSnapshotStore.putImported(id, "kpp", bytes, sourceJson)
        BrushIdentity.register(BrushIdentity.Entry(id, newName, "kpp", BrushIdentity.Kind.IMPORTED, src.pack))
        BrushOverrideStore.replace(
            id,
            BrushOverrideStore.Overrides(
                controls = mapOf(
                    ControlId.AUTHOR.key to author,
                    ControlId.AUTHOR_LOCKED.key to authorLocked,
                ),
            ),
        )
        EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("create", "from" to baseId, "id" to id, "name" to newName)
        return id
    }

    /**
     * 删除一个导入/复制的预设：磁盘上的有效文件（kpp 或 myb）与 `_prev.png` 旧缩略图、identity、快照、覆盖、缩略图一起删。
     * 返回被删预设的 id；没有 identity 的 id、内置预设都返回 null 且什么也不删（启动 reconcile 保证磁盘上的预设都有 identity）。
     */
    fun delete(id: String, presetDir: File): String? {
        val e = BrushIdentity.entry(id) ?: return null
        if (e.kind == BrushIdentity.Kind.BUILTIN) return null
        PRESET_EXTS.forEach { File(presetDir, "${e.name}.$it").delete() }
        File(presetDir, "${e.name}_prev.png").delete()
        forget(id)
        return id
    }

    /**
     * 重命名：只改 identity 的显示名，有效文件按新名重写后删旧文件。新名撞已有预设（任意扩展名）时返回 false。
     */
    fun rename(id: String, newName: String, presetDir: File): Boolean {
        if (BrushStoreBootstrap.readOnly) return false
        val e = BrushIdentity.entry(id) ?: return false
        val oldName = e.name
        if (takenNames(presetDir).any { it.equals(newName, ignoreCase = true) }) return false
        val oldFile = EffectivePresetBuilder.targetFile(presetDir, e)
        if (!BrushIdentity.rename(e.id, newName)) return false
        if (EffectivePresetBuilder.materialize(e.id, presetDir) == null) {
            BrushIdentity.rename(e.id, oldName)
            return false
        }
        oldFile.delete()
        File(presetDir, "${oldName}_prev.png").delete()
        return true
    }

    fun forget(id: String) {
        val e = BrushIdentity.entry(id) ?: return
        if (e.kind != BrushIdentity.Kind.IMPORTED) return
        BrushOverrideStore.clear(e.id)
        BrushThumbStore.delete(e.id)
        BrushIdentity.remove(e.id)
        PresetSnapshotStore.removeImported(e.id)
        BrushDiagnostics.log("forget", "name" to e.name, "id" to e.id)
    }
}
