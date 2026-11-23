/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun `same versions are not newer`() {
        assertFalse(VersionComparator.isNewerVersion("1.0.0-Preview6", "1.0.0-Preview6"))
        assertFalse(VersionComparator.isNewerVersion("v1.0.0-Preview6", "1.0.0-Preview6"))
        assertFalse(VersionComparator.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("v1.0.0", "1.0.0"))
    }

    @Test
    fun `higher preview number is newer`() {
        assertTrue(VersionComparator.isNewerVersion("v1.0.0-Preview7", "1.0.0-Preview6"))
        assertTrue(VersionComparator.isNewerVersion("1.0.0-Preview10", "1.0.0-Preview6"))
        assertFalse(VersionComparator.isNewerVersion("v1.0.0-Preview5", "1.0.0-Preview6"))
    }

    @Test
    fun `release version is newer than preview version of same semver`() {
        assertTrue(VersionComparator.isNewerVersion("v1.0.0", "1.0.0-Preview6"))
        assertFalse(VersionComparator.isNewerVersion("1.0.0-Preview6", "1.0.0"))
    }

    @Test
    fun `higher minor or major version is newer`() {
        assertTrue(VersionComparator.isNewerVersion("v1.1.0", "1.0.0"))
        assertTrue(VersionComparator.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewerVersion("v2.0.0-Preview1", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("v0.9.9", "1.0.0"))
    }

    @Test
    fun `parse version handles irregular formats gracefully`() {
        val v1 = VersionComparator.parse("v1.2.3")
        assertEquals(1, v1.major)
        assertEquals(2, v1.minor)
        assertEquals(3, v1.patch)
        assertFalse(v1.isPrerelease)

        val v2 = VersionComparator.parse("1.0.0-Preview6")
        assertEquals(1, v2.major)
        assertEquals(0, v2.minor)
        assertEquals(0, v2.patch)
        assertTrue(v2.isPrerelease)
        assertEquals("preview", v2.prereleaseType)
        assertEquals(6, v2.prereleaseNum)
    }

    @Test
    fun `abi matcher correctly detects 64-bit and 32-bit devices`() {
        val arm64Device = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        val arm32Device = arrayOf("armeabi-v7a", "armeabi")

        assertTrue(AbiMatcher.is64Bit(arm64Device))
        assertFalse(AbiMatcher.is64Bit(arm32Device))
    }

    @Test
    fun `abi matcher selects 64-bit apk on 64-bit device`() {
        val assets = listOf(
            ReleaseAsset("ReveriePaint-v1.4.11-armeabi-v7a.apk", "http://v7a", 100L, ""),
            ReleaseAsset("ReveriePaint-v1.4.11-arm64-v8a.apk", "http://v8a", 100L, ""),
        )
        val arm64Device = arrayOf("arm64-v8a", "armeabi-v7a")
        val matched = AbiMatcher.selectBestApk(assets, arm64Device)
        assertEquals("ReveriePaint-v1.4.11-arm64-v8a.apk", matched?.name)
    }

    @Test
    fun `abi matcher selects 32-bit apk on 32-bit device`() {
        val assets = listOf(
            ReleaseAsset("ReveriePaint-v1.4.11-armeabi-v7a.apk", "http://v7a", 100L, ""),
            ReleaseAsset("ReveriePaint-v1.4.11-arm64-v8a.apk", "http://v8a", 100L, ""),
        )
        val arm32Device = arrayOf("armeabi-v7a", "armeabi")
        val matched = AbiMatcher.selectBestApk(assets, arm32Device)
        assertEquals("ReveriePaint-v1.4.11-armeabi-v7a.apk", matched?.name)
    }

    @Test
    fun `abi matcher fallbacks to generic apk when split not found`() {
        val assets = listOf(
            ReleaseAsset("ReveriePaint-v1.4.10.apk", "http://generic", 100L, ""),
        )
        val arm64Device = arrayOf("arm64-v8a", "armeabi-v7a")
        val arm32Device = arrayOf("armeabi-v7a", "armeabi")

        assertEquals("ReveriePaint-v1.4.10.apk", AbiMatcher.selectBestApk(assets, arm64Device)?.name)
        assertEquals("ReveriePaint-v1.4.10.apk", AbiMatcher.selectBestApk(assets, arm32Device)?.name)
    }

    @Test
    fun `abi matcher avoids 64-bit only apk on 32-bit device`() {
        val assets = listOf(
            ReleaseAsset("ReveriePaint-v1.4.11-arm64-v8a.apk", "http://v8a", 100L, ""),
        )
        val arm32Device = arrayOf("armeabi-v7a")
        val matched = AbiMatcher.selectBestApk(assets, arm32Device)
        assertEquals(null, matched)
    }

    @Test
    fun `release notes aggregator merges newer versions and excludes current or older`() {
        val releases = listOf(
            "v1.10.1" to "fix 1",
            "v1.10.0" to "feat 2",
            "v1.9.3" to "old fix",
            "v1.9.2" to "older fix",
        )
        val merged = ReleaseNotesAggregator.mergeNewerReleaseNotes(releases, "1.9.3")
        assertEquals("## v1.10.1\n\nfix 1\n\n---\n\n## v1.10.0\n\nfeat 2", merged)
    }

    @Test
    fun `release notes aggregator returns single body without heading when only one version newer`() {
        val releases = listOf(
            "v1.10.1" to "fix 1",
            "v1.10.0" to "feat 2",
        )
        val merged = ReleaseNotesAggregator.mergeNewerReleaseNotes(releases, "1.10.0")
        assertEquals("fix 1", merged)
    }
}
