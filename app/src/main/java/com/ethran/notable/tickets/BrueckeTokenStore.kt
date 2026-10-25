package com.ethran.notable.tickets

import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.utils.AppResult
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

const val BRUECKE_TOKEN_KEY = "BRUECKE_TOKEN"

/**
 * The bridge token (`X-Bruecke-Token`), kept like the sync password: encrypted with the app's
 * Keystore key, in its own KV row so it never passes through the sync settings (whose save path
 * guards the password, see `KvProxy.updateSyncSettings`). Decrypted once per process and
 * ciphertext; a failed decrypt reads as "no token" and is never written back over the stored one.
 */
@Singleton
class BrueckeTokenStore @Inject constructor(
    private val kvRepository: KvRepository,
    private val cryptoHelper: CryptoHelper,
) {
    private val log = ShipBook.getLogger("BrueckeToken")

    @Volatile
    private var memo: Pair<String, String>? = null

    /** The token, or "" when none is stored or it cannot be read right now. */
    suspend fun get(): String = withContext(Dispatchers.IO) {
        val stored = runCatching { kvRepository.get(BRUECKE_TOKEN_KEY)?.value }.getOrNull()
        if (stored.isNullOrBlank()) return@withContext ""
        memo?.takeIf { it.first == stored }?.let { return@withContext it.second }
        when (val r = cryptoHelper.decrypt(stored)) {
            is AppResult.Success -> r.data.also { memo = stored to it }
            is AppResult.Error -> {
                log.w("Bridge token unreadable: ${r.error.userMessage}")
                ""
            }
        }
    }

    suspend fun isSaved(): Boolean = withContext(Dispatchers.IO) {
        runCatching { !kvRepository.get(BRUECKE_TOKEN_KEY)?.value.isNullOrBlank() }.getOrDefault(false)
    }

    /** Stores [token] (trimmed); blank removes it. Throws if it cannot be encrypted. */
    suspend fun set(token: String) = withContext(Dispatchers.IO) {
        val plain = token.trim()
        if (plain.isEmpty()) {
            kvRepository.delete(BRUECKE_TOKEN_KEY)
            memo = null
            return@withContext
        }
        val cipher = when (val r = cryptoHelper.encrypt(plain)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> throw IllegalStateException("Unable to encrypt bridge token: ${r.error.userMessage}")
        }
        kvRepository.set(Kv(BRUECKE_TOKEN_KEY, cipher))
        memo = cipher to plain
    }
}
