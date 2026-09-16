package com.ethran.notable.sync

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fork: lets the user cut a hanging sync short — tapping the sync button while a round runs.
 *
 * The WebDAV client uses blocking `Call.execute()`, which a coroutine cancel does not interrupt, so
 * cancelling the worker alone left the round stuck in a socket read. Instead every call of the sync
 * [okhttp3.OkHttpClient] registers here ([eventListenerFactory]); [cancelRunning] cancels the calls
 * in flight and every call started afterwards in the same round, so the round fails through its
 * normal error path within moments. The next round clears the flag ([beginRound]).
 *
 * Nothing is left half-written by this: downloads go to a `.part` file and are renamed only when
 * complete, and every upload is a single PUT whose success is recorded only after the server
 * answered, so a cancelled PUT is simply uploaded again next round.
 */
object SyncCancellation {
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun beginRound() {
        cancelRequested = false
    }

    /** Cancels the running round's network calls. */
    fun cancelRunning() {
        cancelRequested = true
        calls.forEach { it.cancel() }
    }

    val eventListenerFactory = EventListener.Factory {
        object : EventListener() {
            override fun callStart(call: Call) {
                calls += call
                if (cancelRequested) call.cancel()
            }

            override fun callEnd(call: Call) {
                calls -= call
            }

            override fun callFailed(call: Call, ioe: IOException) {
                calls -= call
            }
        }
    }
}

/**
 * DNS with an upper bound. OkHttp has no DNS timeout of its own, and on a Wi-Fi without a working
 * uplink the system resolver can block for a long time before the connect timeout even starts.
 */
class TimeoutDns(
    private val timeoutMs: Long,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val task = FutureTask { delegate.lookup(hostname) }
        Thread(task, "sync-dns").apply { isDaemon = true }.start()
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw UnknownHostException("DNS lookup for $hostname timed out after $timeoutMs ms")
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw cause as? UnknownHostException
                ?: UnknownHostException("DNS lookup for $hostname failed: ${cause?.message}").apply { initCause(cause) }
        }
    }
}
