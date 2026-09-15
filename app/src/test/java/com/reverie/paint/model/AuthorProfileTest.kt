/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorProfileTest {

    @Test
    fun `default profile is empty except enabled`() {
        val profile = AuthorProfile()
        assertTrue(profile.enabled)
        assertTrue(profile.isEmpty())
        assertFalse(profile.isNotEmpty())
    }

    @Test
    fun `profile with name is not empty`() {
        val profile = AuthorProfile(name = "LanRhyme")
        assertFalse(profile.isEmpty())
        assertTrue(profile.isNotEmpty())
    }

    @Test
    fun `json roundtrip preserves all fields`() {
        val origin = AuthorProfile(
            enabled = true,
            name = "LanRhyme",
            nickname = "Lan",
            organization = "Reverie Studio",
            email = "lan@example.com",
            website = "https://reverie.paint",
            copyright = "CC BY-NC 4.0",
        )
        val json = origin.toJson()
        val restored = AuthorProfile.fromJson(json)

        assertEquals(origin.enabled, restored.enabled)
        assertEquals(origin.name, restored.name)
        assertEquals(origin.nickname, restored.nickname)
        assertEquals(origin.organization, restored.organization)
        assertEquals(origin.email, restored.email)
        assertEquals(origin.website, restored.website)
        assertEquals(origin.copyright, restored.copyright)
    }

    @Test
    fun `json roundtrip handles quotes and special characters`() {
        val origin = AuthorProfile(
            enabled = false,
            name = "Name with \"Quotes\" and \n newline",
            nickname = "Nick\\Slash",
            organization = "Org/Team",
            email = "user+tag@domain.com",
            website = "https://domain.com/path?a=1&b=2",
            copyright = "Copyright (C) 2026 \"LanRhyme\". All rights reserved.",
        )
        val json = origin.toJson()
        val restored = AuthorProfile.fromJson(json)

        assertEquals(origin.enabled, restored.enabled)
        assertEquals(origin.name, restored.name)
        assertEquals(origin.nickname, restored.nickname)
        assertEquals(origin.organization, restored.organization)
        assertEquals(origin.email, restored.email)
        assertEquals(origin.website, restored.website)
        assertEquals(origin.copyright, restored.copyright)
    }

    @Test
    fun `fromJson handles null or empty json gracefully`() {
        val p1 = AuthorProfile.fromJson(null)
        assertTrue(p1.isEmpty())
        val p2 = AuthorProfile.fromJson("")
        assertTrue(p2.isEmpty())
        val p3 = AuthorProfile.fromJson("{ bad json }")
        assertTrue(p3.isEmpty())
    }
}
