package com.ethran.notable.editor.drawing

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StrokeOrderTest {

    private fun stroke(id: String, pen: Pen) = Stroke(
        id = id,
        size = 3f,
        pen = pen,
        top = 0f,
        bottom = 10f,
        left = 0f,
        right = 10f,
        points = emptyList(),
        pageId = "p",
    )

    @Test
    fun `markers are drawn before all other pens, each group keeps its order`() {
        val strokes = listOf(
            stroke("a", Pen.BALLPEN),
            stroke("m1", Pen.MARKER),
            stroke("b", Pen.FOUNTAIN),
            stroke("m2", Pen.MARKER),
            stroke("c", Pen.BALLPEN),
        )
        assertEquals(listOf("m1", "m2", "a", "b", "c"), inDrawingOrder(strokes).map { it.id })
    }

    @Test
    fun `without markers the list is returned as is`() {
        val strokes = listOf(stroke("a", Pen.BALLPEN), stroke("b", Pen.BRUSH))
        assertSame(strokes, inDrawingOrder(strokes))
    }
}
