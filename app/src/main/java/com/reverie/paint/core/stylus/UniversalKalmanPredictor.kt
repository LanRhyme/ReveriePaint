/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Single predicted touch/stylus point with 2D coordinates and pressure.
 */
data class PredictedPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampMs: Long = 0L
)

/**
 * Pure-Kotlin 4-order 1D Kalman state estimator tracking position, velocity,
 * acceleration, and jerk ([x, \dot{x}, \ddot{x}, \dddot{x}]^T).
 *
 * State transition and process noise models follow standard physical kinematics
 * and the open AndroidX motion prediction architecture (androidx.input:input-motionprediction):
 *   F = [ 1.0, 1.0, 0.5, 0.16 ]
 *       [ 0.0, 1.0, 1.0, 0.5  ]
 *       [ 0.0, 0.0, 1.0, 1.0  ]
 *       [ 0.0, 0.0, 0.0, 1.0  ]
 *
 *   G = [0.16, 0.5, 1.0, 1.0]^T,  Q = sigmaProcess * (G * G^T)
 *   H = [1.0, 0.0, 0.0, 0.0],      R = sigmaMeasurement
 *
 * Covariance update employs Joseph stabilized formulation to guarantee numerical symmetry
 * and positive semi-definiteness: P = (I - K*H) * P * (I - K*H)^T + K * R * K^T
 */
class KalmanFilter4D(
    private val sigmaProcess: Double = 0.01,
    private val sigmaMeasurement: Double = 1.0
) {
    val x = DoubleArray(4)
    private val p = DoubleArray(16)
    private val q = DoubleArray(16)

    // Preallocated scratchpads for zero-allocation hot path
    private val tempFp = DoubleArray(16)
    private val tempIkh = DoubleArray(16)
    private val tempIkhP = DoubleArray(16)
    private val kGain = DoubleArray(4)

    init {
        val g = doubleArrayOf(0.16, 0.5, 1.0, 1.0)
        for (r in 0..3) {
            for (c in 0..3) {
                q[r * 4 + c] = sigmaProcess * (g[r] * g[c])
            }
        }
        reset(0.0)
    }

    fun reset(initialValue: Double = 0.0) {
        x[0] = if (initialValue.isFinite()) initialValue else 0.0
        x[1] = 0.0
        x[2] = 0.0
        x[3] = 0.0

        for (i in 0..15) p[i] = 0.0
        p[0] = 1.0
        p[5] = 1.0
        p[10] = 1.0
        p[15] = 1.0
    }

    fun predict() {
        // x_prior = F * x
        val nx0 = x[0] + x[1] + 0.5 * x[2] + 0.16 * x[3]
        val nx1 = x[1] + x[2] + 0.5 * x[3]
        val nx2 = x[2] + x[3]
        val nx3 = x[3]
        x[0] = nx0
        x[1] = nx1
        x[2] = nx2
        x[3] = nx3

        // tempFp = F * P
        for (c in 0..3) {
            val p0c = p[c]
            val p1c = p[4 + c]
            val p2c = p[8 + c]
            val p3c = p[12 + c]

            tempFp[c] = p0c + p1c + 0.5 * p2c + 0.16 * p3c
            tempFp[4 + c] = p1c + p2c + 0.5 * p3c
            tempFp[8 + c] = p2c + p3c
            tempFp[12 + c] = p3c
        }

        // P_prior = tempFp * F^T + Q
        for (r in 0..3) {
            val rOffset = r * 4
            val tf0 = tempFp[rOffset]
            val tf1 = tempFp[rOffset + 1]
            val tf2 = tempFp[rOffset + 2]
            val tf3 = tempFp[rOffset + 3]

            p[rOffset] = tf0 + tf1 + 0.5 * tf2 + 0.16 * tf3 + q[rOffset]
            p[rOffset + 1] = tf1 + tf2 + 0.5 * tf3 + q[rOffset + 1]
            p[rOffset + 2] = tf2 + tf3 + q[rOffset + 2]
            p[rOffset + 3] = tf3 + q[rOffset + 3]
        }
    }

    fun update(z: Double) {
        if (!z.isFinite()) return
        val y = z - x[0]
        var s = p[0] + sigmaMeasurement
        if (s < 1e-9) s = 1e-9

        val invS = 1.0 / s
        kGain[0] = p[0] * invS
        kGain[1] = p[4] * invS
        kGain[2] = p[8] * invS
        kGain[3] = p[12] * invS

        x[0] += kGain[0] * y
        x[1] += kGain[1] * y
        x[2] += kGain[2] * y
        x[3] += kGain[3] * y

        // Joseph stabilized update: I_KH = I - K*H
        for (i in 0..15) tempIkh[i] = 0.0
        tempIkh[0] = 1.0 - kGain[0]
        tempIkh[4] = -kGain[1]
        tempIkh[5] = 1.0
        tempIkh[8] = -kGain[2]
        tempIkh[10] = 1.0
        tempIkh[12] = -kGain[3]
        tempIkh[15] = 1.0

        // tempIkhP = I_KH * P
        for (r in 0..3) {
            val rOff = r * 4
            for (c in 0..3) {
                var sum = 0.0
                for (k in 0..3) {
                    sum += tempIkh[rOff + k] * p[k * 4 + c]
                }
                tempIkhP[rOff + c] = sum
            }
        }

        // P = tempIkhP * I_KH^T + K * R * K^T
        for (r in 0..3) {
            val rOff = r * 4
            for (c in 0..3) {
                var sum = 0.0
                for (k in 0..3) {
                    sum += tempIkhP[rOff + k] * tempIkh[c * 4 + k]
                }
                sum += kGain[r] * kGain[c] * sigmaMeasurement
                p[rOff + c] = sum
            }
        }

        // Explicit numerical symmetrization: P = (P + P^T) / 2
        for (r in 0..3) {
            for (c in (r + 1)..3) {
                val sym = (p[r * 4 + c] + p[c * 4 + r]) * 0.5
                p[r * 4 + c] = sym
                p[c * 4 + r] = sym
            }
        }
    }
}

