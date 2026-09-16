package com.ethran.notable.utils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull


// Helper function to achieve time-based chunking
fun <T> Flow<T>.chunked(timeoutMillisSelector: Long): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    coroutineScope {
        val channel = produceIn(this)
        while (true) {
            val start = System.currentTimeMillis()
            val received = channel.receiveCatching().getOrNull() ?: break
            buffer.add(received)

            // Fork: suspend until the window closes. Upstream polled tryReceive() in a tight loop,
            // spinning a core at 100 % for the whole window (1 s after every page save).
            while (true) {
                val remaining = timeoutMillisSelector - (System.currentTimeMillis() - start)
                if (remaining <= 0) break
                val next = withTimeoutOrNull(remaining) { channel.receiveCatching() } ?: break
                buffer.add(next.getOrNull() ?: break)
            }
            emit(buffer.toList())
            buffer.clear()
        }
    }
}

fun <T : Any> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = flow {
    var prev: T? = null
    this@withPrevious.collect {
        emit(prev to it)
        prev = it
    }
}