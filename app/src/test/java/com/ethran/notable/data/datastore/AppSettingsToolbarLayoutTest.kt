package com.ethran.notable.data.datastore

import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsToolbarLayoutTest {

    // Same configuration as KvProxy's Json instance.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `settings persisted before the field decode with a null layout and default pens`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"version":1}""")
        assertNull(decoded.toolbarLayout)
        assertEquals(ToolbarPen.DEFAULT_PENS, decoded.toolbarPens)
    }

    @Test
    fun `toolbarPens round-trip through AppSettings serialization`() {
        val settings = AppSettings(
            version = 1,
            toolbarPens = ToolbarPen.DEFAULT_PENS + ToolbarPen(
                id = "a1b2c3d4",
                pen = com.ethran.notable.editor.utils.Pen.FOUNTAIN,
                color = android.graphics.Color.MAGENTA,
                size = 7f,
            ),
        )
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), settings),
        )
        assertEquals(settings.toolbarPens, decoded.toolbarPens)
    }

    @Test
    fun `toolbarLayout round-trips through AppSettings serialization`() {
        val settings = AppSettings(
            version = 1,
            toolbarLayout = ToolbarLayout(
                scrollable = listOf("PEN:ball", "DIVIDER", "ERASER"),
                pinned = listOf("UNDO", "MENU"),
            ),
        )
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), settings),
        )
        assertEquals(settings.toolbarLayout, decoded.toolbarLayout)
    }

    @Test
    fun `page arrows are added to a saved layout once`() {
        val saved = json.decodeFromString(
            AppSettings.serializer(),
            """{"version":1,"toolbarLayout":{"scrollable":[],"pinned":["PAGE_NAV","MENU"]}}""",
        )
        val upgraded = saved.withPageArrowsAdded()
        assertEquals(listOf("PREV_PAGE", "PAGE_NAV", "NEXT_PAGE", "MENU"), upgraded.toolbarLayout?.pinned)
        assertEquals(true, upgraded.toolbarPageArrowsAdded)

        // Hidden again by the user afterwards: stays hidden.
        val hidden = upgraded.copy(toolbarLayout = ToolbarLayout(emptyList(), listOf("PAGE_NAV", "MENU")))
        assertEquals(hidden, hidden.withPageArrowsAdded())
    }

    @Test
    fun `front light is added to a saved layout once`() {
        val saved = json.decodeFromString(
            AppSettings.serializer(),
            """{"version":1,"toolbarLayout":{"scrollable":[],"pinned":["UNDO","MENU"]}}""",
        )
        val upgraded = saved.withPageArrowsAdded().withFrontLightAdded()
        assertEquals(
            listOf("PREV_PAGE", "NEXT_PAGE", "UNDO", "LIGHT", "MENU"),
            upgraded.toolbarLayout?.pinned,
        )
        assertEquals(true, upgraded.toolbarLightAdded)

        // Hidden again by the user afterwards: stays hidden.
        val hidden = upgraded.copy(toolbarLayout = ToolbarLayout(emptyList(), listOf("UNDO")))
        assertEquals(hidden, hidden.withFrontLightAdded())
    }

    @Test
    fun `default layout needs no upgrade, only the flag`() {
        val upgraded = AppSettings(version = 1).withPageArrowsAdded().withFrontLightAdded()
        assertNull(upgraded.toolbarLayout)
        assertEquals(true, upgraded.toolbarPageArrowsAdded)
        assertEquals(true, upgraded.toolbarLightAdded)
    }
}
