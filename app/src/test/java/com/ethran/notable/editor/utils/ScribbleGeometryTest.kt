package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic handwriting at roughly Note Air 5C scale (~12 px per mm): x-height 35 px, lines 90 px
 * apart. Shapes are crude, but they reverse, retrace and overlap the way the real thing does.
 */
class ScribbleGeometryTest {

    private fun polyline(vararg xy: Float, step: Float = 2f): List<StrokePoint> {
        val out = mutableListOf(StrokePoint(xy[0], xy[1]))
        for (i in 2 until xy.size step 2) {
            val ax = xy[i - 2]; val ay = xy[i - 1]; val bx = xy[i]; val by = xy[i + 1]
            val n = maxOf(1, (kotlin.math.hypot(bx - ax, by - ay) / step).toInt())
            for (k in 1..n) out += StrokePoint(ax + (bx - ax) * k / n, ay + (by - ay) * k / n)
        }
        return out
    }

    private fun stroke(points: List<StrokePoint>, size: Float = 3f) = Stroke(
        size = size,
        pen = Pen.BALLPEN,
        top = points.minOf { it.y } - size,
        bottom = points.maxOf { it.y } + size,
        left = points.minOf { it.x } - size,
        right = points.maxOf { it.x } + size,
        points = points,
        pageId = "p",
    )

    /** "m" humps from [x0], baseline [baseline], [count] letters, each 30 px wide. */
    private fun mmm(x0: Float, baseline: Float, count: Int): List<StrokePoint> {
        val xy = mutableListOf(x0, baseline)
        for (i in 0 until count) {
            val x = x0 + i * 30f
            xy += listOf(x, baseline - 35f, x + 8f, baseline - 35f, x + 15f, baseline,
                x + 15f, baseline - 35f, x + 23f, baseline - 35f, x + 30f, baseline)
        }
        return polyline(*xy.toFloatArray())
    }

    /** Horizontal back-and-forth between [x0] and [x1], drifting from [yTop] to [yBottom]. */
    private fun horizontalScribble(x0: Float, x1: Float, yTop: Float, yBottom: Float, passes: Int = 8): List<StrokePoint> {
        val xy = mutableListOf(x0, yTop)
        for (i in 1..passes) {
            xy += if (i % 2 == 1) x1 else x0
            xy += yTop + (yBottom - yTop) * i / passes
        }
        return polyline(*xy.toFloatArray())
    }

    // --- Is it a scribble? --------------------------------------------------------------------

    @Test
    fun `back and forth scribble is recognised as horizontal`() {
        assertEquals(ScribbleAxis.HORIZONTAL, scribbleAxis(horizontalScribble(100f, 300f, 100f, 140f)))
    }

    @Test
    fun `handwritten mmm is not a horizontal scribble`() {
        assertTrue(scribbleAxis(mmm(100f, 200f, 4)) != ScribbleAxis.HORIZONTAL)
    }

    @Test
    fun `a plain line is no scribble`() {
        assertNull(scribbleAxis(polyline(0f, 0f, 400f, 5f)))
    }

    @Test
    fun `mmm written under a line lies on blank paper`() {
        val lineAbove = listOf(stroke(mmm(100f, 140f, 6)))
        // Written 90 px lower: the humps reach up to 165, the line above ends at 140 + stroke size.
        val newWord = mmm(100f, 230f, 4)
        val axis = scribbleAxis(newWord)!! // humps reverse vertically: shaped like a scribble
        val coverage = inkCoverage(ScribbleEnvelope.of(newWord), lineAbove)
        assertTrue("coverage $coverage", coverage < requiredInkCoverage(axis))
    }

    @Test
    fun `scribble over a word lies on ink`() {
        val word = listOf(stroke(mmm(100f, 140f, 6)))
        val scribble = horizontalScribble(95f, 285f, 105f, 140f)
        val axis = scribbleAxis(scribble)!!
        assertTrue(inkCoverage(ScribbleEnvelope.of(scribble), word) >= requiredInkCoverage(axis))
    }

    // --- What does it erase? ------------------------------------------------------------------

    @Test
    fun `i-dot and comma next to the scribbled word go too`() {
        val word = stroke(mmm(100f, 140f, 4))
        val iDot = stroke(polyline(160f, 88f, 161f, 89f)) // ~17 px above the x-height
        val comma = stroke(polyline(225f, 138f, 222f, 152f)) // after the word, below baseline
        val scribble = horizontalScribble(95f, 220f, 108f, 138f)
        val erased = selectScribbledStrokes(ScribbleEnvelope.of(scribble), listOf(word, iDot, comma))
        assertTrue(word in erased)
        assertTrue("i-dot", iDot in erased)
        assertTrue("comma", comma in erased)
    }

    @Test
    fun `line above only brushed by a descender-height overshoot stays`() {
        val lineAbove = stroke(mmm(100f, 50f, 8))
        // A "g" with its tail hanging from the line above into the scribbled word.
        val gTail = stroke(polyline(130f, 15f, 130f, 50f, 130f, 110f, 115f, 118f))
        val word = stroke(mmm(100f, 140f, 6))
        val scribble = horizontalScribble(95f, 285f, 105f, 140f)
        val erased = selectScribbledStrokes(ScribbleEnvelope.of(scribble), listOf(lineAbove, gTail, word))
        assertTrue(word in erased)
        assertFalse(lineAbove in erased)
        assertFalse("g tail", gTail in erased)
    }

    @Test
    fun `short scribble over part of a long line erases the whole line`() {
        val line = stroke(polyline(0f, 300f, 1200f, 304f))
        val scribble = horizontalScribble(500f, 560f, 285f, 318f, passes = 6)
        assertEquals(ScribbleAxis.HORIZONTAL, scribbleAxis(scribble))
        val envelope = ScribbleEnvelope.of(scribble)
        assertTrue(inkCoverage(envelope, listOf(line)) >= SCRIBBLE_MIN_INK_COVERAGE)
        assertEquals(listOf(line), selectScribbledStrokes(envelope, listOf(line)))
    }

    @Test
    fun `a long line below the scribble stays`() {
        val scribble = horizontalScribble(100f, 300f, 105f, 140f)
        val lineBelow = stroke(polyline(0f, 175f, 1200f, 175f))
        assertTrue(selectScribbledStrokes(ScribbleEnvelope.of(scribble), listOf(lineBelow)).isEmpty())
    }

    @Test
    fun `dot of a neighbouring line well above stays`() {
        val word = stroke(mmm(100f, 140f, 4))
        val dotLineAbove = stroke(polyline(160f, 40f, 161f, 41f))
        val scribble = horizontalScribble(95f, 220f, 108f, 138f)
        val erased = selectScribbledStrokes(ScribbleEnvelope.of(scribble), listOf(word, dotLineAbove))
        assertFalse(dotLineAbove in erased)
    }
}
