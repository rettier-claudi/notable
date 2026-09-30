package com.ethran.notable.data

import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.NOTEBOOK_KIND_SCRATCH
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Fork: a scratch note with more than one page (v0.2.6-claudi.24). Philipp, 2026-09-30: "dass
 * Schmierzettel auch mehrere Seiten haben können" -- he wrote one train of thought over two
 * scratch notes and got two separate receipts.
 *
 * "Next page" on a quick page turns it into a notebook with `kind = scratch` (the kind the server
 * side already uses for its scratch-note copies, so the home screen shows it in the *Scratch notes*
 * row) and adds an empty page after it. The quick page keeps its id and becomes page 1: the server
 * side recognises the note by that id (receipt name, PDF), so its first receipt and its PDF carry on.
 *
 * Its old `quickpages/<id>.json` has to go from the server, but only once the notebook with that
 * page is up -- in between the server side would take the vanished file for a note deleted on the
 * device and withdraw what it had read from it. The quick-page sync does it ([MovedQuickPages],
 * [planMovedQuickPageRemovals]); the notebook sync runs first in a full round, so it is one round.
 */

/** The notebook a quick page becomes when a page is added to it (pure, unit-tested). */
internal fun scratchNotebookFor(page: Page, notebookId: String, now: Date): Notebook =
    Notebook(
        id = notebookId,
        title = "Scratch note " + SimpleDateFormat("HH:mm", Locale.ROOT).format(page.createdAt),
        openPageId = page.id,
        pageIds = listOf(page.id),
        parentFolderId = page.parentFolderId,
        defaultBackground = page.background,
        defaultBackgroundType = page.backgroundType,
        createdAt = now,
        updatedAt = now,
        kind = NOTEBOOK_KIND_SCRATCH,
    )

/** Result of [AppRepository.addPageToQuickPage]. */
data class ScratchNoteGrowth(val notebookId: String, val newPageId: String)

/** What the quick-page sync does this round with the quick pages that moved into a notebook. */
internal data class MovedQuickPagePlan(
    /** Server files `quickpages/<id>.json` to delete now. */
    val deleteRemote: List<String>,
    /** Ids that need no more attention (deleted, or nothing on the server to delete). */
    val forget: List<String>,
)

/**
 * For each moved quick page: its page row ([pages], null = deleted on the device) and its sync row
 * ([rows]). The server file goes once the page's notebook has uploaded it (a sync row under that
 * notebook) -- or at once, if the page is gone from the device altogether. A page that is a quick
 * page again needs nothing: the quick-page sync treats it as one. [remoteNames] null = listing
 * unknown: nothing is deleted, nothing forgotten.
 */
internal fun planMovedQuickPageRemovals(
    moved: List<String>,
    pages: Map<String, Page>,
    rows: Map<String, PageSyncState>,
    remoteNames: Set<String>?,
): MovedQuickPagePlan {
    val delete = mutableListOf<String>()
    val forget = mutableListOf<String>()
    for (id in moved) {
        val page = pages[id]
        val notebookId = page?.notebookId
        when {
            page != null && notebookId == null -> forget += id
            page != null && rows[id]?.notebookId != notebookId -> Unit // notebook not up yet: wait
            remoteNames == null -> Unit
            "$id.json" in remoteNames -> delete += id
            else -> forget += id
        }
    }
    return MovedQuickPagePlan(delete, forget)
}

/** The ids of quick pages that moved into a notebook and whose server file is still to be deleted. */
@Singleton
class MovedQuickPages @Inject constructor(private val kvProxy: KvProxy) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(String.serializer())

    suspend fun get(): List<String> = kvProxy.getOrDefault(KEY, serializer, emptyList())

    suspend fun add(pageId: String) = mutex.withLock {
        val now = get()
        if (pageId !in now) kvProxy.setKv(KEY, now + pageId, serializer)
    }

    suspend fun remove(pageIds: Collection<String>) = mutex.withLock {
        if (pageIds.isEmpty()) return@withLock
        val now = get()
        val left = now.filterNot { it in pageIds }
        if (left.size != now.size) kvProxy.setKv(KEY, left, serializer)
    }

    private companion object {
        const val KEY = "MOVED_QUICK_PAGES"
    }
}
