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
    SELECT_MAGNETIC("select_magnetic", "磁性套索", ToolGroup.SELECTION),
    SELECT_SIMILAR("select_similar", "相似色选择", ToolGroup.SELECTION),

    // Transform tools
    TRANSFORM("transform", "变换", ToolGroup.TRANSFORM),
    MOVE("move", "移动", ToolGroup.TRANSFORM),
    CROP("crop", "裁剪", ToolGroup.TRANSFORM),

    // Other tools
    PICKER("picker", "拾色", ToolGroup.OTHER),
    TEXT("text", "文本", ToolGroup.OTHER),
    LIQUIFY("liquify", "液化", ToolGroup.OTHER),
    MEASURE("measure", "测量", ToolGroup.OTHER),
    PATH("path", "路径", ToolGroup.SHAPES),
    REFERENCE("reference", "参考", ToolGroup.VIEW),
    ;

    companion object {
        fun fromId(id: String): Tool = entries.find { it.id == id } ?: BRUSH
    }
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
    val filePath: String = "",
    val previewPath: String = "",
    val strokeCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val lastModified: Long = 0L,
    val layerCount: Int = 1,
    val colorMode: String = "RGB 8-bit",
    val fileSize: Long = 0L,
    val isFolder: Boolean = false,
    val folderPath: String = "",
    val items: List<Project> = emptyList(),
    /** Whether the project file embeds a drawing-process recording (回放可用). */
    val hasRecording: Boolean = false,
    /** Whether this project is an auto-saved recovery draft (自动保存草稿 / 异常退出恢复). */
    val isAutoSaved: Boolean = false,
)

/** A canvas size preset for the create page */
data class CanvasPreset(
    val name: String,
    val width: Int,
    val height: Int,
)
