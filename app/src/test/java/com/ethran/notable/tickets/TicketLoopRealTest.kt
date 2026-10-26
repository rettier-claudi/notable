package com.ethran.notable.tickets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Philipp's real loops (25.10.2026: on the new Keiko-ba page "only one was recognised").
 *
 * The strokes from that page never reached the server — the bridge replaced the page while he was
 * still drawing on it, and the next sync deleted the notebook on the tablet. So these are his real
 * circles from his notes (eight circled numbers, 61–163 px), placed on the real Keiko-ba layout of
 * that morning. Private: `/workspace/data/notable-testdaten/ticket-kreise-echt.txt.gz` (or
 * `-Dticket.kreise.echt=…` / env `TICKET_KREISE_ECHT`); without it the tests are skipped.
 *
 * `ticket-kreise-server.txt.gz` next to it holds the bridge's verdict (`tickets.kreis`) for a grid
 * of placements: client and server must judge alike.
 */
class TicketLoopRealTest {

    private class Fixture(val layout: TicketLayout, val loops: Map<String, List<Triple<Float, Float, Int>>>)

    private fun file(prop: String, env: String, name: String): File? {
        val path = System.getProperty(prop) ?: System.getenv(env) ?: "/workspace/data/notable-testdaten/$name"
        return File(path).takeIf { it.isFile }
    }

    private val fixture: Fixture? by lazy {
        val f = file("ticket.kreise.echt", "TICKET_KREISE_ECHT", "ticket-kreise-echt.txt.gz") ?: return@lazy null
        val regions = mutableListOf<Region>()
        val loops = linkedMapOf<String, List<Triple<Float, Float, Int>>>()
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split("|")
                when (p[0]) {
                    "L" -> {
                        val (a, b, c, d) = p[3].split(",").map { it.toFloat() }
                        regions += Region(p[1], RegionArt.of(p[2])!!, Box.of(a, b, c, d), p[4].ifEmpty { null }, p[5] == "1")
                    }
                    "K" -> loops[p[1]] = p[2].split(" ").map {
                        val (x, y, dt) = it.split(",")
                        Triple(x.toFloat(), y.toFloat(), dt.toInt())
                    }
                }
            }
        }
        Fixture(TicketLayout("9e48000e", "keikoba", null, regions), loops)
    }

    private fun placed(f: Fixture, name: String, cell: Region, dx: Float, dy: Float, id: String = name) =
        strokeOf(f.loops.getValue(name).map { (x, y, _) -> (x + cell.box.cx + dx) to (y + cell.box.cy + dy) }, id = id)

    private fun cells(f: Fixture) = f.layout.regionen.filter { it.art == RegionArt.STAGE }

    /** Does the loop cut through the printed emoji (44 px in the middle of the cell)? Then it is not "around". */
    private fun cutsEmoji(s: com.ethran.notable.data.db.Stroke, cell: Region) =
        s.points.any { abs(it.x - cell.box.cx) < 22f && abs(it.y - cell.box.cy) < 22f }

    @Test
    fun `every real loop around a stage is recognised, nothing wrong`() {
        val f = fixture
        assumeTrue("private loop set not present, skipped", f != null)
        var all = 0
        var right = 0
        val wrong = mutableListOf<String>()
        val missed = mutableMapOf<String, Int>()
        for (name in f!!.loops.keys) for (cell in cells(f).filter { it.ticket == "KD-153" }) {
            for (dx in -24..24 step 6) for (dy in -12..12 step 6) {
                val s = placed(f, name, cell, dx.toFloat(), dy.toFloat())
                if (cutsEmoji(s, cell)) continue
                all++
                val g = TicketGestureGeometry.recognize(listOf(s), f.layout)
                when {
                    g.size == 1 && g[0].region == cell -> right++
                    g.isEmpty() -> missed.merge(name, 1, Int::plus)
                    else -> wrong += "$name ${cell.wert} $dx/$dy → ${g.map { "${it.ticket} ${it.wert}" }}"
                }
            }
        }
        println("real loops: $right of $all recognised; missed per loop: $missed")
        assertEquals("never the wrong symbol", emptyList<String>(), wrong)
        // 25.10. measured: 100 % up to a 24 px offset, except the one huge loop (163 × 198 px, 2.5 cells
        // wide) far off-centre — that one rounds three centres and stays undecided.
        assertTrue("recognised: $right of $all", right >= all * 95 / 100)
    }

    @Test
    fun `real loops drawn in one go in rows one above the other each count`() {
        val f = fixture
        assumeTrue("private loop set not present, skipped", f != null)
        // KD-151, KD-152, KD-153 are adjacent rows (80 px). Philipp's loops are 78–101 px high, so the
        // rings of neighbouring rows overlap and form one cluster.
        val rows = listOf("KD-151", "KD-152", "KD-153")
        val names = listOf("167f2833", "06a83728", "a24b53ba", "673403b3", "5a10eaa0", "61740ee6")
        var batches = 0
        var clustered = 0
        for (k in names.indices) for (stage in 0 until 6) {
            val picked = rows.mapIndexed { r, t ->
                val cell = cells(f!!).filter { it.ticket == t }[(stage + r % 2) % 6]
                cell to placed(f, names[(k + r) % names.size], cell, 3f * r, -2f * r, id = "$t-$k-$stage")
            }
            val strokes = picked.map { it.second }
            if (TicketGestureGeometry.clusters(strokes).size < strokes.size) clustered++
            batches++
            val g = TicketGestureGeometry.recognize(strokes, f!!.layout)
            assertEquals(
                "batch $k/$stage",
                picked.map { "${it.first.ticket} ${it.first.wert}" },
                g.map { "${it.ticket} ${it.wert}" },
            )
        }
        println("batches of three loops: $batches, with touching rings: $clustered")
        assertTrue("the case of 25.10. is in the set (touching rings)", clustered > batches / 2)
    }

    @Test
    fun `client and bridge judge the same loops alike`() {
        val f = fixture
        assumeTrue("private loop set not present, skipped", f != null)
        val verdicts = file("ticket.kreise.server", "TICKET_KREISE_SERVER", "ticket-kreise-server.txt.gz")
        assumeTrue("server verdicts not present, skipped", verdicts != null)
        val byKey = cells(f!!).associateBy { "${it.ticket}|${it.wert}" }
        var n = 0
        val differ = mutableListOf<String>()
        GZIPInputStream(verdicts!!.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val (name, ticket, wert, dx, dy, server) = line.split("|").let { Six(it) }
                val cell = byKey.getValue("$ticket|$wert")
                val g = TicketGestureGeometry.recognize(listOf(placed(f, name, cell, dx.toFloat(), dy.toFloat())), f.layout)
                val client = g.singleOrNull()?.let { "${it.ticket} ${it.wert}" } ?: "-"
                n++
                if (client != server) differ += "$name $ticket $wert $dx/$dy: client $client, bridge $server"
            }
        }
        println("parity: $n placements, ${differ.size} differ ${differ.take(5)}")
        assertTrue("enough placements", n > 5000)
        assertEquals("client and bridge differ", emptyList<String>(), differ)
    }

    private class Six(val p: List<String>) {
        operator fun component1() = p[0]
        operator fun component2() = p[1]
        operator fun component3() = p[2]
        operator fun component4() = p[3]
        operator fun component5() = p[4]
        operator fun component6() = p[5]
    }
}
