package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneNameTest {
    private val uuid = "6baf4c2e-b744-4058-a853-7ace96ae479a"

    @Test
    fun notebook_and_folder_tombstones_are_listed_nothing_else() {
        assertTrue(WebDavXml.isTombstoneName(uuid))
        assertTrue(WebDavXml.isTombstoneName("folder-$uuid"))
        assertFalse(WebDavXml.isTombstoneName("folder-"))
        assertFalse(WebDavXml.isTombstoneName("folder-nope"))
        assertFalse(WebDavXml.isTombstoneName("manifest.json.tmp"))
        assertFalse(WebDavXml.isTombstoneName(""))
    }

    @Test
    fun folder_tombstone_path_carries_the_prefix() {
        assertEquals("/notable/deletions/folder-$uuid", SyncPaths.folderTombstone(uuid))
        assertEquals(uuid, "folder-$uuid".removePrefix(SyncPaths.FOLDER_TOMBSTONE_PREFIX))
    }
}
