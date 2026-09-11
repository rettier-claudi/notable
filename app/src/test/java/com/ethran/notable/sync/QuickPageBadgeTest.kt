package com.ethran.notable.sync

import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class QuickPageBadgeTest {

    private fun page(id: String, updatedAt: Long) = Page(id = id, updatedAt = Date(updatedAt))
    private fun row(id: String, syncedAt: Long) = PageSyncState(
        pageId = id, notebookId = QuickPageSyncService.QUICK_PAGES_NOTEBOOK_ID,
        syncedLocalUpdatedAt = Date(syncedAt), lastSyncedAt = Date(syncedAt),
    )

    @Test
    fun uploaded_and_untouched_page_reads_as_synced() {
        val badges = quickPageBadgesFor(
            pages = listOf(page("p", 10_000)),
            rows = listOf(row("p", 10_000)),
            syncing = false,
        )
        assertEquals(mapOf("p" to SyncBadge.SYNCED), badges)
    }

    @Test
    fun never_uploaded_or_edited_page_reads_as_not_synced() {
        val badges = quickPageBadgesFor(
            pages = listOf(page("fresh", 10_000), page("edited", 30_000)),
            rows = listOf(row("edited", 10_000)),
            syncing = false,
        )
        assertEquals(
            mapOf("fresh" to SyncBadge.NOT_SYNCED, "edited" to SyncBadge.NOT_SYNCED),
            badges
        )
    }

    @Test
    fun pending_pages_spin_while_a_sync_runs_but_synced_ones_do_not() {
        val badges = quickPageBadgesFor(
            pages = listOf(page("pending", 30_000), page("done", 10_000)),
            rows = listOf(row("pending", 10_000), row("done", 10_000)),
            syncing = true,
        )
        assertEquals(
            mapOf("pending" to SyncBadge.SYNCING, "done" to SyncBadge.SYNCED),
            badges
        )
    }
}
