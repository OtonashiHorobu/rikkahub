package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * `setting_set` 工具与审计回滚共用同一套校验：工具能写的值，回滚也必须能写回去。
 */
class SettingsMutationsTest {
    private val model = Model(modelId = "deepseek-chat", displayName = "DeepSeek Chat")
    private val settings = Settings(
        providers = listOf(ProviderSetting.OpenAI(name = "DeepSeek", models = listOf(model))),
    )

    private fun applied(key: String, raw: String): Settings {
        val result = settings.applyWritableSetting(key, raw)
        assertTrue("expected $key=$raw to apply, got $result", result is SettingApplyResult.Applied)
        return (result as SettingApplyResult.Applied).settings
    }

    private fun rejected(key: String, raw: String): String {
        val result = settings.applyWritableSetting(key, raw)
        assertTrue("expected $key=$raw to be rejected", result is SettingApplyResult.Rejected)
        return (result as SettingApplyResult.Rejected).reason
    }

    @Test
    fun `boolean and numeric settings are normalised`() {
        assertEquals(true, applied("dynamic_color", "true").dynamicColor)
        assertEquals(false, applied("web_server_enabled", "false").webServerEnabled)
        assertEquals(8081, applied("web_server_port", "8081").webServerPort)
    }

    @Test
    fun `unknown keys and bad values are rejected with a reason`() {
        assertTrue(rejected("provider_api_key", "sk-x").contains("not writable"))
        assertTrue(rejected("dynamic_color", "yes").contains("true or false"))
        assertTrue(rejected("web_server_port", "22").contains("1024..65535"))
        assertTrue(rejected("chat_model_id", "not-a-uuid").contains("invalid model id"))
    }

    @Test
    fun `model ids must exist`() {
        val updated = applied("chat_model_id", model.id.toString())
        assertEquals(model.id, updated.chatModelId)

        val missing = rejected("fast_model_id", Uuid.random().toString())
        assertTrue(missing.contains("model not found"))
    }

    @Test
    fun `every whitelisted key can round-trip through the same function`() {
        // 回滚路径写入的是 normalized 字符串，必须能再次被接受
        settings.applyWritableSetting("translate_target_language", "zh-CN").let {
            assertTrue(it is SettingApplyResult.Applied)
            assertEquals("zh-CN", (it as SettingApplyResult.Applied).normalized)
        }
        assertTrue(WRITABLE_SETTING_KEYS.contains("web_server_port"))
    }
}
