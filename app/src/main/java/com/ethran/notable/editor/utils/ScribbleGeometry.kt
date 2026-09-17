package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Scribble-to-erase geometry (fork), pure Kotlin so it runs in JVM tests.
 *
 * Two questions, answered separately:
 *  1. Is this pen stroke a scribble? ([scribbleAxis] + [inkCoverage]) A scribble goes back and
 *     forth over the same stretch many times *and* lies over existing ink. Handwriting such as
 *     "mmm" also reverses a lot, but vertically while advancing, and on blank paper.
 *  2. Which strokes does it erase? ([selectScribbledStrokes]) Everything under the area the
 *     scribble swept ([ScribbleEnvelope]), measured on the stroke's own polyline: a big enough
 *     share of it, a long enough continuous piece of it (so a long line goes when only part of it
 *     is scribbled over), or small marks (i-dots, commas) lying in or just around the area — how
 *     far around grows with the scribble's size on each axis.
 *
 * All distances are page pixels (≈ screen pixels at zoom 1; the Note Air 5C has ~12 px per mm).
 */

/** Width of one envelope column. */
const val SCRIBBLE_BIN_PX = 10f

/** Pen movement below this does not count as a direction change (sensor jitter). */
const val SCRIBBLE_REVERSAL_JITTER_PX = 6f

/** Path travelled along the scribble axis, as a multiple of the scribble's extent on that axis. */
const val SCRIBBLE_MIN_RETRACE = 3.5f
const val SCRIBBLE_MIN_REVERSALS = 3

/** Share of the envelope columns that must already contain ink for a horizontal scribble. */
const val SCRIBBLE_MIN_INK_COVERAGE = 0.4f

/** Vertical zig-zags look like handwriting ("mmm"), so they need more ink underneath. */
const val SCRIBBLE_MIN_INK_COVERAGE_VERTICAL = 0.6f

/** A stroke goes if at least this share of its length lies under the scribble… */
const val SCRIBBLE_ERASE_SHARE = 0.4f

/**
 * …or a continuous piece at least this long, and at least 60 % of the scribble's width and 1.2 × its
 * height: a short scribble across a long line takes the whole line, while the tail of a "g" from
 * the line above only pokes into the scribble and stays.
 */
const val SCRIBBLE_ERASE_MIN_RUN_PX = 30f

/** Strokes whose own extent is at most this are "small marks": i-dots, commas, periods. */
const val SCRIBBLE_SMALL_MARK_PX = 30f

/** Small marks count this far beside the scribble, as a share of its width (clamped to 4–45 px): about a pen width for one letter. */
const val SCRIBBLE_SMALL_MARK_MARGIN_X_SHARE = 0.2f

private const val SAMPLE_STEP_PX = 3f

enum class ScribbleAxis { HORIZONTAL, VERTICAL }

internal data class Pt(val x: Float, val y: Float)

/** Resamples a polyline so consecutive points are at most [step] apart (single points kept). */
internal fun densify(points: List<StrokePoint>, step: Float = SAMPLE_STEP_PX): List<Pt> {
    if (points.isEmpty()) return emptyList()
    val out = ArrayList<Pt>(points.size * 2)
    out += Pt(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val ax = points[i - 1].x
        val ay = points[i - 1].y
        val bx = points[i].x
        val by = points[i].y
        val n = ceil(hypot(bx - ax, by - ay) / step).toInt()
        for (k in 1..max(n, 1)) {
            val t = k.toFloat() / max(n, 1)
            out += Pt(ax + (bx - ax) * t, ay + (by - ay) * t)
        }
    }
    return out
}

/** Direction changes along one axis, ignoring movement shorter than [jitter]. */
private fun countReversals(values: List<Float>, jitter: Float = SCRIBBLE_REVERSAL_JITTER_PX): Int {
    if (values.isEmpty()) return 0
    var reversals = 0
    var direction = 0
    var anchor = values[0]
    for (v in values) {
        val d = v - anchor
        when {
            direction >= 0 && d <= -jitter -> {
                if (direction > 0) reversals++
                direction = -1; anchor = v
            }

            direction <= 0 && d >= jitter -> {
                if (direction < 0) reversals++
                direction = 1; anchor = v
            }

            direction > 0 && v > anchor -> anchor = v
            direction < 0 && v < anchor -> anchor = v
        }
    }
    return reversals
}

