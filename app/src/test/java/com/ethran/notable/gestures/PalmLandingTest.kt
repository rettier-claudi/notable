package com.ethran.notable.gestures

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_000L

/** Fork: a palm touching down as a one-finger drag ends is no second finger. */
class PalmLandingTest {

    private var clockTime = T0
    private val thresholds = GestureThresholds(Density(1f))
    private val tracker = PointerTracker(now = { clockTime }, lateFingerTravelPx = thresholds.tapMovementTolerancePx)
    private val flags = GestureFlags(smoothScroll = true, continuousZoom = false)

    @Test
    fun `palm landing at the end of a one-finger swipe keeps it a one-finger swipe`() {
        tracker.down(1, 600f, 500f, T0)
        for (i in 1..6) tracker.moveTo(1, 600f - i * 40f, 500f, T0 + i * 40L)
        tracker.down(2, 900f, 900f, T0 + 250) // the palm
        tracker.moveTo(2, 905f, 880f, T0 + 270)
        tracker.up(1, 360f, 500f, T0 + 280)
        assertEquals(1, tracker.maxConcurrentPressed)
        assertEquals(0, tracker.pressedCount())
        val events = classifyGesture(tracker, GestureMode.Normal, flags, thresholds)
        assertEquals(listOf(GestureEvent.Swipe(1, GestureEvent.Direction.Left)), events)
    }

    @Test
    fun `two fingers landing together still make a two-finger gesture`() {
        tracker.down(1, 300f, 500f, T0)
        tracker.moveTo(1, 330f, 500f, T0 + 60)
        tracker.down(2, 420f, 500f, T0 + 80) // moved already, but within the grace window
        assertEquals(2, tracker.maxConcurrentPressed)
    }

    @Test
    fun `second finger joining a resting first finger counts`() {
        tracker.down(1, 300f, 500f, T0)
        tracker.moveTo(1, 302f, 501f, T0 + 200)
        tracker.down(2, 420f, 500f, T0 + 400)
        assertTrue(tracker.maxConcurrentPressed == 2)
    }
}
