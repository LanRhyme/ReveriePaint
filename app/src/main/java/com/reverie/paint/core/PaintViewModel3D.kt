/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.reverie.paint.R
import com.reverie.paint.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

private val meshCache = ConcurrentHashMap<String, Mesh3D>()
private val skinnedModelCache = ConcurrentHashMap<String, SkinnedMesh3D>()

/**
 * 一次形变的完整结果: 网格 + 骨架 + 关节世界坐标
 *
 * 旧实现把"当前骨架/关节世界坐标"当作 [getMeshForState] 的副作用写入 ViewModel,
 * 命中缓存时不会刷新, 于是切换图层/模型后骨架覆盖层会画在错误的位置。
 * 现在骨架与关节坐标随形变结果一起返回, 调用方自己决定画哪一份。
 */
internal class Deformed3D(
    val mesh: Mesh3D,
    val skeleton: Skeleton3D?,
    val worldJoints: FloatArray?,
)

private data class DeformKey(
    val modelType: String,
    val posePreset: String,
    val jointAngles: Map<String, Float>,
    /** 关节静止位置偏移的签名 —— 不含它会导致改绑骨后骨架点/网格不刷新 */
    val restOffsets: String = "",
)

/** 静态形变结果 LRU (烘焙/导出/撤销回放复用), 按浮点元素总量限容 */
private val deformCache = LinkedHashMap<DeformKey, Deformed3D>(8, 0.75f, true)
private val deformCacheLock = Any()
private var deformCacheFloats = 0L
private const val DEFORM_CACHE_FLOAT_BUDGET = 2_000_000L // ≈ 8MB, 旧实现固定 40 份可达 30MB+

/** 交互拖拽槽位: 复用同一组顶点缓冲, 拖拽时每帧不再产生 300KB 级垃圾 */
private class InteractiveSlot {
    var state: Model3DState? = null
    var result: Deformed3D? = null
    var buffer: DeformBuffer? = null
}

private val interactiveSlots = ConcurrentHashMap<Long, InteractiveSlot>()

/**
 * 加载或获取已解析的带骨骼蒙皮 3D 模型 (支持内置 GLB 与外部导入的 .glb / .vrm / .pmx)
 */
