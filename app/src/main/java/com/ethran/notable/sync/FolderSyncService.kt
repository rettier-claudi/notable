package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the pure folder merge: what folders.json and the local table should contain, which
 * local folders a server tombstone removes, and which tombstones a newer local copy overrides.
 */
internal data class FolderMerge(
    val merged: List<Folder>,
    /** Local folders to delete (contents move to the root), in no particular order. */
    val deleteLocally: List<Folder>,
    /** Folder ids whose tombstone is older than the local copy: keep the folder, drop the tombstone. */
    val resurrected: List<String>,
)

/**
 * Per-folder last-writer-wins union of [local] and [remote] — a local folder replaces its remote
 * counterpart only when its `updatedAt` is later; a folder known to only one side is kept — minus
 * the folders in [tombstones] (id -> deletion time). A tombstoned folder is dropped from both sides
 * unless the local copy was changed *after* the tombstone (same resurrection rule as notebooks,
 * section 5.7): then it stays, goes back into folders.json, and the tombstone is to be removed.
 * An undated tombstone (server omitted Last-Modified) always wins, as there is nothing to compare.
 *
 * A rename is nothing but a newer `updatedAt` with a new title on the same id — no notebook is
 * involved, so a renamed folder never makes a notebook re-transfer.
 */
internal fun mergeFolders(
    local: List<Folder>,
    remote: List<Folder>,
    tombstones: Map<String, Date?>,
): FolderMerge {
    val folderMap = LinkedHashMap<String, Folder>()
    remote.forEach { folderMap[it.id] = it }
    local.forEach { l ->
        val r = folderMap[l.id]
        if (r == null || l.updatedAt.after(r.updatedAt)) folderMap[l.id] = l
    }
    val deleteLocally = mutableListOf<Folder>()
    val resurrected = mutableListOf<String>()
    for ((id, deletedAt) in tombstones) {
        val localCopy = local.firstOrNull { it.id == id }
        if (localCopy != null && deletedAt != null && localCopy.updatedAt.after(deletedAt)) {
            resurrected += id
            continue
        }
        folderMap.remove(id)
        if (localCopy != null) deleteLocally += localCopy
    }
    return FolderMerge(folderMap.values.toList(), deleteLocally, resurrected)
}

@Singleton
class FolderSyncService @Inject constructor(
    private val appRepository: AppRepository,
) {
    private val folderSerializer = FolderSerializer
    private val log = SyncLogger

    suspend fun syncFolders(
        client: WebDAVClient,
        uploadOnly: Boolean,
        downloadOnly: Boolean = false,
        /** From the preflight's root listing — saves a HEAD on folders.json every round. */
        remoteFileExists: Boolean = true,
        /** Folder tombstones from this round's `deletions/` listing (id -> deletion time). */
        folderTombstones: Map<String, Date?> = emptyMap(),
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Syncing folders...")
        val localFolders = appRepository.folderRepository.getAll()
        val remotePath = SyncPaths.foldersFile()

        if (remoteFileExists) {
            return client.getFileWithMetadata(remotePath).flatMap { remoteFile ->
                // Null when the server issues no ETag: the PUT below then goes out unguarded rather
                // than failing. Failing would abort the whole run (SyncOrchestrator treats folder
                // sync as fatal) over the one write that can least afford to be guarded — the merge
                // below is a union, so an unguarded write can never drop a remote folder.
                val remoteEtag = remoteFile.etag

                val remoteFoldersJson = remoteFile.content.decodeToString()
                val remoteFolders = folderSerializer.deserializeFolders(remoteFoldersJson)

                val merge = mergeFolders(localFolders, remoteFolders, folderTombstones)

                if (!uploadOnly) {
                    applyLocally(merge, client)
                }

                // Download-only: apply the merge locally but never push folders.json.
                if (downloadOnly) {
                    AppResult.Success(Unit)
                } else {
                    // Written back every round on purpose, changed or not: the server side reads the
                    // file's serverTimestamp as this device's heartbeat ("the tablet has completed a
                    // round since I last wrote"). One PUT per round; drop it only together with that.
                    val updatedFoldersJson = folderSerializer.serializeFolders(merge.merged)
                    client.putFile(
                        remotePath,
                        updatedFoldersJson.toByteArray(),
                        "application/json",
                        ifMatch = remoteEtag
                    )
                }
            }.map { }
        } else {
            val merge = mergeFolders(localFolders, emptyList(), folderTombstones)
            if (!uploadOnly) applyLocally(merge, client)
            if (!downloadOnly && merge.merged.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(merge.merged)
                return client.putFile(remotePath, foldersJson.toByteArray(), "application/json")
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Write the merged set to the local table and act on tombstones: deleted folders go (their
     * contents move to the root, nothing is deleted with them), resurrected folders get their
     * tombstone removed from the server (best-effort; a leftover is re-checked next round).
     */
    private suspend fun applyLocally(merge: FolderMerge, client: WebDAVClient) {
        for (folder in merge.merged) {
            val existing = appRepository.folderRepository.get(folder.id)
            if (existing != null) {
                if (existing != folder) appRepository.folderRepository.update(folder)
            } else {
                appRepository.folderRepository.create(folder)
            }
        }
        for (folder in merge.deleteLocally) {
            log.i(TAG, "Deleting folder locally (tombstone on server): ${folder.title}")
            try {
                appRepository.deleteFolderKeepingContents(folder.id)
            } catch (e: Exception) {
                log.e(TAG, "Failed to delete folder ${folder.title}: ${e.message}")
            }
        }
        for (id in merge.resurrected) {
            log.i(TAG, "↻ Keeping folder $id (changed after its server deletion); removing tombstone")
            client.delete(SyncPaths.folderTombstone(id)).onError {
                log.w(TAG, "Failed to remove folder tombstone $id: ${it.userMessage}")
            }
        }
    }

    companion object {
        private const val TAG = "FolderSyncService"
    }
}
