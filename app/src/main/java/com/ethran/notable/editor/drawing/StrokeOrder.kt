package com.ethran.notable.editor.drawing

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen

/**
 * Drawing order for a page: highlighter (marker) strokes first, everything else on top, each
 * group in its stored order. So a marker drawn over existing writing never covers it, and
 * writing added later over a marker stays on top as before. Only the render order changes;
 * the stored stroke list keeps insertion order.
 */
fun inDrawingOrder(strokes: List<Stroke>): List<Stroke> {
    if (strokes.none { it.pen == Pen.MARKER }) return strokes
    val (markers, others) = strokes.partition { it.pen == Pen.MARKER }
    return markers + others
}
