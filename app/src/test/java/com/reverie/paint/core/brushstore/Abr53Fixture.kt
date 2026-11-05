/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/**
 * T2/T3 共用夹具：把 53 支真实 ABR 设备快照装进 JVM 临时 `brush_store`，
 * 并按既有 [AbrImportResetTest] 的模式初始化 [BrushStoreBootstrap]。
 *
 * 样本目录来源：平板上真实 ABR 导入快照（含第三方笔刷位图），出于 GPL 再分发考虑**不入库**，
 * 通过系统属性/环境变量 `ABR53_DIR`（系统属性优先，与 [AbrImportResetTest] 的 `ABR_SAMPLE` 取法一致）
 * 指向外部样本目录（每个 `imp_*` 目录含 `source.json` + `preset.kpp`，外加 [TSV_NAME]）。
 * 未设置或目录不存在时 [available] 为 false，各用例先 [assumeTrue] 门控（显示为 skipped），
 * [setUp] 内部也做了同样门控兜底。
 *
 * 装载步骤：临时目录 → Bootstrap.run（真实出厂 kpp 造出厂版本）→ 逐目录拷贝
 * `source.json`/`preset.kpp` 到 `snapshots/imported/<目录名>/` → [BrushIdentity.registerAll]
 * 注册 `imp:<uuid>` 身份；外部样本目录本身只读不写回。
 *
 * tsv 行按名字原样（保留尾随空格）与 `abrParams.name` 精确对齐。
 */
internal object Abr53Fixture {

    const val DIR_VAR = "ABR53_DIR"
    const val TSV_NAME = "abr_preset_table.tsv"
    const val TSV_COLUMNS = 18

    /** 外部样本目录：系统属性优先、环境变量兜底；未设置或目录不存在返回 null */
    fun externalDirOrNull(): File? {
        val p = System.getProperty(DIR_VAR) ?: System.getenv(DIR_VAR) ?: return null
        return File(p).takeIf { it.isDirectory }
    }

    /** 外部样本目录是否可用（各用例用它做 [assumeTrue] 门控） */
    fun available(): Boolean = externalDirOrNull() != null

    /** 外部样本目录（调用方须先用 [available] 门控，或仅在门控通过后的路径上调用） */
    fun dir(): File = requireNotNull(externalDirOrNull()) { "缺少外部 ABR53 样本（设置 $DIR_VAR）" }

    class Store(val root: File, val presetDir: File) {
        fun tearDown() {
            BrushStoreBootstrap.resetForTest()
            root.deleteRecursively()
        }
    }

    class Sample(
        val id: String, // imp:<uuid>
        val dirName: String, // 资源目录名 == BrushIdentity.dirNameFor(id)
        val name: String, // abrParams.name 原样（可能带尾随空格）
        val pack: String,
        val source: JSONObject, // source.json
        val abrParams: JSONObject,
        val tsvRow: List<String>, // tsv 原始行（18 列，右侧缺列补空串，不 trim）
        val snapshotKpp: File, // 临时目录里的快照 kpp
    ) {

        /** tsv 第 [col] 列（0 起）trim 后的值 */
        fun tsv(col: Int): String = tsvRow[col].trim()
    }

    fun setUp(): Pair<Store, List<Sample>> {
        val resDir = externalDirOrNull()
        assumeTrue("缺少外部 ABR53 样本（设置 $DIR_VAR）", resDir != null)
        val dirs = resDir!!.listFiles { f -> f.isDirectory && f.name.startsWith("imp_") }!!
            .sortedBy { it.name }
        check(dirs.size == 53) { "abr53 目录数应为 53，实际 ${dirs.size}" }

        val root = Files.createTempDirectory("abr53store").toFile()
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        // 与 AbrImportResetTest 相同的 Bootstrap 初始化（一个真实出厂 kpp 造出厂版本）
        check(
            BrushStoreBootstrap.run(
                root, presetDir, true, "1",
                { mapOf("b)_Basic-1.kpp" to KppFixtures.bytes("b)_Basic-1")) },
                migrate = { true },
            ),
        ) { "BrushStoreBootstrap.run 失败" }

        val tsvRows = readTsv(File(resDir, TSV_NAME))
        check(tsvRows.size == 53) { "tsv 行数应为 53，实际 ${tsvRows.size}" }
        val byName = HashMap<String, List<String>>() // tsv 名字（原样）-> 行
        for (row in tsvRows) {
            check(byName.put(row[1], row) == null) { "tsv 名字重复: [${row[1]}]" }
        }

        val importedRoot = File(root, "brush_store/snapshots/imported")
        check(importedRoot.isDirectory) { "PresetSnapshotStore.imported 目录不存在" }
        val entries = ArrayList<BrushIdentity.Entry>(dirs.size)
        val samples = ArrayList<Sample>(dirs.size)
        for (d in dirs) {
            val srcJson = File(d, "source.json")
            val srcKpp = File(d, "preset.kpp")
            check(srcJson.isFile && srcKpp.isFile) { "样本不完整: ${d.name}" }
            val dest = File(importedRoot, d.name)
            check(dest.mkdir() || dest.isDirectory)
            File(dest, "source.json").writeBytes(srcJson.readBytes())
            File(dest, "preset.kpp").writeBytes(srcKpp.readBytes())
            val source = JSONObject(srcJson.readText())
            val abrParams = source.getJSONObject("abrParams")
            val name = abrParams.getString("name")
            val id = "imp:" + d.name.removePrefix("imp_")
            check(BrushIdentity.dirNameFor(id) == d.name) { "目录名与 dirNameFor 不一致: ${d.name}" }
            entries += BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, source.optString("pack"))
            val row = requireNotNull(byName[name]) { "tsv 中找不到名字: [$name]" }
            samples += Sample(
                id = id,
                dirName = d.name,
                name = name,
                pack = source.optString("pack"),
                source = source,
                abrParams = abrParams,
                tsvRow = row,
                snapshotKpp = File(dest, "preset.kpp"),
            )
        }
        BrushIdentity.registerAll(entries)
        return Store(root, presetDir) to samples
    }

    private fun readTsv(f: File): List<List<String>> {
        check(f.isFile) { "缺少 $TSV_NAME" }
        return f.readLines().filter { it.isNotBlank() }.map { line ->
            val cols = line.split('\t')
            (0 until TSV_COLUMNS).map { cols.getOrNull(it) ?: "" }
        }
    }
}
