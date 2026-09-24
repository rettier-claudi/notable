package com.ethran.notable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.StrokeMigrationHelper
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.gestures.pageTurnDirectionForKey
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.ActivityPulse
import com.ethran.notable.sync.ForegroundSyncController
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.ui.AppEventUiBridge
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.SyncWorkUiBridge
import com.ethran.notable.ui.components.NotableApp
import com.ethran.notable.ui.components.crashLogsAsText
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.AppResumeClock
import com.ethran.notable.utils.hasUsableStorage
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.AndroidEntryPoint
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


private const val TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"
const val PACKAGE_NAME = "com.ethran.notable"

// TODO: Check if migrating to LocalConfiguration in Compose is good idea
var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Delay the init till we have the permissions required
    @Inject
    lateinit var kvProxy: dagger.Lazy<KvProxy>

    @Inject
    lateinit var strokeMigrationHelper: dagger.Lazy<StrokeMigrationHelper>

    @Inject
    lateinit var editorSettingCacheManager: dagger.Lazy<EditorSettingCacheManager>

    @Inject
    lateinit var appRepositoryLazy: dagger.Lazy<AppRepository>

    @Inject
    lateinit var exportEngineLazy: dagger.Lazy<ExportEngine>

    @Inject
    lateinit var pageDataManager: dagger.Lazy<PageDataManager>

    @Inject
    lateinit var syncScheduler: dagger.Lazy<SyncScheduler>

    @Inject
    lateinit var foregroundSync: dagger.Lazy<ForegroundSyncController>

    @Inject
    lateinit var snackDispatcher: SnackDispatcher

    @Inject
    lateinit var appEventUiBridge: AppEventUiBridge

    @Inject
    lateinit var syncWorkUiBridge: SyncWorkUiBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()
        ShipBook.start(
            this.application, BuildConfig.SHIPBOOK_APP_ID, BuildConfig.SHIPBOOK_APP_KEY
        )
        // ShipBook installs its own uncaught handler in start(); re-install ours on top so it is
        // outermost and still writes the durable crash file even if ShipBook doesn't chain back,
        // and flush any startup telemetry (crash-loop signal) that predated ShipBook being up.
        (application as? NotableApp)?.onShipBookStarted()
        maybeShowCrashLoopHint()

        Log.i(TAG, "Notable started")

        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels

        trackSystemGestureBlocking()
        trackForegroundSync()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncWorkUiBridge.syncUiEvents.collect { event ->
                    val message = event.errorArg?.let { arg ->
                        getString(event.messageResId, arg)
                    } ?: getString(event.messageResId)
                    // A failure message is longer and more important, so give it time to be read.
                    snackDispatcher.showOrUpdateSnack(
                        SnackConf(text = message, duration = if (event.isError) 7000 else 3000)
                    )
                }
            }
        }

        val snackState = SnackState()

        setContent {
            var isInitialized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (hasUsableStorage(this@MainActivity)) {
                    withContext(Dispatchers.IO) {
                        // Init app settings, also do migration
                        val savedSettings =
                            kvProxy.get().getOrDefault(
                                APP_SETTINGS_KEY,
                                AppSettings.serializer(),
                                AppSettings(version = 1)
                            )

                        // Fork: one-time upgrades, new toolbar elements go into a saved layout.
                        val settings = savedSettings.withPageArrowsAdded().withFrontLightAdded()
                        if (settings != savedSettings) {
                            kvProxy.get().setKv(APP_SETTINGS_KEY, settings, AppSettings.serializer())
                        }
                        GlobalAppSettings.update(settings)
                        strokeMigrationHelper.get().reencodeStrokePointsToBinary()
                        pageDataManager.get()
                            .registerComponentCallbacks(this@MainActivity.applicationContext)
                        editorSettingCacheManager.get().init()
                    }
                    restorePeriodicSyncSchedule()
                    // Trigger initial sync on app startup (fails silently if offline)
                    triggerInitialSync()
                }
                isInitialized = true
            }
            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    if (isInitialized) {
                        NotableApp(
                            // Call .get() here so they are only instantiated AFTER the permission check runs
                            exportEngine = exportEngineLazy.get(),
                            snackState = snackState,
                            snackDispatcher = snackDispatcher,
                            appRepository = appRepositoryLazy.get()
                        )
                    } else {
                        ShowInitMessage()
                    }
                }
            }
        }
    }


    /**
     * If startup detected a likely crash loop, show one dismissible snack with a "Copy logs" action
     * that copies the local crash files to the clipboard. Simplest possible surface — the full
     * viewer lives in Settings → Debug. Returns whether a loop was detected (so a test
     * harness can crash only on the non-recovery launch).
     */
    private fun maybeShowCrashLoopHint(): Boolean {
        if ((application as? NotableApp)?.consumeCrashLoopDetected() != true) return false
        snackDispatcher.showOrUpdateSnack(
            SnackConf(
                text = "Notable restarted after repeated crashes.",
                duration = null, // stays until dismissed
                actions = listOf(
                    "Copy logs" to {
                        val text = crashLogsAsText(this)
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Notable crash logs", text))
                        Toast.makeText(this, "Copied crash logs", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        )
        return true
    }

    private fun triggerInitialSync() {
        lifecycleScope.launch {
            try {
                val settings = kvProxy.get().getSyncSettings()
                if (settings.syncEnabled && settings.syncOnAppStart) {
                    Log.i(TAG, "Triggering one-time sync on app startup via WorkManager")
                    foregroundSync.get().requestSync("app start")
                }
            } catch (e: Exception) {
                Log.i(TAG, "Initial sync setup failed: ${e.message}")
            }
        }
    }

    /**
     * Foreground sync: a sync request whenever the activity resumes (first resume included --
     * the controller's rate limit folds it into the app-start sync) and a periodic poll that only
     * runs while RESUMED, so nothing keeps the radio busy while the tablet sleeps.
     */
    private fun trackForegroundSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!hasUsableStorage(this@MainActivity)) return@repeatOnLifecycle
                try {
                    foregroundSync.get().onAppResumed()
                    foregroundSync.get().trackActivityWhileResumed()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Foreground sync stopped: ${e.message}")
                }
            }
        }
    }

    private fun restorePeriodicSyncSchedule() {
        lifecycleScope.launch {
            try {
                val settings = kvProxy.get().getSyncSettings()
                syncScheduler.get().reconcilePeriodicSync(settings)
            } catch (e: Exception) {
                Log.i(TAG, "Periodic sync reconcile failed: ${e.message}")
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            CanvasEventBus.reinitSignal.emit(Unit)
        }
    }

    override fun onStop() {
        super.onStop()
        // The app left the screen (home, app switch, device sleep): push what was just written.
        // lifecycleScope lives until onDestroy, so the request is still made after onStop.
        lifecycleScope.launch {
            try {
                if (hasUsableStorage(this@MainActivity)) foregroundSync.get().onAppStopped()
            } catch (e: Exception) {
                Log.w(TAG, "Sync on app close failed: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        this.lifecycleScope.launch {
            Log.d("QuickSettings", "App is paused - maybe quick settings opened?")
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Log.d(TAG, "OnWindowFocusChanged: $hasFocus")
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreen()
        }
        lifecycleScope.launch {
            CanvasEventBus.onFocusChange.emit(hasFocus)
        }
    }

    override fun onResume() {
        super.onResume()
        AppResumeClock.markResumed()
        enableFullScreen()
        lifecycleScope.launch {
            CanvasEventBus.onFocusChange.emit(true)
        }
    }

    // Hardware page-turn keys (volume / page up-down / d-pad, also what Onyx's system
    // side-swipe gestures deliver). Consumed only while an editor is open and the setting is on,
    // so the keys keep their normal meaning in the library and in other apps.
    private fun consumePageTurnKey(keyCode: Int, isDown: Boolean): Boolean {
        if (!GlobalAppSettings.current.pageTurnKeys) return false
        val direction = pageTurnDirectionForKey(keyCode) ?: return false
        if (CanvasEventBus.pageTurnKey.subscriptionCount.value == 0) return false
        // Act on the down event only, but swallow the matching up event as well so the system
        // does not still change the volume on key release.
        if (isDown) CanvasEventBus.pageTurnKey.tryEmit(direction)
        return true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        // Every finger/stylus touch the window sees. Stroke commits report separately
        // (PageDataManager), because Onyx's raw pen path does not go through here.
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) ActivityPulse.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        ActivityPulse.touch()
        // Auto-repeat while holding a key would flip through the whole notebook.
        if (event != null && event.repeatCount > 0 && pageTurnDirectionForKey(keyCode) != null &&
            CanvasEventBus.pageTurnKey.subscriptionCount.value > 0 &&
            GlobalAppSettings.current.pageTurnKeys
        ) return true
        if (consumePageTurnKey(keyCode, isDown = true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (consumePageTurnKey(keyCode, isDown = false)) return true
        return super.onKeyUp(keyCode, event)
    }

    // Onyx SystemUI's TouchInteractionService swallows multi-finger touches for its
    // global gestures (three-finger screenshot), which steals our three-finger
    // swipes. Raising this flag makes it stand down — but the same pipeline serves
    // the side/bottom edge navigation swipes, so blocking is opt-in via settings.
    // The flag is global and sticky (nothing else clears it), so it is held only
    // while the activity is resumed AND the setting is on, released otherwise.
    private var onyxSystemGesturesBlocked = false

    private fun trackSystemGestureBlocking() {
        if (!DeviceCompat.isOnyxDevice) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    snapshotFlow { GlobalAppSettings.current.blockSystemGestures }
                        .collect { setOnyxSystemGesturesBlocked(it) }
                } finally {
                    setOnyxSystemGesturesBlocked(false)
                }
            }
        }
    }

    private fun setOnyxSystemGesturesBlocked(blocked: Boolean) {
        if (blocked == onyxSystemGesturesBlocked) return
        onyxSystemGesturesBlocked = blocked
        val action =
            if (blocked) "onyx.action.INTERCEPT_GESTURE"
            else "onyx.action.DO_NOT_INTERCEPT_GESTURE"
        sendBroadcast(Intent(action))
    }


    // when the screen orientation is changed, set new screen width restart is not necessary,
    // as we need first to update page dimensions which is done in EditorView()
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.i(TAG, "Switched to Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.i(TAG, "Switched to Portrait")
        }
        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
    }

    private fun enableFullScreen() {
        // Clearer intent broadcasting syntax
        val optimizeIntent = Intent("com.onyx.app.optimize.setting").apply {
            putExtra("optimize_fullScreen", true)
            putExtra(
                "optimize_pkgName", packageName
            ) // Use Context.packageName instead of hardcoding
        }
        sendBroadcast(optimizeIntent)

        // Modern, backwards-compatible AndroidX way to handle fullscreen / insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
@Preview(showBackground = true)
fun ShowInitMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Initializing...",
            color = Color.Black,
            fontSize = 30.sp
        )
    }
}