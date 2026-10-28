package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/**
 * F2 只读层：让模型知道 RikkaHub 里有什么（助手、提供商、模型、工作区、技能），并把界面打开到对应页面。
 *
 * 输出里**没有任何密钥**：提供商只给 id/名称/模型清单，`apiKey`、自定义 header 的值一律不出现。
 * 写类工具（assistant_* / setting_set / provider_* 等）在 F2 的 CONFIG、SECRETS 两档里，另起文件。
 */
fun createAppReadOnlyTools(
    settings: Settings,
    assistant: Assistant,
    conversationId: Uuid?,
    workspace: suspend () -> List<WorkspaceEntity>,
    skills: () -> List<SkillMetadata>,
    appEventBus: AppEventBus,
): List<Tool> = listOf(
    appStateTool(settings, assistant, conversationId, workspace, skills),
    appListAssistantsTool(settings),
    appListProvidersTool(settings),
    appListModelsTool(settings),
    appListWorkspacesTool(workspace),
    appListSkillsTool(skills),
    appOpenTool(appEventBus),
)

private fun appStateTool(
    settings: Settings,
    assistant: Assistant,
    conversationId: Uuid?,
    workspaces: suspend () -> List<WorkspaceEntity>,
    skills: () -> List<SkillMetadata>,
) = Tool(
    name = "app_state",
    description = """
        Describe the RikkaHub state this turn runs in: the current assistant, its chat model and provider, the bound workspace, the permission level, and how many assistants/providers/models/workspaces/skills exist.
        Use it before changing any configuration so you know what you are working with. Secrets (API keys, passwords, headers) are never included.
    """.trimIndent(),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val providerName = model?.let { chatModel -> providerOf(settings, chatModel.id)?.name }
        val workspace = assistant.workspaceId?.let { id ->
            workspaces().firstOrNull { it.id == id.toString() }
        }
        val skillCount = skills().size
        val payload = buildJsonObject {
            put("assistant", buildJsonObject {
                put("id", assistant.id.toString())
                put("name", assistant.name)
                put("permission_level", assistant.permissionLevel.wireName)
                putJsonArray("enabled_skills") {
                    assistant.enabledSkills.sorted().forEach { add(it) }
                }
            })
            put("model", buildJsonObject {
                put("display_name", model?.displayName.orEmpty())
                put("model_id", model?.modelId.orEmpty())
                put("provider", providerName.orEmpty())
                put("context_length", model?.contextLength ?: 0)
            })
            put("workspace", buildJsonObject {
                put("id", workspace?.id.orEmpty())
                put("name", workspace?.name.orEmpty())
                put("type", workspace?.type.orEmpty())
                put("default_dir", workspace?.workspaceDir().orEmpty())
                put("shell_status", workspace?.shellStatus.orEmpty())
            })
            put("conversation_id", conversationId?.toString().orEmpty())
            put("environment", buildJsonObject {
                put("assistants", settings.assistants.size)
                put("providers", settings.providers.size)
                put("models", settings.providers.sumOf { it.models.size })
                put("skills", skillCount)
                put("mcp_servers", settings.mcpServers.size)
                put("quick_messages", settings.quickMessages.size)
                put("permission_levels", "read_only < workspace_write < full_access")
            })
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

private fun appListAssistantsTool(settings: Settings) = Tool(
    name = "app_list_assistants",
    description = """
        List the user's assistants (name, id, model, bound workspace, permission level, enabled skills).
        Use it to find the assistant id you need for other tools, or to survey how the user has set things up.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive filter on the assistant name")
                })
            }
        )
    },
    execute = { args ->
        val query = args.strArg("query").trim()
        val payload = buildJsonArray {
            settings.assistants
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id.toString())
                        put("name", item.name)
                        put("chat_model_id", item.chatModelId?.toString().orEmpty())
                        put("workspace_id", item.workspaceId?.toString().orEmpty())
                        put("permission_level", item.permissionLevel.wireName)
                        putJsonArray("enabled_skills") {
                            item.enabledSkills.sorted().forEach { add(it) }
                        }
                    })
                }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

private fun appListProvidersTool(settings: Settings) = Tool(
    name = "app_list_providers",
    description = """
        List the configured providers (id, name, type, enabled, model count and the model ids they expose).
        API keys and custom header values are never returned.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("include_models", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, also list each provider's models (default false)")
                })
            }
        )
    },
    execute = { args ->
        val includeModels = args.boolArg("include_models") ?: false
        val payload = buildJsonArray {
            settings.providers.forEach { provider -> add(providerSummary(provider, includeModels)) }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

private fun appListModelsTool(settings: Settings) = Tool(
    name = "app_list_models",
    description = """
        List every model the user has added, with the provider it belongs to.
        Filter by provider id or by model type (chat / image / embedding) when the list is long.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Only models of this provider")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        ModelType.entries.forEach { add(it.name.lowercase()) }
                    })
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive filter on display name or model id")
                })
            }
        )
    },
    execute = { args ->
        val providerId = args.strArg("provider_id").trim()
        val type = args.strArg("type").trim().lowercase().ifEmpty { null }
        val query = args.strArg("query").trim()
        val payload = buildJsonArray {
            settings.providers
                .filter { providerId.isEmpty() || it.id.toString() == providerId }
                .forEach { provider ->
                    provider.models
                        .filter { model ->
                            (type == null || model.type.name.lowercase() == type) &&
                                (query.isEmpty() ||
                                    model.displayName.contains(query, ignoreCase = true) ||
                                    model.modelId.contains(query, ignoreCase = true))
                        }
                        .forEach { model -> add(modelSummary(model, provider)) }
                }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

private fun appListWorkspacesTool(workspaces: suspend () -> List<WorkspaceEntity>) = Tool(
    name = "app_list_workspaces",
    description = """
        List the workspaces the user has (name, id, type, shell status, default directory).
        An assistant bound to a workspace gives you file and shell tools for that environment.
    """.trimIndent(),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val payload = buildJsonArray {
            workspaces().forEach { workspace ->
                add(buildJsonObject {
                    put("id", workspace.id)
                    put("name", workspace.name)
                    put("type", workspace.type)
                    put("shell_status", workspace.shellStatus)
                    put("default_dir", workspace.workspaceDir().orEmpty())
                })
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

private fun appListSkillsTool(skills: () -> List<SkillMetadata>) = Tool(
    name = "app_list_skills",
    description = """
        List the skills available on this device (name, description, whether it is built in).
        The current assistant only sees the skills enabled for it; use `app_list_assistants` to check that list.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive filter on the skill name or description")
                })
            }
        )
    },
    execute = { args ->
        val query = args.strArg("query").trim()
        val payload = buildJsonArray {
            skills()
                .filter {
                    query.isEmpty() ||
                        it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true)
                }
                .sortedBy { it.name }
                .forEach { skill ->
                    add(buildJsonObject {
                        put("name", skill.name)
                        put("description", skill.description)
                        put("built_in", skill.builtin)
                    })
                }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

private fun appOpenTool(appEventBus: AppEventBus) = Tool(
    name = "app_open",
    description = """
        Bring a RikkaHub screen to the foreground on the user's phone: the assistant list, the workspace list, skills, prompts, quick messages, MCP servers, providers, models, settings, or a specific assistant/workspace.
        Use it when the next step is easier for the user to do by hand (for example "打开提供商设置").
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("target", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            listOf(
                                "assistants", "assistant", "workspaces", "workspace", "skills",
                                "prompts", "quick_messages", "mcp", "providers", "models",
                                "settings", "web",
                            ).forEach { add(it) }
                        }
                    )
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Assistant or workspace id, required for the `assistant` / `workspace` targets")
                })
            },
            required = listOf("target"),
        )
    },
    execute = { args ->
        val target = args.strArg("target").trim().lowercase()
        val id = args.strArg("id").trim()
        val screen = appOpenTargetScreen(target, id)
        if (screen == null) {
            listOf(
                UIMessagePart.Text(
                    "Unknown or incomplete target: $target. Provide one of the enum values, plus `id` for assistant/workspace."
                )
            )
        } else {
            appEventBus.emit(AppEvent.Navigate(screen))
            listOf(UIMessagePart.Text("Opened $target on the device."))
        }
    },
)

/** 提供商摘要：只给 id/名称/类型/模型，`apiKey`、自定义 header 的值一概不出现在这里 */
internal fun providerSummary(provider: ProviderSetting, includeModels: Boolean): JsonObject = buildJsonObject {
    put("id", provider.id.toString())
    put("name", provider.name)
    put("type", providerTypeName(provider))
    put("enabled", provider.enabled)
    put("built_in", provider.builtIn)
    put("model_count", provider.models.size)
    if (includeModels) {
        putJsonArray("models") {
            provider.models.forEach { model -> add(modelSummary(model, provider)) }
        }
    }
}

/** `app_open` 的 target → 路由；缺 id 的 assistant/workspace 返回 null，交给调用方报错 */
internal fun appOpenTargetScreen(target: String, id: String): Screen? = when (target) {
    "assistants" -> Screen.Assistant
    "assistant" -> id.takeIf { it.isNotBlank() }?.let { Screen.AssistantDetail(it) }
    "workspaces" -> Screen.Workspaces
    "workspace" -> id.takeIf { it.isNotBlank() }?.let { Screen.WorkspaceDetail(it) }
    "skills" -> Screen.Skills
    "prompts" -> Screen.Prompts
    "quick_messages" -> Screen.QuickMessages
    "mcp" -> Screen.SettingMcp
    "providers" -> Screen.SettingProvider
    "models" -> Screen.SettingModels
    "settings" -> Screen.Setting
    "web" -> Screen.SettingWeb
    else -> null
}

internal fun modelSummary(model: Model, provider: ProviderSetting) = buildJsonObject {
    put("id", model.id.toString())
    put("model_id", model.modelId)
    put("display_name", model.displayName)
    put("type", model.type.name.lowercase())
    put("context_length", model.contextLength)
    put("provider_id", provider.id.toString())
    put("provider", provider.name)
}

private fun providerOf(settings: Settings, modelId: Uuid): ProviderSetting? =
    settings.providers.firstOrNull { provider -> provider.models.any { it.id == modelId } }

/** 提供商的具体类型名，界面上就是用这个区分 OpenAI / Claude / Google / 账号登录 */
private fun providerTypeName(provider: ProviderSetting): String = provider::class.simpleName.orEmpty()
