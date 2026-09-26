package com.ethran.notable.editor.drawing

/**
 * Opaque band colour of a highlighter: [color] mixed half-and-half with white. Plain ints (no
 * android.graphics.Color) so it runs in unit tests; drawn with DARKEN in [drawMarkerStroke].
 */
fun markerInkColor(color: Int): Int {
    fun towardWhite(shift: Int) = (((color shr shift) and 0xFF) + 256) / 2 shl shift
    return (0xFF shl 24) or towardWhite(16) or towardWhite(8) or towardWhite(0)
}
