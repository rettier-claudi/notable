package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.NotebookSyncState
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.db.SyncStateValue
import com.ethran.notable.di.ApplicationScope
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/*
 * "Sent" state after a successful "sync and notify" (toolbar button or gesture).
 *
 * - A **quick page** that was sent is locked for good: read-only on the device, no way back by
 *   editing (that is the point -- it went to the inbox, further scribbles would be lost).
 * - A **notebook** that was sent is only marked; the mark disappears with the next local change.
 *
 * Both are device state only. Nothing here touches Page.updatedAt / Notebook.updatedAt or any sync
 * row, so locking or marking is never an edit: it uploads nothing, and it cannot keep the
 * server-side consumer's nightly "delete the quick page file -> the device deletes the page" from
 * working (that deletion only asks "edited since the last upload?", which a lock does not change).
 */

private const val SENT_MARKS_KEY = "SENT_MARKS"

/** Same tolerance the sync badges use for "local edit newer than the last committed sync". */
private const val CONTENT_TOLERANCE_MS = 1000L

@Serializable
data class NotebookSentMark(
    /** Wall clock of the send. Informational. */
    val sentAt: Long,
    /** `Notebook.updatedAt` (epoch ms) of the state that was sent. The mark holds while the
     * notebook's current `updatedAt` is not later than this. */
    val anchorUpdatedAt: Long,
)

@Serializable
data class SentMarks(
    /** Quick pages locked by a successful send: pageId -> wall clock of the send. */
    val lockedPages: Map<String, Long> = emptyMap(),
    /** Notebooks marked as sent, see [NotebookSentMark]. */
    val sentNotebooks: Map<String, NotebookSentMark> = emptyMap(),
) {
    fun isPageLocked(pageId: String?): Boolean = pageId != null && pageId in lockedPages

    fun isEmpty(): Boolean = lockedPages.isEmpty() && sentNotebooks.isEmpty()

    /** A notebook shows the mark while nothing was changed locally since it was sent. */
    fun isNotebookMarked(notebookId: String, notebookUpdatedAt: Long?): Boolean {
        val mark = sentNotebooks[notebookId] ?: return false
        return notebookUpdatedAt != null && notebookUpdatedAt <= mark.anchorUpdatedAt
    }

    fun withPageLocked(pageId: String, at: Long): SentMarks =
        copy(lockedPages = lockedPages + (pageId to at))

    fun withNotebookMarked(notebookId: String, at: Long, anchorUpdatedAt: Long): SentMarks =
        copy(sentNotebooks = sentNotebooks + (notebookId to NotebookSentMark(at, anchorUpdatedAt)))

    /**
     * A download is about to replace the notebook with the server's copy (timestamp
     * [newUpdatedAt]). That is not a change *made here*, so a mark that still held before the
     * download keeps holding afterwards. A mark that was already void stays void.
     */
    fun rebasedForDownload(notebookId: String, localUpdatedAtBefore: Long?, newUpdatedAt: Long): SentMarks {
        val mark = sentNotebooks[notebookId] ?: return this
        if (localUpdatedAtBefore == null || localUpdatedAtBefore > mark.anchorUpdatedAt) return this
        if (newUpdatedAt <= mark.anchorUpdatedAt) return this
        return copy(sentNotebooks = sentNotebooks + (notebookId to mark.copy(anchorUpdatedAt = newUpdatedAt)))
    }

    /**
     * Drop what no longer applies: locks of quick pages that are gone (deleted here, or deleted
     * because the server-side consumer removed their file) and notebook marks that were voided by
     * a local change or whose notebook is gone. Once dropped, a mark never comes back, even if a
     * later write (a force download) moved the notebook's timestamp back.
     */
    fun pruned(existingQuickPageIds: Set<String>, notebookUpdatedAts: Map<String, Long>): SentMarks {
        val pages = lockedPages.filterKeys { it in existingQuickPageIds }
        val books = sentNotebooks.filter { (id, mark) ->
            val updatedAt = notebookUpdatedAts[id]
            updatedAt != null && updatedAt <= mark.anchorUpdatedAt
        }
        return if (pages.size == lockedPages.size && books.size == sentNotebooks.size) this
        else SentMarks(pages, books)
    }
}

/** What a finished send leaves behind. */
enum class SendOutcome { LOCK_QUICK_PAGE, MARK_NOTEBOOK, NOTHING }

/**
 * Only a send whose content is confirmed on the server *and* whose notification was accepted
 * (2xx) counts. Anything else leaves no lock and no mark: otherwise a page would read as "sent"
 * that never arrived, and a locked page could not even be fixed by writing on it.
 */
