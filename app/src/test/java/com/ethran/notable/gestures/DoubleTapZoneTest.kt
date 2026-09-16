package com.ethran.notable.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapZoneTest {

    // Density 1 ⇒ the 40 dp double-tap distance is 40 px.
    private val thresholds = GestureThresholds(Density(1f))
    private val width = 1860f
    private val height = 2480f

    @Test
    fun `top left counts`() {
        assertTrue(isInDoubleTapZone(Offset(100f, 100f), width, height))
        assertTrue(isInDoubleTapZone(Offset(1200f, 1600f), width, height))
    }

    @Test
    fun `resting hand on the right or bottom third does not`() {
        assertFalse(isInDoubleTapZone(Offset(1500f, 300f), width, height))
        assertFalse(isInDoubleTapZone(Offset(300f, 2000f), width, height))
        assertFalse(isInDoubleTapZone(Offset(1500f, 2000f), width, height))
    }

    @Test
    fun `second tap must land near the first`() {
        assertTrue(isSecondTapNearFirst(Offset(300f, 300f), Offset(320f, 330f), thresholds))
        assertFalse(isSecondTapNearFirst(Offset(300f, 300f), Offset(300f, 1200f), thresholds))
    }
}
