package com.ethran.notable.editor.utils

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import io.shipbook.shipbooksdk.Log

enum class Eraser(val _name: String) {
    PEN("PEN"), SELECT("SELECT"),

    /** Fork: drags like [PEN] but only takes highlighter strokes; writing stays. */
    MARKER("MARKER"),
}

/** The strokes [eraser] may take at all; the swath or lasso then picks among them. */
fun erasableStrokes(strokes: List<Stroke>, eraser: Eraser): List<Stroke> =
    if (eraser == Eraser.MARKER) strokes.filter { it.pen == Pen.MARKER } else strokes

// Fork: 300 ms (upstream 150). A short lift between letters must not start a scribble.
const val SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS = 300L

/**
 * Width (px) of the pen-eraser swath: the diameter of the region [handleErase] actually deletes.
 * Shared so the native side-button eraser indicator (see einkHelper.enableNativeEraser) is drawn
 * at exactly the size it erases.
 */
const val ERASER_SWATH_WIDTH = 30f

const val MINIMUM_SCRIBBLE_POINTS = 15


// Erases strokes if touchPoints are "scribble", returns true if erased.
// returns null if not erased, dirty rectangle otherwise
fun handleScribbleToErase(
    page: PageView,
    touchPoints: List<StrokePoint>,
    history: History,
    pen: Pen,
    strokeSize: Float,
    color: Int,
    currentLastStrokeEndTime: Long,
    firstPointTime: Long
): Rect? {
    if (pen == Pen.MARKER) return null // do not erase with highlighter
    if (!GlobalAppSettings.current.scribbleToEraseEnabled) return null // scribble to erase is disabled
    if (touchPoints.size < MINIMUM_SCRIBBLE_POINTS) return null
    if (firstPointTime < currentLastStrokeEndTime + SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS) return null // not enough time has passed since last stroke

    // Fork: shape + ink underneath decide, and the strokes under the swept area go (see
    // ScribbleGeometry.kt). Upstream erased by bounding-box overlap, which left i-dots behind, took
    // the line above along, and needed a long line scribbled over for a fifth of its length.
    val axis = scribbleAxis(touchPoints) ?: return null
    val envelope = ScribbleEnvelope.of(touchPoints)
    val coverage = inkCoverage(envelope, page.strokes)
    if (coverage < requiredInkCoverage(axis)) {
        Log.d("ScribbleToErase", "Not over ink: $axis coverage $coverage")
        return null
    }
    val deletedStrokes = selectScribbledStrokes(envelope, page.strokes)

    // If strokes were found, remove them and update history
    if (deletedStrokes.isNotEmpty()) {
        val deletedStrokeIds = deletedStrokes.map { it.id }
        page.removeStrokes(deletedStrokeIds)

        // Build the scribble as a real stroke (not added to the page — the net visible result is
        // still "everything gone") so undo can bring it back. Two history blocks make undo staged:
        //   undo #1 (top block)    → re-add the erased strokes AND the scribble
        //   undo #2 (older block)  → remove the scribble again (leaving just the erased strokes)
        // Pushed older-first because undo pops the most-recent block first.
        val scribbleBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }
        scribbleBox.inset(-strokeSize, -strokeSize)
        val scribbleStroke = Stroke(
            size = strokeSize,
            pen = pen,
            pageId = page.currentPageId,
            top = scribbleBox.top,
            bottom = scribbleBox.bottom,
            left = scribbleBox.left,
            right = scribbleBox.right,
            points = touchPoints,
            color = color,
            // Pressure is normalized to [0,1] at capture (TouchPoint.toStrokePoint).
            maxPressure = MAX_PRESSURE_NORMALIZED
        )
        history.addOperationsToHistory(listOf(Operation.DeleteStroke(listOf(scribbleStroke.id))))
        history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes + scribbleStroke)))
        // Return the erased region in SCREEN coordinates (mirrors handleErase). The caller
        // pushes this rect to the SurfaceView/EPD via commitErase, and the surface bitmap
        // (windowedBitmap) is in screen space — returning page coords here pushed the wrong
        // region whenever scrolled/zoomed. The caller unions this with the scribble track so
        // the firmware ink is cleared in the same post. See docs/onyx-sdk/onyx-scribble-to-erase.md.
        val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
        page.drawAreaScreenCoordinates(screenArea = effectedArea)
        return effectedArea
    }
    return null
}


// points is in page coordinates, returns effected area.
fun handleErase(
    page: PageView, history: History, points: List<SimplePointF>, eraser: Eraser
): Rect? {
    val paint = Paint().apply {
        this.strokeWidth = ERASER_SWATH_WIDTH
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND
        this.isAntiAlias = true
    }
    val path = pointsToPath(points)
    var outPath = Path()

    if (eraser == Eraser.SELECT) {
        path.close()
        outPath = path
    }


    if (eraser == Eraser.PEN || eraser == Eraser.MARKER) {
        paint.getFillPath(path, outPath)
    }

    val deletedStrokes = selectStrokesFromPath(erasableStrokes(page.strokes, eraser), outPath)

    val deletedStrokeIds = deletedStrokes.map { it.id }

    if (deletedStrokes.isEmpty()) return null
    page.removeStrokes(deletedStrokeIds)

    history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))

    val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
    page.drawAreaScreenCoordinates(screenArea = effectedArea)
    return effectedArea
}


// points is in page coordinates, returns effected area.
fun cleanAllStrokes(
    page: PageView, history: History
): Rect? {
    val deletedStrokes = page.strokes
    val deletedStrokeIds = deletedStrokes.map { it.id }
    if (deletedStrokes.isEmpty()) return null

    page.removeStrokes(deletedStrokeIds)
    history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))

    val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
    page.drawAreaScreenCoordinates(screenArea = effectedArea)
    return effectedArea
}