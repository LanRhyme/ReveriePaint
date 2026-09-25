/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap

/**
 * JNI bridge to the C++ ReverieCore engine (Krita-based painting core).
 * All methods are thin wrappers over the native library.
 */
object ReverieCoreBridge {
    /**
     * The engine links Qt6Core (for Krita's QObject-based classes) and
     * KF6I18n, whose KCatalogStaticData ctor calls
     * QAndroidApplication::context() and invokes getAssets() on it without
     * checking validity. In a pure Compose Activity Qt has no registered
     * context, so context() returns null and the call crashes with
     * "GetMethodID received NULL jclass".
     *
     * QtActivity normally registers the Activity via QtNative.setActivity.
     * We emulate that with reflection: set the private static m_activity
     * field so Qt's context() returns our Activity. The class loader is
     * registered first for the same reason (Qt finds its classes through
     * the app class loader).
     */
    fun initQtAndroid() {
        try {
            val qtNative = Class.forName("org.qtproject.qt.android.QtNative")
            qtNative
                .getMethod("setClassLoader", ClassLoader::class.java)
                .invoke(null, this.javaClass.classLoader)
            android.util.Log.i("RP-BRIDGE", "QtNative.setClassLoader OK")

            val activityField = qtNative.getDeclaredField("m_activity")
            activityField.isAccessible = true
            val activityClass = Class.forName("android.app.Activity")
            val act = mainActivity
            if (act != null) {
                activityField.set(null, activityClass.cast(act))
                android.util.Log.i("RP-BRIDGE", "QtNative.m_activity registered")
            } else {
                android.util.Log.w("RP-BRIDGE", "mainActivity null, skip activity registration")
            }
        } catch (t: Throwable) {
            android.util.Log.e("RP-BRIDGE", "Qt init failed", t)
        }
    }

    /** Called from MainActivity.onResume so Qt always has a live context. */
    fun syncActivity(activity: android.app.Activity) {
        mainActivity = activity
        // The native library may already be loaded; re-register the
        // activity if the field update matters for later context() calls.
        try {
            val qtNative = Class.forName("org.qtproject.qt.android.QtNative")
            val activityField = qtNative.getDeclaredField("m_activity")
            activityField.isAccessible = true
            val activityClass = Class.forName("android.app.Activity")
            activityField.set(null, activityClass.cast(activity))
            android.util.Log.i("RP-BRIDGE", "syncActivity: m_activity registered OK")
        } catch (t: Throwable) {
            android.util.Log.e("RP-BRIDGE", "syncActivity failed", t)
        }
    }

    @Volatile
    var mainActivity: android.app.Activity? = null

    @Volatile
    private var nativeLoaded = false

    /**
     * Must be called from MainActivity.onCreate AFTER the activity exists,
     * so Qt's C++ side (initJNI) reads a live activity reference into its
     * global g_jActivity cache. Calling it earlier (in a class-init block)
     * would cache null and KF6I18n's context() calls would still crash.
     */
    fun ensureLoaded() {
        if (nativeLoaded) return
        nativeLoaded = true
        initQtAndroid()
        System.loadLibrary("reverie_jni")
    }

    external fun newDocument(
        w: Int,
        h: Int,
    ): Boolean

    external fun newDocumentEx(
        w: Int,
        h: Int,
        infiniteCanvas: Boolean,
    ): Boolean

    external fun setInfiniteCanvas(infinite: Boolean)

    external fun isInfiniteCanvas(): Boolean

    external fun fillBackground(color: String)

    external fun clearCanvas()

    external fun addLayer(name: String)

    external fun removeLayer(index: Int)

    external fun setCurrentLayer(index: Int)

    external fun layerCount(): Int

    external fun layerName(index: Int): String

    external fun setLayerBlendMode(
        index: Int,
        opId: String,
    )

    external fun layerBlendMode(index: Int): String

    external fun setLayerVisible(
        index: Int,
        visible: Boolean,
    )

    external fun layerVisible(index: Int): Boolean

