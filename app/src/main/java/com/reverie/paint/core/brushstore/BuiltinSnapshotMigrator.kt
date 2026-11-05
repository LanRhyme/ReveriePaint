/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import java.io.File

/**
 * 内置快照升级策略（执行文档 W-10，D-4：新快照 + 保留覆盖）。
 *
 * 出厂版本 = 资产清单 md5（[BrushStoreBootstrap.factoryVersion]），APK 里任何内置 kpp 一变就会装一套新出厂快照；
 * 本对象在"旧版本 → 新版本"切换时比较两份清单：
 * - 新增：只记日志（identity 由 ensureBuiltinIdentities 注册，materializeAll 落盘）；
 * - 变更：无覆盖 → 直接用新快照（== 全新安装）；有覆盖 → 保留覆盖叠在新快照上；paintop 变了时只保留 LIVE 控件覆盖
 *   （其余控件对新笔刷类型未必适用），丢弃的键记 `upgrade_override_dropped`；
 * - 删除：不删用户的笔刷——旧快照转存为导入快照（provenance=retired，包名"已下线"），覆盖一并迁过去。
 */
object BuiltinSnapshotMigrator {
    const val PROVENANCE_RETIRED = "retired"
    const val RETIRED_PACK = "已下线"

    data class Diff(val added: Set<String>, val changed: Set<String>, val removed: Set<String>)

    /** 出厂目录 → {文件名: md5} */
    fun manifest(dir: File): Map<String, String> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && BrushStoreBootstrap.isFactoryPreset(it.name) }
            .associate { it.name to BrushDiagnostics.md5(it.readBytes()) }

    fun diff(old: Map<String, String>, new: Map<String, String>): Diff = Diff(
        added = new.keys - old.keys,
        changed = new.keys.filterTo(HashSet()) { it in old && old[it] != new[it] },
        removed = old.keys - new.keys,
    )

    /** 在新出厂版本已安装、旧版本目录尚未清理、内置 identity 尚未同步之前调用 */
    fun onFactoryChanged(oldVersion: String, newVersion: String): Diff {
        val oldDir = PresetSnapshotStore.factoryDir(oldVersion)
        val newDir = PresetSnapshotStore.factoryDir(newVersion)
        val d = diff(manifest(oldDir), manifest(newDir))
        for (fn in d.added.sorted()) BrushDiagnostics.log("upgrade_added", "file" to fn)
        for (fn in d.changed.sorted()) {
            val name = fn.substringBeforeLast('.')
            val id = BrushIdentity.builtinId(name)
            if (PresetSnapshotStore.restoredFile(id) != null) {
                BrushDiagnostics.log("upgrade_skipped_restored", "name" to name)
                continue
            }
            upgradeChanged(fn, File(oldDir, fn), File(newDir, fn))
        }
        for (fn in d.removed.sorted()) retire(fn, File(oldDir, fn))
        BrushDiagnostics.log(
            "upgrade",
            "from" to oldVersion, "to" to newVersion,
            "added" to d.added.size, "changed" to d.changed.size, "removed" to d.removed.size,
        )
        return d
    }

    private fun paintOp(f: File): String? =
        if (f.name.endsWith(".kpp", true)) KppHelper.readPresetXml(f.readBytes(), stripEmbeddedResources = true)?.let { KppXml.paintOpId(it) } else null

    private fun upgradeChanged(fn: String, oldFile: File, newFile: File) {
        val name = fn.substringBeforeLast('.')
        val id = BrushIdentity.builtinId(name)
        val o = BrushOverrideStore.get(id)
        if (o.isEmpty()) {
            BrushDiagnostics.log("upgrade_changed", "name" to name, "overrides" to 0)
            return
        }
        val oldOp = paintOp(oldFile)
        val newOp = paintOp(newFile)
        var kept = o
        if (oldOp != newOp) {
            val keep = ControlId.entries.filter { it.klass == ControlClass.LIVE || it.klass == ControlClass.APP_ONLY }.mapTo(HashSet()) { it.key }
            val drop = o.controls.keys.filterNot { it in keep }.toSet()
            kept = o.copy(controls = o.controls.filterKeys { it !in drop }, dirty = o.dirty - drop, rawKeys = emptyMap())
            if (drop.isNotEmpty() || o.rawKeys.isNotEmpty()) {
                BrushDiagnostics.log(
                    "upgrade_override_dropped",
                    "name" to name, "from" to oldOp, "to" to newOp, "keys" to drop.sorted().joinToString(","), "rawKeys" to o.rawKeys.size,
                )
            }
            BrushOverrideStore.replace(id, kept)
        }
        BrushDiagnostics.log("upgrade_changed", "name" to name, "overrides" to kept.controls.size, "policy" to "new_snapshot_keep_overrides")
    }

    private fun retire(fn: String, oldFile: File) {
        val name = fn.substringBeforeLast('.')
        val ext = fn.substringAfterLast('.').lowercase()
        val builtin = BrushIdentity.builtinId(name)
        val restored = PresetSnapshotStore.restoredFile(builtin)
        if (BrushIdentity.entry(builtin) == null && restored == null && !oldFile.isFile) return
        val bytes = restored?.readBytes() ?: if (oldFile.isFile) oldFile.readBytes() else return
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(
            id, ext, bytes,
            JSONObject().put("provenance", PROVENANCE_RETIRED).put("pack", RETIRED_PACK).put("file", fn)
                .put("from", builtin).put("md5", BrushDiagnostics.md5(bytes)),
        )
        BrushOverrideStore.copy(builtin, id)
        BrushOverrideStore.clear(builtin)
        BrushThumbStore.copy(builtin, id)
        BrushThumbStore.delete(builtin)
        PresetSnapshotStore.removeRestored(builtin)
        BrushIdentity.remove(builtin)
        BrushIdentity.register(BrushIdentity.Entry(id, name, ext, BrushIdentity.Kind.IMPORTED, RETIRED_PACK))
        BrushDiagnostics.log("upgrade_retired", "name" to name, "id" to id)
    }

    /** 首次进入 v2：磁盘上的内置有效副本与出厂不同、且没有覆盖时记 drift（有效副本随后以出厂为准） */
    fun logDrift(presetDir: File, version: String) {
        val dir = PresetSnapshotStore.factoryDir(version)
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!BrushStoreBootstrap.isFactoryPreset(f.name)) continue
            val disk = File(presetDir, f.name).takeIf { it.isFile } ?: continue
            val id = BrushIdentity.builtinId(f.name.substringBeforeLast('.'))
            if (!BrushOverrideStore.get(id).isEmpty()) continue
            val dm = BrushDiagnostics.md5(disk.readBytes())
            val am = BrushDiagnostics.md5(f.readBytes())
            if (dm != am) BrushDiagnostics.log("drift", "name" to f.nameWithoutExtension, "diskMd5" to dm, "assetMd5" to am)
        }
    }
}
