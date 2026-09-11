package com.ethran.notable.utils

/**
 * When the activity last came to the foreground. Lets the editor tell "the sync that ran right
 * after start/wake-up replaced my page" (safe to reload silently: the user has not drawn yet)
 * from "the page changed on the server while I was working on it" (ask first).
 */
object AppResumeClock {
    @Volatile
    var lastResumedAt: Long = 0L

    fun markResumed() {
        lastResumedAt = System.currentTimeMillis()
    }

    fun millisSinceResume(): Long = System.currentTimeMillis() - lastResumedAt
}
