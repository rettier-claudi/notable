package com.ethran.notable.tickets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Philipp's real strokes on the bridge's task pages (Sept./Oct. 2026; 46 px boxes, 70 px rows on
 * 80 px lines — the same pen and the same kind of page as the ticket pages).
 *
 * - `ticket-haken-echt.txt.gz` (in the repo): only the strokes the bridge read as tick or
 *   strike-through, with the bridge's real layout. No handwriting — the repository is public.
 * - The full set with all handwriting lies outside the repo
 *   (`/workspace/data/notable-testdaten/ticket-gesten-echt.txt.gz` on spathi, or the path in
 *   `-Dticket.gesten.echt=…` / env `TICKET_GESTEN_ECHT`). Without it the handwriting test is
 *   skipped (and says so).
 */
class TicketGestureRealStrokesTest {

    private val ticks: Map<String, RealPage> by lazy {
        readRealPages(javaClass.classLoader!!.getResourceAsStream("ticket-haken-echt.txt.gz")!!)
    }

    private fun recognizeAll(pages: Map<String, RealPage>, layoutFor: (RealPage) -> TicketLayout, filter: (RealStroke) -> Boolean) =
        pages.values.flatMap { page ->
            val layout = layoutFor(page)
            batches(page.strokes.filter(filter)).flatMap { batch ->
                TicketGestureGeometry.recognize(batch.map { it.stroke }, layout)
            }
        }

    @Test
    fun `most of Philipp's real ticks in a box are recognised, nothing else from them`() {
        val tickStrokes = ticks.values.sumOf { p -> p.strokes.count { it.status == "haken" } }
        val gestures = recognizeAll(ticks, { it.layout() }) { true }
        val weiter = gestures.filter { it.action == GestureAction.WEITER }.flatMap { it.strokeIds }.toSet()
        val dropped = gestures.filter { it.action == GestureAction.DROPPED }
        println("real ticks: $tickStrokes strokes, ${weiter.size} as tick, ${dropped.size} as line through the row")
        // Measured 25.10.: 142 of 175 lie (almost) wholly in a box; the rest are strike-throughs, the
        // "bin dran" dots left of the box and ticks that ran far out — the bridge reads those.
        assertTrue("ticks recognised: ${weiter.size}", weiter.size >= 135)
        assertTrue("strike-through lines recognised: ${dropped.size}", dropped.size >= 6)
        assertEquals(
            "only ticks and lines",
            emptyList<TicketGesture>(),
            gestures.filter { it.action != GestureAction.WEITER && it.action != GestureAction.DROPPED },
        )
    }

    @Test
    fun `no real tick or line hits a second region`() {
        // Every gesture names one ticket: a tick at the edge between two boxes gives nothing.
        val gestures = recognizeAll(ticks, { it.layout() }) { true }
        val ids = gestures.flatMap { it.strokeIds }
        assertEquals(ids.size, ids.toSet().size)
    }

    private fun privateSet(): File? {
        val path = System.getProperty("ticket.gesten.echt") ?: System.getenv("TICKET_GESTEN_ECHT")
            ?: "/workspace/data/notable-testdaten/ticket-gesten-echt.txt.gz"
        return File(path).takeIf { it.isFile }
    }

    /**
     * The hard case: all of Philipp's handwriting on those pages, against a ticket grid laid over
     * **every** line of the page — box, traffic light, row, and a stage or belt bar at the right —
     * so writing runs over regions all the time. Only marks in a box may count (that is what they
     * are); no handwriting may become a loop, a line or a light toggle.
     */
    @Test
    fun `real handwriting over a ticket grid triggers nothing but box marks`() {
        val file = privateSet()
        assumeTrue("private handwriting set not present, skipped", file != null)
        val pages = readRealPages(file!!.inputStream())
        val handwriting: (RealStroke) -> Boolean = { it.status != "haken" }
        val count = pages.values.sumOf { p -> p.strokes.count(handwriting) }
        val gestures = recognizeAll(pages, { grid(it.id) }, handwriting)
        println("handwriting: $count strokes, gestures: ${gestures.map { "${it.action} ${it.strokeIds}" }}")
        assertTrue("enough handwriting in the set", count > 700)
        assertEquals(
            "handwriting as loop/line/light",
            emptyList<TicketGesture>(),
            gestures.filter { it.action != GestureAction.WEITER },
        )
        // A mark that sits wholly in a box is a tick by design. Measured 25.10., all looked at:
        // three crosses in real boxes the bridge had not counted (one is the first stroke of a
        // two-stroke cross, alone here because its second stroke is in the tick set), a cross at
        // the left margin, and one capital letter written at the start of a line into the box column.
        val expected = setOf(
            "e4bfb6a8-39c7a387", "72fa79a5-b0c8719b", "374f4c4c-601e7819",
            "e4bfb6a8-1d3daa2d", "26dcbf94-53870fb1",
        )
        val unexpected = gestures.filter { g -> g.strokeIds.none { it in expected } }
        assertEquals("new box marks from handwriting", emptyList<TicketGesture>(), unexpected)
    }

    /** All strokes of those pages, in the order drawn, on the bridge's real layout. */
    @Test
    fun `all real strokes on the real layout - handwriting stays handwriting`() {
        val file = privateSet()
        assumeTrue("private handwriting set not present, skipped", file != null)
        val pages = readRealPages(file!!.inputStream())
        val status = pages.values.flatMap { p -> p.strokes.map { it.stroke.id to it.status } }.toMap()
        val gestures = recognizeAll(pages, { it.layout() }) { true }
        val fromHandwriting = gestures.filter { g -> g.strokeIds.any { status[it] != "haken" } }
        println("all strokes: ${status.size}, gestures ${gestures.size}, touching handwriting: " +
            fromHandwriting.map { "${it.action} ${it.strokeIds}" })
        assertEquals(
            "only box marks may touch handwriting",
            emptyList<TicketGesture>(),
            fromHandwriting.filter { it.action != GestureAction.WEITER },
        )
        assertTrue("box marks touching handwriting: $fromHandwriting", fromHandwriting.size <= 4)
        assertTrue(gestures.count { it.action == GestureAction.WEITER } >= 135)
    }

    /** Ticket rows on every 80 px line from y 170 (the bridge's line grid); belt bars on odd pages. */
    private fun grid(pageId: String): TicketLayout {
        val belt = pageId.hashCode() % 2 != 0
        val rows = (0 until 28).map { k -> ticketRow("R$k", 170f + 80f * k, belt) }
        return TicketLayout(pageId, "grid", null, rows.flatten())
    }
}
