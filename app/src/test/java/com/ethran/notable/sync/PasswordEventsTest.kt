package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordEventsTest {

    @Test
    fun new_lines_are_pending_until_uploaded() {
        val empty = PasswordEvents()
        assertFalse(empty.pending)

        val one = empty.appended("a")
        assertTrue(one.pending)

        val uploaded = one.copy(uploadedThrough = "a")
        assertFalse(uploaded.pending)
        assertTrue(uploaded.appended("b").pending)
    }

    @Test
    fun keeps_only_the_newest_lines() {
        var events = PasswordEvents()
        repeat(PasswordEvents.MAX_LINES + 5) { events = events.appended("line $it") }

        assertEquals(PasswordEvents.MAX_LINES, events.lines.size)
        assertEquals("line 5", events.lines.first())
        assertEquals("line ${PasswordEvents.MAX_LINES + 4}", events.lines.last())
    }

    @Test
    fun text_is_one_line_per_event() {
        assertEquals("a\nb\n", PasswordEvents().appended("a").appended("b").text())
    }

    @Test
    fun events_file_is_outside_the_synced_trees() {
        assertEquals("/notable/diagnostics/sync-password.log", SyncPaths.passwordEventsFile())
    }
}
