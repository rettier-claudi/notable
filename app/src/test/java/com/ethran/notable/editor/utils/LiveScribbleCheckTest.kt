package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live red pen: same decision as pen-up, reached while drawing, cheap between checks. */
class LiveScribbleCheckTest {

    private fun stroke(points: List<StrokePoint>) = Stroke(
        size = 5f, pen = Pen.BALLPEN,
        top = points.minOf { it.y } - 5f, bottom = points.maxOf { it.y } + 5f,
        left = points.minOf { it.x } - 5f, right = points.maxOf { it.x } + 5f,
        points = points, pageId = "p",
    )

    /** A word-sized horizontal line of "ink" from x0 to x1 at height y. */
    private fun ink(x0: Float, x1: Float, y: Float) =
        stroke((0..40).map { StrokePoint(x0 + (x1 - x0) * it / 40, y) })

    private fun zigZag(x0: Float, x1: Float, yTop: Float, yBottom: Float, passes: Int): List<StrokePoint> {
        val out = mutableListOf(StrokePoint(x0, yTop))
        for (i in 1..passes) {
            val x = if (i % 2 == 0) x0 else x1
            val y = yTop + (yBottom - yTop) * i / passes
            val last = out.last()
            for (k in 1..10) out += StrokePoint(last.x + (x - last.x) * k / 10, last.y + (y - last.y) * k / 10)
        }
        return out
    }

    /** Feeds [points] 10 ms apart; returns the index of the point that turned it red, or null. */
    private fun feed(check: LiveScribbleCheck, points: List<StrokePoint>, page: List<Stroke>): Int? {
        var redAt: Int? = null
        points.forEachIndexed { i, p ->
            if (check.add(p, i * 10L) { page }) {
                assertEquals("turns red only once", null, redAt)
                redAt = i
            }
        }
        return redAt
    }

    @Test
    fun `scribble over ink turns red before the pen is lifted`() {
        val page = listOf(ink(100f, 200f, 300f))
        val scribble = zigZag(100f, 200f, 290f, 310f, passes = 12)
        val check = LiveScribbleCheck()
        val redAt = feed(check, scribble, page)
        assertTrue("red somewhere in the stroke", redAt != null)
        assertTrue("red before the end (at $redAt of ${scribble.size})", redAt!! < scribble.size - 1)
        assertTrue(check.detected)
    }

    @Test
    fun `red exactly when pen-up would erase`() {
        val page = listOf(ink(100f, 200f, 300f))
        val scribble = zigZag(100f, 200f, 290f, 310f, passes = 12)
        val redAt = feed(LiveScribbleCheck(intervalMs = 0), scribble, page)!!
        // pen lifted right at the red point erases; any earlier lift does not
        assertTrue(scribbleTargets(scribble.subList(0, redAt + 1), page).isNotEmpty())
        assertTrue(scribbleTargets(scribble.subList(0, redAt), page).isEmpty())
    }

    @Test
    fun `scribble on blank paper stays its own colour`() {
        val page = listOf(ink(100f, 200f, 800f)) // ink elsewhere on the page
        assertEquals(null, feed(LiveScribbleCheck(), zigZag(100f, 200f, 290f, 310f, passes = 12), page))
    }

    @Test
    fun `a straight line over ink is no scribble`() {
        val page = listOf(ink(100f, 200f, 300f))
        val line = (0..60).map { StrokePoint(100f + it * 2f, 300f) }
        assertEquals(null, feed(LiveScribbleCheck(), line, page))
    }

    @Test
    fun `the page is not read while the shape is no scribble`() {
        var reads = 0
        val check = LiveScribbleCheck(intervalMs = 0)
        (0..200).forEach { check.add(StrokePoint(100f + it, 300f), it.toLong()) { reads++; emptyList() } }
        assertEquals(0, reads)
    }

    @Test
    fun `checks are throttled`() {
        var reads = 0
        val page = listOf(ink(100f, 200f, 800f))
        val check = LiveScribbleCheck(intervalMs = 80)
        // 12 passes ≈ 121 points fed 1 ms apart: one check at most per 80 ms
        zigZag(100f, 200f, 290f, 310f, passes = 12).forEachIndexed { i, p ->
            check.add(p, i.toLong()) { reads++; page }
        }
        assertTrue("reads $reads", reads <= 2)
        assertFalse(check.detected)
    }

    @Test
    fun `reset starts a new stroke`() {
        val page = listOf(ink(100f, 200f, 300f))
        val check = LiveScribbleCheck()
        feed(check, zigZag(100f, 200f, 290f, 310f, passes = 12), page)
        check.reset()
        assertFalse(check.detected)
        assertTrue(feed(check, zigZag(100f, 200f, 290f, 310f, passes = 12), page) != null)
    }
}

/** Fork: red means erase, and the red pen turns black instead. */
class LiveScribbleRedLatchTest {
    private fun ink(x0: Float, x1: Float, y: Float): Stroke {
        val points = (0..40).map { StrokePoint(x0 + (x1 - x0) * it / 40, y) }
        return Stroke(
            size = 5f, pen = Pen.BALLPEN, top = y - 5f, bottom = y + 5f, left = x0 - 5f, right = x1 + 5f,
            points = points, pageId = "p",
        )
    }

    @Test
    fun `a stroke that turned red erases even if it then wanders off the ink`() {
        val word = ink(100f, 200f, 300f)
        val out = mutableListOf(StrokePoint(100f, 290f))
        for (i in 1..12) {
            val x = if (i % 2 == 0) 100f else 200f
            val last = out.last()
            for (k in 1..10) out += StrokePoint(last.x + (x - last.x) * k / 10, last.y + (290f + 20f * i / 12 - last.y) * k / 10)
        }
        val check = LiveScribbleCheck(intervalMs = 0)
        out.forEachIndexed { i, p -> check.add(p, i.toLong()) { listOf(word) } }
        assertTrue(check.detected)
        // Then a long zig-zag over blank paper: on its own the whole stroke would not count any more.
        for (i in 1..30) {
            val x = if (i % 2 == 0) 200f else 900f
            out += StrokePoint(x, 310f + i * 30f)
        }
        assertTrue(scribbleTargets(out, listOf(word)).isEmpty())
        assertEquals(listOf(word), confirmedScribbleTargets(out, listOf(word), check.targets))
    }

    @Test
    fun `red pen turns black, others red`() {
        assertEquals(LIVE_SCRIBBLE_BLACK, liveScribbleColor(0xFFE53935.toInt()))
        assertEquals(LIVE_SCRIBBLE_RED, liveScribbleColor(0xFF000000.toInt()))
        assertEquals(LIVE_SCRIBBLE_RED, liveScribbleColor(0xFF00FF00.toInt()))
        assertEquals(LIVE_SCRIBBLE_RED, liveScribbleColor(0xFF1E88E5.toInt()))
    }
}
