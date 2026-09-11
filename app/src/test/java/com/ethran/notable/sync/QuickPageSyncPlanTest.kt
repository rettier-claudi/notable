package com.ethran.notable.sync

import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class QuickPageSyncPlanTest {

    private fun page(id: String, updatedAt: Long) = Page(id = id, updatedAt = Date(updatedAt))
    private fun row(id: String, syncedAt: Long) = PageSyncState(
        pageId = id, notebookId = QuickPageSyncService.QUICK_PAGES_NOTEBOOK_ID,
        syncedLocalUpdatedAt = Date(syncedAt), lastSyncedAt = Date(syncedAt),
    )

    @Test
    fun new_and_edited_pages_are_uploaded() {
        val plan = planQuickPageSync(
            localPages = listOf(page("new", 5_000), page("edited", 20_000), page("same", 10_000)),
            rows = mapOf("edited" to row("edited", 10_000), "same" to row("same", 10_000)),
            remoteNames = setOf("edited.json", "same.json"),
        )
        assertEquals(listOf("new", "edited"), plan.upload.map { it.id })
        assertEquals(emptyList<String>(), plan.deleteLocal)
        assertEquals(emptyList<String>(), plan.deleteRemote)
    }

    @Test
    fun page_removed_on_server_is_deleted_locally_only_when_unchanged() {
        val plan = planQuickPageSync(
            localPages = listOf(page("ingested", 10_000), page("ingested-but-edited", 30_000)),
            rows = mapOf(
                "ingested" to row("ingested", 10_000),
                "ingested-but-edited" to row("ingested-but-edited", 10_000),
            ),
            remoteNames = emptySet(),
        )
        assertEquals(listOf("ingested"), plan.deleteLocal)
        assertEquals(listOf("ingested-but-edited"), plan.upload.map { it.id })
    }

    @Test
    fun page_never_uploaded_is_not_deleted_when_absent_on_server() {
        // No row = never uploaded: absence on the server means nothing, upload it.
        val plan = planQuickPageSync(
            localPages = listOf(page("fresh", 10_000)),
            rows = emptyMap(),
            remoteNames = emptySet(),
        )
        assertEquals(listOf("fresh"), plan.upload.map { it.id })
        assertEquals(emptyList<String>(), plan.deleteLocal)
    }

    @Test
    fun page_deleted_on_device_is_deleted_on_server() {
        val plan = planQuickPageSync(
            localPages = emptyList(),
            rows = mapOf("gone" to row("gone", 10_000)),
            remoteNames = setOf("gone.json"),
        )
        assertEquals(listOf("gone"), plan.deleteRemote)
    }
}