    external fun currentLayerIndex(): Int

    external fun layerId(index: Int): Long

    // ===== 动画: 帧 / 轨道 / 关键帧 =====
    // 每条轨道 = 一个图层; 帧数据由 Krita 的 KisRasterKeyframeChannel 持有

    external fun animationEnabled(): Boolean

    external fun animationCurrentTime(): Int

    external fun setAnimationCurrentTime(
        time: Int,
        recordUndo: Boolean,
    )

    external fun animationFramerate(): Int

    external fun setAnimationFramerate(fps: Int)

    external fun animationLength(): Int

    external fun animationPlaybackRange(): IntArray

    external fun setAnimationPlaybackRange(
        start: Int,
        end: Int,
    )

    /**
     * 全局洋葱皮配置 (KisImageConfig) + 应用到所有动画位图图层。
     *
     * @param tintBackwardArgb 过去帧着色色板 (ARGB, alpha 忽略), 0 = 不改
     * @param tintForwardArgb  未来帧着色色板 (ARGB, alpha 忽略), 0 = 不改
     */
    external fun configureOnionSkin(
        enabled: Boolean,
        prev: Int,
        next: Int,
        maxOpacity: Int,
        tintFactor: Int,
        tintBackwardArgb: Int,
        tintForwardArgb: Int,
    )

    /** 显式离散偏移与衰减透明度洋葱皮配置 (供仅关键帧模式与自定义衰减曲线使用) */
    external fun configureOnionSkinExplicit(
        enabled: Boolean,
        offsets: IntArray,
        opacities: IntArray,
        tintFactor: Int = 100,
        tintBackwardArgb: Int = 0,
        tintForwardArgb: Int = 0,
    )

    external fun anyLayerOnionSkin(): Boolean

    /** 读回洋葱皮全局配置: [过去色 ARGB, 未来色 ARGB, 着色强度 0~255] */
    external fun onionSkinConfig(): IntArray

    /**
     * 丢弃洋葱皮图层缓存。**改动画布任何一帧的像素后都要调** ——
     * 洋葱皮缓存的失效判据是 (currentTime, configSeqNo, channelHash),
     * 不含帧内像素改动, 不调就会看到邻帧的旧叠影。
     * 内部对未开洋葱皮的文档直接返回, 可无条件调用。
     */
    external fun flushOnionSkinCaches()

    external fun revAssetNames(): Array<String>

    external fun revAssetBytes(name: String): ByteArray?

    external fun importKeyframeFromBitmap(
        layerIndex: Int,
        time: Int,
        bitmap: Bitmap,
    ): Boolean

    external fun storeRevAsset(
        name: String,
        data: ByteArray,
    )

    external fun layerAnimated(index: Int): Boolean

    external fun layerAnimatable(index: Int): Boolean

    external fun enableLayerAnimation(index: Int): Boolean

    external fun hasKeyframe(
        layerIndex: Int,
        time: Int,
    ): Boolean

    external fun keyframeCount(layerIndex: Int): Int

    external fun keyframeTimes(layerIndex: Int): IntArray

    external fun addKeyframe(
        layerIndex: Int,
        time: Int,
    ): Boolean

    external fun addDuplicateKeyframe(
        layerIndex: Int,
        time: Int,
    ): Boolean

    external fun removeKeyframe(
        layerIndex: Int,
        time: Int,
    ): Boolean

    external fun copyKeyframe(
        layerIndex: Int,
        fromTime: Int,
        toTime: Int,
    ): Boolean

    external fun cloneKeyframe(
        layerIndex: Int,
        fromTime: Int,
        toTime: Int,
    ): Boolean

    external fun moveKeyframe(
        layerIndex: Int,
        fromTime: Int,
        toTime: Int,
    ): Boolean

    external fun previousKeyframeTime(
        layerIndex: Int,
        time: Int,
    ): Int

    external fun nextKeyframeTime(
        layerIndex: Int,
        time: Int,
    ): Int

    external fun keyframeDuration(
        layerIndex: Int,
        time: Int,
    ): Int

