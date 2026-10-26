package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * 25.10.2026: the tablet bridge replaced a ticket page (tombstone 10:10:40) while Philipp was still
 * drawing on it; his strokes from 09:52 on had never been uploaded (the notebook was still open).
 * The next sync deleted the notebook, and the strokes with it, because the last local edit (10:09)
 * was older than the tombstone. Unsynced edits now keep the notebook.
 */
class TombstoneVerdictTest {
    private fun t(hhmmss: String) = Date(Date.UTC(126, 9, 25, hhmmss.take(2).toInt(), hhmmss.substring(3, 5).toInt(), hhmmss.takeLast(2).toInt()))

    @Test
    fun `the case of 25 October - unsynced strokes before the tombstone keep the notebook`() {
        assertEquals(TombstoneVerdict.KEEP_UNSYNCED, tombstoneVerdict(t("09:09:37"), t("09:10:40"), t("08:51:58")))
    }

    @Test
    fun `a clean copy goes`() {
        assertEquals(TombstoneVerdict.DELETE, tombstoneVerdict(t("08:51:58"), t("09:10:40"), t("08:51:58")))
    }

    @Test
    fun `edited after the deletion - resurrection as before`() {
        assertEquals(TombstoneVerdict.KEEP_EDITED_AFTER, tombstoneVerdict(t("09:12:00"), t("09:10:40"), t("08:51:58")))
    }

    @Test
    fun `undated tombstone - unsynced keeps, clean goes, never synced keeps`() {
        assertEquals(TombstoneVerdict.KEEP_UNSYNCED, tombstoneVerdict(t("09:09:37"), null, t("08:51:58")))
        assertEquals(TombstoneVerdict.DELETE, tombstoneVerdict(t("08:51:58"), null, t("08:51:58")))
        assertEquals(TombstoneVerdict.KEEP_UNSYNCED, tombstoneVerdict(t("08:51:58"), null, null))
    }
}
