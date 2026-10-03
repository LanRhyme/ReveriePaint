// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyProfessionalTest {
    @Test fun `small brushes keep full precision on large canvases via sparse fallback`() {
        assertEquals(1, LiquifyProfessional.fieldStep(8f))
        assertTrue(!LiquifyProfessional.supportsFullField(8192, 8192, 8f))
        assertTrue(LiquifyProfessional.supportsFullField(1024, 1024, 8f))
        assertEquals(4, LiquifyProfessional.fieldStep(300f))
    }
    @Test fun `cursor diameter defines support and steps bound hard edges`() {
        assertEquals(40f, LiquifyProfessional.radius(80f), 0f)
        assertEquals(4f, LiquifyProfessional.radius(0f), 0f)
        assertEquals(25, LiquifyProfessional.substeps(80f, 80f))
    }

    @Test fun `radial motion and hold have different explicit units`() {
        assertEquals(.04f, LiquifyProfessional.gain(1, 4f, 80f, 1f), .000001f)
        assertEquals(.024f, LiquifyProfessional.gain(1, 0f, 80f, .016f), .000001f)
        assertEquals(.032f, LiquifyProfessional.gain(3, 0f, 80f, .016f), .000001f)
        assertEquals(0f, LiquifyProfessional.gain(0, 4f, 80f, Float.NaN), 0f)
        assertEquals(1f, LiquifyProfessional.gain(5, 4f, 80f, 2f), 0f)
    }

    @Test fun `frame budgeting preserves the complete professional path`() {
        val s = LiquifyInteractionSession()
        s.begin(20f, 30f, 3)
        s.submitTarget(100f, 30f, .4f)
        var dabs = 0
        while (s.prepareFlush(80f, 0, false, 3, professional = true)) {
            assertTrue(s.planSteps <= 3)
            assertEquals(1f, s.planStrengthScale, 0f)
            assertEquals(.4f, s.planPressureFactor, 0f)
            dabs += s.planSteps
            s.advanceFlush(s.planSteps)
        }
        assertEquals(25, dabs)
        assertEquals(100f, s.renderedX, .0001f)
    }
}
