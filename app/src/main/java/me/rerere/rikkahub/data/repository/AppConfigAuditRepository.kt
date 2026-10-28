package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.applyWritableSetting
import me.rerere.rikkahub.data.db.dao.AppConfigAuditDAO
import me.rerere.rikkahub.data.db.entity.AppConfigAuditEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * Agent 配置改动的审计与回滚。
 *
 * 记录方式是「按对象 diff」：一次工具调用前后各拿一份 [Settings]，把变化的助手 / MCP server /
 * 快捷消息 / 模式注入 / 提供商 / 备份配置 / 标量设置各写一行。凭据字段在落库前脱敏，
 * 因此**含密钥的改动不提供自动回滚**（`rollbackable = false`），避免把 `••••` 写回配置。
 */
class AppConfigAuditRepository(
    private val dao: AppConfigAuditDAO,
    /** 生产环境是 `SettingsStore.update`；抽成 lambda 让回滚逻辑可以脱离 Android 单测 */
    private val applySettings: suspend ((Settings) -> Settings) -> Unit,
) {
    fun listFlow(limit: Int = 200) = dao.listFlow(limit)

    suspend fun clear() = dao.clear()

    /** 对比一次写操作前后的设置，把每个变化的对象记成一行 */
    suspend fun recordDiff(
        toolName: String,
        conversationId: Uuid?,
        assistantId: Uuid?,
        before: Settings,
        after: Settings,
        now: Long = System.currentTimeMillis(),
    ) {
        val drafts = buildList {
            diffById(
                scope = SCOPE_ASSISTANT,
                beforeItems = before.assistants,
                afterItems = after.assistants,
                id = { it.id.toString() },
                encode = { JsonInstant.encodeToString(it.redacted()) },
                rollbackable = { it.customHeaders.isEmpty() },
                summary = { item -> "助手「${item?.name.orEmpty()}」" },
            ).forEach(::add)

            diffById(
                scope = SCOPE_MCP,
                beforeItems = before.mcpServers,
                afterItems = after.mcpServers,
                id = { it.id.toString() },
                encode = { JsonInstant.encodeToString(it.redacted()) },
                // 自定义 header 可能带 token，回滚会把掩码写回去
                rollbackable = { false },
                summary = { item -> "MCP server「${item?.commonOptions?.name.orEmpty()}」" },
            ).forEach(::add)

            diffById(
                scope = SCOPE_QUICK_MESSAGE,
                beforeItems = before.quickMessages,
                afterItems = after.quickMessages,
                id = { it.id.toString() },
                encode = { JsonInstant.encodeToString(it) },
                rollbackable = { true },
                summary = { item -> "快捷消息「${item?.title.orEmpty()}」" },
            ).forEach(::add)

            diffById(
                scope = SCOPE_MODE_INJECTION,
                beforeItems = before.modeInjections,
                afterItems = after.modeInjections,
                id = { it.id.toString() },
                encode = { JsonInstant.encodeToString(it) },
                rollbackable = { true },
                summary = { item -> "模式注入「${item?.name.orEmpty()}」" },
            ).forEach(::add)

            diffById(
                scope = SCOPE_PROVIDER,
                beforeItems = before.providers,
                afterItems = after.providers,
                id = { it.id.toString() },
                encode = { JsonInstant.encodeToString(it.redacted()) },
                rollbackable = { false },
                summary = { item -> "提供商「${item?.name.orEmpty()}」" },
            ).forEach(::add)

            diffJsonSetting(
                scope = SCOPE_S3,
                beforeJson = JsonInstant.encodeToString(before.s3Config.redacted()),
                afterJson = JsonInstant.encodeToString(after.s3Config.redacted()),
                summary = "S3 备份配置",
            )?.let(::add)

            diffJsonSetting(
                scope = SCOPE_WEBDAV,
                beforeJson = JsonInstant.encodeToString(before.webDavConfig.redacted()),
                afterJson = JsonInstant.encodeToString(after.webDavConfig.redacted()),
                summary = "WebDAV 备份配置",
            )?.let(::add)

            if (before.webServerAccessPassword != after.webServerAccessPassword ||
                before.webServerJwtEnabled != after.webServerJwtEnabled ||
                before.webServerEnabled != after.webServerEnabled
            ) {
                add(newDraft(scope = SCOPE_WEB_SERVER, targetId = SCOPE_WEB_SERVER, summary = "Web 服务访问控制"))
            }

            scalarSettingChanges(before, after).forEach { (key, old, new) ->
                add(
                    newDraft(
                        scope = SCOPE_SETTING,
                        targetId = key,
                        beforeJson = old?.toString(),
                        afterJson = new?.toString(),
                        summary = "设置「$key」",
                        rollbackable = true,
                    )
                )
            }
        }
        if (drafts.isEmpty()) return
        dao.insertAll(
            drafts.map {
                it.copy(
                    toolName = toolName,
                    conversationId = conversationId?.toString(),
                    assistantId = assistantId?.toString(),
                    createdAt = now,
                )
            }
        )
    }

    /** 撤销一行：把对象恢复成 before 快照。含凭据的记录直接拒绝，不写半截数据。 */
    suspend fun rollback(id: String): Result<Unit> {
        val entry = dao.getById(id) ?: return Result.failure(IllegalStateException("记录不存在"))
        if (!entry.rollbackable) {
            return Result.failure(IllegalStateException("这条记录含凭据或被删除的对象无法自动撤销"))
        }
        return runCatching {
            applySettings { settings -> applySnapshot(settings, entry) }
            dao.markReverted(id, System.currentTimeMillis())
        }
    }

    private fun applySnapshot(settings: Settings, entry: AppConfigAuditEntity): Settings =
        when (entry.scope) {
            SCOPE_ASSISTANT -> settings.copy(
                assistants = restoreList(
                    current = settings.assistants,
                    idOf = { it.id.toString() },
                    id = entry.targetId,
                    beforeJson = entry.beforeJson,
                ) { JsonInstant.decodeFromString<Assistant>(it) }
            )

            SCOPE_QUICK_MESSAGE -> settings.copy(
                quickMessages = restoreList(
                    current = settings.quickMessages,
                    idOf = { it.id.toString() },
                    id = entry.targetId,
                    beforeJson = entry.beforeJson,
                ) { JsonInstant.decodeFromString<QuickMessage>(it) }
            )

            SCOPE_MODE_INJECTION -> settings.copy(
                modeInjections = restoreList(
                    current = settings.modeInjections,
                    idOf = { it.id.toString() },
                    id = entry.targetId,
                    beforeJson = entry.beforeJson,
                ) { JsonInstant.decodeFromString<PromptInjection.ModeInjection>(it) }
            )

            SCOPE_SETTING -> {
                val raw = entry.beforeJson?.let { JsonInstant.parseToJsonElement(it) }
                    ?.let { (it as? JsonPrimitive)?.content }
                    ?: throw IllegalStateException("记录缺少旧值")
                when (val result = settings.applyWritableSetting(entry.targetId, raw)) {
                    is me.rerere.rikkahub.data.datastore.SettingApplyResult.Applied -> result.settings
                    is me.rerere.rikkahub.data.datastore.SettingApplyResult.Rejected ->
                        throw IllegalStateException(result.reason)
                }
            }

            else -> throw IllegalStateException("不支持撤销的类型: ${entry.scope}")
        }

    private fun <T> restoreList(
        current: List<T>,
        idOf: (T) -> String,
        id: String,
        beforeJson: String?,
        decode: (String) -> T,
    ): List<T> {
        val restored = beforeJson?.let(decode)
        val exists = current.any { idOf(it) == id }
        return when {
            restored == null -> current.filterNot { idOf(it) == id }
            exists -> current.map { if (idOf(it) == id) restored else it }
            else -> current + restored
        }
    }

    private fun <T> diffById(
        scope: String,
        beforeItems: List<T>,
        afterItems: List<T>,
        id: (T) -> String,
        encode: (T) -> String,
        rollbackable: (T) -> Boolean,
        summary: (T?) -> String,
    ): List<AppConfigAuditEntity> {
        val beforeById = beforeItems.associateBy(id)
        val afterById = afterItems.associateBy(id)
        return (beforeById.keys + afterById.keys).mapNotNull { key ->
            val beforeItem = beforeById[key]
            val afterItem = afterById[key]
            val before = beforeItem?.let(encode)
            val after = afterItem?.let(encode)
            if (before == after) return@mapNotNull null
            newDraft(
                scope = scope,
                targetId = key,
                beforeJson = before,
                afterJson = after,
                summary = summary(afterItem ?: beforeItem),
                rollbackable = (afterItem ?: beforeItem)?.let(rollbackable) ?: false,
            )
        }
    }

    private fun diffJsonSetting(
        scope: String,
        beforeJson: String,
        afterJson: String,
        summary: String,
    ): AppConfigAuditEntity? {
        if (beforeJson == afterJson) return null
        return newDraft(
            scope = scope,
            targetId = scope,
            beforeJson = beforeJson,
            afterJson = afterJson,
            summary = summary,
            rollbackable = false,
        )
    }

    private fun newDraft(
        scope: String,
        targetId: String,
        beforeJson: String? = null,
        afterJson: String? = null,
        summary: String,
        rollbackable: Boolean = false,
    ) = AppConfigAuditEntity(
        id = Uuid.random().toString(),
        toolName = "",
        scope = scope,
        targetId = targetId,
        beforeJson = beforeJson,
        afterJson = afterJson,
        summary = summary,
        rollbackable = rollbackable,
        createdAt = 0,
    )

    /** 标量设置的前后值：只比对白名单里的键 */
    private fun scalarSettingChanges(
        before: Settings,
        after: Settings,
    ): List<Triple<String, JsonElement?, JsonElement?>> {
        val changes = mutableListOf<Triple<String, JsonElement?, JsonElement?>>()
        fun check(key: String, old: Any?, new: Any?) {
            if (old != new) {
                changes += Triple(
                    key,
                    old?.let { JsonPrimitive(it.toString()) },
                    new?.let { JsonPrimitive(it.toString()) },
                )
            }
        }
        check("dynamic_color", before.dynamicColor, after.dynamicColor)
        check("developer_mode", before.developerMode, after.developerMode)
        check("enable_auto_title", before.enableAutoTitle, after.enableAutoTitle)
        check("enable_suggestion", before.enableSuggestion, after.enableSuggestion)
        check("chat_model_id", before.chatModelId, after.chatModelId)
        check("fast_model_id", before.fastModelId, after.fastModelId)
        check("translate_target_language", before.translateTargetLanguage, after.translateTargetLanguage)
        check("web_server_enabled", before.webServerEnabled, after.webServerEnabled)
        check("web_server_port", before.webServerPort, after.webServerPort)
        check("web_server_localhost_only", before.webServerLocalhostOnly, after.webServerLocalhostOnly)
        return changes
    }

    companion object {
        const val SCOPE_ASSISTANT = "assistant"
        const val SCOPE_MCP = "mcp_server"
        const val SCOPE_QUICK_MESSAGE = "quick_message"
        const val SCOPE_MODE_INJECTION = "mode_injection"
        const val SCOPE_PROVIDER = "provider"
        const val SCOPE_SETTING = "setting"
        const val SCOPE_S3 = "s3"
        const val SCOPE_WEBDAV = "webdav"
        const val SCOPE_WEB_SERVER = "web_server"
    }
}

