/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerTest {

    @Test
    fun `sanitize masks json password and token fields`() {
        val input = """{"username": "artist", "password": "SuperSecretPassword123!", "token": "a1b2c3d4e5f6"}"""
        val sanitized = DiagnosticsManager.sanitize(input)
        assertFalse(sanitized.contains("SuperSecretPassword123!"))
        assertFalse(sanitized.contains("a1b2c3d4e5f6"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertTrue(sanitized.contains("artist"))
    }

    @Test
    fun `sanitize masks Bearer and Basic authentication headers`() {
        val input = "Authorization: Bearer my_super_secret_jwt_token_here_12345"
        val sanitized = DiagnosticsManager.sanitize(input)
        assertFalse(sanitized.contains("my_super_secret_jwt_token_here_12345"))
        assertTrue(sanitized.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `sanitize masks url embedded basic auth credentials`() {
        val input = "Connecting to https://artist:TopSecretPass@dav.example.com/remote.php/webdav"
        val sanitized = DiagnosticsManager.sanitize(input)
        assertFalse(sanitized.contains("TopSecretPass"))
        assertTrue(sanitized.contains("https://artist:[REDACTED]@dav.example.com"))
    }

    @Test
    fun `sanitize preserves normal diagnostic logs unchanged`() {
        val normal = "D/RP_IO: saveRevp blob=4096 bytes to /data/user/0/com.reverie.paint/files/projects/art.revp"
        val sanitized = DiagnosticsManager.sanitize(normal)
        assertEquals(normal, sanitized)
    }
}
