package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/**
 * F2 的 SECRETS 档：写提供商密钥、S3/WebDAV/SSH/Web 服务的凭据。
 *
 * 规则（计划里的硬约束）：
 * - 写入类工具只接受新值，**永不回显明文**，返回值固定 `{ ok, masked }`。
 * - 读类信息（有没有配置）由只读档给 `has_key`，不给内容。
 * - 这些工具都在 `FULL_ACCESS` 档，只有完全放行的会话才会注册。
 */
fun createAppSecretTools(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    masker: SecretMasker,
    workspaces: suspend () -> List<WorkspaceEntity>,
    saveSshCredentials: suspend (workspaceId: String, password: String?, privateKey: String?, passphrase: String?) -> Unit,
): List<Tool> = listOf(
    providerUpsertTool(updateSettings, masker),
    providerApiKeySetTool(updateSettings, masker),
    providerApiKeyClearTool(updateSettings),
    s3ConfigSetTool(updateSettings, masker),
    webDavConfigSetTool(updateSettings, masker),
    sshWorkspaceCredentialSetTool(workspaces, saveSshCredentials, masker),
    webServerSecretSetTool(updateSettings, masker),
)

// ---------------------------------------------------------------- 提供商

private fun providerUpsertTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    masker: SecretMasker,
) = Tool(
    name = "provider_upsert",
    description = """
        Add or update an AI provider. Omit provider_id to create one. type is openai (also for OpenAI-compatible relays),
        claude or google. base_url must be http(s); switching to plain http or a private address requires user approval.
        api_key is write-only: only a masked value is returned. models upserts by model id, keeping models you do not mention.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", buildJsonObject { put("type", "string") })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("openai"); add("claude"); add("google") })
                })
                put("name", buildJsonObject { put("type", "string") })
                put("base_url", buildJsonObject { put("type", "string") })
                put("api_key", buildJsonObject {
                    put("type", "string")
                    put("description", "New key; omitted keeps the current one")
                })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("models", buildJsonObject {
                    put("type", "array")
                    put("description", "Models to add or update: [{ model_id, display_name }]")
                })
            }
        )
    },
    // 换成明文 http / 私网地址时强制审批（计划的红线 2）
    needsApproval = { args -> isRiskyEndpoint(args.strArg("base_url")) },
    execute = { args ->
        toolResult {
            val providerId = args.strOrNull("provider_id")
            val requestedType = args.strOrNull("type")?.lowercase()
            var maskedKey: String? = null
            var savedId: String? = null
            updateSettings { latest ->
                val existing = providerId?.let { id -> latest.providers.firstOrNull { it.id.toString() == id } }
                if (providerId != null && existing == null) {
                    throw ToolInputException("provider not found: $providerId")
                }
                val baseUrl = args.strOrNull("base_url")?.also(::requireHttpUrl)
                val apiKey = args.strOrNull("api_key")
                if (apiKey != null) maskedKey = masker.masked(apiKey)
                val modelSpecs = args.modelSpecs()
                val provider = when {
                    existing != null -> existing.edit(
                        name = args.strOrNull("name"),
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        enabled = args.boolArg("enabled"),
                        modelSpecs = modelSpecs,
                    )

                    else -> newProvider(
                        type = requestedType ?: "openai",
                        name = args.strOrNull("name") ?: "Provider",
                        baseUrl = baseUrl,
                        apiKey = apiKey.orEmpty(),
                        enabled = args.boolArg("enabled") ?: true,
                        modelSpecs = modelSpecs,
                    )
                }
                if (provider == null) {
                    throw ToolInputException("this provider type does not support API keys")
                }
                savedId = provider.id.toString()
                latest.copy(
                    providers = if (existing == null) {
                        latest.providers + provider
                    } else {
                        latest.providers.map { if (it.id == existing.id) provider else it }
                    }
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", savedId.orEmpty())
                if (maskedKey != null) put("masked", maskedKey!!)
            })))
        }
    },
)

private fun providerApiKeySetTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    masker: SecretMasker,
) = Tool(
    name = "provider_api_key_set",
    description = "Set the API key of an existing provider. The key is write-only; only a masked value is returned.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", buildJsonObject { put("type", "string") })
                put("api_key", buildJsonObject { put("type", "string") })
            },
            required = listOf("provider_id", "api_key"),
        )
    },
    execute = { args ->
        toolResult {
            val id = args.strOrNull("provider_id") ?: throw ToolInputException("provider_id is required")
            val key = args.strOrNull("api_key") ?: throw ToolInputException("api_key is required")
            updateSettings { latest ->
                val target = latest.providers.firstOrNull { it.id.toString() == id }
                    ?: throw ToolInputException("provider not found: $id")
                val updated = target.copyApiKey(key)
                    ?: throw ToolInputException("provider does not use an API key: $id")
                latest.copy(providers = latest.providers.map { if (it.id == target.id) updated else it })
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", id)
                put("masked", masker.masked(key))
            })))
        }
    },
)

private fun providerApiKeyClearTool(updateSettings: suspend ((Settings) -> Settings) -> Unit) = Tool(
    name = "provider_api_key_clear",
    description = "Remove the stored API key of a provider.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", buildJsonObject { put("type", "string") })
            },
            required = listOf("provider_id"),
        )
    },
    execute = { args ->
        toolResult {
            val id = args.strOrNull("provider_id") ?: throw ToolInputException("provider_id is required")
            updateSettings { latest ->
                val target = latest.providers.firstOrNull { it.id.toString() == id }
                    ?: throw ToolInputException("provider not found: $id")
                val updated = target.copyApiKey("")
                    ?: throw ToolInputException("provider does not use an API key: $id")
                latest.copy(providers = latest.providers.map { if (it.id == target.id) updated else it })
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("id", id)
            })))
        }
    },
)

// ---------------------------------------------------------------- 备份 / 远端 / 服务

private fun s3ConfigSetTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    masker: SecretMasker,
) = Tool(
    name = "s3_config_set",
    description = """
        Configure the S3 backup target. Only the fields you pass change. Secret keys are write-only and returned masked.
        endpoint must be http(s).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("endpoint", buildJsonObject { put("type", "string") })
                put("bucket", buildJsonObject { put("type", "string") })
                put("access_key_id", buildJsonObject { put("type", "string") })
                put("secret_access_key", buildJsonObject { put("type", "string") })
                put("region", buildJsonObject { put("type", "string") })
                put("path_style", buildJsonObject { put("type", "boolean") })
            }
        )
    },
    needsApproval = { args -> isRiskyEndpoint(args.strArg("endpoint")) },
    execute = { args ->
        toolResult {
            var maskedSecret: String? = null
            updateSettings { latest ->
                val current = latest.s3Config
                val secret = args.strOrNull("secret_access_key")
                if (secret != null) maskedSecret = masker.masked(secret)
                latest.copy(
                    s3Config = current.copy(
                        endpoint = args.strOrNull("endpoint")?.also(::requireHttpUrl) ?: current.endpoint,
                        bucket = args.strOrNull("bucket") ?: current.bucket,
                        accessKeyId = args.strOrNull("access_key_id") ?: current.accessKeyId,
                        secretAccessKey = secret ?: current.secretAccessKey,
                        region = args.strOrNull("region") ?: current.region,
                        pathStyle = args.boolArg("path_style") ?: current.pathStyle,
                    )
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                if (maskedSecret != null) put("masked", maskedSecret!!)
            })))
        }
    },
)

