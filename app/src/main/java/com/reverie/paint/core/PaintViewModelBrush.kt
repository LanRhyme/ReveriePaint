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

    internal fun PaintViewModel.updateBrushFlow(v: Double) {
        brushFlow = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushFlow(v) }
    }

    internal fun PaintViewModel.resetBrushParams() {
        val preset = brushPresets.getOrNull(brushPresetIndex) ?: return
        brushParams.remove(preset.name)
        persistBrushParams()

        updateBrushSpacing(0.1)
        updateBrushAngle(0.0)
        updateBrushScatter(0.0)
        updateBrushFade(0.0)
        updateBrushSoftness(0.5)
        updateBrushRatio(1.0)
        updateBrushSharpness(0.0)
        updateBrushRotation(0.0)
        updateBrushCompositeOp("normal")

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

        runCore(after = {
            val d = ReverieCoreBridge.brushPresetDefaults(brushPresetIndex)
            if (d != null && d.size >= 3) {
                brushSize = d[0]
                brushOpacity = d[1].coerceIn(0.0, 1.0)
                brushFlow = d[2].coerceIn(0.0, 1.0)
            }
        }) {
            ReverieCoreBridge.loadBrushPreset(brushPresetIndex)
        }
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

    internal fun PaintViewModel.updateBrushMinSizeLimit(v: Double) {
        brushMinSizeLimit = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushMaxSizeLimit(v: Double) {
        brushMaxSizeLimit = v
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
        )
        persistBrushParams()
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
                brushParams[o.getString("n")] = BrushParams(
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
                    compositeOp = o.optString("cop", "normal"),
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
                map[o.getString("id")] = PaintViewModel.ToolBrushState(
                    presetIndex = o.optInt("pi", -1),
                    category = o.optString("c", "全部"),
                    categoryScrollIndex = o.optInt("csi", 0),
                    categoryScrollOffset = o.optInt("cso", 0),
                    presetScrollIndex = o.optInt("psi", 0),
                    presetScrollOffset = o.optInt("pso", 0)
                )
            }
            toolBrushStates = map
        } catch (_: Exception) {
        }
        
        try {
            currentToolId = prefs().getString("current_tool_id", "brush") ?: "brush"
        } catch (_: Exception) {
        }
        
        try {
            val raw = prefs().getString("pinned_tools", null)
            if (raw != null && raw.isNotBlank()) {
                val tools = raw.split(",").map { com.reverie.paint.model.Tool.fromId(it) }
                pinnedTools = tools
            } else {
                pinnedTools = listOf(
                    com.reverie.paint.model.Tool.BRUSH,
                    com.reverie.paint.model.Tool.ERASER
                )
            }
        } catch (_: Exception) {
            pinnedTools = listOf(
                com.reverie.paint.model.Tool.BRUSH,
                com.reverie.paint.model.Tool.ERASER
            )
        }
    }

    internal fun PaintViewModel.selectBrushPreset(index: Int) {
        if (index == brushPresetIndex) return
        val preset = brushPresets.getOrNull(index) ?: return
        val saved = brushParams[preset.name]
        brushPresetIndex = index
        runCore(after = {
            if (saved != null) {
                // User-adjusted values for this preset
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
                brushCompositeOp = saved.compositeOp
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
            } else {
                // First use: the preset's own defaults
                val d = ReverieCoreBridge.brushPresetDefaults(index)
                if (d != null && d.size >= 3) {
                    brushSize = d[0]
                    brushOpacity = d[1].coerceIn(0.0, 1.0)
                    brushFlow = d[2].coerceIn(0.0, 1.0)
                }
            }
        }) {
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
                ReverieCoreBridge.setBrushCompositeOp(saved.compositeOp)
            }
            if (ReverieCoreBridge.loadBrushPreset(index)) {
                brushPresetIndex = index
                updateCurrentToolBrushState { it.copy(presetIndex = index) }
                try {
                    prefs().edit().putInt("last_brush_preset_index", index).apply()
                } catch (_: Exception) {
                }
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

    internal fun PaintViewModel.updateBrushPanelCategory(cat: String) {
        brushPanelSelectedCategory = cat
        updateCurrentToolBrushState { it.copy(category = cat) }
    }

    internal fun PaintViewModel.updateBrushCategoryScroll(index: Int, offset: Int) {
        brushCategoryScrollIndex = index
        brushCategoryScrollOffset = offset
        updateCurrentToolBrushState { it.copy(categoryScrollIndex = index, categoryScrollOffset = offset) }
    }

    internal fun PaintViewModel.updateBrushPresetScroll(index: Int, offset: Int) {
        brushPresetScrollIndex = index
        brushPresetScrollOffset = offset
        updateCurrentToolBrushState { it.copy(presetScrollIndex = index, presetScrollOffset = offset) }
    }

    internal fun PaintViewModel.updateBrushSize(v: Double) {
        brushSize = v
        saveBrushParam()
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
        runCore(render = false) { ReverieCoreBridge.setBrushOpacity(v) }
    }


