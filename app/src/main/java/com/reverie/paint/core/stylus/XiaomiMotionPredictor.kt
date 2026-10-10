/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.MotionEvent

/**
 * OEM Native Motion Predictor for Xiaomi Devices (HyperOS / MIUI).
 * Dynamically resolves Xiaomi PenEngine stroke estimation APIs via reflection.
 * If the proprietary SDK classes are not available or not supported on the device,
 * it safely falls back so that the caller can seamlessly delegate to AndroidX MotionEventPredictor.
 */
class XiaomiMotionPredictor(context: Context) {

    companion object {
        private const val TAG = "ReverieXiaomiPredictor"

        /**
         * Check if current hardware is Xiaomi / Redmi.
         */
        fun isXiaomiDeviceSupported(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brandName = Build.BRAND.lowercase()
            return manufacturer.contains("xiaomi") || brandName.contains("xiaomi") ||
                    manufacturer.contains("redmi") || brandName.contains("redmi")
        }
    }

    private var estimateInstance: Any? = null
    private var estimateMethod: java.lang.reflect.Method? = null
    private var resetMethod: java.lang.reflect.Method? = null
    private var isAvailable = false

    init {
        if (isXiaomiDeviceSupported()) {
            initXiaomiPenEngine(context)
        }
    }

    private fun initXiaomiPenEngine(context: Context) {
        val candidateClassNames = listOf(
            "com.miui.penengine.estimate.MiuiStrokeEstimate",
            "com.miui.penengine.estimate.StrokeEstimate",
            "com.miui.penengine.estimate.MiuiPointPredictor",
            "com.miui.penengine.estimate.PointPredictor",
            "com.miui.penengine.estimate.PenEstimateManager",
        )

        for (className in candidateClassNames) {
            try {
                val clazz = Class.forName(className)
                val instance = try {
                    val constructor = clazz.getConstructor(Context::class.java)
                    constructor.newInstance(context.applicationContext)
                } catch (_: Throwable) {
                    try {
                        clazz.getDeclaredConstructor().newInstance()
                    } catch (_: Throwable) {
                        null
                    }
                }

                if (instance != null) {
                    val methods = clazz.methods
                    val estMethod = methods.firstOrNull { m ->
                        val pts = m.parameterTypes
                        (m.name.contains("estimate", ignoreCase = true) ||
                                m.name.contains("predict", ignoreCase = true) ||
                                m.name.contains("compute", ignoreCase = true)) &&
                                pts.size == 1 && pts[0] == MotionEvent::class.java
                    }

                    val rMethod = methods.firstOrNull { m ->
                        m.name.equals("reset", ignoreCase = true) || m.name.equals("clear", ignoreCase = true)
                    }

                    if (estMethod != null) {
                        estimateInstance = instance
                        estimateMethod = estMethod
                        resetMethod = rMethod
                        isAvailable = true
                        Log.i(TAG, "Xiaomi PenEngine stroke estimate initialized from $className")
                        break
                    }
                }
            } catch (_: Throwable) {
                // Class not found or incompatible on this OS build
            }
        }
    }

    val isValid: Boolean
        get() = isAvailable && estimateInstance != null && estimateMethod != null

    /**
     * Compute predicted touch point from Xiaomi PenEngine.
     * Returns true if prediction was successfully populated into outPoint.
     * outPoint layout: [0]=x, [1]=y, [2]=pressure.
     */
    fun predictPoint(event: MotionEvent, pointerIndex: Int, outPoint: FloatArray): Boolean {
        if (!isValid || pointerIndex < 0 || pointerIndex >= event.pointerCount) return false
        val instance = estimateInstance ?: return false
        val method = estimateMethod ?: return false

        try {
            val result = method.invoke(instance, event) ?: return false
            if (result is MotionEvent) {
                try {
                    if (pointerIndex in 0 until result.pointerCount) {
                        val px = result.getX(pointerIndex)
                        val py = result.getY(pointerIndex)
                        val pp = result.getPressure(pointerIndex)
                        if (px.isFinite() && py.isFinite() && (px != 0f || py != 0f)) {
                            outPoint[0] = px
                            outPoint[1] = py
                            outPoint[2] = if (pp.isFinite()) pp else event.getPressure(pointerIndex)
                            return true
                        }
                    }
                } finally {
                    result.recycle()
                }
            } else {
                val resClass = result.javaClass
                val px = try {
                    resClass.getMethod("getX").invoke(result) as? Float
                        ?: resClass.getField("x").getFloat(result)
                } catch (_: Throwable) { null }

                val py = try {
                    resClass.getMethod("getY").invoke(result) as? Float
                        ?: resClass.getField("y").getFloat(result)
                } catch (_: Throwable) { null }

                val pp = try {
                    resClass.getMethod("getPressure").invoke(result) as? Float
                        ?: resClass.getField("pressure").getFloat(result)
                } catch (_: Throwable) { null }

                if (px != null && py != null && px.isFinite() && py.isFinite() && (px != 0f || py != 0f)) {
                    outPoint[0] = px
                    outPoint[1] = py
                    outPoint[2] = pp ?: event.getPressure(pointerIndex)
                    return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    fun reset() {
        try {
            resetMethod?.invoke(estimateInstance)
        } catch (_: Throwable) {}
    }

    fun destroy() {
        reset()
        estimateInstance = null
        estimateMethod = null
        resetMethod = null
        isAvailable = false
    }
}
