package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.AgentPermissionLevel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/**
 * F2 的 CONFIG 档：改助手、技能、全局设置、MCP、快捷消息、模式注入。
 *
 * 这一档**不碰凭据**（API Key / 密码 / 云存储密钥在 SECRETS 档）。写操作统一走注入的
 * [updateSettings]（生产环境就是 `SettingsStore.update`），保证并发修改不会互相覆盖；
 * 能在代码里断言的红线（删当前对话在用的助手、删最后一个助手）直接拒绝。
 */
fun createAppConfigTools(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    saveSkill: (name: String, content: String) -> SkillMetadata?,
    deleteSkill: suspend (SkillMetadata) -> Boolean,
    currentAssistantId: Uuid,
    workspaces: suspend () -> List<WorkspaceEntity>,
    skills: () -> List<SkillMetadata>,
): List<Tool> = listOf(
    assistantCreateTool(updateSettings, workspaces, skills),
    assistantUpdateTool(updateSettings, workspaces, skills),
    assistantDeleteTool(updateSettings, currentAssistantId),
    skillCreateTool(saveSkill, updateSettings, currentAssistantId),
    skillDeleteTool(deleteSkill, skills),
    settingSetTool(updateSettings),
    mcpServerUpsertTool(updateSettings),
    mcpServerRemoveTool(updateSettings),
    quickMessageSetTool(updateSettings, currentAssistantId),
    modeInjectionSetTool(updateSettings, currentAssistantId),
)

// ---------------------------------------------------------------- 助手

private fun assistantCreateTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    workspaces: suspend () -> List<WorkspaceEntity>,
    skills: () -> List<SkillMetadata>,
) = Tool(
    name = "assistant_create",
    description = """
        Create a new assistant. New assistants start at read_only permission and with no tools switched on beyond the defaults; set the permission level explicitly when the user wants more.
        Returns the new assistant id.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                assistantPatchProperties()
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Display name of the new assistant")
                })
            },
            required = listOf("name"),
        )
    },
    execute = { args ->
        toolResult {
            val name = args.strOrNull("name")
                ?: throw ToolInputException("name is required")
            val workspaceList = workspaces()
            val skillNames = skills().mapTo(HashSet()) { it.name }
            var created: Assistant? = null
            updateSettings { latest ->
                val assistant = Assistant(
                    name = name,
                    // 新建默认只读：越权时用户会看到提示，而不是静默拿到写权限
                    permissionLevel = AgentPermissionLevel.READ_ONLY,
                ).applyPatch(args, latest, workspaceList, skillNames)
                created = assistant
                latest.copy(assistants = latest.assistants + assistant)
            }
            val assistant = created ?: throw ToolInputException("failed to create assistant")
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", assistant.id.toString())
                put("name", assistant.name)
            })))
        }
    },
)

private fun assistantUpdateTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    workspaces: suspend () -> List<WorkspaceEntity>,
    skills: () -> List<SkillMetadata>,
) = Tool(
    name = "assistant_update",
    description = """
        Update fields of an existing assistant. Only the fields you pass are changed; everything else stays as it is.
        Use app_list_assistants first to get the id.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("assistant_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Id of the assistant to update")
                })
                assistantPatchProperties()
            },
            required = listOf("assistant_id"),
        )
    },
    execute = { args ->
        toolResult {
            val id = args.strOrNull("assistant_id")
                ?: throw ToolInputException("assistant_id is required")
            val workspaceList = workspaces()
            val skillNames = skills().mapTo(HashSet()) { it.name }
            var changed: List<String> = emptyList()
            updateSettings { latest ->
                val target = latest.assistants.firstOrNull { it.id.toString() == id }
                    ?: throw ToolInputException("assistant not found: $id")
                val updated = target.applyPatch(args, latest, workspaceList, skillNames)
                changed = changedFields(args)
                latest.copy(
                    assistants = latest.assistants.map { if (it.id == target.id) updated else it }
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", id)
                put("changed", buildJsonArray { changed.forEach { add(it) } })
            })))
        }
    },
)

