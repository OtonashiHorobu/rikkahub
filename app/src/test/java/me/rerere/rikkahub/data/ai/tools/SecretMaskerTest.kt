package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretMaskerTest {
    @Test
    fun `known secrets are replaced by their masked form`() {
        val masker = SecretMasker { listOf("sk-abcdefghijklmnopqrstuvwxyz") }
        val text = masker.mask("""{"apiKey":"sk-abcdefghijklmnopqrstuvwxyz"}""")
        assertFalse(text.contains("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("""{"apiKey":"sk-a••••wxyz"}""", text)
    }

    @Test
    fun `unknown token shapes are masked by pattern`() {
        val masker = SecretMasker()
        val text = masker.mask(
            "token sk-1234567890abcdefghij and Bearer abcdefghijklmnop.qrstu and AKIAABCDEFGHIJKLMNOP"
        )
        assertFalse(text.contains("sk-1234567890abcdefghij"))
        assertFalse(text.contains("abcdefghijklmnop.qrstu"))
        assertFalse(text.contains("AKIAABCDEFGHIJKLMNOP"))
        assertTrue(text.contains("sk-1234••••ghij"))
        assertTrue(text.contains("Bearer abcd••••rstu"))
        assertTrue(text.contains("AKIAABCD••••MNOP"))
    }

    @Test
    fun `key value pairs keep the key name and hide the value`() {
        val masker = SecretMasker()
        val text = masker.mask("""config: api_key=super-secret-value-1234, retries=3""")
        assertFalse(text.contains("super-secret-value-1234"))
        assertTrue(text.contains("api_key=supe••••1234"))
        // 普通参数不受影响
        assertTrue(text.contains("retries=3"))
    }

    @Test
    fun `short secrets are masked completely`() {
        val masker = SecretMasker()
        assertEquals("••••", masker.masked("abc"))
        assertEquals("••••", masker.masked("0123456789ab".take(11)))
        assertEquals("abcd••••wxyz", masker.masked("abcdefghiwxyz"))
    }

    @Test
    fun `plain text is left alone`() {
        val masker = SecretMasker { listOf("sk-real-key-value-9999") }
        val text = "workspace files: notes.md, archive/, bin/"
        assertEquals(text, masker.mask(text))
    }
}
