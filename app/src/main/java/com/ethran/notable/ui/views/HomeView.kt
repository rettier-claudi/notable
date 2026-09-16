package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.ui.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.sync.SyncBadge
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.CornerBadges
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.dialogs.ConflictResolutionDialog
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Check
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.CornerDownRight
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Home
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import com.ethran.notable.sync.SyncState
import com.ethran.notable.ui.viewmodels.HomeSyncStatus
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.shipbook.shipbooksdk.ShipBook


object LibraryDestination : NavigationDestination {
    override val route = "library"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String? = null): String {
        return if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
    }
}

private val log = ShipBook.getLogger("HomeView")

@Composable
fun Library(
    navController: NavController,
    folderId: String? = null,
    goToPage: (String) -> Unit = {},
    onCreateNewQuickPage: (String?) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val newlyCreatedBookId by viewModel.newlyCreatedBookId.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    // Show config dialog for newly created notebooks so user can rename immediately
    if (newlyCreatedBookId != null) {
        if (GlobalAppSettings.current.renameOnCreate && uiState.books.any { it.id == newlyCreatedBookId }) {
            NotebookConfigDialog(
                appRepository = viewModel.appRepository,
                exportEngine = viewModel.exportEngine,
                syncScheduler = viewModel.syncScheduler,
                bookId = newlyCreatedBookId!!,
                onClose = { viewModel.clearNewlyCreatedBookId() }
            )
        } else {
            viewModel.clearNewlyCreatedBookId()
        }
    }

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        syncScheduler = viewModel.syncScheduler,
        uiState = uiState,
        syncStatus = syncStatus,
        onSyncNow = viewModel::onSyncNow,
        onCancelSync = viewModel::onCancelSync,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        onCreateNewQuickPage = { onCreateNewQuickPage(uiState.folderId) },
        onCreateNewFolder = viewModel::createNewFolder,
        onDeleteFolder = viewModel::deleteFolder,
        onDeleteEmptyBook = viewModel::deleteEmptyBook,
        onCreateNewNotebook = viewModel::onCreateNewNotebook,
        onImportPdf = viewModel::onPdfFile,
        onImportXopp = viewModel::onXoppFile,
        onPreviewMissing = viewModel::onPreviewRequested

    )
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryContent(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    uiState: LibraryUiState,
    syncStatus: HomeSyncStatus = HomeSyncStatus(),
    onSyncNow: () -> Unit = {},
    onCancelSync: () -> Unit = {},
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewQuickPage: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onDeleteFolder: (String) -> Unit = {},
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    onPreviewMissing: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Topbar {
            // Topbar stacks its content in a Box, so the two rows go in a Column. Same two rows
            // in the root and in every folder, with fixed heights: the old breadcrumb put a 24 dp
            // chevron next to 20 sp text inside a folder only (both in one Box with the chip
            // row), which made the bar a pixel or two taller there than on the home screen.
            Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(TOP_ROW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TopIconButton(
                    icon = FeatherIcons.FolderPlus,
                    contentDescription = stringResource(R.string.home_new_folder_button),
                    onClick = onCreateNewFolder,
                )
                ImportFileButton(onImportPdf = onImportPdf, onImportXopp = onImportXopp)
                SyncStatusChip(status = syncStatus, onSyncNow = onSyncNow, onCancelSync = onCancelSync)
                BadgedBox(
                    badge = {
                        if (!uiState.isLatestVersion) Badge(
                            backgroundColor = Color.Black,
                            modifier = Modifier.offset((-12).dp, 10.dp)
                        )
                    }) {
                    Icon(
                        imageVector = FeatherIcons.Settings, contentDescription = "Settings",
                        Modifier
                            .padding(8.dp)
                            .noRippleClickable(onClick = onNavigateToSettings)
                    )
                }
            }
            FolderBar(
                folderRepository = appRepository.folderRepository,
                folders = uiState.folders,
                currentFolderId = uiState.folderId,
                onNavigateToFolder = onNavigateToFolder,
                onDeleteFolder = onDeleteFolder,
            )
            }
        }

        Column(Modifier.padding(10.dp)) {
            Spacer(Modifier.height(10.dp))
            ShowPagesRow(
                appRepository = appRepository,
                pages = uiState.singlePages,
                currentPageId = null,
                title = stringResource(R.string.home_quick_pages), onSelectPage = goToPage,
                showAddQuickPage = true,
                onCreateNewQuickPage = onCreateNewQuickPage,
                onPreviewMissing = onPreviewMissing,
                syncBadges = uiState.quickPageBadges,
                lockedPageIds = uiState.lockedPageIds,
            ) {
                // Notebooks the server side marked "kind": "scratch" sit in the same row, as
                // tiles: tap opens the first page, long-press opens the notebook settings.
                items(uiState.scratchBooks.reversed(), key = { "book-" + it.id }) { book ->
                    ScratchBookTile(
                        appRepository = appRepository,
                        exportEngine = exportEngine,
                        syncScheduler = syncScheduler,
                        book = book,
                        syncBadge = uiState.syncBadges[book.id],
                        sent = book.id in uiState.sentNotebookIds,
                        onNavigateToEditor = onNavigateToEditor,
                        onPreviewMissing = onPreviewMissing,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            NotebookGrid(
                appRepository = appRepository,
                exportEngine = exportEngine,
                syncScheduler = syncScheduler,
                books = uiState.books,
                isImporting = uiState.isImporting,
                syncBadges = uiState.syncBadges,
                sentNotebookIds = uiState.sentNotebookIds,
                onNavigateToEditor = onNavigateToEditor,
                onDeleteEmptyBook = onDeleteEmptyBook,
                onCreateNewNotebook = onCreateNewNotebook,
                onPreviewMissing = onPreviewMissing
            )
        }
    }
}

private val TOP_ROW_HEIGHT = 44.dp
private val FOLDER_BAR_HEIGHT = 44.dp

/** An icon-only button in the top row, sized like the settings icon. */
@Composable
private fun TopIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.Black,
        modifier = Modifier
            .padding(8.dp)
            .size(24.dp)
            .noRippleClickable(onClick = onClick)
    )
}

