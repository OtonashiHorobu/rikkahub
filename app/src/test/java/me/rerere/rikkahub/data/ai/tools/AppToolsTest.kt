package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.AgentPermissionLevel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * F2 只读层的边界：能列出配置，但**绝不吐密钥**；`app_open` 只认白名单目标。
 */
class AppToolsTest {
    private val model = Model(modelId = "deepseek-chat", displayName = "DeepSeek Chat")
    private val provider = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "DeepSeek",
        apiKey = "sk-this-must-never-leak",
        baseUrl = "https://api.deepseek.com/v1",
        customHeaders = listOf(CustomHeader("Authorization", "Bearer also-secret")),
        models = listOf(model),
    )

    private val assistant = Assistant(
        name = "Agent",
        chatModelId = model.id,
        permissionLevel = AgentPermissionLevel.WORKSPACE_WRITE,
    )

    private fun tools(
        settings: Settings? = null,
        assistant: Assistant = this.assistant,
    ): List<Tool> = createAppReadOnlyTools(
        settings = settings ?: Settings(providers = listOf(provider), assistants = listOf(assistant)),
        assistant = assistant,
        conversationId = Uuid.random(),
        workspace = { emptyList() },
        skills = { listOf(SkillMetadata("demo", "demo skill", skillDir = File("."))) },
        appEventBus = AppEventBus(),
    )

    private fun Tool.textOf(vararg args: Pair<String, String>): String = runBlocking {
        val input = JsonObject(args.toMap().mapValues { (_, value) ->
            kotlinx.serialization.json.JsonPrimitive(value)
        })
        execute(input).filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    }

    @Test
    fun `read only tools cover the planned names`() {
        val names = tools().map { it.name }.toSet()
        assertEquals(
            setOf(
                "app_state",
                "app_list_assistants",
                "app_list_providers",
                "app_list_models",
                "app_list_workspaces",
                "app_list_skills",
                "app_open",
            ),
            names,
        )
        // 全部是只读档，最低权限等级也能用
        names.forEach { name ->
            assertEquals(AgentPermissionLevel.READ_ONLY, AgentToolLevels.requiredLevel(name))
        }
    }

    @Test
    fun `provider summary never contains credentials`() {
        val json = JsonInstant.encodeToString(providerSummary(provider, includeModels = true))
        assertFalse(json.contains("sk-this-must-never-leak"))
        assertFalse(json.contains("also-secret"))
        assertTrue(json.contains("DeepSeek"))
        assertTrue(json.contains("deepseek-chat"))
    }

    @Test
    fun `app state reports the current setup without secrets`() {
        val text = tools().first { it.name == "app_state" }.textOf()
        assertFalse(text.contains("sk-this-must-never-leak"))
        val json = JsonInstant.parseToJsonElement(text).jsonObject
        assertEquals("Agent", json["assistant"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("workspace_write", json["assistant"]!!.jsonObject["permission_level"]!!.jsonPrimitive.content)
        assertEquals("DeepSeek Chat", json["model"]!!.jsonObject["display_name"]!!.jsonPrimitive.content)
        assertEquals("1", json["environment"]!!.jsonObject["assistants"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant list filters by query`() {
        val other = Assistant(name = "Translator")
        val settings = Settings(assistants = listOf(assistant, other), providers = listOf(provider))
        val tool = tools(settings).first { it.name == "app_list_assistants" }

        val all = JsonInstant.parseToJsonElement(tool.textOf()).toString()
        assertTrue(all.contains("Agent"))
        assertTrue(all.contains("Translator"))

        val filtered = JsonInstant.parseToJsonElement(tool.textOf("query" to "trans")).toString()
        assertFalse(filtered.contains("\"Agent\""))
        assertTrue(filtered.contains("Translator"))
    }

    @Test
    fun `app open only accepts whitelisted targets`() {
        assertEquals(Screen.Workspaces, appOpenTargetScreen("workspaces", ""))
        assertEquals(Screen.WorkspaceDetail("ws-1"), appOpenTargetScreen("workspace", "ws-1"))
        assertEquals(Screen.AssistantDetail("a-1"), appOpenTargetScreen("assistant", "a-1"))
        assertNull(appOpenTargetScreen("workspace", ""))
        assertNull(appOpenTargetScreen("rm -rf", ""))
    }

    @Test
    fun `app open reports unknown targets instead of navigating`() {
        val text = tools().first { it.name == "app_open" }
            .textOf("target" to "assistant", "id" to "")
        assertTrue(text.contains("Unknown or incomplete target"))
    }
}
