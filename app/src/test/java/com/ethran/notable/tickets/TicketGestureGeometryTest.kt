package com.ethran.notable.tickets

import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic strokes on a synthetic Keiko-ba page: row KD-105 on y 400, KD-76 (belt bar) on y 480. */
class TicketGestureGeometryTest {

    private val layout = layoutOf(ticketRow("KD-105", 400f), ticketRow("KD-76", 480f, belt = true))

    private fun recognize(vararg strokes: com.ethran.notable.data.db.Stroke) =
        TicketGestureGeometry.recognize(strokes.toList(), layout)

    private fun single(vararg strokes: com.ethran.notable.data.db.Stroke): TicketGesture {
        val g = recognize(*strokes)
        assertEquals("exactly one gesture: $g", 1, g.size)
        return g.single()
    }

    private fun none(message: String, vararg strokes: com.ethran.notable.data.db.Stroke) =
        assertEquals(message, emptyList<TicketGesture>(), recognize(*strokes))

    // Box of KD-105: 120..166 × 414..460.

    @Test
    fun `tick in the box moves on`() {
        val g = single(stroke(128f to 432f, 140f to 452f, 162f to 418f))
        assertEquals("KD-105", g.ticket)
        assertEquals(GestureAction.WEITER, g.action)
        assertEquals(null, g.wert)
    }

    @Test
    fun `tick that overshoots the box like Philipp's still counts`() {
        // Real ticks run 10-25 px out of the 46 px box.
        val g = single(stroke(118f to 430f, 138f to 458f, 178f to 402f))
        assertEquals(GestureAction.WEITER, g.action)
    }

    @Test
    fun `cross in two strokes is one gesture with both ids`() {
        val a = stroke(124f to 418f, 162f to 456f, id = "a")
        val b = stroke(162f to 418f, 124f to 456f, id = "b")
        val g = single(a, b)
        assertEquals(listOf("a", "b"), g.strokeIds)
    }

    @Test
    fun `red, highlighter and dots in the box do nothing`() {
        none("red", stroke(128f to 432f, 140f to 452f, 162f to 418f, color = RED))
        none("legacy red pen", stroke(128f to 432f, 140f to 452f, 162f to 418f, pen = Pen.REDBALLPEN))
        none("highlighter", stroke(128f to 432f, 140f to 452f, 162f to 418f, pen = Pen.MARKER))
        none("dot", stroke(141f to 436f, 144f to 438f, 142f to 440f))
    }

    @Test
    fun `blue tick counts, only red is the server's`() {
        assertEquals(GestureAction.WEITER, single(stroke(128f to 432f, 140f to 452f, 162f to 418f, color = BLUE)).action)
    }

    @Test
    fun `a word starting in the box is no tick`() {
        // "Mail" written from the box into the text: one cluster, mostly outside the box.
        none(
            "word",
            stroke(130f to 455f, 135f to 420f, 145f to 445f, 155f to 420f, 160f to 455f),
            stroke(175f to 440f, 190f to 432f, 200f to 455f, 210f to 438f),
            stroke(225f to 455f, 228f to 435f),
            stroke(245f to 455f, 248f to 405f),
        )
    }

    @Test
    fun `a long stroke through the box is no tick`() {
        none("slash", stroke(110f to 470f, 260f to 380f))
    }

    @Test
    fun `tick and writing in the same batch - the tick still counts`() {
        val tick = stroke(128f to 432f, 140f to 452f, 162f to 418f, id = "tick")
        val word = stroke(600f to 455f, 620f to 420f, 640f to 455f, 660f to 420f, 680f to 455f, id = "word")
        val g = single(tick, word)
        assertEquals(listOf("tick"), g.strokeIds)
    }

    // Stage bar of KD-105: symbols at x 1340 + i·64 (48 wide), y 411..459.

    @Test
    fun `loop around one stage moves there`() {
        val g = single(loop(1340f + 2 * 64f + 24f, 435f, 36f, 32f))
        assertEquals("KD-105", g.ticket)
        assertEquals(GestureAction.STAGE, g.action)
        assertEquals("🧐 審査", g.wert)
    }

    @Test
    fun `loop around a belt symbol sets the belt`() {
        val g = single(loop(1340f + 3 * 64f + 24f, 515f, 38f, 34f, turns = 1.25f, startDeg = 30f))
        assertEquals("KD-76", g.ticket)
        assertEquals(GestureAction.GUERTEL, g.action)
        assertEquals("⚔️ 型", g.wert)
    }