/**
 * Pure-Kotlin 2-order 1D Kalman state estimator tracking pressure and its rate of change ([p, \dot{p}]^T).
 *
 *   F = [ 1.0, 1.0 ]
 *       [ 0.0, 1.0 ]
 *   G = [0.5, 1.0]^T,  Q = sigmaProcess * (G * G^T)
 *   H = [1.0, 0.0],    R = sigmaMeasurement
 */
class KalmanFilter2D(
    private val sigmaProcess: Double = 0.01,
    private val sigmaMeasurement: Double = 1.0
) {
    val x = DoubleArray(2)
    private val p = DoubleArray(4)
    private val q = DoubleArray(4)

    private val tempFp = DoubleArray(4)
    private val tempIkh = DoubleArray(4)
    private val tempIkhP = DoubleArray(4)
    private val kGain = DoubleArray(2)

    init {
        val g = doubleArrayOf(0.5, 1.0)
        for (r in 0..1) {
            for (c in 0..1) {
                q[r * 2 + c] = sigmaProcess * (g[r] * g[c])
            }
        }
        reset(0.0)
    }

    fun reset(initialValue: Double = 0.0) {
        x[0] = if (initialValue.isFinite()) initialValue else 0.0
        x[1] = 0.0
        p[0] = 1.0
        p[1] = 0.0
        p[2] = 0.0
        p[3] = 1.0
    }

    fun predict() {
        val nx0 = x[0] + x[1]
        val nx1 = x[1]
        x[0] = nx0
        x[1] = nx1

        tempFp[0] = p[0] + p[2]
        tempFp[1] = p[1] + p[3]
        tempFp[2] = p[2]
        tempFp[3] = p[3]

        p[0] = tempFp[0] + tempFp[1] + q[0]
        p[1] = tempFp[1] + q[1]
        p[2] = tempFp[2] + tempFp[3] + q[2]
        p[3] = tempFp[3] + q[3]
    }

    fun update(z: Double) {
        if (!z.isFinite()) return
        val y = z - x[0]
        var s = p[0] + sigmaMeasurement
        if (s < 1e-9) s = 1e-9

        val invS = 1.0 / s
        kGain[0] = p[0] * invS
        kGain[1] = p[2] * invS

        x[0] += kGain[0] * y
        x[1] += kGain[1] * y

        tempIkh[0] = 1.0 - kGain[0]
        tempIkh[1] = 0.0
        tempIkh[2] = -kGain[1]
        tempIkh[3] = 1.0

        tempIkhP[0] = tempIkh[0] * p[0]
        tempIkhP[1] = tempIkh[0] * p[1]
        tempIkhP[2] = tempIkh[2] * p[0] + tempIkh[3] * p[2]
        tempIkhP[3] = tempIkh[2] * p[1] + tempIkh[3] * p[3]

        p[0] = tempIkhP[0] * tempIkh[0] + kGain[0] * kGain[0] * sigmaMeasurement
        p[1] = tempIkhP[0] * tempIkh[2] + tempIkhP[1] * tempIkh[3] + kGain[0] * kGain[1] * sigmaMeasurement
        p[2] = tempIkhP[2] * tempIkh[0] + kGain[1] * kGain[0] * sigmaMeasurement
        p[3] = tempIkhP[2] * tempIkh[2] + tempIkhP[3] * tempIkh[3] + kGain[1] * kGain[1] * sigmaMeasurement

        val sym = (p[1] + p[2]) * 0.5
        p[1] = sym
        p[2] = sym
    }
}

