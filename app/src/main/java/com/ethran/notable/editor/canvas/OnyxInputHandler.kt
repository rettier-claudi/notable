package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.LiveScribbleCheck
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS
import com.ethran.notable.editor.utils.toStrokePoint
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.cancelPendingScreenFreezeReset
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.configureCalligraphyLiveAngle
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.ERASER_INDICATOR_COLOR
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.ui.convertDpToPixel
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Fork: pen colour while a stroke counts as a scribble that will erase. */
private const val LIVE_SCRIBBLE_COLOR = Color.RED

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
    private val strokeHistoryBatch: MutableList<String>,
) {
    var isErasing: Boolean = false
    var lastStrokeEndTime: Long = 0

    // Fork: what the firmware pen layer was last set to by updateIsDrawing/updateActiveSurface
    // (null: not known yet). updateIsDrawing compares against this instead of trusting that every
    // isDrawing change reached it — see PenLayerRules.kt.
    private var appliedIsDrawing: Boolean? = null
    private val penLayerLock = Mutex()

    // Fork: toolbarState.isDrawing when the current pen stroke began (null: no begin seen).
    @Volatile
    private var drawingAtStrokeBegin: Boolean? = null

    // Fork: live scribble feedback — the pen turns red as soon as lifting it would erase.
    private val liveScribble = LiveScribbleCheck()
    private var liveScribbleActive = false
    private var liveScribbleRed = false
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    // TODO: As OnyxInput is not done by lazy, which forces evaluation of the touchHelper
    //       lazy during DrawCanvas construction.
    val touchHelper by lazy {
        val helper = if (DeviceCompat.isOnyxDevice) {
            try {
                // Fork: the DrawCanvas hash, which surfaceDestroyed compares against. Upstream stored
                // this handler's hash, so closeRawDrawing() there never ran.
                referencedSurfaceView = drawCanvas.hashCode().toString()
                TouchHelper.create(drawCanvas, inputCallback)
            } catch (t: Throwable) {
                Log.w("OnyxInputHandler", "TouchHelper.create failed: ${t.message}")
                null
            }
        } else null
        helper
    }

    @Suppress("RedundantOverride")
    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
            drawingAtStrokeBegin = toolbarState.isDrawing
            startLiveScribble(p1)
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
            endLiveScribble()
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
            if (p0 != null) feedLiveScribble(p0)
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) =
            onRawDrawingList(plist)


        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (touchHelper == null) return
            // Re-assert the native eraser indicator because setRawDrawingEnabled(true) (called
            // on every resume) resets it to disabled internally. The track style follows the active
            // eraser type: the wide marker (style 8) for the pen/drag eraser, a dotted outline
            // (DASH style 5) for the lasso/select eraser. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
            enableNativeEraser(touchHelper, toolbarState.eraser)
            // The eraser channel carries no colour of its own — the firmware paints the track with
            // the global setStrokeColor. Set it here (width comes from the style's params, so this
            // touches colour only, not thickness). This is the one thing we still set on begin.
            touchHelper?.setStrokeColor(ERASER_INDICATOR_COLOR)
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            updatePenAndStroke()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist)

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
        }

        override fun onPenActive(point: TouchPoint?) {
            super.onPenActive(point)
        }
    }

    /**
     * Fork: the same conditions [handleScribbleToErase] checks at pen-up, decided once per stroke.
     * Only these strokes are watched, so ordinary writing with scribble-to-erase off costs nothing.
     */
    private fun startLiveScribble(first: TouchPoint?) {
        liveScribble.reset()
        resetLiveScribbleColor()
        val startedAt = first?.timestamp ?: System.currentTimeMillis()
        liveScribbleActive = touchHelper != null &&
                toolbarState.isDrawing &&
                toolbarState.mode == Mode.Draw &&
                toolbarState.pen != Pen.MARKER &&
                GlobalAppSettings.current.scribbleToEraseEnabled &&
                !page.isReadOnly &&
                startedAt >= lastStrokeEndTime + SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS
        if (liveScribbleActive && first != null) feedLiveScribble(first)
    }

    private fun feedLiveScribble(point: TouchPoint) {
        if (!liveScribbleActive) return
        val pagePoint = point.toStrokePoint(page.scroll, page.zoomLevel.value)
        if (liveScribble.add(pagePoint, System.currentTimeMillis()) { page.strokes }) {
            log.d("Live scribble: would erase, pen turns red")
            // Whether the firmware recolours a stroke already in progress is device-dependent; on
            // a device that latches the colour at pen-down this shows nothing and does no harm.
            touchHelper?.setStrokeColor(LIVE_SCRIBBLE_COLOR)
            liveScribbleRed = true
        }
    }

    private fun endLiveScribble() {
        liveScribbleActive = false
        liveScribble.reset()
        resetLiveScribbleColor()
    }

    private fun resetLiveScribbleColor() {
        if (!liveScribbleRed) return
        liveScribbleRed = false
        // Back to the tool's own colour before the next stroke starts.
        updatePenAndStroke()
    }

    fun updatePenAndStroke() {
        if(touchHelper == null) return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> {
                val scaledWidth = toolbarState.activePenSetting.strokeSize * page.zoomLevel.value
                touchHelper!!.setStrokeStyle(penToStroke(toolbarState.pen))
                    ?.setStrokeWidth(scaledWidth)
                    ?.setStrokeColor(toolbarState.activePenSetting.color)
                // Match the live square-pen nib angle to the dry render (+45°) so the calligraphy
                // stroke doesn't rotate on pen-up. See docs/onyx-sdk/onyx-pen-styles-catalog.md.
                if (toolbarState.pen == Pen.CALLIGRAPHY) {
                    configureCalligraphyLiveAngle(angleDegrees = 45f, strokeWidth = scaledWidth)
                }
            }

            Mode.Erase -> applyEraserIndicatorStyle(penEraserColor = Color.GRAY)

            Mode.Select -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    /**
     * Configures the helper's stroke so the eraser feedback matches the active eraser type:
     * a marker for the pen eraser, and a dashed line for the lasso / select eraser. Shared
     * by the hand eraser (Mode.Erase in [updatePenAndStroke]) and the pen side-button
     * eraser ([onBeginRawErasing], native indicator).
     *
     * @param penEraserColor colour for the [Eraser.PEN] marker. Hand-erase uses grey; the
     * native button-erase indicator uses black (matches the user's preference and is more
     * visible against ink).
     */
    private fun applyEraserIndicatorStyle(penEraserColor: Int = Color.BLACK) {
        if (touchHelper == null) return
        when (toolbarState.eraser) {
            Eraser.PEN, Eraser.MARKER -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                ?.setStrokeWidth(30f)
                ?.setStrokeColor(penEraserColor)

            Eraser.SELECT -> {
                val dashStyleID = penToStroke(Pen.DASHED)
                touchHelper!!.setStrokeStyle(dashStyleID)
                    ?.setStrokeWidth(3f)
                    ?.setStrokeColor(Color.BLACK)
                val params = FloatArray(4)
                params[0] = 5f // thickness
                params[1] = 9f // no idea
                params[2] = 9f // no idea
                params[3] = 0f // no idea
                Device.currentDevice().setStrokeParameters(dashStyleID, params)
            }
        }
    }

    /**
     * Brings the firmware pen layer in line with toolbarState.isDrawing. Fork: reads the state
     * again after every suspension and compares against what was actually applied, so a change that
     * arrives mid-way (or one the distinct-until-changed observer never delivered) can't leave the
     * layer on while the editor has drawing off. [force] re-applies even if nothing seems to differ.
     */
    suspend fun updateIsDrawing(force: Boolean = false) {
        if (touchHelper == null) return
        penLayerLock.withLock {
            var forceNext = force
            // A few rounds at most: each one re-checks the state that may have flipped meanwhile.
            repeat(3) {
                val wanted = toolbarState.isDrawing
                if (!forceNext && wanted == appliedIsDrawing) return
                forceNext = false
                if (!applyIsDrawing(wanted)) return
            }
        }
    }

    /** Returns false if there is no live surface to apply it to. */
    private suspend fun applyIsDrawing(wanted: Boolean): Boolean {
        log.i("Update is drawing: $wanted")
        if (wanted) {
            // Fork: a late "drawing on" (after a gesture or focus change) can arrive once the editor
            // is already gone, e.g. Send → home screen. Enabling raw drawing then leaves the firmware
            // pen layer over the home screen: pen strokes draw instead of navigating.
            if (!isSurfaceLive()) {
                log.i("Not enabling raw drawing: canvas detached")
                return false
            }
            enablePenLayer()
        } else {
            // Before the first surface setup there is no pen layer to switch off.
            if (appliedIsDrawing == null && !isSurfaceLive()) return false
            // A pending resetScreenFreeze resume would re-freeze the screen after we disable
            // raw drawing (e.g. lasso select: the select-stroke refreshUi armed it) — kill it.
            cancelPendingScreenFreezeReset()
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.refreshManager.drawCanvasToView(null)
            touchHelper!!.setRawDrawingEnabled(false)
        }
        appliedIsDrawing = wanted
        return true
    }

    private fun enablePenLayer() {
        touchHelper!!.setRawDrawingEnabled(true)
        // setRawDrawingEnabled(true) resets the framework stroke config to firmware defaults
        // (brush channel on, eraser channel off). Re-assert the eraser channel (styled for the
        // active eraser type) and re-send the active pen style so the next stroke uses the tool.
        enableNativeEraser(touchHelper, toolbarState.eraser)
        updatePenAndStroke()
        // Fork: commitErase switches the input reader off for its settle. If drawing was off when
        // the settle ended, it left the reader off, and the next "drawing on" brought back the
        // firmware ink without any input reaching us — the pen drew, nothing happened, and no
        // stroke arrived that could have healed it. Whoever turns the layer on turns the reader on.
        touchHelper!!.setRawInputReaderEnable(true)
    }

    /**
     * Fork: end of [CanvasRefreshManager.commitErase]'s settle. Pen layer and input reader come back
     * per the current state, and appliedIsDrawing says what the layer really is, so the next change
     * is applied instead of being taken for "already so".
     */
    suspend fun resumeAfterErase() {
        if (touchHelper == null) return
        penLayerLock.withLock {
            if (toolbarState.isDrawing && isSurfaceLive()) {
                enablePenLayer()
                appliedIsDrawing = true
            } else {
                log.w("commitErase: not in drawing mode, leaving raw drawing disabled")
                appliedIsDrawing = false
            }
        }
        // drawing may have switched while we held the lock
        updateIsDrawing()
    }

    private fun isSurfaceLive(): Boolean =
        drawCanvas.isAttachedToWindow && drawCanvas.holder.surface.isValid

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        coroutineScope.launch {
            if (!isSurfaceLive()) return@launch
            onSurfaceInit(drawCanvas)
            val toolbarHeight =
                if (toolbarState.isToolbarOpen) convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
            penLayerLock.withLock {
                setupSurface(
                    drawCanvas,
                    touchHelper,
                    toolbarHeight
                )
                // setupSurface resets the framework stroke style to firmware defaults. Re-send the
                // pen style here, inside the same coroutine and after the surface is armed: a caller
                // that invokes updatePenAndStroke() right after updateActiveSurface() would otherwise
                // race this launch and have its style overwritten.
                updatePenAndStroke()
                // Fork: setupSurface always leaves raw drawing on. With drawing off (open selection,
                // menu, locked page) that is the stuck pen layer from PenLayerRules.kt — undo it.
                if (!toolbarState.isDrawing) touchHelper?.setRawDrawingEnabled(false)
                appliedIsDrawing = toolbarState.isDrawing
            }
        }
    }
    private fun onRawDrawingList(plist: TouchPointList) {
        if (touchHelper == null) return
        // Raw drawing is off on a locked page; this only catches input that raced the switch.
        if (page.isReadOnly) return
        // Fork: self-heal. Drawing was off for the whole stroke, so the pen layer should have been
        // off: drop the stroke (it would land under an open selection, or select behind it) and
        // switch the layer off, which also repaints away the firmware ink.
        val drawingAtBegin = drawingAtStrokeBegin
        drawingAtStrokeBegin = null
        if (isStrayStroke(drawingAtBegin, toolbarState.isDrawing)) {
            log.w("Pen stroke while drawing is off: pen layer was left on, switching it off")
            coroutineScope.launch {
                updateIsDrawing(force = true)
                drawCanvas.refreshManager.drawCanvasToView(null)
            }
            return
        }
        val currentLastStrokeEndTime = lastStrokeEndTime
        lastStrokeEndTime = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()

        when (toolbarState.mode) {
            Mode.Erase -> onRawErasingList(plist)
            Mode.Select -> {
                thread {
                    val points =
                        copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                    handleSelect(
                        scope = coroutineScope,
                        page = drawCanvas.page,
                        viewModel = viewModel,
                        points = points
                    )
                    val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                    val padding = 10
                    val dirtyRect = Rect(
                        boundingBox.left - padding,
                        boundingBox.top - padding,
                        boundingBox.right + padding,
                        boundingBox.bottom + padding
                    )
                    drawCanvas.refreshManager.refreshUi(dirtyRect)
                }
            }

            Mode.Line -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")


                        val (startPoint, endPoint) = getModifiedStrokeEndpoints(
                            plist.points,
                            page.scroll,
                            page.zoomLevel.value
                        )
                        val linePoints = transformToLine(startPoint, endPoint)

                        handleDraw(
                            drawCanvas.page,
                            strokeHistoryBatch,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            toolbarState.pen,
                            linePoints
                        )

                        coroutineScope.launch(Dispatchers.Default) {
                            val dirtyRect = Rect(
                                min(startPoint.x, endPoint.x).toInt(),
                                min(startPoint.y, endPoint.y).toInt(),
                                max(startPoint.x, endPoint.x).toInt(),
                                max(startPoint.y, endPoint.y).toInt()
                            )
                            drawCanvas.refreshManager.refreshUi(dirtyRect)
                            CanvasEventBus.commitHistorySignal.emit(Unit)
                        }
                    }

                }
            }

            Mode.Draw -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")

                        val scaledPoints =
                            copyInput(plist.points, page.scroll, page.zoomLevel.value)
                        val firstPointTime = plist.points.first().timestamp
                        val erasedByScribbleDirtyRect = handleScribbleToErase(
                            page,
                            scaledPoints,
                            history,
                            toolbarState.pen,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            currentLastStrokeEndTime,
                            firstPointTime
                        )
                        if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                            log.d("Drawing...")
                            // draw the stroke
                            handleDraw(
                                drawCanvas.page,
                                strokeHistoryBatch,
                                toolbarState.activePenSetting.strokeSize,
                                toolbarState.activePenSetting.color,
                                toolbarState.pen,
                                scaledPoints
                            )
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble track (firmware screen coords) with the erased
                            // strokes' bounds so commitErase overwrites both in one pass while
                            // still frozen. Scribble is not drawn into the page bitmap — we only
                            // need the region to cover the firmware's live track.
                            // See docs/onyx-sdk/onyx-scribble-to-erase.md.
                            val padding = 10
                            val trackBox =
                                calculateBoundingBox(plist.points) { Pair(it.x, it.y) }.toRect()
                            val dirty = Rect(
                                trackBox.left - padding,
                                trackBox.top - padding,
                                trackBox.right + padding,
                                trackBox.bottom + padding
                            )
                            erasedByScribbleDirtyRect.let { dirty.union(it) }
                            // Use areaErase=true for the longer 500ms settle (scribble is a large gesture).
                            drawCanvas.refreshManager.commitErase(dirty, areaErase = true)
                        }

                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        CanvasEventBus.commitHistorySignal.emit(Unit)
                    }
                }
            }
        }
    }

    private fun onRawErasingList(plist: TouchPointList?) {
        isErasing = false
        if (page.isReadOnly) return

        if (plist == null) return
        val points = copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)

        val padding = 10
        val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
        val strokeArea = Rect(
            boundingBox.left - padding,
            boundingBox.top - padding,
            boundingBox.right + padding,
            boundingBox.bottom + padding
        )
        val zoneEffected = handleErase(
            drawCanvas.page,
            history,
            points,
            eraser = toolbarState.eraser
        )

        // Single atomic commit of the whole touched region: the native eraser indicator
        // track spans strokeArea, the erased strokes' bounds are zoneEffected, so repainting
        // their union both wipes the indicator and shows the erased result in one pass.
        // commitErase blocks input, draws synchronously, then drops the firmware overlay so
        // indicator + strokes disappear together (no double refresh, no gap to draw into).
        // See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle the official app uses; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

}