/**
 * The axis the pen goes back and forth on, or null if the stroke does not retrace enough to be a
 * scribble. Shape only — [inkCoverage] decides whether there is anything to erase.
 */
fun scribbleAxis(points: List<StrokePoint>): ScribbleAxis? {
    if (points.size < MINIMUM_SCRIBBLE_POINTS) return null
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val width = maxX - minX
    val height = maxY - minY
    var travelX = 0f
    var travelY = 0f
    for (i in 1 until points.size) {
        travelX += abs(points[i].x - points[i - 1].x)
        travelY += abs(points[i].y - points[i - 1].y)
    }
    val horizontal = width > 0f && travelX >= width * SCRIBBLE_MIN_RETRACE &&
            countReversals(points.map { it.x }) >= SCRIBBLE_MIN_REVERSALS
    val vertical = height > 0f && travelY >= height * SCRIBBLE_MIN_RETRACE &&
            countReversals(points.map { it.y }) >= SCRIBBLE_MIN_REVERSALS
    return when {
        horizontal && vertical ->
            if (travelX / width >= travelY / height) ScribbleAxis.HORIZONTAL else ScribbleAxis.VERTICAL

        horizontal -> ScribbleAxis.HORIZONTAL
        vertical -> ScribbleAxis.VERTICAL
        else -> null
    }
}

/**
 * The area a scribble swept: for each [SCRIBBLE_BIN_PX]-wide column, the vertical span the pen
 * covered there. Columns the pen never reached (a gap in the middle) have no span.
 */
class ScribbleEnvelope private constructor(
    private val originX: Float,
    private val top: FloatArray,
    private val bottom: FloatArray,
    /** Where the pen actually went; the columns round outwards by up to a column width. */
    val left: Float,
    val right: Float,
) {
    val minY: Float = top.filter { !it.isNaN() }.minOrNull() ?: 0f
    val maxY: Float = bottom.filter { !it.isNaN() }.maxOrNull() ?: 0f

    /** Median column height — "how tall the scribble is", robust against a stray overshoot. */
    val typicalHeight: Float = run {
        val heights = top.indices.filter { !top[it].isNaN() }.map { bottom[it] - top[it] }.sorted()
        if (heights.isEmpty()) 0f else heights[heights.size / 2]
    }

    val columnCount: Int get() = top.size

    private fun column(x: Float): Int = floor((x - originX) / SCRIBBLE_BIN_PX).toInt()

    /** Whether (x, y) lies in the swept area grown by [marginX] sideways and [marginY] up and down. */
    fun contains(x: Float, y: Float, marginX: Float = 0f, marginY: Float = marginX): Boolean {
        if (x < left - marginX || x > right + marginX) return false
        val from = max(column(x - marginX), 0)
        val to = min(column(x + marginX), top.size - 1)
        for (c in from..to) {
            if (top[c].isNaN()) continue
            if (y >= top[c] - marginY && y <= bottom[c] + marginY) return true
        }
        return false
    }

    internal fun columnSpan(c: Int): Pair<Float, Float>? =
        if (top[c].isNaN()) null else top[c] to bottom[c]

    internal fun columnOf(x: Float): Int = column(x)

    companion object {
        fun of(points: List<StrokePoint>): ScribbleEnvelope {
            val samples = densify(points)
            val originX = floor(samples.minOf { it.x } / SCRIBBLE_BIN_PX) * SCRIBBLE_BIN_PX
            val columns = column(samples.maxOf { it.x }, originX) + 1
            val top = FloatArray(columns) { Float.NaN }
            val bottom = FloatArray(columns) { Float.NaN }
            for (p in samples) {
                val c = column(p.x, originX)
                if (top[c].isNaN() || p.y < top[c]) top[c] = p.y
                if (bottom[c].isNaN() || p.y > bottom[c]) bottom[c] = p.y
            }
            return ScribbleEnvelope(originX, top, bottom, samples.minOf { it.x }, samples.maxOf { it.x })
        }

        private fun column(x: Float, originX: Float) = floor((x - originX) / SCRIBBLE_BIN_PX).toInt()
    }
}

