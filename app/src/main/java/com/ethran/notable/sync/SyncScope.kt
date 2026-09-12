package com.ethran.notable.sync

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncState
import com.ethran.notable.data.db.SyncStateValue

/**
 * Title of the root folder that is synced in every round besides the root ("Workspace"), with
 * all of its subfolders. Matched by title, not id, on purpose: the server side renames its day
 * folders each night, so the folder that is "Today" today is a different id tomorrow.
 */
const val ALWAYS_SYNCED_FOLDER_TITLE = "Today"

/**
 * Titles accepted for the always-synced folder: the current name plus the old German one, so a
 * device and a server side that renamed at different times keep syncing during the transition.
 */
val ALWAYS_SYNCED_FOLDER_TITLES: List<String> = listOf(ALWAYS_SYNCED_FOLDER_TITLE, "Heute")

/**
 * Which notebooks a full sync round looks at on the server (one conditional manifest GET each).
 * Everything else in the round — folders.json, tombstones, new remote notebooks, local deletions,
 * scratch notes — is scope-independent and costs a fixed handful of requests.
 *
 * - Default: notebooks in the root and in the *root* folder titled [ALWAYS_SYNCED_FOLDER_TITLE]
 *   (or one of [ALWAYS_SYNCED_FOLDER_TITLES]), including every subfolder below it — the server
 *   side files finished scratch notes and deleted notebooks into `Today/Scratch notes` and
 *   `Today/Notebooks`.
 * - [folderId]: that folder and its subfolders (the home screen's sync button inside a folder).
 * - [extraNotebookId]: always included (editor sync button, "Send").
 *
 * In every scope a notebook with local changes not yet on the server is included too: pushing
 * costs requests only when there is something to push, and a book moved out of "Today" must still
 * get its move up. Notebooks outside the scope are never touched — a server-side change to one
 * arrives when its folder is synced (or, for a move into the root / "Today", the moment its
 * folder turns into "Today").
 */
data class SyncScope(
    val folderId: String? = null,
    val extraNotebookId: String? = null,
) {
    val isSingleFolder: Boolean get() = folderId != null

    fun describe(): String = buildString {
        append(if (folderId != null) "folder $folderId + subfolders" else "root + \"$ALWAYS_SYNCED_FOLDER_TITLE\" + subfolders")
        append(" + dirty")
        if (extraNotebookId != null) append(" + $extraNotebookId")
    }
}

/** Case- and whitespace-insensitive title match, since the title is typed by a person. */
internal fun folderTitleMatches(title: String, wanted: String): Boolean =
    title.trim().equals(wanted.trim(), ignoreCase = true)

/** Whether [title] names the always-synced folder under any of its accepted spellings. */
internal fun isAlwaysSyncedTitle(title: String): Boolean =
    ALWAYS_SYNCED_FOLDER_TITLES.any { folderTitleMatches(title, it) }

/**
 * The ids of [roots] plus every folder below them, however deep. Pure; a cycle in the data
 * (impossible through the app, but folders.json is written by another side) terminates because
 * every id is visited once.
 */
internal fun folderSubtreeIds(folders: List<Folder>, roots: Collection<String>): Set<String> {
    val childrenByParent = folders.filter { it.parentFolderId != null }.groupBy { it.parentFolderId!! }
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        childrenByParent[id]?.forEach { queue.addLast(it.id) }
    }
    return seen
}

/**
 * Ids of the root folders titled "Today" (or an alias) and all their subfolders — the folder part
 * of the default scope. Only *root* folders are matched by title: a subfolder happening to be
 * called "Today" inside a day folder must not pull that day folder's contents into every round.
 */
internal fun alwaysSyncedFolderIds(folders: List<Folder>): Set<String> {
    val roots = folders.filter { it.parentFolderId == null && isAlwaysSyncedTitle(it.title) }.map { it.id }
    return folderSubtreeIds(folders, roots)
}

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
    val folderIds: Set<String> = when (val folderId = scope.folderId) {
        null -> alwaysSyncedFolderIds(folders)
        else -> folderSubtreeIds(folders, listOf(folderId))
    }
    val includeRoot = scope.folderId == null
    return notebooks.filter { notebook ->
        val parent = notebook.parentFolderId
        val inScope = (includeRoot && parent == null) || (parent != null && parent in folderIds)
        inScope || notebook.id == scope.extraNotebookId || isLocallyDirty(notebook, rows[notebook.id])
    }
}
