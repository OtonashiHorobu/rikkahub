package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.AgentPermissionLevel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * F2 CONFIG 档：写操作要落到 settings 上，红线要拒绝，白名单外的设置不能写。
 */
class AppConfigToolsTest {
    private val model = Model(modelId = "deepseek-chat", displayName = "DeepSeek Chat")
    private val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "DeepSeek", models = listOf(model))
    private val currentAssistant = Assistant(name = "Current")

    private var settings = Settings(
        providers = listOf(provider),
        assistants = listOf(currentAssistant, Assistant(name = "Other")),
        mcpServers = emptyList(),
    )
    private var savedSkills = mutableListOf<SkillMetadata>()

    private fun tools(): List<Tool> = createAppConfigTools(
        updateSettings = { block -> settings = block(settings) },
        saveSkill = { name, _ ->
            SkillMetadata(name, "saved $name", skillDir = File("."))
                .also { savedSkills += it }
        },
        deleteSkill = { skill -> savedSkills.removeAll { it.name == skill.name } },
        currentAssistantId = currentAssistant.id,
        workspaces = { emptyList() },
        skills = { savedSkills },
    )

    private fun Tool.call(vararg args: Pair<String, Any>): JsonObject = runBlocking {
        val input = JsonObject(args.toMap().mapValues { (_, value) ->
            when (value) {
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        })
        val text = execute(input).filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        JsonInstant.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.error(): String? = (this["error"] as? JsonPrimitive)?.contentOrNullOrNull()

    private fun JsonPrimitive.contentOrNullOrNull(): String? =
        if (isString || this !is kotlinx.serialization.json.JsonNull) content else null

    @Test
    fun `config tools match the planned names and level`() {
        val names = tools().map { it.name }.toSet()
        assertEquals(
            setOf(
                "assistant_create", "assistant_update", "assistant_delete",
                "skill_create", "skill_delete",
                "setting_set",
                "mcp_server_upsert", "mcp_server_remove",
                "quick_message_set", "mode_injection_set",
            ),
            names,
        )
        names.forEach { name ->
            assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, AgentToolLevels.requiredLevel(name))
        }
    }

    @Test
    fun `assistant create uses read only by default and validates input`() {
        val created = tools().first { it.name == "assistant_create" }
            .call("name" to "New", "system_prompt" to "be brief")
        assertEquals(true, created["ok"]!!.jsonPrimitive.content.toBoolean())
        val newId = created["id"]!!.jsonPrimitive.content
        val assistant = settings.assistants.first { it.id.toString() == newId }
        assertEquals("New", assistant.name)
        assertEquals("be brief", assistant.systemPrompt)
        // 新建默认只读，不是继承调用者的等级
        assertEquals(AgentPermissionLevel.READ_ONLY, assistant.permissionLevel)

        val badModel = tools().first { it.name == "assistant_create" }
            .call("name" to "X", "chat_model_id" to Uuid.random().toString())
        assertTrue(badModel.error().orEmpty().contains("model not found"))

        val badLevel = tools().first { it.name == "assistant_create" }
            .call("name" to "X", "permission_level" to "root")
        assertTrue(badLevel.error().orEmpty().contains("permission_level must be one of"))
    }

    @Test
    fun `assistant update only touches the given fields`() {
        val updated = tools().first { it.name == "assistant_update" }.call(
            "assistant_id" to currentAssistant.id.toString(),
            "permission_level" to "full_access",
        )
        assertEquals(true, updated["ok"]!!.jsonPrimitive.content.toBoolean())
        val current = settings.assistants.first { it.id == currentAssistant.id }
        assertEquals(AgentPermissionLevel.FULL_ACCESS, current.permissionLevel)
        // 没传的字段保持原样
        assertEquals("Current", current.name)
    }

    @Test
    fun `assistant delete refuses the running assistant and the last one`() {
        val selfDelete = tools().first { it.name == "assistant_delete" }
            .call("assistant_id" to currentAssistant.id.toString())
        assertTrue(selfDelete.error().orEmpty().contains("running this conversation"))

        // 只剩一个助手时也要拒绝
        settings = settings.copy(assistants = listOf(currentAssistant))
        val lastDelete = tools().first { it.name == "assistant_delete" }
            .call("assistant_id" to currentAssistant.id.toString())
        assertTrue(lastDelete.error().orEmpty().contains("running this conversation"))

        val other = settings.assistants.let { listOf(currentAssistant, Assistant(name = "Doomed")) }
        settings = settings.copy(assistants = other)
        val deleted = tools().first { it.name == "assistant_delete" }
            .call("assistant_id" to other.last().id.toString())
        assertEquals(true, deleted["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, settings.assistants.size)
    }

    @Test
    fun `setting set enforces the whitelist and value ranges`() {
        val unknown = tools().first { it.name == "setting_set" }
            .call("key" to "provider_api_key", "value" to "sk-x")
        assertTrue(unknown.error().orEmpty().contains("not writable"))

        val port = tools().first { it.name == "setting_set" }
            .call("key" to "web_server_port", "value" to "8081")
        assertEquals(true, port["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(8081, settings.webServerPort)

        val badPort = tools().first { it.name == "setting_set" }
            .call("key" to "web_server_port", "value" to "22")
        assertTrue(badPort.error().orEmpty().contains("1024..65535"))

        val badBool = tools().first { it.name == "setting_set" }
            .call("key" to "dynamic_color", "value" to "yes")
        assertTrue(badBool.error().orEmpty().contains("true or false"))
    }

    @Test
    fun `mcp server upsert validates the url and remove detaches assistants`() {
        val bad = tools().first { it.name == "mcp_server_upsert" }
            .call("name" to "local", "url" to "ftp://example.com")
        assertTrue(bad.error().orEmpty().contains("absolute http(s) URL"))

        val created = tools().first { it.name == "mcp_server_upsert" }
            .call("name" to "local", "url" to "https://mcp.example.com/sse", "transport" to "sse")
        val serverId = created["id"]!!.jsonPrimitive.content
        assertEquals(1, settings.mcpServers.size)
        assertEquals("local", settings.mcpServers.single().commonOptions.name)

        // 挂到助手上再删，确认引用被一起清掉
        settings = settings.copy(
            assistants = settings.assistants.map {
                if (it.id == currentAssistant.id) it.copy(mcpServers = setOf(Uuid.parse(serverId))) else it
            }
        )
        val removed = tools().first { it.name == "mcp_server_remove" }
            .call("server_id" to serverId)
        assertEquals(true, removed["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(settings.mcpServers.isEmpty())
        assertTrue(settings.assistants.none { it.mcpServers.isNotEmpty() })
    }

    @Test
    fun `quick message and mode injection attach to the running assistant`() {
        val message = tools().first { it.name == "quick_message_set" }
            .call("title" to "Hello", "content" to "Hi there")
        val messageId = message["id"]!!.jsonPrimitive.content
        assertEquals(1, settings.quickMessages.size)
        assertTrue(
            settings.assistants.first { it.id == currentAssistant.id }.quickMessageIds.contains(Uuid.parse(messageId))
        )

        val injection = tools().first { it.name == "mode_injection_set" }.call(
            "name" to "Style",
            "content" to "Answer in Chinese",
            "position" to "bottom_of_chat",
            "role" to "assistant",
        )
        val injectionId = injection["id"]!!.jsonPrimitive.content
        val saved = settings.modeInjections.first { it.id.toString() == injectionId }
        assertEquals(PromptInjection.ModeInjection::class, saved::class)
        assertEquals("Answer in Chinese", saved.content)
        assertTrue(
            settings.assistants.first { it.id == currentAssistant.id }.modeInjectionIds.contains(
                Uuid.parse(injectionId)
            )
        )
    }
}
