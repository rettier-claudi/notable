package com.ethran.notable.sync

import com.ethran.notable.data.db.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FolderMergeTest {

    private fun folder(id: String, title: String, updatedAt: Long) =
        Folder(id = id, title = title, createdAt = Date(1_000), updatedAt = Date(updatedAt))

    @Test
    fun server_rename_with_newer_timestamp_wins_and_touches_nothing_else() {
        // The nightly rotation: same ids, new titles, newer updatedAt. No notebook is involved --
        // the merge only ever yields folders, so nothing can be re-downloaded or re-uploaded.
        val local = listOf(folder("a", "Heute", 10_000), folder("b", "Gestern", 10_000))
        val remote = listOf(folder("a", "Gestern", 20_000), folder("b", "14.04.2026", 20_000))
        val merge = mergeFolders(local, remote, emptyMap())
        assertEquals(mapOf("a" to "Gestern", "b" to "14.04.2026"), merge.merged.associate { it.id to it.title })
        assertTrue(merge.deleteLocally.isEmpty())
        assertTrue(merge.resurrected.isEmpty())
    }

    @Test
    fun local_rename_with_newer_timestamp_wins() {
        val local = listOf(folder("a", "Rezepte", 30_000))
        val remote = listOf(folder("a", "New Folder", 20_000))
        assertEquals("Rezepte", mergeFolders(local, remote, emptyMap()).merged.single().title)
    }

    @Test
    fun local_rename_without_newer_timestamp_loses_to_the_server() {
        // Why FolderRepository.rename stamps updatedAt: an equal timestamp is not "newer".
        val local = listOf(folder("a", "Rezepte", 20_000))
        val remote = listOf(folder("a", "New Folder", 20_000))
        assertEquals("New Folder", mergeFolders(local, remote, emptyMap()).merged.single().title)
    }

    @Test
    fun folders_known_to_one_side_only_are_kept() {
        val local = listOf(folder("mine", "Mine", 5_000))
        val remote = listOf(folder("theirs", "Theirs", 5_000))
        assertEquals(setOf("mine", "theirs"), mergeFolders(local, remote, emptyMap()).merged.map { it.id }.toSet())
    }

    @Test
    fun a_tombstoned_folder_is_dropped_from_both_sides_and_deleted_locally() {
        val local = listOf(folder("old", "07.09.2026", 5_000), folder("keep", "Heute", 5_000))
        val remote = listOf(folder("keep", "Heute", 5_000))
        val merge = mergeFolders(local, remote, mapOf("old" to Date(9_000)))
        assertEquals(listOf("keep"), merge.merged.map { it.id })
        assertEquals(listOf("old"), merge.deleteLocally.map { it.id })
        assertTrue(merge.resurrected.isEmpty())
    }

    @Test
    fun a_tombstone_also_removes_a_folder_the_server_still_lists() {
        // The tombstone was written first and folders.json not yet rewritten (or a lost write put
        // the folder back): the tombstone decides, so the app does not resurrect it.
        val remote = listOf(folder("old", "07.09.2026", 5_000))
        val merge = mergeFolders(emptyList(), remote, mapOf("old" to Date(9_000)))
        assertTrue(merge.merged.isEmpty())
        assertTrue(merge.deleteLocally.isEmpty())
    }

    @Test
    fun a_folder_changed_after_its_tombstone_is_resurrected() {
        val local = listOf(folder("f", "Renamed here", 12_000))
        val merge = mergeFolders(local, emptyList(), mapOf("f" to Date(9_000)))
        assertEquals(listOf("f"), merge.merged.map { it.id })
        assertEquals(listOf("f"), merge.resurrected)
        assertTrue(merge.deleteLocally.isEmpty())
    }

    @Test
    fun an_undated_tombstone_always_wins() {
        val local = listOf(folder("f", "x", 12_000))
        val merge = mergeFolders(local, emptyList(), mapOf("f" to null))
        assertTrue(merge.merged.isEmpty())
        assertEquals(listOf("f"), merge.deleteLocally.map { it.id })
    }

    @Test
    fun a_tombstone_for_an_unknown_folder_is_harmless() {
        val merge = mergeFolders(emptyList(), emptyList(), mapOf("ghost" to Date(1)))
        assertTrue(merge.merged.isEmpty() && merge.deleteLocally.isEmpty() && merge.resurrected.isEmpty())
    }
}