private fun webDavConfigSetTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    masker: SecretMasker,
) = Tool(
    name = "webdav_config_set",
    description = "Configure the WebDAV backup target. Only passed fields change; the password is write-only and returned masked.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject { put("type", "string") })
                put("username", buildJsonObject { put("type", "string") })
                put("password", buildJsonObject { put("type", "string") })
                put("path", buildJsonObject { put("type", "string") })
            }
        )
    },
    needsApproval = { args -> isRiskyEndpoint(args.strArg("url")) },
    execute = { args ->
        toolResult {
            var maskedPassword: String? = null
            updateSettings { latest ->
                val current = latest.webDavConfig
                val password = args.strOrNull("password")
                if (password != null) maskedPassword = masker.masked(password)
                latest.copy(
                    webDavConfig = current.copy(
                        url = args.strOrNull("url")?.also(::requireHttpUrl) ?: current.url,
                        username = args.strOrNull("username") ?: current.username,
                        password = password ?: current.password,
                        path = args.strOrNull("path") ?: current.path,
                    )
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                if (maskedPassword != null) put("masked", maskedPassword!!)
            })))
        }
    },
)

private fun webServerSecretSetTool(
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    masker: SecretMasker,
) = Tool(
    name = "web_server_secret_set",
    description = "Set the access password used by the built-in web server. The password is write-only and returned masked.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("access_password", buildJsonObject { put("type", "string") })
                put("jwt_enabled", buildJsonObject { put("type", "boolean") })
            }
        )
    },
    execute = { args ->
        toolResult {
            val password = args.strOrNull("access_password")
            val jwt = args.boolArg("jwt_enabled")
            if (password == null && jwt == null) {
                throw ToolInputException("nothing to set: pass access_password and/or jwt_enabled")
            }
            updateSettings { latest ->
                latest.copy(
                    webServerAccessPassword = password ?: latest.webServerAccessPassword,
                    webServerJwtEnabled = jwt ?: latest.webServerJwtEnabled,
                )
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                if (password != null) put("masked", masker.masked(password))
            })))
        }
    },
)

