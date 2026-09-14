package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.folderBar
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.splitScratchNotebooks
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.fold
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.NotebookSyncStatusStore
import com.ethran.notable.sync.SentMarkStore
import com.ethran.notable.sync.SyncBadge
import com.ethran.notable.sync.SyncProgressReporter
import com.ethran.notable.sync.SyncRequest
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val folderId: String? = null,
    val isLatestVersion: Boolean = true,
    val isImporting: Boolean = false,
    /** The folder bar: root folders in bar order (plus the path to a nested current folder). */
    val folders: List<Folder> = emptyList(),
    /** Notebooks shown in the *Notebooks* grid: everything the folder holds except [scratchBooks]. */
    val books: List<Notebook> = emptyList(),
    /**
     * Notebooks the server side marked `"kind": "scratch"`, shown as tiles in the *Scratch notes*
     * row next to the folder's real scratch notes ([singlePages]); see [splitScratchNotebooks].
     */
    val scratchBooks: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList(),
    val syncBadges: Map<String, SyncBadge> = emptyMap(),
    val quickPageBadges: Map<String, SyncBadge> = emptyMap(),
    /** Quick pages that were sent and are locked. */
    val lockedPageIds: Set<String> = emptySet(),
    /** Notebooks that were sent and not changed since. */
    val sentNotebookIds: Set<String> = emptySet(),
)

/** What the home screen's sync chip shows; a pure function of engine state, settings and badges. */
data class HomeSyncStatus(
    val enabled: Boolean = false,
    /** Sync is on but no password is available, so every sync is skipped without a word. */
    val missingPassword: Boolean = false,
    val state: SyncState = SyncState.Idle,
    val lastSyncTime: Long? = null,
    /** A sync is enqueued or running (WorkManager), even before the engine reports progress. */
    val busy: Boolean = false,
    /** Notebooks with local edits not yet on the server. */
    val pendingCount: Int = 0,
    /** Notebooks whose last sync ended in a conflict or error. */
    val conflictCount: Int = 0,
)

