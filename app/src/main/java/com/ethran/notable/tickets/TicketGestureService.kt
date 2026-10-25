package com.ethran.notable.tickets

import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.di.ApplicationScope
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

const val TICKET_GESTURE_QUEUE_KEY = "TICKET_GESTURE_QUEUE"

/**
 * Ticket pages on the tablet (spec tablet-bruecke `docs/ticket-seiten.md`, "Ablauf Client"):
 *
 * 1. opening a page fetches its gesture layout (`GET /gesten/<pageId>`; 404 = ordinary page,
 *    any error = nothing happens);
 * 2. after the pen has rested for [DEBOUNCE_MS], the strokes drawn since are checked for the
 *    unambiguous gestures ([TicketGestureGeometry]);
 * 3. each gesture goes to `POST /geste` with its stroke ids; the answer is drawn small and grey
 *    next to the row ([TicketLabels]). Offline or failing: it waits in a queue (KV, survives a
 *    restart) and is sent again later — harmless, the bridge is idempotent over the stroke ids.
 *
 * Without a bridge URL and token in the settings nothing of this runs.
 */
@Singleton
class TicketGestureService @Inject constructor(
    private val kvProxy: KvProxy,
    private val tokenStore: BrueckeTokenStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val log = ShipBook.getLogger("TicketGestures")
    private val client = BrueckeClient()

    /** Layouts of the ticket pages seen in this process. Absent = ordinary page or not known. */
    private val layouts = ConcurrentHashMap<String, TicketLayout>()

    private val pendingStrokes = ConcurrentHashMap<String, MutableList<Stroke>>()
    private val debounceJobs = ConcurrentHashMap<String, Job>()

    /** Stroke ids already turned into a gesture in this process (never twice). */
    private val handled = ConcurrentHashMap.newKeySet<String>()
    private val recentTicks = RecentTicks()

    private val queueLock = Mutex()
    private var queue: MutableList<PendingGesture>? = null
    private val flushLock = Mutex()
    @Volatile
    private var flushAgain = false
    private var retryJob: Job? = null
    private var retryStep = 0

    private data class Config(val url: String, val token: String)

    private suspend fun config(): Config? {
        val url = runCatching { kvProxy.getSyncSettings().brueckeUrl.trim() }.getOrDefault("")
        if (url.isEmpty()) return null
        val token = tokenStore.get()
        if (token.isEmpty()) return null
        return Config(url, token)
    }

    /** Page opened: fetch (or refresh) its layout, and send whatever still waits. */
    fun onPageOpened(pageId: String) {
        if (pageId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val cfg = config() ?: return@launch
            when (val r = client.getLayout(cfg.url, cfg.token, pageId)) {
                is LayoutResult.Ticket -> {
                    layouts[pageId] = r.layout
                    log.i("Ticket page $pageId: ${r.layout.regionen.size} regions (${r.layout.art}, ${r.layout.stand})")
                }
                LayoutResult.NoTicketPage -> layouts.remove(pageId)
                is LayoutResult.Failed -> log.i("No layout for $pageId: ${r.reason}")
            }
            flush()
        }
    }

    fun isTicketPage(pageId: String) = layouts.containsKey(pageId)

    /**
     * A stroke was drawn on [pageId]. [lookup] answers which of the given stroke ids still exist
     * (in their current form) when the pen has rested — an undone or erased stroke is no gesture.
     */
    fun onStrokeDrawn(pageId: String, stroke: Stroke, lookup: (List<String>) -> List<Stroke?>) {
        if (!layouts.containsKey(pageId)) return
        synchronized(pendingStrokes) {
            pendingStrokes.getOrPut(pageId) { mutableListOf() }.add(stroke)
            debounceJobs[pageId]?.cancel()
            debounceJobs[pageId] = scope.launch(Dispatchers.Default) {
                delay(DEBOUNCE_MS)
                val batch = synchronized(pendingStrokes) {
                    debounceJobs.remove(pageId)
                    pendingStrokes.remove(pageId).orEmpty()
                }
                evaluate(pageId, batch, lookup)
            }
        }
    }

    private suspend fun evaluate(pageId: String, batch: List<Stroke>, lookup: (List<String>) -> List<Stroke?>) {
        val layout = layouts[pageId] ?: return
        val ids = batch.map { it.id }.filterNot { it in handled }
        if (ids.isEmpty()) return
        val present = runCatching { lookup(ids).filterNotNull() }.getOrElse { emptyList() }
        if (present.isNotEmpty()) reportActivity(pageId, present)
        val gestures = TicketGestureGeometry.recognize(present, layout)
        if (gestures.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = gestures.filter { g ->
            if (g.strokeIds.any { it in handled }) return@filter false
            if (g.action == GestureAction.WEITER && !recentTicks.accept(pageId, g.region, now)) {
                // A tick drawn in two goes (pause longer than the debounce) must not move twice.
                log.i("Second tick in the same box of ${g.ticket} within ${RecentTicks.WINDOW_MS} ms, left to the bridge")
                return@filter false
            }
            true
        }
        if (fresh.isEmpty()) return
        val items = fresh.map { g ->
            handled.addAll(g.strokeIds)
            val anchor = layout.rowOf(g.ticket) ?: g.region.box
            TicketLabels.set(pageId, g.ticket, anchor, SENDING)
            PendingGesture(
                pageId = pageId,
                strokeIds = g.strokeIds,
                ticket = g.ticket,
                aktion = g.action.wire,
                wert = g.wert,
                anchor = listOf(anchor.x1, anchor.y1, anchor.x2, anchor.y2),
                createdAt = now,
            )
        }
        log.i("Gestures on $pageId: ${items.joinToString { "${it.ticket} ${it.aktion} ${it.wert ?: ""}" }}")
        withQueue { q -> items.forEach { item -> if (q.none { it.key == item.key }) q.add(item) } }
        flush()
    }

    /** Tells the bridge that this page is being written on (it must not replace it meanwhile). */
    private fun reportActivity(pageId: String, strokes: List<Stroke>) {
        scope.launch(Dispatchers.IO) {
            val cfg = config() ?: return@launch
            if (!client.postActivity(cfg.url, cfg.token, pageId, strokes)) {
                log.i("Activity on $pageId not delivered (bridge offline or older than 25.10.)")
            }
        }
    }

    /** Sends the queue in order; stops at the first item that has to wait (offline, bad token). */
    suspend fun flush() {
        if (!flushLock.tryLock()) {
            // A flush is running; it may already have passed the end of the queue.
            flushAgain = true
            return
        }
        try {
            flushAgain = false
            val cfg = config() ?: return
            while (true) {
                val item = withQueue { it.firstOrNull() } ?: break
                when (val r = client.postGesture(cfg.url, cfg.token, item)) {
                    is PostResult.Answered -> {
                        TicketLabels.set(item.pageId, item.ticket, item.anchorBox(), r.anzeige)
                        withQueue { q -> q.removeAll { it.key == item.key } }
                        retryStep = 0
                    }
                    PostResult.AuthFailed -> {
                        log.w("Bridge refused the token")
                        TicketLabels.set(item.pageId, item.ticket, item.anchorBox(), "✗ Token")
                        scheduleRetry()
                        break
                    }
                    is PostResult.Retry -> {
                        log.i("Gesture for ${item.ticket} waits: ${r.reason}")
                        TicketLabels.set(item.pageId, item.ticket, item.anchorBox(), WAITING)
                        scheduleRetry()
                        break
                    }
                }
            }
        } finally {
            flushLock.unlock()
        }
        if (flushAgain && retryJob?.isActive != true) flush()
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = RETRY_MS[retryStep.coerceAtMost(RETRY_MS.size - 1)]
        retryStep++
        retryJob = scope.launch(Dispatchers.IO) {
            delay(wait)
            flush()
        }
    }

    private suspend fun <T> withQueue(block: (MutableList<PendingGesture>) -> T): T = queueLock.withLock {
        val q = queue ?: loadQueue().also { queue = it }
        val before = q.toList()
        val result = block(q)
        if (q != before) {
            runCatching { kvProxy.setKv(TICKET_GESTURE_QUEUE_KEY, q.toList(), ListSerializer(PendingGesture.serializer())) }
                .onFailure { log.w("Could not save the gesture queue: ${it.message}") }
        }
        result
    }

    private suspend fun loadQueue(): MutableList<PendingGesture> {
        val stored = runCatching {
            kvProxy.get(TICKET_GESTURE_QUEUE_KEY, ListSerializer(PendingGesture.serializer()))
        }.getOrNull().orEmpty()
        stored.forEach {
            handled.addAll(it.strokeIds)
            TicketLabels.set(it.pageId, it.ticket, it.anchorBox(), WAITING)
        }
        return stored.toMutableList()
    }

    companion object {
        /** Pen rest before the strokes are read: a two-stroke cross comes in well under it. */
        const val DEBOUNCE_MS = 1000L
        const val SENDING = "…"
        const val WAITING = "⟳"
        private val RETRY_MS = longArrayOf(30_000, 60_000, 120_000, 300_000, 900_000)
    }
}

/**
 * A second tick in the same box shortly after the first is the second half of the same mark
 * (drawn with a longer pause), not "one more stage".
 */
internal class RecentTicks {
    private val last = ConcurrentHashMap<String, Long>()

    fun accept(pageId: String, region: Region, now: Long): Boolean {
        val key = "$pageId|${region.ticket}|${region.box}"
        val prev = last[key]
        last[key] = now
        return prev == null || now - prev > WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 5_000L
    }
}