internal fun PaintViewModel.getSkinnedModelForState(state: Model3DState): SkinnedMesh3D? {
    val key = state.modelType
    skinnedModelCache[key]?.let { return it }

    val skinned: SkinnedMesh3D? = when (key.lowercase()) {
        "boy", BuiltinModel3D.ANIME_BOY.id, BuiltinModel3D.BODY_MANNEQUIN.id, BuiltinModel3D.BODY_SIT.id -> {
            try {
                appContext.assets.open("3d_models/anime_boy.glb").use { GlbParser.parse(it) }
            } catch (e: Exception) {
                Log.e("RP_3D", "Failed to load anime_boy.glb from assets", e)
                null
            }
        }
        "girl", BuiltinModel3D.ANIME_GIRL.id -> {
            try {
                appContext.assets.open("3d_models/anime_girl.glb").use { GlbParser.parse(it) }
            } catch (e: Exception) {
                Log.e("RP_3D", "Failed to load anime_girl.glb from assets", e)
                null
            }
        }
        "cat", BuiltinModel3D.CAT.id -> {
            try {
                appContext.assets.open("3d_models/anime_cat.glb").use { GlbParser.parse(it) }
            } catch (e: Exception) {
                Log.e("RP_3D", "Failed to load anime_cat.glb from assets", e)
                null
            }
        }
        else -> {
            if (key.startsWith("file:") || key.startsWith("uri:")) {
                try {
                    val stream = if (key.startsWith("file:")) {
                        val file = java.io.File(key.substring(5))
                        if (file.exists()) file.inputStream() else null
                    } else {
                        val uri = Uri.parse(key.substring(4))
                        appContext.contentResolver.openInputStream(uri)
                    }
                    stream?.use { s ->
                        when {
                            key.endsWith(".glb", ignoreCase = true) || key.endsWith(".vrm", ignoreCase = true) -> GlbParser.parse(s)
                            key.endsWith(".pmx", ignoreCase = true) -> PmxParser.parse(s)
                            else -> null
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse imported skinned model from: $key", e)
                    null
                }
            } else {
                null
            }
        }
    }

    if (skinned != null) {
        if (skinnedModelCache.size > 20) skinnedModelCache.clear()
        // 缓存的是"原始绑定姿态": 关节静止偏移是每层各自的状态, 不能污染共享缓存
        skinnedModelCache[key] = skinned
    }
    // 关节静止位置偏移 (用户微调过的 bind pose): 重算 IB, 静止网格保持不变
    val rest = state.jointRestOffsets
    if (skinned == null || rest.isEmpty()) return skinned
    return withRestRebindCache(skinned, JointOffsetSpec.toWorldOffsets(rest), rest)
}

/** 重绑结果缓存: 键 = 模型对象 + 偏移签名, 避免拖动时每帧重建骨架 */
private val restRebindCache = LinkedHashMap<String, SkinnedMesh3D>(8, 0.75f, true)

private fun withRestRebindCache(
    base: SkinnedMesh3D,
    worldOffsets: Map<String, FloatArray>,
    raw: Map<String, Float>,
): SkinnedMesh3D {
    val skeleton = base.skeleton ?: return base
    val sig = raw.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
    val key = System.identityHashCode(base).toString() + "|" + sig
    restRebindCache[key]?.let { return it }
    val rebound = Skeleton3D(skeleton.withRestOffsets(worldOffsets).joints, skeleton.humanoidMapping)
    val result = SkinnedMesh3D(base.baseMesh, rebound, base.jointIndices, base.jointWeights)
    if (restRebindCache.size > 8) restRebindCache.clear()
    restRebindCache[key] = result
    return result
}

/**
 * 获取当前模型的骨架 (只取解析缓存, 不做形变) —— 骨骼拖拽需要绑定姿态的旋转轴角色
 */
internal fun PaintViewModel.skeletonForState(state: Model3DState): Skeleton3D? =
    getSkinnedModelForState(state)?.skeleton

/**
 * 执行一次形变。buffer 非空时复用其中的顶点/矩阵数组。
 */
internal fun PaintViewModel.deformState(state: Model3DState, buffer: DeformBuffer?): Deformed3D {
    val skinned = getSkinnedModelForState(state)
    val skeleton = skinned?.skeleton
    if (skinned != null && skeleton != null && skinned.jointIndices != null && skinned.jointWeights != null) {
        val euler = PosePresets.buildEulerMap(state.modelType, state.posePreset, state.jointAngles)
        // 贴地量只由"预设"决定, 不随用户微调角度漂移
        val shift = PosePresets.groundShiftForPreset(skinned, state.modelType, state.posePreset)
        val translations = PosePresets.buildTranslations(state.modelType, state.posePreset, state.jointAngles)
        val outJoints = when {
            buffer != null && buffer.worldJoints.size >= skeleton.count * 3 -> buffer.worldJoints
            else -> FloatArray(skeleton.count * 3)
        }
        val bend = PosePresets.catCurlBend(state.modelType, state.posePreset, state.jointAngles)
        val mesh = skinned.createDeformedMesh(euler, outJoints, buffer, shift, bend, translations)
        return Deformed3D(mesh, skeleton, outJoints)
    }
    // 无骨骼蒙皮但已由 glTF/GLB 解析器读出的网格 (静态道具模型): 直接用它, 不要再走 OBJ 兜底
    if (skinned != null) return Deformed3D(skinned.baseMesh, skeleton, null)
    return Deformed3D(buildStaticMesh(state), null, null)
}

/**
 * 获取形变结果 (带缓存)。用于烘焙/导出/线稿等非逐帧路径。
 */
internal fun PaintViewModel.getDeformedForState(state: Model3DState): Deformed3D {
    val restSig = if (state.jointRestOffsets.isEmpty()) {
        ""
    } else {
        state.jointRestOffsets.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
    }
    val key = DeformKey(state.modelType, state.posePreset, state.jointAngles, restSig)
    synchronized(deformCacheLock) { deformCache[key]?.let { return it } }

    val result = deformState(state, null)

    synchronized(deformCacheLock) {
        if (!deformCache.containsKey(key)) {
            deformCache[key] = result
            deformCacheFloats += result.mesh.positions.size + result.mesh.normals.size
            val iter = deformCache.entries.iterator()
            while (deformCacheFloats > DEFORM_CACHE_FLOAT_BUDGET && iter.hasNext()) {
                val entry = iter.next()
                if (entry.key == key) continue
                deformCacheFloats -= entry.value.mesh.positions.size + entry.value.mesh.normals.size
                iter.remove()
            }
        }
    }
    return result
}

/**
 * 逐帧渲染专用: 复用顶点缓冲, 命中上一次状态时直接返回 (静止的 3D 图层叠加绘制零开销)。
 *
 * 返回的 [Mesh3D] 指向可复用缓冲, 只允许"画完即弃", 不要持有到下一帧之后。
 */
internal fun PaintViewModel.getInteractiveDeformed(state: Model3DState): Deformed3D {
    if (interactiveSlots.size > 16) interactiveSlots.clear()
    val slot = interactiveSlots.getOrPut(state.layerId) { InteractiveSlot() }
    val cachedState = slot.state
    if (cachedState != null && slot.result != null && (cachedState === state || cachedState == state)) {
        return slot.result!!
    }

    val skinned = getSkinnedModelForState(state)
    val vertexCount = skinned?.baseMesh?.vertexCount ?: 0
    val jointCount = skinned?.skeleton?.count ?: 0
    var buffer = slot.buffer
    if (buffer == null || buffer.vertexCount != vertexCount) {
        buffer = if (vertexCount > 0) DeformBuffer(vertexCount, jointCount) else null
        slot.buffer = buffer
    }

    val result = deformState(state, buffer)
    slot.state = state
    slot.result = result
    return result
}

/**
 * 获取 3D 网格对象 (兼容入口)
 */
internal fun PaintViewModel.getMeshForState(state: Model3DState): Mesh3D = getDeformedForState(state).mesh

/**
 * 后台预热模型解析: 首次绘制时在 UI 线程解析 276KB~600KB 的 GLB 会造成明显掉帧
 */
internal fun PaintViewModel.prewarm3DModel(state: Model3DState) {
    val key = state.modelType
    if (skinnedModelCache.containsKey(key) || meshCache.containsKey(key)) return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            // 蒙皮模型走 skinned 缓存; 内置几何/OBJ 走静态网格缓存。
            // 两者都必须在这里补齐, 否则首帧会在 onDraw 里现解析 (ANR)。
            if (getSkinnedModelForState(state) == null) {
                getDeformedForState(state)
            }
        } catch (e: com.reverie.paint.model.MeshTooLargeException) {
            Log.w("RP_3D", "Prewarm 3D model too large: $key", e)
        } catch (e: Exception) {
            Log.w("RP_3D", "Prewarm 3D model failed: $key", e)
        }
    }
}