private fun assistantDeleteTool(updateSettings: suspend ((Settings) -> Settings) -> Unit, currentAssistantId: Uuid) = Tool(
    name = "assistant_delete",
    description = """
        Delete an assistant by id.
        The assistant running this conversation cannot be deleted, and the last remaining assistant cannot be deleted either.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("assistant_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Id of the assistant to delete")
                })
            },
            required = listOf("assistant_id"),
        )
    },
    execute = { args ->
        toolResult {
            val id = args.strOrNull("assistant_id")
                ?: throw ToolInputException("assistant_id is required")
            val targetId = runCatching { Uuid.parse(id) }.getOrNull()
                ?: throw ToolInputException("invalid assistant id: $id")
            // 红线：不能删正在跑这次对话的助手
            if (targetId == currentAssistantId) {
                throw ToolInputException("refusing to delete the assistant that is running this conversation")
            }
            var deleted: Assistant? = null
            updateSettings { latest ->
                if (latest.assistants.size <= 1) {
                    throw ToolInputException("refusing to delete the last assistant")
                }
                val target = latest.assistants.firstOrNull { it.id == targetId }
                    ?: throw ToolInputException("assistant not found: $id")
                deleted = target
                val remaining = latest.assistants.filterNot { it.id == targetId }
                latest.copy(
                    assistants = remaining,
                    // 全局选中的助手被删掉时退到剩下的第一个
                    assistantId = if (latest.assistantId == targetId) remaining.first().id else latest.assistantId,
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", id)
                put("name", deleted?.name.orEmpty())
            })))
        }
    },
)

// ---------------------------------------------------------------- 技能

private fun skillCreateTool(
    saveSkill: (name: String, content: String) -> SkillMetadata?,
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    currentAssistantId: Uuid,
) = Tool(
    name = "skill_create",
    description = """
        Create or overwrite a skill from a full SKILL.md document (YAML frontmatter with name/description, then the instructions).
        Pass enable_for_assistant=false to only write the skill without enabling it for this assistant.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Skill directory name, e.g. release-notes")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full SKILL.md content, starting with the --- frontmatter block")
                })
                put("enable_for_assistant", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Enable the skill for this assistant (default true)")
                })
            },
            required = listOf("name", "content"),
        )
    },
    execute = { args ->
        toolResult {
            val name = args.strOrNull("name") ?: throw ToolInputException("name is required")
            if (name.any { it.isWhitespace() || it == '/' || it == '\\' }) {
                throw ToolInputException("skill name must not contain whitespace or slashes")
            }
            val content = args.strArg("content")
            if (content.isBlank()) throw ToolInputException("content is required")
            val skill = saveSkill(name, content)
                ?: throw ToolInputException("failed to save skill: $name")
            val enable = args.boolArg("enable_for_assistant") ?: true
            if (enable) {
                updateSettings { latest ->
                    latest.copy(assistants = latest.assistants.map { assistant ->
                        if (assistant.id == currentAssistantId) {
                            assistant.copy(enabledSkills = assistant.enabledSkills + skill.name)
                        } else {
                            assistant
                        }
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("name", skill.name)
                put("enabled_for_assistant", enable)
            })))
        }
    },
)

private fun skillDeleteTool(
    deleteSkill: suspend (SkillMetadata) -> Boolean,
    skills: () -> List<SkillMetadata>,
) = Tool(
    name = "skill_delete",
    description = "Delete a user-created skill by name. Built-in skills cannot be deleted.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Skill name as returned by app_list_skills")
                })
            },
            required = listOf("name"),
        )
    },
    execute = { args ->
        toolResult {
            val name = args.strOrNull("name") ?: throw ToolInputException("name is required")
            val skill = skills().firstOrNull { it.name == name }
                ?: throw ToolInputException("skill not found: $name")
            if (skill.builtin) throw ToolInputException("refusing to delete built-in skill: $name")
            if (!deleteSkill(skill)) {
                throw ToolInputException("failed to delete skill: $name")
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("name", name)
            })))
        }
    },
)

// ---------------------------------------------------------------- 全局设置

private val WRITABLE_SETTING_KEYS = listOf(
    "dynamic_color", "developer_mode", "enable_auto_title", "enable_suggestion",
    "chat_model_id", "fast_model_id", "translate_target_language",
    "web_server_enabled", "web_server_port", "web_server_localhost_only",
)