fun sendOutcome(isQuickPage: Boolean, contentOnServer: Boolean, webhookStatus: Int?): SendOutcome {
    val delivered = contentOnServer && webhookStatus != null && webhookStatus in 200..299
    return when {
        !delivered -> SendOutcome.NOTHING
        isQuickPage -> SendOutcome.LOCK_QUICK_PAGE
        else -> SendOutcome.MARK_NOTEBOOK
    }
}

/**
 * Is the quick page's current content on the server? True when the quick-page sync recorded an
 * upload of this page and the page was not edited after it.
 */
fun quickPageContentOnServer(pageUpdatedAt: Long?, row: PageSyncState?): Boolean =
    pageUpdatedAt != null && row != null &&
        row.notebookId == QuickPageSyncService.QUICK_PAGES_NOTEBOOK_ID &&
        pageUpdatedAt - row.syncedLocalUpdatedAt.time <= CONTENT_TOLERANCE_MS

/**
 * Is the notebook's current content on the server? True when its last committed sync is SYNCED
 * (not an error, conflict or skipped remote change) and nothing was edited after that commit --
 * the same rule as the library's "synced" badge.
 */
fun notebookContentOnServer(notebookUpdatedAt: Long?, row: NotebookSyncState?): Boolean =
    notebookUpdatedAt != null && row != null &&
        row.state == SyncStateValue.SYNCED &&
        notebookUpdatedAt - row.syncedLocalUpdatedAt.time <= CONTENT_TOLERANCE_MS

/**
 * Holds [SentMarks] in memory (synchronous reads for the editor's input guard) and persists them in
 * the app's key-value table, i.e. in `Documents/notabledb` next to the notes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SentMarkStore @Inject constructor(
    private val kvProxy: KvProxy,
    private val appRepository: AppRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val log = ShipBook.getLogger("SentMarkStore")
    private val _marks = MutableStateFlow(SentMarks())
    val marks: StateFlow<SentMarks> = _marks.asStateFlow()

    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    init {
        appScope.launch {
            ensureLoaded()
            // Housekeeping in one place: whenever notebooks or quick pages change, drop what no
            // longer applies. This is also what makes a notebook's mark vanish on the first stroke.
            // Only watched while there is something to watch.
            marks.map { it.isEmpty() }.distinctUntilChanged().flatMapLatest { empty ->
                if (empty) emptyFlow()
                else combine(
                    appRepository.pageRepository.getAllSinglePagesFlow(),
                    appRepository.bookRepository.getAllFlow(),
                ) { pages, books ->
                    pages.mapTo(HashSet()) { it.id } to books.associate { it.id to it.updatedAt.time }
                }
            }.catch { log.w("Sent-mark housekeeping stopped: ${it.message}") }
                .collect { (pageIds, notebookUpdatedAts) ->
                    update { it.pruned(pageIds, notebookUpdatedAts) }
                }
        }
    }

    /** Loads the persisted marks once; later calls return immediately. */
    suspend fun ensureLoaded() {
        if (loaded.isCompleted) return
        mutex.withLock {
            if (loaded.isCompleted) return
            try {
                _marks.value = kvProxy.getOrDefault(SENT_MARKS_KEY, SentMarks.serializer(), SentMarks())
                loaded.complete(Unit)
            } catch (e: Exception) {
                // No storage access yet (permission). Leave it unloaded so the next call retries,
                // and never persist over marks we could not read.
                log.w("Could not load sent marks: ${e.message}")
            }
        }
    }

    fun isPageLocked(pageId: String?): Boolean = _marks.value.isPageLocked(pageId)

    suspend fun lockQuickPage(pageId: String) = update { it.withPageLocked(pageId, System.currentTimeMillis()) }

    suspend fun markNotebook(notebookId: String, anchorUpdatedAt: Long) =
        update { it.withNotebookMarked(notebookId, System.currentTimeMillis(), anchorUpdatedAt) }

    suspend fun rebaseForDownload(notebookId: String, localUpdatedAtBefore: Long?, newUpdatedAt: Long) =
        update { it.rebasedForDownload(notebookId, localUpdatedAtBefore, newUpdatedAt) }

    private suspend fun update(transform: (SentMarks) -> SentMarks) {
        ensureLoaded()
        if (!loaded.isCompleted) return
        mutex.withLock {
            val before = _marks.value
            val after = transform(before)
            if (after == before) return
            _marks.value = after
            try {
                kvProxy.setKv(SENT_MARKS_KEY, after, SentMarks.serializer())
            } catch (e: Exception) {
                log.e("Could not persist sent marks: ${e.message}")
            }
        }
    }
}
