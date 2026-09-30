package com.ethran.notable.data

import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.db.isScratch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ScratchNoteGrowthTest {

    private fun page(id: String, notebookId: String? = null) = Page(id = id, notebookId = notebookId)
    private fun row(id: String, notebookId: String) =
        PageSyncState(pageId = id, notebookId = notebookId, remoteEtag = null,
            syncedLocalUpdatedAt = Date(0), lastSyncedAt = Date(0))

    @Test
    fun `the quick page becomes page 1 of a scratch-kind notebook in its folder`() {
        val quick = Page(id = "p", parentFolderId = "f", background = "lined", backgroundType = "native")
        val book = scratchNotebookFor(quick, "nb", Date(1000))
        assertTrue(book.isScratch)
        assertEquals(listOf("p"), book.pageIds)
        assertEquals("p", book.openPageId)
        assertEquals("f", book.parentFolderId)
        assertEquals("lined", book.defaultBackground)
        assertEquals(Date(1000), book.updatedAt)
    }

    @Test
    fun `server file goes once the notebook has uploaded the page`() {
        val plan = planMovedQuickPageRemovals(
            listOf("p"), mapOf("p" to page("p", "nb")), mapOf("p" to row("p", "nb")), setOf("p.json"),
        )
        assertEquals(listOf("p"), plan.deleteRemote)
        assertEquals(emptyList<String>(), plan.forget)
    }

    @Test
    fun `before the notebook is up nothing is deleted`() {
        val plan = planMovedQuickPageRemovals(
            listOf("p"), mapOf("p" to page("p", "nb")), emptyMap(), setOf("p.json"),
        )
        assertEquals(MovedQuickPagePlan(emptyList(), emptyList()), plan)
    }

    @Test
    fun `unknown listing deletes and forgets nothing`() {
        val plan = planMovedQuickPageRemovals(
            listOf("p"), mapOf("p" to page("p", "nb")), mapOf("p" to row("p", "nb")), null,
        )
        assertEquals(MovedQuickPagePlan(emptyList(), emptyList()), plan)
    }

    @Test
    fun `already gone from the server is forgotten`() {
        val plan = planMovedQuickPageRemovals(
            listOf("p"), mapOf("p" to page("p", "nb")), mapOf("p" to row("p", "nb")), emptySet(),
        )
        assertEquals(MovedQuickPagePlan(emptyList(), listOf("p")), plan)
    }

    @Test
    fun `page deleted on the device takes its server file with it`() {
        val plan = planMovedQuickPageRemovals(listOf("p"), emptyMap(), emptyMap(), setOf("p.json"))
        assertEquals(listOf("p"), plan.deleteRemote)
    }

    @Test
    fun `a page that stayed a quick page is left to the quick-page sync`() {
        val plan = planMovedQuickPageRemovals(
            listOf("p"), mapOf("p" to page("p")), emptyMap(), setOf("p.json"),
        )
        assertEquals(MovedQuickPagePlan(emptyList(), listOf("p")), plan)
    }
}
