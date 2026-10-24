package com.ethran.notable.data.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.sync.SYNC_SERVER_CAPABILITIES_KEY
import com.ethran.notable.sync.SYNC_SETTINGS_KEY
import com.ethran.notable.sync.ServerCapabilities
import com.ethran.notable.sync.SyncPasswordDiagnostics
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.hasFilePermission
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton


@Entity
data class Kv(
    @PrimaryKey
    val key: String,
    val value: String
)

// DAO
@Dao
interface KvDao {
    @Query("SELECT * FROM kv WHERE `key`=:key")
    suspend fun get(key: String): Kv?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(kv: Kv)

    @Query("DELETE FROM kv WHERE `key`=:key")
    suspend fun delete(key: String)

}

@Singleton
class KvRepository @Inject constructor(
    private val db: KvDao,
    @param:ApplicationContext private val context: Context
) {

    private fun checkPermission() {
        if (!hasFilePermission(context)) {
            throw IllegalStateException("Storage permission not granted or DB not accessible.")
        }
    }

    suspend fun get(key: String): Kv? = withContext(Dispatchers.IO) {
        checkPermission()
        db.get(key)
    }

    suspend fun set(kv: Kv) = withContext(Dispatchers.IO) {
        checkPermission()
        db.set(kv)
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        checkPermission()
        db.delete(key)
    }

}


/**
 * A high-level proxy for the Key-Value database.
 *
 * This class handles:
 * 1. Serialization: Automatically converts Kotlin objects (like [AppSettings]) to/from JSON strings.
 * 2. State Management: Syncs database updates with [GlobalAppSettings] for immediate UI feedback.
 * 3. Reactive updates: Provides [LiveData] streams for specific keys.
 *
 * Use this class instead of [KvRepository] for app-level data like settings and UI states.
 */
