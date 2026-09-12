package com.ethran.notable.sync

/**
 * Centralized server path structure for WebDAV sync.
 * All server paths should be constructed here to prevent spelling mistakes
 * and make future structural changes easier.
 */
object SyncPaths {
    private const val ROOT = "notable"

    fun rootDir() = "/$ROOT"
    fun notebooksDir() = "/$ROOT/notebooks"
    fun tombstonesDir() = "/$ROOT/deletions"
    fun foldersFile() = "/$ROOT/folders.json"

    fun notebookDir(notebookId: String) = "/$ROOT/notebooks/$notebookId"
    fun manifestFile(notebookId: String) = "/$ROOT/notebooks/$notebookId/manifest.json"
    fun pagesDir(notebookId: String) = "/$ROOT/notebooks/$notebookId/pages"
    fun pageFile(notebookId: String, pageId: String) =
        "/$ROOT/notebooks/$notebookId/pages/$pageId.json"

    fun imagesDir(notebookId: String) = "/$ROOT/notebooks/$notebookId/images"
    fun imageFile(notebookId: String, imageName: String) =
        "/$ROOT/notebooks/$notebookId/images/$imageName"

    fun backgroundsDir(notebookId: String) = "/$ROOT/notebooks/$notebookId/backgrounds"
    fun backgroundFile(notebookId: String, bgName: String) =
        "/$ROOT/notebooks/$notebookId/backgrounds/$bgName"

    /**
     * Zero-byte tombstone file for a deleted notebook.
     * Presence of this file on the server means the notebook was deleted.
     * The server's own lastModified on the tombstone provides the deletion
     * timestamp needed for conflict resolution.
     */
    fun tombstone(notebookId: String) = "/$ROOT/deletions/$notebookId"

    /**
     * Zero-byte tombstone for a deleted *folder*: `deletions/folder-<folderId>`. Same directory,
     * same listing, same 90-day prune and the same resurrection rule as notebook tombstones —
     * distinguished only by the prefix, so a folder tombstone costs no extra request per round.
     */
    fun folderTombstone(folderId: String) = "/$ROOT/deletions/$FOLDER_TOMBSTONE_PREFIX$folderId"

    const val FOLDER_TOMBSTONE_PREFIX = "folder-"

    // Quick pages (single pages without a notebook): one flat directory of page files, uploaded
    // one-way; a page file removed on the server removes the page on the device.
    fun quickPagesDir() = "/$ROOT/quickpages"
    fun quickPageFile(pageId: String) = "/$ROOT/quickpages/$pageId.json"
    fun quickPagesImagesDir() = "/$ROOT/quickpages/images"
    fun quickPageImageFile(imageName: String) = "/$ROOT/quickpages/images/$imageName"
}