/**
 * 构造非蒙皮模型的静态网格 (内置几何体 / OBJ 导入 / 素模兜底)
 */
private fun PaintViewModel.buildStaticMesh(state: Model3DState): Mesh3D {
    // 导入的静态模型 (OBJ) 与姿态/关节角度无关: 缓存键必须和导入时写入的键一致,
    // 否则 onDraw 会重新解析文件 (线上 9fc2dbca2e 的 ANR 就是这么来的)。
    val isImportedStatic = state.modelType.startsWith("file:") || state.modelType.startsWith("uri:")
    val key = if (isImportedStatic) {
        state.modelType
    } else {
        val anglesKey = if (state.jointAngles.isEmpty()) "" else {
            state.jointAngles.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        }
        "${state.modelType}#${state.posePreset}#$anglesKey"
    }
    meshCache[key]?.let { return it }

    val builtin = BuiltinModel3D.fromId(state.modelType)
    val mesh = when (builtin) {
        BuiltinModel3D.ANIME_BOY, BuiltinModel3D.BODY_MANNEQUIN ->
            Mesh3D.createAnimeMannequin(isFemale = false, posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.ANIME_GIRL ->
            Mesh3D.createAnimeMannequin(isFemale = true, posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.CAT -> Mesh3D.createCatModel(posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.BODY_SIT ->
            Mesh3D.createAnimeMannequin(isFemale = false, posePreset = "sit", angles = state.jointAngles)
        BuiltinModel3D.HEAD_MANNEQUIN -> {
            try {
                appContext.assets.open("3d_models/head_planar.glb").use { s -> GlbParser.parse(s).baseMesh }
            } catch (e: Exception) {
                Mesh3D.createHeadMannequin()
            }
        }
        BuiltinModel3D.CUBE -> Mesh3D.createCube(1.8f)
        BuiltinModel3D.SPHERE -> Mesh3D.createSphere(1.2f, 18, 24)
        BuiltinModel3D.CYLINDER -> Mesh3D.createCylinder(1.0f, 2.0f, 24)
        null -> {
            val rawKey = state.modelType
            if (rawKey.startsWith("file:")) {
                try {
                    val file = java.io.File(rawKey.substring(5))
                    if (file.exists()) {
                        file.inputStream().use { stream ->
                            Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                        }
                    } else {
                        Mesh3D.createCube(1.8f)
                    }
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse OBJ from file: $rawKey", e)
                    Mesh3D.createCube(1.8f)
                }
            } else if (rawKey.startsWith("uri:")) {
                try {
                    val uri = Uri.parse(rawKey.substring(4))
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                    } ?: Mesh3D.createCube(1.8f)
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse OBJ from uri: $rawKey", e)
                    Mesh3D.createCube(1.8f)
                }
            } else {
                Mesh3D.createCube(1.8f)
            }
        }
    }

    meshCache[key] = mesh
    trimMeshCache(keepKey = key)
    return mesh
}

/**
 * 缓存超限时优先淘汰姿态变体 (内置模型的 pose/关节角度组合), 保留导入的静态模型。
 * 导入模型被淘汰后下一次会在 onDraw 里重新解析文件 —— 正是 ANR/OOM 的老路
 * (线上 9fc2dbca2e / 3bde27f5cc), 所以这里绝不无差别 clear。
 */
private fun PaintViewModel.trimMeshCache(keepKey: String) {
    if (meshCache.size <= 24) return
    val iterator = meshCache.keys.iterator()
    while (meshCache.size > 12 && iterator.hasNext()) {
        val cacheKey = iterator.next()
        if (cacheKey == keepKey) continue
        val imported = cacheKey.startsWith("file:") || cacheKey.startsWith("uri:")
        if (!imported) {
            iterator.remove()
        }
    }
    // 极端情况: 全是导入模型 (理论上不超过图层数), 兜底清一次避免无限增长;
    // 刚构建好的网格必须保住, 否则这一帧白算、下一帧又要在 onDraw 里重来。
    if (meshCache.size > 48) {
        val kept = meshCache[keepKey]
        meshCache.clear()
        if (kept != null) {
            meshCache[keepKey] = kept
        }
    }
}

/**
 * 压入一条 3D 图层历史状态快照至撤销栈
 */
internal fun PaintViewModel.push3DUndoState(state: Model3DState) {
    val maxUndo = 30
    undo3DStack = (listOf(state) + undo3DStack).take(maxUndo)
    redo3DStack = emptyList()
}

/**
 * 撤销 3D 姿态与视角变换
 */
internal fun PaintViewModel.undo3D(): Boolean {
    val previous = undo3DStack.firstOrNull() ?: return false
    val current = currentLayer3DState ?: return false
    undo3DStack = undo3DStack.drop(1)
    redo3DStack = listOf(current) + redo3DStack
    update3DState(previous)
    if (undoToastEnabled) {
        showActionToast(R.string.toast_undo_3d_pose, R.drawable.ic_undo)
    }
    return true
}

/**
 * 重做 3D 姿态与视角变换
 */
internal fun PaintViewModel.redo3D(): Boolean {
    val next = redo3DStack.firstOrNull() ?: return false
    val current = currentLayer3DState ?: return false
    redo3DStack = redo3DStack.drop(1)
    undo3DStack = listOf(current) + undo3DStack
    update3DState(next)
    if (undoToastEnabled) {
        showActionToast(R.string.toast_redo_3d_pose, R.drawable.ic_redo)
    }
    return true
}

/**
 * 切换当前 3D 模型的姿态预设
 */
internal fun PaintViewModel.set3DPosePreset(preset: String) {
    val state = currentLayer3DState ?: return
    push3DUndoState(state)
    val updated = state.copy(posePreset = preset, jointAngles = emptyMap())
    update3DState(updated)
}

/**
 * 调整指定骨骼关节的微调角度
 */
internal fun PaintViewModel.set3DJointAngle(jointKey: String, angle: Float) {
    val state = currentLayer3DState ?: return
    val newAngles = state.jointAngles.toMutableMap()
    if (kotlin.math.abs(angle) < 0.01f) {
        newAngles.remove(jointKey)
    } else {
        newAngles[jointKey] = angle
    }
    val updated = state.copy(jointAngles = newAngles)
    update3DState(updated)
}

/**
 * 重置当前 3D 模型的骨骼微调角度
 */
internal fun PaintViewModel.reset3DJointAngles() {
    val state = currentLayer3DState ?: return
    push3DUndoState(state)
    val updated = state.copy(jointAngles = emptyMap())
    update3DState(updated)
}

/**
 * 新建内置 3D 参考图层
 */
internal fun PaintViewModel.create3DLayer(
    builtin: BuiltinModel3D = BuiltinModel3D.ANIME_BOY,
    customName: String? = null,
) {
    val displayName = customName ?: (getString(R.string.layer_3d_tag) + " (" + builtin.displayNameRes + ")")
    clearLayerSelection()

    runCore(after = {
        syncLayersFromNative()
        val curIdx = currentLayerIndex
        val layer = layers.getOrNull(curIdx)
        if (layer != null) {
            val initialState = Model3DState(
                layerId = layer.id,
                modelType = builtin.id,
                rotX = 18f,
                rotY = -35f,
                rotZ = 0f,
                panX = 0f,
                panY = 0f,
                zoom = 1.0f,
                lightYaw = 45f,
                lightPitch = 35f,
                renderMode = RenderMode3D.CLAY,
            )
            active3DLayers = active3DLayers + (layer.id to initialState)
            ensure3DLayerClearedInCore(layer.id)
            prewarm3DModel(initialState)
            active3DSelectedJointName = null
            showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
        }
    }) {
        ReverieCoreBridge.addLayer(displayName)
    }
}

/**
 * 导入外部 3D 模型 (.glb / .vrm / .pmx / .obj) 创建 3D 参考图层
 */
internal fun PaintViewModel.create3DLayerFromFile(
    uri: Uri,
    fileName: String,
) {
    // 3D 模型解析是重 IO + CPU 活 (大 obj 可到数万行): 必须离开主线程, 否则选完文件
    // 立刻 input dispatch timeout (1.6.2 线上 ANR 的主线程栈就停在 Mesh3D 解析里)。
    viewModelScope.launch(Dispatchers.IO) {
        val cleanName = fileName.replace(Regex("\\.(glb|vrm|pmx|obj)$", RegexOption.IGNORE_CASE), "")
        val displayName = getString(R.string.layer_3d_tag) + " (" + cleanName + ")"

        // 本地持久化缓存，防止设备重启或临时授权失效后丢失外部 3D 模型
        val modelsDir = java.io.File(appContext.filesDir, "3d_models").apply { mkdirs() }
        val safeFileName = "model_${System.currentTimeMillis()}_$fileName"
        val localFile = java.io.File(modelsDir, safeFileName)
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e("RP_3D", "Failed to cache 3D model file to app internal storage", e)
        }

        val modelPathKey = if (localFile.exists() && localFile.length() > 0) {
            "file:${localFile.absolutePath}"
        } else {
            "uri:$uri"
        }

        try {
            val stream = if (localFile.exists() && localFile.length() > 0) {
                localFile.inputStream()
            } else {
                appContext.contentResolver.openInputStream(uri)
            }
            val lowerName = fileName.lowercase()
            stream?.use { s ->
                when {
                    lowerName.endsWith(".glb") || lowerName.endsWith(".vrm") -> {
                        val skinned = GlbParser.parse(s)
                        skinnedModelCache[modelPathKey] = skinned
                    }
                    lowerName.endsWith(".pmx") -> {
                        val skinned = PmxParser.parse(s)
                        skinnedModelCache[modelPathKey] = skinned
                    }
                    else -> {
                        val mesh = Mesh3D.parseObj(BufferedReader(InputStreamReader(s)))
                        meshCache[modelPathKey] = mesh
                    }
                }
            }
        } catch (e: com.reverie.paint.model.MeshTooLargeException) {
            // 规模超预算: 明确告知用户, 不要静默失败 (否则用户只看到"没反应")
            Log.w("RP_3D", "3D model too large: $fileName", e)
            withContext(Dispatchers.Main.immediate) {
                showActionToast(R.string.toast_3d_model_too_large)
            }
            return@launch
        } catch (e: Exception) {
            Log.e("RP_3D", "Failed to parse imported 3D model file", e)
            return@launch
        }

        // 解析结果落到缓存后再回主线程改 UI 状态; 引擎调用照旧走 runCore 的队列。
        withContext(Dispatchers.Main.immediate) {
            clearLayerSelection()
            runCore(after = {
                syncLayersFromNative()
                val curIdx = currentLayerIndex
                val layer = layers.getOrNull(curIdx)
                if (layer != null) {
                    val initialState = Model3DState(
                        layerId = layer.id,
                        modelType = modelPathKey,
                        rotX = 15f,
                        rotY = -30f,
                        zoom = 1.0f,
                        renderMode = RenderMode3D.CLAY,
                    )
                    active3DLayers = active3DLayers + (layer.id to initialState)
                    ensure3DLayerClearedInCore(layer.id)
                    active3DSelectedJointName = null
                    showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
                }
            }) {
                ReverieCoreBridge.addLayer(displayName)
            }
        }
    }
}

/** 兼容旧接口别名 */
internal fun PaintViewModel.create3DLayerFromObj(uri: Uri, fileName: String) = create3DLayerFromFile(uri, fileName)

/**
 * 确保 3D 参考图层在底层 Krita 引擎中保持清空状态 (避免图层位图与实时硬件 Canvas 投影发生重影叠画)
 */
internal fun PaintViewModel.ensure3DLayerClearedInCore(layerId: Long) {
    val idx = layers.indexOfFirst { it.id == layerId }
    if (idx >= 0) {
        runCore(render = true, after = { notifyLayerChanged(forceThumbs = false) }) {
            ReverieCoreBridge.clearLayer(idx)
        }
    }
}

/**
 * 处理单指拖拽 3D 手势变换
 *
 * 骨骼模式下的轴向/符号来自 [Skeleton3D] 的绑定姿态几何:
 * - 垂直拖拽走俯仰轴, 手指向下时骨骼末端/朝向也向下;
 * - 水平拖拽走能产生横向摆动的轴 (沿骨轴旋转 = 原地自转, 屏幕上几乎无位移, 已自动淘汰),
 *   头/颈固定为绕骨轴转头。
 */
internal fun PaintViewModel.handle3DGesture(deltaX: Float, deltaY: Float) {
    val state = currentLayer3DState ?: return
    when (active3DInteractionMode) {
        Interaction3DMode.ROTATE -> {
            val newRotY = (state.rotY + deltaX * 0.45f) % 360f
            val newRotX = (state.rotX + deltaY * 0.45f).coerceIn(-89f, 89f)
            update3DState(state.copy(rotX = newRotX, rotY = newRotY))
        }
        Interaction3DMode.PAN -> {
            val factor = 0.0035f / state.zoom.coerceAtLeast(0.1f)
            val newPanX = (state.panX + deltaX * factor).coerceIn(-5.0f, 5.0f)
            val newPanY = (state.panY - deltaY * factor).coerceIn(-5.0f, 5.0f)
            update3DState(state.copy(panX = newPanX, panY = newPanY))
        }
        Interaction3DMode.LIGHT -> {
            val newYaw = (state.lightYaw + deltaX * 0.45f).coerceIn(-120f, 120f)
            val newPitch = (state.lightPitch - deltaY * 0.45f).coerceIn(-75f, 75f)
            update3DState(state.copy(lightYaw = newYaw, lightPitch = newPitch))
        }
        Interaction3DMode.BONE -> {
            val jointName = active3DSelectedJointName
            android.util.Log.d(
                "RP_3D_EDIT",
                "boneDrag joint=$jointName editMode=$jointPositionEditMode dx=$deltaX dy=$deltaY " +
                    "offsets=${currentLayer3DState?.jointRestOffsets}",
            )
            // 未选中关节点 (或选中的骨骼已随模型切换而失效) 时，滑动触摸空白处旋转模型整体视角
            val skeleton = if (jointName != null) skeletonForState(state) else null
            val jointIndex = if (jointName != null && skeleton != null) {
                skeleton.joints.indexOfFirst { it.name == jointName }
            } else {
                -1
            }
            if (jointName == null || jointIndex < 0) {
                val newRotY = (state.rotY + deltaX * 0.45f) % 360f
                val newRotX = (state.rotX + deltaY * 0.45f).coerceIn(-89f, 89f)
                update3DState(state.copy(rotX = newRotX, rotY = newRotY))
                return
            }
            // 关节位置微调模式: 同一套"点中选择 + 拖拽"交互, 但改的是位置而不是角度。
            // 竖直拖拽 = 沿骨骼局部 Y 平移 (与 PosePresets.buildTranslations 的 _ty 约定一致,
            // 存储单位 1/100 模型单位, 因此每像素 0.1 = 0.001 模型单位)。
            if (jointPositionEditMode) {
                // 改的是"绑定姿态"而非姿势: 网格不动, 只挪这根骨及其子链的旋转轴心。
                // 竖直 = Y, 水平 = X (屏幕右 -> 模型 +X)
                val kx = JointOffsetSpec.key(jointName, 0)
                val ky = JointOffsetSpec.key(jointName, 1)
                val curX = state.jointRestOffsets[kx] ?: 0f
                val curY = state.jointRestOffsets[ky] ?: 0f
                val nextX = (curX + deltaX * 0.03f).coerceIn(-JointOffsetSpec.RANGE, JointOffsetSpec.RANGE)
                val nextY = (curY - deltaY * 0.03f).coerceIn(-JointOffsetSpec.RANGE, JointOffsetSpec.RANGE)
                android.util.Log.d("RP_3D_EDIT", "apply nextX=$nextX nextY=$nextY (cur $curX,$curY)")
                if (kotlin.math.abs(nextX - curX) < 0.01f && kotlin.math.abs(nextY - curY) < 0.01f) return
                var next = state.jointRestOffsets
                next = JointOffsetSpec.withOffset(next, jointName, nextX, 0)
                next = JointOffsetSpec.withOffset(next, jointName, nextY, 1)
                update3DState(state.copy(jointRestOffsets = next))
                return
            }
            // 点中轴环后只改该轴, 手感与环一致
            val lockedAxis = active3DBoneAxis
            if (lockedAxis >= 0) {
                val pitchKey = "${jointName}_p"
                val yawKey = "${jointName}_y"
                val newAngles = state.jointAngles.toMutableMap()
                if (lockedAxis == 0) {
                    val cur = state.jointAngles[pitchKey] ?: 0f
                    val next = (cur + deltaY * 0.6f * skeleton!!.dragPitchSign(jointIndex)).coerceIn(-150f, 150f)
                    if (kotlin.math.abs(next - cur) < 0.01f) return
                    newAngles[pitchKey] = next
                } else {
                    val cur = state.jointAngles[yawKey] ?: 0f
                    val next = (cur + deltaX * 0.6f) % 360f
                    if (kotlin.math.abs(next - cur) < 0.01f) return
                    newAngles[yawKey] = next
                }
                update3DState(state.copy(jointAngles = newAngles))
                return
            }
            val pitchSign = skeleton!!.dragPitchSign(jointIndex)
            val swingAxis = skeleton.dragSwingAxis(jointIndex)
            val swingSign = skeleton.dragSwingSign(jointIndex)

            val pitchKey = "${jointName}_p"
            val swingKey = if (swingAxis == 2) "${jointName}_r" else "${jointName}_y"
            val curPitch = state.jointAngles[pitchKey] ?: 0f
            val curSwing = state.jointAngles[swingKey] ?: 0f
            val newPitch = (curPitch + deltaY * 0.6f * pitchSign).coerceIn(-150f, 150f)
            val newSwing = (curSwing + deltaX * 0.6f * swingSign) % 360f
            if (kotlin.math.abs(newPitch - curPitch) < 0.01f && kotlin.math.abs(newSwing - curSwing) < 0.01f) {
                return
            }
            val newAngles = state.jointAngles.toMutableMap()
            newAngles[pitchKey] = newPitch
            newAngles[swingKey] = newSwing
            update3DState(state.copy(jointAngles = newAngles))
        }
    }
}

/**
 * 按比例缩放当前 3D 模型
 */
internal fun PaintViewModel.zoom3DModel(scaleRatio: Float) {
    val state = currentLayer3DState ?: return
    val newZoom = (state.zoom * scaleRatio).coerceIn(0.1f, 10.0f)
    update3DState(state.copy(zoom = newZoom))
}

/**
 * 还原当前 3D 模型缩放到 100%
 */
internal fun PaintViewModel.reset3DZoom() {
    val state = currentLayer3DState ?: return
    update3DState(state.copy(zoom = 1.0f))
}

/**
 * 更新 3D 状态 (仅更新轻量内存模型，驱动视口硬件 Canvas 120Hz 实时重绘)
 */
internal fun PaintViewModel.update3DState(updated: Model3DState) {
    active3DLayers = active3DLayers + (updated.layerId to updated)
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.postInvalidate()
}

/**
 * 复位当前 3D 图层视角
 */
internal fun PaintViewModel.reset3DView() {
    val state = currentLayer3DState ?: return
    update3DState(
        state.copy(
            rotX = 18f,
            rotY = -35f,
            rotZ = 0f,
            panX = 0f,
            panY = 0f,
            zoom = 1.0f,
            lightYaw = 45f,
            lightPitch = 35f,
        )
    )
}

/**
 * 切换 3D 图层渲染风格 (素模 / 光影 / 线框)
 */
internal fun PaintViewModel.set3DRenderMode(mode: RenderMode3D) {
    val state = currentLayer3DState ?: return
    update3DState(state.copy(renderMode = mode))
}

/**
 * 异步将 3D 烘焙结果写回底层 Krita 颜料图层 (在工作线程渲染，彻底杜绝主线程阻塞)
 */
internal fun PaintViewModel.commit3DLayerPixels(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val layerIdx = layers.indexOfFirst { it.id == layerId }
    if (layerIdx < 0) return

    viewModelScope.launch(Dispatchers.Default) {
        val mesh = getMeshForState(state)
        val w = docWidth.coerceAtLeast(1)
        val h = docHeight.coerceAtLeast(1)

        val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h) ?: return@launch
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

        runCore(render = true, after = {
            notifyLayerChanged(forceThumbs = false)
        }) {
            val targetIdx = layers.indexOfFirst { it.id == layerId }
            if (targetIdx >= 0) {
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(targetIdx)
                ReverieCoreBridge.clearLayer(targetIdx)
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                ReverieCoreBridge.setCurrentLayer(cur)
            }
            stampBmp.recycle()
            if (rendered !== stampBmp && !rendered.isRecycled) {
                rendered.recycle()
            }
        }
    }
}