@Singleton
class KvProxy @Inject constructor(
    private val kvRepository: KvRepository,
    private val cryptoHelper: CryptoHelper,
    private val passwordDiagnostics: SyncPasswordDiagnostics
) {
    private val log = ShipBook.getLogger("KvProxy")
    private val syncSettingsMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }


    suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val kv = kvRepository.get(key)
            ?: return@withContext null //returns null when there is no database
        val jsonValue = kv.value
        json.decodeFromString(serializer, jsonValue)
    }

    /**
     * Fault-isolated read: on a decode failure, back up the corrupt JSON to a
     * `<key>_corrupt_<ts>` row (never silently discard user config) and return [default] instead of
     * throwing. A single unreadable key must never block startup — the `AppSettings` load routes
     * through here so one bad value can't brick the app.
     */
    suspend fun <T> getOrDefault(
        key: String,
        serializer: KSerializer<T>,
        default: T
    ): T = withContext(Dispatchers.IO) {
        val kv = kvRepository.get(key) ?: return@withContext default
        try {
            json.decodeFromString(serializer, kv.value)
        } catch (e: Exception) {
            log.e("Failed to decode '$key'; backing up corrupt value and using default", e)
            runCatching {
                kvRepository.set(Kv("${key}_corrupt_${System.currentTimeMillis()}", kv.value))
            }.onFailure { log.w("Failed to back up corrupt '$key'", it) }
            default
        }
    }


    suspend fun <T> setKv(key: String, value: T, serializer: KSerializer<T>) {
        val jsonValue = json.encodeToString(serializer, value)
        log.i("Setting $key to $value")
        kvRepository.set(Kv(key, jsonValue))
    }

    suspend fun setAppSettings(value: AppSettings) {
        setKv(APP_SETTINGS_KEY, value, AppSettings.serializer())
        GlobalAppSettings.update(value)
    }


    // Helper functions that handle sync settings, as it needs to be decrypted and encrypted

    // The decrypted password of this process, keyed by the stored ciphertext it came from. The
    // settings are read on every touch pause (activity pulse), chip update and sync step; going to
    // the Keystore once per process instead takes it off that path, so a passing Keystore failure
    // can no longer blank the password of a running app. A changed password is a new ciphertext
    // and misses.
    private val passwordMemo = PasswordMemo()

    /** The last read of the sync settings found a stored password it could not decrypt. */
    @Volatile
    var syncPasswordUnreadable: Boolean = false
        private set

    @Volatile
    private var lastUnreadableRecordAt = 0L

    suspend fun getSyncSettings(): SyncSettings = withContext(Dispatchers.IO) {
        val settings = readStoredSyncSettings()
        if (settings.password.isBlank()) {
            syncPasswordUnreadable = false
            return@withContext settings
        }
        passwordMemo.get(settings.password)?.let {
            return@withContext settings.copy(password = it)
        }

        val failedAttempts = mutableListOf<String>()
        val decrypted = cryptoHelper.decrypt(settings.password) { attempt, error ->
            failedAttempts += "#$attempt $error"
        }
        when (decrypted) {
            is AppResult.Success -> {
                passwordMemo.put(settings.password, decrypted.data)
                syncPasswordUnreadable = false
                if (failedAttempts.isNotEmpty()) {
                    passwordDiagnostics.record(
                        "Decrypt succeeded after ${failedAttempts.size} failed attempt(s): " +
                            failedAttempts.joinToString("; ")
                    )
                }
                settings.copy(password = decrypted.data)
            }
            is AppResult.Error -> {
                log.w("Failed to decrypt sync password: ${decrypted.error.userMessage}")
                // Recorded when it starts and then every few minutes while it lasts, not on every
                // read: the settings are read on each touch pause.
                val now = System.currentTimeMillis()
                if (!syncPasswordUnreadable || now - lastUnreadableRecordAt > UNREADABLE_RECORD_GAP_MS) {
                    lastUnreadableRecordAt = now
                    passwordDiagnostics.record(
                        "Password unreadable, sync skipped: " +
                            (failedAttempts + "last ${decrypted.error.userMessage}").joinToString("; ")
                    )
                }
                syncPasswordUnreadable = true
                settings.copy(password = "")
            }
        }
    }

    /**
     * The only write path for sync settings: an atomic read-modify-write of the stored record.
     *
     * [transform] never sees the password. The stored encrypted password is kept as it is, without
     * a decrypt/re-encrypt round trip, unless [newPassword] is given, in which case that is
     * encrypted and stored instead; an empty [newPassword] clears it. Round-tripping through
     * [getSyncSettings] would lose the password, because it reads as empty whenever the Keystore
     * fails to decrypt it.
     *
     * @throws IllegalStateException if [newPassword] cannot be encrypted; nothing is written then.
     */
    suspend fun updateSyncSettings(
        newPassword: String? = null,
        transform: (SyncSettings) -> SyncSettings
    ) = withContext(Dispatchers.IO) {
        val encryptedNewPassword = newPassword?.let { encryptSyncPassword(it) }
        syncSettingsMutex.withLock {
            setKv(
                SYNC_SETTINGS_KEY,
                applySyncSettingsUpdate(readStoredSyncSettings(), encryptedNewPassword, transform),
                SyncSettings.serializer()
            )
        }
        // Fork: the new password is known in plain text right here, so no Keystore read is needed
        // for it later (see passwordMemo).
        if (newPassword != null && encryptedNewPassword != null) {
            passwordMemo.put(encryptedNewPassword, newPassword)
            syncPasswordUnreadable = false
        }
    }

    /** The record as stored, password still encrypted. */
    private suspend fun readStoredSyncSettings(): SyncSettings =
        kvRepository.get(SYNC_SETTINGS_KEY)
            ?.let { json.decodeFromString(SyncSettings.serializer(), it.value) }
            ?: SyncSettings()

    private fun encryptSyncPassword(password: String): String =
        when (val encrypted = cryptoHelper.encrypt(password)) {
            is AppResult.Success -> encrypted.data
            is AppResult.Error ->
                throw IllegalStateException("Unable to encrypt sync password: ${encrypted.error.userMessage}")
        }

    // Measured server capabilities. A separate KV entry, not a field on SyncSettings: it is a fact
    // about the server, not a user preference, and callers must check its serverKey before trusting
    // it (a record for a different server is stale). getOrDefault(null) can't express "absent", so
    // reads go through get() and a decode failure surfaces rather than silently masking a bad row.

    suspend fun getServerCapabilities(): ServerCapabilities? =
        get(SYNC_SERVER_CAPABILITIES_KEY, ServerCapabilities.serializer())

    suspend fun setServerCapabilities(value: ServerCapabilities) =
        setKv(SYNC_SERVER_CAPABILITIES_KEY, value, ServerCapabilities.serializer())

}

/** One decrypted password, valid only for the exact ciphertext it was decrypted from. */
internal class PasswordMemo {
    @Volatile
    private var entry: Pair<String, String>? = null

    fun get(cipherText: String): String? = entry?.takeIf { it.first == cipherText }?.second

    fun put(cipherText: String, plainText: String) {
        entry = if (cipherText.isBlank()) null else cipherText to plainText
    }
}

private const val UNREADABLE_RECORD_GAP_MS = 10 * 60_000L

/**
 * Applies [transform] to [stored] with the password hidden from it: [transform] sees an empty
 * password, and the result carries [encryptedNewPassword], or the stored encrypted password if null.
 */
internal fun applySyncSettingsUpdate(
    stored: SyncSettings,
    encryptedNewPassword: String?,
    transform: (SyncSettings) -> SyncSettings
): SyncSettings = transform(stored.copy(password = ""))
    .copy(password = encryptedNewPassword ?: stored.password)