    external fun setAllKeyframesDuration(
        layerIndex: Int,
        duration: Int,
    ): Boolean

    external fun setSelectedKeyframesDuration(
        layerIndex: Int,
        selectedTimes: IntArray,
        duration: Int,
    ): Boolean

    /**
     * 把图层 [layerIndex] 在 [time] 处的关键帧画面渲染进 [bitmap] (时间轴帧块缩略图)。
     * 引擎侧走 writeToDevice 拷帧, 不改变文档 currentTime, 画布不会跳帧。
     */
    external fun renderKeyframeThumb(
        layerIndex: Int,
        time: Int,
        bitmap: Bitmap,
    ): Boolean

    /**
     * 渲染关键帧完整画布内容到指定 [bitmap] (供透光台对位 Shift & Trace 使用)。
     */
    external fun renderKeyframeFull(
        layerIndex: Int,
        time: Int,
        bitmap: Bitmap,
    ): Boolean

    /** 帧缩略图缓存代际: 变化即表示 UI 侧 (图层, 帧号) 缓存整体过期。 */
    external fun keyframeThumbGen(): Long

    /**
     * 取走并清空帧缩略图"精准失效"脏帧集合, 交替 [layer0, time0, layer1, ...]。
     * 命中的帧必须重渲染, 其余帧在代际未变时照常复用。
     * 只能在 reverie-render 线程调用。
     */
    external fun takeDirtyKeyframeThumbs(): IntArray

    /**
     * 播放期洋葱皮抑制: true = 播放开始 (隐藏洋葱皮), false = 暂停/停止 (还原)。
     * 只能在 reverie-render 线程调用 (与其他引擎调用同线程串行)。
     */
    external fun setOnionSkinSuppressed(suppressed: Boolean)

    /** 自动中割: 在 timeA 与 timeB 之间按 t(0~1) 距离场插值生成中间帧并写入 targetTime */
    external fun animationGenerateInbetween(
        layerIndex: Int,
        timeA: Int,
        timeB: Int,
        targetTime: Int,
        t: Float,
    ): Boolean

    /** 查询关键帧色标 (0=无, 1=原画, 2=中割, 3=草稿) */
    external fun animationKeyframeTag(
        layerIndex: Int,
        time: Int,
    ): Int

    /** 设置关键帧色标并记录撤销 */
    external fun animationSetKeyframeTag(
        layerIndex: Int,
        time: Int,
        tag: Int,
    )

    /** 读取所有关键帧色标: 交替 [layer, time, tag, ...] */
    external fun animationAllKeyframeTags(): IntArray

    /** 查询轨道末帧持续帧数 (默认 1) */
    external fun animationLastFrameHold(layerIndex: Int): Int

    /** 设置轨道末帧持续帧数并记录撤销 */
    external fun animationSetLastFrameHold(
        layerIndex: Int,
        hold: Int,
        recordUndo: Boolean,
    )

    /** 读取所有轨道末帧持续帧数: 交替 [layer, hold, ...] */
    external fun animationAllLastFrameHolds(): IntArray

    external fun setToolMode(mode: Int)

    external fun drawPolygon(
        xs: IntArray,
        ys: IntArray,
        count: Int,
        closed: Boolean,
    )

    external fun gradientFill(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        type: Int,
        repeat: Int = 0,
        reverse: Boolean = false,
    )

    external fun selectShape(
        kind: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    )

    external fun selectPolygon(
        xs: IntArray,
        ys: IntArray,
        count: Int,
    )

    external fun moveLayerContent(
        dx: Int,
        dy: Int,
    )

    external fun cropCanvas(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    )

    external fun contentBounds(): IntArray?

    external fun contentBoundsLayers(layers: IntArray): IntArray?

    external fun applyTransform(
        xscale: Double,
        yscale: Double,
        xshear: Double,
        yshear: Double,
        rotationRad: Double,
        xtranslate: Double,
        ytranslate: Double,
        originX: Double = -1.0,
        originY: Double = -1.0,
    ): Boolean

