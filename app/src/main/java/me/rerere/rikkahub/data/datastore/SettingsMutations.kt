package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.datastore.SettingApplyResult.Applied
import me.rerere.rikkahub.data.datastore.SettingApplyResult.Rejected
import kotlin.uuid.Uuid

/** `setting_set` 工具与审计回滚共用的白名单设置项 */
val WRITABLE_SETTING_KEYS = listOf(
    "dynamic_color",
    "developer_mode",
    "enable_auto_title",
    "enable_suggestion",
    "chat_model_id",
    "fast_model_id",
    "translate_target_language",
    "web_server_enabled",
    "web_server_port",
    "web_server_localhost_only",
)

sealed interface SettingApplyResult {
    data class Applied(val settings: Settings, val normalized: String) : SettingApplyResult
    data class Rejected(val reason: String) : SettingApplyResult
}

/**
 * 按白名单写一个全局设置。
 *
 * 工具层把 [Rejected] 转成给模型看的错误，回滚路径把同一个 key/值写回去，
 * 两边用同一套校验，避免「工具能写、回滚写不进去」。
 */
fun Settings.applyWritableSetting(key: String, raw: String): SettingApplyResult = when (key) {
    "dynamic_color" -> applyBool(key, raw) { copy(dynamicColor = it) }
    "developer_mode" -> applyBool(key, raw) { copy(developerMode = it) }
    "enable_auto_title" -> applyBool(key, raw) { copy(enableAutoTitle = it) }
    "enable_suggestion" -> applyBool(key, raw) { copy(enableSuggestion = it) }
    "web_server_enabled" -> applyBool(key, raw) { copy(webServerEnabled = it) }
    "web_server_localhost_only" -> applyBool(key, raw) { copy(webServerLocalhostOnly = it) }
    "chat_model_id" -> applyModelId(key, raw) { copy(chatModelId = it) }
    "fast_model_id" -> applyModelId(key, raw) { copy(fastModelId = it) }
    "translate_target_language" -> {
        if (raw.length > 20) {
            Rejected("language tag looks invalid: $raw")
        } else {
            Applied(copy(translateTargetLanguage = raw), raw)
        }
    }

    "web_server_port" -> {
        val port = raw.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: return Rejected("web_server_port must be 1024..65535")
        Applied(copy(webServerPort = port), port.toString())
    }

    else -> Rejected("setting is not writable: $key (allowed: ${WRITABLE_SETTING_KEYS.joinToString(", ")})")
}

private fun Settings.applyBool(
    key: String,
    raw: String,
    apply: Settings.(Boolean) -> Settings,
): SettingApplyResult {
    val value = raw.toBooleanStrictOrNull() ?: return Rejected("$key expects true or false")
    return Applied(apply(value), value.toString())
}

private fun Settings.applyModelId(
    key: String,
    raw: String,
    apply: Settings.(Uuid) -> Settings,
): SettingApplyResult {
    val id = runCatching { Uuid.parse(raw) }.getOrNull()
        ?: return Rejected("invalid model id: $raw")
    if (findModelById(id) == null) {
        return Rejected("model not found: $raw (use app_list_models)")
    }
    return Applied(apply(id), raw)
}
