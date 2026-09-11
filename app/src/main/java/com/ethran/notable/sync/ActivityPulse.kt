package com.ethran.notable.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "The user just did something in the app": every touch/key the activity sees and every content
 * write (a committed stroke). [ForegroundSyncController] turns this into the two activity-driven
 * syncs that replace the old periodic foreground poll — one after a stretch of quiet, one when
 * work resumes after a long pause.
 *
 * A plain object rather than an injected singleton: the emitters are deep in the input and
 * persistence paths where threading a dependency through would be noise, and the cost per touch
 * must stay at "write a Long".
 */
object ActivityPulse {
    private val _pulses = MutableSharedFlow<Long>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emissions carry the timestamp of the activity. */
    val pulses: SharedFlow<Long> = _pulses.asSharedFlow()

    @Volatile
    var lastActivityAt: Long = 0L
        private set

    /**
     * Record activity. Called from touch/key dispatch and from every content write, so it is on a
     * hot path: the flow drops rather than suspends, and a burst of touches inside
     * [MIN_PULSE_GAP_MS] emits once.
     */
    fun touch() {
        val now = System.currentTimeMillis()
        val previous = lastActivityAt
        lastActivityAt = now
        if (now - previous >= MIN_PULSE_GAP_MS) _pulses.tryEmit(now)
    }

    private const val MIN_PULSE_GAP_MS = 1_000L
}
