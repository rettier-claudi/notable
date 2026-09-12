package com.ethran.notable.sync

import com.ethran.notable.data.db.NotebookSyncState
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.db.SyncStateValue
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SentMarksTest {

    private fun quickRow(id: String, syncedAt: Long) = PageSyncState(
        pageId = id, notebookId = QuickPageSyncService.QUICK_PAGES_NOTEBOOK_ID,
        syncedLocalUpdatedAt = Date(syncedAt), lastSyncedAt = Date(syncedAt),
    )

    private fun notebookRow(state: String, anchor: Long) = NotebookSyncState(
        notebookId = "nb", state = state, lastSyncedAt = Date(anchor),
        syncedLocalUpdatedAt = Date(anchor),
    )

    // --- The lock is not an edit (the bridge's nightly deletion keeps working) ---------------

    @Test
    fun locking_a_sent_quick_page_changes_nothing_the_quick_page_sync_looks_at() {
        // Uploaded at 10 000; the bridge ingested it and deleted the server file.
        val page = Page(id = "zettel", updatedAt = Date(10_000))
        val rows = mapOf("zettel" to quickRow("zettel", 10_000))
        val before = planQuickPageSync(listOf(page), rows, remoteNames = emptySet())

        val marks = SentMarks().withPageLocked("zettel", at = 99_000)
        assertTrue(marks.isPageLocked("zettel"))
        // The lock lives outside Page and page_sync_state: the planner sees the same inputs and
        // makes the same decision -- delete locally, upload nothing.
        val after = planQuickPageSync(listOf(page), rows, remoteNames = emptySet())
        assertEquals(before, after)
        assertEquals(listOf("zettel"), after.deleteLocal)
        assertTrue(after.upload.isEmpty())
    }

    @Test
    fun a_locked_page_still_on_the_server_is_not_uploaded_again() {
        val page = Page(id = "zettel", updatedAt = Date(10_000))
        val plan = planQuickPageSync(
            listOf(page), mapOf("zettel" to quickRow("zettel", 10_000)), remoteNames = setOf("zettel.json")
        )
        assertTrue(plan.upload.isEmpty())
        assertTrue(plan.deleteLocal.isEmpty())
    }

    @Test
    fun the_lock_of_a_deleted_quick_page_is_dropped() {
        val marks = SentMarks().withPageLocked("gone", 1).withPageLocked("kept", 2)
        val pruned = marks.pruned(existingQuickPageIds = setOf("kept"), notebookUpdatedAts = emptyMap())
        assertFalse(pruned.isPageLocked("gone"))
        assertTrue(pruned.isPageLocked("kept"))
    }

    // --- Notebook mark: gone with the next local change --------------------------------------

    @Test
    fun a_notebook_mark_holds_until_the_notebook_changes() {
        val marks = SentMarks().withNotebookMarked("nb", at = 50_000, anchorUpdatedAt = 40_000)
        assertTrue(marks.isNotebookMarked("nb", notebookUpdatedAt = 40_000))
        // First stroke after sending bumps Notebook.updatedAt.
        assertFalse(marks.isNotebookMarked("nb", notebookUpdatedAt = 60_000))
        assertFalse(marks.isNotebookMarked("other", notebookUpdatedAt = 40_000))
        assertFalse(marks.isNotebookMarked("nb", notebookUpdatedAt = null))
    }

    @Test
    fun a_voided_notebook_mark_is_dropped_and_does_not_come_back() {
        val marks = SentMarks().withNotebookMarked("nb", at = 50_000, anchorUpdatedAt = 40_000)
        val pruned = marks.pruned(emptySet(), notebookUpdatedAts = mapOf("nb" to 60_000))
        assertTrue(pruned.sentNotebooks.isEmpty())
        // Even if the timestamp later went back (force download of an older server copy).
        assertFalse(pruned.isNotebookMarked("nb", notebookUpdatedAt = 30_000))
    }

    @Test
    fun an_unchanged_notebook_keeps_its_mark_and_pruning_is_a_no_op() {
        val marks = SentMarks().withNotebookMarked("nb", at = 50_000, anchorUpdatedAt = 40_000)
            .withPageLocked("p", 1)
        val pruned = marks.pruned(setOf("p"), mapOf("nb" to 40_000))
        assertSame(marks, pruned)
    }

    @Test
    fun a_mark_survives_a_server_copy_replacing_the_notebook() {
        val marks = SentMarks().withNotebookMarked("nb", at = 50_000, anchorUpdatedAt = 40_000)
        // Download writes the server's timestamp 70 000; not a change made here.
        val rebased = marks.rebasedForDownload("nb", localUpdatedAtBefore = 40_000, newUpdatedAt = 70_000)
        assertTrue(rebased.isNotebookMarked("nb", 70_000))
        assertFalse(rebased.isNotebookMarked("nb", 80_000))
    }

    @Test
    fun a_download_does_not_revive_a_mark_voided_by_a_local_change() {
        val marks = SentMarks().withNotebookMarked("nb", at = 50_000, anchorUpdatedAt = 40_000)
        val rebased = marks.rebasedForDownload("nb", localUpdatedAtBefore = 45_000, newUpdatedAt = 70_000)
        assertFalse(rebased.isNotebookMarked("nb", 70_000))
    }

    // --- Only a delivered send locks or marks ------------------------------------------------

    @Test
    fun only_a_confirmed_upload_plus_an_accepted_notification_locks_or_marks() {
        assertEquals(SendOutcome.LOCK_QUICK_PAGE, sendOutcome(isQuickPage = true, contentOnServer = true, webhookStatus = 200))
        assertEquals(SendOutcome.MARK_NOTEBOOK, sendOutcome(isQuickPage = false, contentOnServer = true, webhookStatus = 204))
        // Sync did not get the page up.
        assertEquals(SendOutcome.NOTHING, sendOutcome(true, contentOnServer = false, webhookStatus = 200))
        assertEquals(SendOutcome.NOTHING, sendOutcome(false, contentOnServer = false, webhookStatus = 200))
        // Webhook refused or unreachable.
        assertEquals(SendOutcome.NOTHING, sendOutcome(true, contentOnServer = true, webhookStatus = 500))
        assertEquals(SendOutcome.NOTHING, sendOutcome(true, contentOnServer = true, webhookStatus = 301))
        assertEquals(SendOutcome.NOTHING, sendOutcome(false, contentOnServer = true, webhookStatus = null))
    }

    @Test
    fun quick_page_content_counts_as_on_the_server_only_when_uploaded_and_not_edited_since() {
        assertTrue(quickPageContentOnServer(10_000, quickRow("p", 10_000)))
        assertTrue(quickPageContentOnServer(10_500, quickRow("p", 10_000))) // within tolerance
        assertFalse(quickPageContentOnServer(12_000, quickRow("p", 10_000))) // edited after upload
        assertFalse(quickPageContentOnServer(10_000, null)) // never uploaded
        assertFalse(quickPageContentOnServer(null, quickRow("p", 10_000))) // page gone
        val notebookRow = quickRow("p", 10_000).copy(notebookId = "some-notebook")
        assertFalse(quickPageContentOnServer(10_000, notebookRow))
    }

    @Test
    fun notebook_content_counts_as_on_the_server_only_when_synced_and_not_edited_since() {
        assertTrue(notebookContentOnServer(40_000, notebookRow(SyncStateValue.SYNCED, 40_000)))
        assertFalse(notebookContentOnServer(45_000, notebookRow(SyncStateValue.SYNCED, 40_000)))
        assertFalse(notebookContentOnServer(40_000, notebookRow(SyncStateValue.ERROR, 40_000)))
        assertFalse(notebookContentOnServer(40_000, notebookRow(SyncStateValue.CONFLICT, 40_000)))
        assertFalse(notebookContentOnServer(40_000, notebookRow(SyncStateValue.REMOTE_AHEAD, 40_000)))
        assertFalse(notebookContentOnServer(40_000, null))
    }

    // --- Persistence ------------------------------------------------------------------------

    @Test
    fun marks_round_trip_through_the_kv_json() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val marks = SentMarks().withPageLocked("p", 1).withNotebookMarked("nb", 2, 3)
        val decoded = json.decodeFromString(SentMarks.serializer(), json.encodeToString(SentMarks.serializer(), marks))
        assertEquals(marks, decoded)
        assertEquals(SentMarks(), json.decodeFromString(SentMarks.serializer(), "{}"))
    }
}
