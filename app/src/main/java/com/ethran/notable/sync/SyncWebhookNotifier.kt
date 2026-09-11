package com.ethran.notable.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * The toolbar's "sync and notify": start a full sync, wait for that WorkManager request to
 * finish, then POST a small JSON to [SyncSettings.syncWebhookUrl] so a server-side consumer can
 * pick the fresh pages up immediately instead of on its own schedule. One at a time; the
 * toolbar button shows [pending] while it runs.
 */
@Singleton
class SyncWebhookNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kvProxy: KvProxy,
    private val syncScheduler: SyncScheduler,
    private val snackDispatcher: SnackDispatcher,
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

    fun syncThenNotify(pageId: String?, notebookId: String?) {
        if (!_pending.compareAndSet(expect = false, update = true)) return
        appScope.launch {
            try {
                val settings = kvProxy.getSyncSettings()
                val url = settings.syncWebhookUrl.trim()
                if (url.isBlank()) {
                    snack("No webhook URL configured (Settings > Sync)")
                    return@launch
                }
                val request = SyncRequest.SyncAll
                syncScheduler.triggerImmediateSync(request)
                // KEEP policy: if a sync was already running the new request was dropped, so wait
                // on the unique work name rather than a request id. Finished = no running/enqueued.
                val finished = withTimeoutOrNull(SYNC_WAIT_MS) {
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWorkFlow(syncScheduler.uniqueNameFor(request))
                        .first { infos -> infos.all { it.state.isFinished } }
                }
                val syncOk = finished?.any { it.state == WorkInfo.State.SUCCEEDED } == true
                if (finished == null) Log.w(TAG, "Sync did not finish within ${SYNC_WAIT_MS} ms, notifying anyway")

                val body = JSONObject().apply {
                    put("source", "notable")
                    put("event", "sync-and-notify")
                    put("pageId", pageId ?: JSONObject.NULL)
                    put("notebookId", notebookId ?: JSONObject.NULL)
                    put("syncSucceeded", syncOk)
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
                result.onSuccess { code ->
                    if (code in 200..299) snack("Synced and notified") else snack("Webhook answered $code")
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
    }

    private fun snack(text: String) =
        snackDispatcher.showOrUpdateSnack(SnackConf(text = text, duration = 4000))

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    companion object {
        private const val TAG = "SyncWebhookNotifier"
        private const val SYNC_WAIT_MS = 120_000L
    }
}
