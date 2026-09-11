package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.data.db.KvProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
     * Foreground poll: every [SyncSettings.foregroundSyncIntervalMinutes] minutes while the caller's
     * scope is alive. Re-reads the settings each round so changes apply without a restart; a
     * value of 0 disables it (checked again every minute).
     */
    suspend fun pollWhileResumed() {
        while (currentCoroutineContext().isActive) {
            val settings = try {
                kvProxy.getSyncSettings()
            } catch (e: Exception) {
                delay(SETTINGS_RECHECK_MS)
                continue
            }
            val minutes = settings.foregroundSyncIntervalMinutes
            if (!settings.syncEnabled || minutes <= 0) {
                delay(SETTINGS_RECHECK_MS)
                continue
            }
            delay(minutes * 60_000L)
            requestSync("foreground poll")
        }
    }

    companion object {
        private const val TAG = "ForegroundSync"
        const val MIN_GAP_MS = 30_000L
        private const val SETTINGS_RECHECK_MS = 60_000L
    }
}
