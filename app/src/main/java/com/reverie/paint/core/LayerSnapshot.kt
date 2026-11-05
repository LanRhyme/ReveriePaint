/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 图层快照探针 (P0 验收用, 见 docs/plans/CORE-SPLIT.md)
 *
 * `setprop debug.reverie.snapshot 1` -> 每次同步图层时用旧路径 + 快照路径各算一遍,
 *   逐字段比对并把差异与时序打到 logcat (tag: ReverieSnapshot)
 * `setprop debug.reverie.snapshot 2` -> 除比对之外, 面板数据改用快照结果 (等价性验证)
 *
 * 关闭 (0) 时唯一开销是一次返回 0 的 JNI 调用。
 */
package com.reverie.paint.core

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 与 `rp_layer_snapshot` 一一对应的解析结果 (字段偏移见 reverie_core.h) */
internal data class SnapshotLayer(
    val index: Int,
    val id: Long,
    val name: String,
    val visible: Boolean,
    val locked: Boolean,
    val alphaLocked: Boolean,
    val isGroup: Boolean,
    val nodeType: Int,
    val depth: Int,
    val colorLabel: Int,
    val clipped: Boolean,
    val alphaInherited: Boolean,
    val isBackground: Boolean,
    val soloed: Boolean,
    val opacity: Double,
    val blendMode: String,
    val isStrokeLayer: Boolean,
    val strokeSize: Int,
    val strokeColor: Int,
    val strokePosition: Int,
    val strokeOpacity: Int,
    val fillColor: Int,
    val passThrough: Boolean,
)

internal data class SnapshotPage(
    val rev: Int,
    val currentLayer: Int,
    val soloActive: Boolean,
    val layers: List<SnapshotLayer>,
)

internal object LayerSnapshotProbe {

    private const val TAG = "ReverieSnapshot"

    /**
     * 复用的直接缓冲: allocateDirect 本身成本不低(每次几十 µs), 图层同步在滑动笔刷
     * 参数时会被反复触发, 因此按容量缓存一块, 只在不够大时重新分配。
     */
    private var scratch: ByteBuffer? = null

    fun read(): SnapshotPage? = try {
        readFromNative()
    } catch (e: LinkageError) {
        // .so 与 APP 版本不匹配 (预编译库落后 / 该 ABI 未编进新 JNI):
        // 这是安装包问题而非文档问题, 绝不能让它变成主线程 FATAL —— 保持上次镜像。
        reportLinkageMismatch(e)
        null
    }

    private fun readFromNative(): SnapshotPage? {
        var buffer = scratch
        if (buffer == null) {
            val size = ReverieCoreBridge.nativeSnapshotSize()
            if (size <= SnapshotCodec.HEADER_BYTES) return null
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
            scratch = buffer
        }
        buffer.clear()
        var rev = ReverieCoreBridge.nativeSnapshotFill(buffer)
        if (rev < 0) {
            // native 返回 -needed: 容量不够, 扩容后重试一次 (图层数变化时才会发生)
            val needed = -rev
            if (needed <= SnapshotCodec.HEADER_BYTES) return null
            buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.LITTLE_ENDIAN)
            scratch = buffer
            rev = ReverieCoreBridge.nativeSnapshotFill(buffer)
            if (rev < 0) return null
        }
        val page = SnapshotCodec.parse(buffer)
        if (page == null) Log.w(TAG, "bad magic from native snapshot")
        return page
    }

    fun toUiState(layer: SnapshotLayer): PaintViewModel.LayerUiState = PaintViewModel.LayerUiState(
        index = layer.index,
        id = layer.id,
        name = layer.name,
        visible = layer.visible,
        locked = layer.locked,
        alphaLocked = layer.alphaLocked,
        isGroup = layer.isGroup,
        nodeType = layer.nodeType,
        depth = layer.depth,
        colorLabel = layer.colorLabel,
        clipped = layer.clipped,
        alphaInherited = layer.alphaInherited,
        isBackground = layer.isBackground,
        soloed = layer.soloed,
        opacity = layer.opacity,
        blendMode = layer.blendMode,
        isStrokeLayer = layer.isStrokeLayer,
        strokeSize = layer.strokeSize,
        strokeColor = layer.strokeColor,
        strokePosition = layer.strokePosition,
        strokeOpacity = layer.strokeOpacity,
        fillColor = layer.fillColor,
        passThrough = layer.passThrough,
    )

    /** 快照暂不可用 (解析失败/未就绪): 限流告警, 保持上一次镜像 */
    private var lastUnavailableLogNs = 0L

    fun reportUnavailable() {
        val now = System.nanoTime()
        if (now - lastUnavailableLogNs < 2_000_000_000L) return
        lastUnavailableLogNs = now
        Log.w(TAG, "layer snapshot unavailable; keeping previous mirror")
    }

    /**
     * 原生库与 APP 版本不匹配: 安装包里该 ABI 的 libreverie_jni.so 没有导出快照入口
     * (历史事故: 2026-10-31 v1.6.1 的 armeabi-v7a 包缺 nativeSnapshotMode)。
     * 这类问题只能靠换包解决, 但至少要在 logcat 里说清楚, 而不是留一条裸的
     * UnsatisfiedLinkError 堆栈。
     */
    private fun reportLinkageMismatch(e: LinkageError) {
        val now = System.nanoTime()
        if (now - lastLinkageLogNs < 2_000_000_000L) return
        lastLinkageLogNs = now
        Log.e(
            TAG,
            "native library does not match this APK (missing layer-snapshot JNI); " +
                "install the build matching this device ABI. Keeping previous layer mirror.",
            e,
        )
    }

    private var lastLinkageLogNs = 0L
}