    external fun applyPerspectiveTransform(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        origX: Double,
        origY: Double,
        origW: Double,
        origH: Double,
    ): Boolean

    external fun applyWarpMeshTransform(
        origXs: DoubleArray,
        origYs: DoubleArray,
        transfXs: DoubleArray,
        transfYs: DoubleArray,
        count: Int,
        origX: Double,
        origY: Double,
        origW: Double,
        origH: Double,
    ): Boolean

    external fun setBrushSecondaryColor(color: String)

    external fun floodFillAt(
        x: Int,
        y: Int,
        tolerance: Int,
        sampleMerged: Boolean = true,
        expand: Int = 0,
        feather: Int = 0,
        closeGap: Int = 4,
    )

    external fun setBrushSize(size: Double)

    /** 当前预设 Size 压感曲线求值：pressure(0..1) → 实际笔刷直径比例(0..1)，光标环同源缩放 */
    external fun brushPressureFraction(pressure: Float): Float

    external fun setBrushColor(color: String)

    external fun setBrushOpacity(opacity: Double)

    external fun loadBrushPresetsFromDir(dirPath: String): Int

    external fun loadBrushResources(dirPath: String): Int

    external fun loadBrushPreset(index: Int): Boolean

    external fun brushPresetCount(): Int

    external fun brushPresetDefaults(index: Int): DoubleArray

    external fun brushPresetName(index: Int): String

    external fun brushPresetThumbData(index: Int): ByteArray

    external fun currentBrushPreset(): Int

    external fun setBrushFlow(flow: Double)

    external fun setBrushSmudgeRate(rate: Double)

    external fun setBrushSmudgeLength(length: Double)

    external fun setBrushAirbrush(enabled: Boolean, rate: Double)

    external fun strokeAirbrushTick(): Boolean

    external fun setPresetIsEraser(isEraser: Boolean)

    external fun setBrushSpacing(v: Double)

    external fun setBrushAngle(v: Double)

    external fun setBrushScatter(v: Double)

    external fun setBrushFade(v: Double)

    external fun setBrushSoftness(v: Double)

    external fun setBrushRatio(v: Double)

    external fun setBrushSharpness(v: Double)

    external fun setBrushRotation(v: Double)

    external fun setBrushCompositeOp(op: String)
    external fun setBrushPressureDynamics(
        enabled: Boolean,
        sizeStrength: Double,
        opacityStrength: Double,
        flowStrength: Double,
        curveType: Int,
    )
    external fun setBrushFollowDirection(enabled: Boolean)
    external fun setBrushJitter(jitterAngle: Double, jitterSize: Double)
    external fun setBrushMirror(flipX: Boolean, flipY: Boolean)
    external fun setBrushAntiAliasing(level: Int)
    external fun setBrushTipAsset(assetName: String): Boolean

    external fun touchStrokeStart(
        x: Double,
        y: Double,
        pressure: Double,
    )

    external fun touchStrokeMove(
        x: Double,
        y: Double,
        pressure: Double,
    )

    /** Batched stroke transport: [coords] holds [x,y,pressure] triplets,
     *  [count] is the triplet count. Drains every pending sample in one JNI
     *  call (no intermediate points lost) and returns true when a flush
     *  painted new ink — only then does the caller schedule a render. */
    external fun touchStrokeMoveBatch(coords: FloatArray, count: Int): Boolean

    /** Flush the pending stroke-start dot when no movement arrived yet
     *  (pen-down instant-ink feedback). Returns true when ink was painted. */
    external fun touchStrokeKickIdle(): Boolean

    external fun touchStrokeEnd()

    external fun touchStrokeCancel()

    external fun renderToBuffer(
        bitmap: Bitmap,
        forceFull: Boolean = false,
        outDirty: IntArray? = null,
    ): Boolean

    /** Dirty content exists but the projection recomposite is still running. */
    external fun renderPendingDirty(): Boolean

