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
        ReverieCoreBridge.setBrushFlow(v)
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
        ReverieCoreBridge.setBrushSpacing(v)
    }

    internal fun PaintViewModel.updateBrushAngle(v: Double) {
        brushAngle = v
        ReverieCoreBridge.setBrushAngle(v)
    }

    internal fun PaintViewModel.updateBrushScatter(v: Double) {
        brushScatter = v
        ReverieCoreBridge.setBrushScatter(v)
    }

    internal fun PaintViewModel.updateBrushFade(v: Double) {
        brushFade = v
        ReverieCoreBridge.setBrushFade(v)
    }

    internal fun PaintViewModel.updateBrushSoftness(v: Double) {
        brushSoftness = v
        ReverieCoreBridge.setBrushSoftness(v)
    }

    internal fun PaintViewModel.updateBrushRatio(v: Double) {
        brushRatio = v
        ReverieCoreBridge.setBrushRatio(v)
    }

    internal fun PaintViewModel.updateBrushSharpness(v: Double) {
        brushSharpness = v
        ReverieCoreBridge.setBrushSharpness(v)
    }

    internal fun PaintViewModel.updateBrushRotation(v: Double) {
        brushRotation = v
        ReverieCoreBridge.setBrushRotation(v)
    }

    internal fun PaintViewModel.updateBrushCompositeOp(op: String) {
        brushCompositeOp = op
        ReverieCoreBridge.setBrushCompositeOp(op)
    }

    internal fun PaintViewModel.saveBrushParam() {
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        brushParams[name] = BrushParams(brushSize, brushOpacity, brushFlow)
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
                brushParams[o.getString("n")] =
                    BrushParams(o.getDouble("s"), o.getDouble("o"), o.getDouble("f"))
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
        ReverieCoreBridge.setBrushSize(v)
    }

    internal fun PaintViewModel.updateBrushColor(c: String) {
        brushColor = c
        ReverieCoreBridge.setBrushColor(c)
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
        ReverieCoreBridge.setBrushOpacity(v)
    }

