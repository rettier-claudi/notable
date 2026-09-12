package com.ethran.notable.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a folder's notebooks are split between the *Scratch notes* row and the *Notebooks* grid. */
class NotebookKindTest {

    private fun book(id: String, kind: String? = null, pages: List<String> = listOf("p-$id")) =
        Notebook(id = id, title = id, pageIds = pages, kind = kind)

    @Test
    fun isScratch_only_for_kind_scratch() {
        assertTrue(book("a", kind = "scratch").isScratch)
        assertTrue(book("b", kind = " Scratch ").isScratch)
        assertFalse(book("c", kind = null).isScratch)
        assertFalse(book("d", kind = "").isScratch)
        assertFalse(book("e", kind = "journal").isScratch)
        assertFalse(book("f", kind = "scratchpad").isScratch)
    }

    @Test
    fun split_puts_scratch_kind_in_the_row_and_everything_else_in_the_grid() {
        val scratch1 = book("s1", kind = "scratch")
        val plain = book("n1")
        val unknown = book("u1", kind = "journal")
        val scratch2 = book("s2", kind = "scratch")

        val (row, grid) = splitScratchNotebooks(listOf(scratch1, plain, unknown, scratch2))

        assertEquals(listOf(scratch1, scratch2), row)
        assertEquals(listOf(plain, unknown), grid)
    }

    @Test
    fun split_keeps_a_scratch_kind_notebook_without_pages_in_the_grid() {
        // Nothing to draw as a tile; the grid's empty-notebook warning takes care of it.
        val empty = book("s0", kind = "scratch", pages = emptyList())
        val (row, grid) = splitScratchNotebooks(listOf(empty))
        assertTrue(row.isEmpty())
        assertEquals(listOf(empty), grid)
    }

    @Test
    fun split_of_ordinary_folder_is_all_grid() {
        val books = listOf(book("a"), book("b"))
        val (row, grid) = splitScratchNotebooks(books)
        assertTrue(row.isEmpty())
        assertEquals(books, grid)
    }
}
