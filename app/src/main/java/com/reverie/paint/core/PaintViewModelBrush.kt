/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

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
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

    internal fun PaintViewModel.prefs() =
        appContext.getSharedPreferences("brush_groups", android.content.Context.MODE_PRIVATE)

    internal fun PaintViewModel.loadBrushGroups() {
        val groupsJson = prefs().getString("user_groups", null)
        val customsJson = prefs().getString("custom_groups", null)
        userBrushGroups = if (groupsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(groupsJson)
                (0 until arr.length()).associate { i ->
                    val o = arr.getJSONObject(i)
                    o.getString("n") to o.getString("g")
                }
            }.getOrDefault(emptyMap())
        } else emptyMap()
        customBrushGroups = if (customsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(customsJson)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    internal fun PaintViewModel.saveBrushGroups() {
        try {
            val arr = org.json.JSONArray()
            for ((n, g) in userBrushGroups) {
                arr.put(org.json.JSONObject().put("n", n).put("g", g))
            }
            val cust = org.json.JSONArray()
            for (g in customBrushGroups) cust.put(g)
            prefs().edit()
                .putString("user_groups", arr.toString())
                .putString("custom_groups", cust.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushGroups failed", e)
        }
    }

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
        userBrushGroups = userBrushGroups + (presetName to group)
        saveBrushGroups()
        // Refresh the displayed group of this preset
        brushPresets = brushPresets.map {
            if (it.name == presetName) it.copy(group = group) else it
        }
    }

    internal fun PaintViewModel.saveBrushOrder() {
        try {
            val arr = org.json.JSONArray()
            for (n in brushOrder) arr.put(n)
            prefs().edit().putString("brush_order", arr.toString()).apply()
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
        brushOrder = newList.map { it.name }
        saveBrushOrder()
    }

    /** 拖拽直接指定位置重排序 */
    internal fun PaintViewModel.reorderBrushPresets(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in brushPresets.indices || toIndex !in brushPresets.indices) return
        val newList = brushPresets.toMutableList()
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)
        brushPresets = newList
        brushOrder = newList.map { it.name }
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
        categoryOrder = list
        saveCategoryOrder()
    }

    internal fun PaintViewModel.moveCategoryUp(cat: String, allCategories: List<String>) {
        val idx = allCategories.indexOf(cat)
        if (idx > 0) {
            reorderCategory(idx, idx - 1, allCategories)
        }
    }

    internal fun PaintViewModel.moveCategoryDown(cat: String, allCategories: List<String>) {
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

    internal fun PaintViewModel.updateBrushFlow(v: Double) {
        brushFlow = v
        saveBrushParam()
        rememberToolParamSnapshot()
        runCore(render = false) { ReverieCoreBridge.setBrushFlow(v) }
    }

    internal fun PaintViewModel.updateBrushSpacing(v: Double) {
        brushSpacing = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushSpacing(v) }
    }

    internal fun PaintViewModel.updateBrushAngle(v: Double) {
        brushAngle = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushAngle(v) }
    }

    internal fun PaintViewModel.updateBrushScatter(v: Double) {
        brushScatter = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushScatter(v) }
    }

    internal fun PaintViewModel.updateBrushFade(v: Double) {
        brushFade = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushFade(v) }
    }

    internal fun PaintViewModel.updateBrushSoftness(v: Double) {
        brushSoftness = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushSoftness(v) }
    }

    internal fun PaintViewModel.updateBrushRatio(v: Double) {
        brushRatio = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushRatio(v) }
    }

    internal fun PaintViewModel.updateBrushSharpness(v: Double) {
        brushSharpness = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushSharpness(v) }
    }

    internal fun PaintViewModel.updateBrushRotation(v: Double) {
        brushRotation = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushRotation(v) }
    }

    internal fun PaintViewModel.updateBrushCompositeOp(op: String) {
        brushCompositeOp = op
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushCompositeOp(op) }
    }

    internal fun PaintViewModel.updateBrushAntiAliasing(v: Int) {
        brushAntiAliasing = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTipShape(v: Int) {
        brushTipShape = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushRandomFlipX(v: Boolean) {
        brushRandomFlipX = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushRandomFlipY(v: Boolean) {
        brushRandomFlipY = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushFollowDirection(v: Boolean) {
        brushFollowDirection = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushStreamline(v: Double) {
        brushStreamline = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTaper(v: Double) {
        brushTaper = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTextureEnabled(v: Boolean) {
        brushTextureEnabled = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTextureScale(v: Double) {
        brushTextureScale = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTextureStrength(v: Double) {
        brushTextureStrength = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTextureMode(v: String) {
        brushTextureMode = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushHueJitter(v: Double) {
        brushHueJitter = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushSatJitter(v: Double) {
        brushSatJitter = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushValJitter(v: Double) {
        brushValJitter = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushSecondaryMix(v: Double) {
        brushSecondaryMix = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPressureColorMix(v: Boolean) {
        brushPressureColorMix = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPressureEnabled(v: Boolean) {
        brushPressureEnabled = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPressureSize(v: Double) {
        brushPressureSize = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPressureOpacity(v: Double) {
        brushPressureOpacity = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPressureFlow(v: Double) {
        brushPressureFlow = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushSpeedSize(v: Double) {
        brushSpeedSize = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPressureCurve(v: Int) {
        brushPressureCurve = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushTipAsset(asset: String) {
        brushTipAsset = asset
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushPaintOpId(id: String) {
        brushPaintOpId = id
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushAirbrush(v: Boolean) {
        brushAirbrush = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushAirbrushRate(v: Double) {
        brushAirbrushRate = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushSmudgeRate(v: Double) {
        brushSmudgeRate = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushSmudgeRate(v) }
    }

    internal fun PaintViewModel.updateBrushSmudgeLength(v: Double) {
        brushSmudgeLength = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushSmudgeLength(v) }
    }

    internal fun PaintViewModel.updateBrushSpikes(v: Int) {
        brushSpikes = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushJitterAngle(v: Double) {
        brushJitterAngle = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushJitterSize(v: Double) {
        brushJitterSize = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushMinSizeLimit(v: Double) {
        brushMinSizeLimit = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushMaxSizeLimit(v: Double) {
        brushMaxSizeLimit = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushAuthor(v: String) {
        if (brushIsAuthorLocked) return
        brushAuthor = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushDescription(v: String) {
        brushDescription = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushVersion(v: String) {
        brushVersion = v
        saveBrushParam()
    }

    internal fun PaintViewModel.saveBrushParam() {
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        brushParams[name] = BrushParams(
            size = brushSize,
            opacity = brushOpacity,
            flow = brushFlow,
            spacing = brushSpacing,
            angle = brushAngle,
            scatter = brushScatter,
            fade = brushFade,
            softness = brushSoftness,
            ratio = brushRatio,
            sharpness = brushSharpness,
            rotation = brushRotation,
            compositeOp = brushCompositeOp,
            antiAliasing = brushAntiAliasing,
            tipShape = brushTipShape,
            randomFlipX = brushRandomFlipX,
            randomFlipY = brushRandomFlipY,
            followDirection = brushFollowDirection,
            streamline = brushStreamline,
            taper = brushTaper,
            textureEnabled = brushTextureEnabled,
            textureScale = brushTextureScale,
            textureStrength = brushTextureStrength,
            textureMode = brushTextureMode,
            hueJitter = brushHueJitter,
            satJitter = brushSatJitter,
            valJitter = brushValJitter,
            secondaryMix = brushSecondaryMix,
            pressureColorMix = brushPressureColorMix,
            pressureEnabled = brushPressureEnabled,
            pressureSize = brushPressureSize,
            pressureOpacity = brushPressureOpacity,
            pressureFlow = brushPressureFlow,
            speedSize = brushSpeedSize,
            pressureCurve = brushPressureCurve,
            minSizeLimit = brushMinSizeLimit,
            maxSizeLimit = brushMaxSizeLimit,
            tipAsset = brushTipAsset,
            paintOpId = brushPaintOpId,
            airbrush = brushAirbrush,
            airbrushRate = brushAirbrushRate,
            smudgeRate = brushSmudgeRate,
            smudgeLength = brushSmudgeLength,
            spikes = brushSpikes,
            jitterAngle = brushJitterAngle,
            jitterSize = brushJitterSize,
            author = brushAuthor,
            isAuthorLocked = brushIsAuthorLocked,
            description = brushDescription,
            version = brushVersion,
        )
        schedulePersistBrushParams()
    }

    internal fun PaintViewModel.persistBrushParams() {
        try {
            val json = org.json.JSONArray()
            for ((name, p) in brushParams) {
                val o = org.json.JSONObject()
                o.put("n", name)
                o.put("s", p.size)
                o.put("o", p.opacity)
                o.put("f", p.flow)
                o.put("sp", p.spacing)
                o.put("ang", p.angle)
                o.put("sc", p.scatter)
                o.put("fa", p.fade)
                o.put("so", p.softness)
                o.put("ra", p.ratio)
                o.put("sh", p.sharpness)
                o.put("ro", p.rotation)
                o.put("cop", p.compositeOp)
                o.put("aa", p.antiAliasing)
                o.put("ts", p.tipShape)
                o.put("rfx", p.randomFlipX)
                o.put("rfy", p.randomFlipY)
                o.put("fd", p.followDirection)
                o.put("sl", p.streamline)
                o.put("tp", p.taper)
                o.put("te", p.textureEnabled)
                o.put("tscl", p.textureScale)
                o.put("tstr", p.textureStrength)
                o.put("tm", p.textureMode)
                o.put("hj", p.hueJitter)
                o.put("sj", p.satJitter)
                o.put("vj", p.valJitter)
                o.put("sm", p.secondaryMix)
                o.put("pcm", p.pressureColorMix)
                o.put("pe", p.pressureEnabled)
                o.put("ps", p.pressureSize)
                o.put("po", p.pressureOpacity)
                o.put("pf", p.pressureFlow)
                o.put("ss", p.speedSize)
                o.put("pc", p.pressureCurve)
                o.put("mins", p.minSizeLimit)
                o.put("maxs", p.maxSizeLimit)
                o.put("ta", p.tipAsset)
                o.put("poid", p.paintOpId)
                o.put("ab", p.airbrush)
                o.put("abr", p.airbrushRate)
                o.put("smr", p.smudgeRate)
                o.put("sml", p.smudgeLength)
                o.put("spk", p.spikes)
                o.put("ja", p.jitterAngle)
                o.put("js", p.jitterSize)
                o.put("aut", p.author)
                o.put("autl", p.isAuthorLocked)
                o.put("desc", p.description)
                o.put("ver", p.version)
                json.put(o)
            }
            prefs().edit().putString("brush_params", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.persistToolBrushStates() {
        try {
            val json = org.json.JSONArray()
            for ((id, s) in toolBrushStates) {
                val o = org.json.JSONObject()
                o.put("id", id)
                o.put("pi", s.presetIndex)
                o.put("c", s.category)
                o.put("csi", s.categoryScrollIndex)
                o.put("cso", s.categoryScrollOffset)
                o.put("psi", s.presetScrollIndex)
                o.put("pso", s.presetScrollOffset)
                if (s.paramMemory.isNotEmpty()) {
                    val pm = org.json.JSONArray()
                    for ((n, v) in s.paramMemory) {
                        if (v.size >= 3) {
                            pm.put(org.json.JSONArray().apply {
                                put(n); put(v[0]); put(v[1]); put(v[2])
                            })
                        }
                    }
                    o.put("pm", pm)
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

    internal fun PaintViewModel.loadBrushParams() {
        try {
            val raw = prefs().getString("brush_params", null) ?: return
            val json = org.json.JSONArray(raw)
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val name = o.getString("n")
                val isEraser = name.startsWith("a)_") || name.contains("Eraser", ignoreCase = true)
                val rawCop = o.optString("cop", "normal")
                brushParams[name] = BrushParams(
                    size = o.optDouble("s", 20.0),
                    opacity = o.optDouble("o", 1.0),
                    flow = o.optDouble("f", 1.0),
                    spacing = o.optDouble("sp", 0.1),
                    angle = o.optDouble("ang", 0.0),
                    scatter = o.optDouble("sc", 0.0),
                    fade = o.optDouble("fa", 0.0),
                    softness = o.optDouble("so", 0.5),
                    ratio = o.optDouble("ra", 1.0),
                    sharpness = o.optDouble("sh", 0.0),
                    rotation = o.optDouble("ro", 0.0),
                    compositeOp = if (!isEraser && rawCop == "erase") "normal" else rawCop,
                    antiAliasing = o.optInt("aa", 1),
                    tipShape = o.optInt("ts", 0),
                    randomFlipX = o.optBoolean("rfx", false),
                    randomFlipY = o.optBoolean("rfy", false),
                    followDirection = o.optBoolean("fd", false),
                    streamline = o.optDouble("sl", 0.0),
                    taper = o.optDouble("tp", 0.0),
                    textureEnabled = o.optBoolean("te", false),
                    textureScale = o.optDouble("tscl", 1.0),
                    textureStrength = o.optDouble("tstr", 0.5),
                    textureMode = o.optString("tm", "multiply"),
                    hueJitter = o.optDouble("hj", 0.0),
                    satJitter = o.optDouble("sj", 0.0),
                    valJitter = o.optDouble("vj", 0.0),
                    secondaryMix = o.optDouble("sm", 0.0),
                    pressureColorMix = o.optBoolean("pcm", false),
                    pressureEnabled = o.optBoolean("pe", true),
                    pressureSize = o.optDouble("ps", 1.0),
                    pressureOpacity = o.optDouble("po", 1.0),
                    pressureFlow = o.optDouble("pf", 1.0),
                    speedSize = o.optDouble("ss", 0.0),
                    pressureCurve = o.optInt("pc", 0),
                    minSizeLimit = o.optDouble("mins", 1.0),
                    maxSizeLimit = o.optDouble("maxs", 500.0),
                    tipAsset = o.optString("ta", ""),
                    paintOpId = o.optString("poid", "defaultpaintop"),
                    airbrush = o.optBoolean("ab", false),
                    airbrushRate = o.optDouble("abr", 0.05),
                    smudgeRate = o.optDouble("smr", 0.5),
                    smudgeLength = o.optDouble("sml", 0.5),
                    spikes = o.optInt("spk", 2),
                    jitterAngle = o.optDouble("ja", 0.0),
                    jitterSize = o.optDouble("js", 0.0),
                    author = o.optString("aut", "ReveriePaint"),
                    isAuthorLocked = o.optBoolean("autl", false),
                    description = o.optString("desc", ""),
                    version = o.optString("ver", "1.0"),
                )
            }
        } catch (_: Exception) {
        }
        
        try {
            val raw = prefs().getString("tool_brush_states", null) ?: return
            val json = org.json.JSONArray(raw)
            val map = mutableMapOf<String, PaintViewModel.ToolBrushState>()
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val pmJson = o.optJSONArray("pm")
                var paramMemory: Map<String, List<Double>> = emptyMap()
                if (pmJson != null) {
                    val m = mutableMapOf<String, List<Double>>()
                    for (j in 0 until pmJson.length()) {
                        val e = pmJson.optJSONArray(j) ?: continue
                        if (e.length() >= 4) {
                            m[e.optString(0)] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                        }
                    }
                    paramMemory = m
                }
                map[o.getString("id")] = PaintViewModel.ToolBrushState(
                    presetIndex = o.optInt("pi", -1),
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
        
        try {
            val pinned = prefs().getString("pinned_tools", null) ?: return
            val ids = pinned.split(",").filter { it.isNotEmpty() }
            pinnedTools = ids.mapNotNull { com.reverie.paint.model.Tool.fromId(it) }
        } catch (_: Exception) {
        }
    }

    /** Reset all parameters for the current brush back to factory defaults */
    internal fun PaintViewModel.resetBrushParams() {
        val index = brushPresetIndex
        val d = ReverieCoreBridge.brushPresetDefaults(index)
        brushSize = d?.getOrNull(0) ?: 20.0
        brushOpacity = (d?.getOrNull(1) ?: 1.0).coerceIn(0.0, 1.0)
        brushFlow = (d?.getOrNull(2) ?: 1.0).coerceIn(0.0, 1.0)
        brushSpacing = 0.1
        brushAngle = 0.0
        brushScatter = 0.0
        brushFade = 0.0
        brushSoftness = 0.5
        brushRatio = 1.0
        brushSharpness = 0.0
        brushRotation = 0.0
        brushCompositeOp = "normal"
        brushAntiAliasing = 1
        brushTipShape = 0
        brushRandomFlipX = false
        brushRandomFlipY = false
        brushFollowDirection = false
        brushStreamline = 0.0
        brushTaper = 0.0
        brushTextureEnabled = false
        brushTextureScale = 1.0
        brushTextureStrength = 0.5
        brushTextureMode = "multiply"
        brushHueJitter = 0.0
        brushSatJitter = 0.0
        brushValJitter = 0.0
        brushSecondaryMix = 0.0
        brushPressureColorMix = false
        brushPressureEnabled = true
        brushPressureSize = 1.0
        brushPressureOpacity = 1.0
        brushPressureFlow = 1.0
        brushSpeedSize = 0.0
        brushPressureCurve = 0
        brushMinSizeLimit = 1.0
        brushMaxSizeLimit = 500.0
        brushTipAsset = ""
        brushPaintOpId = "defaultpaintop"
        brushAirbrush = false
        brushAirbrushRate = 0.05
        brushSmudgeRate = 0.5
        brushSmudgeLength = 0.5
        brushSpikes = 2
        brushJitterAngle = 0.0
        brushJitterSize = 0.0
        brushDescription = ""
        brushVersion = "1.0"
        saveBrushParam()
        runCore(render = false) {
            ReverieCoreBridge.setBrushSize(brushSize)
            ReverieCoreBridge.setBrushOpacity(brushOpacity)
            ReverieCoreBridge.setBrushFlow(brushFlow)
            ReverieCoreBridge.setBrushSpacing(brushSpacing)
            ReverieCoreBridge.setBrushAngle(brushAngle)
            ReverieCoreBridge.setBrushScatter(brushScatter)
            ReverieCoreBridge.setBrushFade(brushFade)
            ReverieCoreBridge.setBrushSoftness(brushSoftness)
            ReverieCoreBridge.setBrushRatio(brushRatio)
            ReverieCoreBridge.setBrushSharpness(brushSharpness)
            ReverieCoreBridge.setBrushRotation(brushRotation)
            ReverieCoreBridge.setBrushCompositeOp(brushCompositeOp)
            ReverieCoreBridge.setBrushSmudgeRate(brushSmudgeRate)
            ReverieCoreBridge.setBrushSmudgeLength(brushSmudgeLength)
        }
    }

    internal fun PaintViewModel.selectBrushPreset(index: Int) {
        val preset = brushPresets.getOrNull(index)
        val isBuiltIn = preset?.isBuiltIn == true
        val isEraserPreset = preset?.group == "橡皮擦" || preset?.name?.startsWith("a)") == true || preset?.name?.contains("Eraser", ignoreCase = true) == true
        
        if (isEraserPreset) {
            if (currentToolId != "eraser") {
                applyTool("eraser")
            }
        } else {
            if (currentToolId == "eraser") {
                applyTool("brush")
            }
        }

        val saved = if (preset != null) brushParams[preset.name] else null
        val effectiveCompOp = if (isEraserPreset) {
            "erase"
        } else {
            val savedOp = saved?.compositeOp
            if (savedOp.isNullOrBlank() || savedOp == "erase") "normal" else savedOp
        }
        runCore(after = {
            if (saved != null) {
                brushSize = saved.size
                brushOpacity = saved.opacity
                brushFlow = saved.flow
                brushSpacing = saved.spacing
                brushAngle = saved.angle
                brushScatter = saved.scatter
                brushFade = saved.fade
                brushSoftness = saved.softness
                brushRatio = saved.ratio
                brushSharpness = saved.sharpness
                brushRotation = saved.rotation
                brushCompositeOp = effectiveCompOp
                brushAntiAliasing = saved.antiAliasing
                brushTipShape = saved.tipShape
                brushRandomFlipX = saved.randomFlipX
                brushRandomFlipY = saved.randomFlipY
                brushFollowDirection = saved.followDirection
                brushStreamline = saved.streamline
                brushTaper = saved.taper
                brushTextureEnabled = saved.textureEnabled
                brushTextureScale = saved.textureScale
                brushTextureStrength = saved.textureStrength
                brushTextureMode = saved.textureMode
                brushHueJitter = saved.hueJitter
                brushSatJitter = saved.satJitter
                brushValJitter = saved.valJitter
                brushSecondaryMix = saved.secondaryMix
                brushPressureColorMix = saved.pressureColorMix
                brushPressureEnabled = saved.pressureEnabled
                brushPressureSize = saved.pressureSize
                brushPressureOpacity = saved.pressureOpacity
                brushPressureFlow = saved.pressureFlow
                brushSpeedSize = saved.speedSize
                brushPressureCurve = saved.pressureCurve
                brushMinSizeLimit = saved.minSizeLimit
                brushMaxSizeLimit = saved.maxSizeLimit
                brushTipAsset = saved.tipAsset
                brushPaintOpId = saved.paintOpId
                brushAirbrush = saved.airbrush
                brushAirbrushRate = saved.airbrushRate
                brushSmudgeRate = saved.smudgeRate
                brushSmudgeLength = saved.smudgeLength
                brushSpikes = saved.spikes
                brushJitterAngle = saved.jitterAngle
                brushJitterSize = saved.jitterSize
                brushAuthor = if (isBuiltIn) "Krita" else saved.author
                brushIsAuthorLocked = if (isBuiltIn) true else saved.isAuthorLocked
                brushDescription = saved.description
                brushVersion = saved.version
            } else {
                // First use: the preset's own defaults
                val d = ReverieCoreBridge.brushPresetDefaults(index)
                if (d != null && d.size >= 3) {
                    brushSize = d[0]
                    brushOpacity = d[1].coerceIn(0.0, 1.0)
                    brushFlow = d[2].coerceIn(0.0, 1.0)
                }
                brushCompositeOp = effectiveCompOp
                brushAuthor = if (isBuiltIn) "Krita" else "原创创作者"
                brushIsAuthorLocked = isBuiltIn
            }
            // 主线程先写预设值, 再由 overlay 用当前工具记忆覆盖;
            // overlay 内部的引擎 setter 经 runCore 追加在预设 setter 之后, 写序确定。
            applyToolParamMemoryOverlay()
        }) {
            if (ReverieCoreBridge.loadBrushPreset(index)) {
                brushPresetIndex = index
                updateCurrentToolBrushState { it.copy(presetIndex = index) }
                try {
                    prefs().edit().putInt("last_brush_preset_index", index).apply()
                } catch (_: Exception) {
                }
            }
            if (saved != null) {
                ReverieCoreBridge.setBrushSize(saved.size)
                ReverieCoreBridge.setBrushOpacity(saved.opacity)
                ReverieCoreBridge.setBrushFlow(saved.flow)
                ReverieCoreBridge.setBrushSpacing(saved.spacing)
                ReverieCoreBridge.setBrushAngle(saved.angle)
                ReverieCoreBridge.setBrushScatter(saved.scatter)
                ReverieCoreBridge.setBrushFade(saved.fade)
                ReverieCoreBridge.setBrushSoftness(saved.softness)
                ReverieCoreBridge.setBrushRatio(saved.ratio)
                ReverieCoreBridge.setBrushSharpness(saved.sharpness)
                ReverieCoreBridge.setBrushRotation(saved.rotation)
                ReverieCoreBridge.setBrushCompositeOp(effectiveCompOp)
                ReverieCoreBridge.setBrushSmudgeRate(saved.smudgeRate)
                ReverieCoreBridge.setBrushSmudgeLength(saved.smudgeLength)
            } else {
                ReverieCoreBridge.setBrushCompositeOp(effectiveCompOp)
                ReverieCoreBridge.setBrushSmudgeRate(brushSmudgeRate)
                ReverieCoreBridge.setBrushSmudgeLength(brushSmudgeLength)
            }
        }
    }

    internal fun PaintViewModel.updateCurrentToolBrushState(updater: (PaintViewModel.ToolBrushState) -> PaintViewModel.ToolBrushState) {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER || t == com.reverie.paint.model.Tool.SMUDGE) {
            val state = toolBrushStates[t.id] ?: PaintViewModel.ToolBrushState()
            toolBrushStates = toolBrushStates.toMutableMap().apply { put(t.id, updater(state)) }
            persistToolBrushStates()
        }
    }

    /** Krita saved{Mode}Size 语义: 把当前 size/opacity/flow 快照进
     *  (当前工具 × 当前预设) 的记忆, 让每个工具各自记住自己的数值。 */
    internal fun PaintViewModel.rememberToolParamSnapshot() {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t != com.reverie.paint.model.Tool.BRUSH && t != com.reverie.paint.model.Tool.ERASER &&
            t != com.reverie.paint.model.Tool.SMUDGE
        ) return
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        updateCurrentToolBrushState { st ->
            st.copy(
                paramMemory = st.paramMemory.toMutableMap().apply {
                    put(name, listOf(brushSize, brushOpacity, brushFlow))
                }
            )
        }
    }

    /** 预设参数加载完成后, 用当前工具对该预设的记忆覆盖 size/opacity/flow。
     *  无记忆时保持预设参数不变 (首次使用语义)。 */
    internal fun PaintViewModel.applyToolParamMemoryOverlay() {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t != com.reverie.paint.model.Tool.BRUSH && t != com.reverie.paint.model.Tool.ERASER &&
            t != com.reverie.paint.model.Tool.SMUDGE
        ) return
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        val mem = toolBrushStates[t.id]?.paramMemory?.get(name) ?: return
        if (mem.size < 3) return
        // 损坏数据防护: 每个值做 isFinite() 检查, 非有限值跳过该值, 防 NaN 流入引擎
        if (mem[0].isFinite()) {
            brushSize = mem[0]
        }
        if (mem[1].isFinite()) {
            brushOpacity = mem[1]
        }
        if (mem[2].isFinite()) {
            brushFlow = mem[2]
        }
        runCore(render = false) {
            if (mem[0].isFinite()) ReverieCoreBridge.setBrushSize(mem[0])
            if (mem[1].isFinite()) ReverieCoreBridge.setBrushOpacity(mem[1])
            if (mem[2].isFinite()) ReverieCoreBridge.setBrushFlow(mem[2])
        }
    }

    internal fun PaintViewModel.updateBrushPanelCategory(cat: String) {
        brushPanelSelectedCategory = cat
        updateCurrentToolBrushState { it.copy(category = cat) }
        persistBrushPanelState()
    }

    internal fun PaintViewModel.updateBrushCategoryScroll(index: Int, offset: Int) {
        brushCategoryScrollIndex = index
        brushCategoryScrollOffset = offset
        updateCurrentToolBrushState { it.copy(categoryScrollIndex = index, categoryScrollOffset = offset) }
        persistBrushPanelState()
    }

    internal fun PaintViewModel.updateBrushPresetScroll(index: Int, offset: Int) {
        brushPresetScrollIndex = index
        brushPresetScrollOffset = offset
        updateCurrentToolBrushState { it.copy(presetScrollIndex = index, presetScrollOffset = offset) }
        persistBrushPanelState()
    }

    internal fun PaintViewModel.updateBrushSize(v: Double) {
        brushSize = v
        saveBrushParam()
        rememberToolParamSnapshot()
        runCore(render = false) { ReverieCoreBridge.setBrushSize(v) }
    }

    internal fun PaintViewModel.updateBrushColor(c: String) {
        brushColor = c
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
    }

    internal fun PaintViewModel.swapColors() {
        val temp = brushColor
        updateBrushColor(brushSecondaryColor)
        updateBrushSecondaryColor(temp)
    }

    internal fun PaintViewModel.updateBrushOpacity(v: Double) {
        brushOpacity = v
        saveBrushParam()
        rememberToolParamSnapshot()
        runCore(render = false) { ReverieCoreBridge.setBrushOpacity(v) }
    }

    /** Reload all brush presets from filesDir and update Compose state */
    internal fun PaintViewModel.reloadBrushPresets(selectName: String? = null) {
        val dir = File(appContext.filesDir, "paintoppresets")
        val brushDir = File(appContext.filesDir, "brushes")
        val builtInNames = appContext.assets.list("paintoppresets")?.map { it.removeSuffix(".kpp") }?.toSet() ?: emptySet()
        val list = ArrayList<BrushPresetInfo>()
        runCore(after = {
            brushPresets = list.toList()
            if (brushPresets.isNotEmpty()) {
                val targetIndex = if (selectName != null) {
                    val idx = brushPresets.indexOfFirst { it.name == selectName }
                    if (idx >= 0) idx else 0
                } else {
                    brushPresetIndex.coerceIn(0, brushPresets.size - 1)
                }
                selectBrushPreset(targetIndex)
            }
        }) {
            ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
            val n = ReverieCoreBridge.loadBrushPresetsFromDir(dir.absolutePath)
            list.clear()
            for (i in 0 until n) {
                val nm = ReverieCoreBridge.brushPresetName(i)
                list.add(
                    BrushPresetInfo(
                        index = i,
                        name = nm,
                        thumbBytes = ReverieCoreBridge.brushPresetThumbData(i),
                        group = userBrushGroups[nm] ?: inferBrushGroup(nm),
                        isBuiltIn = builtInNames.contains(nm),
                    ),
                )
            }
        }
    }

    /** 复制指定笔刷 (复制出的笔刷不受内置作者锁定及不可删除限制) */
    internal fun PaintViewModel.duplicateBrushPreset(presetIndex: Int, newName: String? = null): Boolean {
        val src = brushPresets.getOrNull(presetIndex) ?: return false
        val cleanName = newName?.trim()?.ifEmpty { null } ?: "${src.name} 副本"
        val dir = File(appContext.filesDir, "paintoppresets")
        val srcFile = File(dir, "${src.name}.kpp")
        val dstFile = File(dir, "$cleanName.kpp")
        if (srcFile.exists()) {
            srcFile.copyTo(dstFile, overwrite = true)
        }
        val srcParams = brushParams[src.name] ?: BrushParams()
        // 复制出的笔刷作者可自由修改，且非内置
        brushParams[cleanName] = srcParams.copy(
            author = if (src.isBuiltIn) "Krita (副本)" else srcParams.author,
            isAuthorLocked = false,
        )
        persistBrushParams()
        if (userBrushGroups.containsKey(src.name)) {
            userBrushGroups = userBrushGroups + (cleanName to userBrushGroups[src.name]!!)
            saveBrushGroups()
        }
        reloadBrushPresets(selectName = cleanName)
        return true
    }

    /** 删除指定笔刷 (内置笔刷不可删除) */
    internal fun PaintViewModel.deleteBrushPreset(presetIndex: Int): Boolean {
        val preset = brushPresets.getOrNull(presetIndex) ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷禁止删除
        }
        val dir = File(appContext.filesDir, "paintoppresets")
        val file = File(dir, "${preset.name}.kpp")
        if (file.exists()) file.delete()
        brushParams.remove(preset.name)
        persistBrushParams()
        userBrushGroups = userBrushGroups - preset.name
        saveBrushGroups()
        reloadBrushPresets()
        return true
    }

    /** 删除自定义笔刷组 (内置组不可删除) */
    internal fun PaintViewModel.deleteBrushGroup(name: String): Boolean {
        if (isBuiltInGroup(name)) return false // 内置组禁止删除
        if (!customBrushGroups.contains(name)) return false
        customBrushGroups = customBrushGroups.filter { it != name }
        userBrushGroups = userBrushGroups.filterValues { it != name }
        saveBrushGroups()
        return true
    }

    /** 创建全新自定义笔刷 (默认基于 Basic-1) */
    internal fun PaintViewModel.createNewBrushPreset(
        name: String,
        group: String = "自定义",
        basePresetIndex: Int = -1,
        tipAsset: String = "",
        paintOpId: String = "defaultpaintop",
    ): Boolean {
        val cleanName = name.trim().ifEmpty { "自定义笔刷_${(System.currentTimeMillis() % 10000)}" }
        val dir = File(appContext.filesDir, "paintoppresets")
        if (!dir.exists()) dir.mkdirs()
        
        // 默认基准预设选择 b)_Basic-1
        val base = if (basePresetIndex in brushPresets.indices) {
            brushPresets[basePresetIndex]
        } else {
            brushPresets.firstOrNull { it.name == "b)_Basic-1" || it.name.contains("Basic-1") }
                ?: brushPresets.firstOrNull()
        }
        val baseFile = base?.let { File(dir, "${it.name}.kpp") }
        val targetFile = File(dir, "$cleanName.kpp")
        if (baseFile != null && baseFile.exists()) {
            baseFile.copyTo(targetFile, overwrite = true)
        } else {
            val first = dir.listFiles()?.firstOrNull { it.name.endsWith(".kpp") }
            first?.copyTo(targetFile, overwrite = true)
        }
        val baseParams = base?.name?.let { brushParams[it] }
        brushParams[cleanName] = (baseParams?.copy() ?: BrushParams()).copy(
            tipAsset = if (tipAsset.isNotEmpty()) tipAsset else (baseParams?.tipAsset ?: ""),
            paintOpId = if (paintOpId.isNotEmpty() && paintOpId != "defaultpaintop") paintOpId else (baseParams?.paintOpId ?: "defaultpaintop"),
            author = "原创创作者",
            isAuthorLocked = false,
        )
        persistBrushParams()
        userBrushGroups = userBrushGroups + (cleanName to group)
        if (!customBrushGroups.contains(group) && group != "全部") {
            customBrushGroups = customBrushGroups + group
        }
        saveBrushGroups()
        reloadBrushPresets(selectName = cleanName)
        return true
    }

    /** 导入外部笔刷文件 (.kpp, .bundle, .gbr, .png) */
    internal fun PaintViewModel.importBrushFromUri(uri: android.net.Uri): Boolean {
        return try {
            val resolver = appContext.contentResolver
            val filename = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "import_${System.currentTimeMillis()}"

            val dir = File(appContext.filesDir, "paintoppresets")
            if (!dir.exists()) dir.mkdirs()

            if (filename.endsWith(".kpp", ignoreCase = true)) {
                val target = File(dir, filename)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val presetName = filename.substringBeforeLast(".")
                brushParams[presetName] = BrushParams(
                    author = "外部创作者 (分享)",
                    isAuthorLocked = true,
                    description = "导入自外部创作者分享的笔刷预设",
                )
                persistBrushParams()
                userBrushGroups = userBrushGroups + (presetName to "导入")
                if (!customBrushGroups.contains("导入")) {
                    customBrushGroups = customBrushGroups + "导入"
                }
                saveBrushGroups()
                reloadBrushPresets(selectName = presetName)
                true
            } else if (filename.endsWith(".png", true) || filename.endsWith(".gbr", true) || filename.endsWith(".gih", true) || filename.endsWith(".abr", true)) {
                val brushDir = File(appContext.filesDir, "brushes")
                if (!brushDir.exists()) brushDir.mkdirs()
                val target = File(brushDir, filename)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val presetName = filename.substringBeforeLast(".")
                createNewBrushPreset(name = presetName, group = "导入", tipAsset = filename)
                brushParams[presetName] = brushParams[presetName]?.copy(
                    author = "外部创作者 (分享)",
                    isAuthorLocked = true,
                ) ?: BrushParams(author = "外部创作者 (分享)", isAuthorLocked = true)
                persistBrushParams()
                true
            } else if (filename.endsWith(".bundle", true) || filename.endsWith(".zip", true)) {
                resolver.openInputStream(uri)?.use { input ->
                    val tempZip = File(appContext.cacheDir, "bundle_temp.zip")
                    tempZip.outputStream().use { input.copyTo(it) }
                    val zip = ZipFile(tempZip)
                    for (entry in zip.entries()) {
                        if (entry.name.contains("paintoppresets/") && entry.name.endsWith(".kpp")) {
                            val name = entry.name.substringAfterLast("/")
                            val out = File(dir, name)
                            zip.getInputStream(entry).use { inS -> out.outputStream().use { inS.copyTo(it) } }
                        } else if (entry.name.contains("brushes/")) {
                            val brushDir = File(appContext.filesDir, "brushes")
                            if (!brushDir.exists()) brushDir.mkdirs()
                            val name = entry.name.substringAfterLast("/")
                            val out = File(brushDir, name)
                            zip.getInputStream(entry).use { inS -> out.outputStream().use { inS.copyTo(it) } }
                        }
                    }
                    zip.close()
                    tempZip.delete()
                }
                reloadBrushPresets()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importBrushFromUri failed", e)
            false
        }
    }

    /** 重命名笔刷 (内置笔刷固定禁止重命名) */
    internal fun PaintViewModel.renameBrushPreset(presetIndex: Int, newName: String): Boolean {
        val preset = brushPresets.getOrNull(presetIndex) ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷固定名称，禁止修改
        }
        val clean = newName.trim().ifEmpty { return false }
        if (clean == preset.name) return true
        val dir = File(appContext.filesDir, "paintoppresets")
        val src = File(dir, "${preset.name}.kpp")
        val dst = File(dir, "$clean.kpp")
        if (src.exists()) src.renameTo(dst)
        val p = brushParams.remove(preset.name)
        if (p != null) brushParams[clean] = p
        persistBrushParams()
        val g = userBrushGroups[preset.name]
        if (g != null) {
            userBrushGroups = (userBrushGroups - preset.name) + (clean to g)
        }
        saveBrushGroups()
        if (brushOrder.contains(preset.name)) {
            brushOrder = brushOrder.map { if (it == preset.name) clean else it }
            saveBrushOrder()
        }
        reloadBrushPresets(selectName = clean)
        return true
    }

    /** 导入用户自定义笔尖贴图 (PNG, GBR, GIH, JPG) 并设置为当前笔刷笔尖 */
    internal fun PaintViewModel.importCustomBrushTip(uri: android.net.Uri): String? {
        return try {
            val resolver = appContext.contentResolver
            val filename = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "tip_${System.currentTimeMillis()}.png"

            val cleanName = if (filename.endsWith(".png", true) || filename.endsWith(".gbr", true) || filename.endsWith(".gih", true) || filename.endsWith(".jpg", true)) {
                filename
            } else {
                "$filename.png"
            }
            val brushDir = File(appContext.filesDir, "brushes")
            if (!brushDir.exists()) brushDir.mkdirs()
            val target = File(brushDir, cleanName)
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            updateBrushTipAsset(cleanName)
            cleanName
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importCustomBrushTip failed", e)
            null
        }
    }