    @Test
    fun `loop in two halves counts too`() {
        val cx = 1340f + 24f
        val full = loop(cx, 435f, 36f, 32f, turns = 1.05f)
        val half = full.points.size / 2
        val a = strokeOf(full.points.take(half + 1).map { it.x to it.y }, id = "a")
        val b = strokeOf(full.points.drop(half).map { it.x to it.y }, id = "b")
        val g = single(a, b)
        assertEquals("📜 巻物", g.wert)
        assertEquals(listOf("a", "b"), g.strokeIds)
    }

    @Test
    fun `loops that are no clear choice do nothing`() {
        none("around two symbols", loop(1340f + 64f + 56f, 435f, 80f, 34f))
        none("open arc (C)", loop(1340f + 24f, 435f, 36f, 32f, turns = 0.7f))
        none("small o on top of the symbol", loop(1340f + 24f, 435f, 12f, 12f))
        none("red loop", loop(1340f + 24f, 435f, 36f, 32f, color = RED))
        none("huge loop round the whole bar", loop(1340f + 190f, 435f, 230f, 45f))
    }

    // ---- 25.10.2026: Philipp's circles on the real Keiko-ba page, "only one was recognised" ------
    // Real layout: cells 64 px on a 68 px pitch, rows 80 px. KD-151 on y 400, KD-152 on 480, KD-153 on 560.

    private val keiko = layoutOf(keikobaRow("KD-151", 400f), keikobaRow("KD-152", 480f), keikobaRow("KD-153", 560f))

    /** Centre of stage [i] in the row on [y]. */
    private fun cell(i: Int, y: Float) = (1336f + i * 68f + 32f) to (y + 36f)

    @Test
    fun `a loop a little off-centre that also rounds the neighbour's centre still counts`() {
        // 90 px loop, 18 px right of 🥋: its ring also runs round the centre of 🧐 (68 px away).
        val (cx, cy) = cell(1, 480f)
        val g = TicketGestureGeometry.recognize(listOf(loop(cx + 18f, cy, 45f, 42f)), keiko)
        assertEquals(1, g.size)
        assertEquals("KD-152", g.single().ticket)
        assertEquals("🥋 稽古", g.single().wert)
    }

    @Test
    fun `loops in rows one above the other, drawn in one go, each count`() {
        // Pen rest between them shorter than the debounce: one batch, and the rings touch (one cluster).
        val (x1, y1) = cell(2, 400f)
        val (x2, y2) = cell(1, 480f)
        val (x3, y3) = cell(4, 560f)
        val strokes = listOf(
            loop(x1, y1, 44f, 46f, id = "l1"),
            loop(x2 + 4f, y2 + 3f, 46f, 47f, id = "l2"),
            loop(x3, y3 - 2f, 45f, 46f, id = "l3"),
        )
        assertEquals("the first two rings touch: one cluster", 2, TicketGestureGeometry.clusters(strokes).size)
        val g = TicketGestureGeometry.recognize(strokes, keiko)
        assertEquals(
            listOf("KD-151 🧐 審査 [l1]", "KD-152 🥋 稽古 [l2]", "KD-153 ⚔️ 抜刀 [l3]"),
            g.map { "${it.ticket} ${it.wert} ${it.strokeIds}" },
        )
    }

    @Test
    fun `two loops in one batch, one of them in two halves`() {
        val (x1, y1) = cell(0, 400f)
        val (x2, y2) = cell(3, 480f)
        val full = loop(x2, y2, 44f, 44f, turns = 1.05f)
        val half = full.points.size / 2
        val a = strokeOf(full.points.take(half + 1).map { it.x to it.y }, id = "a")
        val b = strokeOf(full.points.drop(half).map { it.x to it.y }, id = "b")
        val g = TicketGestureGeometry.recognize(listOf(loop(x1, y1, 44f, 46f, id = "l1"), a, b), keiko)
        assertEquals(
            listOf("KD-151 📜 巻物 [l1]", "KD-152 🧘 構え [a, b]"),
            g.map { "${it.ticket} ${it.wert} ${it.strokeIds}" },
        )
    }