/**
 * 快照二进制解析 (纯函数, 无 JNI/Log 依赖, 可 JVM 单测)。
 *
 * 布局见 `app/src/main/cpp/reverie_core.h`: header 32 字节, 每层 152 字节定长,
 * 字段偏移: id@0 opacity@8 index@16 node_type@20 depth@24 color_label@28
 * stroke_size@32 stroke_position@36 stroke_opacity@40 stroke_color@44
 * fill_color@48 flags@52 name@56[64] blend_mode@120[32]
 */
internal object SnapshotCodec {

    const val HEADER_BYTES = 32
    const val LAYER_BYTES = 152
    const val MAGIC = 0x43535052 // "RPSC" little-endian
    private const val NAME_OFFSET = 56
    private const val NAME_CAP = 64
    private const val BLEND_OFFSET = 120
    private const val BLEND_CAP = 32

    const val FLAG_VISIBLE = 1 shl 0
    const val FLAG_LOCKED = 1 shl 1
    const val FLAG_ALPHA_LOCKED = 1 shl 2
    const val FLAG_GROUP = 1 shl 3
    const val FLAG_STROKE = 1 shl 4
    const val FLAG_BACKGROUND = 1 shl 5
    const val FLAG_SOLOED = 1 shl 6
    const val FLAG_CLIPPED = 1 shl 7
    const val FLAG_ALPHA_INHERITED = 1 shl 8
    const val FLAG_PASS_THROUGH = 1 shl 9
    const val FLAG_SOLO_SHADOWED = 1 shl 10

    fun parse(buffer: ByteBuffer): SnapshotPage? {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(0)
        if (buffer.int != MAGIC) return null
        buffer.int // abi
        val rev = buffer.int
        val count = buffer.int
        val currentLayer = buffer.int
        val soloActive = buffer.int != 0
        val stride = buffer.int
        if (stride < LAYER_BYTES) return null
        val layers = ArrayList<SnapshotLayer>(count)
        for (i in 0 until count) {
            val base = HEADER_BYTES + i * stride
            if (base + LAYER_BYTES > buffer.capacity()) return null
            val flags = buffer.getInt(base + 52)
            layers.add(
                SnapshotLayer(
                    index = buffer.getInt(base + 16),
                    id = buffer.getLong(base),
                    name = readCString(buffer, base + NAME_OFFSET, NAME_CAP),
                    visible = if (soloActive) (flags and FLAG_SOLO_SHADOWED) == 0
                    else (flags and FLAG_VISIBLE) != 0,
                    locked = (flags and FLAG_LOCKED) != 0,
                    alphaLocked = (flags and FLAG_ALPHA_LOCKED) != 0,
                    isGroup = (flags and FLAG_GROUP) != 0,
                    nodeType = buffer.getInt(base + 20),
                    depth = buffer.getInt(base + 24),
                    colorLabel = buffer.getInt(base + 28),
                    clipped = (flags and FLAG_CLIPPED) != 0,
                    alphaInherited = (flags and FLAG_ALPHA_INHERITED) != 0,
                    isBackground = (flags and FLAG_BACKGROUND) != 0,
                    soloed = (flags and FLAG_SOLOED) != 0,
                    opacity = buffer.getDouble(base + 8),
                    blendMode = readCString(buffer, base + BLEND_OFFSET, BLEND_CAP),
                    isStrokeLayer = (flags and FLAG_STROKE) != 0,
                    strokeSize = buffer.getInt(base + 32),
                    strokeColor = buffer.getInt(base + 44),
                    strokePosition = buffer.getInt(base + 36),
                    strokeOpacity = buffer.getInt(base + 40),
                    fillColor = buffer.getInt(base + 48),
                    passThrough = (flags and FLAG_PASS_THROUGH) != 0,
                ),
            )
        }
        return SnapshotPage(rev = rev, currentLayer = currentLayer, soloActive = soloActive, layers = layers)
    }

    private fun readCString(buffer: ByteBuffer, offset: Int, cap: Int): String {
        var end = offset
        while (end < offset + cap && buffer.get(end).toInt() != 0) end++
        val bytes = ByteArray(end - offset)
        for (i in bytes.indices) bytes[i] = buffer.get(offset + i)
        return String(bytes, Charsets.UTF_8)
    }
}
