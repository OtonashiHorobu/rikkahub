package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class StripLineNumberPrefixesTest {
    @Test
    fun `numbered lines lose their prefix`() {
        assertEquals(
            "fun main() {\n    println(1)\n}",
            stripLineNumberPrefixes("1: fun main() {\n2:     println(1)\n3: }"),
        )
    }

    @Test
    fun `plain text is untouched`() {
        val text = "note: this is fine\nanother: line"
        assertEquals(text, stripLineNumberPrefixes(text))
    }

    @Test
    fun `mixed indented numbered lines still strip`() {
        assertEquals(
            "a\nb",
            stripLineNumberPrefixes("  12: a\n  13: b"),
        )
    }

    @Test
    fun `empty text stays empty`() {
        assertEquals("", stripLineNumberPrefixes(""))
    }
}