    @Test
    fun `a batch of loops with writing in it stays the server's`() {
        val (x1, y1) = cell(2, 400f)
        val (x2, y2) = cell(1, 480f)
        // A word written right through both rings: the cluster is not only loops.
        val word = stroke((x1 - 60f) to (y1 + 30f), (x1 - 20f) to (y2 - 10f), x2 to (y2 + 20f), (x2 + 60f) to (y1 + 20f))
        val g = TicketGestureGeometry.recognize(listOf(loop(x1, y1, 44f, 46f), word, loop(x2, y2, 46f, 47f)), keiko)
        assertTrue("nothing: $g", g.isEmpty())
    }

    @Test
    fun `a loop right between two cells is still no choice`() {
        val (cx, cy) = cell(2, 480f)
        none2("between 🧐 and 🧘", loop(cx + 34f, cy, 60f, 44f))
        none2("between two rows", loop(cx, cy + 40f, 44f, 60f))
    }

    private fun none2(message: String, vararg strokes: com.ethran.notable.data.db.Stroke) =
        assertEquals(message, emptyList<TicketGesture>(), TicketGestureGeometry.recognize(strokes.toList(), keiko))

    @Test
    fun `scribbling over a symbol is no loop`() {
        // Passes through the symbol's centre.
        val pts = (0..60).map { i ->
            val t = i / 60f * 4 * Math.PI.toFloat()
            (1364f + 30f * kotlin.math.cos(t)) to (435f + 25f * kotlin.math.sin(2 * t))
        }
        none("figure eight", strokeOf(pts))
    }

    // Row of KD-105: 100..1740 × 400..470.

    @Test
    fun `black line through the row drops the ticket`() {
        val g = single(stroke(110f to 440f, 1300f to 432f))
        assertEquals("KD-105", g.ticket)
        assertEquals(GestureAction.DROPPED, g.action)
    }

    @Test
    fun `line from the line tool counts as well`() {
        val pts = (0 until 100).map { i -> (200f + 14f * i) to 437f }
        assertEquals(GestureAction.DROPPED, single(strokeOf(pts)).action)
    }

    @Test
    fun `lines that are no drop do nothing`() {
        none("red line (delete, server only)", stroke(110f to 440f, 1300f to 432f, color = RED))
        none("blue line", stroke(110f to 440f, 1300f to 432f, color = BLUE))
        none("too short (40 %)", stroke(200f to 440f, 860f to 436f))
        none("underline on the bottom line", stroke(110f to 468f, 1300f to 466f))
        none("wavy", strokeOf(densify((0..40).map { i -> (110f + 30f * i) to (437f + if (i % 2 == 0) -14f else 14f) })))
        none("slanted across two rows", stroke(110f to 405f, 1300f to 540f))
    }

    // Traffic light of KD-105: 186..222 × 419..455.

    @Test
    fun `short stroke through the traffic light toggles it`() {
        val g = single(stroke(182f to 460f, 226f to 414f))
        assertEquals("KD-105", g.ticket)
        assertEquals(GestureAction.AMPEL, g.action)
    }

    @Test
    fun `strokes near the light that are not a toggle do nothing`() {
        none("long stroke into the text", stroke(182f to 460f, 330f to 420f))
        none("dot on the light", stroke(203f to 436f, 205f to 438f))
        none("letter with loops on the light", loop(204f, 437f, 14f, 14f, turns = 2.2f))
        none("red stroke", stroke(182f to 460f, 226f to 414f, color = RED))
    }

    @Test
    fun `handwriting across the whole row does nothing`() {
        // A sentence written across the row: a zigzag of letters over box, light, text and stage bar.
        val letters = (0 until 60).map { i ->
            val x = 112f + i * 27f
            stroke(x to 455f, x + 6f to 412f, x + 12f to 450f, x + 18f to 418f, x + 22f to 455f)
        }
        val g = recognize(*letters.toTypedArray())
        assertTrue("nothing from writing: $g", g.isEmpty())
    }

    @Test
    fun `colour buckets agree with the bridge`() {
        assertTrue(TicketGestureGeometry.isRed(RED))
        assertTrue(TicketGestureGeometry.isRed(0xFFE53935.toInt()))
        assertTrue(!TicketGestureGeometry.isRed(BLACK))
        assertTrue("magenta counts as red at the bridge too", TicketGestureGeometry.isRed(0xFFFF00FF.toInt()))
        assertTrue(!TicketGestureGeometry.isRed(0xFF00A000.toInt()))
        assertTrue(TicketGestureGeometry.isBlack(BLACK))
        assertTrue(TicketGestureGeometry.isBlack(0xFF404040.toInt()))
        assertTrue(!TicketGestureGeometry.isBlack(BLUE))
    }
}
