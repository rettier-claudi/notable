package com.ethran.notable.tickets

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The small grey answers of the bridge next to a ticket row ("→ 🥋", "✗"). An overlay of this
 * process only: drawn over the page in the editor, never a stroke, never saved, never synced.
 * One label per ticket and page; the newest replaces the older one.
 */
object TicketLabels {
    data class Label(val anchor: Box, val text: String)

    private val byPage = ConcurrentHashMap<String, ConcurrentHashMap<String, Label>>()
    private val _changes = MutableSharedFlow<Pair<String, Box>>(extraBufferCapacity = 32)

    /** (pageId, row) whenever a label appears or changes, so the open editor repaints that row. */
    val changes: SharedFlow<Pair<String, Box>> = _changes.asSharedFlow()

    fun set(pageId: String, ticket: String, anchor: Box, text: String) {
        byPage.getOrPut(pageId) { ConcurrentHashMap() }[ticket] = Label(anchor, text)
        _changes.tryEmit(pageId to anchor)
    }

    fun forPage(pageId: String): Collection<Label> = byPage[pageId]?.values.orEmpty()

    private val paint by lazy {
        Paint().apply {
            isAntiAlias = true
            color = GREY
            typeface = Typeface.DEFAULT
        }
    }

    /**
     * Draws the labels of [pageId] that touch [pageArea] (page coordinates). [offset] is what the
     * strokes get too (minus the scroll); [maxX] is the right page edge in canvas coordinates, so
     * a label right of a full-width row still stays on the page.
     */
    fun draw(canvas: Canvas, pageId: String, pageArea: Rect, offset: Offset, maxX: Float) {
        val labels = forPage(pageId)
        if (labels.isEmpty()) return
        for (l in labels) {
            if (l.anchor.y2 < pageArea.top || l.anchor.y1 > pageArea.bottom) continue
            paint.textSize = (l.anchor.h * 0.45f).coerceIn(22f, 40f)
            val width = paint.measureText(l.text)
            val x = minOf(l.anchor.x2 + GAP, maxX - width - GAP)
            val y = l.anchor.cy + paint.textSize * 0.35f
            canvas.drawText(l.text, x + offset.x, y + offset.y, paint)
        }
    }

    private const val GAP = 10f
    private const val GREY = 0xFF808080.toInt()
}
