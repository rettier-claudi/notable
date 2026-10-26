package com.ethran.notable.tickets

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What a gesture does, with the word `POST /geste` expects as `aktion`. */
enum class GestureAction(val wire: String) {
    WEITER("weiter"), STAGE("stage"), GUERTEL("guertel"), DROPPED("dropped"), AMPEL("ampel")
}

data class TicketGesture(
    val ticket: String,
    val action: GestureAction,
    /** Only for [GestureAction.STAGE] / [GestureAction.GUERTEL]: the circled symbol's value. */
    val wert: String?,
    val strokeIds: List<String>,
    /** The region the gesture hit (for the answer's position and the double-tick check). */
    val region: Region,
)

/**
 * Recognises the gestures of a ticket page that the tablet can tell apart on its own (spec
 * "Gesten", column "am Tablet erkennbar? ja"): a tick in a box, a loop around one stage/belt
 * symbol, a black line through most of a row, a short stroke through the traffic light.
 *
 * Only what is unambiguous; everything else stays with the server, which reads every stroke at the
 * next sync anyway. So every rule errs towards "nothing": a stroke cluster that fits two rules,
 * or one rule in two places, gives nothing. Red strokes (delete, server only) and highlighter never
 * count. Writing that happens to run over a region must not trigger: each rule bounds size and shape
 * (tested on Philipp's real handwriting over bridge pages, `TicketGestureRealStrokesTest`).
 *
 * All coordinates are page coordinates, like the stroke points.
 */
object TicketGestureGeometry {

    // ---- Tick in the box --------------------------------------------------------------------
    // Philipp's ticks on the bridge's 46 px boxes (89 pages, Sep/Oct 2026): 45–93 px wide, the
    // stroke overshoots the box by 10–25 px on some side; 82–99 % of the points lie within
    // 18 px of the box. "Whole stroke in the box" therefore means: in the box plus a margin.

    /** Margin around the box, as a share of its side (at least [KASTEN_RAND_MIN_PX]). */
    const val KASTEN_RAND = 0.4f
    const val KASTEN_RAND_MIN_PX = 14f
    /** Share of the points that must lie in the box plus margin (each stroke at least 0.7). */
    const val KASTEN_ANTEIL = 0.85f
    /** Ink inside the box itself, times its side: a dot or a graze is no tick. */
    const val KASTEN_TINTE = 0.8f
    /** The tick's bounding box is at most this many sides wide and high. */
    const val KASTEN_GROESSE = 2.2f
    /** … and at least this share of a side in both directions (smallest real tick: 45 × 40 px). */
    const val KASTEN_MIN = 0.4f

    // ---- Loop around a symbol ---------------------------------------------------------------
    /** Angle the pen has to travel around the symbol's centre: a full loop with a little slack. */
    const val KREIS_WINKEL_GRAD = 320f
    /** The loop stays off the symbol: no point closer to its centre than this share of its size. */
    const val KREIS_ABSTAND = 0.3f
    /** Loop size relative to the symbol: at least this share of it in each direction … */
    const val KREIS_MIN = 0.7f
    /** … and at most this many symbols wide/high. */
    const val KREIS_MAX = 4f
    /** Path length relative to the loop's ellipse: one or two rounds, not a word with loops. */
    const val KREIS_WEG_MIN = 0.75f
    const val KREIS_WEG_MAX = 2.4f
    // Philipp's loops are 60–110 px (real ones from his notes, 25.10.: 375–400° round, path ≈ the
    // ellipse), the bridge's symbols 64 px on a 68 px pitch. A loop a little off-centre therefore also
    // runs round the neighbour's centre. Then the symbol nearest to the loop's centre wins, if it is
    // clearly nearer than the next one and the loop's centre lies on it.
    /** Nearest enclosed symbol at most this share of the distance to the second one … */
    const val KREIS_KLAR = 0.6f
    /** … and the loop's centre within this share of the symbol's size from its centre. */
    const val KREIS_MITTE = 0.6f

    // ---- Line through the row -----------------------------------------------------------------
    // Philipp's strike-through lines on bridge rows (70 px high): 9–49 px high, path/chord 1.01–1.06,
    // centre 25–50 px below the row top.
    /** Horizontal coverage of the row. */
    const val LINIE_DECKUNG = 0.6f
    /** Share of the points within the row band (row ± a quarter of its height). */
    const val LINIE_IM_BAND = 0.9f
    /** Path ÷ chord: a straight line. Handwriting is 2.5 and more. */
    const val LINIE_GERADE = 1.2f
    /** Height at most this share of the row: flat, not a slash across several rows. */
    const val LINIE_HOEHE = 0.9f

