/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.core.DeviceInfo
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `device info properties access is safe without throwing`() {
        val isHuawei = DeviceInfo.isHuaweiOrHonor
        val isHarmony = DeviceInfo.isHarmonyOs
        val sysProp = DeviceInfo.getSystemProperty("non.existent.prop")

        assertNotNull(isHuawei)
        assertNotNull(isHarmony)
        assertNotNull(sysProp)
    }
}
