package com.ethran.notable.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UnwrittenPagesTest {

    @Test
    fun `unwritten pages are held`() {
        assertEquals(setOf("c"), unwrittenPagesToHold(listOf("a", "b", "c"), setOf("c")))
    }

    @Test
    fun `nothing unwritten holds nothing`() {
        assertEquals(emptySet<String>(), unwrittenPagesToHold(listOf("a", "b"), emptySet()))
    }

    @Test
    fun `a notebook of only unwritten pages keeps its first`() {
        assertEquals(setOf("b", "c"), unwrittenPagesToHold(listOf("a", "b", "c"), setOf("a", "b", "c")))
        assertEquals(emptySet<String>(), unwrittenPagesToHold(listOf("a"), setOf("a")))
    }

    @Test
    fun `ids outside the notebook are ignored`() {
        assertEquals(emptySet<String>(), unwrittenPagesToHold(listOf("a"), setOf("x")))
    }

    @Test
    fun `download keeps a held page after its local predecessor`() {
        // Local a b [n] c, server added r after c: n stays after b.
        assertEquals(
            listOf("a", "b", "n", "c", "r"),
            keepHeldPages(listOf("a", "b", "c", "r"), listOf("a", "b", "n", "c"), setOf("n")),
        )
    }

    @Test
    fun `download keeps a held page at the end`() {
        assertEquals(
            listOf("a", "r", "n"),
            keepHeldPages(listOf("a", "r"), listOf("a", "n"), setOf("n")),
        )
    }

    @Test
    fun `held page whose predecessor the server removed moves to the nearest survivor`() {
        assertEquals(
            listOf("a", "n", "c"),
            keepHeldPages(listOf("a", "c"), listOf("a", "b", "n", "c"), setOf("n")),
        )
    }

    @Test
    fun `held page first locally stays first`() {
        assertEquals(
            listOf("n", "a"),
            keepHeldPages(listOf("a"), listOf("n", "a"), setOf("n")),
        )
    }

    @Test
    fun `consecutive held pages keep their order`() {
        assertEquals(
            listOf("a", "n1", "n2", "b"),
            keepHeldPages(listOf("a", "b"), listOf("a", "n1", "n2", "b"), setOf("n1", "n2")),
        )
    }

    @Test
    fun `nothing held is the server order`() {
        assertEquals(listOf("b", "a"), keepHeldPages(listOf("b", "a"), listOf("a", "b"), emptySet()))
    }

    @Test
    fun `open page falls back to the page before it`() {
        assertEquals("b", openPageAfterRemoval(listOf("a", "b", "n"), "n", setOf("n")))
        assertEquals("a", openPageAfterRemoval(listOf("a", "n1", "n2"), "n2", setOf("n1", "n2")))
    }

    @Test
    fun `open page falls forward when nothing precedes it`() {
        assertEquals("a", openPageAfterRemoval(listOf("n", "a"), "n", setOf("n")))
    }

    @Test
    fun `open page that stays is kept`() {
        assertEquals("a", openPageAfterRemoval(listOf("a", "n"), "a", setOf("n")))
        assertEquals(null, openPageAfterRemoval(listOf("a", "n"), null, setOf("n")))
    }
}