/** 审计快照里去掉凭据字段，和 SecretMasker 的掩码规则保持一致 */
private fun Assistant.redacted(): Assistant = copy(
    customHeaders = customHeaders.map { CustomHeader(it.name, MASKED) },
)

private fun McpServerConfig.redacted(): McpServerConfig = clone(
    commonOptions = commonOptions.redacted(),
)

private fun McpCommonOptions.redacted(): McpCommonOptions =
    if (headers.isEmpty()) this else copy(headers = headers.map { (name, _) -> name to MASKED })

private fun ProviderSetting.redacted(): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(
        apiKey = apiKey.maskedForAudit(),
        customHeaders = customHeaders.map { CustomHeader(it.name, MASKED) },
    )

    is ProviderSetting.Claude -> copy(
        apiKey = apiKey.maskedForAudit(),
        customHeaders = customHeaders.map { CustomHeader(it.name, MASKED) },
    )

    is ProviderSetting.Google -> copy(
        apiKey = apiKey.maskedForAudit(),
        customHeaders = customHeaders.map { CustomHeader(it.name, MASKED) },
    )

    else -> this
}

private fun S3Config.redacted(): S3Config = copy(
    accessKeyId = accessKeyId.maskedForAudit(),
    secretAccessKey = secretAccessKey.maskedForAudit(),
)

private fun WebDavConfig.redacted(): WebDavConfig = copy(
    password = password.maskedForAudit(),
)

private fun String.maskedForAudit(): String =
    if (isEmpty()) {
        ""
    } else if (length < 12) {
        MASKED
    } else {
        "${take(4)}$MASKED${takeLast(4)}"
    }

private const val MASKED = "••••"