/**
 * "Open file" as an icon in the top row: the same PDF/xopp import that used to be the lower half
 * of the import tile in the notebook grid.
 */
@Composable
fun ImportFileButton(onImportPdf: (Uri, Boolean) -> Unit, onImportXopp: (Uri) -> Unit) {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    var showPdfImportChoiceDialog by remember { mutableStateOf<Uri?>(null) }

    showPdfImportChoiceDialog?.let { uri ->
        PdfImportChoiceDialog(uri = uri, onCopy = { picked ->
            showPdfImportChoiceDialog = null
            onImportPdf(picked, /* copy= */ true)
        }, onObserve = {
            showPdfImportChoiceDialog = null
            onImportPdf(it, /* copy= */ false)
        }, onDismiss = { showPdfImportChoiceDialog = null })
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            log.w("OpenDocument: uri is null (user cancelled or provider returned null)")
            return@rememberLauncherForActivityResult
        }
        try {
            val mimeType = context.contentResolver.getType(uri)
            log.d("Selected file mimeType: $mimeType, uri: $uri")
            if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
                showPdfImportChoiceDialog = uri
            } else {
                onImportXopp(uri)
            }
        } catch (e: Exception) {
            log.e("contentPicker failed: ${e.message}", e)
            snackState.showOrUpdateSnack(SnackConf(text = "Importing failed: ${e.message}"))
        }
    }
    TopIconButton(
        icon = FeatherIcons.Upload,
        contentDescription = stringResource(R.string.home_import_file),
        onClick = {
            launcher.launch(
                arrayOf(
                    "application/x-xopp",
                    "application/gzip",
                    "application/octet-stream",
                    "application/pdf"
                )
            )
        },
    )
}

/**
 * The folder bar: "Workspace" (the root) and the root folders, in [sortFoldersForBar] order, the
 * open one filled black, plus the subfolders along the path to the open folder (see [folderBar]).
 * Tapping switches; long-pressing a folder opens its settings (rename, delete). Fixed height on
 * purpose (see [LibraryContent]). New folders are always created in the root.
 */
