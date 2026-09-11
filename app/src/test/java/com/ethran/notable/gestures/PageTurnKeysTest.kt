package com.ethran.notable.gestures

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTurnKeysTest {

    @Test
    fun volume_and_page_keys_turn_pages() {
        assertEquals(1, pageTurnDirectionForKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(1, pageTurnDirectionForKey(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(-1, pageTurnDirectionForKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(-1, pageTurnDirectionForKey(KeyEvent.KEYCODE_PAGE_UP))
    }

    @Test
    fun dpad_maps_right_down_to_next_and_left_up_to_previous() {
        assertEquals(1, pageTurnDirectionForKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, pageTurnDirectionForKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(-1, pageTurnDirectionForKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(-1, pageTurnDirectionForKey(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun other_keys_are_left_to_the_system() {
        assertNull(pageTurnDirectionForKey(KeyEvent.KEYCODE_BACK))
        assertNull(pageTurnDirectionForKey(KeyEvent.KEYCODE_POWER))
        assertNull(pageTurnDirectionForKey(KeyEvent.KEYCODE_A))
    }
}
