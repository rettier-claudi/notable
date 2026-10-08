package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Scribble-to-erase geometry (fork), pure Kotlin so it runs in JVM tests.
 *
 * Two questions, answered separately:
 *  1. Is this pen stroke a scribble? ([scribbleAxis] + [inkCoverage]) A scribble goes back and
 *     forth over the same stretch many times, turning sharply each time, *and* lies over existing
 *     ink. Handwriting such as "mmm" also reverses a lot, but on blank paper; circles and filled
 *     dots reverse too, but round, never sharp.
 *  2. Which strokes does it erase? ([selectScribbledStrokes]) Everything under the area the
 *     scribble covers ([ScribbleArea]): the zig-zag filled in between its turning points, so a big
 *     sloppy scribble also covers what lies between its passes. Sideways the area ends at the
 *     outermost turns. A stroke goes when a fair share of it lies in the area, or a long enough
 *     continuous piece of it (a long line scribbled over somewhere), and i-dots go with the
 *     letters under them.
 *
 * Tuned on Philipp's test pages (Scratch note 2026-10-08, see ScribbleRealPagesTest).
 * All distances are page pixels (≈ screen pixels at zoom 1; the Note Air 5C has ~12 px per mm).
 */

/** Pen movement below this does not count as a direction change (sensor jitter). */
const val SCRIBBLE_REVERSAL_JITTER_PX = 6f

/** Path travelled along the scribble axis, as a multiple of the scribble's extent on that axis. */
const val SCRIBBLE_MIN_RETRACE = 3.5f

/** Sharp turns a scribble needs at least… */
const val SCRIBBLE_MIN_REVERSALS = 3

/** …and the share of all its turns that must be sharp. A circle or spiral has none. */
const val SCRIBBLE_MIN_SHARP_SHARE = 0.6f

/** A turn is sharp when the pen's direction changes by at least this much… */
const val SCRIBBLE_SHARP_TURN_DEGREES = 110.0

/**
 * …measured this far before and after the turning point, as a share of the scribble's typical pass
 * length (so a big scribble's rounded ends still count, a circle's never do).
 */
const val SCRIBBLE_TURN_ARM_SHARE = 0.2f

/** Share of the area (along the scribble's direction of travel) that must already hold ink. */
const val SCRIBBLE_MIN_INK_COVERAGE = 0.4f

/** Vertical zig-zags look like handwriting ("mmm"), so they need more ink underneath. */
const val SCRIBBLE_MIN_INK_COVERAGE_VERTICAL = 0.6f

/**
 * A stroke goes if at least this share of its length lies in the area. On the test pages, strokes
 * that should go had ≥ 32 %, neighbours that should stay ≤ 8 %.
 */
const val SCRIBBLE_ERASE_SHARE = 0.25f

/**
 * …or a continuous piece at least this long, and at least half the area's shorter side: a
 * scribble anywhere across a long line takes the whole line, while the tail of a "g" from the line
 * above only pokes into the scribble and stays.
 */
const val SCRIBBLE_ERASE_MIN_RUN_PX = 30f

/** Strokes whose own extent is at most this are dots: i-dots, periods, commas. */
const val SCRIBBLE_DOT_PX = 16f

/** Points this close to the scribble's own path count as covered (pen width + jitter). */
const val SCRIBBLE_PATH_TOLERANCE_PX = 5f

/**
 * Within the grace period after another stroke (fast writing), a stroke only counts as a scribble
 * when it has gone on at least this long, with at least [SCRIBBLE_GRACE_OVERRIDE_REVERSALS] sharp
 * turns: a quick zig-zag letter right after writing stays ink, scribbling on does erase.
 */
const val SCRIBBLE_GRACE_OVERRIDE_MS = 600
const val SCRIBBLE_GRACE_OVERRIDE_REVERSALS = 6

private const val SAMPLE_STEP_PX = 3f
private const val COVERAGE_BIN_PX = 10f
private const val GRID_CELL_PX = 8f

