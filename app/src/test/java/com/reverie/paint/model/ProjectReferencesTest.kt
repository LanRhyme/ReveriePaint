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
}
