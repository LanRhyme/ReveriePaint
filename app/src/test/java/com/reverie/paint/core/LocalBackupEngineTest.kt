/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.backup.BackupCategory
import com.reverie.paint.core.backup.ConflictResolution
import com.reverie.paint.core.backup.LocalBackupEngine
import com.reverie.paint.core.backup.RestoreOptions
import com.reverie.paint.core.backup.RestoreOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalBackupEngineTest {

    private fun restoreBackup(
        filesDir: File,
        prefApplier: ((String?, String?, String?) -> Unit)?,
        inputStream: InputStream,
        options: RestoreOptions,
    ): RestoreOutcome = LocalBackupEngine.restoreBackup(
        filesDir, prefApplier, inputStream, options,
        // JVM 没有 Android Os.rename；仍用真实文件系统原子替换验证整条恢复路径。
        replaceFile = { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
    )

    @Test
    fun `replacement failure keeps original content and removes staging file`() {
        val root = Files.createTempDirectory("backup-commit-failure").toFile()
        try {
            val dir = File(root, "projects").apply { mkdirs() }
            val target = File(dir, "painting.revp").apply { writeText("original") }
            try {
                LocalBackupEngine.restoreBackup(
                    root, null, ByteArrayInputStream(storedProjectZip("replacement".toByteArray())),
                    RestoreOptions(
                        selectedCategories = setOf(BackupCategory.PROJECTS),
                        projectConflictResolution = ConflictResolution.OVERWRITE,
                    ),
                    replaceFile = { staged, destination ->
                        assertEquals("replacement", staged.readText())
                        assertEquals("original", destination.readText())
                        throw IOException("Simulated atomic rename failure")
                    },
                )
                fail("Expected replacement failure")
            } catch (_: IOException) {
                assertEquals("original", target.readText())
                assertEquals(listOf("painting.revp"), dir.list()!!.toList())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `bad CRC does not destroy an existing project or leave a partial new project`() {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        val bytes = storedProjectZip(payload)
        // STORED entries put data immediately after the local header and UTF-8 filename.
        val start = 30 + "data/projects/painting.revp".toByteArray().size
        bytes[start + 4000] = (bytes[start + 4000].toInt() xor 1).toByte()
        assertFailedRestorePreservesFiles(bytes)
    }

    @Test
    fun `truncated backup does not destroy an existing project or leave a partial new project`() {
        val bytes = storedProjectZip(ByteArray(8192) { (it % 251).toByte() })
        assertFailedRestorePreservesFiles(bytes.copyOf(4096))
    }

    private fun assertFailedRestorePreservesFiles(bytes: ByteArray) {
        for (existing in listOf(true, false)) {
            val root = Files.createTempDirectory("backup-failure").toFile()
            try {
                val dir = File(root, "projects").apply { mkdirs() }
                val target = File(dir, "painting.revp")
                if (existing) target.writeText("irreplaceable local drawing")
                try {
                    restoreBackup(
                        root, null, ByteArrayInputStream(bytes),
                        RestoreOptions(
                            selectedCategories = setOf(BackupCategory.PROJECTS),
                            projectConflictResolution = ConflictResolution.OVERWRITE,
                        ),
                    )
                    fail("Corrupt backup must fail")
                } catch (_: IOException) {
                    // The restore error must not cost the user their existing document.
                }
                if (existing) assertEquals("irreplaceable local drawing", target.readText())
                else assertFalse("Failed new entry must not become a project", target.exists())
                assertEquals("No staging files left behind", if (existing) 1 else 0, dir.listFiles()!!.size)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun storedProjectZip(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("data/projects/painting.revp").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(payload) }.value
            }
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `estimateCategory correctly calculates counts and sizes`() {
        val root = Files.createTempDirectory("backup-est").toFile()
        try {
            val projDir = File(root, "projects").apply { mkdirs() }
            File(projDir, "art1.revp").writeText("content-1")
            File(projDir, "art2.revp").writeText("content-2")
            File(projDir, "draft.txt").writeText("ignore")

            val prefs = mapOf(
                "userPalettes" to "[{\"id\":\"p1\",\"name\":\"Test\",\"colors\":[\"#FFFFFF\"]}]",
                // 快捷键表是制表符纯文本 (多按键用整行展开)
                "shortcut_bindings" to "#ReveriePaint Shortcuts v1\nundo\tLeftCtrl + Z\nredo\tLeftCtrl + Y",
                "somePref" to 123,
            )

            val projEst = LocalBackupEngine.estimateCategory(root, prefs, BackupCategory.PROJECTS)
            assertEquals(2, projEst.count)
            assertEquals(18L, projEst.totalBytes)

            val palEst = LocalBackupEngine.estimateCategory(root, prefs, BackupCategory.PALETTES)
            assertEquals(1, palEst.count)

            val scEst = LocalBackupEngine.estimateCategory(root, prefs, BackupCategory.SHORTCUTS_STYLUS)
            assertEquals(2, scEst.count)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `createBackup and inspectBackup round trip`() {
        val root = Files.createTempDirectory("backup-roundtrip").toFile()
        try {
            val projDir = File(root, "projects").apply { mkdirs() }
            File(projDir, "test.revp").writeText("drawing data")
            val brushDir = File(root, "paintoppresets").apply { mkdirs() }
            File(brushDir, "ink.kpp").writeText("preset data")

            val prefs = mapOf(
                "sync_password_enc" to "super-secret-encrypted-password",
                "userPalettes" to "[{\"id\":\"pal1\",\"name\":\"Pal\",\"colors\":[\"#FF0000\"]}]",
                "uiOpacity" to 0.85f,
            )

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root,
                prefsMap = prefs,
                selectedCategories = setOf(BackupCategory.PROJECTS, BackupCategory.BRUSHES, BackupCategory.SETTINGS_LAYOUT),
                outputStream = out,
                appVersion = "1.3.4",
            )

            val zipBytes = out.toByteArray()
            assertTrue("Zip package should not be empty", zipBytes.isNotEmpty())

            val inspection = LocalBackupEngine.inspectBackup(root, ByteArrayInputStream(zipBytes))
            assertEquals(1, inspection.manifest.version)
            assertEquals("1.3.4", inspection.manifest.appVersion)
            assertEquals(1, inspection.manifest.projects.size)
            assertEquals("test.revp", inspection.manifest.projects.first().relativePath)
            assertEquals(1, inspection.manifest.brushPresets.size)
            assertEquals("ink.kpp", inspection.manifest.brushPresets.first())

            // Because the files exist in root, they should be identified as existing conflicts
            assertEquals(listOf("test.revp"), inspection.existingProjectConflicts)
            assertEquals(listOf("ink.kpp"), inspection.existingBrushConflicts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restoreBackup preserves both copies on KEEP_BOTH_COPY conflict`() {
        val srcRoot = Files.createTempDirectory("backup-src").toFile()
        val destRoot = Files.createTempDirectory("backup-dest").toFile()
        try {
            val srcProj = File(srcRoot, "projects").apply { mkdirs() }
            File(srcProj, "painting.revp").writeText("source-drawing")

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = srcRoot,
                prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.PROJECTS),
                outputStream = out,
            )

            // In dest, pre-create the same file with different content
            val destProj = File(destRoot, "projects").apply { mkdirs() }
            val existing = File(destProj, "painting.revp").apply { writeText("local-modified") }

            var appliedPrefs: String? = null
            val outcome = restoreBackup(
                filesDir = destRoot,
                prefApplier = { prefs, _, _ -> appliedPrefs = prefs },
                inputStream = ByteArrayInputStream(out.toByteArray()),
                options = RestoreOptions(
                    selectedCategories = setOf(BackupCategory.PROJECTS),
                    projectConflictResolution = ConflictResolution.KEEP_BOTH_COPY,
                ),
            )

            assertEquals(1, outcome.restoredProjects)
            assertEquals("local-modified", existing.readText())

            val copyFile = File(destProj, "painting (备份恢复).revp")
            assertTrue("Should create copy file", copyFile.exists())
            assertEquals("source-drawing", copyFile.readText())
        } finally {
            srcRoot.deleteRecursively()
            destRoot.deleteRecursively()
        }
    }

    @Test
    fun `restoreBackup overwrites existing file when OVERWRITE is selected`() {
        val srcRoot = Files.createTempDirectory("backup-src-ow").toFile()
        val destRoot = Files.createTempDirectory("backup-dest-ow").toFile()
        try {
            val srcProj = File(srcRoot, "projects").apply { mkdirs() }
            File(srcProj, "painting.revp").writeText("new-backup-content")

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = srcRoot,
                prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.PROJECTS),
                outputStream = out,
            )

            val destProj = File(destRoot, "projects").apply { mkdirs() }
            val target = File(destProj, "painting.revp").apply { writeText("old-content") }

            val outcome = restoreBackup(
                filesDir = destRoot,
                prefApplier = null,
                inputStream = ByteArrayInputStream(out.toByteArray()),
                options = RestoreOptions(
                    selectedCategories = setOf(BackupCategory.PROJECTS),
                    projectConflictResolution = ConflictResolution.OVERWRITE,
                ),
            )

            assertEquals(1, outcome.restoredProjects)
            assertEquals("new-backup-content", target.readText())
        } finally {
            srcRoot.deleteRecursively()
            destRoot.deleteRecursively()
        }
    }

    @Test
    fun `safeValidatePath rejects zip slip traversal`() {
        val root = Files.createTempDirectory("slip-test").toFile()
        try {
            val safeFile = File(root, "projects/art.revp")
            LocalBackupEngine.safeValidatePath(root, safeFile)

            val maliciousFile = File(root, "../outside.txt")
            try {
                LocalBackupEngine.safeValidatePath(root, maliciousFile)
                fail("应当拦截 Zip Slip 目录穿越")
            } catch (e: SecurityException) {
                // Expected
                assertTrue(e.message?.contains("非法的备份路径") == true)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sensitive credentials are never included in exported preferences`() {
        val root = Files.createTempDirectory("sensitive-test").toFile()
        try {
            val prefs = mapOf(
                "sync_password_enc" to "ENCRYPTED_SECRET",
                "sync_last_backup" to 123456789L,
                "themeName" to "Morandi",
            )

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root,
                prefsMap = prefs,
                selectedCategories = setOf(BackupCategory.SETTINGS_LAYOUT),
                outputStream = out,
            )

            var restoredPrefsJson: String? = null
            restoreBackup(
                filesDir = root,
                prefApplier = { prefsJson, _, _ -> restoredPrefsJson = prefsJson },
                inputStream = ByteArrayInputStream(out.toByteArray()),
                options = RestoreOptions(selectedCategories = setOf(BackupCategory.SETTINGS_LAYOUT)),
            )

            assertNotNull(restoredPrefsJson)
            assertFalse("不应导出 sync_password_enc", restoredPrefsJson!!.contains("sync_password_enc"))
            assertFalse("不应导出 sync_last_backup", restoredPrefsJson!!.contains("sync_last_backup"))
            assertTrue("应保留常规偏好", restoredPrefsJson!!.contains("themeName"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `备份笔刷预设时 myb 和 kpp 一样会被带上`() {
        val root = Files.createTempDirectory("backup-myb").toFile()
        try {
            val brushDir = File(root, "paintoppresets").apply { mkdirs() }
            File(brushDir, "ink.kpp").writeText("kpp data")
            File(brushDir, "chalk.myb").writeText("{\"version\":3}")
            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root, prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.BRUSHES), outputStream = out,
            )
            val inspection = LocalBackupEngine.inspectBackup(root, ByteArrayInputStream(out.toByteArray()))
            assertEquals(listOf("chalk.myb", "ink.kpp"), inspection.manifest.brushPresets.sorted())
            val est = LocalBackupEngine.estimateCategory(root, emptyMap<String, Any>(), BackupCategory.BRUSHES)
            assertEquals(2, est.count)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `备份笔刷时自动排除出厂内置预设与材质，仅打包用户自定笔刷与素材`() {
        val root = Files.createTempDirectory("backup-filter-defaults").toFile()
        try {
            val presetsDir = com.reverie.paint.core.brushstore.ResourceDirs.presets(root).apply { mkdirs() }
            val tipsDir = com.reverie.paint.core.brushstore.ResourceDirs.tips(root).apply { mkdirs() }
            val patternsDir = com.reverie.paint.core.brushstore.ResourceDirs.patterns(root).apply { mkdirs() }

            // 写入出厂与自定义预设
            File(presetsDir, "default_pen.kpp").writeText("factory preset")
            File(presetsDir, "custom_brush.kpp").writeText("user preset")

            // 写入出厂与自定义笔尖
            File(tipsDir, "default_tip.png").writeText("factory tip")
            File(tipsDir, "custom_tip.png").writeText("user tip")

            // 写入出厂与自定义纹理
            File(patternsDir, "default_pat.png").writeText("factory pattern")
            File(patternsDir, "custom_pat.png").writeText("user pattern")

            // 登记出厂清单与 identity
            com.reverie.paint.core.backup.BrushBackupMerge.saveFactoryResources(
                root,
                tips = listOf("default_tip.png"),
                patterns = listOf("default_pat.png"),
                presets = listOf("default_pen.kpp"),
            )
            val identityJson = JSONObject().put("schema", 2).put(
                "presets",
                JSONObject()
                    .put("builtin:default_pen", JSONObject().put("name", "default_pen").put("ext", "kpp").put("kind", "BUILTIN"))
                    .put("imp:user1", JSONObject().put("name", "custom_brush").put("ext", "kpp").put("kind", "IMPORTED")),
            ).toString(1)
            File(root, "brush_store/identity.json").writeText(identityJson)

            // 验证估算：出厂预设/笔尖/纹理均被排除，仅统计自定义内容（1 预设 + 2 材质）
            val est = LocalBackupEngine.estimateCategory(root, emptyMap<String, Any>(), BackupCategory.BRUSHES)
            assertEquals(3, est.count)
            assertTrue(est.description.contains("1 个预设, 2 个笔尖纹理"))

            // 验证打包
            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root,
                prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.BRUSHES),
                outputStream = out,
            )

            val inspection = LocalBackupEngine.inspectBackup(root, ByteArrayInputStream(out.toByteArray()))
            assertEquals(listOf("custom_brush.kpp"), inspection.manifest.brushPresets)
            assertEquals(listOf("custom_tip.png"), inspection.manifest.brushTips)
            assertEquals(listOf("custom_pat.png"), inspection.manifest.brushPatterns)

            // 冲突检测应仅包含自定义笔刷，不报内置笔刷冲突
            assertEquals(listOf("custom_brush.kpp"), inspection.existingBrushConflicts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `若设备仅有内置笔刷，笔刷备份估算与打包条目均为 0`() {
        val root = Files.createTempDirectory("backup-only-defaults").toFile()
        try {
            val presetsDir = com.reverie.paint.core.brushstore.ResourceDirs.presets(root).apply { mkdirs() }
            val tipsDir = com.reverie.paint.core.brushstore.ResourceDirs.tips(root).apply { mkdirs() }
            val patternsDir = com.reverie.paint.core.brushstore.ResourceDirs.patterns(root).apply { mkdirs() }

            File(presetsDir, "Basic-1.kpp").writeText("factory preset")
            File(tipsDir, "round.png").writeText("factory tip")
            File(patternsDir, "paper.png").writeText("factory pattern")

            com.reverie.paint.core.backup.BrushBackupMerge.saveFactoryResources(
                root,
                tips = listOf("round.png"),
                patterns = listOf("paper.png"),
                presets = listOf("Basic-1.kpp"),
            )
            val identityJson = JSONObject().put("schema", 2).put(
                "presets",
                JSONObject().put("builtin:Basic-1", JSONObject().put("name", "Basic-1").put("ext", "kpp").put("kind", "BUILTIN")),
            ).toString(1)
            File(root, "brush_store/identity.json").writeText(identityJson)

            val est = LocalBackupEngine.estimateCategory(root, emptyMap<String, Any>(), BackupCategory.BRUSHES)
            assertEquals(0, est.count)
            assertEquals(0L, est.totalBytes)

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root,
                prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.BRUSHES),
                outputStream = out,
            )

            val inspection = LocalBackupEngine.inspectBackup(root, ByteArrayInputStream(out.toByteArray()))
            assertTrue(inspection.manifest.brushPresets.isEmpty())
            assertTrue(inspection.manifest.brushTips.isEmpty())
            assertTrue(inspection.manifest.brushPatterns.isEmpty())
            assertTrue(inspection.existingBrushConflicts.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}

