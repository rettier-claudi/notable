package com.ethran.notable.editor.canvas

/*
 * Fork: when the firmware pen layer may be on. The layer draws ink itself and freezes the screen
 * until the app hands it back; the app only unfreezes while it thinks drawing is on
 * (CanvasRefreshManager.refreshUi). So a layer that is on while the editor's `isDrawing` is off
 * shows ink and nothing else — no lasso, no pasted selection, no finished stroke. Philipp hit
 * exactly that after cut → paste (2026-09-30): the pen drew, a lasso selected nothing, and the
 * pasted content could not be placed with the pen, because the pen never reached the selection.
 */

/**
 * Whether a request to turn drawing on may be granted. An open selection keeps the pen off: its
 * floating bitmap is placed by tapping outside it, and with the pen layer on that tap would go to
 * the firmware instead. A sent (locked) quick page never gets the pen.
 */
fun penMayDraw(requested: Boolean, pageLocked: Boolean, selectionOpen: Boolean): Boolean =
    requested && !pageLocked && !selectionOpen

/**
 * A stroke that began and ended while the editor had drawing off reached us through a pen layer
 * that should have been off. `drawingAtBegin` is null when the begin callback didn't arrive —
 * then we can't tell and treat the stroke as normal. A stroke during which drawing merely switched
 * off (e.g. the lasso that opens a selection) began with drawing on and is not stray.
 */
fun isStrayStroke(drawingAtBegin: Boolean?, drawingAtEnd: Boolean): Boolean =
    drawingAtBegin == false && !drawingAtEnd