/**
 * 2D coordinate + 1D pressure Kalman state estimator aggregator.
 */
class PenKalmanStateEstimator(
    sigmaProcess: Double = 0.01,
    sigmaMeasurement: Double = 1.0
) {
    val xKalman = KalmanFilter4D(sigmaProcess, sigmaMeasurement)
    val yKalman = KalmanFilter4D(sigmaProcess, sigmaMeasurement)
    val pKalman = KalmanFilter2D(sigmaProcess, sigmaMeasurement)

    var numIterations: Int = 0
        private set

    val positionX: Double get() = xKalman.x[0]
    val positionY: Double get() = yKalman.x[0]
    val velocityX: Double get() = xKalman.x[1]
    val velocityY: Double get() = yKalman.x[1]
    val accelerationX: Double get() = xKalman.x[2]
    val accelerationY: Double get() = yKalman.x[2]
    val jerkX: Double get() = xKalman.x[3]
    val jerkY: Double get() = yKalman.x[3]
    val pressure: Double get() = pKalman.x[0]
    val pressureChange: Double get() = pKalman.x[1]

    fun reset() {
        xKalman.reset(0.0)
        yKalman.reset(0.0)
        pKalman.reset(0.0)
        numIterations = 0
    }

    fun update(x: Double, y: Double, p: Double) {
        if (!x.isFinite() || !y.isFinite() || !p.isFinite()) return
        if (numIterations == 0) {
            xKalman.reset(x)
            yKalman.reset(y)
            pKalman.reset(p)
        } else {
            xKalman.predict()
            xKalman.update(x)
            yKalman.predict()
            yKalman.update(y)
            pKalman.predict()
            pKalman.update(p)
        }
        numIterations++
    }
}

/**
 * Universal Kalman Ink Predictor Engine.
 *
 * Provides ultra-low latency forward stroke prediction for any stylus or touch device.
 * Utilizes a 4-order Kalman filter for [x, y] coordinates ([x, \dot{x}, \ddot{x}, \dddot{x}]^T)
 * and a 2-order Kalman filter for pressure ([p, \dot{p}]^T).
 *
 * Implements adaptive step modulation:
 *   steps = ceil( (targetMs / reportRateMs) * norm(v) * (1 - norm(jank))^8 )
 *
 * Features:
 * - Low-speed threshold lock-out (< 0.2 px/ms suppresses prediction when resting/hovering)
 * - Aggressive 8-th power jerk suppression at sharp direction changes / corners
 * - 1/10 screen diagonal anti-overshoot gating
 * - Sharp angular discrepancy gating (cos(theta) < 0.55 relative to current velocity)
 * - Zero-allocation hot path
 */
