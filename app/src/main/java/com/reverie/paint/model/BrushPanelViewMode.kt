/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Display modes for the brush library preset list.
 */
enum class BrushPanelViewMode(val prefValue: String) {
    /** Icon + brush name + S-curve stroke preview (default). */
    STROKE_ICON_NAME("stroke_icon_name"),

    /** Brush name + full-width S-curve stroke preview. */
    STROKE_NAME("stroke_name"),

    /** 2-column compact cards with icon + name + stroke preview. */
    TWO_COLUMN("two_column"),

    /** Compact list with icon + brush name. */
    LIST("list"),

    /** 3-column icon grid. */
    GRID("grid");

    val isGrid: Boolean get() = this == GRID || this == TWO_COLUMN
    val gridColumns: Int get() = if (this == TWO_COLUMN) 2 else 3
    val showsStrokePreview: Boolean get() = this == STROKE_ICON_NAME || this == STROKE_NAME || this == TWO_COLUMN
    val showsPresetIcon: Boolean get() = this != STROKE_NAME

    companion object {
        fun fromPrefs(modeValue: String?, legacyGridView: Boolean): BrushPanelViewMode {
            if (!modeValue.isNullOrBlank()) {
                entries.firstOrNull { it.prefValue == modeValue }?.let { return it }
            }
            return if (legacyGridView) GRID else STROKE_ICON_NAME
        }
    }
}