private fun settingSetTool(updateSettings: suspend ((Settings) -> Settings) -> Unit) = Tool(
    name = "setting_set",
    description = """
        Change one global setting. Only whitelisted keys are writable: ${WRITABLE_SETTING_KEYS.joinToString(", ")}.
        Values are validated (model ids must exist, ports must be in range); on success the applied value is returned.
        Secrets such as API keys, passwords and cloud storage credentials are not settable here.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { WRITABLE_SETTING_KEYS.forEach { add(it) } })
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "New value; booleans/numbers are given as strings too, e.g. \"true\" or \"8080\"")
                })
            },
            required = listOf("key", "value"),
        )
    },
    execute = { args ->
        toolResult {
            val key = args.strOrNull("key") ?: throw ToolInputException("key is required")
            if (key !in WRITABLE_SETTING_KEYS) {
                throw ToolInputException(
                    "setting is not writable: $key (allowed: ${WRITABLE_SETTING_KEYS.joinToString(", ")})"
                )
            }
            val value = args.strOrNull("value") ?: throw ToolInputException("value is required")
            var applied: String = value
            updateSettings { latest ->
                val (next, appliedValue) = latest.applySetting(key, value)
                applied = appliedValue
                next
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("key", key)
                put("value", applied)
            })))
        }
    },
)

private fun Settings.applySetting(key: String, raw: String): Pair<Settings, String> = when (key) {
    "dynamic_color" -> copy(dynamicColor = raw.toBool(key)) to raw.toBool(key).toString()
    "developer_mode" -> copy(developerMode = raw.toBool(key)) to raw.toBool(key).toString()
    "enable_auto_title" -> copy(enableAutoTitle = raw.toBool(key)) to raw.toBool(key).toString()
    "enable_suggestion" -> copy(enableSuggestion = raw.toBool(key)) to raw.toBool(key).toString()
    "chat_model_id" -> copy(chatModelId = raw.toModelId(this)) to raw
    "fast_model_id" -> copy(fastModelId = raw.toModelId(this)) to raw
    "translate_target_language" -> {
        if (raw.length > 20) throw ToolInputException("language tag looks invalid: $raw")
        copy(translateTargetLanguage = raw) to raw
    }

    "web_server_enabled" -> copy(webServerEnabled = raw.toBool(key)) to raw.toBool(key).toString()
    "web_server_localhost_only" -> copy(webServerLocalhostOnly = raw.toBool(key)) to raw.toBool(key).toString()
    "web_server_port" -> {
        val port = raw.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: throw ToolInputException("web_server_port must be 1024..65535")
        copy(webServerPort = port) to port.toString()
    }

    else -> throw ToolInputException("setting is not writable: $key")
}

private fun String.toBool(key: String): Boolean =
    toBooleanStrictOrNull() ?: throw ToolInputException("$key expects true or false")

private fun String.toModelId(settings: Settings): Uuid {
    val id = runCatching { Uuid.parse(this) }.getOrNull()
        ?: throw ToolInputException("invalid model id: $this")
    if (settings.findModelById(id) == null) {
        throw ToolInputException("model not found: $this (use app_list_models)")
    }
    return id
}

// ---------------------------------------------------------------- MCP

private fun mcpServerUpsertTool(updateSettings: suspend ((Settings) -> Settings) -> Unit) = Tool(
    name = "mcp_server_upsert",
    description = """
        Add or update an MCP server. Pass server_id to update an existing one, omit it to add a new one.
        Transport is streamable_http (default) or sse. Headers are write-only: use an Authorization header for tokens,
        they are never echoed back by any tool.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("server_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Existing server id; omit to create")
                })
                put("name", buildJsonObject { put("type", "string") })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute http(s) URL of the MCP endpoint")
                })
                put("transport", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("streamable_http"); add("sse") })
                })
                put("enable", buildJsonObject { put("type", "boolean") })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "Extra HTTP headers, e.g. {\"Authorization\": \"Bearer ...\"}")
                })
            }
        )
    },
    execute = { args ->
        toolResult {
            val serverId = args.strOrNull("server_id")?.let { raw ->
                runCatching { Uuid.parse(raw) }.getOrNull()
                    ?: throw ToolInputException("invalid server_id: $raw")
            }
            val name = args.strOrNull("name")
            val url = args.strOrNull("url")
            val transport = args.strOrNull("transport") ?: "streamable_http"
            if (transport !in setOf("streamable_http", "sse")) {
                throw ToolInputException("transport must be streamable_http or sse")
            }
            val headers = args.headersArg()
            var savedId: String? = null
            updateSettings { latest ->
                val existing = serverId?.let { id -> latest.mcpServers.firstOrNull { it.id == id } }
                if (serverId != null && existing == null) {
                    throw ToolInputException("MCP server not found: $serverId")
                }
                val finalUrl = url ?: existing?.url().orEmpty()
                if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                    throw ToolInputException("url must be an absolute http(s) URL")
                }
                val finalName = name ?: existing?.commonOptions?.name ?: "MCP"
                val options = (existing?.commonOptions ?: McpCommonOptions()).copy(
                    name = finalName,
                    enable = args.boolArg("enable") ?: existing?.commonOptions?.enable ?: true,
                    // header 是写操作：不传就沿用，传了就整体替换
                    headers = headers ?: existing?.commonOptions?.headers ?: emptyList(),
                )
                val server = when (transport) {
                    "sse" -> McpServerConfig.SseTransportServer(
                        id = existing?.id ?: Uuid.random(),
                        commonOptions = options,
                        url = finalUrl,
                    )

                    else -> McpServerConfig.StreamableHTTPServer(
                        id = existing?.id ?: Uuid.random(),
                        commonOptions = options,
                        url = finalUrl,
                    )
                }
                savedId = server.id.toString()
                latest.copy(
                    mcpServers = if (existing == null) {
                        latest.mcpServers + server
                    } else {
                        latest.mcpServers.map { if (it.id == existing.id) server else it }
                    }
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", savedId.orEmpty())
            })))
        }
    },
)

