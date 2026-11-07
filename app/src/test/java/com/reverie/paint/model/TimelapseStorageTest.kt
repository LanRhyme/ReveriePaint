/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TimelapseStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cleanProjectName strips autosave and emergency suffixes`() {
        assertEquals("未命名作品15", TimelapseStorage.cleanProjectName("未命名作品15.autosave"))
        assertEquals("未命名作品15", TimelapseStorage.cleanProjectName("未命名作品15.emergency"))
        assertEquals("未命名作品15", TimelapseStorage.cleanProjectName("未命名作品15"))
        assertEquals("我的画作", TimelapseStorage.cleanProjectName("  我的画作.autosave  "))
    }

    @Test
    fun `resolveFolderKey identifies root and nested stacks correctly`() {
        val filesDir = tempFolder.newFolder("files")
        val rootDir = File(filesDir, "projects").apply { mkdirs() }
        val stackA = File(rootDir, "画集A").apply { mkdirs() }
        val stackB = File(stackA, "子画集B").apply { mkdirs() }

        // 根目录工程 -> 无画集前缀
        val rootProj = File(rootDir, "未命名作品15.revp")
        assertEquals("", TimelapseStorage.resolveFolderKey(filesDir, rootProj.absolutePath))

        // 画集A工程 -> 画集A
        val stackProj = File(stackA, "未命名作品15.revp")
        assertEquals("画集A", TimelapseStorage.resolveFolderKey(filesDir, stackProj.absolutePath))

        // 子画集B工程 -> 画集A_子画集B
        val subStackProj = File(stackB, "未命名作品15.revp")
        assertEquals("画集A_子画集B", TimelapseStorage.resolveFolderKey(filesDir, subStackProj.absolutePath))

        // 自动保存草稿通过关联的 masterFilePath 映射回原画集
        val autoSaveDir = File(filesDir, "autosave").apply { mkdirs() }
        val autoDraft = File(autoSaveDir, "未命名作品15.autosave.revp")
        assertEquals("画集A", TimelapseStorage.resolveFolderKey(filesDir, autoDraft.absolutePath, stackProj.absolutePath))

        // 未存盘新工程草稿 -> 归为根前缀
        assertEquals("", TimelapseStorage.resolveFolderKey(filesDir, autoDraft.absolutePath, null))
    }

    @Test
    fun `resolveTimelapseDirName associates autosave draft with master project`() {
        val filesDir = tempFolder.newFolder("files")
        val rootDir = File(filesDir, "projects").apply { mkdirs() }
        val autoSaveDir = File(filesDir, "autosave").apply { mkdirs() }

        val masterFile = File(rootDir, "未命名作品15.revp")
        val autoSaveFile = File(autoSaveDir, "未命名作品15.autosave.revp")

        // 正式工程与草稿文件解析出的延时摄影目录名完全一致
        val masterDirName = TimelapseStorage.resolveTimelapseDirName(
            filesDir = filesDir,
            filePath = masterFile.absolutePath,
            projectName = "未命名作品15",
        )
        val draftDirName = TimelapseStorage.resolveTimelapseDirName(
            filesDir = filesDir,
            filePath = autoSaveFile.absolutePath,
            projectName = "未命名作品15",
        )

        assertEquals("未命名作品15", masterDirName)
        assertEquals("未命名作品15", draftDirName)
        assertEquals(masterDirName, draftDirName)
    }

    @Test
    fun `resolveTimelapseDirName isolates same project name across stacks`() {
        val filesDir = tempFolder.newFolder("files")
        val rootDir = File(filesDir, "projects").apply { mkdirs() }
        val stackA = File(rootDir, "画集A").apply { mkdirs() }
        val stackB = File(rootDir, "画集B").apply { mkdirs() }

        val projRoot = File(rootDir, "未命名作品.revp")
        val projA = File(stackA, "未命名作品.revp")
        val projB = File(stackB, "未命名作品.revp")

        val rootDirName = TimelapseStorage.resolveTimelapseDirName(filesDir, projRoot.absolutePath)
        val aDirName = TimelapseStorage.resolveTimelapseDirName(filesDir, projA.absolutePath)
        val bDirName = TimelapseStorage.resolveTimelapseDirName(filesDir, projB.absolutePath)

        assertEquals("未命名作品", rootDirName)
        assertEquals("画集A__未命名作品", aDirName)
        assertEquals("画集B__未命名作品", bDirName)

        // 三者互不相同，杜绝同名工程碰撞
        val dirSet = setOf(rootDirName, aDirName, bDirName)
        assertEquals(3, dirSet.size)
    }

    @Test
    fun `resolveTimelapseFramesDir migrates legacy flat directory for stack projects`() {
        val filesDir = tempFolder.newFolder("files")
        val rootDir = File(filesDir, "projects").apply { mkdirs() }
        val stackA = File(rootDir, "画集A").apply { mkdirs() }
        val timelapseRoot = File(filesDir, "timelapse").apply { mkdirs() }

        // 模拟历史旧版：画集工程的帧保存在扁平目录 timelapse/作品1/
        val legacyFlatDir = File(timelapseRoot, "作品1").apply { mkdirs() }
        File(legacyFlatDir, "frame_00000.webp").writeText("frame-data")

        val projA = File(stackA, "作品1.revp")
        val resolvedDir = TimelapseStorage.resolveTimelapseFramesDir(filesDir, projA.absolutePath, "作品1")

        val expectedDir = File(timelapseRoot, "画集A__作品1")
        assertEquals(expectedDir.absolutePath, resolvedDir?.absolutePath)
        assertTrue(expectedDir.exists())
        assertTrue(File(expectedDir, "frame_00000.webp").exists())
        assertFalse(legacyFlatDir.exists())
    }

    @Test
    fun `resolveTimelapseFramesDir migrates legacy frames directory next to revp`() {
        val filesDir = tempFolder.newFolder("files")
        val rootDir = File(filesDir, "projects").apply { mkdirs() }
        val projFile = File(rootDir, "旧作品.revp").apply { writeText("dummy") }

        // 模拟旧版 projects/frames/旧作品/
        val legacyFramesDir = File(rootDir, "frames/旧作品").apply { mkdirs() }
        File(legacyFramesDir, "frame_00001.webp").writeText("old-frame")

        val resolved = TimelapseStorage.resolveTimelapseFramesDir(filesDir, projFile.absolutePath, "旧作品")
        val expected = File(File(filesDir, "timelapse"), "旧作品")

        assertEquals(expected.absolutePath, resolved?.absolutePath)
        assertTrue(expected.exists())
        assertTrue(File(expected, "frame_00001.webp").exists())
        assertFalse(legacyFramesDir.exists())
    }
}