    external fun pickColorAt(
        x: Int,
        y: Int,
        currentLayerOnly: Boolean = false,
    ): String?

    external fun undo()

    external fun redo()

    external fun canUndo(): Boolean

    external fun canRedo(): Boolean

    external fun setUndoCaptureEnabled(on: Boolean)

    external fun clearUndoHistory()

    external fun beginUndoMacro(text: String = "")

    external fun endUndoMacro()

    external fun liquify(
        fx: Int,
        fy: Int,
        tx: Int,
        ty: Int,
        strength: Double,
        mode: Int,
    )

    /** Open one undo transaction for a whole liquify drag. A non-empty
     *  [layers] list liquifies those layers together (multi-select). */
    external fun liquifyBegin(layers: IntArray? = null)

    /** Commit the liquify drag transaction. */
    external fun liquifyEnd()

    /** Revert the whole liquify drag. */
    external fun liquifyCancel()

    external fun setLiquifyBrushSize(size: Double)

    /** Move several layers' content at once (one undo step). */
    external fun moveLayerContentLayers(
        layers: IntArray?,
        dx: Int,
        dy: Int,
    )

    external fun applyTransformLayers(
        layers: IntArray?,
        xscale: Double,
        yscale: Double,
        xshear: Double,
        yshear: Double,
        rotationRad: Double,
        xtranslate: Double,
        ytranslate: Double,
        originX: Double = -1.0,
        originY: Double = -1.0,
    ): Boolean

    external fun applyTransformLayersEx(
        layers: IntArray?,
        xscale: Double,
        yscale: Double,
        xshear: Double,
        yshear: Double,
        rotationRad: Double,
        xtranslate: Double,
        ytranslate: Double,
        originX: Double = -1.0,
        originY: Double = -1.0,
        copyOnly: Boolean = false,
    ): Boolean

    external fun lassoSelect(
        xs: IntArray,
        ys: IntArray,
        count: Int,
    )

    external fun selectContiguousAt(
        x: Int,
        y: Int,
        tolerance: Int,
        sampleMerged: Boolean = true,
        expand: Int = 0,
        feather: Int = 0,
        closeGap: Int = 4,
    )

    external fun selectSimilarAt(
        x: Int,
        y: Int,
        tolerance: Int,
        sampleMerged: Boolean = true,
    )

    external fun lassoFill(
        xs: IntArray,
        ys: IntArray,
        count: Int,
    )

    external fun lassoClear(
        xs: IntArray,
        ys: IntArray,
        count: Int,
    )

    external fun drawText(
        x: Int,
        y: Int,
        text: String,
        fontSize: Double,
    )

    external fun stampBitmap(
        x: Int,
        y: Int,
        bitmap: android.graphics.Bitmap,
    )

    external fun setShapeStrokeWidth(w: Double)

    external fun setShapeFilled(f: Boolean)

    external fun drawShape(
        kind: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        filled: Boolean,
    )

    external fun savePng(path: String): Boolean

    external fun exportJpg(
        path: String,
        quality: Int = 90,
    ): Boolean

    external fun exportPsd(path: String): Boolean

    external fun saveRevp(
        path: String,
        extraMetaJson: String = "",
        recordingBlob: ByteArray? = null,
    ): Boolean

    /**
     * 上一次 .revp 保存的阶段耗时(ms)与产物体积, 供性能标尺显示:
     * `[total, snapshot, encode, write, pngCount, pngBytes, fileBytes, async]`。
     * 典型用法是保存后或每秒钟取一次 (见 `PaintViewModel.pollSaveStats`)。
     */
    external fun revpSaveStats(): LongArray?

    external fun saveRevpAsync(
        path: String,
        extraMetaJson: String = "",
        recordingBlob: ByteArray? = null,
    ): Boolean

    external fun loadRevp(path: String): Boolean

    external fun loadPsd(path: String): Boolean

    external fun saveKra(path: String): Boolean

    external fun setAuthorProfile(json: String): Boolean

    external fun loadPng(path: String): Boolean

