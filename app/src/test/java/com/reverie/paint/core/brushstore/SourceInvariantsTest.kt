package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级不变量：每条对应 笔刷统一总计划 里的一个"只留一个"的结论，防止回潮。
 * 每条守卫和它保护的修复在同一个提交里加。
 */
class SourceInvariantsTest {
    private val mainRoot = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    // 空集会让所有守卫空过，所以先断言源码树确实被扫到
    private fun mainFiles(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also { check(it.size > 100) { "源码树没扫到: ${mainRoot.absolutePath}" } }

    private fun hits(regex: Regex, except: Set<String> = emptySet(), only: Set<String>? = null): List<String> =
        mainFiles().filter { it.name !in except && (only == null || it.name in only) }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (regex.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }

    @Test
    fun `gap8 RefNormalizer 已删除 没有任何引用`() {
        assertEquals(emptyList<String>(), hits(Regex("""\bRefNormalizer\b""")))
        assertEquals(false, File(mainRoot, "com/reverie/paint/core/brushstore/RefNormalizer.kt").exists())
    }

    @Test
    fun `gap9 PNG 块只在 PngChunks 里读写`() {
        // CRC32 / Deflater 只允许出现在 PngChunks（压缩 zip 的 mimetype CRC 例外：KritaBundleManager 里是 zip 条目不是 PNG）
        val re = Regex("""\b(CRC32|Deflater|Inflater)\b|"(IDAT|IEND|IHDR|tEXt|zTXt|iTXt)"""")
        val offenders = hits(re, except = setOf("PngChunks.kt", "KritaBundleManager.kt", "PngSequenceZipEncoder.kt"))
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `gap4 kpp 只有一个读取器 parseKppAttributes 与 KppParsedAttributes 已删除`() {
        assertEquals(emptyList<String>(), hits(Regex("""(parseKppAttributes|parseKppFile|KppParsedAttributes)\b""")))
    }

    @Test
    fun `gap10 bundle 的 manifest 与 zip 解析只在 BrushImportPipeline 里 导入 VM 不再碰`() {
        val re = Regex("""\bZipFile\b|XmlPullParser|newPullParser|manifest\.xml|endsWith\("\.tag"""")
        val vm = hits(re).filter { it.startsWith("PaintViewModelBrush.kt:") }
        assertEquals(emptyList<String>(), vm)
        assertEquals(true, hits(Regex("""parseManifestTags""")).any { it.startsWith("BrushImportPipeline.kt:") })
    }

    @Test
    fun `gap6 id 是唯一查找键 名字到 id 只在磁盘边界和旧数据转换里出现`() {
        val re = Regex("""\b(idForName|entryForName)\(""")
        val allowed = setOf(
            "BrushIdentity.kt", // 定义
            "BrushStoreVm.kt", // idForScanned：引擎扫描文件名 → id，全工程唯一一处
            "BrushStoreBootstrap.kt", // 启动 reconcile：磁盘文件 → identity
            "BrushImportPipeline.kt", // adoptFile：新落盘的文件 → identity
            "LegacyBrushParamsMigrator.kt", // 旧 prefs 的名字键 → id（一次性迁移）
            "PaintViewModelBrush.kt", // 旧 tool-state 的 pn / pm 名字键 → id（读时转换）
            "BrushIdPrefs.kt", // 旧偏好（分组/排序/常用/最近/快捷栏）的名字键 → id（一次性迁移）
        )
        assertEquals(emptyList<String>(), hits(re, except = allowed))
        // 按名字的 API 不能再存在
        assertEquals(emptyList<String>(), hits(Regex("""managedEntry\([^)]*\.name\)|fun presetId\(|BrushApplicability\.forName|fun forName\(""")))
    }

    @Test
    fun `gap7 filesDir 下的 paintoppresets brushes patterns 目录只在 ResourceStore 里拼路径`() {
        // assets.list("brushes") 之类是 APK 资产路径、bundle 里的 zip 条目名是 Krita 格式，都不是 filesDir 目录
        val re = Regex("""File\([^"\n]*,\s*"(paintoppresets|brushes|patterns)"\)""")
        assertEquals(emptyList<String>(), hits(re, except = setOf("ResourceStore.kt")))
    }

    @Test
    fun `gap1 回放不自带笔刷 setter 也不用正则读 kpp`() {
        // 回放里的笔刷参数只经 BrushApply（同一个施加入口）；kpp 里的图案名走 KppXml，不用正则
        val setters = Regex("""ReverieCoreBridge\.(setBrush\w+|setToolMode)\(""")
        assertEquals(emptyList<String>(), hits(setters, only = setOf("PlaybackEngine.kt")))
        val regexUse = Regex("""\bRegex\(""")
        assertEquals(emptyList<String>(), hits(regexUse, only = setOf("PlaybackPresetCache.kt")))
    }

    @Test
    fun `gap2 笔刷参数只有 brushParams 一个所有者 brushX 是只读派生 笔画原值镜像已删`() {
        // vm 上凡是和 BrushParams 字段同名的 `brushXxx` 都不能是可写状态（var），只能是 val get() 派生
        val names = com.reverie.paint.core.BrushParams::class.java.declaredFields
            .map { it.name }.filter { it != "Companion" && !it.startsWith("$") }
        val mirror = names.map { "brush" + it.replaceFirstChar { c -> c.uppercase() } }.toSet()
        val decl = Regex("""\bvar (brush\w+)\b""")
        val bad = mainFiles().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val n = decl.find(l)?.groupValues?.get(1)
                if (n != null && n in mirror) "${f.name}:${i + 1}: ${l.trim()}" else null
            }
        }
        assertEquals(emptyList<String>(), bad)
        assertEquals(emptyList<String>(), hits(Regex("""\bactiveStrokeOriginal""")))
    }

    @Test
    fun `gap3 基线缓存只自校验 主代码里只有复位入口清它`() {
        val h = hits(Regex("""\bclear(BaselineCache|ForTest)\b"""), except = setOf("PresetReadback.kt"))
        // 只允许 resetForTest 两处（测试复位），不允许业务路径手动清
        assertEquals(listOf("BrushStoreBootstrap.kt", "BrushStoreVm.kt"), h.map { it.substringBefore(':') }.sorted())
    }

    @Test
    fun `gap12 ABR 导入撞名只用 uniqueDisplayName 不再自带 下划线序号 循环`() {
        assertEquals(emptyList<String>(), hits(Regex("""[$]\{safeName\}_[$]counter""")))
        assertEquals(emptyList<String>(), hits(Regex("""existingKppNames""")))
    }

    @Test
    fun `gap12 预设后缀判断只有 BrushImportPipeline_isPreset 一份 备份 同步 导入口 出厂 都不再硬编码 myb`() {
        assertEquals(emptyList<String>(), hits(Regex("""endsWith\("\.myb"""), except = setOf("BrushImportPipeline.kt")))
        assertEquals(emptyList<String>(), hits(Regex("""listOf\("kpp", "myb"\)"""), except = setOf("BrushImportPipeline.kt")))
        assertEquals(
            emptyList<String>(),
            hits(Regex("""endsWith\("\.kpp", ignoreCase = true\)"""), only = setOf("LocalBackupEngine.kt", "PaintViewModelBrush.kt", "PaintViewModelBrushLoader.kt", "BrushStoreBootstrap.kt")),
        )
    }

    @Test
    fun `gap12 图片笔尖导入 建预设的结果要用上 且用撞名后的真名`() {
        val src = File(mainRoot, "com/reverie/paint/core/PaintViewModelBrush.kt").readText()
        assertTrue(src.contains("val created = withContext(Dispatchers.Main) {"))
        assertTrue(src.contains("BrushImportResult(success = created, presetName = presetName"))
    }

    @Test
    fun `prefs 分组 排序 常用 最近 快捷栏 只按 id 存取 旧的名字键只在迁移器里出现`() {
        val legacyKeys = Regex(""""(user_groups|brush_order|brush_favorite_names|brush_recent_names|quick_brush_order)"""")
        assertEquals(emptyList<String>(), hits(legacyKeys, except = setOf("BrushIdPrefs.kt")))
        assertEquals(emptyList<String>(), hits(Regex("""\b(favoriteBrushNames|recentBrushNames)\b""")))
        // 分组按 id 查，不按显示名
        assertEquals(emptyList<String>(), hits(Regex("""userBrushGroups\[\s*(\w+\.)?(name|nm|presetName)\s*\]""")))
        assertEquals(emptyList<String>(), hits(Regex("""brushOrder\s*=.*\.name\b""")))
        // 改名不碰任何按 id 存的偏好（id 不变，不需要）
        val rename = mainRoot.walkTopDown().first { it.name == "PaintViewModelBrushIO.kt" }.readText()
            .substringAfter("fun PaintViewModel.renameBrushPreset").substringBefore("importCustomBrushTip")
        assertEquals(false, Regex("""userBrushGroups|brushOrder|favoriteBrush|recentBrush|quickBrushOrder""").containsMatchIn(rename))
    }

    @Test
    fun `v53 光标环轮廓的原生查询只经 BrushOutlineFetch 排到渲染线程 不在主线程直接调`() {
        // 原生 currentBrushOutline 与 loadBrushPreset 没有共用的锁；预设在渲染线程换，所以查询也只能在渲染线程
        val calls = hits(Regex("""getCurrentBrushOutline\(\)"""), except = setOf("ReverieCoreBridge.kt"))
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().startsWith("PaintViewModelBrush.kt:"))
        val vm = File(mainRoot, "com/reverie/paint/core/PaintViewModelBrush.kt").readText()
        val body = vm.substringAfter("fun PaintViewModel.fetchBrushOutlinePolygons()").substringBefore("internal fun resolvePresetSize")
        assertTrue(body.contains("BrushOutlineFetch.fetch("))
        assertTrue(body.indexOf("BrushOutlineFetch.fetch(") < body.indexOf("getCurrentBrushOutline()"))
    }

    @Test
    fun `v53 导入后只重载一次预设表 导入口各自统一重载 单条导入内部不再重载`() {
        // v52 前（含上游 f55b4a0e0）：ABR 分支自己 reloadBrushPresets，importBrushFromUri 又重载一次 → 同一支笔连载两次
        val vm = File(mainRoot, "com/reverie/paint/core/PaintViewModelBrush.kt").readText()
        val single = vm.substringAfter("suspend fun PaintViewModel.importSingleBrushInternal").substringBefore("internal fun abrPresetName")
        assertEquals(emptyList<String>(), Regex("""reloadBrushPresets\(""").findAll(single).map { it.value }.toList())
        // 图片笔尖分支建预设时显式不重载，由入口统一重载
        val createCall = Regex("""createNewBrushPreset\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL).find(single)!!.groupValues[1]
        assertTrue(createCall, createCall.contains("reload = false"))
        // 入口各重载一次
        val io = File(mainRoot, "com/reverie/paint/core/PaintViewModelBrushIO.kt").readText()
        assertEquals(1, Regex("""reloadBrushPresets\(selectName = result\.presetName\)""").findAll(io).count())
        assertEquals(1, Regex("""reloadBrushPresets\(selectName = lastPreset\)""").findAll(io).count())
    }
}
