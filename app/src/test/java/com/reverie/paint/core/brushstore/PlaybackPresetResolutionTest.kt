/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingReader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 回放预设解析与执行（W-12）：实际 kpp + 缓存 */
class PlaybackPresetResolutionTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private val stems by lazy { KppFixtures.all.map { it.first }.take(12) }

    private class FakeEngine : BrushApply.EngineFacade {
        val calls = mutableListOf<String>()
        var loadFileResult = true
        var tipResolved = true
        override fun loadBrushPreset(index: Int): Boolean { calls += "load:$index"; return true }
        override fun loadBrushPresetFile(path: String): Boolean { calls += "loadFile:${File(path).name}"; return loadFileResult }
        override fun currentTipResolved(): Boolean { calls += "currentTipResolved"; return tipResolved }
        override fun setPresetIsEraser(isEraser: Boolean) { calls += "eraser:$isEraser" }
        override fun setBrushCompositeOp(op: String) { calls += "comp" }
        override fun setBrushColor(color: String) { calls += "color" }
        override fun setBrushSecondaryColor(color: String) { calls += "color2" }
        override fun setBrushSize(v: Double) { calls += "size" }
        override fun setBrushOpacity(v: Double) { calls += "opacity" }
        override fun setBrushFlow(v: Double) { calls += "flow" }
        override fun setBrushAngle(deg: Double) { calls += "angle" }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("playback").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } }, migrate = { true }))
    }

    @After
    fun tearDown() {
        BrushApply.engine = BrushApply.NativeEngine
        BrushStoreBootstrap.resetForTest()
        root.deleteRecursively()
    }

    private val storeRoot get() = BrushStoreBootstrap.storeRootDir!!

    @Test
    fun recordCachesEffectiveBytesAndEventRoundTrips() {
        val s = stems[0]
        val sel = PlaybackPresetCache.recordSelect(3, com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "")!!
        val eff = File(presetDir, "$s.kpp").readBytes()
        assertEquals(BrushDiagnostics.md5(eff), sel.md5)
        assertEquals(BrushIdentity.builtinId(s), sel.presetId)
        assertArrayEquals(eff, PlaybackPresetCache.cacheFile(storeRoot, sel.md5).readBytes())

        val b = RecordingBuffer()
        PlaybackPresetCache.write(b, sel)
        PlaybackPresetCache.write(b, sel.copy(index = -1))
        val r = RecordingReader(b.data.copyOf(b.size))
        assertEquals(sel, PlaybackPresetCache.read(r))
        assertEquals(sel.copy(index = -1), PlaybackPresetCache.read(r))
        assertEquals(b.size, r.pos)

        // 尝试对未由 v2 管理的笔刷记录返回 null（非破坏性回退）
        assertNull(PlaybackPresetCache.recordSelect(0, com.reverie.paint.core.brushstore.BrushIdentity.idForName("not_a_managed_preset") ?: ""))
    }

    @Test
    fun recordCachesPatternFileAndResolveRestoresMissingCopy() {
        val s = stems[0]
        val pngName = "paper.png"
        // 真实 kpp 外壳 + 带图案名的 XML（按真实文件读，不是裸文本）
        val bytes = com.reverie.paint.core.KppHelper.replacePresetXml(
            KppFixtures.bytes(s),
            """<Preset><param name="Texture/Pattern/PatternFileName" type="string"><![CDATA[/tmp/patterns/$pngName]]></param></Preset>""",
        )
        File(presetDir, "$s.kpp").writeBytes(bytes)
        val live = PlaybackPresetCache.livePatternDir(storeRoot).apply { mkdirs() }
        val png = byteArrayOf(1, 2, 3, 4)
        File(live, pngName).writeBytes(png)

        val sel = PlaybackPresetCache.recordSelect(1, com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "")!!
        val cached = File(PlaybackPresetCache.patternCacheDir(storeRoot, sel.md5), pngName)
        assertTrue(cached.isFile)
        assertArrayEquals(png, cached.readBytes())

        File(live, pngName).delete()
        assertFalse(File(live, pngName).isFile)
        PlaybackPresetCache.resolve(sel)
        assertArrayEquals(png, File(live, pngName).readBytes())
    }

    @Test
    fun resolutionFollowsEffectiveMd5ThenCacheThenFallback() {
        val s = stems[1]
        val id = BrushIdentity.builtinId(s)
        val sel = PlaybackPresetCache.recordSelect(5, com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "")!!
        val eng = FakeEngine()
        BrushApply.engine = eng
        assertEquals(PlaybackPresetCache.Resolution.EXACT, BrushApply.applyReplay(sel, false))
        assertEquals(listOf("loadFile:$s.kpp", "currentTipResolved", "eraser:false"), eng.calls)

        // 录制后用户编辑了预设 → 有效字节变了，但录制时的字节在缓存中
        eng.calls.clear()
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.25), dirty = setOf("opacity")))
        EffectivePresetBuilder.materialize(id, presetDir)
        assertTrue(BrushDiagnostics.md5(File(presetDir, "$s.kpp").readBytes()) != sel.md5)
        assertEquals(PlaybackPresetCache.Resolution.CACHED, BrushApply.applyReplay(sel, false))
        assertEquals(listOf("loadFile:${sel.md5}.kpp", "currentTipResolved", "eraser:false"), eng.calls)

        // 缓存文件删掉 → 用该笔的快照（原始字节）重建，再由 CONTEXT_EXT 补形状覆盖（见 missingCacheRebuildsFromSnapshot…）
        PlaybackPresetCache.cacheFile(storeRoot, sel.md5).delete()
        eng.calls.clear()
        assertEquals(PlaybackPresetCache.Resolution.REBUILT, BrushApply.applyReplay(sel, true))
        assertTrue(eng.calls.any { it.startsWith("loadFile:") }); assertTrue(eng.calls.contains("eraser:true"))

        // 撤回空开 → 有效 md5 回到录制时 → 又 EXACT
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides())
        EffectivePresetBuilder.materialize(id, presetDir)
        assertEquals(PlaybackPresetCache.Resolution.EXACT, PlaybackPresetCache.resolve(sel))
    }

    @Test
    fun cacheIsLruBounded() {
        val dir = PlaybackPresetCache.cacheDir(storeRoot).apply { mkdirs() }
        val t = System.currentTimeMillis() - 1_000_000
        for (i in 0 until 10) File(dir, "f$i.kpp").apply { writeBytes(byteArrayOf(i.toByte())); setLastModified(t + i * 1000L) }
        PlaybackPresetCache.prune(storeRoot, max = 4)
        assertEquals(setOf("f6.kpp", "f7.kpp", "f8.kpp", "f9.kpp"), dir.list()!!.toSet())

        // 命中会刷新 mtime，不会被下一次剪枝淘汰
        val sel = PlaybackPresetCache.recordSelect(0, (com.reverie.paint.core.brushstore.BrushIdentity.idForName(stems[2]) ?: ""))!!
        val hit = PlaybackPresetCache.cacheFile(storeRoot, sel.md5)
        hit.setLastModified(t - 10_000)
        BrushOverrideStore.replace(BrushIdentity.builtinId(stems[2]), BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.5), dirty = setOf("opacity")))
        EffectivePresetBuilder.materialize(BrushIdentity.builtinId(stems[2]), presetDir)
        assertEquals(PlaybackPresetCache.Resolution.CACHED, PlaybackPresetCache.resolve(sel))
        PlaybackPresetCache.prune(storeRoot, max = 4)
        assertNotNull(dir.list()!!.firstOrNull { it == hit.name })
    }
    @Test
    fun loadOrTipFailureFallsBackToMissingWithoutIndexLoad() {
        val s = stems[2]
        val sel = PlaybackPresetCache.recordSelect(7, com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "")!!
        val eng = FakeEngine()
        BrushApply.engine = eng

        // 1. loadBrushPresetFile failed -> enters MISSING directly without loadBrushPreset(index)
        eng.loadFileResult = false
        eng.calls.clear()
        assertEquals(PlaybackPresetCache.Resolution.MISSING, BrushApply.applyReplay(sel, false))
        assertEquals(listOf("loadFile:$s.kpp", "eraser:false"), eng.calls)
        assertFalse(eng.calls.any { it.startsWith("load:") })

        // 2. tip not resolved -> enters MISSING directly without loadBrushPreset(index)
        eng.loadFileResult = true
        eng.tipResolved = false
        eng.calls.clear()
        assertEquals(PlaybackPresetCache.Resolution.MISSING, BrushApply.applyReplay(sel, false))
        assertEquals(listOf("loadFile:$s.kpp", "currentTipResolved", "eraser:false"), eng.calls)
        assertFalse(eng.calls.any { it.startsWith("load:") })
    }

    private fun replayRecorder(eng: FakeEngine) = object : BrushApply.ReplayEngine {
        override fun setBrushSoftness(v: Double) { eng.calls += "softness:$v" }
        override fun setBrushSpacing(v: Double) { eng.calls += "spacing:$v" }
        override fun setBrushScatter(v: Double) { eng.calls += "scatter:$v" }
        override fun setBrushRotation(v: Double) { eng.calls += "rotation:$v" }
        override fun setBrushRatio(v: Double) { eng.calls += "ratio:$v" }
        override fun setBrushSharpness(v: Double) { eng.calls += "sharpness:$v" }
        override fun setBrushSmudgeRate(v: Double) { eng.calls += "smudgeRate:$v" }
        override fun setBrushSmudgeLength(v: Double) { eng.calls += "smudgeLen:$v" }
        override fun setBrushAirbrush(enabled: Boolean, rate: Double) { eng.calls += "airbrush" }
        override fun setBrushFade(v: Double) { eng.calls += "fade:$v" }
    }

    /** 新录制（v2 选笔 + CONTEXT_EXT）：缓存被淘汰、当前有效字节又被改过 → 用快照 + CONTEXT_EXT 重建，不用当前覆盖 */
    @Test
    fun missingCacheRebuildsFromSnapshotAndContextExtSuppliesOverrides() {
        val s = stems[3]
        val id = BrushIdentity.builtinId(s)
        val sel = PlaybackPresetCache.recordSelect(5, id)!!
        val snapshot = PresetSnapshotStore.read(id)!!
        // 录制后用户又改了预设（有效字节 ≠ 录制时），录制缓存也被淘汰
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.25), dirty = setOf("opacity")))
        EffectivePresetBuilder.materialize(id, presetDir)
        PlaybackPresetCache.cacheFile(storeRoot, sel.md5).delete()
        val eng = FakeEngine(); BrushApply.engine = eng; BrushApply.replayEngine = replayRecorder(eng)

        assertEquals(PlaybackPresetCache.Resolution.REBUILT, BrushApply.applyReplay(sel, false))
        val loaded = eng.calls.first { it.startsWith("loadFile:") }.removePrefix("loadFile:")
        val rebuilt = PlaybackPresetCache.cacheDir(storeRoot).listFiles()!!.single { it.name == loaded }
        assertArrayEquals("重建用的是快照字节，不是当前有效字节", snapshot, rebuilt.readBytes())
        assertTrue("当前有效字节不该被拿来重建", !rebuilt.readBytes().contentEquals(File(presetDir, "$s.kpp").readBytes()))

        // 录制时该预设被自定义过：CONTEXT_EXT 的形状值叠在重建的快照上（= 快照 + 覆盖）
        eng.calls.clear()
        BrushApply.applyReplayExt(5, 1, 0.1, 0.2, 3.0, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, "#abc", false, 0.0)
        assertEquals(listOf("softness:0.1", "spacing:0.2", "angle", "scatter:0.4", "rotation:0.5", "ratio:0.6", "sharpness:0.7"),
            eng.calls.filter { it.startsWith("softness") || it.startsWith("spacing") || it == "angle" || it.startsWith("scatter") || it.startsWith("rotation") || it.startsWith("ratio") || it.startsWith("sharp") })
        BrushApply.resetEngineForTest()
    }

    /** 重建失败（没有快照 / 载入失败）仍然退回"沿用当前笔"，不会载入别的东西 */
    @Test
    fun noSnapshotStillKeepsCurrentBrush() {
        val eng = FakeEngine(); BrushApply.engine = eng
        val sel = PlaybackPresetCache.Select(4, "gone", "imp:gone", "deadbeef")
        assertEquals(PlaybackPresetCache.Resolution.MISSING, BrushApply.applyReplay(sel, true))
        assertEquals(listOf("eraser:true"), eng.calls)
    }

    /** 旧录制（选笔事件没有 md5）：行为不变，不重建、不载入 */
    @Test
    fun oldRecordingWithoutMd5KeepsCurrentBehavior() {
        val s = stems[4]
        val eng = FakeEngine(); BrushApply.engine = eng
        val sel = PlaybackPresetCache.Select(2, s, BrushIdentity.builtinId(s), "")
        assertEquals(PlaybackPresetCache.Resolution.MISSING, BrushApply.applyReplay(sel, false))
        assertEquals(listOf("eraser:false"), eng.calls)
        assertTrue(PlaybackPresetCache.cacheDir(storeRoot).listFiles().orEmpty().none { it.name.startsWith("rebuilt-") })
    }
}