    external fun renderLayerThumb(
        index: Int,
        bitmap: Bitmap,
    ): Boolean

    external fun startTransformPreview(bitmap: Bitmap): Boolean

    external fun startTransformPreviewLayers(
        layers: IntArray,
        bitmap: Bitmap,
    ): Boolean

    external fun startTransformPreviewLayersEx(
        layers: IntArray,
        bitmap: Bitmap,
        copyOnly: Boolean,
    ): Boolean

    external fun cancelTransformPreview()

    external fun docWidth(): Int

    external fun docHeight(): Int

    // ---- Full layer system ----
    external fun addGroupLayer(name: String): Int

    external fun copyLayer(index: Int): Int

    external fun copySelectionToNewLayer(cut: Boolean): Int

    external fun clearLayer(index: Int)

    external fun setLayerName(
        index: Int,
        name: String,
    )

    external fun setLayerOpacity(
        index: Int,
        opacity: Double,
    )

    // Opacity change without pushing an undo step (slider drag preview); the
    // drag release commits through setLayerOpacity so one drag = one undo step
    external fun setLayerOpacityDirect(
        index: Int,
        opacity: Double,
    )

    external fun layerOpacity(index: Int): Double

    external fun setLayerLocked(
        index: Int,
        locked: Boolean,
    )

    external fun layerLocked(index: Int): Boolean

    external fun setLayerAlphaLocked(
        index: Int,
        locked: Boolean,
    )

    external fun layerAlphaLocked(index: Int): Boolean

    external fun setLayerColorLabel(
        index: Int,
        label: Int,
    )

    external fun layerColorLabel(index: Int): Int

    external fun layerIsGroup(index: Int): Boolean

    /** NodeType 值域: 0paint/1group/2fill/3adjust/5clone/10-13四mask, 越界-1 */
    external fun layerNodeType(index: Int): Int

    /** 创建真 KisAdjustmentLayer（滤镜类型为 reverie-f<filterType>，参数 p1-p4，可选 lut） */
    external fun createAdjustmentLayer(
        name: String,
        filterType: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
        lut: ByteArray? = null,
    ): Boolean

    /** 预览调整层滤镜配置（不入撤销栈，用于滑块拖拽实时渲染） */
    external fun previewAdjustmentLayerConfig(
        index: Int,
        filterType: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
        lut: ByteArray? = null,
    ): Boolean

    /** 更新调整层滤镜配置并入撤销栈；lut 仅曲线 LUT(768B)/渐变映射(1024B)时非空；origConfigJson 为进入面板前的配置快照 */
    external fun setAdjustmentLayerConfig(
        index: Int,
        filterType: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
        lut: ByteArray? = null,
        origConfigJson: String? = null,
    ): Boolean

    /** 读取调整层当前配置 JSON（{"type","p1"-"p4","lut":base64}），非调整层返回空串 */
    external fun getAdjustmentLayerConfig(index: Int): String
    // 原生填充层换色 (KisGeneratorLayer + reverie-solid-color); 非填充层返回 false
    external fun setFillLayerColor(index: Int, colorArgb: Int): Boolean

    external fun layerDepth(index: Int): Int

    external fun layerBackground(index: Int): Boolean

    external fun setBackgroundColor(
        color: Int,
        commit: Boolean = true,
    )

    external fun layerClipped(index: Int): Boolean

    external fun setLayerClipped(
        index: Int,
        clipped: Boolean,
    )

    external fun flipLayerHorizontal(index: Int)

    external fun flipLayerVertical(index: Int)

    external fun flipCanvasHorizontal()

    external fun flipCanvasVertical()

    external fun fillLayer(index: Int)

    external fun stampVisibleLayers(): Int

    external fun moveLayer(
        from: Int,
        to: Int,
    ): Boolean

    external fun moveLayerAbove(
        from: Int,
        above: Int,
    ): Boolean

    external fun moveLayerToGroup(
        from: Int,
        group: Int,
    ): Boolean

    external fun moveLayersToGroup(
        fromIndices: IntArray,
        group: Int,
    ): Boolean

