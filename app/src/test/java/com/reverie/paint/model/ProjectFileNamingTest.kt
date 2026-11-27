/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectFileNamingTest {

    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun `renaming artwork to an unused name succeeds and preserves file content`() {
        val root = tempDir.newFolder("projects")
        val src = File(root, "草稿A.revp").apply { writeText("new-artwork-bytes") }

        val result = renameProjectFileSafely(src, isFolder = false, newName = "插画最终版")

        assertTrue(result is ProjectRenameResult.Success)
        val success = result as ProjectRenameResult.Success
        val expected = File(root, "插画最终版.revp")
        assertEquals(expected.absolutePath, success.newFile.absolutePath)
        assertFalse(src.exists())
        assertTrue(expected.exists())
        assertEquals("new-artwork-bytes", expected.readText())
    }

    @Test
    fun `renaming artwork to existing artwork name fails without overwriting old artwork`() {
        val root = tempDir.newFolder("projects")
        val oldArtwork = File(root, "旧画布.revp").apply { writeText("old-artwork-bytes") }
        val newArtwork = File(root, "新画布.revp").apply { writeText("new-artwork-bytes") }

        val result = renameProjectFileSafely(newArtwork, isFolder = false, newName = "旧画布")

        assertEquals(ProjectRenameResult.Failure(ProjectRenameError.ALREADY_EXISTS), result)
        assertTrue(oldArtwork.exists())
        assertTrue(newArtwork.exists())
        assertEquals("old-artwork-bytes", oldArtwork.readText())
        assertEquals("new-artwork-bytes", newArtwork.readText())
    }

    @Test
    fun `renaming artwork to existing artwork name with different extension fails and keeps both`() {
        val root = tempDir.newFolder("projects")
        val kraArtwork = File(root, "角色设定.kra").apply { writeText("kra-bytes") }
        val revpArtwork = File(root, "草稿.revp").apply { writeText("revp-bytes") }

        val result = renameProjectFileSafely(revpArtwork, isFolder = false, newName = "角色设定")

        assertEquals(ProjectRenameResult.Failure(ProjectRenameError.ALREADY_EXISTS), result)
        assertTrue(kraArtwork.exists())
        assertTrue(revpArtwork.exists())
        assertEquals("kra-bytes", kraArtwork.readText())
        assertEquals("revp-bytes", revpArtwork.readText())
    }

    @Test
    fun `renaming artwork to its own current name returns Unchanged`() {
        val root = tempDir.newFolder("projects")
        val artwork = File(root, "线稿.revp").apply { writeText("lineart-bytes") }

        val result = renameProjectFileSafely(artwork, isFolder = false, newName = "  线稿  ")

        assertEquals(ProjectRenameResult.Unchanged, result)
        assertTrue(artwork.exists())
        assertEquals("lineart-bytes", artwork.readText())
    }

    @Test
    fun `renaming folder to existing folder name fails without merging or overwriting`() {
        val root = tempDir.newFolder("projects")
        val existingFolder = File(root, "人物合集").apply { mkdirs() }
        val existingInner = File(existingFolder, "a.revp").apply { writeText("a") }
        val sourceFolder = File(root, "新建画集").apply { mkdirs() }
        val sourceInner = File(sourceFolder, "b.revp").apply { writeText("b") }

        val result = renameProjectFileSafely(sourceFolder, isFolder = true, newName = "人物合集")

        assertEquals(ProjectRenameResult.Failure(ProjectRenameError.ALREADY_EXISTS), result)
        assertTrue(existingFolder.isDirectory)
        assertTrue(sourceFolder.isDirectory)
        assertEquals("a", existingInner.readText())
        assertEquals("b", sourceInner.readText())
    }

    @Test
    fun `renaming autosave draft preserves autosave suffix and detects conflict in gallery dir`() {
        val projectsDir = tempDir.newFolder("projects")
        val autoDir = tempDir.newFolder("autosave")
        val existingMaster = File(projectsDir, "旧画布.revp").apply { writeText("master") }
        val autosaveDraft = File(autoDir, "未命名作品.autosave.revp").apply { writeText("draft") }

        val conflict = renameProjectFileSafely(
            source = autosaveDraft,
            isFolder = false,
            newName = "旧画布",
            extraConflictDirs = listOf(projectsDir),
        )
        assertEquals(ProjectRenameResult.Failure(ProjectRenameError.ALREADY_EXISTS), conflict)
        assertTrue(existingMaster.exists())
        assertTrue(autosaveDraft.exists())

        val ok = renameProjectFileSafely(
            source = autosaveDraft,
            isFolder = false,
            newName = "独立草稿",
            extraConflictDirs = listOf(projectsDir),
        )
        assertTrue(ok is ProjectRenameResult.Success)
        val renamed = File(autoDir, "独立草稿.autosave.revp")
        assertTrue(renamed.exists())
        assertEquals("draft", renamed.readText())
    }

    @Test
    fun `renaming normal artwork detects conflict against autosave draft in extraConflictDirs`() {
        val projectsDir = tempDir.newFolder("projects")
        val autoDir = tempDir.newFolder("autosave")
        val autosaveDraft = File(autoDir, "草稿B.autosave.revp").apply { writeText("draft-b") }
        val artwork = File(projectsDir, "作品A.revp").apply { writeText("work-a") }

        val result = renameProjectFileSafely(
            source = artwork,
            isFolder = false,
            newName = "草稿B",
            extraConflictDirs = listOf(autoDir),
        )

        assertEquals(ProjectRenameResult.Failure(ProjectRenameError.ALREADY_EXISTS), result)
        assertTrue(autosaveDraft.exists())
        assertTrue(artwork.exists())
    }

    @Test
    fun `blank and invalid names are rejected`() {
        val root = tempDir.newFolder("projects")
        val artwork = File(root, "作品.revp").apply { writeText("data") }

        assertEquals(
            ProjectRenameResult.Failure(ProjectRenameError.BLANK),
            renameProjectFileSafely(artwork, isFolder = false, newName = ""),
        )
        assertEquals(
            ProjectRenameResult.Failure(ProjectRenameError.BLANK),
            renameProjectFileSafely(artwork, isFolder = false, newName = "   "),
        )
        listOf(".", "..", "a/b", "a\\b", "a\u0000b").forEach { invalid ->
            assertEquals(
                "Expected INVALID_CHARS for '$invalid'",
                ProjectRenameResult.Failure(ProjectRenameError.INVALID_CHARS),
                renameProjectFileSafely(artwork, isFolder = false, newName = invalid),
            )
        }
        assertTrue(artwork.exists())
    }

    @Test
    fun `resolveUniqueMoveDestination avoids same dir and disambiguates collisions`() {
        val root = tempDir.newFolder("projects")
        val folder = File(root, "画集").apply { mkdirs() }
        val src = File(root, "foo.revp").apply { writeText("src") }

        assertNull(resolveUniqueMoveDestination(src, root))
        assertEquals(File(folder, "foo.revp").absolutePath, resolveUniqueMoveDestination(src, folder)?.absolutePath)

        File(folder, "foo.kra").writeText("existing-kra")
        assertEquals(File(folder, "foo (1).revp").absolutePath, resolveUniqueMoveDestination(src, folder)?.absolutePath)

        File(folder, "foo (1).revp").writeText("existing-1")
        assertEquals(File(folder, "foo (2).revp").absolutePath, resolveUniqueMoveDestination(src, folder)?.absolutePath)
    }
}
