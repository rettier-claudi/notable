package com.ethran.notable.sync

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncState
import com.ethran.notable.data.db.SyncStateValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class SyncScopeTest {

    private val heute = Folder(id = "f-heute", title = "Heute")
    private val gestern = Folder(id = "f-gestern", title = "Gestern")
    private val own = Folder(id = "f-own", title = "New Folder")
    private val folders = listOf(heute, gestern, own)

    private fun book(id: String, folderId: String?, updatedAt: Long = 10_000) =
        Notebook(id = id, title = id, parentFolderId = folderId, updatedAt = Date(updatedAt))

    private fun synced(id: String, anchor: Long = 10_000, state: String = SyncStateValue.SYNCED) =
        NotebookSyncState(
            notebookId = id, state = state, lastSyncedAt = Date(anchor),
            syncedLocalUpdatedAt = Date(anchor),
        )

    private val books = listOf(
        book("root", null),
        book("in-heute", "f-heute"),
        book("in-gestern", "f-gestern"),
        book("in-own", "f-own"),
    )
    private val allSynced = books.associate { it.id to synced(it.id) }

    @Test
    fun default_scope_is_root_plus_the_folder_titled_heute() {
        val selected = selectNotebooksInScope(books, folders, SyncScope(), allSynced)
        assertEquals(listOf("root", "in-heute"), selected.map { it.id })
    }

    @Test
    fun heute_is_matched_by_title_not_id_so_the_nightly_rename_moves_the_scope() {
        // Tomorrow the same id is "Gestern" and another id is "Heute".
        val tomorrow = listOf(
            heute.copy(title = "Gestern"), gestern.copy(title = "Heute"), own,
        )
        val selected = selectNotebooksInScope(books, tomorrow, SyncScope(), allSynced)
        assertEquals(listOf("root", "in-gestern"), selected.map { it.id })
    }

    @Test
    fun title_match_ignores_case_and_whitespace() {
        val sloppy = listOf(heute.copy(title = " heute "), gestern, own)
        val selected = selectNotebooksInScope(books, sloppy, SyncScope(), allSynced)
        assertEquals(listOf("root", "in-heute"), selected.map { it.id })
    }

    @Test
    fun folder_scope_is_exactly_that_folder() {
        val selected = selectNotebooksInScope(books, folders, SyncScope(folderId = "f-own"), allSynced)
        assertEquals(listOf("in-own"), selected.map { it.id })
    }

    @Test
    fun a_named_notebook_is_included_whatever_its_folder() {
        val selected = selectNotebooksInScope(
            books, folders, SyncScope(extraNotebookId = "in-gestern"), allSynced
        )
        assertEquals(listOf("root", "in-heute", "in-gestern"), selected.map { it.id })
    }

    @Test
    fun locally_dirty_notebooks_are_included_in_every_scope() {
        val rows = allSynced + ("in-own" to synced("in-own", anchor = 5_000)) // edited since
        assertEquals(
            listOf("root", "in-heute", "in-own"),
            selectNotebooksInScope(books, folders, SyncScope(), rows).map { it.id }
        )
        assertEquals(
            listOf("in-gestern", "in-own"),
            selectNotebooksInScope(books, folders, SyncScope(folderId = "f-gestern"), rows).map { it.id }
        )
    }

    @Test
    fun never_synced_and_errored_notebooks_count_as_dirty() {
        val rows = allSynced - "in-gestern" + ("in-own" to synced("in-own", state = SyncStateValue.ERROR))
        val selected = selectNotebooksInScope(books, folders, SyncScope(), rows)
        assertEquals(listOf("root", "in-heute", "in-gestern", "in-own"), selected.map { it.id })
    }

    @Test
    fun a_move_within_tolerance_of_the_anchor_is_not_dirty() {
        val rows = allSynced + ("in-own" to synced("in-own", anchor = 9_500))
        val selected = selectNotebooksInScope(books, folders, SyncScope(), rows)
        assertEquals(listOf("root", "in-heute"), selected.map { it.id })
    }
}
