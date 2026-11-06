/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 笔刷动力学与速度传感器全量资产静态审计测试:
 * 遍历扫描 assets/paintoppresets 下的所有 .kpp 预设，
 * 输出并锁定 speed 传感器与 sensorslist 的分布，作为防回归的权威基线。
 */
class BrushDynamicsSpeedAuditTest {

    @Test
    fun `全量扫描 assets 预设的 speed 传感器与 sensorslist 拓扑`() {
        val assetsDir = File("src/main/assets/paintoppresets")
        if (!assetsDir.exists()) {
            println("[SKIP] assets dir not found at ${assetsDir.absolutePath}")
            return
        }

        val kppFiles = assetsDir.listFiles { f -> f.extension.equals("kpp", ignoreCase = true) }.orEmpty()
        println("=== 正在静态扫描 ${kppFiles.size} 个内置 .kpp 笔刷预设 ===")

        val speedPresets = mutableListOf<String>()
        val sensorslistPresets = mutableListOf<String>()

        for (file in kppFiles) {
            val bytes = file.readBytes()
            val xml = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) ?: continue

            // 检查 sensorslist
            if (xml.contains("""id="sensorslist"""")) {
                sensorslistPresets.add(file.name)
            }

            // 检查 speed 传感器
            val ids = KppSensors.sensorIds(KppSensors.sensorXml(xml, "Size")) +
                    KppSensors.sensorIds(KppSensors.sensorXml(xml, "Opacity")) +
                    KppSensors.sensorIds(KppSensors.sensorXml(xml, "Flow")) +
                    KppSensors.sensorIds(KppSensors.sensorXml(xml, "Texture/Strength/"))
            if ("speed" in ids || xml.contains("""<ChildSensor id="speed"""")) {
                speedPresets.add(file.name)
            }
        }

        println("包含 speed 传感器的预设数量: ${speedPresets.size}")
        for (file in kppFiles) {
            val bytes = file.readBytes()
            val xml = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) ?: continue
            val options = listOf("Size", "Opacity", "Flow", "Spacing", "Scatter", "Rotation", "Softness")
            for (opt in options) {
                val sensorXml = KppSensors.sensorXml(xml, opt) ?: continue
                val ids = KppSensors.sensorIds(sensorXml)
                if ("speed" in ids) {
                    val useCurve = KppSensors.useCurve(xml, opt)
                    val isChecked = KppSensors.isChecked(xml, opt)
                    println("  * ${file.name} -> $opt 绑定了 speed (useCurve=$useCurve, isChecked=$isChecked)")
                }
            }
        }

        println("包含 sensorslist 复合传感器的预设数量: ${sensorslistPresets.size}")
        for (name in sensorslistPresets.take(15)) {
            println("  - sensorslist: $name")
        }

        val inkFile = File(assetsDir, "d)_Ink-1_Precision.kpp")
        if (inkFile.exists()) {
            val xml = KppHelper.readPresetXml(inkFile.readBytes(), stripEmbeddedResources = true)
            println("--- d)_Ink-1_Precision SizeSensor ---")
            println(KppSensors.sensorXml(xml ?: "", "Size"))
        }
    }
}