private fun mcpServerRemoveTool(updateSettings: suspend ((Settings) -> Settings) -> Unit) = Tool(
    name = "mcp_server_remove",
    description = "Remove an MCP server and detach it from every assistant.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("server_id", buildJsonObject { put("type", "string") })
            },
            required = listOf("server_id"),
        )
    },
    execute = { args ->
        toolResult {
            val raw = args.strOrNull("server_id") ?: throw ToolInputException("server_id is required")
            val id = runCatching { Uuid.parse(raw) }.getOrNull()
                ?: throw ToolInputException("invalid server_id: $raw")
            updateSettings { latest ->
                if (latest.mcpServers.none { it.id == id }) {
                    throw ToolInputException("MCP server not found: $raw")
                }
                latest.copy(
                    mcpServers = latest.mcpServers.filterNot { it.id == id },
                    assistants = latest.assistants.map { assistant ->
                        if (id in assistant.mcpServers) {
                            assistant.copy(mcpServers = assistant.mcpServers - id)
                        } else {
                            assistant
                        }
                    },
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", raw)
            })))
        }
    },
)

// ---------------------------------------------------------------- 快捷消息 / 模式注入

private fun quickMessageSetTool(updateSettings: suspend ((Settings) -> Settings) -> Unit, currentAssistantId: Uuid) = Tool(
    name = "quick_message_set",
    description = """
        Create or update a quick message (a reusable snippet the user inserts into the composer).
        Omit quick_message_id to create a new one. By default the message is also attached to this assistant.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("quick_message_id", buildJsonObject { put("type", "string") })
                put("title", buildJsonObject { put("type", "string") })
                put("content", buildJsonObject { put("type", "string") })
                put("attach_to_assistant", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Attach to this assistant (default true)")
                })
            },
            required = listOf("title", "content"),
        )
    },
    execute = { args ->
        toolResult {
            val title = args.strOrNull("title") ?: throw ToolInputException("title is required")
            val content = args.strOrNull("content") ?: throw ToolInputException("content is required")
            val attach = args.boolArg("attach_to_assistant") ?: true
            val existingId = args.strOrNull("quick_message_id")?.let { raw ->
                runCatching { Uuid.parse(raw) }.getOrNull()
                    ?: throw ToolInputException("invalid quick_message_id: $raw")
            }
            var savedId: String? = null
            updateSettings { latest ->
                val existing = existingId?.let { id -> latest.quickMessages.firstOrNull { it.id == id } }
                if (existingId != null && existing == null) {
                    throw ToolInputException("quick message not found: $existingId")
                }
                val message = (existing ?: QuickMessage()).copy(title = title, content = content)
                savedId = message.id.toString()
                latest.copy(
                    quickMessages = if (existing == null) {
                        latest.quickMessages + message
                    } else {
                        latest.quickMessages.map { if (it.id == message.id) message else it }
                    },
                    assistants = if (attach) {
                        latest.assistants.map { assistant ->
                            if (assistant.id == currentAssistantId) {
                                assistant.copy(quickMessageIds = assistant.quickMessageIds + message.id)
                            } else {
                                assistant
                            }
                        }
                    } else {
                        latest.assistants
                    },
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", savedId.orEmpty())
                put("attached_to_assistant", attach)
            })))
        }
    },
)

private fun modeInjectionSetTool(updateSettings: suspend ((Settings) -> Settings) -> Unit, currentAssistantId: Uuid) = Tool(
    name = "mode_injection_set",
    description = """
        Create or update a mode injection (a prompt fragment injected at a chosen position).
        Omit mode_injection_id to create a new one. Position is one of ${InjectionPosition.entries.joinToString(", ") { it.name.lowercase() }};
        role is one of user / assistant / system. By default the injection is attached to this assistant.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("mode_injection_id", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject { put("type", "string") })
                put("content", buildJsonObject { put("type", "string") })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("position", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        InjectionPosition.entries.forEach { add(it.name.lowercase()) }
                    })
                })
                put("depth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Inject this many messages deep from the end (0 = disabled)")
                })
                put("role", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("user"); add("assistant"); add("system") })
                })
                put("priority", buildJsonObject { put("type", "integer") })
                put("attach_to_assistant", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("name", "content"),
        )
    },
    execute = { args ->
        toolResult {
            val name = args.strOrNull("name") ?: throw ToolInputException("name is required")
            val content = args.strOrNull("content") ?: throw ToolInputException("content is required")
            val attach = args.boolArg("attach_to_assistant") ?: true
            val position = args.strOrNull("position")?.let { raw ->
                InjectionPosition.entries.firstOrNull { it.name.lowercase() == raw }
                    ?: throw ToolInputException("unknown position: $raw")
            }
            val role = args.strOrNull("role")?.let { raw ->
                MessageRole.entries.firstOrNull { it.name.lowercase() == raw }
                    ?: throw ToolInputException("unknown role: $raw")
            }
            val existingId = args.strOrNull("mode_injection_id")?.let { raw ->
                runCatching { Uuid.parse(raw) }.getOrNull()
                    ?: throw ToolInputException("invalid mode_injection_id: $raw")
            }
            var savedId: String? = null
            updateSettings { latest ->
                val existing = existingId?.let { id -> latest.modeInjections.firstOrNull { it.id == id } }
                if (existingId != null && existing == null) {
                    throw ToolInputException("mode injection not found: $existingId")
                }
                val injection = (existing ?: PromptInjection.ModeInjection()).copy(
                    name = name,
                    content = content,
                    enabled = args.boolArg("enabled") ?: existing?.enabled ?: true,
                    position = position ?: existing?.position ?: InjectionPosition.AFTER_SYSTEM_PROMPT,
                    injectDepth = args.intArg("depth") ?: existing?.injectDepth ?: 4,
                    role = role ?: existing?.role ?: MessageRole.USER,
                    priority = args.intArg("priority") ?: existing?.priority ?: 0,
                )
                savedId = injection.id.toString()
                latest.copy(
                    modeInjections = if (existing == null) {
                        latest.modeInjections + injection
                    } else {
                        latest.modeInjections.map { if (it.id == injection.id) injection else it }
                    },
                    assistants = if (attach) {
                        latest.assistants.map { assistant ->
                            if (assistant.id == currentAssistantId) {
                                assistant.copy(modeInjectionIds = assistant.modeInjectionIds + injection.id)
                            } else {
                                assistant
                            }
                        }
                    } else {
                        latest.assistants
                    },
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", savedId.orEmpty())
                put("attached_to_assistant", attach)
            })))
        }
    },
)

