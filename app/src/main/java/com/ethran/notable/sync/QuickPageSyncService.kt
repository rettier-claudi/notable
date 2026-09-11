package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.deletePage
import com.ethran.notable.data.getDbDir
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.fold
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-way sync of quick pages (pages without a notebook) to `quickpages/` on the server.
 *
 * Up: every quick page that is new or edited since its last upload is PUT as `<pageId>.json`
 * (same page JSON as notebook pages) plus its images; a quick page deleted on the device is
 * DELETEd on the server. Down: **deletions only** -- a page file that vanished from the server
 * removes the page on the device, but only if the page has not been edited since its last upload
 * (then it is re-uploaded instead, so nothing written in the meantime is lost). Page content is
 * never downloaded: the server side (a script that ingests the pages) is a consumer, not an editor.
 *
 * Bookkeeping reuses `page_sync_state` rows under the pseudo notebook id [QUICK_PAGES_NOTEBOOK_ID];
 * a row means "this page was uploaded at that updatedAt", which is what both directions need.
 */
@Singleton
class QuickPageSyncService @Inject constructor(
    private val appRepository: AppRepository,
    @ApplicationContext private val context: Context,
) {
    private val log = SyncLogger

    suspend fun sync(
        client: WebDAVClient,
        uploadOnly: Boolean,
        /** From the preflight's root listing; the directory is created lazily on first upload. */
        dirExists: Boolean = false,
    ): AppResult<QuickPageSyncSummary, DomainError> {
        val dir = SyncPaths.quickPagesDir()
        val localPages = appRepository.pageRepository.getAllSinglePages()
        val rows = appRepository.pageSyncStateRepository.getByNotebook(QUICK_PAGES_NOTEBOOK_ID)
            .associateBy { it.pageId }
        // Nothing here and nothing ever uploaded: no request at all. Without a row, an absent
        // remote file means nothing, so there is also nothing the listing could tell us.
        if (localPages.isEmpty() && rows.isEmpty()) {
            return AppResult.Success(QuickPageSyncSummary(0, 0, 0))
        }
        // ALWAYS from a real listing. `null` means "we do not know what is on the server", and the
        // planner then refuses every local deletion. Assuming an empty server because the listing
        // was skipped or failed is how quick pages got deleted locally on 2026-09-11: absence of
        // knowledge must never read as absence of the file.
        val remoteNames: Set<String>? = client.listNames(dir).fold(
            onSuccess = { it.toSet() },
            onError = { error ->
                log.w(TAG, "Quick pages: listing failed, no deletions this round: ${error.userMessage}")
                null
            }
        )
        val plan = planQuickPageSync(localPages, rows, remoteNames)
        log.i(
            TAG,
            "Quick pages: ${localPages.size} local, ${remoteNames?.size ?: "?"} remote file(s), " +
                "${plan.upload.size} to upload, ${plan.deleteRemote.size} to delete on server, " +
                "${plan.deleteLocal.size} removed on server"
        )

        val errors = ErrorAccumulator()
        var uploaded = 0
        if (plan.upload.isNotEmpty() && !dirExists) {
            client.createCollection(dir).onError { return AppResult.Error(it) }
        }
        for (page in plan.upload) {
            uploadQuickPage(page, client).onSuccess {
                uploaded++
                appRepository.pageSyncStateRepository.upsertAll(
                    listOf(
                        PageSyncState(
                            pageId = page.id,
                            notebookId = QUICK_PAGES_NOTEBOOK_ID,
                            remoteEtag = null,
                            syncedLocalUpdatedAt = page.updatedAt,
                            lastSyncedAt = Date(),
                        )
                    )
                )
            }.onError { errors.add(it) }
        }

        var deletedRemote = 0
        for (pageId in plan.deleteRemote) {
            val path = SyncPaths.quickPageFile(pageId)
            // Listing unknown -> attempt the DELETE anyway (idempotent); dropping the row on a
            // guess would strand the file on the server forever.
            val gone = if (remoteNames != null && pageId.jsonName() !in remoteNames) {
                true
            } else {
                client.delete(path).onError { errors.add(it) } is AppResult.Success
            }
            if (gone) {
                deletedRemote++
                appRepository.pageSyncStateRepository.deleteByIds(listOf(pageId))
            }
        }

        var deletedLocal = 0
        if (!uploadOnly && plan.deleteLocal.isNotEmpty()) {
            if (looksLikeQuickPageWipe(plan.deleteLocal.size, rows.size)) {
                // The server appearing to have dropped most of what we uploaded is far more likely
                // to be our own misreading than an intentional bulk cleanup. Refuse and say so.
                log.e(
                    TAG,
                    "Refusing to delete ${plan.deleteLocal.size} of ${rows.size} quick pages: " +
                        "that looks like a misread listing, not a server-side cleanup."
                )
            } else {
                for (pageId in plan.deleteLocal) {
                    try {
                        // Deleting a note because a *remote* file vanished is the one destructive
                        // step here, so it is reversible: the page is written out first and can be
                        // put back by hand from quickpages-deleted/.
                        backupQuickPage(pageId)
                        deletePage(appRepository, pageId, context.filesDir)
                        appRepository.pageSyncStateRepository.deleteByIds(listOf(pageId))
                        deletedLocal++
                        log.i(TAG, "Quick page removed on server, deleted locally: $pageId")
                    } catch (e: Exception) {
                        errors.add(DomainError.DatabaseError("Failed to delete quick page $pageId: ${e.message}"))
                    }
                }
            }
        }

        return errors.asResult(QuickPageSyncSummary(uploaded, deletedRemote, deletedLocal))
    }

    private suspend fun uploadQuickPage(page: Page, client: WebDAVClient): AppResult<Unit, DomainError> {
        // A page row that disappeared between planning and upload (deleted while the sync ran) is
        // not an error -- skip it silently rather than failing the whole run with "not found".
        val data = appRepository.pageRepository.getWithDataById(page.id) ?: run {
            log.i(TAG, "Quick page ${page.id} vanished before upload, skipping")
            return AppResult.Success(Unit)
        }
        val json = NotebookSerializer.serializePage(page, data.strokes, data.images)
        client.putFile(SyncPaths.quickPageFile(page.id), json.toByteArray(), "application/json")
            .onError { return AppResult.Error(it) }
        val errors = ErrorAccumulator()
        for (image in data.images) {
            val uri = image.uri
            if (uri.isNullOrEmpty()) continue
            val localFile = if (uri.startsWith("file:")) File(android.net.Uri.parse(uri).path ?: uri) else File(uri)
            if (!localFile.exists()) continue
            val remote = SyncPaths.quickPageImageFile(localFile.name)
            if (!client.exists(remote).getOrElse { false }) {
                client.createCollection(SyncPaths.quickPagesImagesDir())
                client.putFile(remote, localFile, mimeType(localFile)).onError { errors.add(it) }
            }
        }
        log.i(TAG, "Uploaded quick page ${page.id}")
        return errors.asResult(Unit)
    }

    /** Write a page's JSON next to the database before it is deleted, so the delete is undoable. */
    private suspend fun backupQuickPage(pageId: String) {
        try {
            val data = appRepository.pageRepository.getWithDataById(pageId) ?: return
            val dir = File(getDbDir(), "quickpages-deleted")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "$pageId.json").writeText(
                NotebookSerializer.serializePage(data.page, data.strokes, data.images)
            )
        } catch (e: Exception) {
            log.w(TAG, "Could not back up quick page $pageId before deleting: ${e.message}")
        }
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "QuickPageSync"
        const val QUICK_PAGES_NOTEBOOK_ID = "__quickpages__"
        const val TOLERANCE_MS = 1000L
    }
}