    // ---- Stroke through the traffic light ---------------------------------------------------
    /** All points within the light plus this share of its size. */
    const val AMPEL_RAND = 0.8f
    /** Stroke extent: at least this share of the light's size (crosses it) … */
    const val AMPEL_MIN = 0.6f
    /** … and at most this many sizes (short). */
    const val AMPEL_MAX = 2.6f
    /** Path ÷ chord. */
    const val AMPEL_GERADE = 1.6f

    /**
     * Strokes whose bounds come this close belong to the same mark (the two strokes of a cross
     * overlap; letters of a word sit a few px apart). Not more: Philipp ticks box after box in
     * quick succession, and his ticks overshoot towards the neighbouring box.
     */
    const val GRUPPE_ABSTAND_PX = 8f

    /** A tick with a non-gesture stroke this close beside it (same height) is a letter of a word. */
    const val SCHRIFT_ABSTAND_PX = 30f

    fun recognize(strokes: List<Stroke>, layout: TicketLayout): List<TicketGesture> {
        val usable = strokes.filter { usable(it) }
        if (usable.isEmpty() || layout.regionen.isEmpty()) return emptyList()
        val groups = clusters(usable)
        val found: List<List<TicketGesture>> = groups.map { c ->
            // Several loops drawn in one go (pen rest shorter than the debounce) whose rings touch —
            // stages of two tickets one above the other — form one cluster: each loop on its own.
            loops(c, layout)?.takeIf { it.size >= 2 }
                ?: listOfNotNull((tick(c, layout) + loop(c, layout) + line(c, layout) + light(c, layout)).singleOrNull())
                    .ifEmpty { loops(c, layout).orEmpty() }
        }
        // Strokes that are no gesture themselves: possibly writing.
        val other = groups.filterIndexed { i, _ -> found[i].isEmpty() }.flatten()
        return groups.indices.flatMap { i ->
            found[i].filterNot { g -> g.action == GestureAction.WEITER && writingBeside(boundsOf(groups[i]), other) }
        }
    }

    /**
     * A cluster of two or more strokes read as separate loops, in drawing order: each stroke on its
     * own, or with the next one (a loop in two halves). Only if **every** stroke is part of a loop and
     * no symbol is hit twice — a cluster with one stroke that is no loop is writing, and stays the
     * server's.
     */
    internal fun loops(c: List<Stroke>, layout: TicketLayout): List<TicketGesture>? {
        if (c.size < 2) return null
        val out = mutableListOf<TicketGesture>()
        var i = 0
        while (i < c.size) {
            val one = loop(listOf(c[i]), layout).singleOrNull()
            if (one != null) {
                out += one
                i++
                continue
            }
            val two = if (i + 1 < c.size && loop(listOf(c[i + 1]), layout).isEmpty()) {
                loop(listOf(c[i], c[i + 1]), layout).singleOrNull()
            } else null
            out += two ?: return null
            i += 2
        }
        return out.takeIf { o -> o.map { it.region }.distinct().size == o.size }
    }

    /**
     * Is there writing right beside [b] in the same batch -- a letter that landed in the box at the
     * start of a word, not a tick? Ticks in the boxes above and below don't count (no overlap in
     * height), nor do other gestures (the light next to the box).
     */
    private fun writingBeside(b: Box, others: List<Stroke>): Boolean = others.any { s ->
        val o = bounds(s)
        val overlap = min(b.y2, o.y2) - max(b.y1, o.y1)
        val gap = max(o.x1 - b.x2, b.x1 - o.x2)
        overlap >= 0.4f * min(b.h, o.h) && gap <= SCHRIFT_ABSTAND_PX
    }

    /** Red never (deleting is the server's), highlighter never, no dots. */
    fun usable(s: Stroke): Boolean =
        s.points.size >= 2 && s.pen != Pen.MARKER && s.pen != Pen.REDBALLPEN && !isRed(s.color)

    // Philipp's pens: black (content), red (correction), blue (answer via Telegram), green.
    // Same buckets as the bridge (`erkennen.farbe`), so both sides agree on a colour.
    fun isRed(color: Int): Boolean {
        val (r, g, b) = rgb(color)
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        if (hi < 90 || hi - lo < 60) return false
        return r >= g && r >= b && g < 150
    }

    fun isBlack(color: Int): Boolean {
        val (r, g, b) = rgb(color)
        val hi = maxOf(r, g, b)
        return hi < 90 || hi - minOf(r, g, b) < 60
    }

    private fun rgb(c: Int) = Triple((c shr 16) and 255, (c shr 8) and 255, c and 255)

    // ---- clustering ---------------------------------------------------------------------------

