package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EraserFilterTest {

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

    private val strokes = listOf(
        stroke("a", Pen.BALLPEN),
        stroke("m1", Pen.MARKER),
        stroke("b", Pen.FOUNTAIN),
        stroke("m2", Pen.MARKER),
    )

    @Test
    fun `marker eraser only sees highlighter strokes`() {
        assertEquals(listOf("m1", "m2"), erasableStrokes(strokes, Eraser.MARKER).map { it.id })
    }

    @Test
    fun `pen and lasso eraser see everything`() {
        assertSame(strokes, erasableStrokes(strokes, Eraser.PEN))
        assertSame(strokes, erasableStrokes(strokes, Eraser.SELECT))
    }
}
