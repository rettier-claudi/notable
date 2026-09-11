package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDeletionSelectionTest {

    @Test
    fun notebook_downloaded_in_this_run_is_not_a_local_deletion() {
        // Downloaded during this run: has a sync-state row, was absent before the download step,
        // exists locally now. Used to be tombstoned right after arriving.
        val deleted = selectLocallyDeletedNotebookIds(
            syncedNotebookIds = setOf("kept", "fresh-download"),
            preDownloadNotebookIds = setOf("kept"),
            currentLocalNotebookIds = setOf("kept", "fresh-download"),
        )

        assertEquals(emptySet<String>(), deleted)
    }

    @Test
    fun notebook_deleted_by_the_user_is_detected() {
        val deleted = selectLocallyDeletedNotebookIds(
            syncedNotebookIds = setOf("kept", "gone"),
            preDownloadNotebookIds = setOf("kept"),
            currentLocalNotebookIds = setOf("kept"),
        )

        assertEquals(setOf("gone"), deleted)
    }

    @Test
    fun notebook_deleted_while_the_sync_ran_waits_for_the_next_run() {
        // Present at the start of the run, deleted meanwhile: not this run's business.
        val deleted = selectLocallyDeletedNotebookIds(
            syncedNotebookIds = setOf("late-delete"),
            preDownloadNotebookIds = setOf("late-delete"),
            currentLocalNotebookIds = emptySet(),
        )

        assertEquals(emptySet<String>(), deleted)
    }
}
