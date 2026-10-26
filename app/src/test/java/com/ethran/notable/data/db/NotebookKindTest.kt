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

    // --- Tickets row (claudi.37): the bridge's YouTrack pages, "kind": "tickets" ---

    private fun titled(id: String, title: String, kind: String? = "tickets", pages: List<String> = listOf("p-$id")) =
        Notebook(id = id, title = title, pageIds = pages, kind = kind)

    @Test
    fun isTickets_only_for_kind_tickets() {
        assertTrue(book("a", kind = "tickets").isTickets)
        assertTrue(book("b", kind = " Tickets ").isTickets)
        assertFalse(book("c", kind = null).isTickets)
        assertFalse(book("d", kind = "scratch").isTickets)
        assertFalse(book("e", kind = "ticket").isTickets)
        assertFalse(book("f", kind = "tickets").isScratch)
    }

    @Test
    fun ticket_split_orders_the_three_pages_as_in_the_spec_whatever_their_age() {
        // Reprints give a page a new id and a new createdAt: the row must not follow either.
        val keikoba = titled("k-new", "⚔️ 稽古場")
        val banzuke = titled("b", "🥇 番付")
        val tenshukaku = titled("t", "🏯 天守閣")
        val plain = book("n1")
        val scratch = book("s1", kind = "scratch")

        val (row, rest) = splitTicketNotebooks(listOf(keikoba, plain, banzuke, scratch, tenshukaku))

        assertEquals(listOf(banzuke, tenshukaku, keikoba), row)
        assertEquals(listOf(plain, scratch), rest)
        // the scratch split then sees only the rest
        assertEquals(listOf(scratch), splitScratchNotebooks(rest).first)
    }

    @Test
    fun ticket_split_keeps_an_empty_ticket_notebook_in_the_grid() {
        val empty = titled("e", "🥇 番付", pages = emptyList())
        val (row, rest) = splitTicketNotebooks(listOf(empty))
        assertTrue(row.isEmpty())
        assertEquals(listOf(empty), rest)
    }

    @Test
    fun ticket_split_of_ordinary_folder_changes_nothing() {
        val books = listOf(book("a"), book("b", kind = "scratch"))
        val (row, rest) = splitTicketNotebooks(books)
        assertTrue(row.isEmpty())
        assertEquals(books, rest)
    }
}
