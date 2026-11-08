/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrBlendModes
import com.reverie.paint.core.AbrPresetConverter
import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import java.io.File

/**
 * 启动编排（执行文档 §2.4 启动流程）：打开存储 → 同步出厂快照 → [迁移] → reconcileDisk → materializeAll。
 * 调用方随后照旧 `loadBrushPresetsFromDir(paintoppresets)`，不改 JNI。
 */
object BrushStoreBootstrap {
    const val STORE_DIR = "brush_store"
    const val RECONCILE_PENDING_FILE = "reconcile.pending"
    private const val STAMP_FILE = "factory_stamp"

    @Volatile
    var readOnly: Boolean = false
        internal set

    @Volatile
    var presetDir: File? = null
        private set

    /** 当前 brush_store 根目录（run 成功后有效；回放缓存等放在其下） */
    @Volatile
    var storeRootDir: File? = null
        private set

    @Volatile
    var resourceStore: ResourceStore? = null
        private set

    fun resourceStore(filesDir: File): ResourceStore =
        resourceStore ?: ResourceStore(storeRoot(filesDir)).also {
            resourceStore = it
            installTipResolver(it)
        }

    fun storeRoot(filesDir: File): File = File(filesDir, STORE_DIR)

    fun openStores(storeRoot: File) {
        storeRoot.mkdirs()
        PresetSnapshotStore.open(storeRoot)
        BrushOverrideStore.open(storeRoot)
        BrushIdentity.open(storeRoot)
        BrushThumbStore.open(storeRoot)
        val filesDir = storeRoot.parentFile ?: storeRoot
        val bDir = ResourceDirs.tips(filesDir)
        val patDir = ResourceDirs.patterns(filesDir)
        val rs = ResourceStore(storeRoot, bDir, patDir)
        resourceStore = rs
        installTipResolver(rs)
    }

