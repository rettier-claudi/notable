package com.ethran.notable.gestures

import android.view.KeyEvent

/**
 * Hardware keys that turn pages in the editor. Covers physical page buttons (Boox Page/Go
 * series, Bluetooth clickers), volume keys, and Onyx's configurable system gestures, which
 * deliver a side swipe as a Page-Up/Down or Volume key event to the foreground app.
 *
 * Returns +1 for "next page", -1 for "previous page", null for any other key.
 */
fun pageTurnDirectionForKey(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_MEDIA_NEXT,
    KeyEvent.KEYCODE_SPACE -> 1

    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1

    else -> null
}
