package com.ethran.notable.data

import com.ethran.notable.data.db.Folder
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderOrderTest {

    private fun f(id: String, title: String, parent: String? = null) =
        Folder(id = id, title = title, parentFolderId = parent)

    @Test
    fun heute_gestern_dates_descending_then_alphabetical() {
        val folders = listOf(
            f("z", "Zettel"), f("d1", "08.09.2026"), f("g", "Gestern"), f("n", "New Folder"),
            f("d3", "10.09.2026"), f("h", "Heute"), f("d2", "09.09.2026"), f("a", "arbeit"),
            f("y", "31.12.2025"),
        )
        assertEquals(
            listOf("Heute", "Gestern", "10.09.2026", "09.09.2026", "08.09.2026", "31.12.2025", "arbeit", "New Folder", "Zettel"),
            sortFoldersForBar(folders).map { it.title }
        )
    }

    @Test
    fun date_order_is_by_date_not_by_string() {
        val folders = listOf(f("a", "01.10.2026"), f("b", "30.09.2026"), f("c", "02.01.2027"))
        assertEquals(listOf("02.01.2027", "01.10.2026", "30.09.2026"), sortFoldersForBar(folders).map { it.title })
    }

    @Test
    fun heute_and_gestern_match_loosely_and_almost_dates_are_plain_titles() {
        val folders = listOf(f("a", "12.9.2026"), f("b", " heute"), f("c", "GESTERN"))
        assertEquals(listOf(" heute", "GESTERN", "12.9.2026"), sortFoldersForBar(folders).map { it.title })
    }

    @Test
    fun bar_shows_root_folders_wherever_you_are() {
        val all = listOf(f("h", "Heute"), f("n", "New Folder"), f("sub", "Sub", parent = "n"))
        assertEquals(listOf("h", "n"), folderBar(all, currentId = null).map { it.id })
        assertEquals(listOf("h", "n"), folderBar(all, currentId = "h").map { it.id })
    }

    @Test
    fun bar_appends_the_path_to_a_nested_current_folder_and_its_children() {
        val all = listOf(
            f("h", "Heute"), f("n", "New Folder"),
            f("sub", "Sub", parent = "n"), f("subsub", "Deeper", parent = "sub"), f("sib", "Sibling", parent = "sub"),
        )
        // Inside "Sub": root folders, then Sub itself (path), then its children.
        assertEquals(listOf("h", "n", "sub", "subsub", "sib"), folderBar(all, currentId = "sub").map { it.id })
        // Inside a root folder with children: children are appended so they stay reachable.
        assertEquals(listOf("h", "n", "sub"), folderBar(all, currentId = "n").map { it.id })
    }

    @Test
    fun bar_tolerates_an_unknown_current_folder() {
        val all = listOf(f("h", "Heute"))
        assertEquals(listOf("h"), folderBar(all, currentId = "gone").map { it.id })
    }
}
