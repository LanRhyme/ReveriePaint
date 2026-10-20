package com.reverie.paint.model

internal enum class StrokeFinish {
    NONE,
    COMMIT,
    CANCEL,
}

internal object StrokeDispatch {
    fun toolForMove(strokeStarted: Boolean, strokeTool: Tool?, current: Tool): Tool {
        return if (strokeStarted && strokeTool != null) strokeTool else current
    }

    fun finish(strokeStarted: Boolean, strokeTool: Tool?, current: Tool, isCancel: Boolean): StrokeFinish {
        if (!strokeStarted || strokeTool == null) return StrokeFinish.NONE
        if (isCancel || !sameFamily(strokeTool, current)) return StrokeFinish.CANCEL
        return StrokeFinish.COMMIT
    }

    fun sameFamily(a: Tool, b: Tool): Boolean = family(a) == family(b)

    private fun family(tool: Tool): Int = when (tool) {
        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> 0
        Tool.LIQUIFY -> 1
        else -> tool.ordinal + 100
    }
}
