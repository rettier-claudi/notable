package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Handwriting recorded on the Note Air 5C ("Test" twice, see scribble-tes-t.txt). The scribbles in
 * the recording are five plain up-down lines, not a zig-zag, so the tests scribble over it with a
 * synthetic zig-zag in the same place.
 */
class ScribbleRealStrokesTest {

    private fun load(letterScale: Float = 1f): Map<String, Stroke> {
        val text = javaClass.classLoader!!.getResource("scribble-tes-t.txt")!!.readText()
        val raw = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (name, data) = line.split("|")
            name to data.split(" ").map { val (x, y) = it.split(","); StrokePoint(x.toFloat(), y.toFloat()) }
        }
        // Smaller handwriting, same spacing: each letter shrinks around its own centre.
        val letter = { name: String -> name.substringBefore(" stem").substringBefore(" bar") }
        val centres = raw.entries.groupBy { letter(it.key) }.mapValues { (_, strokes) ->
            val points = strokes.flatMap { it.value }
            StrokePoint((points.minOf { it.x } + points.maxOf { it.x }) / 2, (points.minOf { it.y } + points.maxOf { it.y }) / 2)
        }
        return raw.mapValues { (name, original) ->
            val c = centres.getValue(letter(name))
            val points = original.map { StrokePoint(c.x + (it.x - c.x) * letterScale, c.y + (it.y - c.y) * letterScale) }
            Stroke(
                size = 5f, pen = Pen.BALLPEN,
                top = points.minOf { it.y } - 5f, bottom = points.maxOf { it.y } + 5f,
                left = points.minOf { it.x } - 5f, right = points.maxOf { it.x } + 5f,
                points = points, pageId = "p",
            )
        }
    }

    private fun zigZag(x0: Float, x1: Float, yTop: Float, yBottom: Float, passes: Int = 10): List<StrokePoint> {
        val out = mutableListOf<StrokePoint>()
        for (i in 0..passes) {
            val x = if (i % 2 == 0) x0 else x1
            val y = yTop + (yBottom - yTop) * i / passes
            if (out.isNotEmpty()) {
                val last = out.last()
                for (k in 1..10) out += StrokePoint(last.x + (x - last.x) * k / 10, last.y + (y - last.y) * k / 10)
            } else out += StrokePoint(x, y)
        }
        return out
    }

    private fun erasedBy(scribble: List<StrokePoint>, page: Map<String, Stroke>): Set<String> {
        val ink = page.filterKeys { !it.startsWith("scribble") }
        val axis = scribbleAxis(scribble)!!
        val envelope = ScribbleEnvelope.of(scribble)
        val coverage = inkCoverage(envelope, ink.values.toList())
        assert(coverage >= requiredInkCoverage(axis)) { "coverage $coverage" }
        val erased = selectScribbledStrokes(envelope, ink.values.toList())
        return ink.filterValues { it in erased }.keys
    }

    @Test
    fun `scribbling out the last t takes only the t`() {
        val page = load()
        assertEquals(setOf("t stem", "t bar"), erasedBy(zigZag(999f, 1024f, 570f, 646f), page))
    }

    @Test
    fun `smaller handwriting - the letters before the t stay`() {
        // At 85 % the e and s are no bigger than an i-dot; the scribble still does not touch them.
        val page = load(letterScale = 0.85f)
        val t = listOf("t stem", "t bar").flatMap { page.getValue(it).points }
        val scribble = zigZag(t.minOf { it.x } - 2f, t.maxOf { it.x } + 2f, t.minOf { it.y } - 2f, t.maxOf { it.y } + 2f)
        assertEquals(setOf("t stem", "t bar"), erasedBy(scribble, page))
    }

    @Test
    fun `scribbling out the last two letters leaves the rest of the word`() {
        val page = load()
        assertEquals(setOf("s", "t stem", "t bar"), erasedBy(zigZag(970f, 1024f, 570f, 646f), page))
    }
}
