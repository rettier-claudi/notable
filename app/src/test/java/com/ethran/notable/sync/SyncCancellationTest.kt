package com.ethran.notable.sync

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SyncCancellationTest {

    // Accepts connections and never answers: a server behind a dead uplink.
    private val silentServer = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            val held = mutableListOf<java.net.Socket>()
            try {
                while (true) held += server.accept()
            } catch (_: IOException) {
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .eventListenerFactory(SyncCancellation.eventListenerFactory)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request() = Request.Builder().url("http://127.0.0.1:${silentServer.localPort}/").build()

    @After
    fun tearDown() {
        silentServer.close()
        SyncCancellation.beginRound()
    }

    @Test
    fun `cancel cuts a call blocked in a read`() {
        SyncCancellation.beginRound()
        val failed = CountDownLatch(1)
        thread {
            try {
                client.newCall(request()).execute().close()
            } catch (_: IOException) {
                failed.countDown()
            }
        }
        Thread.sleep(300) // let it connect and block
        val start = System.nanoTime()
        SyncCancellation.cancelRunning()
        assertTrue("call still blocked", failed.await(3, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3))
    }

    @Test
    fun `calls started after cancel fail at once until the next round`() {
        SyncCancellation.cancelRunning()
        try {
            client.newCall(request()).execute().close()
            fail("expected cancellation")
        } catch (_: IOException) {
        }
        SyncCancellation.beginRound()
        assertTrue(!SyncCancellation.cancelRequested)
    }

    @Test
    fun `dns lookup is bounded`() {
        val hanging = Dns { Thread.sleep(10_000); listOf(InetAddress.getLoopbackAddress()) }
        val start = System.nanoTime()
        try {
            TimeoutDns(200, hanging).lookup("example.invalid")
            fail("expected timeout")
        } catch (_: UnknownHostException) {
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
    }
}
