package com.ethran.notable.editor.ui

import com.ethran.notable.editor.ToolbarUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Really send?" question (claudi.17) blocks drawing while it is up, like the other modals --
 * on e-ink the pen draws through a Compose dialog unless the raw input surface is told to stop.
 */
class SendConfirmStateTest {

    @Test
    fun `drawing is allowed while nothing is asked`() {
        assertTrue(ToolbarUiState().isDrawingAllowed)
    }

    @Test
    fun `the send question blocks drawing`() {
        assertFalse(ToolbarUiState(isSendConfirmOpen = true).isDrawingAllowed)
    }
}
