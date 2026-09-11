package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.data.db.KvProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the device close to the server while the app is actually in use, without touching the
 * radio when it is not:
 *
 *  - [requestSync] is the single funnel for "the user is here, look at the server now" triggers
 *    (app start, activity resume after sleep, the foreground poll). It rate-limits them to one
 *    WorkManager request per [MIN_GAP_MS] and skips them entirely without a network, so a wake-up
 *    storm (quick settings, dialogs, screen on/off) costs at most one sync.
 *  - [pollWhileResumed] runs only while the activity is RESUMED (the caller scopes it with
 *    `repeatOnLifecycle`), so nothing runs while the tablet sleeps; the WorkManager periodic job
 *    (>= 15 min) stays the background fallback.
 *
 * Manual "Sync now" buttons bypass this on purpose and call [SyncScheduler] directly.
 */
@Singleton
class ForegroundSyncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kvProxy: KvProxy,
    private val syncScheduler: SyncScheduler,
) {
    private val connectivity by lazy { ConnectivityChecker(context) }

    @Volatile
    private var lastRequestAt = 0L

    /**
     * Trigger a full sync unless one was requested less than [MIN_GAP_MS] ago or we are offline.
     * [force] skips the rate limit (used when leaving the app: that sync must not be dropped).
     */
    suspend fun requestSync(reason: String, force: Boolean = false): Boolean {
        val settings = try {
            kvProxy.getSyncSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Sync settings unavailable, skipping $reason sync: ${e.message}")
            return false
        }
        if (!settings.syncEnabled) return false
        if (settings.username.isBlank() || settings.password.isBlank()) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastRequestAt < MIN_GAP_MS) {
            Log.d(TAG, "Skipping $reason sync: last request ${now - lastRequestAt} ms ago")
            return false
        }
        if (!connectivity.isNetworkAvailable()) {
            Log.d(TAG, "Skipping $reason sync: no network")
            return false
        }
        if (settings.wifiOnly && !connectivity.isUnmeteredConnected()) {
            Log.d(TAG, "Skipping $reason sync: WiFi-only and not on an unmetered network")
            return false
        }
        lastRequestAt = now
        Log.i(TAG, "Triggering sync ($reason)")
        syncScheduler.triggerImmediateSync(SyncRequest.SyncAll)
        return true
    }

    /** Called from the activity's onResume; honours the "sync when the app resumes" setting. */
    suspend fun onAppResumed() {
        val settings = try {
            kvProxy.getSyncSettings()
        } catch (e: Exception) {
            return
        }
        if (!settings.syncOnResume) return
        requestSync("resume")
    }

    /**
     * Called from the activity's onStop (app left the screen). Not rate-limited: the user may have
     * written something in the last seconds, and the process may be frozen soon after.
     */
    suspend fun onAppStopped() {
        val settings = try {
            kvProxy.getSyncSettings()
        } catch (e: Exception) {
            return
        }
        if (!settings.syncOnAppClose) return
        requestSync("app close", force = true)
    }

    /**
     * Activity-driven foreground syncing, in place of a periodic poll. Two triggers, both derived
     * from [ActivityPulse]:
     *
     *  - **settle**: [SyncSettings.idleSyncMinutes] after the last activity, sync once. While the
     *    user writes, nothing is sent; when they stop, the result goes up.
     *  - **return**: activity after at least [SyncSettings.returnSyncMinutes] of quiet syncs
     *    immediately — the server may have moved on while the tablet lay untouched.
     *
     * Runs only while the caller's scope lives (the activity is RESUMED), so an idle device costs
     * nothing; the WorkManager job stays the background fallback. Either threshold at 0 disables
     * that trigger.
     */
    suspend fun trackActivityWhileResumed(): Unit = coroutineScope {
        var settleJob: Job? = null
        // Entering the foreground counts as activity, so a long pause before the *next* touch does
        // not immediately re-trigger the return sync that onAppResumed already covered.
        var lastActivityAt = System.currentTimeMillis()

        ActivityPulse.pulses.collect { at ->
            val quietFor = at - lastActivityAt
            lastActivityAt = at
            val settings = try {
                kvProxy.getSyncSettings()
            } catch (e: Exception) {
                return@collect
            }
            if (!settings.syncEnabled) return@collect

            val returnAfter = settings.returnSyncMinutes
            if (returnAfter > 0 && quietFor >= returnAfter * 60_000L) {
                requestSync("back after ${quietFor / 60_000} min idle")
            }

            val settleAfter = settings.idleSyncMinutes
            settleJob?.cancel()
            if (settleAfter > 0) {
                settleJob = launch {
                    delay(settleAfter * 60_000L)
                    requestSync("idle for $settleAfter min")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ForegroundSync"
        const val MIN_GAP_MS = 30_000L
    }
}