/** Page-coordinate extent of a stroke's own points (not the padded Stroke bounds). */
private fun pointExtent(stroke: Stroke): Float {
    if (stroke.points.isEmpty()) return 0f
    val w = stroke.points.maxOf { it.x } - stroke.points.minOf { it.x }
    val h = stroke.points.maxOf { it.y } - stroke.points.minOf { it.y }
    return max(w, h)
}

private fun overlapsVertically(
    stroke: Stroke, envelope: ScribbleEnvelope, marginX: Float, marginY: Float = marginX,
) = stroke.right >= envelope.left - marginX && stroke.left <= envelope.right + marginX &&
        stroke.bottom >= envelope.minY - marginY && stroke.top <= envelope.maxY + marginY

/**
 * Share of the envelope's columns that already hold ink from [strokes] within their span. Near 0
 * for a word written on blank paper, near 1 for a scribble over a word.
 */
fun inkCoverage(envelope: ScribbleEnvelope, strokes: List<Stroke>): Float {
    val covered = BooleanArray(envelope.columnCount)
    var spans = 0
    for (c in 0 until envelope.columnCount) if (envelope.columnSpan(c) != null) spans++
    if (spans == 0) return 0f
    val margin = max(envelope.typicalHeight * 0.1f, 4f)
    for (stroke in strokes) {
        if (!overlapsVertically(stroke, envelope, margin)) continue
        for (p in densify(stroke.points)) {
            val c = envelope.columnOf(p.x)
            if (c < 0 || c >= covered.size || covered[c]) continue
            val (t, b) = envelope.columnSpan(c) ?: continue
            if (p.y >= t - margin && p.y <= b + margin) covered[c] = true
        }
    }
    return covered.count { it } / spans.toFloat()
}

/** Minimum ink coverage for a scribble on [axis]. */
fun requiredInkCoverage(axis: ScribbleAxis): Float = when (axis) {
    ScribbleAxis.HORIZONTAL -> SCRIBBLE_MIN_INK_COVERAGE
    ScribbleAxis.VERTICAL -> SCRIBBLE_MIN_INK_COVERAGE_VERTICAL
}

/** The strokes a scribble with this [envelope] erases. */
fun selectScribbledStrokes(envelope: ScribbleEnvelope, strokes: List<Stroke>): List<Stroke> {
    val height = envelope.typicalHeight
    val minRun = maxOf(SCRIBBLE_ERASE_MIN_RUN_PX, (envelope.right - envelope.left) * 0.6f, height * 1.2f)
    // Each axis grows with the scribble's size on that axis. i-dots sit up to about one x-height
    // above the letters the scribble covers; sideways a comma or a slanted dot is only a little
    // off. A scribble over the last letter is narrow, so the letters next to it (as small as an
    // i-dot in small handwriting) stay.
    val edgeTolerance = 4f
    val smallMarkMarginY = height.coerceIn(10f, 45f)
    val smallMarkMarginX = ((envelope.right - envelope.left) * SCRIBBLE_SMALL_MARK_MARGIN_X_SHARE)
        .coerceIn(edgeTolerance, 45f)
    return strokes.filter { stroke ->
        if (!overlapsVertically(stroke, envelope, smallMarkMarginX, smallMarkMarginY)) return@filter false
        if (pointExtent(stroke) <= SCRIBBLE_SMALL_MARK_PX) {
            return@filter stroke.points.any { envelope.contains(it.x, it.y, smallMarkMarginX, smallMarkMarginY) }
        }
        // Length-weighted: a segment counts as covered when both its ends lie under the scribble.
        val samples = densify(stroke.points)
        var total = 0f
        var covered = 0f
        var run = 0f
        var longestRun = 0f
        var previousInside = envelope.contains(samples[0].x, samples[0].y, edgeTolerance)
        for (i in 1 until samples.size) {
            val length = hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
            val inside = envelope.contains(samples[i].x, samples[i].y, edgeTolerance)
            total += length
            if (inside && previousInside) {
                covered += length
                run += length
                if (run > longestRun) longestRun = run
            } else run = 0f
            previousInside = inside
        }
        if (total <= 0f) return@filter false
        covered / total >= SCRIBBLE_ERASE_SHARE || longestRun >= minRun
    }
}