class UniversalKalmanPredictor(
    predictionTargetMs: Float = 20.0f,
    maxSteps: Int = 4
) {
    var predictionTargetMs: Float = if (predictionTargetMs.isFinite() && predictionTargetMs > 0f) predictionTargetMs.coerceIn(1.0f, MAX_PREDICTION_MS) else 20.0f
        private set
    val maxSteps: Int = maxSteps.coerceIn(1, 16)

    val kalman = PenKalmanStateEstimator(sigmaProcess = 0.01, sigmaMeasurement = 1.0)

    var lastPosX: Float = 0f
        private set
    var lastPosY: Float = 0f
        private set
    var prevPosX: Float = 0f
        private set
    var prevPosY: Float = 0f
        private set
    var lastPressure: Float = 1f
        private set

    private var prevEventTimeMs: Long = 0L

    // Sliding window of report rates (in ms)
    private val reportRates = FloatArray(20)
    private var reportRateCount = 0
    private var reportRateHead = 0
    var avgReportRateMs: Float = 8.33f // Default 120Hz = 8.33ms
        private set

    var screenDiagonal: Float = 0f
        private set

    companion object {
        const val MAX_PREDICTION_MS = 32.0f
        private const val LOW_SPEED_PX_PER_MS = 0.18
        private const val HIGH_SPEED_PX_PER_MS = 2.2
        private const val LOW_JANK = 0.02
        private const val HIGH_JANK = 1.0

        fun normalizeRange(value: Double, minVal: Double, maxVal: Double): Double {
            if (value.isNaN() || maxVal <= minVal) return 0.0
            if (value <= minVal) return 0.0
            if (value >= maxVal) return 1.0
            return (value - minVal) / (maxVal - minVal)
        }
    }

    fun setScreenDiagonal(diagonal: Float) {
        if (!diagonal.isNaN() && !diagonal.isInfinite() && diagonal > 0f) {
            screenDiagonal = diagonal
        }
    }

    fun setPredictionTargetMs(targetMs: Float) {
        if (targetMs.isFinite() && targetMs > 0f) {
            predictionTargetMs = targetMs.coerceIn(1.0f, MAX_PREDICTION_MS)
        }
    }

    fun setRefreshRate(fps: Float) {
        if (fps > 0f && !fps.isNaN() && !fps.isInfinite()) {
            avgReportRateMs = (1000f / fps).coerceIn(4f, 33f)
        }
    }

    fun reset() {
        kalman.reset()
        lastPosX = 0f
        lastPosY = 0f
        prevPosX = 0f
        prevPosY = 0f
        lastPressure = 1f
        prevEventTimeMs = 0L
        reportRateCount = 0
        reportRateHead = 0
    }

    fun addPoint(x: Float, y: Float, pressure: Float, timestampMs: Long) {
        if (!x.isFinite() || !y.isFinite()) return
        val safePressure = if (pressure.isFinite()) pressure.coerceIn(0.01f, 1f) else 1f

        kalman.update(x.toDouble(), y.toDouble(), safePressure.toDouble())
        if (kalman.numIterations > 1) {
            prevPosX = lastPosX
            prevPosY = lastPosY
        } else {
            prevPosX = x
            prevPosY = y
        }
        lastPosX = x
        lastPosY = y
        lastPressure = safePressure

        if (prevEventTimeMs > 0L && timestampMs > prevEventTimeMs) {
            val dt = (timestampMs - prevEventTimeMs).toFloat()
            if (dt in 1.0f..100.0f) {
                recordReportRate(dt)
            }
        }
        if (timestampMs > 0L) {
            prevEventTimeMs = timestampMs
        }
    }

    private fun recordReportRate(dt: Float) {
        reportRates[reportRateHead] = dt
        reportRateHead = (reportRateHead + 1) % reportRates.size
        if (reportRateCount < reportRates.size) {
            reportRateCount++
        }
        var sum = 0f
        for (i in 0 until reportRateCount) {
            sum += reportRates[i]
        }
        avgReportRateMs = sum / reportRateCount
    }

    /**
     * Compute predicted forward stroke points along the estimated trajectory.
     * Returns empty list if resting, moving too slowly, changing direction abruptly,
     * or if insufficient history exists (< 3 samples).
     */
    fun predictStrokePoints(): List<PredictedPoint> {
        if (kalman.numIterations < 3) {
            return emptyList()
        }

        val velX = kalman.velocityX
        val velY = kalman.velocityY
        if (!velX.isFinite() || !velY.isFinite() ||
            !kalman.accelerationX.isFinite() || !kalman.accelerationY.isFinite() ||
            !kalman.jerkX.isFinite() || !kalman.jerkY.isFinite() ||
            !kalman.pressure.isFinite() || !kalman.pressureChange.isFinite() ||
            !lastPosX.isFinite() || !lastPosY.isFinite()
        ) {
            return emptyList()
        }
        val vMag = hypot(velX, velY)
        val reportRate = avgReportRateMs.coerceAtLeast(1.0f).toDouble()

        // Velocity normalization: norm(v)
        val speedPxPerMs = vMag / reportRate
        val normV = normalizeRange(speedPxPerMs, LOW_SPEED_PX_PER_MS, HIGH_SPEED_PX_PER_MS)
        if (normV <= 0.0) {
            // Low-speed threshold lock-out: completely resting or slow movement
            return emptyList()
        }

        // Jerk normalization: norm(jank)
        val jerkX = kalman.jerkX
        val jerkY = kalman.jerkY
        val jankMag = hypot(jerkX, jerkY)
        val normJank = normalizeRange(jankMag, LOW_JANK, HIGH_JANK)

        // 8-th power jerk damping model
        val damping = (1.0 - normJank).coerceIn(0.0, 1.0).pow(8.0)

        // Adaptive step modulation: steps = ceil( (targetMs / reportRateMs) * norm(v) * (1 - norm(jank))^8 )
        val targetRatio = (predictionTargetMs.toDouble() / reportRate)
        val rawSteps = ceil(targetRatio * normV * damping).toInt()
        val steps = rawSteps.coerceIn(0, maxSteps)
        if (steps <= 0) {
            return emptyList()
        }

        val result = ArrayList<PredictedPoint>(steps)
        var currPosX = lastPosX.toDouble()
        var currPosY = lastPosY.toDouble()
        var currVelX = velX
        var currVelY = velY
        var currAccX = kalman.accelerationX
        var currAccY = kalman.accelerationY
        var currPressure = kalman.pressure
        val pressureChange = kalman.pressureChange

        val vNormLen = hypot(velX, velY)
        val motionDx = (lastPosX - prevPosX).toDouble()
        val motionDy = (lastPosY - prevPosY).toDouble()
        val motionDist = hypot(motionDx, motionDy)

        for (i in 0 until steps) {
            currAccX += jerkX * 0.1
            currAccY += jerkY * 0.1
            currVelX += currAccX * 0.5
            currVelY += currAccY * 0.5
            currPosX += currVelX * 1.0
            currPosY += currVelY * 1.0
            currPressure += pressureChange
            if (!currPosX.isFinite() || !currPosY.isFinite() || !currPressure.isFinite()) {
                break
            }

            // Wild prediction gating:
            // 1. Distance from last known real position exceeding 1/10 screen diagonal
            val dx = currPosX - lastPosX
            val dy = currPosY - lastPosY
            val dist = hypot(dx, dy)
            if (screenDiagonal > 0f && dist > (screenDiagonal / 10.0)) {
                break
            }

            // 2. Sharp angular discrepancy relative to physical stroke incoming vector:
            // Extrapolated displacement must not sharply diverge from incoming motion (cos(theta) >= 0.55)
            if (motionDist > 1.5 && dist > 1.5) {
                val dotMotion = (motionDx * dx + motionDy * dy) / (motionDist * dist)
                if (dotMotion < 0.55) {
                    break
                }
            }

            // 3. Extrapolated displacement must not sharply diverge from velocity direction
            if (vNormLen > 1.5 && dist > 1.5) {
                val dotVel = (velX * dx + velY * dy) / (vNormLen * dist)
                if (dotVel < 0.55) {
                    break
                }
            }

            val pClamped = currPressure.coerceIn(0.01, 1.0).toFloat()
            result.add(PredictedPoint(currPosX.toFloat(), currPosY.toFloat(), pClamped))

            if (currPressure < 0.05) {
                break
            }
        }

        return result
    }

    /**
     * Compute single forward predicted touch point (for real-time stroke feather extension).
     */
    fun predictTouchPoint(): PredictedPoint? {
        val buf = FloatArray(3)
        return if (predictTouchPointScalar(buf)) {
            PredictedPoint(buf[0], buf[1], buf[2])
        } else {
            null
        }
    }

    /**
     * Zero-allocation scalar prediction of the final forward touch point.
     * Writes into [outPoint]: outPoint[0] = x, outPoint[1] = y, outPoint[2] = pressure.
     * Returns true if a valid prediction was computed and stored, false otherwise.
     */
    fun predictTouchPointScalar(outPoint: FloatArray): Boolean {
        if (outPoint.size < 3 || kalman.numIterations < 3) {
            return false
        }

        val velX = kalman.velocityX
        val velY = kalman.velocityY
        if (!velX.isFinite() || !velY.isFinite() ||
            !kalman.accelerationX.isFinite() || !kalman.accelerationY.isFinite() ||
            !kalman.jerkX.isFinite() || !kalman.jerkY.isFinite() ||
            !kalman.pressure.isFinite() || !kalman.pressureChange.isFinite() ||
            !lastPosX.isFinite() || !lastPosY.isFinite()
        ) {
            return false
        }
        val vMag = hypot(velX, velY)
        val reportRate = avgReportRateMs.coerceAtLeast(1.0f).toDouble()

        // Velocity normalization: norm(v)
        val speedPxPerMs = vMag / reportRate
        val normV = normalizeRange(speedPxPerMs, LOW_SPEED_PX_PER_MS, HIGH_SPEED_PX_PER_MS)
        if (normV <= 0.0) {
            return false
        }

        // Jerk normalization: norm(jank)
        val jerkX = kalman.jerkX
        val jerkY = kalman.jerkY
        val jankMag = hypot(jerkX, jerkY)
        val normJank = normalizeRange(jankMag, LOW_JANK, HIGH_JANK)

        // 8-th power jerk damping model
        val damping = (1.0 - normJank).coerceIn(0.0, 1.0).pow(8.0)

        // Adaptive step modulation
        val targetRatio = (predictionTargetMs.toDouble() / reportRate)
        val rawSteps = ceil(targetRatio * normV * damping).toInt()
        val steps = rawSteps.coerceIn(0, maxSteps)
        if (steps <= 0) {
            return false
        }

        var currPosX = lastPosX.toDouble()
        var currPosY = lastPosY.toDouble()
        var currVelX = velX
        var currVelY = velY
        var currAccX = kalman.accelerationX
        var currAccY = kalman.accelerationY
        var currPressure = kalman.pressure
        val pressureChange = kalman.pressureChange

        val vNormLen = hypot(velX, velY)
        val motionDx = (lastPosX - prevPosX).toDouble()
        val motionDy = (lastPosY - prevPosY).toDouble()
        val motionDist = hypot(motionDx, motionDy)

        var validSteps = 0
        var lastValidX = currPosX
        var lastValidY = currPosY
        var lastValidP = currPressure

        for (i in 0 until steps) {
            currAccX += jerkX * 0.1
            currAccY += jerkY * 0.1
            currVelX += currAccX * 0.5
            currVelY += currAccY * 0.5
            currPosX += currVelX * 1.0
            currPosY += currVelY * 1.0
            currPressure += pressureChange
            if (!currPosX.isFinite() || !currPosY.isFinite() || !currPressure.isFinite()) {
                break
            }

            // Wild prediction gating:
            // 1. Distance from last known real position exceeding 1/10 screen diagonal
            val dx = currPosX - lastPosX
            val dy = currPosY - lastPosY
            val dist = hypot(dx, dy)
            if (screenDiagonal > 0f && dist > (screenDiagonal / 10.0)) {
                break
            }

            // 2. Sharp angular discrepancy relative to physical stroke incoming vector (cos(theta) >= 0.55)
            if (motionDist > 1.5 && dist > 1.5) {
                val dotMotion = (motionDx * dx + motionDy * dy) / (motionDist * dist)
                if (dotMotion < 0.55) {
                    break
                }
            }

            // 3. Extrapolated displacement must not sharply diverge from velocity direction
            if (vNormLen > 1.5 && dist > 1.5) {
                val dotVel = (velX * dx + velY * dy) / (vNormLen * dist)
                if (dotVel < 0.55) {
                    break
                }
            }

            lastValidX = currPosX
            lastValidY = currPosY
            lastValidP = currPressure
            validSteps++

            if (currPressure < 0.05) {
                break
            }
        }

        if (validSteps > 0) {
            outPoint[0] = lastValidX.toFloat()
            outPoint[1] = lastValidY.toFloat()
            outPoint[2] = lastValidP.coerceIn(0.01, 1.0).toFloat()
            return true
        }
        return false
    }
}
