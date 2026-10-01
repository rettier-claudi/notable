package com.ethran.notable.gestures

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.AppSettings.GestureAction
import com.ethran.notable.gestures.GestureEvent.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoHomeGestureTest {

    private val defaults = AppSettings(version = 1)

    @Test
    fun `three-finger swipe set to go home is recognized`() {
        val s = defaults.copy(twoFingerSwipeRightAction = GestureAction.GoHome)
        assertTrue(isGoHomeGesture(listOf(GestureEvent.Swipe(3, Direction.Right)), s))
        assertFalse(isGoHomeGesture(listOf(GestureEvent.Swipe(3, Direction.Left)), s))
    }

    @Test
    fun `two-finger swipe set to go home is recognized`() {
        val s = defaults.copy(swipeLeftTwoFingersAction = GestureAction.GoHome)
        assertTrue(isGoHomeGesture(listOf(GestureEvent.Swipe(2, Direction.Left)), s))
    }

    @Test
    fun `one-finger swipe and two-finger tap follow their settings`() {
        val s = defaults.copy(swipeRightAction = GestureAction.GoHome, twoFingerTapAction = GestureAction.GoHome)
        assertTrue(isGoHomeGesture(listOf(GestureEvent.Swipe(1, Direction.Right)), s))
        assertTrue(isGoHomeGesture(listOf(GestureEvent.Tap(2)), s))
        assertFalse(isGoHomeGesture(listOf(GestureEvent.Tap(1)), s))
    }

    @Test
    fun `nothing set to go home - nothing recognized`() {
        val all = listOf(
            GestureEvent.Swipe(1, Direction.Left), GestureEvent.Swipe(2, Direction.Right),
            GestureEvent.Swipe(3, Direction.Left), GestureEvent.Tap(2), GestureEvent.DoubleTap,
        )
        assertFalse(isGoHomeGesture(all, defaults))
    }

    @Test
    fun `double tap is ignored even when set to go home`() {
        val s = defaults.copy(doubleTapAction = GestureAction.GoHome)
        assertFalse(isGoHomeGesture(listOf(GestureEvent.DoubleTap), s))
    }

    @Test
    fun `swipe mapping matches the editor`() {
        assertEquals(defaults.swipeLeftAction, swipeAction(GestureEvent.Swipe(1, Direction.Left), defaults))
        assertEquals(defaults.twoFingerSwipeRightAction, swipeAction(GestureEvent.Swipe(3, Direction.Right), defaults))
    }
}
