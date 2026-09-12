package com.ethran.notable.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Send" = the toolbar's "sync and notify" (and the gesture of the same name): run a full sync until
 * the page's current content is confirmed on the server, then POST a small JSON to
 * [SyncSettings.syncWebhookUrl] so a server-side consumer can pick the fresh page up immediately
 * instead of on its own schedule. If both worked, a quick page is locked and a notebook marked as
 * sent ([SentMarkStore]). One at a time; the toolbar button shows [pending] while it runs.
 */
@Singleton
class SyncWebhookNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kvProxy: KvProxy,
    private val syncScheduler: SyncScheduler,
    private val snackDispatcher: SnackDispatcher,
    private val appRepository: AppRepository,
    private val sentMarkStore: SentMarkStore,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Starts a send in the background. Returns false (and does nothing) while another send is
     * still running.
     */
    fun syncThenNotify(pageId: String?, notebookId: String?): Boolean {
        if (!_pending.compareAndSet(expect = false, update = true)) return false
        appScope.launch {
            try {
                val settings = kvProxy.getSyncSettings()
                val url = settings.syncWebhookUrl.trim()
                if (url.isBlank()) {
                    snack("No webhook URL configured (Settings > Sync)")
                    return@launch
                }
                val sync = syncUntilOnServer(pageId, notebookId)
                if (!sync.finished) Log.w(TAG, "Sync did not finish within ${SYNC_WAIT_MS} ms, notifying anyway")

                val body = JSONObject().apply {
                    put("source", "notable")
                    put("event", "sync-and-notify")
                    put("pageId", pageId ?: JSONObject.NULL)
                    put("notebookId", notebookId ?: JSONObject.NULL)
                    put("syncSucceeded", sync.contentOnServer)
                    put("time", iso(Date()))
                }.toString()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        http.newCall(
                            Request.Builder().url(url)
                                .post(body.toRequestBody("application/json".toMediaType()))
                                .build()
                        ).execute().use { it.code }
                    }
                }
                val status = result.getOrNull()
                val outcome = if (pageId == null) SendOutcome.NOTHING
                else sendOutcome(isQuickPage = notebookId == null, sync.contentOnServer, status)
                when (outcome) {
                    SendOutcome.LOCK_QUICK_PAGE -> sentMarkStore.lockQuickPage(pageId!!)
                    SendOutcome.MARK_NOTEBOOK -> sync.notebookUpdatedAt?.let {
                        sentMarkStore.markNotebook(notebookId!!, it)
                    }
                    SendOutcome.NOTHING -> {}
                }
                result.onSuccess { code ->
                    snack(
                        when {
                            code !in 200..299 -> "Webhook answered $code"
                            outcome == SendOutcome.LOCK_QUICK_PAGE -> "Sent. Page locked."
                            outcome == SendOutcome.MARK_NOTEBOOK -> "Sent. Notebook marked."
                            else -> "Notified, but the sync did not confirm this page on the server"
                        }
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Webhook POST failed: ${e.message}")
                    snack("Webhook failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync and notify failed: ${e.message}")
                snack("Sync and notify failed: ${e.message}")
            } finally {
                _pending.value = false
            }
        }
        return true
    }

    private data class SyncOutcome(
        /** The last sync request finished (did not hit [SYNC_WAIT_MS]). */
        val finished: Boolean,
        /** The page's / notebook's current content is confirmed on the server. */
        val contentOnServer: Boolean,
        /** Notebook timestamp the confirmation refers to (the anchor of the "sent" mark). */
        val notebookUpdatedAt: Long?,
    )

    /**
     * Full sync, repeated a few times if it did not get the content up: a request folded into a run
     * that started before the last strokes (KEEP policy), or one that bounced off a sync already
     * holding the engine's lock ("in progress", e.g. the sync-on-close of the editor that is being
     * left), finishes "successfully" without this page. A hard failure (auth, network, config) is
     * not retried here -- another round would fail the same way.
     */
    private suspend fun syncUntilOnServer(pageId: String?, notebookId: String?): SyncOutcome {
        // The notebook is named so a round covers it even when it sits in a folder that the
        // standard scope leaves alone (see SyncScope).
        val request = SyncRequest.SyncAll(notebookId = notebookId)
        var last = SyncOutcome(finished = false, contentOnServer = false, notebookUpdatedAt = null)
        repeat(MAX_SYNC_ROUNDS) { round ->
            syncScheduler.triggerImmediateSyncAndAwait(request)
            val infos = withTimeoutOrNull(SYNC_WAIT_MS) {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(syncScheduler.uniqueNameFor(request))
                    .first { infos -> infos.all { it.state.isFinished } }
            } ?: return last.copy(finished = false)
            val workSucceeded = infos.any {
                it.state == WorkInfo.State.SUCCEEDED &&
                    it.outputData.getBoolean(SyncWorker.OUTPUT_KEY_SUCCESS, false)
            }
            last = checkOnServer(pageId, notebookId, workSucceeded)
            if (last.contentOnServer) return last
            val collided = infos.any {
                it.outputData.getString(SyncWorker.OUTPUT_KEY_ERROR) ==
                    com.ethran.notable.utils.DomainError.SyncInProgress.javaClass.simpleName
            }
            if (!collided && !workSucceeded) return last
            Log.i(TAG, "Send: content not on the server after round ${round + 1} (collided=$collided), retrying")
            delay(RETRY_DELAY_MS)
        }
        return last
    }

    private suspend fun checkOnServer(pageId: String?, notebookId: String?, workSucceeded: Boolean): SyncOutcome {
        if (pageId == null) return SyncOutcome(true, workSucceeded, null)
        return if (notebookId == null) {
            val page = appRepository.pageRepository.getById(pageId)
            val row = appRepository.pageSyncStateRepository
                .getByNotebook(QuickPageSyncService.QUICK_PAGES_NOTEBOOK_ID)
                .firstOrNull { it.pageId == pageId }
            SyncOutcome(true, quickPageContentOnServer(page?.updatedAt?.time, row), null)
        } else {
            val book = appRepository.bookRepository.getById(notebookId)
            val row = appRepository.notebookSyncStateRepository.get(notebookId)
            val updatedAt = book?.updatedAt?.time
            SyncOutcome(true, notebookContentOnServer(updatedAt, row), updatedAt)
        }
    }

    private fun snack(text: String) =
        snackDispatcher.showOrUpdateSnack(SnackConf(text = text, duration = 4000))

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    companion object {
        private const val TAG = "SyncWebhookNotifier"
        private const val SYNC_WAIT_MS = 120_000L
        private const val MAX_SYNC_ROUNDS = 3
        private const val RETRY_DELAY_MS = 3_000L
    }
}
