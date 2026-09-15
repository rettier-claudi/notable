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
        val settings = kvRepository.get(SYNC_SETTINGS_KEY)
            ?.let { json.decodeFromString(SyncSettings.serializer(), it.value) }
            ?: return@withContext SyncSettings()

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

    suspend fun setSyncSettings(value: SyncSettings) {
        val encryptedPassword = when (val encrypted = cryptoHelper.encrypt(value.password)) {
            is AppResult.Success -> encrypted.data
            is AppResult.Error -> {
                throw IllegalStateException("Unable to encrypt sync password: ${encrypted.error.userMessage}")
            }
        }

        setKv(
            SYNC_SETTINGS_KEY,
            value.copy(password = encryptedPassword),
            SyncSettings.serializer()
        )
        passwordMemo.put(encryptedPassword, value.password)
        syncPasswordUnreadable = false
    }

    /**
     * Read-modify-write of the sync settings that leaves the stored password untouched.
     *
     * [getSyncSettings] returns an empty password when the Keystore fails to decrypt it, and writing
     * that result back through [setSyncSettings] would persist the empty password for good. This
     * works on the stored record instead and writes the encrypted password back as it was.
     */
    suspend fun updateSyncSettingsKeepingPassword(transform: (SyncSettings) -> SyncSettings) =
        withContext(Dispatchers.IO) {
            val stored = kvRepository.get(SYNC_SETTINGS_KEY)
                ?.let { json.decodeFromString(SyncSettings.serializer(), it.value) }
                ?: SyncSettings()
            setKv(SYNC_SETTINGS_KEY, keepStoredPassword(stored, transform), SyncSettings.serializer())
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

/**
 * Applies [transform] to [stored] without letting it touch the password: [transform] sees an empty
 * password, and the stored (encrypted) value is put back afterwards.
 */
internal fun keepStoredPassword(
    stored: SyncSettings,
    transform: (SyncSettings) -> SyncSettings
): SyncSettings = transform(stored.copy(password = "")).copy(password = stored.password)

private const val UNREADABLE_RECORD_GAP_MS = 10 * 60_000L
