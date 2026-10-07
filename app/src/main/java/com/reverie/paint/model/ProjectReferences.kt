/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import java.io.File

data class ReferenceViewState(
    val open: Boolean = false,
    val grayscale: Boolean = false,
    val flipped: Boolean = false,
    val tab: Int = 0,
    val collapsed: Boolean = false,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

/** Local-only limits and path matching; no project format or binary protocol. */
object ProjectReferences {
    const val MAX_IMAGES = 50
    const val MAX_BYTES = 64 * 1024 * 1024

    fun validState(state: ReferenceViewState): ReferenceViewState =
        if (state.tab in 0..1 && state.zoom.isFinite() && state.zoom in .02f..128f &&
            state.rotation.isFinite() && state.panX.isFinite() && state.panY.isFinite()) state
        else ReferenceViewState()

    fun movedPath(path: String, source: String, target: String): String? = when {
        path == source -> target
        path.startsWith(source + File.separator) -> target + path.removePrefix(source)
        else -> null
    }
}