/** Dots must really be over (or straight above) the scribble: the i-dot of the next letter stays. */
private const val DOT_TOLERANCE_PX = 2f

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

private fun Pt.along(axis: ScribbleAxis) = if (axis == ScribbleAxis.HORIZONTAL) x else y

/**
 * Indices of the direction changes along [axis] (the turning points), ignoring movement shorter
 * than [jitter]. Each index is the extreme point of the run before the change.
 */
internal fun turningPoints(
    samples: List<Pt>, axis: ScribbleAxis, jitter: Float = SCRIBBLE_REVERSAL_JITTER_PX,
): List<Int> {
    val out = ArrayList<Int>()
    if (samples.isEmpty()) return out
    var direction = 0
    var anchor = samples[0].along(axis)
    var anchorIndex = 0
    for (i in samples.indices) {
        val v = samples[i].along(axis)
        val d = v - anchor
        when {
            direction >= 0 && d <= -jitter -> {
                if (direction > 0) out += anchorIndex
                direction = -1; anchor = v; anchorIndex = i
            }

            direction <= 0 && d >= jitter -> {
                if (direction < 0) out += anchorIndex
                direction = 1; anchor = v; anchorIndex = i
            }

            direction > 0 && v > anchor -> { anchor = v; anchorIndex = i }
            direction < 0 && v < anchor -> { anchor = v; anchorIndex = i }
        }
    }
    return out
}

/** Median distance along [axis] between consecutive turning points: how long one pass is. */
private fun passLength(samples: List<Pt>, turns: List<Int>, axis: ScribbleAxis): Float {
    if (turns.size < 2) return 0f
    val lengths = (1 until turns.size)
        .map { abs(samples[turns[it]].along(axis) - samples[turns[it - 1]].along(axis)) }
        .sorted()
    return lengths[lengths.size / 2]
}

/** The point about [distance] along the path from [index], walking in [step] (±1). */
private fun walk(samples: List<Pt>, index: Int, distance: Float, step: Int): Pt {
    var travelled = 0f
    var i = index
    while (i + step in samples.indices && travelled < distance) {
        travelled += hypot(samples[i + step].x - samples[i].x, samples[i + step].y - samples[i].y)
        i += step
    }
    return samples[i]
}

private val SHARP_TURN_COS = cos(Math.toRadians(SCRIBBLE_SHARP_TURN_DEGREES)).toFloat()

/** Whether the pen turns by at least [SCRIBBLE_SHARP_TURN_DEGREES] around [index] within [arm]. */
private fun isSharpTurn(samples: List<Pt>, index: Int, arm: Float): Boolean {
    val before = walk(samples, index, arm, -1)
    val after = walk(samples, index, arm, 1)
    val p = samples[index]
    val ux = p.x - before.x
    val uy = p.y - before.y
    val wx = after.x - p.x
    val wy = after.y - p.y
    val nu = hypot(ux, uy)
    val nw = hypot(wx, wy)
    if (nu < 1e-3f || nw < 1e-3f) return false
    return (ux * wx + uy * wy) / (nu * nw) <= SHARP_TURN_COS
}

/** What the zig-zag along one axis looks like. */
internal class AxisShape(
    val axis: ScribbleAxis,
    val retraceRatio: Float,
    val turns: List<Int>,
    val sharpTurns: Int,
    val passLength: Float,
) {
    val isScribble: Boolean
        get() = retraceRatio >= SCRIBBLE_MIN_RETRACE &&
                sharpTurns >= SCRIBBLE_MIN_REVERSALS &&
                sharpTurns >= turns.size * SCRIBBLE_MIN_SHARP_SHARE
}

