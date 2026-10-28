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
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * F2 SECRETS 档：写入生效、返回值只有掩码、危险端点触发审批。
 */
class AppSecretToolsTest {
    private val existing = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "DeepSeek",
        apiKey = "sk-old-key-1234567890",
        models = listOf(Model(modelId = "deepseek-chat", displayName = "DeepSeek Chat")),
    )
    private var settings = Settings(providers = listOf(existing))
    private val masker = SecretMasker { emptyList() }

    private var sshCredentials: Triple<String?, String?, String?>? = null

    private var workspaces = emptyList<me.rerere.rikkahub.data.db.entity.WorkspaceEntity>()

    private fun tools(): List<Tool> = createAppSecretTools(
        updateSettings = { block -> settings = block(settings) },
        masker = masker,
        workspaces = { workspaces },
        saveSshCredentials = { id, password, privateKey, passphrase ->
            sshCredentials = Triple("$id/$password", privateKey, passphrase)
        },
    )

    private fun Tool.call(vararg args: Pair<String, Any>): JsonObject = runBlocking {
        val input = JsonObject(args.toMap().mapValues { (_, value) ->
            when (value) {
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        })
        val text = execute(input).filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        JsonInstant.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `api key set stores the key and only returns a mask`() {
        val result = tools().first { it.name == "provider_api_key_set" }.call(
            "provider_id" to existing.id.toString(),
            "api_key" to "sk-brand-new-secret-abcdef123456",
        )
        assertEquals("sk-b••••3456", result["masked"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("brand-new-secret"))
        val stored = (settings.providers.single() as ProviderSetting.OpenAI).apiKey
        assertEquals("sk-brand-new-secret-abcdef123456", stored)
    }

    @Test
    fun `api key clear empties the stored key`() {
        tools().first { it.name == "provider_api_key_clear" }
            .call("provider_id" to existing.id.toString())
        assertEquals("", (settings.providers.single() as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `provider upsert creates a provider with models`() {
        val result = tools().first { it.name == "provider_upsert" }.call(
            "type" to "openai",
            "name" to "Relay",
            "base_url" to "https://relay.example.com/v1",
            "api_key" to "sk-relay-key-000000000000",
            "models" to "",
        )
        assertEquals(true, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, settings.providers.size)
        val created = settings.providers.last() as ProviderSetting.OpenAI
        assertEquals("Relay", created.name)
        assertEquals("https://relay.example.com/v1", created.baseUrl)
        assertEquals("sk-relay-key-000000000000", created.apiKey)
        // 返回值里只有掩码
        assertEquals("sk-r••••0000", result["masked"]!!.jsonPrimitive.content)
    }

    @Test
    fun `risky endpoints require approval`() {
        val upsert = tools().first { it.name == "provider_upsert" }
        fun approval(url: String) = upsert.needsApproval(
            JsonObject(mapOf("base_url" to JsonPrimitive(url)))
        )
        assertTrue(approval("http://relay.example.com/v1"))
        assertTrue(approval("https://192.168.1.10/v1"))
        assertTrue(approval("https://127.0.0.1:8443/v1"))
        assertFalse(approval("https://api.deepseek.com/v1"))
        assertFalse(approval(""))
    }

    @Test
    fun `s3 and web server secrets are masked in the response`() {
        val s3 = tools().first { it.name == "s3_config_set" }.call(
            "endpoint" to "https://s3.example.com",
            "bucket" to "backups",
            "access_key_id" to "AKIAEXAMPLE000000",
            "secret_access_key" to "very-secret-access-key-value",
        )
        assertEquals("very••••alue", s3["masked"]!!.jsonPrimitive.content)
        assertEquals("backups", settings.s3Config.bucket)
        assertFalse(s3.toString().contains("very-secret-access-key-value"))

        val web = tools().first { it.name == "web_server_secret_set" }
            .call("access_password" to "super-secret-password", "jwt_enabled" to true)
        assertEquals("supe••••word", web["masked"]!!.jsonPrimitive.content)
        assertTrue(settings.webServerJwtEnabled)
        assertEquals("super-secret-password", settings.webServerAccessPassword)
    }

    @Test
    fun `ssh credentials are validated and forwarded to the repository hook`() {
        val missing = tools().first { it.name == "ssh_workspace_credential_set" }.call(
            "workspace_id" to "ws-1",
            "password" to "ssh-password-123456",
        )
        assertTrue(missing.toString().contains("workspace not found"))

        workspaces = listOf(
            me.rerere.rikkahub.data.db.entity.WorkspaceEntity(
                id = "ws-1",
                name = "remote",
                root = "ws-1",
                createdAt = 0,
                updatedAt = 0,
                type = me.rerere.workspace.WorkspaceType.SSH.name,
            )
        )
        val result = tools().first { it.name == "ssh_workspace_credential_set" }.call(
            "workspace_id" to "ws-1",
            "password" to "ssh-password-123456",
        )
        assertEquals(true, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("ws-1/ssh-password-123456", sshCredentials?.first)
        assertEquals("ssh-••••3456", result["masked"]!!.jsonPrimitive.content)
    }
}
