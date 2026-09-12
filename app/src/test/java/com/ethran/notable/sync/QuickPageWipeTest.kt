package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickPageWipeTest {

    private val rows = (1..6).map { "p$it" }.toSet()

    @Test
    fun six_locked_pages_vanishing_at_once_are_deleted_without_question() {
        // 2026-09-12: six sent pages on the server, the bridge ingests and removes all six.
        val split = partitionQuickPageDeletions(rows.toList(), lockedIds = rows, rowIds = rows)
        assertEquals(rows.toList(), split.delete)
        assertEquals(emptyList<String>(), split.refused)
        assertEquals(0, split.unlockedRowCount)
    }

    @Test
    fun never_sent_pages_still_fall_under_the_wipe_guard() {
        val split = partitionQuickPageDeletions(rows.toList(), lockedIds = emptySet(), rowIds = rows)
        assertEquals(emptyList<String>(), split.delete)
        assertEquals(rows.toList(), split.refused)
        assertEquals(6, split.unlockedRowCount)
    }

    @Test
    fun locked_pages_are_deleted_even_when_the_unlocked_ones_trip_the_guard() {
        val locked = setOf("p1", "p2")
        val split = partitionQuickPageDeletions(rows.toList(), lockedIds = locked, rowIds = rows)
        assertEquals(listOf("p1", "p2"), split.delete)
        assertEquals(listOf("p3", "p4", "p5", "p6"), split.refused)
        // Measured against never-sent rows only.
        assertEquals(4, split.unlockedRowCount)
    }

    @Test
    fun locked_pages_do_not_shrink_the_reference_the_guard_measures_against_unfairly() {
        // 4 locked rows and 2 unlocked rows; both unlocked vanish -> 2 of 2, below the floor of 3.
        val locked = setOf("p1", "p2", "p3", "p4")
        val split = partitionQuickPageDeletions(listOf("p5", "p6"), lockedIds = locked, rowIds = rows)
        assertEquals(listOf("p5", "p6"), split.delete)
        assertEquals(emptyList<String>(), split.refused)
    }

    @Test
    fun the_guard_itself_is_unchanged() {
        assertEquals(true, looksLikeQuickPageWipe(3, 4))
        assertEquals(false, looksLikeQuickPageWipe(2, 2))
        assertEquals(false, looksLikeQuickPageWipe(3, 6))
    }
}