    /**
     * 笔尖自包含尺寸（writeTipAsset 写 width/height）：注入笔尖文件字节查找
     * （登记名优先，未登记回退磁盘同名文件；IO 异常按"无字节"处理，writeTipAsset 退回旧行为）。
     */
    private fun installTipResolver(rs: ResourceStore) {
        PresetPropertyMap.tipBytesResolver = { name ->
            try {
                val ref = rs.resolveByName(name, ResourceStore.Kind.TIP)
                val f = if (ref != null) rs.fileFor(ref) else File(rs.dirFor(ResourceStore.Kind.TIP), name)
                f.takeIf { it.isFile }?.readBytes()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 出厂版本号 = 资产清单（文件名 + 各自 md5）的 md5，与 versionCode 无关，资产一变就变 */
    fun factoryVersion(files: Map<String, ByteArray>): String =
        BrushDiagnostics.md5(
            files.keys.sorted().joinToString("\n") { "$it:${BrushDiagnostics.md5(files.getValue(it))}" }.toByteArray(),
        )

    fun isFactoryPreset(fileName: String): Boolean = BrushImportPipeline.isPreset(fileName)

    /**
     * 同步出厂快照并注册内置 identity。[installStamp] 未变且当前版本存在时不读资产（启动快路径）。
     * 返回 (当前版本, 是否换了版本)。
     */
    fun syncFactory(storeRoot: File, installStamp: String, readAssets: () -> Map<String, ByteArray>): Pair<String, Boolean> {
        val stampFile = File(storeRoot, STAMP_FILE)
        val cur = PresetSnapshotStore.currentFactoryVersion()
        if (cur != null && PresetSnapshotStore.factoryDir(cur).isDirectory &&
            stampFile.takeIf { it.exists() }?.readText()?.trim() == installStamp
        ) {
            ensureBuiltinIdentities(PresetSnapshotStore.factoryDir(cur).list()?.toList().orEmpty())
            return cur to false
        }
        val files = readAssets().filterKeys { isFactoryPreset(it) }
        val v = factoryVersion(files)
        val changed = v != cur
        if (changed) {
            PresetSnapshotStore.installFactory(v, files)
            PresetSnapshotStore.setCurrentFactoryVersion(v)
            // W-10：旧→新的变更/删除策略，须在 ensureBuiltinIdentities 删除下线 identity 之前
            if (cur != null && PresetSnapshotStore.factoryDir(cur).isDirectory) {
                runCatching { BuiltinSnapshotMigrator.onFactoryChanged(cur, v) }
                    .onFailure { BrushDiagnostics.log("upgrade_failed", "err" to it.toString()) }
            }
            PresetSnapshotStore.pruneFactory(setOfNotNull(v, cur))
            BrushDiagnostics.log("factory_install", "version" to v, "previous" to cur, "files" to files.size)
        }
        ensureBuiltinIdentities(files.keys)
        AtomicFiles.writeText(stampFile, installStamp)
        return v to changed
    }

    /** 每个出厂文件对应一个 `builtin:<name>`；已不在出厂清单里的内置 identity 删除 */
    fun ensureBuiltinIdentities(fileNames: Collection<String>) {
        val wanted = fileNames.filter { isFactoryPreset(it) }.map { fn ->
            val name = fn.substringBeforeLast('.')
            BrushIdentity.Entry(BrushIdentity.builtinId(name), name, fn.substringAfterLast('.').lowercase(), BrushIdentity.Kind.BUILTIN)
        }
        val missing = wanted.filter { BrushIdentity.entry(it.id) != it }
        BrushIdentity.registerAll(missing)
        val keep = wanted.mapTo(HashSet()) { it.id }
        for (e in BrushIdentity.all()) {
            if (e.kind == BrushIdentity.Kind.BUILTIN && e.id !in keep) {
                BrushIdentity.remove(e.id)
                BrushThumbStore.delete(e.id)
                BrushDiagnostics.log("builtin_removed", "id" to e.id)
            }
        }
    }

    /** 启动：同步出厂 → [migrate]（只在需要时）→ reconcileDisk → [refreshDriftedAbrSnapshots] → materializeAll。 */
    fun run(
        filesDir: File,
        presetDir: File,
        installStamp: String,
        readAssets: () -> Map<String, ByteArray>,
        migrate: () -> Boolean,
    ): Boolean {
        readOnly = false
        return try {
            val t0 = System.nanoTime()
            val root = storeRoot(filesDir)
            openStores(root)
            val first = PresetSnapshotStore.currentFactoryVersion() == null
            val (version, _) = syncFactory(root, installStamp, readAssets)
            if (first) runCatching { BuiltinSnapshotMigrator.logDrift(presetDir, version) }
            if (!migrate()) {
                BrushDiagnostics.log("store_inactive", "reason" to "migration_not_done")
                return false
            }
            reconcileDisk(filesDir)
            refreshDriftedAbrSnapshots()
            val results = EffectivePresetBuilder.materializeAll(presetDir)
            this.presetDir = presetDir
            this.storeRootDir = root
            BrushDiagnostics.log(
                "store_ready",
                "presets" to results.size,
                "written" to results.count { it.written },
                "ms" to (System.nanoTime() - t0) / 1_000_000,
            )
            true
        } catch (t: Throwable) {
            readOnly = true
            BrushDiagnostics.log("store_failed", "err" to t.toString())
            runCatching {
                presetDir.mkdirs()
                for ((name, bytes) in readAssets().filterKeys { isFactoryPreset(it) }) {
                    val f = File(presetDir, name)
                    if (!f.exists()) {
                        f.writeBytes(bytes)
                        f.setReadOnly()
                    }
                }
            }
            false
        }
    }

    /** 兼容测试中带 enabled 参数的旧重载 */
    fun run(
        filesDir: File,
        presetDir: File,
        enabled: Boolean,
        installStamp: String,
        readAssets: () -> Map<String, ByteArray>,
        migrate: () -> Boolean,
    ): Boolean {
        if (!enabled) return false
        return run(filesDir, presetDir, installStamp, readAssets, migrate)
    }

    fun reconcilePendingFile(filesDir: File): File = File(storeRoot(filesDir), RECONCILE_PENDING_FILE)

    fun hasReconcilePending(filesDir: File): Boolean = reconcilePendingFile(filesDir).exists()

    fun beginReconcilePending(filesDir: File) {
        val storeDir = storeRoot(filesDir)
        storeDir.mkdirs()
        val pendingFile = File(storeDir, RECONCILE_PENDING_FILE)
        val pDir = ResourceDirs.presets(filesDir)
        val map = LinkedHashMap<String, String>()
        pDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in BrushImportPipeline.PRESET_EXTS }?.forEach { f ->
            map[f.name] = BrushDiagnostics.md5(f.readBytes()).lowercase()
        }
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        AtomicFiles.writeText(pendingFile, json.toString(1))
    }

    /**
     * 磁盘对账：
     * 1. brushes/ 和 patterns/ 下未登记的文件登记到 ResourceStore；
     * 2. 没有 snapshot 的 kpp / myb 通过 adoptFile 登记；
     * 3. 对被恢复/同步覆盖的文件（与 pending 标记相比 md5 不同的文件），以恢复文件为准换掉 snapshot 并清空 overrides；
     * 4. 移除 pending 标记。
     */
    fun reconcileDisk(filesDir: File? = null) {
        val root = storeRootDir ?: filesDir?.let { storeRoot(it) } ?: return
        if (readOnly) return
        val pDir = presetDir ?: filesDir?.let { ResourceDirs.presets(it) } ?: return
        val bDir = ResourceDirs.tips(pDir.parentFile ?: pDir)
        val patDir = ResourceDirs.patterns(pDir.parentFile ?: pDir)
        val pendingFile = File(root, RECONCILE_PENDING_FILE)
        val hasPending = pendingFile.exists()
        val preMd5Map = if (hasPending) {
            runCatching {
                val obj = JSONObject(pendingFile.readText())
                val m = HashMap<String, String>()
                for (k in obj.keys()) m[k] = obj.getString(k).lowercase()
                m
            }.getOrNull()
        } else null

        val rStore = resourceStore ?: ResourceStore(root, bDir, patDir).also { resourceStore = it }
        bDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            if (rStore.resolveByName(f.name, ResourceStore.Kind.TIP) == null) {
                rStore.register(f.readBytes(), ResourceStore.Kind.TIP, f.name)
            }
        }
        patDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            if (rStore.resolveByName(f.name, ResourceStore.Kind.PATTERN) == null) {
                rStore.register(f.readBytes(), ResourceStore.Kind.PATTERN, f.name)
            }
        }

        pDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in BrushImportPipeline.PRESET_EXTS }?.forEach { f ->
            val name = f.nameWithoutExtension
            val fext = f.extension.lowercase()
            val entry = BrushIdentity.entryForName(name)
            if (entry == null || !PresetSnapshotStore.has(entry.id)) {
                BrushImportPipeline.adoptFile(name, pDir, "reconcile", ext = fext)
            } else if (!entry.ext.equals(fext, ignoreCase = true)) {
                BrushDiagnostics.log("reconcile_ext_mismatch", "name" to name, "file" to f.name, "entry" to entry.ext)
            } else if (hasPending && preMd5Map != null) {
                val currentBytes = f.readBytes()
                val currentMd5 = BrushDiagnostics.md5(currentBytes).lowercase()
                val id = entry.id
                val preMd5 = preMd5Map[f.name]
                if (preMd5 == null || preMd5 != currentMd5) {
                    val expectedBytes = EffectivePresetBuilder.buildFor(id)
                    if (expectedBytes == null || !expectedBytes.contentEquals(currentBytes)) {
                        PresetSnapshotStore.replaceForRestore(id, entry.ext, currentBytes)
                        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides())
                        BrushThumbStore.delete(id)
                        BrushDiagnostics.log("reconcile_restored", "id" to id, "name" to name)
                    }
                }
            }
        }

        if (hasPending) {
            pendingFile.delete()
        }
    }

    /**
     * 转换器版本漂移自愈（Round3-B，修 §10.1-8）：转换规则升级（CONVERTER_VERSION +1）后，旧版本导入的
     * ABR 快照不会自己变——这里在 reconcileDisk 之后、materializeAll 之前扫描 provenance=="abr" 且
     * abrConverterVersion < CONVERTER_VERSION 的导入快照，用 source.json 的 abrParams（旧版缺字段由
     * [AbrPresetConverter.fromAbrParamsJson] 从旧快照 XML 回填）重建快照字节并更新 source.json
     * （md5 / abrConverterVersion / abrParams / appBaseline）。
     *
     * 资源绑定以旧快照 XML 已绑定的为准（见 [AbrPresetConverter.reconvertSnapshot]）；source.json 里的
     * abrBlendPolicy（导入时混合模式策略，Round3-B 起写入）随刷新回放，缺键按 COMPAT_SAFE；用户覆盖
     * （BrushOverrideStore）不动，紧随其后的 materializeAll 按新快照重新叠加覆盖并落盘生效文件。
     * 单条 runCatching：损坏（source.json 非法 / 缺 abrParams / 快照读不出 XML）仅记日志跳过；
     * 本函数绝不抛出、绝不置 readOnly。当前版本与非 ABR 快照只做两次 opt 判断，零 IO 重写。
     */
    fun refreshDriftedAbrSnapshots() {
        if (readOnly) return
        for (e in BrushIdentity.all()) {
            if (e.kind != BrushIdentity.Kind.IMPORTED || !e.ext.equals("kpp", ignoreCase = true)) continue
            val src = PresetSnapshotStore.source(e.id) ?: continue
            if (src.optString("provenance") != BrushImportPipeline.PROVENANCE_ABR) continue
            // 版本相等：一次 optInt 判断即走，零 IO 重写
            if (src.optInt("abrConverterVersion", 0) >= AbrPresetConverter.CONVERTER_VERSION) continue
            runCatching {
                val abrParams = src.optJSONObject("abrParams") ?: run {
                    BrushDiagnostics.log("drift_refresh_skipped", "id" to e.id, "reason" to "no_abrParams")
                    return@runCatching
                }
                val oldBytes = PresetSnapshotStore.read(e.id) ?: run {
                    BrushDiagnostics.log("drift_refresh_skipped", "id" to e.id, "reason" to "no_snapshot")
                    return@runCatching
                }
                val oldXml = KppHelper.readPresetXml(oldBytes, stripEmbeddedResources = true)
                val preset = AbrPresetConverter.fromAbrParamsJson(abrParams, oldXml)
                // 回放导入时的混合模式策略；v11/v12 旧 source 无此键 → COMPAT_SAFE（历史现状）
                val policy = runCatching { AbrBlendModes.Policy.valueOf(src.optString("abrBlendPolicy")) }
                    .getOrNull() ?: AbrBlendModes.Policy.COMPAT_SAFE
                val converted = AbrPresetConverter.reconvertSnapshot(oldBytes, preset, e.name, src.optString("pack"), resourceStore, policy)
                    ?: run {
                        BrushDiagnostics.log("drift_refresh_skipped", "id" to e.id, "reason" to "reconvert_failed")
                        return@runCatching
                    }
                val newSource = JSONObject(src.toString())
                    .put("md5", BrushDiagnostics.md5(converted.kppBytes))
                    .put("abrConverterVersion", AbrPresetConverter.CONVERTER_VERSION)
                    .put("abrParams", AbrPresetConverter.abrParamsJson(preset))
                    .put("appBaseline", PresetReadback.baselineJson(converted.bp))
                PresetSnapshotStore.updateImportedSnapshot(e.id, e.ext, converted.kppBytes, newSource)
                BrushDiagnostics.log(
                    "drift_refreshed", "id" to e.id, "name" to e.name,
                    "from" to src.optInt("abrConverterVersion", 0), "to" to AbrPresetConverter.CONVERTER_VERSION,
                )
            }.onFailure {
                BrushDiagnostics.log("drift_refresh_failed", "id" to e.id, "err" to it.javaClass.simpleName, "msg" to it.toString())
            }
        }
    }

    /** 测试用 */
    fun resetForTest() {
        readOnly = false
        presetDir = null
        storeRootDir = null
        resourceStore = null
        PresetPropertyMap.tipBytesResolver = null
        BrushThumbStore.resetForTest()
        PresetReadback.clearForTest()
    }

    /** 归快照管理的预设（.kpp / .myb 且有 snapshot）；只认 id */
    fun managedEntry(id: String): BrushIdentity.Entry? {
        val e = BrushIdentity.entry(id) ?: return null
        if (!PresetSnapshotStore.has(e.id)) return null
        return e
    }
}
