/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingUriPolicyTest {

    @Test
    fun `desktop widget navigation uri is not an import`() {
        // Glance 给小组件跳转 intent 填的 data，点了小组件不该触发导入
        assertFalse(
            IncomingUriPolicy.isImportable("glance-action")
        )
    }

    @Test
    fun `file and content uris are importable`() {
        assertTrue(IncomingUriPolicy.isImportable("file"))
        assertTrue(IncomingUriPolicy.isImportable("content"))
    }

    @Test
    fun `scheme comparison is case insensitive`() {
        assertTrue(IncomingUriPolicy.isImportable("Content"))
        assertTrue(IncomingUriPolicy.isImportable("FILE"))
    }

    @Test
    fun `unreadable schemes are rejected`() {
        assertFalse(IncomingUriPolicy.isImportable("http"))
        assertFalse(IncomingUriPolicy.isImportable("https"))
        assertFalse(IncomingUriPolicy.isImportable("android.resource"))
        assertFalse(IncomingUriPolicy.isImportable(""))
        assertFalse(IncomingUriPolicy.isImportable(null))
    }
}