internal fun axisShape(samples: List<Pt>, axis: ScribbleAxis): AxisShape {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    var travel = 0f
    for (i in samples.indices) {
        val v = samples[i].along(axis)
        if (v < lo) lo = v
        if (v > hi) hi = v
        if (i > 0) travel += abs(v - samples[i - 1].along(axis))
    }
    val extent = hi - lo
    val turns = turningPoints(samples, axis)
    val pass = passLength(samples, turns, axis)
    val arm = (pass * SCRIBBLE_TURN_ARM_SHARE).coerceIn(6f, 60f)
    val sharp = turns.count { isSharpTurn(samples, it, arm) }
    return AxisShape(axis, if (extent > 0f) travel / extent else 0f, turns, sharp, pass)
}

/** Both axes' shapes and the one that makes this a scribble, if any. */
internal class ScribbleShape(val samples: List<Pt>, val horizontal: AxisShape, val vertical: AxisShape) {
    val axis: ScribbleAxis? = when {
        horizontal.isScribble && vertical.isScribble ->
            if (horizontal.retraceRatio >= vertical.retraceRatio) ScribbleAxis.HORIZONTAL else ScribbleAxis.VERTICAL

        horizontal.isScribble -> ScribbleAxis.HORIZONTAL
        vertical.isScribble -> ScribbleAxis.VERTICAL
        else -> null
    }

    fun along(axis: ScribbleAxis) = if (axis == ScribbleAxis.HORIZONTAL) horizontal else vertical
}

internal fun scribbleShape(points: List<StrokePoint>): ScribbleShape? {
    if (points.size < MINIMUM_SCRIBBLE_POINTS) return null
    val samples = densify(points)
    return ScribbleShape(
        samples,
        axisShape(samples, ScribbleAxis.HORIZONTAL),
        axisShape(samples, ScribbleAxis.VERTICAL),
    )
}

/**
 * The axis the pen goes back and forth on, or null if the stroke is no scribble. Shape only —
 * [inkCoverage] decides whether there is anything to erase.
 */
fun scribbleAxis(points: List<StrokePoint>): ScribbleAxis? = scribbleShape(points)?.axis

/**
 * The area a scribble covers: the triangles between each three consecutive turning points (what
 * the eye fills in between the passes of a zig-zag), plus the pen's own path. Sideways it ends at
 * the outermost turns.
 */
