package com.ethran.notable.data.db

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPasswordReadTest {

    private fun failure(n: Int) = AppResult.Error(DomainError.UnexpectedState("keystore busy $n"))

    @Test
    fun a_passing_failure_is_retried_and_reported() {
        val results = ArrayDeque(listOf(failure(1), AppResult.Success("secret")))
        val pauses = mutableListOf<Long>()
        val retries = mutableListOf<String>()

        val result = retrying(listOf(150L, 600L), pause = { pauses += it }, onRetry = { n, e -> retries += "$n $e" }) {
            results.removeFirst()
        }

        assertEquals(AppResult.Success("secret"), result)
        assertEquals(listOf(150L), pauses)
        assertEquals(listOf("1 keystore busy 1"), retries)
    }

    @Test
    fun gives_up_after_one_try_per_pause_plus_one() {
        var tries = 0
        val pauses = mutableListOf<Long>()

        val result = retrying(listOf(150L, 600L), pause = { pauses += it }) { failure(++tries) }

        assertEquals(3, tries)
        assertEquals(listOf(150L, 600L), pauses)
        assertEquals(failure(3), result)
    }

    @Test
    fun success_on_the_first_try_does_not_pause() {
        var tries = 0

        val result = retrying(listOf(150L), pause = { error("no pause expected") }) {
            tries++
            AppResult.Success("secret")
        }

        assertEquals(1, tries)
        assertEquals(AppResult.Success("secret"), result)
    }

    @Test
    fun memo_answers_only_for_the_ciphertext_it_was_filled_from() {
        val memo = PasswordMemo()
        assertNull(memo.get("BLOB-A"))

        memo.put("BLOB-A", "secret")
        assertEquals("secret", memo.get("BLOB-A"))
        // A new password is a new ciphertext: the old plaintext must not be handed out for it.
        assertNull(memo.get("BLOB-B"))

        memo.put("", "")
        assertNull(memo.get("BLOB-A"))
    }
}
