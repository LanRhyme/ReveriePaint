/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class UniversalKalmanPredictorTest {

    @Test
    fun testLinearMotionPredictionAccuracy() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        // Simulate steady linear movement along X axis at 1.0 px/ms (8 px per 8ms event)
        // (speed 1.0 px/ms falls cleanly inside [0.2, 2.0] px/ms velocity normalization window)
        var time = 1000L
        var x = 100f
        val y = 200f
        val pressure = 0.5f

        for (i in 0 until 12) {
            predictor.addPoint(x, y, pressure, time)
            x += 8f
            time += 8L
        }

        val predictedPoint = predictor.predictTouchPoint()
        assertNotNull("Steady linear motion should produce a forward prediction", predictedPoint)
        predictedPoint!!

        // Forward prediction should extrapolate along the X direction ahead of last position
        val lastX = predictor.lastPosX
        assertTrue("Predicted X (${predictedPoint.x}) must be ahead of last X ($lastX)", predictedPoint.x > lastX)
        // Y deviation should be negligible on pure horizontal motion
        assertEquals("Y coordinate should remain stable", y, predictedPoint.y, 1.5f)
        // Extrapolation displacement should be positive and reasonable
        val forwardDisplacement = predictedPoint.x - lastX
        assertTrue("Displacement should be between 2 and 30 px, was $forwardDisplacement", forwardDisplacement in 2f..30f)
        // Pressure should be preserved
        assertTrue("Predicted pressure should be positive", predictedPoint.pressure in 0.1f..1.0f)

        // Also test stroke points list
        val points = predictor.predictStrokePoints()
        assertTrue("Stroke points list should have at least 1 point", points.isNotEmpty())
        for (i in 1 until points.size) {
            assertTrue("Successive stroke points should progress forward", points[i].x > points[i - 1].x)
        }
    }

    @Test
    fun testSharpCornerJerkSuppression() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        // 1. Move fast along +X axis
        var x = 0f
        for (i in 0 until 10) {
            predictor.addPoint(x, 0f, 0.7f, time)
            x += 12f
            time += 8L
        }

        // Before turn, linear motion produces robust prediction
        val preTurnPred = predictor.predictTouchPoint()
        assertNotNull("Pre-turn motion should predict forward", preTurnPred)

        // 2. Abrupt 90-degree right turn along +Y axis
        // The last point added in the loop was x - 12f (108f, 0f).
        // A 90-degree turn moves from (cornerX, 0f) to (cornerX, 12f).
        val cornerX = x - 12f
        predictor.addPoint(cornerX, 12f, 0.7f, time)
        time += 8L

        // Check Kalman state: jerk magnitude should be high
        val jerkMag = hypot(predictor.kalman.jerkX, predictor.kalman.jerkY)
        assertTrue("Sudden 90-degree turn must produce significant jerk spike", jerkMag > 0.05)

        val postTurnPred = predictor.predictTouchPoint()
        // Aggressive 8-th power jerk suppression formula: (1 - norm(jank))^8
        // Either predictions are completely dampened (null/empty) or severely restricted
        if (postTurnPred != null) {
            val dist = hypot(postTurnPred.x - cornerX, postTurnPred.y - 12f)
            val preDist = hypot(preTurnPred!!.x - cornerX, preTurnPred.y - 0f)
            assertTrue("Predicted displacement at sharp corner ($dist) must be strictly smaller than straight line ($preDist)", dist < preDist)
        }
    }

    @Test
    fun testLowSpeedRestingBehaviorLockout() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        // Simulate pen resting or micro-jitters (< 0.2 px/ms)
        var time = 1000L
        val baseX = 300f
        val baseY = 400f

        for (i in 0 until 10) {
            // Speed: 0.1 px per 8ms = 0.0125 px/ms, well below LOW_SPEED threshold 0.2 px/ms
            val jitterX = baseX + (i % 2) * 0.1f
            val jitterY = baseY + ((i + 1) % 2) * 0.1f
            predictor.addPoint(jitterX, jitterY, 0.5f, time)
            time += 8L
        }

        // Low speed lockout must trigger
        val pred = predictor.predictTouchPoint()
        assertNull("Resting or slow movement below 0.2 px/ms must be completely locked out", pred)

        val strokePoints = predictor.predictStrokePoints()
        assertTrue("Stroke points list must be empty at resting speed", strokePoints.isEmpty())
    }

    @Test
    fun testWildPredictionDiagonalGating() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        // Set a small screen diagonal of 500 px -> 1/10 diagonal is 50 px
        predictor.setScreenDiagonal(500f)
        predictor.setRefreshRate(120f)

        // Normal linear points
        var time = 1000L
        for (i in 0 until 6) {
            predictor.addPoint(i * 10f, 0f, 0.5f, time)
            time += 8L
        }

        val points = predictor.predictStrokePoints()
        for (p in points) {
            val dist = hypot(p.x - predictor.lastPosX, p.y - predictor.lastPosY)
            assertTrue("No predicted point may exceed 1/10 screen diagonal (50px), was $dist", dist <= 50f)
        }
    }

    @Test
    fun testDualTrackReplacementAndReset() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        for (i in 0 until 8) {
            predictor.addPoint(i * 10f, 0f, 0.5f, time)
            time += 8L
        }
        assertTrue("Predictor should have state before reset", predictor.kalman.numIterations > 0)

        // Reset clears all tracks
        predictor.reset()
        assertEquals("Iterations must be 0 after reset", 0, predictor.kalman.numIterations)
        assertEquals("Last pos X must be 0 after reset", 0f, predictor.lastPosX, 1e-4f)
        assertNull("No predictions after reset", predictor.predictTouchPoint())
    }

    @Test
    fun testKalmanFilter4DOrderIntegrity() {
        val kf = KalmanFilter4D(sigmaProcess = 0.01, sigmaMeasurement = 1.0)
        kf.reset(10.0)
        assertEquals(10.0, kf.x[0], 1e-6)
        assertEquals(0.0, kf.x[1], 1e-6)
        assertEquals(0.0, kf.x[2], 1e-6)
        assertEquals(0.0, kf.x[3], 1e-6)

        // Step through constant velocity sequence: 10, 20, 30, 40
        for (pos in listOf(20.0, 30.0, 40.0, 50.0)) {
            kf.predict()
            kf.update(pos)
        }

        // Velocity should have converged to near 10.0
        assertTrue("Velocity should converge close to 10.0, was ${kf.x[1]}", kf.x[1] in 7.0..13.0)
        // Acceleration and jerk should be small in constant velocity motion
        assertTrue("Acceleration should be small, was ${kf.x[2]}", kotlin.math.abs(kf.x[2]) < 5.0)
        assertTrue("Jerk should be small, was ${kf.x[3]}", kotlin.math.abs(kf.x[3]) < 5.0)
    }

    @Test
    fun testKalmanFilter2DPressureIntegrity() {
        val kf = KalmanFilter2D(sigmaProcess = 0.01, sigmaMeasurement = 1.0)
        kf.reset(0.2)
        assertEquals(0.2, kf.x[0], 1e-6)
        assertEquals(0.0, kf.x[1], 1e-6)

        // Increasing pressure sequence: 0.3, 0.4, 0.5, 0.6
        for (p in listOf(0.3, 0.4, 0.5, 0.6)) {
            kf.predict()
            kf.update(p)
        }

        assertTrue("Pressure should be tracked, was ${kf.x[0]}", kf.x[0] in 0.45..0.7)
        assertTrue("Pressure rate of change should be positive, was ${kf.x[1]}", kf.x[1] > 0.0)
    }

    @Test
    fun testHairpinTurnReversalSuppression() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        var x = 100f
        // 1. Steady stroke moving +X
        for (i in 0 until 8) {
            predictor.addPoint(x, 200f, 0.6f, time)
            x += 10f
            time += 8L
        }
        val preTurn = predictor.predictTouchPoint()
        assertNotNull("Steady stroke before reversal should predict forward", preTurn)

        // 2. Abrupt 180-degree reversal: hairpin turn moving back -X
        x -= 10f
        predictor.addPoint(x, 200f, 0.6f, time)
        time += 8L

        // Reversal causes both massive jerk spike AND sharp angular discrepancy
        val postReversal = predictor.predictTouchPoint()
        assertNull("Hairpin 180-degree reversal must be completely suppressed by jerk damping and angular gating", postReversal)
        assertTrue("Stroke points must be empty on hairpin reversal", predictor.predictStrokePoints().isEmpty())
    }

    @Test
    fun testRobustnessNaNAndInfinity() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        for (i in 0 until 8) {
            predictor.addPoint(i * 10f, 100f, 0.5f, time)
            time += 8L
        }
        val validPred = predictor.predictTouchPoint()
        assertNotNull("Should produce valid prediction", validPred)

        // Inject NaNs and Infinities
        predictor.addPoint(Float.NaN, 100f, 0.5f, time)
        predictor.addPoint(100f, Float.NaN, 0.5f, time)
        predictor.addPoint(Float.POSITIVE_INFINITY, 100f, 0.5f, time)
        predictor.addPoint(100f, 100f, Float.NaN, time)

        // State must remain clean and finite
        assertTrue("Predictor X must not be NaN", !predictor.lastPosX.isNaN())
        assertTrue("Predictor Y must not be NaN", !predictor.lastPosY.isNaN())
        assertTrue("Predictor Pressure must not be NaN", !predictor.lastPressure.isNaN())

        // Feed subsequent valid points: predictor continues to work reliably
        time += 8L
        for (i in 8 until 16) {
            predictor.addPoint(i * 10f, 100f, 0.5f, time)
            time += 8L
        }
        val resumedPred = predictor.predictTouchPoint()
        assertNotNull("Predictor should recover and function normally after ignoring invalid input", resumedPred)
        assertTrue("Resumed predicted X must be finite", !resumedPred!!.x.isNaN())
    }

    @Test
    fun testSeededDownPointLatency() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        // ACTION_DOWN at t = 1000ms
        predictor.addPoint(100f, 100f, 0.5f, 1000L)
        // First MOVE at t = 1008ms with batched historical sample at 1004ms
        predictor.addPoint(104f, 100f, 0.5f, 1004L)
        predictor.addPoint(108f, 100f, 0.5f, 1008L)
        // Second MOVE at t = 1016ms with batched historical sample at 1012ms
        predictor.addPoint(112f, 100f, 0.5f, 1012L)
        predictor.addPoint(116f, 100f, 0.5f, 1016L)

        // Predictor reaches >= 4 iterations early and predicts forward
        val pred = predictor.predictTouchPoint()
        assertNotNull("Seeding on DOWN enables forward prediction by 2nd move event", pred)
        assertTrue("Predicted X must be ahead of last position (116)", pred!!.x > 116f)
    }

    @Test
    fun testRapidZigZagMicroStrokes() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        var curX = 500f
        var direction = 1f

        // Rapidly alternate direction every 2 samples (extreme zig-zag sketching)
        for (i in 0 until 16) {
            if (i % 2 == 0) direction = -direction
            curX += direction * 15f
            predictor.addPoint(curX, 500f, 0.8f, time)
            time += 8L

            val pred = predictor.predictTouchPoint()
            if (pred != null) {
                // If a prediction is made, it must never exceed 1/10 screen diagonal (200px)
                val dist = hypot(pred.x - curX, pred.y - 500f)
                assertTrue("Predicted distance during zig-zag must not overshoot ($dist <= 200)", dist <= 200f)
            }
        }
    }

    @Test
    fun testLongStrokeNumericalStability() {
        val kf = KalmanFilter4D(sigmaProcess = 0.01, sigmaMeasurement = 1.0)
        kf.reset(100.0)

        // Simulate a continuous 5,000-sample stroke (approx. 20-40 seconds of sustained sketching)
        var pos = 100.0
        var vel = 1.2
        for (i in 0 until 5000) {
            vel += kotlin.math.sin(i * 0.05) * 0.1
            pos += vel
            kf.predict()
            kf.update(pos)

            assertTrue("Position state must be finite", kf.x[0].isFinite())
            assertTrue("Velocity state must be finite", kf.x[1].isFinite())
            assertTrue("Acceleration state must be finite", kf.x[2].isFinite())
            assertTrue("Jerk state must be finite", kf.x[3].isFinite())
        }

        // Test with predictor engine over 3,000 updates
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)
        var t = 1000L
        var px = 200f
        var py = 200f
        for (i in 0 until 3000) {
            px += 5f
            py += 2f
            t += 8L
            predictor.addPoint(px, py, 0.6f, t)
        }
        val pred = predictor.predictTouchPoint()
        assertNotNull("Predictor must remain stable after 3,000 updates", pred)
        assertTrue("Predicted X must be finite and forward", pred!!.x > px)
        assertTrue("Predicted Y must be finite", pred.y.isFinite())
    }

    @Test
    fun testBatchedHistoricalIncomingVectorTangency() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        // DOWN at (100, 100)
        predictor.addPoint(100f, 100f, 0.5f, 1000L)

        // Event with batched historical points curving into a turn:
        // h0: (110, 100), h1: (120, 100), h2: (120, 110)
        // cur: (120, 120) -> final motion segment is purely vertical from (120, 110) to (120, 120)
        predictor.addPoint(110f, 100f, 0.5f, 1004L)
        predictor.addPoint(120f, 100f, 0.5f, 1008L)
        predictor.addPoint(120f, 110f, 0.5f, 1012L)
        predictor.addPoint(120f, 120f, 0.5f, 1016L)

        // Check that prevPosX and prevPosY accurately reflect the last historical point before curPos
        assertEquals("prevPosX must match final historical sample", 120f, predictor.prevPosX, 1e-4f)
        assertEquals("prevPosY must match final historical sample", 110f, predictor.prevPosY, 1e-4f)
        assertEquals("lastPosX must match current sample", 120f, predictor.lastPosX, 1e-4f)
        assertEquals("lastPosY must match current sample", 120f, predictor.lastPosY, 1e-4f)
    }

    @Test
    fun testNormalizeRangeEdgeCases() {
        // Normal ranges
        assertEquals(0.0, UniversalKalmanPredictor.normalizeRange(0.0, 0.2, 2.0), 1e-6)
        assertEquals(1.0, UniversalKalmanPredictor.normalizeRange(3.0, 0.2, 2.0), 1e-6)
        assertEquals(0.5, UniversalKalmanPredictor.normalizeRange(1.1, 0.2, 2.0), 1e-6)

        // Edge case: NaN input
        assertEquals(0.0, UniversalKalmanPredictor.normalizeRange(Double.NaN, 0.2, 2.0), 1e-6)

        // Edge case: minVal >= maxVal (inverted or degenerate bounds)
        assertEquals(0.0, UniversalKalmanPredictor.normalizeRange(1.0, 2.0, 0.2), 1e-6)
        assertEquals(0.0, UniversalKalmanPredictor.normalizeRange(1.0, 1.0, 1.0), 1e-6)

        // Edge case: extreme infinities
        assertEquals(1.0, UniversalKalmanPredictor.normalizeRange(Double.POSITIVE_INFINITY, 0.2, 2.0), 1e-6)
        assertEquals(0.0, UniversalKalmanPredictor.normalizeRange(Double.NEGATIVE_INFINITY, 0.2, 2.0), 1e-6)
    }

    @Test
    fun testInvalidConstructorAndParameterSanitization() {
        // Construct with invalid/negative parameters
        val badPredictor = UniversalKalmanPredictor(predictionTargetMs = -10.0f, maxSteps = -2)
        // Sanitization must restore healthy defaults
        assertTrue("Prediction target must be clamped positive", badPredictor.predictionTargetMs > 0f)
        assertTrue("Max steps must be clamped at least 1", badPredictor.maxSteps >= 1)

        // Set refresh rate with invalid values: must not corrupt avgReportRateMs
        badPredictor.setRefreshRate(-60f)
        badPredictor.setRefreshRate(0f)
        badPredictor.setRefreshRate(Float.NaN)
        badPredictor.setRefreshRate(Float.POSITIVE_INFINITY)
        assertTrue("avgReportRateMs must remain positive and finite", badPredictor.avgReportRateMs > 0f && badPredictor.avgReportRateMs.isFinite())

        // Set screen diagonal with invalid values
        badPredictor.setScreenDiagonal(-500f)
        badPredictor.setScreenDiagonal(Float.NaN)
        badPredictor.setScreenDiagonal(Float.POSITIVE_INFINITY)
        assertEquals(0f, badPredictor.screenDiagonal, 1e-4f)

        // Set prediction target with NaN
        val defaultTarget = badPredictor.predictionTargetMs
        badPredictor.setPredictionTargetMs(Float.NaN)
        badPredictor.setPredictionTargetMs(-5f)
        assertEquals(defaultTarget, badPredictor.predictionTargetMs, 1e-4f)
    }

    @Test
    fun testVariableReportRateTracking() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)

        // Feed points with varying delta times: 4ms (240Hz digitizer)
        var t = 1000L
        for (i in 0 until 10) {
            predictor.addPoint(i * 10f, 100f, 0.5f, t)
            t += 4L
        }

        // avgReportRateMs should adapt close to 4.0ms
        assertTrue("avgReportRateMs should adapt to 4ms digitizer, was ${predictor.avgReportRateMs}", predictor.avgReportRateMs in 3.8f..4.2f)

        // Feed points transitioning to 8ms (120Hz)
        for (i in 10 until 25) {
            predictor.addPoint(i * 10f, 100f, 0.5f, t)
            t += 8L
        }
        assertTrue("avgReportRateMs should adapt toward 8ms, was ${predictor.avgReportRateMs}", predictor.avgReportRateMs in 6.0f..8.5f)
    }

    @Test
    fun testKalmanFilterNonFiniteInputHardening() {
        val kf4 = KalmanFilter4D(sigmaProcess = 0.01, sigmaMeasurement = 1.0)
        kf4.reset(50.0)

        // Inject NaN / Inf into 4D filter update
        kf4.update(Double.NaN)
        kf4.update(Double.POSITIVE_INFINITY)
        kf4.update(Double.NEGATIVE_INFINITY)

        // State must remain at valid initial value 50.0
        assertEquals("Position state must be preserved across non-finite updates", 50.0, kf4.x[0], 1e-6)
        assertEquals("Velocity must remain 0.0", 0.0, kf4.x[1], 1e-6)

        // Reset with NaN must safely fall back to 0.0
        kf4.reset(Double.NaN)
        assertEquals("Reset with NaN must fall back to 0.0", 0.0, kf4.x[0], 1e-6)

        kf4.reset(Double.POSITIVE_INFINITY)
        assertEquals("Reset with Infinity must fall back to 0.0", 0.0, kf4.x[0], 1e-6)

        // Same test for 2D filter
        val kf2 = KalmanFilter2D(sigmaProcess = 0.01, sigmaMeasurement = 1.0)
        kf2.reset(0.5)
        kf2.update(Double.NaN)
        kf2.update(Double.POSITIVE_INFINITY)
        assertEquals("Pressure state must be preserved across non-finite updates", 0.5, kf2.x[0], 1e-6)

        kf2.reset(Double.NaN)
        assertEquals("Reset with NaN must fall back to 0.0", 0.0, kf2.x[0], 1e-6)
    }

    @Test
    fun testPenKalmanStateEstimatorNonFiniteGuards() {
        val estimator = PenKalmanStateEstimator()
        // Feed valid initial point
        estimator.update(100.0, 200.0, 0.5)
        assertEquals("Iterations must be 1", 1, estimator.numIterations)
        assertEquals(100.0, estimator.positionX, 1e-6)

        // Feed non-finite points: must be ignored and not increment numIterations or corrupt state
        estimator.update(Double.NaN, 200.0, 0.5)
        estimator.update(100.0, Double.POSITIVE_INFINITY, 0.5)
        estimator.update(100.0, 200.0, Double.NaN)
        assertEquals("Iterations must still be 1 after invalid updates", 1, estimator.numIterations)
        assertEquals(100.0, estimator.positionX, 1e-6)
        assertEquals(200.0, estimator.positionY, 1e-6)
        assertEquals(0.5, estimator.pressure, 1e-6)

        // Subsequent valid update works seamlessly
        estimator.update(110.0, 200.0, 0.6)
        assertEquals("Iterations must be 2 after valid update", 2, estimator.numIterations)
        assertTrue("Velocity X must be positive", estimator.velocityX > 0.0)
    }

    @Test
    fun testMaxPredictionWindowLockAt32Ms() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 80.0f)
        assertEquals("Target ms must be clamped to MAX_PREDICTION_MS (32ms)", UniversalKalmanPredictor.MAX_PREDICTION_MS, predictor.predictionTargetMs, 1e-4f)

        predictor.setPredictionTargetMs(100.0f)
        assertEquals("Target ms must remain clamped to MAX_PREDICTION_MS (32ms)", UniversalKalmanPredictor.MAX_PREDICTION_MS, predictor.predictionTargetMs, 1e-4f)

        predictor.setPredictionTargetMs(16.0f)
        assertEquals("Target ms can be set below MAX_PREDICTION_MS", 16.0f, predictor.predictionTargetMs, 1e-4f)
    }

    @Test
    fun testHighSpeedStrokeDoesNotOvershootGating() {
        // High speed motion at 1.5 px/ms (1500 px/s)
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 32.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        var x = 100f
        val y = 200f
        val pressure = 0.5f

        for (i in 0 until 12) {
            predictor.addPoint(x, y, pressure, time)
            x += 12f // 12px / 8ms = 1.5 px/ms
            time += 8L
        }

        val pred = predictor.predictTouchPoint()
        assertNotNull("High-speed linear motion should produce a forward prediction", pred)
        val lastX = predictor.lastPosX
        val displacement = pred!!.x - lastX
        // With 32ms cap, at 1.5 px/ms, displacement is well within reasonable bounds (< 50px) and does not overshoot
        assertTrue("Displacement must not overshoot, was $displacement", displacement in 5f..50f)
    }

    @Test
    fun testPredictionTargetMsBoundaryAndInvalidHandling() {
        // Constructor boundary and invalid checks
        val pNeg = UniversalKalmanPredictor(predictionTargetMs = -10.0f)
        assertEquals("Negative target must fallback to 20ms", 20.0f, pNeg.predictionTargetMs, 1e-4f)

        val pZero = UniversalKalmanPredictor(predictionTargetMs = 0.0f)
        assertEquals("Zero target must fallback to 20ms", 20.0f, pZero.predictionTargetMs, 1e-4f)

        val pNaN = UniversalKalmanPredictor(predictionTargetMs = Float.NaN)
        assertEquals("NaN target must fallback to 20ms", 20.0f, pNaN.predictionTargetMs, 1e-4f)

        val pInf = UniversalKalmanPredictor(predictionTargetMs = Float.POSITIVE_INFINITY)
        assertEquals("Infinite target must fallback to 20ms", 20.0f, pInf.predictionTargetMs, 1e-4f)

        val pSmall = UniversalKalmanPredictor(predictionTargetMs = 0.5f)
        assertEquals("Target < 1.0ms must be clamped to 1.0ms", 1.0f, pSmall.predictionTargetMs, 1e-4f)

        // setPredictionTargetMs validation
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 24.0f)
        predictor.setPredictionTargetMs(-5.0f)
        assertEquals("Negative target in setter must be ignored", 24.0f, predictor.predictionTargetMs, 1e-4f)

        predictor.setPredictionTargetMs(0.0f)
        assertEquals("Zero target in setter must be ignored", 24.0f, predictor.predictionTargetMs, 1e-4f)

        predictor.setPredictionTargetMs(Float.NaN)
        assertEquals("NaN target in setter must be ignored", 24.0f, predictor.predictionTargetMs, 1e-4f)

        predictor.setPredictionTargetMs(0.2f)
        assertEquals("Target < 1.0ms in setter must be clamped to 1.0ms", 1.0f, predictor.predictionTargetMs, 1e-4f)

        predictor.setPredictionTargetMs(50.0f)
        assertEquals("Target > MAX_PREDICTION_MS must be clamped to 32.0ms", UniversalKalmanPredictor.MAX_PREDICTION_MS, predictor.predictionTargetMs, 1e-4f)
    }

    @Test
    fun testPredictTouchPointScalarConsistency() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 20.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        // Test small buffer safety
        val smallBuf = FloatArray(2)
        val smallResult = predictor.predictTouchPointScalar(smallBuf)
        assertTrue("predictTouchPointScalar with buffer < 3 must safely return false", !smallResult)

        var time = 1000L
        var x = 100f
        val y = 200f
        for (i in 0 until 10) {
            predictor.addPoint(x, y, 0.6f, time)
            x += 10f
            time += 8L
        }

        val predObj = predictor.predictTouchPoint()
        assertNotNull("Steady motion must predict touch point", predObj)

        val buf = FloatArray(3)
        val success = predictor.predictTouchPointScalar(buf)
        assertTrue("predictTouchPointScalar must succeed for steady motion", success)
        assertEquals("Scalar X must match object X", predObj!!.x, buf[0], 1e-3f)
        assertEquals("Scalar Y must match object Y", predObj.y, buf[1], 1e-3f)
        assertEquals("Scalar Pressure must match object Pressure", predObj.pressure, buf[2], 1e-3f)
    }

    @Test
    fun testSharpReversalAndSCurveDirectionGating() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 32.0f)
        predictor.setScreenDiagonal(2000f)
        predictor.setRefreshRate(120f)

        var time = 1000L
        var x = 100f
        val y = 200f

        // Move right (+X)
        for (i in 0 until 10) {
            predictor.addPoint(x, y, 0.5f, time)
            x += 15f
            time += 8L
        }

        // Abrupt 180-degree turnaround back to the left (-X)
        // Point added moves backwards: x was 250, now moves to 235
        val turnX = x - 15f
        predictor.addPoint(turnX - 15f, y, 0.5f, time)
        time += 8L

        val pred = predictor.predictTouchPoint()
        // Upon 180° reversal, motion direction and velocity sharply diverge or jerk spikes:
        // Prediction must either be suppressed (null) or if non-null, must NOT overshoot in old +X direction
        if (pred != null) {
            assertTrue(
                "Prediction upon 180° reversal must not project ahead in old positive X direction (was ${pred.x}, turn point was ${turnX - 15f})",
                pred.x <= turnX
            )
        }
    }
}
