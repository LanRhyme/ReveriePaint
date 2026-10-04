/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.Build

/**
 * 设备硬件与系统厂商特性检测工具。
 * 统一识别鸿蒙 (HarmonyOS)、华为 (EMUI)、荣耀 (MagicOS) 等专有系统与芯片环境。
 */
object DeviceInfo {

    val isHuaweiOrHonor: Boolean by lazy {
        val brand = Build.BRAND.orEmpty()
        val mfg = Build.MANUFACTURER.orEmpty()
        brand.contains("huawei", ignoreCase = true) ||
            brand.contains("honor", ignoreCase = true) ||
            mfg.contains("huawei", ignoreCase = true) ||
            mfg.contains("honor", ignoreCase = true) ||
            isHarmonyOs
    }

    val isHarmonyOs: Boolean by lazy {
        try {
            val buildEx = Class.forName("com.huawei.system.BuildEx")
            val method = buildEx.getMethod("getOsBrand")
            val brand = method.invoke(null)?.toString()
            if ("harmony".equals(brand, ignoreCase = true)) return@lazy true
        } catch (_: Throwable) {}

        val propKeys = listOf(
            "hw_sc.build.platform.version",
            "ro.build.version.emui",
            "ro.build.hw_emui_api_level",
            "ro.huawei.build.version.security",
            "ro.build.version.magic"
        )
        for (key in propKeys) {
            if (getSystemProperty(key).isNotBlank()) return@lazy true
        }
        false
    }

    fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
