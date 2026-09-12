package com.ethran.notable.gestures

import androidx.compose.ui.unit.Density
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.AppSettings.GestureAction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_000L

/** The fork's two-finger swipes (left/right) and the actions "home" and "send". */
class TwoFingerSwipeTest {

    private var clockTime = T0
    private val tracker = PointerTracker(now = { clockTime })

    // Density 1: swipe 160 px, pan entry 30 px (see GestureClassifierTest).
    private val thresholds = GestureThresholds(Density(1f))
    private val flags = GestureFlags(smoothScroll = true, continuousZoom = false)

    private fun enterTransform(reserve: Boolean, continuousZoom: Boolean = false) =
        shouldEnterTransform(tracker, GestureMode.Normal, thresholds, continuousZoom, reserve)

    /** Two fingers side by side, both moving by (dx, dy) in [steps] events. */
    private fun twoFingerDrag(dx: Float, dy: Float, steps: Int = 4, check: (Int) -> Unit = {}) {
        tracker.down(1, 300f, 500f, T0)
        tracker.down(2, 420f, 500f, T0 + 10)
        for (i in 1..steps) {
            val t = T0 + 10 + i * 30L
            tracker.moveTo(1, 300f + dx * i / steps, 500f + dy * i / steps, t)
            tracker.moveTo(2, 420f + dx * i / steps, 500f + dy * i / steps, t)
            check(i)
        }
    }

    private fun liftBoth(dx: Float, dy: Float) {
        val t = T0 + 10 + 5 * 30L
        tracker.up(1, 300f + dx, 500f + dy, t)
        tracker.up(2, 420f + dx, 500f + dy, t + 2)
    }

    @Test
    fun `horizontal two-finger movement is not a pan while the swipe is reserved`() {
        twoFingerDrag(dx = -240f, dy = 10f) { assertFalse(enterTransform(reserve = true)) }
        liftBoth(-240f, 10f)
        assertEquals(
            listOf(GestureEvent.Swipe(fingers = 2, direction = GestureEvent.Direction.Left)),
            classifyGesture(tracker, GestureMode.Normal, flags, thresholds)
        )
    }

    @Test
    fun `rightward two-finger swipe`() {
        twoFingerDrag(dx = 240f, dy = -5f)
        liftBoth(240f, -5f)
        assertEquals(
            listOf(GestureEvent.Swipe(fingers = 2, direction = GestureEvent.Direction.Right)),
            classifyGesture(tracker, GestureMode.Normal, flags, thresholds)
        )
    }

    @Test
    fun `vertical two-finger movement still pans when the swipe is reserved`() {
        twoFingerDrag(dx = 5f, dy = 200f)
        assertTrue(enterTransform(reserve = true))
    }

    @Test
    fun `without a reservation horizontal two fingers pan as before`() {
        twoFingerDrag(dx = -240f, dy = 10f)
        assertTrue(enterTransform(reserve = false))
    }

    @Test
    fun `a pinch zooms even while the swipe is reserved`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        tracker.moveTo(2, 140f, 0f, T0 + 80) // ratio 0.4, fingers spreading sideways
        assertTrue(enterTransform(reserve = true, continuousZoom = true))
    }

    @Test
    fun `a short two-finger nudge is not a swipe`() {
        // 120 px would pass the 100 px multi-finger threshold; two fingers need the full 160 px,
        // because a two-finger swipe may be "send", which locks a quick page.
        twoFingerDrag(dx = -120f, dy = 0f)
        liftBoth(-120f, 0f)
        assertTrue(classifyGesture(tracker, GestureMode.Normal, flags, thresholds).isEmpty())
    }

    @Test
    fun `two-finger tap stays a tap`() {
        tracker.down(1, 100f, 100f, T0)
        tracker.down(2, 200f, 100f, T0 + 10)
        tracker.up(1, 102f, 100f, T0 + 75)
        tracker.up(2, 201f, 100f, T0 + 80)
        assertFalse(enterTransform(reserve = true))
        assertEquals(listOf(GestureEvent.Tap(fingers = 2)), classifyGesture(tracker, GestureMode.Normal, flags, thresholds))
    }

    // --- Mapping ------------------------------------------------------------------------------

    @Test
    fun `assigned two-finger swipes use their own settings`() {
        val settings = AppSettings(
            version = 1,
            swipeLeftTwoFingersAction = GestureAction.Send,
            swipeRightTwoFingersAction = GestureAction.GoHome,
        )
        assertTrue(settings.twoFingerSwipeAssigned)
        assertEquals(GestureAction.Send, twoFingerSwipeAction(GestureEvent.Direction.Left, settings))
        assertEquals(GestureAction.GoHome, twoFingerSwipeAction(GestureEvent.Direction.Right, settings))
    }

    @Test
    fun `one assigned direction leaves the other one without action`() {
        val settings = AppSettings(version = 1, swipeLeftTwoFingersAction = GestureAction.Send)
        assertEquals(GestureAction.Send, twoFingerSwipeAction(GestureEvent.Direction.Left, settings))
        assertEquals(GestureAction.None, twoFingerSwipeAction(GestureEvent.Direction.Right, settings))
    }

    @Test
    fun `unassigned two-finger swipes keep the legacy mapping`() {
        val settings = AppSettings(version = 1)
        assertFalse(settings.twoFingerSwipeAssigned)
        assertEquals(settings.twoFingerSwipeLeftAction, twoFingerSwipeAction(GestureEvent.Direction.Left, settings))
        assertEquals(settings.twoFingerSwipeRightAction, twoFingerSwipeAction(GestureEvent.Direction.Right, settings))
    }

    @Test
    fun `home and send can be put on every gesture and survive persistence`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val settings = AppSettings(
            version = 1,
            doubleTapAction = GestureAction.GoHome,
            twoFingerTapAction = GestureAction.Send,
            swipeLeftAction = GestureAction.Send,
            swipeRightAction = GestureAction.GoHome,
            twoFingerSwipeLeftAction = GestureAction.Send,
            twoFingerSwipeRightAction = GestureAction.GoHome,
            swipeLeftTwoFingersAction = GestureAction.Send,
            swipeRightTwoFingersAction = GestureAction.GoHome,
        )
        val decoded = json.decodeFromString(
            AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings)
        )
        assertEquals(settings, decoded)
        // Settings written before the fork's fields decode with the swipes unassigned.
        val old = json.decodeFromString(AppSettings.serializer(), """{"version":1}""")
        assertEquals(GestureAction.None, old.swipeLeftTwoFingersAction)
        assertEquals(GestureAction.None, old.swipeRightTwoFingersAction)
    }
}