// Private data class for clean Flow combining
private data class LibraryDatabaseState(
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList(),
    val syncBadges: Map<String, SyncBadge> = emptyMap(),
    val quickPageBadges: Map<String, SyncBadge> = emptyMap(),
    val lockedPageIds: Set<String> = emptySet(),
    val sentNotebookIds: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    val appRepository: AppRepository,
    private val appEventBus: AppEventBus,
    val importEngine: ImportEngine,
    val exportEngine: ExportEngine,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    val pageDataManager: PageDataManager,
    private val snackDispatcher: SnackDispatcher,
    val syncScheduler: SyncScheduler,
    private val syncStatusStore: NotebookSyncStatusStore,
    private val syncProgressReporter: SyncProgressReporter,
    private val kvProxy: KvProxy,
    sentMarkStore: SentMarkStore,
    @param:ApplicationContext private val context: Context // Kept strictly for ImportEngine
) : ViewModel() {

    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository
    private val pageRepository = appRepository.pageRepository

    private val _folderId = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)
    private val _newlyCreatedBookId = MutableStateFlow<String?>(null)
    val newlyCreatedBookId: StateFlow<String?> = _newlyCreatedBookId
    private val _isLatestVersion = MutableStateFlow(true)

    // 1. Convert LiveData to Flow and switch automatically when folderId changes.
    // The folder bar always shows the root folders, whatever folder is open (see folderBar).
    private val _foldersFlow =
        combine(_folderId, folderRepository.getAllLive().asFlow()) { id, all -> folderBar(all, id) }
    private val _booksFlow =
        _folderId.flatMapLatest { id -> bookRepository.getAllInFolder(id).asFlow() }
    private val _singlePagesFlow =
        _folderId.flatMapLatest { id -> pageRepository.getSinglePagesInFolder(id).asFlow() }

    // 2. Group the database flows (plus per-notebook sync badges) semantically
    private val _dbDataFlow = combine(
        combine(
            _foldersFlow, _booksFlow, _singlePagesFlow,
            syncStatusStore.badges, syncStatusStore.quickPageBadges
        ) { folders, books, pages, badges, quickBadges ->
            LibraryDatabaseState(folders, books, pages, badges, quickBadges)
        },
        sentMarkStore.marks,
    ) { db, marks ->
        db.copy(
            lockedPageIds = db.singlePages.filter { marks.isPageLocked(it.id) }.mapTo(HashSet()) { it.id },
            sentNotebookIds = db.books.filter { marks.isNotebookMarked(it.id, it.updatedAt.time) }
                .mapTo(HashSet()) { it.id },
        )
    }

    // 3. Expose the final UI State
    val uiState: StateFlow<LibraryUiState> = combine(
        _folderId, _isLatestVersion, _isImporting, _dbDataFlow
    ) { folderId, isLatestVersion, isImporting, dbData ->
        val (scratchBooks, books) = splitScratchNotebooks(dbData.books)
        LibraryUiState(
            folderId = folderId,
            isLatestVersion = isLatestVersion,
            isImporting = isImporting,
            folders = dbData.folders,
            books = books,
            scratchBooks = scratchBooks,
            singlePages = dbData.singlePages,
            syncBadges = dbData.syncBadges,
            quickPageBadges = dbData.quickPageBadges,
            lockedPageIds = dbData.lockedPageIds,
            sentNotebookIds = dbData.sentNotebookIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )


    // Home-screen sync chip. Settings are re-read on every engine state change (the orchestrator
    // persists lastSyncTime right after reporting success) and on each subscription, so toggling
    // sync in settings shows up when coming back to the library.
    private val _syncSettingsFlow = syncProgressReporter.state.map { state ->
        val settings = try {
            kvProxy.getSyncSettings()
        } catch (e: Exception) {
            null
        }
        val stored = settings?.lastSyncTime
        // A just-finished run may not have been persisted yet: fall back to "now".
        val last = if (state is SyncState.Success) maxOf(stored ?: 0L, System.currentTimeMillis())
        else stored
        ChipSettings(
            enabled = settings?.syncEnabled == true,
            // Blank also when the stored password can't be decrypted: every sync is then skipped.
            missingPassword = settings?.syncEnabled == true && settings.password.isBlank(),
            state = state,
            lastSyncTime = last,
        )
    }

    private data class ChipSettings(
        val enabled: Boolean,
        val missingPassword: Boolean,
        val state: SyncState,
        val lastSyncTime: Long?,
    )

    val syncStatus: StateFlow<HomeSyncStatus> = combine(
        _syncSettingsFlow, syncStatusStore.badges, syncScheduler.immediateSyncActive()
    ) { chip, badges, busy ->
        HomeSyncStatus(
            enabled = chip.enabled,
            missingPassword = chip.missingPassword,
            state = chip.state,
            lastSyncTime = chip.lastSyncTime,
            busy = busy,
            pendingCount = badges.values.count { it == SyncBadge.NOT_SYNCED },
            conflictCount = badges.values.count { it == SyncBadge.CONFLICT || it == SyncBadge.ERROR },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeSyncStatus()
    )

    /**
     * Home-screen "sync now": the same WorkManager funnel as the settings button. In the root it
     * is the standard round (root + "Today" with subfolders + dirty); inside a folder it syncs that
     * folder and its subfolders.
     */
    fun onSyncNow() {
        syncScheduler.triggerImmediateSync(SyncRequest.SyncAll(folderId = _folderId.value))
    }

    /**
     * Delete a folder (Room cascades to its notebooks and quick pages, as upstream does) and put a
     * folder tombstone on the server, so the other side drops it instead of restoring it.
     */
    fun deleteFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            folderRepository.delete(folderId)
            syncScheduler.triggerImmediateSync(SyncRequest.UploadFolderDeletion(folderId))
        }
    }

    init {
        // Run network/heavy checks in the background
        viewModelScope.launch(Dispatchers.IO) {
            _isLatestVersion.value = isLatestVersion(context, appEventBus, true)
        }
    }

    fun onPreviewRequested(pageId: String) {
        thumbnailBackfillQueue.enqueue(listOf(pageId))
    }

    fun loadFolder(folderId: String?) {
        pageDataManager.cancelLoadingPages()
        _folderId.value = folderId
    }

    /** New folders are always root folders: the bar offers no nesting. */
    fun createNewFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            folderRepository.create(Folder(parentFolderId = null))
        }
    }

    fun deleteEmptyBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.delete(bookId)
        }
    }

    fun onCreateNewNotebook() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val notebook = Notebook(
                parentFolderId = _folderId.value,
                defaultBackground = settings.defaultNativeTemplate,
                defaultBackgroundType = BackgroundType.Native.key
            )
            bookRepository.create(notebook)
            _newlyCreatedBookId.value = notebook.id
        }
    }

    fun clearNewlyCreatedBookId() {
        _newlyCreatedBookId.value = null
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val snackText =
                if (copy) "Importing PDF background (copy)" else "Setting up observer for PDF"

            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(SnackConf(text = snackText, duration = 2000))

            try {
                // Ideally, ImportEngine should be injected via Hilt rather than instantiated here
                val result = importEngine.import(
                    uri, ImportOptions(folderId = _folderId.value, linkToExternalFile = !copy)
                )
                
                result.fold(
                    onSuccess = { importedPageIds ->
                        if (importedPageIds.isNotEmpty()) {
                            thumbnailBackfillQueue.enqueue(importedPageIds, PreviewSaveMode.STRICT_BW)
                        }
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "PDF Import Successful"))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onXoppFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Importing from xopp file...",
                    duration = 2000
                )
            )

            try {
                val result = importEngine.import(uri, ImportOptions(folderId = _folderId.value))
                result.fold(
                    onSuccess = { _ -> 
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "XOPP Import Successful", duration = 3000))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

}
