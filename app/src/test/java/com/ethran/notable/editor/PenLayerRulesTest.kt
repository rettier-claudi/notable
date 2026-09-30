package com.ethran.notable.editor

import com.ethran.notable.editor.canvas.isStrayStroke
import com.ethran.notable.editor.canvas.penMayDraw
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenLayerRulesTest {

    @Test
    fun `drawing is granted without selection or lock`() {
        assertTrue(penMayDraw(requested = true, pageLocked = false, selectionOpen = false))
    }

    @Test
    fun `an open selection keeps the pen off`() {
        // the late "drawing on" that used to arrive while pasted content was still floating
        assertFalse(penMayDraw(requested = true, pageLocked = false, selectionOpen = true))
    }

    @Test
    fun `a locked page keeps the pen off`() {
        assertFalse(penMayDraw(requested = true, pageLocked = true, selectionOpen = false))
    }

    @Test
    fun `turning drawing off is always granted`() {
        assertFalse(penMayDraw(requested = false, pageLocked = false, selectionOpen = false))
    }

    @Test
    fun `stroke begun and ended with drawing off is stray`() {
        assertTrue(isStrayStroke(drawingAtBegin = false, drawingAtEnd = false))
    }

    @Test
    fun `lasso that switches drawing off mid-stroke is not stray`() {
        assertFalse(isStrayStroke(drawingAtBegin = true, drawingAtEnd = false))
    }

    @Test
    fun `stroke without begin callback is not stray`() {
        assertFalse(isStrayStroke(drawingAtBegin = null, drawingAtEnd = false))
    }

    @Test
    fun `stroke while drawing is on is not stray`() {
        assertFalse(isStrayStroke(drawingAtBegin = false, drawingAtEnd = true))
        assertFalse(isStrayStroke(drawingAtBegin = true, drawingAtEnd = true))
    }
}