class ScribbleArea private constructor(
    private val triangles: FloatArray, // 6 floats per triangle
    private val triangleBounds: FloatArray, // left, right, top, bottom per triangle
    private val path: List<Pt>,
    private val grid: Map<Long, List<Pt>>,
    /** Axis the pen went back and forth on. */
    val axis: ScribbleAxis,
    /** Typical length of one pass along [axis]. */
    val passLength: Float,
) {
    val left: Float = path.minOf { it.x }
    val right: Float = path.maxOf { it.x }
    val top: Float = path.minOf { it.y }
    val bottom: Float = path.maxOf { it.y }

    /** How tall the scribble is: one pass for a vertical zig-zag, the whole area for a horizontal one. */
    val height: Float = if (axis == ScribbleAxis.VERTICAL) passLength else bottom - top

    fun contains(x: Float, y: Float, tolerance: Float = SCRIBBLE_PATH_TOLERANCE_PX): Boolean {
        if (x < left - tolerance || x > right + tolerance || y < top - tolerance || y > bottom + tolerance) return false
        for (t in 0 until triangles.size / 6) {
            val b = t * 4
            if (x < triangleBounds[b] || x > triangleBounds[b + 1] || y < triangleBounds[b + 2] || y > triangleBounds[b + 3]) continue
            if (inTriangle(t * 6, x, y)) return true
        }
        val cx = floor(x / GRID_CELL_PX).toInt()
        val cy = floor(y / GRID_CELL_PX).toInt()
        val reach = ceil(tolerance / GRID_CELL_PX).toInt()
        val t2 = tolerance * tolerance
        for (i in -reach..reach) for (j in -reach..reach) {
            val cell = grid[key(cx + i, cy + j)] ?: continue
            for (p in cell) {
                val dx = p.x - x
                val dy = p.y - y
                if (dx * dx + dy * dy <= t2) return true
            }
        }
        return false
    }

    private fun inTriangle(o: Int, px: Float, py: Float): Boolean {
        val ax = triangles[o]; val ay = triangles[o + 1]
        val bx = triangles[o + 2]; val by = triangles[o + 3]
        val cx = triangles[o + 4]; val cy = triangles[o + 5]
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val negative = d1 < 0 || d2 < 0 || d3 < 0
        val positive = d1 > 0 || d2 > 0 || d3 > 0
        return !(negative && positive)
    }

    companion object {
        private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        internal fun of(samples: List<Pt>, shape: AxisShape): ScribbleArea {
            val corners = ArrayList<Pt>(shape.turns.size + 2)
            corners += samples.first()
            shape.turns.forEach { corners += samples[it] }
            corners += samples.last()
            val count = max(corners.size - 2, 0)
            val triangles = FloatArray(count * 6)
            val bounds = FloatArray(count * 4)
            for (i in 0 until count) {
                val a = corners[i]; val b = corners[i + 1]; val c = corners[i + 2]
                triangles[i * 6] = a.x; triangles[i * 6 + 1] = a.y
                triangles[i * 6 + 2] = b.x; triangles[i * 6 + 3] = b.y
                triangles[i * 6 + 4] = c.x; triangles[i * 6 + 5] = c.y
                bounds[i * 4] = min(a.x, min(b.x, c.x)); bounds[i * 4 + 1] = max(a.x, max(b.x, c.x))
                bounds[i * 4 + 2] = min(a.y, min(b.y, c.y)); bounds[i * 4 + 3] = max(a.y, max(b.y, c.y))
            }
            val grid = HashMap<Long, MutableList<Pt>>()
            for (p in samples) {
                grid.getOrPut(key(floor(p.x / GRID_CELL_PX).toInt(), floor(p.y / GRID_CELL_PX).toInt())) { ArrayList() } += p
            }
            return ScribbleArea(triangles, bounds, samples, grid, shape.axis, shape.passLength)
        }

        /** The area of [points] as a scribble along [axis] (shape not checked). */
        fun of(points: List<StrokePoint>, axis: ScribbleAxis): ScribbleArea {
            val samples = densify(points)
            return of(samples, axisShape(samples, axis))
        }
    }
}

/** Page-coordinate extent of a stroke's own points (not the padded Stroke bounds). */
private fun pointExtent(stroke: Stroke): Float {
    if (stroke.points.isEmpty()) return 0f
    val w = stroke.points.maxOf { it.x } - stroke.points.minOf { it.x }
    val h = stroke.points.maxOf { it.y } - stroke.points.minOf { it.y }
    return max(w, h)
}

private fun nearArea(stroke: Stroke, area: ScribbleArea, above: Float = 0f): Boolean {
    val margin = SCRIBBLE_PATH_TOLERANCE_PX
    return stroke.right >= area.left - margin && stroke.left <= area.right + margin &&
            stroke.bottom >= area.top - margin - above && stroke.top <= area.bottom + margin
}

/**
 * Share of the area's width that already holds ink from [strokes] (10 px columns). Near 0 for a
 * word written on blank paper, near 1 for a scribble over a word or across a line. Columns, not
 * rows, for both axes: a "g" tail hanging down into a word written on the next line fills a short
 * area's few rows, never its columns.
 */
fun inkCoverage(area: ScribbleArea, strokes: List<Stroke>): Float {
    val columns = max(1, floor((area.right - area.left) / COVERAGE_BIN_PX).toInt() + 1)
    val covered = BooleanArray(columns)
    for (stroke in strokes) {
        if (!nearArea(stroke, area)) continue
        for (p in densify(stroke.points)) {
            val c = floor((p.x - area.left) / COVERAGE_BIN_PX).toInt().coerceIn(0, columns - 1)
            if (!covered[c] && area.contains(p.x, p.y)) covered[c] = true
        }
    }
    return covered.count { it } / columns.toFloat()
}

