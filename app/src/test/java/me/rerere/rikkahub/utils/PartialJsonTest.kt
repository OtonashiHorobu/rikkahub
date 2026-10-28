package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialJsonTest {
    @Test
    fun `complete object is parsed fully`() {
        val parsed = parsePartialJson("""{"path":"/a/b","text":"hi","overwrite":true}""")
        assertEquals("/a/b", parsed["path"]?.value)
        assertTrue(parsed["path"]!!.complete)
        assertEquals("hi", parsed["text"]?.value)
        assertEquals("true", parsed["overwrite"]?.value)
    }

    @Test
    fun `unterminated string returns the prefix`() {
        val parsed = parsePartialJson("""{"path":"/home/mesu/AG""")
        assertEquals("/home/mesu/AG", parsed["path"]?.value)
        assertFalse(parsed["path"]!!.complete)
    }

    @Test
    fun `half escaped quote does not leak`() {
        val parsed = parsePartialJson("""{"path":"a\"b""")
        assertEquals("a\"b", parsed["path"]?.value)
        assertFalse(parsed["path"]!!.complete)
    }

    @Test
    fun `trailing half escape is dropped`() {
        val parsed = parsePartialJson("""{"path":"abc\""")
        assertEquals("abc", parsed["path"]?.value)
        assertFalse(parsed["path"]!!.complete)
    }

    @Test
    fun `unicode escape is decoded`() {
        val parsed = parsePartialJson("""{"text":"\u4e2d\u6587"}""")
        assertEquals("中文", parsed["text"]?.value)
        assertTrue(parsed["text"]!!.complete)
    }

    @Test
    fun `earlier fields survive a truncated later field`() {
        val parsed = parsePartialJson("""{"command":"ls -la","cwd":"/workspace/su""")
        assertEquals("ls -la", parsed["command"]?.value)
        assertTrue(parsed["command"]!!.complete)
        assertEquals("/workspace/su", parsed["cwd"]?.value)
        assertFalse(parsed["cwd"]!!.complete)
    }

    @Test
    fun `newline escapes are decoded`() {
        val parsed = parsePartialJson("""{"old_text":"a\nb"}""")
        assertEquals("a\nb", parsed["old_text"]?.value)
    }

    @Test
    fun `nested object is kept as raw text`() {
        val parsed = parsePartialJson("""{"a":{"b":1},"c":"x"}""")
        assertEquals("""{"b":1}""", parsed["a"]?.value)
        assertEquals("x", parsed["c"]?.value)
    }

    @Test
    fun `empty or garbage input yields nothing`() {
        assertTrue(parsePartialJson("").isEmpty())
        assertTrue(parsePartialJson("{").isEmpty())
        assertTrue(parsePartialJson("not json").isEmpty())
    }

    @Test
    fun `keys without value are marked incomplete`() {
        val parsed = parsePartialJson("""{"path":""")
        assertEquals("", parsed["path"]?.value)
        assertFalse(parsed["path"]!!.complete)
        assertNull(parsed["missing"])
    }
}
