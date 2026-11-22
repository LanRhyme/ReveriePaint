/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProjectReferencesTest {
    @Test fun `valid display choices retain transforms`() {
        val state = ReferenceViewState(true, true, true, 1, true, 2.5f, -37f, -123f, 876f)
        assertEquals(state, ProjectReferences.validState(state))
    }

    @Test fun `invalid display values fall back safely`() {
        for (state in listOf(ReferenceViewState(zoom = Float.NaN), ReferenceViewState(zoom = 0f),
            ReferenceViewState(zoom = 129f), ReferenceViewState(tab = 2),
            ReferenceViewState(rotation = Float.NEGATIVE_INFINITY),
            ReferenceViewState(panX = Float.POSITIVE_INFINITY), ReferenceViewState(panY = Float.NaN))) {
            assertEquals(ReferenceViewState(), ProjectReferences.validState(state))
        }
    }

    private fun path(vararg parts: String) = parts.joinToString(File.separator)

    @Test fun `rename keeps a single artwork mapping`() {
        assertEquals(path("projects", "new.revp"), ProjectReferences.movedPath(
            path("projects", "old.revp"), path("projects", "old.revp"), path("projects", "new.revp")))
    }

    @Test fun `folder move follows nested artworks`() {
        assertEquals(path("other", "nested", "a.revp"), ProjectReferences.movedPath(
            path("projects", "nested", "a.revp"), "projects", "other"))
    }

    @Test fun `similar prefix cannot move or delete another artwork`() {
        assertNull(ProjectReferences.movedPath(path("projects-backup", "a.revp"), "projects", "other"))
        assertNull(ProjectReferences.movedPath("a.revp.bak", "a.revp", "b.revp"))
    }

    @Test
    fun `画布页与参考图片页的视图变换互不影响`() {
        val state = ReferenceViewState(
            zoom = 2.5f, rotation = -37f, panX = -123f, panY = 876f,
            canvasZoom = 0.75f, canvasRotation = 12f, canvasPanX = 34f, canvasPanY = -56f,
        )
        val valid = ProjectReferences.validState(state)
        assertEquals(2.5f, valid.zoom)
        assertEquals(-37f, valid.rotation)
        assertEquals(0.75f, valid.canvasZoom)
        assertEquals(12f, valid.canvasRotation)
        assertEquals(34f, valid.canvasPanX)
        assertEquals(-56f, valid.canvasPanY)
    }

    @Test
    fun `画布页非法变换会回退到默认状态`() {
        for (state in listOf(
            ReferenceViewState(canvasZoom = Float.NaN),
            ReferenceViewState(canvasZoom = 0f),
            ReferenceViewState(canvasZoom = 200f),
            ReferenceViewState(canvasRotation = Float.NEGATIVE_INFINITY),
            ReferenceViewState(canvasPanX = Float.POSITIVE_INFINITY),
            ReferenceViewState(canvasPanY = Float.NaN),
        )) {
            assertEquals(ReferenceViewState(), ProjectReferences.validState(state))
        }
    }
}
