package com.ethran.notable.data

import com.ethran.notable.ui.viewmodels.pageIndexAfter
import org.junit.Assert.assertEquals
import org.junit.Test

class PageIndexAfterTest {

    @Test
    fun `new page goes right after the open page`() {
        assertEquals(2, pageIndexAfter(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `after the last page means at the end`() {
        assertEquals(3, pageIndexAfter(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun `unknown open page appends`() {
        assertEquals(3, pageIndexAfter(listOf("a", "b", "c"), null))
        assertEquals(3, pageIndexAfter(listOf("a", "b", "c"), "gone"))
    }
}
