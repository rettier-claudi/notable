package com.ethran.notable.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailStalenessTest {

    @Test
    fun a_missing_thumbnail_is_stale() {
        assertTrue(isThumbnailStale(thumbModifiedMs = null, pageUpdatedAtMs = 1_000L))
    }

    @Test
    fun a_thumbnail_older_than_the_last_edit_is_stale() {
        // Written on the scratch page after the thumbnail was rendered.
        assertTrue(isThumbnailStale(thumbModifiedMs = 1_000L, pageUpdatedAtMs = 1_001L))
    }

    @Test
    fun a_thumbnail_rendered_after_the_last_edit_is_fresh() {
        assertFalse(isThumbnailStale(thumbModifiedMs = 5_000L, pageUpdatedAtMs = 4_000L))
    }

    @Test
    fun a_thumbnail_rendered_seconds_after_the_edit_is_fresh() {
        // Upstream counted this as stale for a minute and re-rendered it on every preview shown.
        assertFalse(isThumbnailStale(thumbModifiedMs = 4_500L, pageUpdatedAtMs = 4_000L))
    }

    @Test
    fun an_edit_in_the_same_millisecond_as_the_render_start_is_covered() {
        // The thumbnail is stamped with the render's start, and an edit's updatedAt is taken after
        // its stroke is written: a stamp equal to the render start means the stroke was already in
        // the DB when the render read it.
        assertFalse(isThumbnailStale(thumbModifiedMs = 4_000L, pageUpdatedAtMs = 4_000L))
    }
}
