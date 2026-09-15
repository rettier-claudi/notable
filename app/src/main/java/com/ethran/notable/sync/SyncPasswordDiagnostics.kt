package com.ethran.notable.sync

import android.os.Process
import android.os.SystemClock
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.utils.onError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

const val SYNC_PASSWORD_EVENTS_KEY = "SYNC_PASSWORD_EVENTS"

/**
 * Persistent record of what happens to the sync password: failed Keystore decrypts, retries that
 * rescued a read. Twice the password seemed gone on the tablet with no device log to say why;
 * this keeps the evidence in the KV table (survives restarts) and publishes it to the server as
 * [SyncPaths.passwordEventsFile] after the next successful sync, where it can be read without
 * touching the device. Holds messages only, never the password.
 */
@Singleton
class SyncPasswordDiagnostics @Inject constructor(
    private val kvRepository: KvRepository
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    /** Adds [event] to the persistent log and the in-app sync log. Never throws. */
    suspend fun record(event: String) {
        SyncLogger.w(TAG, event)
        val uptimeS = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        val line = "${timeFormat.format(Date())} pid=${Process.myPid()} up=${uptimeS}s $event"
        runCatching {
            mutex.withLock { write(read().appended(line)) }
        }.onFailure { SyncLogger.w(TAG, "Could not store password event: ${it.message}") }
    }

    /** Uploads the log if it has lines the server hasn't seen. Never throws, never fails a sync. */
    suspend fun uploadIfPending(client: WebDAVClient) {
        runCatching {
            val events = mutex.withLock { read() }
            if (!events.pending) return
            val path = SyncPaths.passwordEventsFile()
            client.ensureParentDirectories(path).onError { return }
            client.putFile(path, events.text().toByteArray(), "text/plain; charset=utf-8")
                .onError {
                    SyncLogger.w(TAG, "Password event upload failed: ${it.userMessage}")
                    return
                }
            mutex.withLock { write(read().copy(uploadedThrough = events.lines.last())) }
        }.onFailure { SyncLogger.w(TAG, "Password event upload failed: ${it.message}") }
    }

    private suspend fun read(): PasswordEvents =
        kvRepository.get(SYNC_PASSWORD_EVENTS_KEY)
            ?.let { runCatching { json.decodeFromString(PasswordEvents.serializer(), it.value) }.getOrNull() }
            ?: PasswordEvents()

    private suspend fun write(events: PasswordEvents) =
        kvRepository.set(Kv(SYNC_PASSWORD_EVENTS_KEY, json.encodeToString(PasswordEvents.serializer(), events)))

    companion object {
        private const val TAG = "SyncPassword"
    }
}

@Serializable
internal data class PasswordEvents(
    val lines: List<String> = emptyList(),
    /** Last line the server has; the log is pending upload while newer lines exist. */
    val uploadedThrough: String? = null,
) {
    val pending: Boolean get() = lines.isNotEmpty() && lines.last() != uploadedThrough

    fun appended(line: String): PasswordEvents =
        copy(lines = (lines + line).takeLast(MAX_LINES))

    fun text(): String = lines.joinToString("\n", postfix = "\n")

    companion object {
        const val MAX_LINES = 200
    }
}
