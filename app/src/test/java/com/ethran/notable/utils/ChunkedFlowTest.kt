package com.ethran.notable.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkedFlowTest {

    @Test
    fun `items inside the window are batched, later ones start a new batch`() = runBlocking {
        val batches = flow {
            emit(1); emit(2)
            delay(50)
            emit(3)
            delay(400)
            emit(4)
        }.chunked(200).toList()
        assertEquals(listOf(listOf(1, 2, 3), listOf(4)), batches)
    }

    @Test
    fun `waiting for the window does not burn the thread`() = runBlocking {
        val cpu = java.lang.management.ManagementFactory.getThreadMXBean()
        val before = cpu.currentThreadCpuTime
        flow { emit(1); delay(500) }.chunked(400).toList()
        val usedMs = (cpu.currentThreadCpuTime - before) / 1_000_000
        assertEquals("cpu ms while waiting: $usedMs", true, usedMs < 150)
    }
}