@Composable
fun FolderBar(
    folderRepository: com.ethran.notable.data.db.FolderRepository,
    folders: List<Folder>,
    currentFolderId: String?,
    onNavigateToFolder: (String?) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(FOLDER_BAR_HEIGHT)
            .autoEInkAnimationOnScroll()
    ) {
        item(key = "root") {
            FolderChip(
                title = stringResource(R.string.home_root_folder),
                icon = FeatherIcons.Home,
                selected = currentFolderId == null,
                onClick = { onNavigateToFolder(null) },
                onLongClick = null,
            )
        }
        items(folders, key = { it.id }) { folder ->
            var isFolderSettingsOpen by remember { mutableStateOf(false) }
            if (isFolderSettingsOpen) FolderConfigDialog(
                folderRepository,
                folderId = folder.id,
                onClose = {
                    log.i("Closing Directory Dialog")
                    isFolderSettingsOpen = false
                },
                onDelete = {
                    isFolderSettingsOpen = false
                    onDeleteFolder(folder.id)
                    if (folder.id == currentFolderId) onNavigateToFolder(null)
                },
            )
            FolderChip(
                title = folder.title,
                // A subfolder (Today/Scratch notes, Today/Notebooks, created by the server side)
                // is shown indented by its icon; the app itself only creates root folders.
                icon = if (folder.parentFolderId == null) FeatherIcons.Folder else FeatherIcons.CornerDownRight,
                selected = folder.id == currentFolderId,
                onClick = { onNavigateToFolder(folder.id) },
                onLongClick = { isFolderSettingsOpen = true },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChip(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val fg = if (selected) Color.White else Color.Black
    val bg = if (selected) Color.Black else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(32.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .border(0.5.dp, Color.Black)
            .background(bg)
            .padding(horizontal = 10.dp)
    ) {
        Icon(
            imageVector = icon, contentDescription = null, tint = fg,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text = title, color = fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Sync indicator + button on the home screen: state of the engine, time of the last successful
 * sync, unsynced/conflicted notebook counts. Tapping it starts a sync (or a retry after an error);
 * while a sync runs the tap cancels it. Hidden while sync is disabled.
 */
@Composable
fun SyncStatusChip(status: HomeSyncStatus, onSyncNow: () -> Unit, onCancelSync: () -> Unit = {}) {
    if (!status.enabled) return
    val state = status.state
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val timeLabel = status.lastSyncTime?.let {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = it }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "dd.MM. HH:mm"
        java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(it))
    }

    val busy = status.busy || state is SyncState.Syncing
    val (icon: ImageVector, text: String) = when {
        busy -> FeatherIcons.RefreshCw to stringResource(R.string.home_sync_syncing)
        status.missingPassword -> FeatherIcons.AlertTriangle to stringResource(
            if (status.passwordUnreadable) R.string.home_sync_password_unreadable else R.string.home_sync_no_password
        )
        state is SyncState.Error -> FeatherIcons.AlertTriangle to stringResource(R.string.home_sync_failed)
        status.conflictCount > 0 -> FeatherIcons.AlertTriangle to stringResource(R.string.home_sync_conflict)
        status.pendingCount > 0 -> FeatherIcons.RefreshCw to
            stringResource(R.string.home_sync_pending, status.pendingCount)
        timeLabel != null -> FeatherIcons.Check to stringResource(R.string.home_sync_synced_at, timeLabel)
        else -> FeatherIcons.RefreshCw to stringResource(R.string.home_sync_never)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
            .border(0.5.dp, Color.Black)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .noRippleClickable(onClick = { if (busy) onCancelSync() else onSyncNow() })
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Sync",
            tint = Color.Black,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp)
        )
    }
}

@Composable
fun NotebookGrid(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    books: List<Notebook>,
    isImporting: Boolean,
    syncBadges: Map<String, SyncBadge>,
    sentNotebookIds: Set<String> = emptySet(),
    onNavigateToEditor: (String, String) -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onPreviewMissing: (String) -> Unit
) {
    Text(text = stringResource(R.string.home_notebooks))
    Spacer(Modifier.height(10.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.autoEInkAnimationOnScroll()
    ) {
        item {
            NewNotebookTile(onCreateNewNotebook = onCreateNewNotebook)
        }

        if (books.isNotEmpty()) {
            items(books.reversed()) { book ->
                if (book.pageIds.isEmpty()) {
                    if (!isImporting) {
                        EmptyBookWarningHandler(
                            emptyBook = book,
                            onDelete = { onDeleteEmptyBook(book.id) },
                            onDismiss = { })
                    }
                    return@items
                }
                var isSettingsOpen by remember { mutableStateOf(false) }
                var isConflictOpen by remember { mutableStateOf(false) }
                NotebookCard(
                    bookId = book.id,
                    title = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    syncBadge = syncBadges[book.id],
                    sent = book.id in sentNotebookIds,
                    // A conflicted notebook opens the resolution dialog on tap — the reachable entry
                    // point for the CONFLICT badge — instead of the editor.
                    onOpen = { bookId, pageId ->
                        if (syncBadges[bookId] == SyncBadge.CONFLICT) isConflictOpen = true
                        else onNavigateToEditor(pageId, bookId)
                    },
                    onOpenSettings = { isSettingsOpen = true },
                    onPreviewMissing = onPreviewMissing
                )

                if (isSettingsOpen) {
                    NotebookConfigDialog(
                        appRepository,
                        exportEngine = exportEngine,
                        syncScheduler = syncScheduler,
                        bookId = book.id, onClose = { isSettingsOpen = false })
                }

                if (isConflictOpen) {
                    ConflictResolutionDialog(
                        bookId = book.id,
                        title = book.title,
                        onClose = { isConflictOpen = false })
                }
            }
        }
    }
}

/**
 * A scratch-kind notebook ("kind": "scratch" in its manifest, set by the server side) as a tile
 * in the *Scratch notes* row: the same 100 dp preview as a real scratch note, but with a
 * notebook's badges (checked box when sent, sync state) and a notebook's long-press settings.
 * Tapping opens the first page — such a notebook has one page, the count only shows when it has
 * more. A conflicted one opens the resolution dialog, as in the grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScratchBookTile(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    book: Notebook,
    syncBadge: SyncBadge?,
    sent: Boolean,
    onNavigateToEditor: (String, String) -> Unit,
    onPreviewMissing: (String) -> Unit,
) {
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isConflictOpen by remember { mutableStateOf(false) }
    Box {
        PagePreview(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (syncBadge == SyncBadge.CONFLICT) isConflictOpen = true
                        else onNavigateToEditor(book.pageIds[0], book.id)
                    },
                    onLongClick = { isSettingsOpen = true },
                )
                .width(100.dp)
                .aspectRatio(3f / 4f)
                .border(1.dp, Color.Black, RectangleShape),
            pageId = book.pageIds[0],
            onPreviewMissing = onPreviewMissing
        )
        if (book.pageIds.size > 1) Text(
            text = book.pageIds.size.toString(),
            modifier = Modifier
                .background(Color.Black)
                .padding(5.dp),
            color = Color.White
        )
        CornerBadges(
            modifier = Modifier.align(Alignment.TopEnd),
            sentIcon = if (sent) FeatherIcons.CheckSquare else null,
            sentDescription = "Sent",
            syncBadge = syncBadge,
        )
    }
    if (isSettingsOpen) {
        NotebookConfigDialog(
            appRepository,
            exportEngine = exportEngine,
            syncScheduler = syncScheduler,
            bookId = book.id, onClose = { isSettingsOpen = false })
    }
    if (isConflictOpen) {
        ConflictResolutionDialog(
            bookId = book.id,
            title = book.title,
            onClose = { isConflictOpen = false })
    }
}

/** The "new notebook" tile in the grid. File import moved to the top row ([ImportFileButton]). */
@Composable
fun NewNotebookTile(onCreateNewNotebook: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(100.dp)
            .aspectRatio(3f / 4f)
            .background(Color.LightGray.copy(alpha = 0.3f))
            .border(2.dp, Color.Black, RectangleShape)
            .noRippleClickable(onClick = onCreateNewNotebook)
    ) {
        Icon(
            imageVector = FeatherIcons.FilePlus, contentDescription = "Create Notebook",
            tint = Color.Gray, modifier = Modifier.size(40.dp)
        )
    }
}


@Preview(
    showBackground = true,
    name = "Library - Default State",
    widthDp = 800,
    heightDp = 1200
)
@Composable
fun LibraryContentPreview() {
    // 1. Create a dummy UI state with mock data
    val mockUiState = LibraryUiState(
        folderId = null,
        isLatestVersion = true,
        isImporting = false,
        folders = listOf(
            // Adjust constructor arguments based on your exact entity definition
            Folder(id = "folder_1", title = "Work Notes", parentFolderId = null),
            Folder(id = "folder_2", title = "Personal", parentFolderId = null)
        ),
        books = listOf(
            // Needs pageIds to render the card (empty books show a warning)
            Notebook(id = "book_1", title = "Meeting Minutes", pageIds = listOf("page1", "page2")),
            Notebook(id = "book_2", title = "Journal", pageIds = listOf("page3"))
        ),
        singlePages = emptyList() // Populate with mock Page() objects if you want to see Quick Pages
    )

    // 2. Render the stateless component with empty lambdas
//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}

@Suppress("UnusedVariable")
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Preview(showBackground = true, name = "Library - Update Available & Importing")
@Composable
fun LibraryContentUpdatePreview() {
    val mockUiState = LibraryUiState(
        folderId = "folder_1",
        isLatestVersion = false, // Will show the red badge on the settings icon
        isImporting = true,      // Will hide the delete warning for empty books
        folders = emptyList(),
        books = emptyList(),
        singlePages = emptyList()
    )

//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}