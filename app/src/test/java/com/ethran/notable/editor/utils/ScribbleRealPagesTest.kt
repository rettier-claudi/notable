package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Philipp's scribble tests from the Note Air 5C (2026-10-08, see scribble-tests-2026-10-08.txt.gz).
 * Each scribble is replayed against the strokes drawn before it. The numbers in the test names are
 * the ones he wrote next to the tests on page 1.
 */
class ScribbleRealPagesTest {

    private val pages: Map<String, List<Stroke>> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("scribble-tests-2026-10-08.txt.gz")!!
        val text = GZIPInputStream(stream).bufferedReader().readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (page, index, data) = line.split("|")
            val points = data.split(" ").map {
                val (x, y, dt) = it.split(",")
                StrokePoint(x.toFloat(), y.toFloat(), dt = dt.toInt().toUShort())
            }
            page to Stroke(
                id = "$page-$index", size = 3f, pen = Pen.FOUNTAIN,
                top = points.minOf { it.y } - 3f, bottom = points.maxOf { it.y } + 3f,
                left = points.minOf { it.x } - 3f, right = points.maxOf { it.x } + 3f,
                points = points, pageId = page,
            )
        }.groupBy({ it.first }, { it.second })
    }

    private fun stroke(page: String, index: Int) = pages.getValue(page).first { it.id == "$page-$index" }

    /** Indices [scribble] on [page] erases, given everything drawn before it. */
    private fun erasedBy(page: String, scribble: Int, rushed: Boolean = false): Set<Int> {
        val before = pages.getValue(page).filter { it.id.substringAfterLast("-").toInt() < scribble }
        return scribbleTargets(stroke(page, scribble).points, before, rushed)
            .map { it.id.substringAfterLast("-").toInt() }.toSet()
    }

    @Test
    fun `1 - the last t of Test goes with the rest`() {
        assertEquals((0..4).toSet(), erasedBy("page1", 5))
    }

    @Test
    fun `2 - the bar of the T goes too`() {
        assertEquals((6..10).toSet(), erasedBy("page1", 11))
    }

    @Test
    fun `3 - a big sloppy scribble over two lines takes all of them`() {
        assertEquals((13..43).toSet(), erasedBy("page1", 44))
        assertEquals((113..131).toSet(), erasedBy("page2", 132))
    }

    @Test
    fun `4 and 5 - scribbling over part of a long curve takes the curve`() {
        assertEquals(setOf(45), erasedBy("page1", 51))
        assertEquals(setOf(45), erasedBy("page1", 52))
        assertEquals(setOf(45), erasedBy("page1", 53))
    }

    @Test
    fun `6 - a letter of the line above stays`() {
        // Old geometry also took 70-72 ("scr" of "scribbles") from the line above.
        assertEquals(setOf(87), erasedBy("page1", 99))
    }

    @Test
    fun `neighbouring letters stay`() {
        assertEquals("t of mit, not the i-dot of mi", setOf(90), erasedBy("page2", 110))
        assertEquals("n of ziehen, not the e", setOf(98), erasedBy("page2", 111))
        assertEquals("all of Gestures, the t too", (101..109).toSet(), erasedBy("page2", 112))
    }

    @Test
    fun `circles and filled dots are no scribbles`() {
        for (i in 122..128) assertNull("stroke $i", scribbleAxis(stroke("page1", i).points))
    }

    @Test
    fun `cursive writing across the descender of the line above erases nothing`() {
        assertEquals(emptySet<Int>(), erasedBy("cursive", 53))
    }

    @Test
    fun `a loop drawn around a line of text is no scribble`() {
        assertNull(scribbleAxis(stroke("lasso", 208).points))
    }

    @Test
    fun `no handwriting on the test pages counts as a scribble that erases`() {
        val scribbles = setOf(5, 11, 44, 50, 51, 52, 53, 82, 83, 84, 99, 111, 121)
        for (s in pages.getValue("page1")) {
            val i = s.id.substringAfterLast("-").toInt()
            if (i in scribbles) continue
            assertEquals("page1 stroke $i", emptySet<Int>(), erasedBy("page1", i))
        }
    }

    @Test
    fun `a quick scribble right after writing stays ink, a long one erases`() {
        // 83 began 24 ms after the previous stroke and lasted 622 ms with 6 sharp turns; 82 lasted 392 ms.
        assertEquals(emptySet<Int>(), erasedBy("page1", 82, rushed = true))
        assertTrue(erasedBy("page1", 82).isNotEmpty())
        assertEquals(erasedBy("page1", 83), erasedBy("page1", 83, rushed = true))
    }
}
