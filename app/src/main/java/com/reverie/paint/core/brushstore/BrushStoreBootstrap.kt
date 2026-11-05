/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

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
        resourceStore ?: ResourceStore(storeRoot(filesDir)).also { resourceStore = it }

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
        resourceStore = ResourceStore(storeRoot, bDir, patDir)
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

    /** 启动：同步出厂 → [migrate]（只在需要时）→ reconcileDisk → materializeAll。 */
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
            rStore.register(f.readBytes(), ResourceStore.Kind.TIP, f.name)
        }
        patDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            rStore.register(f.readBytes(), ResourceStore.Kind.PATTERN, f.name)
        }

        pDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in BrushImportPipeline.PRESET_EXTS }?.forEach { f ->
            val name = f.nameWithoutExtension
            val fext = f.extension.lowercase()
            val currentBytes = f.readBytes()
            val currentMd5 = BrushDiagnostics.md5(currentBytes).lowercase()
            val entry = BrushIdentity.entryForName(name)
            if (entry == null || !PresetSnapshotStore.has(entry.id)) {
                BrushImportPipeline.adoptFile(name, pDir, "reconcile", ext = fext)
            } else if (!entry.ext.equals(fext, ignoreCase = true)) {
                BrushDiagnostics.log("reconcile_ext_mismatch", "name" to name, "file" to f.name, "entry" to entry.ext)
            } else {
                val id = entry.id
                if (hasPending && preMd5Map != null) {
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
        }

        if (hasPending) {
            pendingFile.delete()
        }
    }

    /** 测试用 */
    fun resetForTest() {
        readOnly = false
        presetDir = null
        storeRootDir = null
        resourceStore = null
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
