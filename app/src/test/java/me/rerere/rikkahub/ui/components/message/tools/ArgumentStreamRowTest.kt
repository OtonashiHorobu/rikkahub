package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.PartialString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生成中的一行参数摘要：只挑一个最有辨识度的字段，且必须容忍半截 JSON。
 */
class ArgumentStreamRowTest {

    private fun context(
        toolName: String,
        raw: String,
        partial: Map<String, PartialString> = me.rerere.rikkahub.utils.parsePartialJson(raw),
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = toolName,
            input = raw,
        ),
        arguments = JsonObject(emptyMap()),
        content = null,
        loading = true,
        rawInput = raw,
        partial = partial,
        inputComplete = false,
    )

    @Test
    fun `shell shows the command with a prompt prefix`() {
        val text = describeArguments(
            context("workspace_shell", """{"command":"ls -la /""")
        )
        assertEquals("$ ls -la /", text)
    }

    @Test
    fun `shell collapses embedded newlines into one line`() {
        val text = describeArguments(
            context("workspace_shell", """{"command":"cd a\nls"}""")
        )
        assertEquals("$ cd a ls", text)
    }

    @Test
    fun `file tools fall back from path to pattern`() {
        assertEquals(
            "/a/b.kt",
            describeArguments(context("workspace_read_file", """{"path":"/a/b.kt","offset":1""")),
        )
        assertEquals(
            "**/*.kt",
            describeArguments(context("workspace_grep", """{"pattern":"**/*.kt""")),
        )
    }

    @Test
    fun `apk tools show the apk path`() {
        assertEquals(
            "/sdcard/a.apk",
            describeArguments(context("workspace_install_apk", """{"path":"/sdcard/a.apk"}""")),
        )
    }

    @Test
    fun `unknown tools take the first short complete field`() {
        val text = describeArguments(
            context("some_tool", """{"mode":"fast","blob":"x"}""")
        )
        assertEquals("fast", text)
    }

    @Test
    fun `unknown tools skip long values and incomplete fields`() {
        val long = "y".repeat(200)
        val text = describeArguments(
            context("some_tool", """{"a":"$long","b":"tail""")
        )
        // a 太长被跳过，b 还没收全 → 退化成字符数
        assertEquals("""{"a":"$long","b":"tail""".length.let { "$it 字符" }, text)
    }

    @Test
    fun `empty partial with blank raw renders nothing`() {
        assertEquals("", describeArguments(context("workspace_shell", "")))
    }

    @Test
    fun `web tools show the query before the url`() {
        assertEquals(
            "kotlin compose",
            describeArguments(context("search_web", """{"query":"kotlin compose","url":"x"}""")),
        )
        assertEquals(
            "https://a.b",
            describeArguments(context("scrape_web", """{"url":"https://a.b"}""")),
        )
    }

    @Test
    fun `complete arguments still render the same single field`() {
        val text = describeArguments(
            context(
                "workspace_write_file",
                """{"path":"/tmp/a.txt","content":"hello"}""",
                partial = mapOf(
                    "path" to PartialString("/tmp/a.txt", true),
                    "content" to PartialString("hello", true),
                ),
            )
        )
        assertEquals("/tmp/a.txt", text)
    }
}