// ---------------------------------------------------------------- 公共部分

/** 助手可用字段的 schema 片段，create / update 共用 */
private fun JsonObjectBuilder.assistantPatchProperties() {
    put("system_prompt", buildJsonObject { put("type", "string") })
    put("chat_model_id", buildJsonObject {
        put("type", "string")
        put("description", "Model id from app_list_models")
    })
    put("workspace_id", buildJsonObject {
        put("type", "string")
        put("description", "Workspace id; empty string clears the binding")
    })
    put("permission_level", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { AgentPermissionLevel.entries.forEach { add(it.wireName) } })
    })
    put("enabled_skills", buildJsonObject {
        put("type", "array")
        put("description", "Skill names to enable; replaces the current list")
    })
    put("temperature", buildJsonObject { put("type", "number") })
    put("top_p", buildJsonObject { put("type", "number") })
    put("max_tokens", buildJsonObject { put("type", "integer") })
    put("context_message_limit", buildJsonObject { put("type", "integer") })
    put("stream_output", buildJsonObject { put("type", "boolean") })
    put("enable_memory", buildJsonObject { put("type", "boolean") })
    put("enable_web_search", buildJsonObject { put("type", "boolean") })
}

private fun Assistant.applyPatch(
    args: JsonElement,
    settings: Settings,
    workspaces: List<WorkspaceEntity>,
    skillNames: Set<String>,
): Assistant {
    var next = this
    args.strOrNull("name")?.let { next = next.copy(name = it) }
    args.strOrNull("system_prompt")?.let { next = next.copy(systemPrompt = it) }
    args.strOrNull("chat_model_id")?.let { raw ->
        val id = raw.toModelId(settings)
        next = next.copy(chatModelId = id)
    }
    if (args.hasArg("workspace_id")) {
        val raw = args.strArg("workspace_id").trim()
        next = if (raw.isEmpty()) {
            next.copy(workspaceId = null)
        } else {
            val workspace = workspaces.firstOrNull { it.id == raw }
                ?: throw ToolInputException("workspace not found: $raw (use app_list_workspaces)")
            next.copy(workspaceId = Uuid.parse(workspace.id))
        }
    }
    args.strOrNull("permission_level")?.let { raw ->
        val level = AgentPermissionLevel.entries.firstOrNull { it.wireName == raw }
            ?: throw ToolInputException(
                "permission_level must be one of ${AgentPermissionLevel.entries.joinToString(", ") { it.wireName }}"
            )
        next = next.copy(permissionLevel = level)
    }
    args.stringListArg("enabled_skills")?.let { names ->
        val unknown = names.filterNot { it in skillNames }
        if (unknown.isNotEmpty()) {
            throw ToolInputException("unknown skill(s): ${unknown.joinToString(", ")} (use app_list_skills)")
        }
        next = next.copy(enabledSkills = names.toSet())
    }
    args.doubleArg("temperature")?.let { value ->
        if (value !in 0.0..2.0) throw ToolInputException("temperature must be 0..2")
        next = next.copy(temperature = value.toFloat())
    }
    args.doubleArg("top_p")?.let { value ->
        if (value !in 0.0..1.0) throw ToolInputException("top_p must be 0..1")
        next = next.copy(topP = value.toFloat())
    }
    args.intArg("max_tokens")?.let { value ->
        if (value < 1) throw ToolInputException("max_tokens must be positive")
        next = next.copy(maxTokens = value)
    }
    args.intArg("context_message_limit")?.let { value ->
        if (value !in 0..1000) throw ToolInputException("context_message_limit must be 0..1000")
        next = next.copy(contextMessageLimit = value)
    }
    args.boolArg("stream_output")?.let { next = next.copy(streamOutput = it) }
    args.boolArg("enable_memory")?.let { next = next.copy(enableMemory = it) }
    args.boolArg("enable_web_search")?.let { next = next.copy(enableWebSearch = it) }
    return next
}

private fun changedFields(args: JsonElement): List<String> =
    (args as? JsonObject)?.keys?.filterNot { it == "assistant_id" }?.sorted().orEmpty()

private fun JsonElement.headersArg(): List<Pair<String, String>>? {
    val obj = jsonObjectOrNull() ?: return null
    return obj.entries.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
    }
}

private fun McpServerConfig.url(): String = when (this) {
    is McpServerConfig.SseTransportServer -> url
    is McpServerConfig.StreamableHTTPServer -> url
}