/**
 * 栅格化当前 3D 图层 (将其转化为普通光栅像素图层，解除 3D 绑定)
 */
internal fun PaintViewModel.rasterize3DLayer(layerId: Long) {
    commit3DLayerPixels(layerId)
    active3DLayers = active3DLayers - layerId
    interactiveSlots.remove(layerId)
    active3DSelectedJointName = null
    live3DPreviewBitmap = null
    showActionToast(R.string.toast_3d_rasterized, R.drawable.ic_layers)
}

/**
 * 同步将所有活跃 3D 参考图层渲染并烘焙至底层 Krita 颜料图层 (在导出与持久化前调用)
 */
internal fun PaintViewModel.commitAllActive3DLayersSync() {
    for ((layerId, state) in active3DLayers) {
        val layerIdx = layers.indexOfFirst { it.id == layerId }
        if (layerIdx >= 0) {
            val mesh = getMeshForState(state)
            val w = docWidth.coerceAtLeast(1)
            val h = docHeight.coerceAtLeast(1)
            val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h)
            if (rendered != null) {
                val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)
                ReverieCoreBridge.clearLayer(layerIdx)
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(layerIdx)
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                ReverieCoreBridge.setCurrentLayer(cur)
                stampBmp.recycle()
                if (rendered !== stampBmp && !rendered.isRecycled) {
                    rendered.recycle()
                }
            }
        }
    }
}

/**
 * 提取当前 3D 图层的轮廓线稿，在新图层中创建线稿层
 */
internal fun PaintViewModel.extract3DOutlines(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val mesh = getMeshForState(state)
    val wireState = state.copy(renderMode = RenderMode3D.WIREFRAME, wireframeColor = 0xFF1A1A1AL)

    val w = docWidth.coerceAtLeast(1)
    val h = docHeight.coerceAtLeast(1)
    val rendered = gl3dRenderer.renderToBitmap(mesh, wireState, w, h) ?: return
    val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

    val outlineLayerName = getString(R.string.layer_3d_extract_outline)
    runCore(render = true, after = {
        syncLayersFromNative()
        showActionToast(R.string.toast_3d_outline_extracted, R.drawable.ic_magicwand)
    }) {
        ReverieCoreBridge.addLayer(outlineLayerName)
        ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
        stampBmp.recycle()
        if (rendered !== stampBmp && !rendered.isRecycled) {
            rendered.recycle()
        }
    }
}