    external fun moveLayerRelative(
        from: Int,
        target: Int,
        placeAbove: Boolean,
    ): Boolean

    external fun moveLayersRelative(
        fromIndices: IntArray,
        target: Int,
        placeAbove: Boolean,
    ): Boolean

    external fun moveLayerUp(index: Int): Boolean

    external fun moveLayerDown(index: Int): Boolean

    external fun moveLayerOut(index: Int): Boolean

    external fun addMaskToLayer(
        layerIndex: Int,
        maskType: Int,
    ): Boolean

    external fun removeMask(layerIndex: Int): Boolean

    external fun rasterizeLayer(index: Int): Boolean

    external fun flattenGroup(index: Int): Boolean

    external fun setGroupPassThrough(
        index: Int,
        passThrough: Boolean,
    ): Boolean

    external fun groupPassThrough(index: Int): Boolean

    external fun mergeDown(index: Int): Boolean

    external fun soloLayer(index: Int)

    external fun layerSoloed(index: Int): Boolean

    external fun soloActive(): Boolean

    external fun layerSoloKeep(): IntArray

    external fun layerSoloRawMode(): Boolean

    external fun toggleLayerSoloRawMode()

    external fun applyFilter(
        index: Int,
        filterId: Int,
    )

    external fun applyFilterMulti(
        indices: IntArray,
        filterId: Int,
    )

    external fun addLayerWithType(
        name: String,
        type: Int,
        fillColor: Int,
    ): Boolean

    external fun beginFilterPreview(index: Int)
    external fun beginFilterPreviewMulti(indices: IntArray)

    external fun applyFilterPreview(
        index: Int,
        filterType: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
    )

    external fun applyFilterPreviewMulti(
        indices: IntArray,
        filterType: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
    )

    external fun applyCurvesLUTPreview(
        index: Int,
        lutR: ByteArray,
        lutG: ByteArray,
        lutB: ByteArray,
    )

    external fun applyCurvesLUTPreviewMulti(
        indices: IntArray,
        lutR: ByteArray,
        lutG: ByteArray,
        lutB: ByteArray,
    )

    external fun applyGradientMapPreview(
        index: Int,
        gradientLut: IntArray,
    )

    external fun applyGradientMapPreviewMulti(
        indices: IntArray,
        gradientLut: IntArray,
    )

    external fun commitFilter(
        index: Int,
        filterName: String,
    )

    external fun commitFilterMulti(
        indices: IntArray,
        filterName: String,
    )

    external fun cancelFilter(index: Int)
    external fun cancelFilterMulti(indices: IntArray)

    external fun selectionFromLayer(index: Int): Boolean

    external fun hasSelection(): Boolean

    external fun selectionMask(): ByteArray

    external fun selectionOverlayScaled(
        vw: Int,
        vh: Int,
    ): IntArray?

    external fun previewLassoOverlay(
        xs: IntArray,
        ys: IntArray,
        count: Int,
        vw: Int,
        vh: Int,
    ): IntArray?

    external fun selectAll()

    external fun invertSelection()

    external fun setSelectionMode(mode: Int)

    external fun selectionMode(): Int

    external fun featherSelection(radius: Int)

    external fun expandSelection(px: Int)

    external fun contractSelection(px: Int)

    external fun smoothSelection(radius: Int)

    external fun clearSelection()

    // 存储选区 (选区历史与存储槽位)
    external fun saveCurrentSelection(name: String? = null): Int
    external fun loadStoredSelection(index: Int, mode: Int = 0): Boolean
    external fun deleteStoredSelection(index: Int): Boolean
    external fun updateStoredSelection(index: Int): Boolean
    external fun renameStoredSelection(index: Int, name: String): Boolean
    external fun storedSelectionCount(): Int
    external fun storedSelectionName(index: Int): String
    external fun storedSelectionId(index: Int): String
    external fun storedSelectionThumbnail(index: Int, w: Int, h: Int): IntArray?
    external fun clearStoredSelections()
}

