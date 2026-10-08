/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/**
 * CONTEXT_EXT's v2 customization byte. Legacy 0/1 values retain their meaning.
 * Bit 1 qualifies a customized preset: leave its spacing untouched, as live painting does.
 * Older readers still consume one byte and treat 3 as customized; they retain the old
 * spacing bug but can parse the recording. This does not snapshot a preset's resources.
 */
internal object RecordedBrushOverrides {
    private const val CUSTOMIZED = 1
    private const val KEEP_PRESET_SPACING = 2

    fun encode(customized: Boolean, spacingCustomized: Boolean): Int = when {
        !customized -> 0
        spacingCustomized -> CUSTOMIZED
        else -> CUSTOMIZED or KEEP_PRESET_SPACING
    }

    fun applyShape(preset: Int, flags: Int): Boolean = preset < 0 || flags and CUSTOMIZED != 0

    fun applySpacing(preset: Int, flags: Int): Boolean =
        applyShape(preset, flags) && (preset < 0 || flags and KEEP_PRESET_SPACING == 0)
}
