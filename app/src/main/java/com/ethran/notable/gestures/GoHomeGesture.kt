package com.ethran.notable.gestures

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.ethran.notable.data.datastore.GlobalAppSettings
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork: calls [onGesture] when the user makes the gesture that is set to *Go home* in the editor
 * ([isGoHomeGesture]). Only watches — runs in the Initial pass and consumes nothing — so scrolling,
 * tapping and dragging in the content below work as before.
 */
fun Modifier.goHomeGesture(onGesture: () -> Unit): Modifier = composed {
    // The detector runs across recompositions; it must call the current callback, not the first.
    val latest by rememberUpdatedState(onGesture)
    pointerInput(Unit) { detectGoHome { latest() } }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectGoHome(onGesture: () -> Unit) {
    val thresholds = GestureThresholds(density = this)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val tracker = PointerTracker(now = { SystemClock.uptimeMillis() })
        tracker.update(down)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) if (change.type == PointerType.Touch) tracker.update(change)
            if (tracker.pressedCount() > 0) continue
            // E-ink panels drop a contact of a multi-finger gesture for a few ms (see the editor).
            if (tracker.maxConcurrentPressed < 2) break
            val reland = withTimeoutOrNull(TOUCH_RELAND_GRACE_MS) {
                var e = awaitPointerEvent(PointerEventPass.Initial)
                while (e.changes.none { it.type == PointerType.Touch && it.pressed })
                    e = awaitPointerEvent(PointerEventPass.Initial)
                e
            } ?: break
            for (change in reland.changes) if (change.type == PointerType.Touch) tracker.update(change)
        }
        val settings = GlobalAppSettings.current
        val events = classifyGesture(
            tracker = tracker,
            mode = GestureMode.Normal,
            // Recognition only: no scroll or zoom events are acted on here.
            flags = GestureFlags(smoothScroll = true, continuousZoom = true),
            thresholds = thresholds,
        )
        if (isGoHomeGesture(events, settings)) onGesture()
    }
}