    internal fun clusters(strokes: List<Stroke>): List<List<Stroke>> {
        val parent = IntArray(strokes.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x].also { parent[x] = parent[parent[x]] }
            return x
        }
        val boxes = strokes.map { bounds(it).grow(GRUPPE_ABSTAND_PX / 2) }
        for (i in strokes.indices) for (j in i + 1 until strokes.size) {
            val a = boxes[i]
            val b = boxes[j]
            if (a.x1 <= b.x2 && b.x1 <= a.x2 && a.y1 <= b.y2 && b.y1 <= a.y2) parent[find(i)] = find(j)
        }
        return strokes.indices.groupBy { find(it) }.values.map { idx -> idx.map { strokes[it] } }
    }

    // ---- rules --------------------------------------------------------------------------------

    private fun tick(c: List<Stroke>, layout: TicketLayout): List<TicketGesture> {
        if (c.size > 3) return emptyList()
        val all = c.flatMap { it.points }
        val bb = boundsOf(c)
        return layout.regionen.filter { it.art == RegionArt.KASTEN }.mapNotNull { k ->
            val side = min(k.box.w, k.box.h)
            val zone = k.box.grow(max(KASTEN_RAND_MIN_PX, KASTEN_RAND * side))
            if (share(all, zone) < KASTEN_ANTEIL) return@mapNotNull null
            if (c.any { share(it.points, zone) < 0.7f }) return@mapNotNull null
            if (bb.w > KASTEN_GROESSE * side || bb.h > KASTEN_GROESSE * side) return@mapNotNull null
            // A tick has width and height; a single upright stroke ("l", "1") has not.
            if (bb.w < KASTEN_MIN * side || bb.h < KASTEN_MIN * side) return@mapNotNull null
            if (!k.box.grow(0.2f * side).contains(bb.cx, bb.cy)) return@mapNotNull null
            if (c.sumOf { lengthIn(it, k.box).toDouble() } < KASTEN_TINTE * side) return@mapNotNull null
            TicketGesture(k.ticket, GestureAction.WEITER, null, c.map { it.id }, k)
        }
    }

    private fun loop(c: List<Stroke>, layout: TicketLayout): List<TicketGesture> {
        if (c.size > 2) return emptyList()
        val symbols = layout.regionen.filter { it.art == RegionArt.STAGE || it.art == RegionArt.GUERTEL }
        if (symbols.isEmpty()) return emptyList()
        val enclosed = symbols.filter { s ->
            abs(c.sumOf { sweptDegrees(it, s.box.cx, s.box.cy).toDouble() }) >= KREIS_WINKEL_GRAD
        }
        val bb = boundsOf(c)
        val s = target(enclosed, bb) ?: return emptyList()
        val all = c.flatMap { it.points }
        val size = max(s.box.w, s.box.h)
        if (bb.w < KREIS_MIN * s.box.w || bb.h < KREIS_MIN * s.box.h) return emptyList()
        if (bb.w > KREIS_MAX * size || bb.h > KREIS_MAX * size) return emptyList()
        val keepOff = KREIS_ABSTAND * min(s.box.w, s.box.h)
        if (all.any { hypot(it.x - s.box.cx, it.y - s.box.cy) < keepOff }) return emptyList()
        val ellipse = (PI * (bb.w + bb.h) / 2).toFloat()
        val path = c.sumOf { length(it).toDouble() }.toFloat()
        if (path < KREIS_WEG_MIN * ellipse || path > KREIS_WEG_MAX * ellipse) return emptyList()
        val action = if (s.art == RegionArt.STAGE) GestureAction.STAGE else GestureAction.GUERTEL
        return listOf(TicketGesture(s.ticket, action, s.wert, c.map { it.id }, s))
    }

    /**
     * Which of the [enclosed] symbols a loop with bounds [bb] means: the only one, or the one nearest
     * to the loop's centre if that is clearly nearer than the next ([KREIS_KLAR]) and the centre lies
     * on it ([KREIS_MITTE]). Otherwise none.
     */
    internal fun target(enclosed: List<Region>, bb: Box): Region? {
        if (enclosed.size <= 1) return enclosed.singleOrNull()
        fun dist(r: Region) = hypot(r.box.cx - bb.cx, r.box.cy - bb.cy)
        val sorted = enclosed.sortedBy { dist(it) }
        val near = sorted[0]
        if (dist(near) > KREIS_KLAR * dist(sorted[1])) return null
        if (dist(near) > KREIS_MITTE * min(near.box.w, near.box.h)) return null
        return near
    }

    private fun line(c: List<Stroke>, layout: TicketLayout): List<TicketGesture> {
        val s = c.singleOrNull() ?: return emptyList()
        if (!isBlack(s.color)) return emptyList()
        val pts = s.points
        val bb = bounds(s)
        val chord = hypot(pts.last().x - pts.first().x, pts.last().y - pts.first().y)
        if (chord <= 0f || length(s) > LINIE_GERADE * chord) return emptyList()
        return layout.regionen.filter { it.art == RegionArt.ZEILE }.mapNotNull { z ->
            val row = z.box
            val band = row.grow(GRUPPE_ABSTAND_PX, 0.25f * row.h)
            if (share(pts, Box(Float.NEGATIVE_INFINITY, band.y1, Float.POSITIVE_INFINITY, band.y2)) <
                LINIE_IM_BAND
            ) return@mapNotNull null
            val covered = (min(bb.x2, row.x2) - max(bb.x1, row.x1)) / row.w
            if (covered < LINIE_DECKUNG) return@mapNotNull null
            if (bb.h > LINIE_HOEHE * row.h) return@mapNotNull null
            // Through the row, not under it: underlining sits on the bottom line.
            val meanY = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
            if (meanY < row.y1 + 0.15f * row.h || meanY > row.y2 - 0.1f * row.h) return@mapNotNull null
            TicketGesture(z.ticket, GestureAction.DROPPED, null, listOf(s.id), z)
        }
    }

    private fun light(c: List<Stroke>, layout: TicketLayout): List<TicketGesture> {
        val s = c.singleOrNull() ?: return emptyList()
        val pts = s.points
        val bb = bounds(s)
        val chord = hypot(pts.last().x - pts.first().x, pts.last().y - pts.first().y)
        if (chord <= 0f || length(s) > AMPEL_GERADE * chord) return emptyList()
        return layout.regionen.filter { it.art == RegionArt.AMPEL }.mapNotNull { a ->
            val size = max(a.box.w, a.box.h)
            if (share(pts, a.box.grow(AMPEL_RAND * size)) < 1f) return@mapNotNull null
            val extent = max(bb.w, bb.h)
            if (extent < AMPEL_MIN * size || extent > AMPEL_MAX * size) return@mapNotNull null
            if (chord < AMPEL_MIN * min(a.box.w, a.box.h)) return@mapNotNull null
            // Through it: some ink in the inner part of the light.
            val inner = a.box.grow(-0.2f * a.box.w, -0.2f * a.box.h)
            if (pts.none { inner.contains(it.x, it.y) } && !segmentsCross(pts, inner)) return@mapNotNull null
            TicketGesture(a.ticket, GestureAction.AMPEL, null, listOf(s.id), a)
        }
    }

    // ---- geometry helpers ---------------------------------------------------------------------

    internal fun bounds(s: Stroke): Box {
        var x1 = Float.POSITIVE_INFINITY
        var y1 = Float.POSITIVE_INFINITY
        var x2 = Float.NEGATIVE_INFINITY
        var y2 = Float.NEGATIVE_INFINITY
        for (p in s.points) {
            x1 = min(x1, p.x); y1 = min(y1, p.y); x2 = max(x2, p.x); y2 = max(y2, p.y)
        }
        return Box(x1, y1, x2, y2)
    }

    private fun boundsOf(c: List<Stroke>) = c.map { bounds(it) }.reduce(Box::union)

    private fun share(pts: List<com.ethran.notable.data.db.StrokePoint>, r: Box): Float =
        if (pts.isEmpty()) 0f else pts.count { r.contains(it.x, it.y) }.toFloat() / pts.size

    internal fun length(s: Stroke): Float {
        var sum = 0f
        for (i in 1 until s.points.size) {
            val a = s.points[i - 1]
            val b = s.points[i]
            sum += hypot(b.x - a.x, b.y - a.y)
        }
        return sum
    }

    /** Length of the segments that lie with both ends in [r]. */
    private fun lengthIn(s: Stroke, r: Box): Float {
        var sum = 0f
        for (i in 1 until s.points.size) {
            val a = s.points[i - 1]
            val b = s.points[i]
            if (r.contains(a.x, a.y) && r.contains(b.x, b.y)) sum += hypot(b.x - a.x, b.y - a.y)
        }
        return sum
    }

    /** Signed angle (degrees) the stroke travels around (cx, cy). A full loop is ±360. */
    internal fun sweptDegrees(s: Stroke, cx: Float, cy: Float): Float {
        var total = 0.0
        var prev: Double? = null
        for (p in s.points) {
            val dx = (p.x - cx).toDouble()
            val dy = (p.y - cy).toDouble()
            if (dx == 0.0 && dy == 0.0) continue
            val a = atan2(dy, dx)
            prev?.let {
                var d = a - it
                while (d > PI) d -= 2 * PI
                while (d < -PI) d += 2 * PI
                total += d
            }
            prev = a
        }
        return Math.toDegrees(total).toFloat()
    }

    /** Does the polyline cross [r] between two points (fast stroke, few samples)? */
    private fun segmentsCross(pts: List<com.ethran.notable.data.db.StrokePoint>, r: Box): Boolean {
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val steps = (hypot(b.x - a.x, b.y - a.y) / 2f).toInt().coerceIn(1, 200)
            for (k in 1 until steps) {
                val f = k.toFloat() / steps
                if (r.contains(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)) return true
            }
        }
        return false
    }
}
