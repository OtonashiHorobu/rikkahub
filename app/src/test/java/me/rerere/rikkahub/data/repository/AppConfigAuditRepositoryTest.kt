package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.AppConfigAuditDAO
import me.rerere.rikkahub.data.db.entity.AppConfigAuditEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 审计记录与回滚：按对象 diff、凭据脱敏、回滚只允许无凭据的改动。
 */
class AppConfigAuditRepositoryTest {
    private val dao = FakeAuditDao()
    private var settings = Settings(
        assistants = listOf(Assistant(name = "Agent")),
        providers = listOf(
            ProviderSetting.OpenAI(
                name = "DeepSeek",
                apiKey = "sk-should-not-be-stored-in-audit",
                models = listOf(Model(modelId = "deepseek-chat")),
            )
        ),
    )
    private val repository = AppConfigAuditRepository(dao) { block -> settings = block(settings) }

    @Test
    fun `assistant changes are recorded per object with rollback enabled`() = runBlocking {
        val before = settings
        settings = settings.copy(assistants = settings.assistants + Assistant(name = "New"))
        repository.recordDiff("assistant_create", null, null, before, settings)

        val entry = dao.rows.single()
        assertEquals(AppConfigAuditRepository.SCOPE_ASSISTANT, entry.scope)
        assertEquals("assistant_create", entry.toolName)
        assertNull(entry.beforeJson)
        assertTrue(entry.rollbackable)
        assertTrue(entry.summary.contains("New"))
    }

    @Test
    fun `provider audit snapshots are masked and not rollbackable`() = runBlocking {
        val before = settings
        val provider = settings.providers.single() as ProviderSetting.OpenAI
        settings = settings.copy(
            providers = listOf(provider.copy(apiKey = "sk-a-brand-new-key-123456"))
        )
        repository.recordDiff("provider_api_key_set", null, null, before, settings)

        val entry = dao.rows.single()
        assertEquals(AppConfigAuditRepository.SCOPE_PROVIDER, entry.scope)
        assertFalse(entry.rollbackable)
        assertFalse(entry.beforeJson.orEmpty().contains("sk-should-not-be-stored-in-audit"))
        assertFalse(entry.afterJson.orEmpty().contains("sk-a-brand-new-key-123456"))
        assertTrue(entry.beforeJson.orEmpty().contains("••••"))
    }

    @Test
    fun `scalar settings are recorded and can be rolled back`() = runBlocking {
        val before = settings
        settings = settings.copy(translateTargetLanguage = "en-US")
        repository.recordDiff("setting_set", null, null, before, settings)

        val entry = dao.rows.single()
        assertEquals(AppConfigAuditRepository.SCOPE_SETTING, entry.scope)
        assertEquals("translate_target_language", entry.targetId)
        assertTrue(entry.rollbackable)

        val result = repository.rollback(entry.id)
        assertTrue(result.isSuccess)
        assertEquals(before.translateTargetLanguage, settings.translateTargetLanguage)
        assertTrue(dao.rows.single().revertedAt != null)
    }

    @Test
    fun `assistant delete is rollbackable and restores the object`() = runBlocking {
        val target = Assistant(name = "Doomed")
        val withTarget = settings.copy(assistants = settings.assistants + target)
        settings = withTarget
        val before = settings
        settings = settings.copy(assistants = settings.assistants.filterNot { it.id == target.id })
        repository.recordDiff("assistant_delete", null, null, before, settings)

        val entry = dao.rows.single()
        assertNull(entry.afterJson)
        assertTrue(repository.rollback(entry.id).isSuccess)
        assertTrue(settings.assistants.any { it.id == target.id })
    }

    @Test
    fun `non rollbackable entries are refused instead of writing masked values`() = runBlocking {
        val before = settings
        val provider = settings.providers.single() as ProviderSetting.OpenAI
        settings = settings.copy(providers = listOf(provider.copy(apiKey = "sk-new-key-1234567890")))
        repository.recordDiff("provider_api_key_set", null, null, before, settings)

        val result = repository.rollback(dao.rows.single().id)
        assertTrue(result.isFailure)
        assertEquals("sk-new-key-1234567890", (settings.providers.single() as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `unchanged settings produce no audit rows`() = runBlocking {
        repository.recordDiff("assistant_update", null, null, settings, settings.copy())
        assertTrue(dao.rows.isEmpty())
    }

    private class FakeAuditDao : AppConfigAuditDAO {
        val rows = mutableListOf<AppConfigAuditEntity>()
        override fun listFlow(limit: Int): Flow<List<AppConfigAuditEntity>> = flowOf(rows)
        override suspend fun getById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun insert(entry: AppConfigAuditEntity) {
            rows.removeAll { it.id == entry.id }
            rows += entry
        }

        override suspend fun insertAll(entries: List<AppConfigAuditEntity>) {
            entries.forEach { insert(it) }
        }

        override suspend fun markReverted(id: String, revertedAt: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(revertedAt = revertedAt)
        }

        override suspend fun deleteOlderThan(before: Long): Int = 0
        override suspend fun clear() = rows.clear()
    }
}
