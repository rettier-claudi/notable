package com.ethran.notable.io

import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.utils.PreviewSaveMode
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A background worker queue for generating page thumbnails.
 *
 * Processes page IDs sequentially. [enqueue] (backfill of missing thumbnails) reports progress via
 * global snackbars; [refresh] (re-render a thumbnail that may be out of date) is silent, since it
 * runs every time a page is left or a preview is shown and almost always finds nothing to do.
 * Uses a [Mutex] for thread-safe state management across coroutines.
 */
@Singleton
class ThumbnailBackfillQueue @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val appEventBus: AppEventBus
) {
    private val log = ShipBook.getLogger("ThumbnailBackfillQueue")

    private class Request(val pageId: String, val mode: PreviewSaveMode, val quiet: Boolean)

    private val queue = Channel<Request>(Channel.UNLIMITED)

    private val mutex = Mutex()
    private val queuedPageIds = linkedSetOf<String>()
    private val queuedRefreshIds = hashSetOf<String>()

    private var isCycleActive = false
    private var cycleTotal = 0
    private var cycleDone = 0

    private var lastUpdateMs = 0L

    init {
        // listen for thumbnail generation requests
        applicationScope.launch(ioDispatcher) {
            for (request in queue) {
                if (request.quiet) processRefresh(request.pageId, request.mode)
                else processOne(request.pageId, request.mode)
            }
        }
        // A download replaced the page's content (NotebookSyncService has already dropped the old
        // thumbnail file); render the new one so a preview on screen updates.
        applicationScope.launch {
            appEventBus.events.collect { event ->
                if (event is AppEvent.PageDownloaded) refresh(event.pageId)
            }
        }
    }

    /**
     * Re-renders [pageId]'s thumbnail if it is missing or older than the page's last edit, without
     * any snackbar. Skipped when the page is already waiting in the queue either way.
     */
    fun refresh(pageId: String, mode: PreviewSaveMode = PreviewSaveMode.REGULAR) {
        if (pageId.isBlank()) return
        applicationScope.launch(ioDispatcher) {
            val added = mutex.withLock {
                pageId !in queuedPageIds && queuedRefreshIds.add(pageId)
            }
            if (added && queue.trySend(Request(pageId, mode, quiet = true)).isFailure) {
                mutex.withLock { queuedRefreshIds.remove(pageId) }
                log.w("Failed to enqueue thumbnail refresh pageId=$pageId")
            }
        }
    }

    /**
     * Enqueues a list of [pageIds] for thumbnail generation.
     */
    fun enqueue(pageIds: List<String>, mode: PreviewSaveMode = PreviewSaveMode.REGULAR) {
        if (pageIds.isEmpty()) return

        applicationScope.launch(ioDispatcher) {
            val added = mutableListOf<String>()
            mutex.withLock {
                for (pageId in pageIds) {
                    if (pageId.isBlank()) continue
                    if (queuedPageIds.add(pageId)) {
                        added += pageId
                    }
                }

                if (added.isNotEmpty()) {
                    if (!isCycleActive) {
                        isCycleActive = true
                        cycleDone = 0
                        cycleTotal = added.size
                    } else {
                        cycleTotal += added.size
                    }
                    updateProgressLocked()
                }
            }

            added.forEach { pageId ->
                val sent = queue.trySend(Request(pageId, mode, quiet = false))
                if (sent.isFailure) {
                    mutex.withLock {
                        queuedPageIds.remove(pageId)
                    }
                    log.w("Failed to enqueue thumbnail pageId=$pageId")
                }
            }
        }
    }

    private suspend fun processOne(pageId: String, mode: PreviewSaveMode) {
        try {
            thumbnailGenerator.ensureThumbnail(pageId, mode)
        } catch (t: Throwable) {
            // Log the throwable (not just t.message) so the throw site is visible in ShipBook.
            log.e("Thumbnail generation failed for pageId=$pageId", t)
        } finally {
            mutex.withLock {
                queuedPageIds.remove(pageId)
                cycleDone += 1

                if (queuedPageIds.isEmpty()) {
                    finalizeCycleLocked()
                } else {
                    updateProgressLocked(throttled = true)
                }
            }
        }
    }

    private suspend fun processRefresh(pageId: String, mode: PreviewSaveMode) {
        // Leave the set first: an edit made while this render runs must be able to queue another.
        mutex.withLock { queuedRefreshIds.remove(pageId) }
        try {
            thumbnailGenerator.ensureThumbnail(pageId, mode)
        } catch (t: Throwable) {
            log.e("Thumbnail refresh failed for pageId=$pageId", t)
        }
    }

    private fun updateProgressLocked(throttled: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttled && now - lastUpdateMs < 300) return

        lastUpdateMs = now
        appEventBus.tryEmit(
            AppEvent.PreviewBackfillProgress(
                current = cycleDone,
                total = cycleTotal
            )
        )
    }

    private fun finalizeCycleLocked() {
        isCycleActive = false
        val done = cycleDone
        val total = cycleTotal
        cycleDone = 0
        cycleTotal = 0

        // Use a small delay to ensure the user sees the 100% state or "Done" state
        applicationScope.launch {
            delay(100)
            appEventBus.tryEmit(AppEvent.PreviewBackfillCompleted(current = done, total = total))
        }
    }
}
