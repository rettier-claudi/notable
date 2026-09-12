package com.ethran.notable.sync

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncState
import com.ethran.notable.data.db.SyncStateValue

/**
 * Title of the folder that is synced in every round besides the root ("Ablage"). Matched by
 * title, not id, on purpose: the server side renames its day folders each night, so the folder
 * that is "Heute" today is a different id tomorrow.
 */
const val ALWAYS_SYNCED_FOLDER_TITLE = "Heute"

/**
 * Which notebooks a full sync round looks at on the server (one conditional manifest GET each).
 * Everything else in the round — folders.json, tombstones, new remote notebooks, local deletions,
 * quick pages — is scope-independent and costs a fixed handful of requests.
 *
 * - Default: notebooks in the root and in the folder titled [ALWAYS_SYNCED_FOLDER_TITLE].
 * - [folderId]: only that folder (the home screen's sync button inside a folder).
 * - [extraNotebookId]: always included (editor sync button, "Send").
 *
 * In every scope a notebook with local changes not yet on the server is included too: pushing
 * costs requests only when there is something to push, and a book moved out of "Heute" must still
 * get its move up. Notebooks outside the scope are never touched — a server-side change to one
 * arrives when its folder is synced (or, for a move into the root / "Heute", the moment its
 * folder turns into "Heute").
 */
data class SyncScope(
    val folderId: String? = null,
    val extraNotebookId: String? = null,
) {
    val isSingleFolder: Boolean get() = folderId != null

    fun describe(): String = buildString {
        append(if (folderId != null) "folder $folderId" else "root + \"$ALWAYS_SYNCED_FOLDER_TITLE\"")
        append(" + dirty")
        if (extraNotebookId != null) append(" + $extraNotebookId")
    }
}

/** Case- and whitespace-insensitive title match, since the title is typed by a person. */
internal fun folderTitleMatches(title: String, wanted: String): Boolean =
    title.trim().equals(wanted.trim(), ignoreCase = true)

/**
 * Whether [notebook] has something to push: never synced, last sync did not end in SYNCED, or
 * edited past the anchor. Pure, no I/O.
 */
internal fun isLocallyDirty(notebook: Notebook, row: NotebookSyncState?): Boolean {
    if (row == null) return true
    if (row.state != SyncStateValue.SYNCED) return true
    return notebook.updatedAt.time - row.syncedLocalUpdatedAt.time > NotebookSyncPlanner.TOLERANCE_MS
}

/**
 * The notebooks a round reconciles against the server, in the caller's order. Pure and
 * unit-tested; see [SyncScope] for the rules.
 */
internal fun selectNotebooksInScope(
    notebooks: List<Notebook>,
    folders: List<Folder>,
    scope: SyncScope,
    rows: Map<String, NotebookSyncState>,
): List<Notebook> {
    val alwaysFolderIds = folders
        .filter { folderTitleMatches(it.title, ALWAYS_SYNCED_FOLDER_TITLE) }
        .mapTo(HashSet()) { it.id }
    return notebooks.filter { notebook ->
        val inScope = when (val folderId = scope.folderId) {
            null -> notebook.parentFolderId == null || notebook.parentFolderId in alwaysFolderIds
            else -> notebook.parentFolderId == folderId
        }
        inScope || notebook.id == scope.extraNotebookId || isLocallyDirty(notebook, rows[notebook.id])
    }
}