data class QuickPageSyncSummary(val uploaded: Int, val deletedRemote: Int, val deletedLocal: Int)

internal data class QuickPageSyncPlan(
    val upload: List<Page>,
    /** Page ids deleted on the device that still have a row: remove the server file. */
    val deleteRemote: List<String>,
    /** Page ids whose server file vanished and that are unchanged locally: delete on the device. */
    val deleteLocal: List<String>,
)

internal fun String.jsonName() = "$this.json"

/**
 * At least this many local deletions, and more than half of everything we ever uploaded, is treated
 * as a misread listing rather than a real server-side cleanup. Mirrors the notebook side's
 * [looksLikeStaleStateWipe]; the ordinary "the bridge ingested one or two notes" case stays below it.
 */
internal fun looksLikeQuickPageWipe(deletionCount: Int, rowCount: Int): Boolean =
    deletionCount >= 3 && deletionCount > rowCount / 2

/**
 * Pure planning step (unit-tested): which pages go up, which rows are stale, which pages go, which
 * come back.
 *
 * [remoteNames] is null when the server listing is unknown (request failed). Local deletion is the
 * only irreversible outcome here, so it requires positive evidence on every count: a listing we
 * actually performed, a row proving the page once reached the server, and no local edit since.
 */
internal fun planQuickPageSync(
    localPages: List<Page>,
    rows: Map<String, PageSyncState>,
    remoteNames: Set<String>?,
): QuickPageSyncPlan {
    val upload = mutableListOf<Page>()
    val deleteLocal = mutableListOf<String>()
    for (page in localPages) {
        val row = rows[page.id]
        // No row means the page was never uploaded, so its absence on the server says nothing --
        // upload it. This is the case that must never turn into a deletion.
        val edited = row == null ||
            page.updatedAt.time - row.syncedLocalUpdatedAt.time > QuickPageSyncService.TOLERANCE_MS
        if (edited) {
            upload += page
        } else if (remoteNames != null && page.id.jsonName() !in remoteNames) {
            deleteLocal += page.id
        }
    }
    val localIds = localPages.mapTo(mutableSetOf()) { it.id }
    val deleteRemote = rows.keys.filter { it !in localIds }
    return QuickPageSyncPlan(upload, deleteRemote, deleteLocal)
}