// ---------------------------------------------------------------- SSH

private fun sshWorkspaceCredentialSetTool(
    workspaces: suspend () -> List<WorkspaceEntity>,
    saveSshCredentials: suspend (String, String?, String?, String?) -> Unit,
    masker: SecretMasker,
) = Tool(
    name = "ssh_workspace_credential_set",
    description = """
        Set the login credentials of an SSH workspace. Pass password for password auth, or private_key (+ optional passphrase) for key auth.
        Credentials are encrypted on disk and never returned in plain text.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workspace_id", buildJsonObject { put("type", "string") })
                put("password", buildJsonObject { put("type", "string") })
                put("private_key", buildJsonObject {
                    put("type", "string")
                    put("description", "OpenSSH / PEM private key text")
                })
                put("passphrase", buildJsonObject { put("type", "string") })
            },
            required = listOf("workspace_id"),
        )
    },
    execute = { args ->
        toolResult {
            val workspaceId = args.strOrNull("workspace_id")
                ?: throw ToolInputException("workspace_id is required")
            val workspace = workspaces().firstOrNull { it.id == workspaceId }
                ?: throw ToolInputException("workspace not found: $workspaceId (use app_list_workspaces)")
            if (!workspace.isSsh()) throw ToolInputException("not an SSH workspace: ${workspace.name}")
            val password = args.strOrNull("password")
            val privateKey = args.strOrNull("private_key")
            val passphrase = args.strOrNull("passphrase")
            if (password == null && privateKey == null) {
                throw ToolInputException("pass password or private_key")
            }
            saveSshCredentials(workspaceId, password, privateKey, passphrase)
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                put("ok", true)
                put("workspace_id", workspaceId)
                put("masked", masker.masked(password ?: privateKey.orEmpty()))
            })))
        }
    },
)

// ---------------------------------------------------------------- 内部

private fun requireHttpUrl(value: String) {
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        throw ToolInputException("url must be an absolute http(s) URL: $value")
    }
}

/** 明文 http、回环/私网地址或非默认端口视为有风险，需要用户确认（计划的红线 2） */
internal fun isRiskyEndpoint(value: String): Boolean {
    if (value.isBlank()) return false
    val lower = value.lowercase()
    if (lower.startsWith("http://")) return true
    val host = lower.removePrefix("https://").substringBefore('/').substringBefore(':')
    return host == "localhost" ||
        host == "127.0.0.1" ||
        host == "0.0.0.0" ||
        host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        host.startsWith("169.254.") ||
        Regex("""^172\.(1[6-9]|2[0-9]|3[01])\.""").containsMatchIn(host)
}

private fun ProviderSetting.copyApiKey(key: String): ProviderSetting? = when (this) {
    is ProviderSetting.OpenAI -> copy(apiKey = key)
    is ProviderSetting.Claude -> copy(apiKey = key)
    is ProviderSetting.Google -> copy(apiKey = key)
    else -> null
}

private fun newProvider(
    type: String,
    name: String,
    baseUrl: String?,
    apiKey: String,
    enabled: Boolean,
    modelSpecs: List<ModelSpec>,
): ProviderSetting? = when (type) {
    "claude" -> ProviderSetting.Claude(
        name = name,
        apiKey = apiKey,
        enabled = enabled,
        models = modelSpecs.toModels(),
    ).let { if (baseUrl != null) it.copy(baseUrl = baseUrl) else it }

    "google" -> ProviderSetting.Google(
        name = name,
        apiKey = apiKey,
        enabled = enabled,
        models = modelSpecs.toModels(),
    ).let { if (baseUrl != null) it.copy(baseUrl = baseUrl) else it }

    "openai" -> ProviderSetting.OpenAI(
        name = name,
        apiKey = apiKey,
        enabled = enabled,
        models = modelSpecs.toModels(),
    ).let { if (baseUrl != null) it.copy(baseUrl = baseUrl) else it }

    else -> null
}

private fun ProviderSetting.edit(
    name: String?,
    baseUrl: String?,
    apiKey: String?,
    enabled: Boolean?,
    modelSpecs: List<ModelSpec>,
): ProviderSetting? {
    val renamed = copyProvider(
        name = name ?: this.name,
        enabled = enabled ?: this.enabled,
    )
    val withCredentials = when (renamed) {
        is ProviderSetting.OpenAI -> renamed.copy(
            baseUrl = baseUrl ?: renamed.baseUrl,
            apiKey = apiKey ?: renamed.apiKey,
        )

        is ProviderSetting.Claude -> renamed.copy(
            baseUrl = baseUrl ?: renamed.baseUrl,
            apiKey = apiKey ?: renamed.apiKey,
        )

        is ProviderSetting.Google -> renamed.copy(
            baseUrl = baseUrl ?: renamed.baseUrl,
            apiKey = apiKey ?: renamed.apiKey,
        )

        else -> null
    } ?: return null
    return if (modelSpecs.isEmpty()) withCredentials else withCredentials.upsertModels(modelSpecs)
}

private fun ProviderSetting.upsertModels(specs: List<ModelSpec>): ProviderSetting {
    val existing = models.associateBy { it.modelId }
    val merged = models.toMutableList()
    specs.forEach { spec ->
        val current = existing[spec.modelId]
        if (current == null) {
            merged += Model(modelId = spec.modelId, displayName = spec.displayName)
        } else {
            merged[merged.indexOf(current)] = current.copy(displayName = spec.displayName)
        }
    }
    return copyProvider(models = merged)
}

private data class ModelSpec(val modelId: String, val displayName: String)

private fun List<ModelSpec>.toModels(): List<Model> = map { spec ->
    Model(modelId = spec.modelId, displayName = spec.displayName)
}

private fun kotlinx.serialization.json.JsonElement.modelSpecs(): List<ModelSpec> {
    val array = (this as? JsonObject)?.get("models") as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["model_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        val display = (obj["display_name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        ModelSpec(id, display.ifEmpty { id })
    }
}
