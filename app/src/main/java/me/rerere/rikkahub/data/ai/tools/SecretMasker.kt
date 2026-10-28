package me.rerere.rikkahub.data.ai.tools

/**
 * 工具输出的统一脱敏。
 *
 * 两道闸：
 * 1. **已知密钥**：把当前设置里存着的凭据字面量替换掉（模型从别处抄出来也带不走）。
 * 2. **形状识别**：`sk-…`、`Bearer …`、`AKIA…`、`ghp_…` 这类常见 token，以及
 *    `api_key=…` / `password: …` 这种键值对，即使不在设置里也按可疑处理。
 *
 * 密钥只保留前 4 后 4 位用于用户核对（`sk-••••3f2a`），短于 12 位直接整体打码。
 */
class SecretMasker(
    private val knownSecrets: () -> Collection<String> = { emptyList() },
) {
    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        knownSecrets()
            .filter { it.length >= MIN_KNOWN_SECRET_LENGTH }
            .sortedByDescending { it.length }
            .forEach { secret -> result = result.replace(secret, masked(secret)) }

        // 键值对先处理，保留键名只打码值
        result = KEY_VALUE.replace(result) { match ->
            val (key, separator, value) = match.destructured
            "$key$separator${masked(value)}"
        }
        // 再按 token 形状处理；保留类型前缀
        TOKEN_PATTERNS.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val raw = match.value
                val prefix = TOKEN_PREFIX.find(raw)?.value.orEmpty()
                prefix + masked(raw.removePrefix(prefix).trim())
            }
        }
        return result
    }

    /** 只给核对用的掩码，任何情况下都不回明文 */
    fun masked(secret: String): String {
        val value = secret.trim()
        if (value.length < 12) return "••••"
        return "${value.take(4)}••••${value.takeLast(4)}"
    }

    private companion object {
        const val MIN_KNOWN_SECRET_LENGTH = 6

        val KEY_VALUE = Regex(
            """(?i)\b(api[_-]?key|access[_-]?key|secret[_-]?key|secret[_-]?access[_-]?key|password|passwd|token)\b(\s*[:=]\s*["']?)([^\s"',}]{8,})""",
        )

        /** 保留 token 的类型前缀，只对主体打码 */
        val TOKEN_PREFIX = Regex("""^(sk-|ghp_|github_pat_|xoxb-|xoxp-|AKIA|Bearer\s+)""", RegexOption.IGNORE_CASE)

        val TOKEN_PATTERNS = listOf(
            // OpenAI / Anthropic / 常见中转的 sk- 开头 token
            Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
            // GitHub
            Regex("""ghp_[A-Za-z0-9]{20,}"""),
            Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
            // Slack
            Regex("""xox[bp]-[A-Za-z0-9-]{10,}"""),
            // AWS access key
            Regex("""AKIA[0-9A-Z]{16}"""),
            // Authorization 头
            Regex("""Bearer\s+[A-Za-z0-9._\-]{12,}""", RegexOption.IGNORE_CASE),
        )
    }
}

/** 从设置里收集当前存着的凭据字面量，供 [SecretMasker] 做精确替换 */
fun collectKnownSecrets(settings: me.rerere.rikkahub.data.datastore.Settings): List<String> = buildList {
    settings.providers.forEach { provider ->
        when (provider) {
            is me.rerere.ai.provider.ProviderSetting.OpenAI -> add(provider.apiKey)
            is me.rerere.ai.provider.ProviderSetting.Claude -> add(provider.apiKey)
            is me.rerere.ai.provider.ProviderSetting.Google -> add(provider.apiKey)
            // 账号登录类提供商存的是 OAuth 凭据，不是 apiKey；access token 由各自的 toString 脱敏
            else -> Unit
        }
        provider.customHeaders.forEach { header -> add(header.value) }
    }
    add(settings.s3Config.secretAccessKey)
    add(settings.uploadS3Config.secretAccessKey)
    add(settings.s3Config.accessKeyId)
    add(settings.webDavConfig.password)
    add(settings.webServerAccessPassword)
}.filter { it.isNotBlank() }
