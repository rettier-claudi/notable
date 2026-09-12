package com.ethran.notable.sync

import androidx.work.Data

sealed class SyncRequest {
    /**
     * A full sync round. Its *scope* — which notebooks get a manifest check — is decided by
     * [SyncScope]: by default the root ("Workspace") and the folder titled [ALWAYS_SYNCED_FOLDER_TITLE]
     * with its subfolders, plus anything locally dirty. [folderId] narrows the round to one folder
     * and its subfolders (the home screen's sync button inside a folder); [notebookId] adds one notebook regardless of its folder (the
     * editor's sync button and "Send", so a notebook in an old folder still gets there).
     * All variants share one unique work name: a round already running satisfies the request.
     */
    data class SyncAll(val folderId: String? = null, val notebookId: String? = null) : SyncRequest()
    /** Zero-byte `deletions/folder-<id>` so other devices drop the folder instead of restoring it. */
    data class UploadFolderDeletion(val folderId: String) : SyncRequest()
    data object ForceUpload : SyncRequest()
    data object ForceDownload : SyncRequest()
    data class UploadDeletion(val notebookId: String) : SyncRequest()
    data class SyncNotebook(val notebookId: String) : SyncRequest()
    data class SyncFromPageId(val pageId: String) : SyncRequest()

    val typeKey: String
        get() = when (this) {
            is SyncAll -> TYPE_SYNC_ALL
            is UploadFolderDeletion -> TYPE_UPLOAD_FOLDER_DELETION
            ForceUpload -> TYPE_FORCE_UPLOAD
            ForceDownload -> TYPE_FORCE_DOWNLOAD
            is UploadDeletion -> TYPE_UPLOAD_DELETION
            is SyncNotebook -> TYPE_SYNC_NOTEBOOK
            is SyncFromPageId -> TYPE_SYNC_FROM_PAGE_ID
        }

    /**
     * Returns a unique identifier for this request's parameters,
     * used for WorkManager unique work naming.
     */
    val identifier: String
        get() = when (this) {
            is UploadDeletion -> "notebookId:$notebookId"
            is SyncNotebook -> "notebookId:$notebookId"
            is SyncFromPageId -> "pageId:$pageId"
            is UploadFolderDeletion -> "folderId:$folderId"
            else -> "default"
        }

    fun toDataBuilder(): Data.Builder {
        val builder = Data.Builder().putString(KEY_SYNC_TYPE, typeKey)
        when (this) {
            is SyncAll -> {
                folderId?.let { builder.putString(KEY_FOLDER_ID, it) }
                notebookId?.let { builder.putString(KEY_NOTEBOOK_ID, it) }
            }
            is UploadFolderDeletion -> builder.putString(KEY_FOLDER_ID, folderId)
            is UploadDeletion -> builder.putString(KEY_NOTEBOOK_ID, notebookId)
            is SyncNotebook -> builder.putString(KEY_NOTEBOOK_ID, notebookId)
            is SyncFromPageId -> builder.putString(KEY_PAGE_ID, pageId)
            else -> {}
        }
        return builder
    }

    companion object {
        const val KEY_SYNC_TYPE = "sync_type"
        const val KEY_NOTEBOOK_ID = "notebook_id"
        const val KEY_PAGE_ID = "page_id"
        const val KEY_FOLDER_ID = "folder_id"

        const val TYPE_SYNC_ALL = "SYNC_ALL"
        const val TYPE_FORCE_UPLOAD = "FORCE_UPLOAD"
        const val TYPE_FORCE_DOWNLOAD = "FORCE_DOWNLOAD"
        const val TYPE_UPLOAD_DELETION = "UPLOAD_DELETION"
        const val TYPE_SYNC_NOTEBOOK = "SYNC_NOTEBOOK"
        const val TYPE_SYNC_FROM_PAGE_ID = "SYNC_FROM_PAGE_ID"
        const val TYPE_UPLOAD_FOLDER_DELETION = "UPLOAD_FOLDER_DELETION"

        fun fromData(data: Data): SyncRequest? {
            val type = data.getString(KEY_SYNC_TYPE) ?: TYPE_SYNC_ALL
            return when (type) {
                TYPE_SYNC_ALL -> SyncAll(
                    folderId = data.getString(KEY_FOLDER_ID),
                    notebookId = data.getString(KEY_NOTEBOOK_ID),
                )
                TYPE_UPLOAD_FOLDER_DELETION ->
                    data.getString(KEY_FOLDER_ID)?.let { UploadFolderDeletion(it) }
                TYPE_FORCE_UPLOAD -> ForceUpload
                TYPE_FORCE_DOWNLOAD -> ForceDownload
                TYPE_UPLOAD_DELETION -> data.getString(KEY_NOTEBOOK_ID)?.let { UploadDeletion(it) }
                TYPE_SYNC_NOTEBOOK -> data.getString(KEY_NOTEBOOK_ID)?.let { SyncNotebook(it) }
                TYPE_SYNC_FROM_PAGE_ID -> data.getString(KEY_PAGE_ID)?.let { SyncFromPageId(it) }
                else -> null
            }
        }
    }
}
