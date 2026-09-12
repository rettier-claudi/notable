package com.ethran.notable.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps CPU and Wi-Fi up for the duration of one sync round.
 *
 * The round that matters most is the one started when the app leaves the screen — usually
 * because the tablet is going to sleep. Without a lock the process is free to be frozen mid-round
 * and the framework's Wi-Fi power save kicks in at screen-off; a partial wake lock keeps the CPU
 * running and a full-high-perf Wi-Fi lock tells the framework that this app needs the radio at
 * full performance until the round is over. Both are released in `finally` and, as a backstop,
 * time out after [MAX_HOLD_MS] should a round ever hang in a blocking socket.
 *
 * Whether Onyx's own "turn Wi-Fi off in sleep" honours a WifiLock is not something these locks
 * can guarantee: a firmware that calls `setWifiEnabled(false)` from its power manager overrides
 * any lock. The locks make the round as short and as protected as an ordinary app can; the rest
 * is the system setting (see the README).
 */
@Singleton
class SyncPowerGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /** Runs [block] with the locks held. Lock failures are logged, never propagated. */
    suspend fun <T> hold(tag: String, block: suspend () -> T): T {
        val wake = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notable:sync:$tag")
                ?.also { it.acquire(MAX_HOLD_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock unavailable: ${e.message}")
            null
        }
        val wifi = try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiManager?.createWifiLock(mode, "notable:sync:$tag")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi lock unavailable: ${e.message}")
            null
        }
        if (wake != null || wifi != null) {
            Log.d(TAG, "Holding locks for $tag (wake=${wake != null}, wifi=${wifi != null})")
        }
        try {
            return block()
        } finally {
            try {
                if (wifi?.isHeld == true) wifi.release()
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi lock release failed: ${e.message}")
            }
            try {
                if (wake?.isHeld == true) wake.release()
            } catch (e: Exception) {
                Log.w(TAG, "Wake lock release failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SyncPowerGuard"
        /** Longer than any sane round, shorter than "the battery is flat in the morning". */
        const val MAX_HOLD_MS = 5 * 60_000L
    }
}
