package com.ethran.notable.tickets

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val BLACK = 0xFF000000.toInt()
const val RED = 0xFFFF0000.toInt()
const val BLUE = 0xFF0000FF.toInt()

private var counter = 0

fun stroke(
    vararg xy: Pair<Float, Float>,
    color: Int = BLACK,
    pen: Pen = Pen.FOUNTAIN,
    id: String = "s${counter++}",
    step: Float = 3f,
): Stroke = strokeOf(densify(xy.toList(), step), color, pen, id)

fun strokeOf(pts: List<Pair<Float, Float>>, color: Int = BLACK, pen: Pen = Pen.FOUNTAIN, id: String = "s${counter++}"): Stroke {
    val points = pts.mapIndexed { i, (x, y) -> StrokePoint(x, y, dt = (i * 4).toUShort()) }
    return Stroke(
        id = id, size = 3f, pen = pen, color = color,
        top = points.minOf { it.y } - 3f, bottom = points.maxOf { it.y } + 3f,
        left = points.minOf { it.x } - 3f, right = points.maxOf { it.x } + 3f,
        points = points, pageId = "page",
    )
}

/** Points every [step] px along the polyline, like a pen sampling at speed. */
fun densify(corners: List<Pair<Float, Float>>, step: Float = 3f): List<Pair<Float, Float>> {
    if (corners.size < 2) return corners
    val out = mutableListOf(corners.first())
    for (i in 1 until corners.size) {
        val (ax, ay) = corners[i - 1]
        val (bx, by) = corners[i]
        val n = (hypot(bx - ax, by - ay) / step).toInt().coerceAtLeast(1)
        for (k in 1..n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
    }
    return out
}

/** An ellipse around (cx, cy), [turns] rounds, starting at [startDeg], with a little wobble. */
fun loop(
    cx: Float, cy: Float, rx: Float, ry: Float,
    turns: Float = 1.08f, startDeg: Float = 200f, color: Int = BLACK, id: String = "s${counter++}",
): Stroke {
    val n = (turns * 120).toInt()
    val pts = (0..n).map { i ->
        val a = (startDeg + 360f * turns * i / n) * PI.toFloat() / 180f
        val wob = 1f + 0.06f * sin(5 * a)
        (cx + rx * wob * cos(a)) to (cy + ry * wob * sin(a))
    }
    return strokeOf(pts, color, id = id)
}

fun region(ticket: String, art: RegionArt, x1: Number, y1: Number, x2: Number, y2: Number, wert: String? = null) =
    Region(ticket, art, Box(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()), wert)

val STAGES = listOf("📜 巻物", "🥋 稽古", "🧐 審査", "🧘 構え", "⚔️ 抜刀", "⚡ 残心")
val GUERTEL = listOf("🌱 種", "🔥 鍛錬", "🗡️ 浪人", "⚔️ 型", "🎓 免許皆伝", "🤝 伝承")

/**
 * A Keiko-ba-like row on line [y] (80 px lines, like the bridge's pages): box, traffic light,
 * text, six symbols of the stage bar ([GUERTEL] values when [belt]).
 */
fun ticketRow(ticket: String, y: Float, belt: Boolean = false): List<Region> {
    val r = mutableListOf(
        region(ticket, RegionArt.ZEILE, 100, y, 1740, y + 70),
        region(ticket, RegionArt.KASTEN, 120, y + 14, 166, y + 60),
        region(ticket, RegionArt.AMPEL, 186, y + 19, 222, y + 55),
    )
    val values = if (belt) GUERTEL else STAGES
    val art = if (belt) RegionArt.GUERTEL else RegionArt.STAGE
    values.forEachIndexed { i, v ->
        val x = 1340f + i * 64f
        r += region(ticket, art, x, y + 11, x + 48, y + 59, v)
    }
    return r
}

fun layoutOf(vararg rows: List<Region>) = TicketLayout("page", "keikoba", null, rows.flatMap { it })

// ---- real strokes (format: see the header of the resource) ------------------------------------

class RealPage(val id: String) {
    val kaesten = mutableListOf<Box>()
    val texte = mutableListOf<Box>()
    val strokes = mutableListOf<RealStroke>()

    /** The bridge's real layout as a ticket layout: box(es) + row per text line. */
    fun layout(): TicketLayout {
        val regions = mutableListOf<Region>()
        texte.forEachIndexed { i, t ->
            val boxes = kaesten.filter { it.y1 < t.y2 && it.y2 > t.y1 }
            val row = boxes.fold(t) { acc, b -> acc.union(b) }
            regions += Region("Z$i", RegionArt.ZEILE, row)
            boxes.forEach { regions += Region("Z$i", RegionArt.KASTEN, it) }
        }
        return TicketLayout(id, "real", null, regions)
    }
}

class RealStroke(val stroke: Stroke, val status: String, val createdAt: Long) {
    val endsAt: Long get() = createdAt + (stroke.points.last().dt?.toLong() ?: 0L)
}

fun readRealPages(input: InputStream): Map<String, RealPage> {
    val pages = linkedMapOf<String, RealPage>()
    GZIPInputStream(input).bufferedReader().useLines { lines ->
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split("|")
            val page = pages.getOrPut(f[1]) { RealPage(f[1]) }
            when (f[0]) {
                "R" -> {
                    val (a, b, c, d) = f[3].split(",").map { it.toFloat() }
                    (if (f[2] == "kasten") page.kaesten else page.texte) += Box.of(a, b, c, d)
                }
                "S" -> {
                    val pts = f[7].split(" ").map {
                        val (x, y, dt) = it.split(",")
                        StrokePoint(x.toFloat(), y.toFloat(), dt = dt.toInt().toUShort())
                    }
                    val s = Stroke(
                        id = "${f[1]}-${f[2]}", size = 3f, pen = Pen.fromString(f[4]),
                        color = f[5].toLong(16).toInt(),
                        top = pts.minOf { it.y }, bottom = pts.maxOf { it.y },
                        left = pts.minOf { it.x }, right = pts.maxOf { it.x },
                        points = pts, pageId = f[1],
                    )
                    page.strokes += RealStroke(s, f[3], f[6].toLong())
                }
            }
        }
    }
    return pages
}

/** The strokes as the app would hand them over: batches split where the pen rested ≥ [restMs]. */
fun batches(strokes: List<RealStroke>, restMs: Long = TicketGestureService.DEBOUNCE_MS): List<List<RealStroke>> {
    val sorted = strokes.sortedBy { it.createdAt }
    val out = mutableListOf<MutableList<RealStroke>>()
    var lastEnd = Long.MIN_VALUE
    for (s in sorted) {
        if (out.isEmpty() || s.createdAt - lastEnd >= restMs) out += mutableListOf<RealStroke>()
        out.last() += s
        lastEnd = maxOf(lastEnd, s.endsAt)
    }
    return out
}
