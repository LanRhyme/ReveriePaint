package com.reverie.paint.model

/**
 * Data models shared across the painting UI.
 * 画世界 Pro style tool set - the common set users expect in a painter app.
 */
enum class Tool(
    val id: String,
    val label: String,
) {
    BRUSH("brush", "画笔"),
    HAND("hand", "手"),
    ERASER("eraser", "橡皮"),
    PICKER("picker", "取色"),
    FILL("fill", "填充"),
    LASSO("lasso", "套索"),
    MAGICWAND("magicwand", "魔棒"),
    LINE("line", "直线"),
    RECT("rect", "矩形"),
    ELLIPSE("ellipse", "椭圆"),
    TEXT("text", "文字"),
    SMUDGE("smudge", "涂抹"),
    LIQUIFY("liquify", "液化"),
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

/** A saved project entry */
data class Project(
    val name: String,
    val width: Int,
    val height: Int,
)

/** A canvas size preset for the create page */
data class CanvasPreset(
    val name: String,
    val width: Int,
    val height: Int,
)
