package com.ethran.notable.editor.drawing

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerInkTest {

    @Test
    fun `band colour is halfway to white and opaque`() {
        // LTGRAY, the default marker: same shade the Onyx wrapper's 50 % alpha gave on white.
        assertEquals(0xFFE6E6E6.toInt(), markerInkColor(0xFFCCCCCC.toInt()))
        assertEquals(0xFF808080.toInt(), markerInkColor(0xFF000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), markerInkColor(0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFF80.toInt(), markerInkColor(0xFFFFFF00.toInt()))
    }

    @Test
    fun `stored alpha does not leak into the band`() {
        assertEquals(0xFFE6E6E6.toInt(), markerInkColor(0x40CCCCCC))
    }
}
