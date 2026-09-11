package com.ethran.notable.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ethran.notable.R
import com.ethran.notable.sync.SyncWorker
import com.ethran.notable.utils.DomainError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncWorkUiBridge @Inject constructor(
    @ApplicationContext context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    private val handledIds = LinkedHashSet<String>()

    // Flag to track the very first time the Flow reads from the DB
    private var isInitialDbRead = true

    val syncUiEvents: Flow<SnackEvent> = workManager
        .getWorkInfosByTagFlow(SyncWorker.SYNC_WORK_TAG)
        .transform { infos ->
            val finishedWorks = infos.filter { it.state.isFinished }

            // On app launch, populate our handledIds with past jobs but DO NOT emit Snackbars
            if (isInitialDbRead) {
                finishedWorks.forEach { info ->
                    handledIds.add("${info.id}:${info.state.name}")
                }
                isInitialDbRead = false
                return@transform
            }

            // For all subsequent emissions, check if it's a new finish state
            finishedWorks.forEach { info ->
                val key = "${info.id}:${info.state.name}"
                if (handledIds.add(key)) {
                    trimHandledIds()
                    buildSnackEvent(info)?.let { emit(it) }
                }
            }
        }

    // SyncWorker writes the human-readable DomainError.userMessage into OUTPUT_KEY_ERROR for real
    // failures, and only the class simpleName for the informational SyncInProgress case matched here.
    private val SYNC_IN_PROGRESS_ERROR = DomainError.SyncInProgress::class.simpleName

    private fun trimHandledIds(maxSize: Int = 64) {
        if (handledIds.size <= maxSize) return
        val iterator = handledIds.iterator()
        while (handledIds.size > maxSize && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /**
     * Null for every ordinary outcome. On e-ink each snack is a visible repaint that leaves
     * ghosting behind, and a sync that simply worked is already reported by the library chip and
     * the toolbar button — so only a real failure is worth interrupting for. Success, "skipped",
     * "already running" and user-initiated cancellation are all silent.
     */
    private fun buildSnackEvent(info: WorkInfo): SnackEvent? {
        val output = info.outputData
        val errorMsg = output.getString(SyncWorker.OUTPUT_KEY_ERROR)

        return when {
            errorMsg == null || errorMsg == SYNC_IN_PROGRESS_ERROR -> null
            info.state == WorkInfo.State.CANCELLED -> null
            else -> SnackEvent(R.string.sync_failed_message, errorMsg, isError = true)
        }
    }
}

/**
 * A simple data class to carry resource IDs to the UI layer safely.
 */
data class SnackEvent(
    @param:StringRes val messageResId: Int,
    val errorArg: String? = null,
    /** A failure the user should have time to read — shown longer and can be styled as a problem. */
    val isError: Boolean = false
)