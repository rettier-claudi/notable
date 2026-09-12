package com.ethran.notable.sync

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncState
import com.ethran.notable.data.db.SyncStateValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class SyncScopeTest {

    private val heute = Folder(id = "f-heute", title = "Today")
    private val gestern = Folder(id = "f-gestern", title = "Yesterday")
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
        // Tomorrow the same id is "Yesterday" and another id is "Today".
        val tomorrow = listOf(
            heute.copy(title = "Yesterday"), gestern.copy(title = "Today"), own,
        )
        val selected = selectNotebooksInScope(books, tomorrow, SyncScope(), allSynced)
        assertEquals(listOf("root", "in-gestern"), selected.map { it.id })
    }

    @Test
    fun title_match_ignores_case_and_whitespace() {
        val sloppy = listOf(heute.copy(title = " today "), gestern, own)
        val selected = selectNotebooksInScope(books, sloppy, SyncScope(), allSynced)
        assertEquals(listOf("root", "in-heute"), selected.map { it.id })
    }

    @Test
    fun the_old_german_title_is_accepted_as_an_alias_during_the_transition() {
        val german = listOf(heute.copy(title = "Heute"), gestern.copy(title = "Gestern"), own)
        val selected = selectNotebooksInScope(books, german, SyncScope(), allSynced)
        assertEquals(listOf("root", "in-heute"), selected.map { it.id })
        // Both spellings at once (server renamed, a stale local copy not yet): both are in scope.
        val both = listOf(heute, gestern.copy(title = "Heute"), own)
        assertEquals(
            listOf("root", "in-heute", "in-gestern"),
            selectNotebooksInScope(books, both, SyncScope(), allSynced).map { it.id }
        )
    }

    @Test
    fun subfolders_of_today_are_in_the_default_scope_however_deep() {
        val scratch = Folder(id = "f-scratch", title = "Scratch notes", parentFolderId = "f-heute")
        val notebooks = Folder(id = "f-notebooks", title = "Notebooks", parentFolderId = "f-heute")
        val deeper = Folder(id = "f-deeper", title = "Deeper", parentFolderId = "f-notebooks")
        val yScratch = Folder(id = "f-y-scratch", title = "Scratch notes", parentFolderId = "f-gestern")
        val all = folders + scratch + notebooks + deeper + yScratch
        val moreBooks = books + book("in-scratch", "f-scratch") + book("in-notebooks", "f-notebooks") +
            book("in-deeper", "f-deeper") + book("in-y-scratch", "f-y-scratch")
        val rows = moreBooks.associate { it.id to synced(it.id) }
        val selected = selectNotebooksInScope(moreBooks, all, SyncScope(), rows)
        assertEquals(
            listOf("root", "in-heute", "in-scratch", "in-notebooks", "in-deeper"),
            selected.map { it.id }
        )
    }

    @Test
    fun only_a_root_folder_titled_today_counts_not_a_subfolder_with_that_name() {
        val nestedToday = Folder(id = "f-nested-today", title = "Today", parentFolderId = "f-own")
        val all = folders + nestedToday
        val moreBooks = books + book("in-nested-today", "f-nested-today")
        val rows = moreBooks.associate { it.id to synced(it.id) }
        val selected = selectNotebooksInScope(moreBooks, all, SyncScope(), rows)
        assertEquals(listOf("root", "in-heute"), selected.map { it.id })
    }

    @Test
    fun folder_scope_includes_that_folders_subfolders() {
        val scratch = Folder(id = "f-scratch", title = "Scratch notes", parentFolderId = "f-gestern")
        val all = folders + scratch
        val moreBooks = books + book("in-scratch", "f-scratch")
        val rows = moreBooks.associate { it.id to synced(it.id) }
        assertEquals(
            listOf("in-gestern", "in-scratch"),
            selectNotebooksInScope(moreBooks, all, SyncScope(folderId = "f-gestern"), rows).map { it.id }
        )
        // Inside the subfolder itself: just that subfolder (and nothing from the parent).
        assertEquals(
            listOf("in-scratch"),
            selectNotebooksInScope(moreBooks, all, SyncScope(folderId = "f-scratch"), rows).map { it.id }
        )
    }

    @Test
    fun a_folder_cycle_from_a_foreign_folders_json_terminates() {
        val a = Folder(id = "a", title = "Today", parentFolderId = null)
        val b = Folder(id = "b", title = "B", parentFolderId = "a")
        val c = Folder(id = "c", title = "C", parentFolderId = "b")
        val bLoop = b.copy(parentFolderId = "c") // b -> c -> b
        assertEquals(setOf("a"), folderSubtreeIds(listOf(a, bLoop, c), listOf("a")))
        assertEquals(setOf("b", "c"), folderSubtreeIds(listOf(a, bLoop, c), listOf("b")))
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