/** Minimum ink coverage for a scribble on [axis]. */
fun requiredInkCoverage(axis: ScribbleAxis): Float = when (axis) {
    ScribbleAxis.HORIZONTAL -> SCRIBBLE_MIN_INK_COVERAGE
    ScribbleAxis.VERTICAL -> SCRIBBLE_MIN_INK_COVERAGE_VERTICAL
}

/** The strokes a scribble covering [area] erases. */
fun selectScribbledStrokes(area: ScribbleArea, strokes: List<Stroke>): List<Stroke> {
    val minRun = max(SCRIBBLE_ERASE_MIN_RUN_PX, 0.5f * min(area.right - area.left, area.bottom - area.top))
    // i-dots sit up to most of a letter height above the letters the scribble covers. Only dots
    // reach that far: a small letter of the line above is no dot and needs to be scribbled itself.
    val dotReach = area.height.coerceIn(10f, 40f)
    return strokes.filter { stroke ->
        if (!nearArea(stroke, area, above = dotReach)) return@filter false
        if (pointExtent(stroke) <= SCRIBBLE_DOT_PX) {
            return@filter stroke.points.any { p ->
                var dy = -4f // a pen width below counts too: a comma the scribble ends on
                var hit = false
                while (dy <= dotReach && !hit) {
                    hit = area.contains(p.x, p.y + dy, tolerance = DOT_TOLERANCE_PX)
                    dy += 3f
                }
                hit
            }
        }
        // Length-weighted: a segment counts as covered when both its ends lie in the area.
        val samples = densify(stroke.points)
        var total = 0f
        var covered = 0f
        var run = 0f
        var longestRun = 0f
        var previousInside = area.contains(samples[0].x, samples[0].y)
        for (i in 1 until samples.size) {
            val length = hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
            val inside = area.contains(samples[i].x, samples[i].y)
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

/** Duration of a stroke from its points' delta times, or null if they carry none. */
private fun durationMs(points: List<StrokePoint>): Int? = points.lastOrNull()?.dt?.toInt()

/**
 * The strokes this pen stroke erases as a scribble, or an empty list if it isn't one: shape
 * ([scribbleAxis]), ink underneath ([inkCoverage]), then the covered area
 * ([selectScribbledStrokes]). The one decision both pen-up ([handleScribbleToErase]) and the live
 * red pen ([LiveScribbleCheck]) use.
 *
 * [rushed]: the stroke began within the grace period after another one. Then it also has to go on
 * for [SCRIBBLE_GRACE_OVERRIDE_MS] with [SCRIBBLE_GRACE_OVERRIDE_REVERSALS] sharp turns.
 */
fun scribbleTargets(points: List<StrokePoint>, strokes: List<Stroke>, rushed: Boolean = false): List<Stroke> {
    val shape = scribbleShape(points) ?: return emptyList()
    return scribbleTargets(shape, points, strokes, rushed)
}

private fun scribbleTargets(
    shape: ScribbleShape, points: List<StrokePoint>, strokes: List<Stroke>, rushed: Boolean,
): List<Stroke> {
    val axis = shape.axis ?: return emptyList()
    val along = shape.along(axis)
    if (rushed) {
        val duration = durationMs(points) ?: return emptyList()
        if (duration < SCRIBBLE_GRACE_OVERRIDE_MS || along.sharpTurns < SCRIBBLE_GRACE_OVERRIDE_REVERSALS) return emptyList()
    }
    val area = ScribbleArea.of(shape.samples, along)
    if (inkCoverage(area, strokes) < requiredInkCoverage(axis)) return emptyList()
    return selectScribbledStrokes(area, strokes)
}

/**
 * Pen-up for a stroke that already turned red: it erases, whatever the rest of the stroke did.
 * What the whole stroke covers now, plus what it covered when it turned red ([atRed]) — a scribble
 * that wanders off its ink after turning red must not silently become ink itself.
 */
fun confirmedScribbleTargets(points: List<StrokePoint>, strokes: List<Stroke>, atRed: List<Stroke>): List<Stroke> {
    val stillThere = atRed.map { it.id }.toSet()
    val now = scribbleShape(points)?.let { shape ->
        val axis = shape.axis ?: return@let emptyList()
        selectScribbledStrokes(ScribbleArea.of(shape.samples, shape.along(axis)), strokes)
    } ?: emptyList()
    val ids = now.map { it.id }.toSet()
    return now + strokes.filter { it.id in stillThere && it.id !in ids }
}

/** Live re-checks while the pen is down are at least this far apart (and need new points). */
const val LIVE_SCRIBBLE_CHECK_INTERVAL_MS = 80L

/**
 * Fork: answers "would lifting the pen now erase something?" while a stroke is still being drawn,
 * cheaply enough to run on the move callbacks. Points are fed one at a time; the full check runs at
 * most every [LIVE_SCRIBBLE_CHECK_INTERVAL_MS] and only after the cheap shape test passes. Once it
 * says yes it stays yes for the stroke — the pen doesn't flicker between red and its own colour —
 * and pen-up erases ([confirmedScribbleTargets] with [targets]).
 */
class LiveScribbleCheck(private val intervalMs: Long = LIVE_SCRIBBLE_CHECK_INTERVAL_MS) {
    private val points = ArrayList<StrokePoint>(256)
    private var lastCheckAt = Long.MIN_VALUE
    private var pointsAtLastCheck = 0
    private var rushed = false

    /** What the stroke erased when it turned red; empty while it hasn't. */
    var targets: List<Stroke> = emptyList()
        private set
    val detected: Boolean get() = targets.isNotEmpty()

    /** [rushed]: the stroke began within the grace period after another one (see [scribbleTargets]). */
    fun reset(rushed: Boolean = false) {
        points.clear()
        lastCheckAt = Long.MIN_VALUE
        pointsAtLastCheck = 0
        targets = emptyList()
        this.rushed = rushed
    }

    /**
     * Adds [point] and returns true exactly once: on the call where the stroke first counts as a
     * scribble that erases something. [strokes] is only read when a check actually runs.
     */
    fun add(point: StrokePoint, nowMs: Long, strokes: () -> List<Stroke>): Boolean {
        if (detected) return false
        points += point
        if (points.size < MINIMUM_SCRIBBLE_POINTS) return false
        if (points.size == pointsAtLastCheck) return false
        if (lastCheckAt != Long.MIN_VALUE && nowMs - lastCheckAt < intervalMs) return false
        lastCheckAt = nowMs
        pointsAtLastCheck = points.size
        // Shape first: it is O(points) and rules out ordinary writing without touching the page.
        val shape = scribbleShape(points) ?: return false
        if (shape.axis == null) return false
        val found = scribbleTargets(shape, points, strokes(), rushed)
        if (found.isEmpty()) return false
        targets = found
        return true
    }
}

/**
 * Colour the pen turns while a scribble would erase: red, or black when the pen itself is red
 * (red on red shows nothing). [penColor] is ARGB.
 */
fun liveScribbleColor(penColor: Int): Int {
    val r = (penColor shr 16) and 0xff
    val g = (penColor shr 8) and 0xff
    val b = penColor and 0xff
    val reddish = r >= 150 && g <= 110 && b <= 110
    return if (reddish) LIVE_SCRIBBLE_BLACK else LIVE_SCRIBBLE_RED
}

const val LIVE_SCRIBBLE_RED = 0xFFFF0000.toInt()
const val LIVE_SCRIBBLE_BLACK = 0xFF000000.toInt()
