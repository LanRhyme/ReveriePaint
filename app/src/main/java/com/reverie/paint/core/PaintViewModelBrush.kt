/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.BrushIdPrefs
import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.ResourceDirs
import com.reverie.paint.core.brushstore.ResourceStore
import java.io.IOException
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

    internal fun PaintViewModel.prefs() =
        appContext.getSharedPreferences("brush_groups", android.content.Context.MODE_PRIVATE)

    internal fun PaintViewModel.brushIdKvGroups() = BrushIdPrefs.SharedPrefsKv(prefs())
    internal fun PaintViewModel.brushIdKvMain() =
        BrushIdPrefs.SharedPrefsKv(appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE))

    /** 名字 → id 的一次性迁移（幂等）。identity 没打开时不能迁：全部会被当成对不上。 */
    internal fun PaintViewModel.migrateBrushPrefsToIds() {
        if (!appContextReady || !BrushIdentity.isOpen()) return
        val g = brushIdKvGroups(); val m = brushIdKvMain()
        if (!BrushIdPrefs.needsMigration(g, m)) return
        BrushIdPrefs.migrate(g, m, BrushIdPrefs.resolverFor(ResourceDirs.presets(appContext.filesDir)))
    }

    /** 分组/排序/常用/最近/快捷栏顺序：迁移后从 id 键读取（只此一处）。 */
    private var _abrBlendPolicy = androidx.compose.runtime.mutableStateOf(AbrBlendModes.Policy.COMPAT_SAFE)
    private var _abrBlendPolicyLoaded = false

    internal var PaintViewModel.abrBlendPolicy: AbrBlendModes.Policy
        get() {
            if (!_abrBlendPolicyLoaded) {
                loadAbrBlendPolicy()
            }
            return _abrBlendPolicy.value
        }
        set(value) {
            _abrBlendPolicy.value = value
            try {
                appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("abr_blend_policy", value.name)
                    .apply()
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "save abr_blend_policy failed", e)
            }
        }

    internal fun PaintViewModel.loadAbrBlendPolicy() {
        if (_abrBlendPolicyLoaded || !appContextReady) return
        _abrBlendPolicyLoaded = true
        val saved = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .getString("abr_blend_policy", null)
        if (saved != null) {
            runCatching { AbrBlendModes.Policy.valueOf(saved) }.getOrNull()?.let {
                _abrBlendPolicy.value = it
            }
        }
    }

    internal fun PaintViewModel.loadBrushIdPrefs() {
        migrateBrushPrefsToIds()
        val s = BrushIdPrefs.load(brushIdKvGroups(), brushIdKvMain())
        userBrushGroups = s.groups
        brushOrder = s.order
        favoriteBrushIds = s.favorites
        recentBrushIds = s.recents
        quickBrushOrder = s.quick
        brushIdPrefsLoaded = true
    }

    internal fun PaintViewModel.loadBrushGroups() {
        val customsJson = prefs().getString("custom_groups", null)
        customBrushGroups = if (customsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(customsJson)
                (0 until arr.length()).map { arr.getString(it) }.filter {
                    it.isNotBlank() && it !in listOf("默认", "Default", "default", "全部", "常用", "最近")
                }
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    internal fun PaintViewModel.saveBrushGroups() {
        try {
            val cust = org.json.JSONArray()
            for (g in customBrushGroups) cust.put(g)
            prefs().edit()
                .putString(BrushIdPrefs.GROUPS_IDS, BrushIdPrefs.encodeGroups(userBrushGroups))
                .putString("custom_groups", cust.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushGroups failed", e)
        }
    }

    /** UI 的拖拽/选择/菜单状态按显示名暂存；落到偏好前在这里一次性换成 id。 */
    internal fun PaintViewModel.presetIdOf(name: String): String? = brushPresets.firstOrNull { it.name == name }?.id

    /** Create a new user brush group. Returns false if the name exists. */
    internal fun PaintViewModel.createBrushGroup(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (customBrushGroups.contains(n) || n == "全部") return false
        customBrushGroups = customBrushGroups + n
        saveBrushGroups()
        return true
    }

    /** Move a preset into a group (or back to its inferred group). */
    internal fun PaintViewModel.moveBrushToGroup(presetName: String, group: String) {
        val id = presetIdOf(presetName) ?: return
        userBrushGroups = userBrushGroups + (id to group)
        saveBrushGroups()
        // Refresh the displayed group of this preset
        brushPresets = brushPresets.map {
            if (it.id == id) it.copy(group = group) else it
        }
    }

    internal fun PaintViewModel.saveBrushOrder() {
        try {
            val arr = org.json.JSONArray()
            for (n in brushOrder) arr.put(n)
            prefs().edit().putString(BrushIdPrefs.ORDER_IDS, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushOrder failed", e)
        }
    }

    /** Move a preset up/down within its current list position. */
    internal fun PaintViewModel.moveBrushUp(presetName: String) {
        reorderBrush(presetName, -1)
    }

    internal fun PaintViewModel.moveBrushDown(presetName: String) {
        reorderBrush(presetName, 1)
    }

    internal fun PaintViewModel.reorderBrush(presetName: String, delta: Int) {
        val cur = brushPresets
        val idx = cur.indexOfFirst { it.name == presetName }
        val to = idx + delta
        if (idx < 0 || to < 0 || to >= cur.size) return
        val newList = cur.toMutableList()
        val t = newList[idx]
        newList[idx] = newList[to]
        newList[to] = t
        brushPresets = newList
        brushOrder = newList.map { it.id }
        saveBrushOrder()
    }

    /** 拖拽直接指定位置重排序 */
    internal fun PaintViewModel.reorderBrushPresets(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in brushPresets.indices || toIndex !in brushPresets.indices) return
        val newList = brushPresets.toMutableList()
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)
        brushPresets = newList
        brushOrder = newList.map { it.id }
        saveBrushOrder()
    }

    internal fun PaintViewModel.reorderBrushInView(fromName: String, toName: String) {
        if (fromName == toName) return
        val curList = brushPresets.toMutableList()
        val fromIdx = curList.indexOfFirst { it.name == fromName }
        val toIdx = curList.indexOfFirst { it.name == toName }
        if (fromIdx < 0 || toIdx < 0) return
        val item = curList.removeAt(fromIdx)
        curList.add(toIdx, item)
        brushPresets = curList
        brushOrder = curList.map { it.id }
        saveBrushOrder()
    }

    internal fun PaintViewModel.saveCategoryOrder() {
        try {
            val arr = org.json.JSONArray()
            for (c in categoryOrder) arr.put(c)
            prefs().edit().putString("category_order", arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    internal fun PaintViewModel.loadCategoryOrder() {
        try {
            val raw = prefs().getString("category_order", null) ?: return
            val arr = org.json.JSONArray(raw)
            categoryOrder = (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
        }
    }

    internal fun PaintViewModel.reorderCategory(from: Int, to: Int, allCategories: List<String>) {
        val list = allCategories.toMutableList()
        if (from == to || from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        val pinned = listOf("全部", "常用", "最近")
        categoryOrder = list.filter { it !in pinned }
        saveCategoryOrder()
    }

    internal fun PaintViewModel.reorderCategoryByName(fromCat: String, toCat: String, allCategories: List<String>) {
        if (fromCat == toCat) return
        val pinned = listOf("全部", "常用", "最近")
        if (fromCat in pinned || toCat in pinned) return
        val list = allCategories.toMutableList()
        val fromIdx = list.indexOf(fromCat)
        val toIdx = list.indexOf(toCat)
        if (fromIdx < 0 || toIdx < 0) return
        val item = list.removeAt(fromIdx)
        list.add(toIdx, item)
        categoryOrder = list.filter { it !in pinned }
        saveCategoryOrder()
    }

    internal fun PaintViewModel.moveCategoryUp(cat: String, allCategories: List<String>) {
        val pinned = listOf("全部", "常用", "最近")
        if (cat in pinned) return
        val idx = allCategories.indexOf(cat)
        val minIdx = allCategories.indexOfFirst { it !in pinned }
        if (idx > minIdx && minIdx >= 0) {
            reorderCategory(idx, idx - 1, allCategories)
        }
    }

    internal fun PaintViewModel.moveCategoryDown(cat: String, allCategories: List<String>) {
        val pinned = listOf("全部", "常用", "最近")
        if (cat in pinned) return
        val idx = allCategories.indexOf(cat)
        if (idx >= 0 && idx < allCategories.size - 1) {
            reorderCategory(idx, idx + 1, allCategories)
        }
    }

    internal fun PaintViewModel.renameBrushGroup(oldName: String, newName: String): Boolean {
        if (isBuiltInGroup(oldName)) return false
        val clean = newName.trim().ifEmpty { return false }
        if (clean == oldName) return true
        if (customBrushGroups.contains(oldName)) {
            customBrushGroups = customBrushGroups.map { if (it == oldName) clean else it }
        }
        userBrushGroups = userBrushGroups.mapValues { if (it.value == oldName) clean else it.value }
        saveBrushGroups()
        if (categoryOrder.contains(oldName)) {
            categoryOrder = categoryOrder.map { if (it == oldName) clean else it }
            saveCategoryOrder()
        }
        reloadBrushPresets()
        return true
    }

    internal fun PaintViewModel.persistToolBrushStates() {
        try {
            val json = org.json.JSONArray()
            for ((id, s) in toolBrushStates) {
                val o = org.json.JSONObject()
                o.put("id", id)
                o.put("pid", s.presetId)
                o.put("c", s.category)
                o.put("csi", s.categoryScrollIndex)
                o.put("cso", s.categoryScrollOffset)
                o.put("psi", s.presetScrollIndex)
                o.put("pso", s.presetScrollOffset)
                if (s.paramMemory.isNotEmpty()) {
                    val pmi = org.json.JSONArray()
                    for ((k, v) in s.paramMemory) {
                        if (v.size >= 3) {
                            pmi.put(org.json.JSONArray().apply {
                                put(k); put(v[0]); put(v[1]); put(v[2])
                            })
                        }
                    }
                    o.put("pmi", pmi)
                }
                json.put(o)
            }
            prefs().edit().putString("tool_brush_states", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.savePinnedTools(tools: List<com.reverie.paint.model.Tool>) {
        pinnedTools = tools
        try {
            val ids = tools.map { it.id }.joinToString(",")
            prefs().edit().putString("pinned_tools", ids).apply()
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.loadPinnedTools() {
        try {
            val pinned = prefs().getString("pinned_tools", null) ?: return
            val ids = pinned.split(",").filter { it.isNotEmpty() }
            val loaded = ids.mapNotNull { id ->
                when (id) {
                    "symmetry", "perspective", "drawing_guide" -> com.reverie.paint.model.Tool.DRAWING_GUIDE
                    else -> com.reverie.paint.model.Tool.fromIdOrNull(id)
                }
            }.distinct()
            if (loaded.isNotEmpty()) {
                pinnedTools = loaded
            }
        } catch (_: Exception) {
        }
    }


    internal fun PaintViewModel.loadBrushParams(): Map<String, BrushParams> {
        val legacyParams = HashMap<String, BrushParams>()
        val resetLegacySoftness = !prefs().getBoolean("brush_softness_neutral_migrated", false)
        val resetLegacyFade = !prefs().getBoolean("brush_fade_solidity_migrated", false)
        val resetLegacyJitter = !prefs().getBoolean("brush_jitter_mix_migrated", false)
        val resetLegacySpacing = !prefs().getBoolean("brush_spacing_dotted_migrated_v4", false)
        val resetLegacyScatter = !prefs().getBoolean("brush_scatter_migrated_v1", false)
        try {
            val raw = prefs().getString("brush_params", null) ?: return legacyParams
            val json = org.json.JSONArray(raw)
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val name = o.getString("n")
                legacyParams[name] = LegacyBrushParamsCodec.decode(
                    o,
                    LegacyBrushParamsCodec.Heal(
                        softness = resetLegacySoftness,
                        fade = resetLegacyFade,
                        jitter = resetLegacyJitter,
                        spacing = resetLegacySpacing,
                        scatter = resetLegacyScatter,
                    ),
                )
            }
            if (resetLegacySoftness || resetLegacyFade || resetLegacyJitter || resetLegacySpacing || resetLegacyScatter) {
                prefs().edit().putBoolean("brush_softness_neutral_migrated", true).apply()
                prefs().edit().putBoolean("brush_fade_solidity_migrated", true).apply()
                prefs().edit().putBoolean("brush_jitter_mix_migrated", true).apply()
                prefs().edit().putBoolean("brush_spacing_dotted_migrated_v4", true).apply()
                prefs().edit().putBoolean("brush_scatter_migrated_v1", true).apply()
            }
        } catch (_: Exception) {
        }
        return legacyParams
    }

    internal fun PaintViewModel.loadToolBrushStates() {
        try {
            val raw = prefs().getString("tool_brush_states", null) ?: return
            val json = org.json.JSONArray(raw)
            val map = mutableMapOf<String, PaintViewModel.ToolBrushState>()
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val pmiJson = o.optJSONArray("pmi")
                val pmJson = o.optJSONArray("pm")
                var paramMemory: Map<String, List<Double>> = emptyMap()
                if (pmiJson != null) {
                    val m = mutableMapOf<String, List<Double>>()
                    for (j in 0 until pmiJson.length()) {
                        val e = pmiJson.optJSONArray(j) ?: continue
                        if (e.length() >= 4) {
                            m[e.optString(0)] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                        }
                    }
                    paramMemory = m
                } else if (pmJson != null) {
                    val m = mutableMapOf<String, List<Double>>()
                    for (j in 0 until pmJson.length()) {
                        val e = pmJson.optJSONArray(j) ?: continue
                        if (e.length() >= 4) {
                            val oldName = e.optString(0)
                            val id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(oldName)
                            if (id != null) {
                                m[id] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                            }
                        }
                    }
                    paramMemory = m
                }
                var pid = o.optString("pid", "")
                val pn = o.optString("pn", "")
                if (pid.isBlank() && pn.isNotBlank()) {
                    pid = com.reverie.paint.core.brushstore.BrushIdentity.idForName(pn) ?: ""
                }
                map[o.getString("id")] = PaintViewModel.ToolBrushState(
                    presetIndex = o.optInt("pi", -1),
                    presetId = pid,
                    category = o.optString("c", "全部"),
                    categoryScrollIndex = o.optInt("csi", 0),
                    categoryScrollOffset = o.optInt("cso", 0),
                    presetScrollIndex = o.optInt("psi", 0),
                    presetScrollOffset = o.optInt("pso", 0),
                    paramMemory = paramMemory
                )
            }
            toolBrushStates = map
            val curId = prefs().getString("current_tool_id", "brush") ?: "brush"
            val activeState = map[curId] ?: map["brush"]
            if (activeState != null) {
                brushPanelSelectedCategory = activeState.category
                brushCategoryScrollIndex = activeState.categoryScrollIndex
                brushCategoryScrollOffset = activeState.categoryScrollOffset
                brushPresetScrollIndex = activeState.presetScrollIndex
                brushPresetScrollOffset = activeState.presetScrollOffset
            }
        } catch (_: Exception) {
        }
        
        try {
            currentToolId = prefs().getString("current_tool_id", "brush") ?: "brush"
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.resetBrushParams() {
        val index = brushPresetIndex
        // 没有选中预设时什么也没重置，不弹「已重置」（否则是假反馈）
        val preset = brushPresets.firstOrNull { it.index == index } ?: return
        BrushStoreVm.reset(this, preset.id)
        android.widget.Toast.makeText(
            appContext,
            appContext.getString(R.string.brush_reset_toast),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** Check if a brush preset has modified parameters */
    internal fun PaintViewModel.isBrushModified(id: String): Boolean {
        @Suppress("UNUSED_VARIABLE")
        val epoch = brushModifiedEpoch
        return BrushStoreVm.isModified(this, id) ?: false
    }

    /** Capture initial parameter snapshot when opening Brush Studio */
    internal fun PaintViewModel.captureBrushStudioSnapshot() {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return
        brushStudioInitial = BrushStoreVm.captureStudio(preset.id, BrushStoreVm.VmFlushHost(this), toolBrushStates, memoryToolId())
        brushStudioInitialKppBytes = null
    }

    /** Check if the current brush has changes compared to the studio initial snapshot */
    internal fun PaintViewModel.hasBrushStudioChanges(): Boolean {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return false
        val initial = brushStudioInitial ?: return false
        return BrushStoreVm.studioChanged(preset.id, initial, toolBrushStates)
    }

    /** Revert parameters (overrides + this tool's size/opacity/flow memory) to the state when Brush Studio was entered */
    internal fun PaintViewModel.revertBrushStudioSnapshot(): Boolean {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return false
        val initial = brushStudioInitial
        if (initial != null) {
            BrushStoreVm.revertStudio(preset.id, initial, toolBrushStates)?.let {
                toolBrushStates = it
                persistToolBrushStates()
            }
        }
        brushDynamicOptions.clear()
        selectBrushPreset(preset.index)
        return true
    }

    /** Reset a brush preset back to factory default parameters */
    internal fun PaintViewModel.resetBrushPresetToDefault(presetId: String) {
        BrushStoreVm.reset(this, presetId)
        android.widget.Toast.makeText(
            appContext,
            appContext.getString(R.string.brush_reset_toast),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** Share a brush preset (.kpp or .bundle if it has custom tip) via system share sheet */
    internal fun PaintViewModel.fetchBrushOutlinePolygons() {
        // 原生 currentBrushOutline 与 loadBrushPreset 没有共用的锁，预设在渲染线程上整体换掉：
        // 轮廓查询也只能在渲染线程（同一个单线程队列，和加载天然串行）。结果再回主线程。
        val render = renderHandler
        val postRender: ((() -> Unit) -> Unit)? = render?.let { h -> { op -> h.post { op() } } }
        BrushOutlineFetch.fetch(
            onRenderThread = render != null && android.os.Looper.myLooper() === render.looper,
            postRender = postRender,
            postMain = { op -> mainHandler.post { op() } },
            query = { ReverieCoreBridge.getCurrentBrushOutline() },
            apply = { brushOutlinePolygons = it },
        )
    }

    internal fun resolvePresetSize(engineSize: Double, savedSize: Double?): Double {
        if (engineSize.isFinite() && engineSize > 1.0) return engineSize
        if (savedSize != null && savedSize.isFinite() && savedSize > 1.0) return savedSize
        return if (engineSize.isFinite()) engineSize else 20.0
    }

    internal fun PaintViewModel.selectBrushPreset(index: Int, recordToToolState: Boolean = true) {
        if (isEraserMode) {
            setEraserModeInternal(false, showToast = false)
        }
        // brushPresetIndex and every native API are indexed by the NATIVE
        // preset table (filename-sorted). Resolve by BrushPresetInfo.index,
        // never by list position: the Compose list may be reordered by the
        // user's brushOrder and diverge from native indices.
        val preset = brushPresets.firstOrNull { it.index == index }
        if (preset != null) {
            recordRecentBrush(preset.id)
            recordBrushUsageStats(preset.name)
        }
        val isBuiltIn = preset?.isBuiltIn == true
        val isEraserPreset = preset?.group == "橡皮擦" || preset?.name?.startsWith("a)_Eraser", ignoreCase = true) == true || preset?.name?.contains("Eraser", ignoreCase = true) == true

        pendingKppReloadJob?.cancel()
        pendingKppReloadJob = null

        brushPresetIndex = index
        // #136 A：重载预设表后的内部重选不是用户选笔，不能写工具槽，否则会把表里第 0 支笔
        // （或任何回退项）记进「橡皮」槽，下次切橡皮就变成那支笔。
        if (recordToToolState) updateCurrentToolBrushState { it.copy(presetIndex = index) }

        com.reverie.paint.core.brushstore.BrushApply.select(this, index, preset)
    }

    /**
     * 还原临时覆盖（如物理橡皮末端 TOOL_TYPE_ERASER 或手写笔侧键长按临时擦除）结束后的引擎笔刷状态。
     * 若在临时覆盖期间切换了预设 ([reloadPreset] == true)，则完整重新加载原预设及其所有定制动力学与属性，
     * 避免笔刷引擎 (如 ColorSmudge) 被残留的橡皮预设 (如 PaintBrush) 污染覆写。
     */
    internal fun PaintViewModel.restoreCurrentBrushToNative(reloadPreset: Boolean = false) {
        com.reverie.paint.core.brushstore.BrushApply.restore(this, reloadPreset)
    }

    internal fun PaintViewModel.updateCurrentToolBrushState(updater: (PaintViewModel.ToolBrushState) -> PaintViewModel.ToolBrushState) {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        val targetToolId = if (t == com.reverie.paint.model.Tool.BRUSH ||
            t == com.reverie.paint.model.Tool.ERASER ||
            t == com.reverie.paint.model.Tool.SMUDGE
        ) {
            currentToolId
        } else {
            if (lastDrawingToolId.isNotBlank()) lastDrawingToolId else "brush"
        }
        val state = toolBrushStates[targetToolId] ?: PaintViewModel.ToolBrushState()
        val next = updater(state)
        val p = brushPresets.firstOrNull { it.index == next.presetIndex }
        val id = p?.id ?: next.presetId
        val synced = if (next.presetIndex != state.presetIndex || next.presetId.isBlank()) {
            next.copy(presetId = id)
        } else {
            next
        }
        toolBrushStates = toolBrushStates.toMutableMap().apply { put(targetToolId, synced) }
        schedulePersistToolBrushStates()
    }

    /** 大小/不透明度/流量记忆写入的工具槽：绘制类工具用自己，其余（吸管等临时工具）回落到最近的绘制类工具 */
    internal fun PaintViewModel.memoryToolId(): String {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        return if (t == com.reverie.paint.model.Tool.BRUSH ||
            t == com.reverie.paint.model.Tool.ERASER ||
            t == com.reverie.paint.model.Tool.SMUDGE
        ) {
            currentToolId
        } else {
            lastDrawingToolId
        }
    }

    /** Krita saved{Mode}Size 语义: 把当前 size/opacity/flow 快照进
     *  (当前工具 × 当前预设) 的记忆, 让每个工具各自记住自己的数值。 */
    internal fun PaintViewModel.rememberToolParamSnapshot() {
        val targetToolId = memoryToolId()
        val id = brushPresets.firstOrNull { it.index == brushPresetIndex }?.id ?: return
        val currentMap = toolBrushStates[targetToolId]
            ?: PaintViewModel.ToolBrushState(presetIndex = brushPresetIndex, presetId = id)
        toolBrushStates = toolBrushStates.toMutableMap().apply {
            put(
                targetToolId,
                currentMap.copy(
                    presetIndex = if (currentMap.presetIndex >= 0) currentMap.presetIndex else brushPresetIndex,
                    presetId = currentMap.presetId.ifBlank { id },
                    paramMemory = currentMap.paramMemory.toMutableMap().apply {
                        if (id.isNotBlank()) {
                            put(id, listOf(brushSize, brushOpacity, brushFlow))
                        }
                    }
                )
            )
        }
        schedulePersistToolBrushStates()
    }

    internal fun PaintViewModel.updateBrushPanelCategory(cat: String) {
        brushPanelSelectedCategory = cat
        updateCurrentToolBrushState { it.copy(category = cat) }
        persistBrushPanelState()
    }

    internal fun PaintViewModel.updateBrushSize(v: Double, commit: Boolean = true) {
        val minL = brushMinSizeLimit.coerceAtLeast(0.5)
        val maxL = effectiveBrushMaxSize.coerceAtLeast(minL)
        val clamped = v.coerceIn(minL, maxL)
        brushParams = brushParams.copy(size = clamped)
        if (commit) {
            rememberToolParamSnapshot()
        }
        com.reverie.paint.core.brushstore.BrushApply.applyLive(com.reverie.paint.core.brushstore.ControlId.SIZE, clamped)
    }

    internal fun PaintViewModel.updateBrushColor(c: String) {
        brushColor = c
        if (colorPanelTab != 2) {
            colorSphereBaseHex = c
        }
        runCore(render = false) { ReverieCoreBridge.setBrushColor(c) }
        if (isAppContextReady()) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("brushColor", c).apply()
        }
    }

    internal fun PaintViewModel.updateBrushSecondaryColor(c: String) {
        brushSecondaryColor = c
        if (isAppContextReady()) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("brushSecondaryColor", c).apply()
        }
        runCore(render = false) { ReverieCoreBridge.setBrushSecondaryColor(c) }
    }


    internal fun PaintViewModel.updateBrushOpacity(v: Double, commit: Boolean = true) {
        brushParams = brushParams.copy(opacity = v)
        if (commit) {
            rememberToolParamSnapshot()
        }
        com.reverie.paint.core.brushstore.BrushApply.applyLive(com.reverie.paint.core.brushstore.ControlId.OPACITY, v)
    }

    /** Reload all brush presets from filesDir and update Compose state */
    internal fun PaintViewModel.reloadBrushPresets(selectName: String? = null) {
        val dir = ResourceDirs.presets(appContext.filesDir)
        val brushDir = ResourceDirs.tips(appContext.filesDir)
        val builtInNames = getBuiltInBrushNames()
        val list = ArrayList<BrushPresetInfo>()
        runCore(after = {
            // Every reload re-assigns native indices, so keep the selection
            // by NAME; re-apply the persisted user order (reorderBrush only
            // stores it — the native table stays filename-sorted). Names not
            // present in brushOrder keep their native order, appended after.
            val keepName = selectName
                ?: brushPresets.firstOrNull { it.index == brushPresetIndex }?.name
            val keepId = if (selectName == null) brushPresets.firstOrNull { it.index == brushPresetIndex }?.id else null
            val rank = brushOrder.withIndex().associate { it.value to it.index }
            val ordered =
                if (rank.isEmpty()) list.toList()
                else list.toList().sortedBy { rank[it.id] ?: (rank.size + it.index) }
            brushPresets = ordered
            brushPresetsLoaded = true
            if (ordered.isNotEmpty()) {
                // 选中的笔按 id 找回（重命名后 id 不变）；调用方只给了新名字时（创建/导入/改名）才按显示名找
                val target = keepId?.let { id -> ordered.firstOrNull { it.id == id } }
                    ?: keepName?.let { n -> ordered.firstOrNull { it.name == n } }
                when {
                    // 同一支笔、索引变了：正常重选，工具槽跟着刷新索引（名字不变）
                    target != null -> selectBrushPreset(target.index)
                    // 首次启动、本来就没有"上一个选择"：选第一支
                    keepName == null -> selectBrushPreset(ordered[0].index)
                    // 上一个选择已不存在（预设被删除）：给画布一支能画的笔，但不写工具槽。
                    // #136 A：这里以前无条件 selectBrushPreset(ordered[0])，会把"表里第 0 支笔"
                    // 记进当前工具槽——删完预设后切橡皮就变成那支笔。
                    else -> selectBrushPreset(ordered[0].index, recordToToolState = false)
                }
            }
        }) {
            val patternDir = ResourceDirs.patterns(appContext.filesDir)
            if (patternDir.exists()) {
                ReverieCoreBridge.loadPatternResources(patternDir.absolutePath)
            }
            ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
            val n = ReverieCoreBridge.loadBrushPresetsFromDir(dir.absolutePath)
            val groupsSnapshot = userBrushGroups
            list.clear()
            list.addAll(scanNativePresets(n, builtInNames, groupsSnapshot))
        }
    }

    /** 复制指定笔刷 (复制出的笔刷不受内置作者锁定及不可删除限制) */
    internal fun PaintViewModel.duplicateBrushPreset(presetIndex: Int, newName: String? = null): Boolean {
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) return false
        // Native-table index (BrushPresetInfo.index), not list position.
        val src = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        val dir = ResourceDirs.presets(appContext.filesDir)
        // W-08：复制名撞已有预设时加序号，不再覆盖（旧实现 copyTo(overwrite = true) 可覆盖内置）
        val cleanName = com.reverie.paint.core.brushstore.BrushImportPipeline.uniqueCopyName(
            newName?.trim()?.ifEmpty { null } ?: "${src.name} 副本",
            com.reverie.paint.core.brushstore.BrushImportPipeline.takenNames(dir),
        )
        val managedSrc = com.reverie.paint.core.brushstore.BrushStoreBootstrap.managedEntry(src.id)
            ?: return false
        // 快照 = 源快照，覆盖 = 源覆盖的拷贝（.kpp / .myb 同一条路）
        val srcAuthor = com.reverie.paint.core.brushstore.PresetReadback.withAppControls(
            com.reverie.paint.core.brushstore.PresetReadback.baseline(managedSrc.id),
            com.reverie.paint.core.brushstore.BrushOverrideStore.get(managedSrc.id).controls,
        ).author
        val newId = com.reverie.paint.core.brushstore.BrushImportPipeline.duplicate(
            managedSrc.id,
            cleanName,
            dir,
            author = if (src.isBuiltIn) "Krita (副本)" else srcAuthor,
        ) ?: return false
        // 副本跟随源笔所在分组：自建分组按映射；内置笔没有映射，用它当前显示的（推断）分组，
        // 避免用户给副本改名（newName）后按名字推断落到别的分组
        userBrushGroups = userBrushGroups + (newId to (userBrushGroups[src.id] ?: src.group))
        saveBrushGroups()
        reloadBrushPresets(selectName = cleanName)
        return true
    }

    /** 删除指定笔刷 (内置笔刷不可删除) */
    internal fun PaintViewModel.deleteBrushPreset(presetIndex: Int): Boolean {
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) return false
        // Native-table index (BrushPresetInfo.index), not list position.
        val preset = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷禁止删除
        }
        purgeBrushPresets(listOf(preset))
        reloadBrushPresets()
        return true
    }

    /**
     * 删除自定义笔刷的唯一实现（单个 / 批量 / 删组共用）：磁盘文件与库里的 identity+快照+覆盖+缩略图经
     * [com.reverie.paint.core.brushstore.BrushImportPipeline.delete]，再清按工具记忆、分组、收藏、最近、排序。调用方负责挡内置并 reload。
     */
    private fun PaintViewModel.purgeBrushPresets(targets: List<BrushPresetInfo>) {
        if (targets.isEmpty()) return
        val dir = ResourceDirs.presets(appContext.filesDir)
        val deletedIds = targets.mapNotNull {
            com.reverie.paint.core.brushstore.BrushImportPipeline.delete(it.id, dir)
        }.toSet()
        if (deletedIds.isNotEmpty()) {
            // W-08：identity+快照+覆盖一起删，否则下次启动会被 materializeAll 复活
            toolBrushStates = toolBrushStates.mapValues { (_, s) ->
                val nextS = if (s.presetId in deletedIds) s.copy(presetId = "", presetIndex = -1) else s
                nextS.copy(paramMemory = nextS.paramMemory - deletedIds)
            }
            persistToolBrushStates()
        }
        userBrushGroups = userBrushGroups - deletedIds
        favoriteBrushIds = favoriteBrushIds - deletedIds
        recentBrushIds = recentBrushIds.filter { it !in deletedIds }
        quickBrushOrder = quickBrushOrder.filter { it !in deletedIds }
        brushOrder = brushOrder.filter { it !in deletedIds }
        persistBrushPanelState()
        saveBrushOrder()
        saveBrushGroups()
    }

    /** 删除笔刷组 (内置组不可删除)，支持联动物理删除组内所有自定义笔刷 */
    internal fun PaintViewModel.deleteBrushGroup(name: String, deletePresets: Boolean = true): Boolean {
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) return false
        if (isBuiltInGroup(name)) return false // 内置组禁止删除
        val presetsInGroup = brushPresets.filter { it.group == name }

        if (deletePresets) purgeBrushPresets(presetsInGroup.filter { !it.isBuiltIn })

        // 解除该分组下残留预设（如内置笔刷，或未勾选删除笔刷时的自定义笔刷）与当前组的映射
        userBrushGroups = userBrushGroups.filterValues { it != name }
        customBrushGroups = customBrushGroups.filter { it != name }
        categoryOrder = categoryOrder.filter { it != name }
        saveBrushGroups()
        saveCategoryOrder()
        reloadBrushPresets()
        return true
    }

    /** 批量删除笔刷 (内置只读笔刷会自动跳过并保留) */
    internal fun PaintViewModel.deleteBrushPresetsBatch(presetNames: List<String>): Int {
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) return 0
        val targets = brushPresets.filter { it.name in presetNames && !it.isBuiltIn }
        if (targets.isEmpty()) return 0

        purgeBrushPresets(targets)

        reloadBrushPresets()
        return targets.size
    }

    /** 批量移动笔刷到指定分组 */
    internal fun PaintViewModel.moveBrushPresetsBatch(presetNames: List<String>, targetGroup: String) {
        val cleanGroup = targetGroup.trim()
        if (presetNames.isEmpty() || cleanGroup.isBlank()) return
        var updated = userBrushGroups
        for (name in presetNames) {
            val id = presetIdOf(name) ?: continue
            updated = updated + (id to cleanGroup)
        }
        userBrushGroups = updated
        if (!customBrushGroups.contains(cleanGroup) && !BUILT_IN_BRUSH_GROUPS.contains(cleanGroup)) {
            customBrushGroups = customBrushGroups + cleanGroup
        }
        saveBrushGroups()
        reloadBrushPresets()
    }

    /** 创建全新自定义笔刷 (默认基于 Basic-1) */
    internal fun PaintViewModel.createNewBrushPreset(
        name: String,
        group: String = "自定义",
        basePresetIndex: Int = -1,
        tipAsset: String = "",
        paintOpId: String = "defaultpaintop",
        author: String = "原创创作者",
        authorLocked: Boolean = false,
        // 导入口自己统一重载一次；这里再重载会让同一支笔连载两次（v53）
        reload: Boolean = true,
        spacing: Double? = null,
    ): Boolean {
        val dir = ResourceDirs.presets(appContext.filesDir)
        if (!dir.exists()) dir.mkdirs()
        // W-08：撞名加序号，不再覆盖已有（含内置）预设
        val cleanName = com.reverie.paint.core.brushstore.BrushImportPipeline.uniqueCopyName(
            name.trim().ifEmpty { "自定义笔刷_${(System.currentTimeMillis() % 10000)}" },
            com.reverie.paint.core.brushstore.BrushImportPipeline.takenNames(dir),
        )

        // 默认基准预设选择 b)_Basic-1
        val base = brushPresets.firstOrNull { it.index == basePresetIndex }
            ?: brushPresets.firstOrNull { it.name == "b)_Basic-1" || it.name.contains("Basic-1") }
            ?: brushPresets.firstOrNull()
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) {
            return false
        }
        val baseId = com.reverie.paint.core.brushstore.BrushImportPipeline.resolveBaseId(base?.id)
            ?: return false
        val newId = com.reverie.paint.core.brushstore.BrushImportPipeline.create(
            baseId, cleanName, dir, tipAsset, paintOpId, author, authorLocked, spacing,
        ) ?: return false

        userBrushGroups = userBrushGroups + (newId to group)
        if (!customBrushGroups.contains(group) && group != "全部") {
            customBrushGroups = customBrushGroups + group
        }
        saveBrushGroups()
        if (reload) reloadBrushPresets(selectName = cleanName)
        return true
    }

    data class BrushImportResult(
        val success: Boolean,
        val presetName: String? = null,
        val groupName: String? = null,
        val count: Int = 1,
    )

    /** 画笔包超出内存/解析预算时的统一提示 (IO 线程调用, 内部切回主线程) */
    private suspend fun PaintViewModel.notifyImportTooLarge() {
        withContext(Dispatchers.Main) {
            showActionToast(R.string.brush_import_too_large)
        }
    }

    internal fun cleanupStaleImportTempFiles(cacheDir: File) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 30L * 60L * 1000L
            cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("temp_import_") && f.lastModified() in 1 until cutoff) {
                    f.delete()
                }
            }
        }
    }

    /**
     * ABR 零拷贝数据源 RAII 模板：
     * 1. 快路径：若 Uri 对应本地常规文件 (S_ISREG)，直接通过 ParcelFileDescriptor 的
     *    FileChannel 执行 Mmap / PagedChannel 读取，0 磁盘拷贝、0 临时文件；
     * 2. 慢路径（云盘/管道流）：预检 cacheDir 可用空间后，以 64KB 缓冲分块流式落盘至临时文件，
     *    并在 finally 中严格按 [AbrSource.close (unmap) -> tempFile.delete()] 顺序闭环释放。
     */
    private inline fun <T> PaintViewModel.withZeroCopyAbrSource(
        uri: android.net.Uri,
        expectedSize: Long,
        block: (AbrSource) -> T,
    ): T? {
        val resolver = appContext.contentResolver

        // 0. 清理陈旧临时文件
        cleanupStaleImportTempFiles(appContext.cacheDir)

        // 1. 本地 FD 零落盘快路径 (Fast-Path)
        var fastPathSource: AbrSource? = null
        var pfdToClose: android.os.ParcelFileDescriptor? = null
        var fisToClose: java.io.FileInputStream? = null
        try {
            val pfd = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfdToClose = pfd
                val stat = android.system.Os.fstat(pfd.fileDescriptor)
                if (android.system.OsConstants.S_ISREG(stat.st_mode) && stat.st_size >= 4L) {
                    val fis = java.io.FileInputStream(pfd.fileDescriptor)
                    fisToClose = fis
                    // 4 字节预读探针：验证底层 FUSE / DocumentsProvider FD 真正支持 positional read / seekable
                    val probeBuf = java.nio.ByteBuffer.allocate(4)
                    val readBytes = fis.channel.read(probeBuf, 0L)
                    if (readBytes >= 4) {
                        fastPathSource = AbrSource.fromChannel(fis.channel)
                    } else {
                        // 假 regular FD 或损坏通道，安全释放并降级至 cacheDir 慢路径
                        runCatching { fis.close() }
                        fisToClose = null
                        pfd.close()
                        pfdToClose = null
                    }
                } else {
                    pfd.close()
                    pfdToClose = null
                }
            }
        } catch (_: Throwable) {
            runCatching { fisToClose?.close() }
            fisToClose = null
            runCatching { pfdToClose?.close() }
            pfdToClose = null
            fastPathSource = null
        }

        if (fastPathSource != null) {
            return try {
                fastPathSource.use { source ->
                    block(source)
                }
            } finally {
                runCatching { fisToClose?.close() }
                runCatching { pfdToClose?.close() }
            }
        }

        // 2. 兜底路径：流式写入 cacheDir 临时文件
        val safetyMarginBytes = 16L * 1024L * 1024L // 预留 16MB 安全余量
        if (expectedSize > 0L) {
            val usable = appContext.cacheDir.usableSpace
            if (usable < expectedSize + safetyMarginBytes) {
                throw IOException("ENOSPC: cacheDir usable=$usable, required=${expectedSize + safetyMarginBytes}")
            }
        } else {
            if (appContext.cacheDir.usableSpace < safetyMarginBytes) {
                throw IOException("ENOSPC: cacheDir exhausted")
            }
        }

        val tempAbr = File(
            appContext.cacheDir,
            "temp_import_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.abr",
        )
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { inStream ->
                java.io.FileOutputStream(tempAbr).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    while (true) {
                        val read = inStream.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        if (expectedSize <= 0L && (bytesCopied shr 24) != ((bytesCopied + read) shr 24)) {
                            if (appContext.cacheDir.usableSpace < safetyMarginBytes) {
                                throw IOException("ENOSPC: cacheDir exhausted during streaming")
                            }
                        }
                        fos.write(buf, 0, read)
                        bytesCopied += read
                    }
                    fos.fd.sync()
                }
            }
            if (!tempAbr.isFile || tempAbr.length() < 4L) return null

            AbrSource.fromFile(tempAbr).use { source ->
                block(source)
            }
        } finally {
            if (tempAbr.exists() && !tempAbr.delete()) {
                tempAbr.deleteOnExit()
            }
        }
    }

    internal suspend fun PaintViewModel.importSingleBrushInternal(
        uri: android.net.Uri,
        targetGroup: String? = null,
    ): BrushImportResult {
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) {
            return BrushImportResult(success = false)
        }
        return try {
            val resolver = appContext.contentResolver
            // 同时取 DISPLAY_NAME 与 SIZE: 后者用于在读取前就挡住超大画笔包 (整包进内存)
            val (queriedName, queriedSize) = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                        name to size
                    } else {
                        null to -1L
                    }
                }
            }.getOrNull() ?: (null to -1L)
            val filename = queriedName ?: uri.lastPathSegment?.substringAfterLast("/")
                ?: "import_${System.currentTimeMillis()}"
            val fileSize = queriedSize

            val presetDir = ResourceDirs.presets(appContext.filesDir)
            if (!presetDir.exists()) presetDir.mkdirs()
            val brushDir = ResourceDirs.tips(appContext.filesDir)
            if (!brushDir.exists()) brushDir.mkdirs()
            val patternDir = ResourceDirs.patterns(appContext.filesDir)
            if (!patternDir.exists()) patternDir.mkdirs()

            val chosenGroup = targetGroup?.trim()?.takeIf { it.isNotEmpty() }

            if (com.reverie.paint.core.brushstore.BrushImportPipeline.isPreset(filename)) {
                @Suppress("DEPRECATION")
                if (fileSize > AbrParser.MAX_IMPORT_BYTES) {
                    notifyImportTooLarge()
                    return BrushImportResult(success = false)
                }
                // W-08：经统一管线落盘——撞名改名（绝不覆盖内置/已有）、建快照
                val stream = resolver.openInputStream(uri) ?: return BrushImportResult(success = false)
                val bytes = stream.use { readImportBytes(it, fileSize) }
                if (bytes == null) {
                    notifyImportTooLarge()
                    return BrushImportResult(success = false)
                }
                val imported = com.reverie.paint.core.brushstore.BrushImportPipeline
                    .importFile(filename, bytes, presetDir)
                    .imported.firstOrNull() ?: return BrushImportResult(success = false)
                val presetName = imported.name
                val group = chosenGroup ?: "导入"
                withContext(Dispatchers.Main) {
                    imported.id?.let { userBrushGroups = userBrushGroups + (it to group) }
                    if (!customBrushGroups.contains(group) && group !in listOf("全部", "常用", "最近")) {
                        customBrushGroups = customBrushGroups + group
                    }
                    saveBrushGroups()
                }
                BrushImportResult(success = true, presetName = presetName, groupName = group)
            } else if (filename.endsWith(".abr", ignoreCase = true)) {
                val packBaseName = filename.substringBeforeLast(".").trim().ifBlank { "ABR" }
                val targetGroupName = chosenGroup ?: packBaseName

                val safeGroupPrefix = packBaseName.replace(Regex("""[^\w\u4e00-\u9fa5]"""), "_")
                val tipFileNameMap = mutableMapOf<Int, String>()
                val tipUuidToFileName = mutableMapOf<String, String>()
                val tipShaMap = mutableMapOf<String, String>()
                val patternRefs = LinkedHashMap<String, ResourceStore.Ref>()
                var patternIdx = 0
                val resourceStore = BrushStoreBootstrap.resourceStore(appContext.filesDir)

                val parseResult = try {
                    withZeroCopyAbrSource(uri, expectedSize = fileSize) { source ->
                        AbrParser.parse(
                            source,
                            basePackName = targetGroupName,
                            onPatternDecoded = { pattern ->
                                runCatching {
                                    val idx = patternIdx++
                                    val token = pattern.uuid.ifBlank { idx.toString() }
                                        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                                        .ifBlank { idx.toString() }
                                    val ref = resourceStore.register(
                                        pattern.pngBytes,
                                        ResourceStore.Kind.PATTERN,
                                        "abr_pat_${safeGroupPrefix}_$token.png",
                                    )
                                    if (pattern.uuid.isNotEmpty()) patternRefs[pattern.uuid] = ref
                                }.onFailure { t ->
                                    BrushDiagnostics.log(
                                        "abr_pattern_register_failed",
                                        "uuid" to pattern.uuid,
                                        "err" to t.javaClass.simpleName,
                                    )
                                }
                            },
                            onTipDecoded = { decodedTip ->
                                // 1. Tip SHA-256 去重: 若该笔尖像素已在当前包存在，直接复用文件
                                val sha = decodedTip.sha256
                                val existingTipName = if (sha.isNotBlank()) tipShaMap[sha] else null
                                val tipFileName = if (existingTipName != null) {
                                    existingTipName
                                } else {
                                    val tipBytes = AbrParser.encodeTipPng(decodedTip)
                                    val registered = resourceStore.register(
                                        tipBytes,
                                        ResourceStore.Kind.TIP,
                                        "${safeGroupPrefix}_tip_${decodedTip.index}.png",
                                    )
                                    if (sha.isNotBlank()) tipShaMap[sha] = registered.file
                                    registered.file
                                }
                                tipFileNameMap[decodedTip.index] = tipFileName
                                tipUuidToFileName[decodedTip.uuid] = tipFileName
                            },
                        )
                    }
                } catch (e: IOException) {
                    BrushDiagnostics.log(
                        "import_io_error",
                        "file" to filename,
                        "msg" to e.message,
                    )
                    return BrushImportResult(success = false)
                }

                if (parseResult?.tooLarge == true) {
                    notifyImportTooLarge()
                    return BrushImportResult(success = false)
                }
                if (parseResult == null || (parseResult.tips.isEmpty() && parseResult.presets.isEmpty())) {
                    return BrushImportResult(success = false)
                }

                // 非流式旧调用路径兜底：若 parseResult.patterns 不为空，用 registerPatterns 补齐未流式注册的图案
                if (parseResult.patterns.isNotEmpty()) {
                    val fallback = AbrPresetConverter.registerPatterns(
                        parseResult.patterns,
                        resourceStore,
                        safeGroupPrefix,
                    )
                    for ((k, v) in fallback) {
                        if (k !in patternRefs) {
                            patternRefs[k] = v
                        }
                    }
                }

                val tipsByUuid = parseResult.tips.associateBy { it.uuid }
                val tipsByIndex = parseResult.tips.associateBy { it.index }

                // 2. Export preset .kpp files to filesDir/paintoppresets/
                val totalPresets = parseResult.presets.size
                withContext(Dispatchers.Main) {
                    brushImportProgress = Pair(0, totalPresets)
                }

                val importedPresetNames = mutableListOf<String>()
                val importedPresetIds = mutableListOf<String>()
                // W-08/W-09：撞名集合 = 内置 ∪ 已注册 ∪ 磁盘 .kpp/.myb，大小写不敏感
                val takenPresetNames = com.reverie.paint.core.brushstore.BrushImportPipeline.takenNames(presetDir)

                for ((idx, preset) in parseResult.presets.withIndex()) {
                    // Computed presets must NOT bind a sampled tip; see AbrParser.matchTipForPreset.
                    val matchedTip = AbrParser.matchTipForPreset(
                        preset, tipsByUuid, tipsByIndex, parseResult.tips,
                    )
                    val matchedTipFileName = (preset.tipUuid?.let { tipUuidToFileName[it] })
                        ?: (matchedTip?.let { tipFileNameMap[it.index] })
                        ?: ""

                    val rawName = abrPresetName(preset.name, targetGroupName, idx)
                    val safeName = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    // 撞名规则只有一份：kpp / bundle 导入同款 uniqueDisplayName（"名字 (包名)"，仍撞再加序号）
                    val candidateName = com.reverie.paint.core.brushstore.BrushIdentity.uniqueDisplayName(safeName, packBaseName, takenPresetNames)
                    takenPresetNames += candidateName
                    importedPresetNames.add(candidateName)

                    val converted = AbrPresetConverter.convert(
                        preset, candidateName, matchedTip, matchedTipFileName, tipUuidToFileName, packBaseName,
                        patternRefs = patternRefs,
                        policy = abrBlendPolicy,
                    )
                    // W-09：快照 = 转换后的 kpp，source.json 带 abrParams + 转换器版本；重置回到 baseline
                    val baseline = com.reverie.paint.core.brushstore.PresetReadback.baselineJson(converted.bp)
                    val abrId = com.reverie.paint.core.brushstore.BrushImportPipeline.importAbr(
                        candidateName, converted.kppBytes, AbrPresetConverter.abrParamsJson(preset),
                        AbrPresetConverter.CONVERTER_VERSION, filename, presetDir, baseline,
                        blendPolicy = abrBlendPolicy.name,
                    )

                    importedPresetIds.add(abrId)
                    withContext(Dispatchers.Main) {
                        brushImportProgress = Pair(idx + 1, totalPresets)
                    }
                }

                // 3. Update groups and state
                var newCustomGroups = customBrushGroups
                if (!newCustomGroups.contains(targetGroupName) && targetGroupName != "全部") {
                    newCustomGroups = newCustomGroups + targetGroupName
                }
                var newUserGroups = userBrushGroups
                for (pId in importedPresetIds) {
                    newUserGroups = newUserGroups + (pId to targetGroupName)
                }

                withContext(Dispatchers.Main) {
                    customBrushGroups = newCustomGroups
                    userBrushGroups = newUserGroups
                    saveBrushGroups()
                    brushImportProgress = null
                    // 不在这里重载：importBrushFromUri / importBrushesFromUris 拿到结果后统一重载一次
                }

                BrushImportResult(
                    success = true,
                    presetName = importedPresetNames.firstOrNull(),
                    groupName = targetGroupName,
                    count = importedPresetNames.size,
                )
            } else if (filename.endsWith(".png", true) || filename.endsWith(".gbr", true) || filename.endsWith(".gih", true)) {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return BrushImportResult(success = false)
                val registered = com.reverie.paint.core.brushstore.BrushStoreBootstrap.resourceStore(appContext.filesDir).register(
                    bytes,
                    com.reverie.paint.core.brushstore.ResourceStore.Kind.TIP,
                    filename,
                )
                // 撞名序号在这里定下来：返回/选中的名字必须是真正建出来的那个，不能是撞名前的原名（会选中旧笔刷）
                val presetName = com.reverie.paint.core.brushstore.BrushImportPipeline.uniqueCopyName(
                    filename.substringBeforeLast(".").trim().ifEmpty { "导入笔刷" },
                    com.reverie.paint.core.brushstore.BrushImportPipeline.takenNames(presetDir),
                )
                val group = chosenGroup ?: "导入"
                val created = withContext(Dispatchers.Main) {
                    createNewBrushPreset(
                        name = presetName,
                        group = group,
                        tipAsset = registered.file,
                        author = "外部创作者 (分享)",
                        authorLocked = true,
                        reload = false,
                        // gbr/gih 头里的间距（Krita KisGbrBrush 加载时 setSpacing）；png 没有
                        spacing = com.reverie.paint.core.brushstore.GimpBrushHeader.spacing(bytes, filename),
                    )
                }
                BrushImportResult(success = created, presetName = presetName, groupName = group)
            } else if (filename.endsWith(".bundle", true) || filename.endsWith(".zip", true)) {
                val defaultGroupName = chosenGroup ?: filename.substringBeforeLast(".")
                    .removeSuffix(".bundle")
                    .removeSuffix(".zip")
                    .trim()
                    .ifBlank { "导入" }

                // 统一管线：manifest 标签、paintoppresets/ 与根目录的 .kpp/.myb、嵌套 bundle 都在 BrushImportPipeline 里解析；
                // 预设撞名改名、资源同名异内容改名并改写引用，绝不覆盖已有文件
                val tempArchive = File(appContext.cacheDir, "temp_import_${System.currentTimeMillis()}.bundle")
                val result = try {
                    val input = resolver.openInputStream(uri) ?: return BrushImportResult(success = false)
                    tempArchive.outputStream().buffered().use { out ->
                        input.buffered().use { it.copyTo(out, bufferSize = 64 * 1024) }
                    }
                    com.reverie.paint.core.brushstore.BrushImportPipeline.importArchive(
                        tempArchive, filename, presetDir, brushDir, patternDir,
                    )
                } catch (oom: OutOfMemoryError) {
                    com.reverie.paint.core.brushstore.BrushDiagnostics.log("import_oom", "file" to filename, "msg" to oom.message)
                    return BrushImportResult(success = false)
                } catch (e: Throwable) {
                    com.reverie.paint.core.brushstore.BrushDiagnostics.log("import_error", "file" to filename, "msg" to e.message)
                    return BrushImportResult(success = false)
                } finally {
                    tempArchive.delete()
                }
                val importedPresets = result.imported.map { it.name }
                val presetToTagMap = result.imported.mapNotNull { imp -> imp.tag?.let { imp.name to it } }.toMap()
                val presetToIdMap = result.imported.mapNotNull { imp -> imp.id?.let { imp.name to it } }.toMap()

                if (importedPresets.isNotEmpty()) {
                    var newCustomGroups = customBrushGroups
                    var newUserGroups = userBrushGroups
                    var targetFinalGroup = defaultGroupName

                    for (preset in importedPresets) {
                        val rawTag = presetToTagMap[preset]?.trim()
                        val validTag = rawTag?.takeIf {
                            it.isNotBlank() && it !in listOf("默认", "Default", "default", "全部", "All", "all", "常用", "Favorites", "favorites", "最近")
                        }
                        val tag = chosenGroup ?: validTag ?: defaultGroupName
                        if (!newCustomGroups.contains(tag) && tag !in listOf("全部", "常用", "最近")) {
                            newCustomGroups = newCustomGroups + tag
                        }
                        presetToIdMap[preset]?.let { newUserGroups = newUserGroups + (it to tag) }
                        targetFinalGroup = tag
                    }

                    withContext(Dispatchers.Main) {
                        customBrushGroups = newCustomGroups
                        userBrushGroups = newUserGroups
                        saveBrushGroups()
                    }

                    BrushImportResult(
                        success = true,
                        presetName = importedPresets.firstOrNull(),
                        groupName = targetFinalGroup,
                    )
                } else {
                    BrushImportResult(success = false)
                }
            } else {
                BrushImportResult(success = false)
            }
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importSingleBrushInternal failed", e)
            BrushImportResult(success = false)
        }
    }

    /**
     * ABR 预设的落盘名。
     *
     * Photoshop 给很多 ABR 包的笔刷名就是纯数字 ("1"、"2"、"3"…)，Krita 自己的
     * 资源名也是把包名拼在前面 (`brushes_by_mar_ka_d338ela_2`)。裸数字名有两个
     * 实际问题: 用户在笔刷列表里完全认不出这是哪个包; 再导入第二个同命名的包
     * 会走重名兜底变成 "1_2"、"2_2"，越导越乱。
     *
     * 所以纯数字名补包名前缀，自带描述的名字（"Round 10 Hardness 80%"）保持原样。
     */
    internal fun abrPresetName(rawName: String, packBaseName: String, index: Int): String {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) return "$packBaseName ${index + 1}"
        val hasLetter = trimmed.any { it.isLetter() }
        return if (hasLetter) trimmed else "${packBaseName}_$trimmed"
    }

    /** 导入外部笔刷文件 (.kpp, .bundle, .gbr, .png, .abr, .zip) */
