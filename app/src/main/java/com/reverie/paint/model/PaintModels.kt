/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Data models shared across the painting UI.
 * The complete Krita built-in tool set, grouped the way Krita's toolbox
 * groups them. Brush-family tools (BRUSH / ERASER / SMUDGE) reuse the brush
 * panel; the rest carry their own tool-option panel.
 */
enum class ToolGroup(
    val label: String,
) {
    BRUSH("笔刷"),
    FILL("填充"),
    SHAPES("形状"),
    SELECTION("选择"),
    TRANSFORM("变换"),
    VIEW("视图"),
    OTHER("其他"),
}

enum class Tool(
    val id: String,
    val label: String,
    val group: ToolGroup,
) {
    // Brush family: share the brush panel (Krita's FreehandBrush tools)
    BRUSH("brush", "画笔", ToolGroup.BRUSH),
    ERASER("eraser", "橡皮擦", ToolGroup.BRUSH),
    SMUDGE("smudge", "混合涂抹", ToolGroup.BRUSH),

    // Fill family
    FILL("fill", "填充", ToolGroup.FILL),
    GRADIENT("gradient", "渐变", ToolGroup.FILL),

    // Shape tools (Krita's shape tools)
    SHAPES("shapes", "形状", ToolGroup.SHAPES),
    LINE("line", "直线", ToolGroup.SHAPES),
    RECT("rect", "矩形", ToolGroup.SHAPES),
    ELLIPSE("ellipse", "椭圆", ToolGroup.SHAPES),
    POLYGON("polygon", "多边形", ToolGroup.SHAPES),
    POLYLINE("polyline", "多段线", ToolGroup.SHAPES),

    // Selection tools (Krita's selection tools)
    SELECT_RECT("select_rect", "矩形选择", ToolGroup.SELECTION),
    SELECT_ELLIPSE("select_ellipse", "椭圆选择", ToolGroup.SELECTION),
    SELECT_POLYGON("select_polygon", "多边形选择", ToolGroup.SELECTION),
    LASSO("lasso", "套索选择", ToolGroup.SELECTION),
    MAGICWAND("magicwand", "连续选择", ToolGroup.SELECTION),
    SELECT_SIMILAR("select_similar", "相似色选择", ToolGroup.SELECTION),

    // Transform tools
    TRANSFORM("transform", "变换", ToolGroup.TRANSFORM),
    CROP("crop", "裁剪", ToolGroup.TRANSFORM),

    // Other tools
    PICKER("picker", "拾色", ToolGroup.OTHER),
    TEXT("text", "文本", ToolGroup.OTHER),
    LIQUIFY("liquify", "液化", ToolGroup.OTHER),
    MEASURE("measure", "测量", ToolGroup.OTHER),
    PATH("path", "路径", ToolGroup.SHAPES),
    REFERENCE("reference", "参考", ToolGroup.VIEW),
    SHORTCUT("shortcut", "快捷操作", ToolGroup.VIEW),
    QUICK_BRUSH("quick_brush", "快捷笔刷", ToolGroup.VIEW),
    QUICK_COLOR("quick_color", "快捷颜色", ToolGroup.VIEW),
    QUICK_LAYER("quick_layer", "快捷图层", ToolGroup.VIEW),
    SYMMETRY("symmetry", "对称", ToolGroup.OTHER),
    PERSPECTIVE("perspective", "透视", ToolGroup.OTHER),
    ;

    companion object {
        fun fromId(id: String): Tool = entries.find { it.id == id } ?: BRUSH
    }
}

/**
 * 绘画主界面上按下系统返回键/返回手势时的兜底行为。
 *
 * 仅在 BackHandler 链把所有浮层 (面板 / 对话框 / 变换 / 工具切换) 都消费完之后才生效,
 * 因此这三个值只描述"画布处于干净状态时按返回"的结果:
 * - [NONE] 无行为: 忽略返回, 防止误触退出画布 (历史默认)
 * - [OPEN_SETTINGS] 弹出设置面板
 * - [EXIT] 退出画布 (有未保存修改时仍走保存确认流程)
 *
 * `id` 用于 SharedPreferences 持久化, 非法/历史脏值由 [fromId] 回退到 [NONE]。
 */
enum class BackKeyAction(
    val id: String,
) {
    NONE("none"),
    OPEN_SETTINGS("open_settings"),
    EXIT("exit"),
    ;

    companion object {
        fun fromId(id: String?): BackKeyAction = entries.find { it.id == id } ?: NONE
    }
}

/**
 * 界面动效速度档位：
 * - [NORMAL]: 标准 (1.0x / ~200ms)，保持莫兰迪柔和微动效
 * - [FAST]: 极速 (0.45x / ~90ms)，面板展开收起在 0.1s 以内，保障极速绘画心流
 * - [OFF]: 关闭 (0x / 瞬时)，彻底消除等待与重绘开销
 */
enum class UiAnimationSpeed(
    val id: String,
) {
    NORMAL("normal"),
    FAST("fast"),
    OFF("off"),
    ;

    companion object {
        fun fromId(id: String?): UiAnimationSpeed = entries.find { it.id == id } ?: NORMAL
    }
}

/** 套索操作模式: 0: 自由描画, 1: 折线, 2: 自由描画与折线 (多次操作) */
object LassoSubMode {
    const val FREEHAND = 0
    const val POLYLINE = 1
    const val HYBRID = 2
}

/** Brush preset: name + default size + softness-ish hint */
data class BrushPreset(
    val name: String,
    val size: Double,
)

/** A saved project or stack (画集) entry */
data class Project(
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
    val dpi: Int = 300,
    val filePath: String = "",
    val previewPath: String = "",
    val strokeCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val lastModified: Long = 0L,
    val layerCount: Int = 1,
    val selectedLayerIndex: Int = -1,
    val colorMode: String = "RGB 8-bit",
    val fileSize: Long = 0L,
    val isFolder: Boolean = false,
    val folderPath: String = "",
    val items: List<Project> = emptyList(),
    /** Whether the project file embeds a drawing-process recording (回放可用). */
    val hasRecording: Boolean = false,
    /** Whether this project is an auto-saved recovery draft (自动保存草稿 / 异常退出恢复). */
    val isAutoSaved: Boolean = false,
    /** Associated master project file path if this is an autosave draft */
    val masterFilePath: String = "",
    /** Whether the project is an animation canvas (含动画时间轴, meta.layers[].animated) */
    val isAnimation: Boolean = false,
)

/** A canvas size preset for the create page */
data class CanvasPreset(
    val name: String,
    val width: Int,
    val height: Int,
)

/** A snapshot in the persistent recent auto-save history */
data class AutoSaveSnapshot(
    val id: String,
    val fileName: String,
    val displayName: String,
    val masterPath: String,
    val timestamp: Long,
    val strokeCount: Int,
    val layerCount: Int,
    val fileSize: Long,
    val thumbPath: String,
    val isEmergency: Boolean = false,
)
