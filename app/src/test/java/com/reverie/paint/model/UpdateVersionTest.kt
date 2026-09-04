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
}
