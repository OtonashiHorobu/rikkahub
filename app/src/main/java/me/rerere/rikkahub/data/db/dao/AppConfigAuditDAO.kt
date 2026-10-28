package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AppConfigAuditEntity

@Dao
interface AppConfigAuditDAO {
    @Query("SELECT * FROM app_config_audit ORDER BY created_at DESC LIMIT :limit")
    fun listFlow(limit: Int = 200): Flow<List<AppConfigAuditEntity>>

    @Query("SELECT * FROM app_config_audit WHERE id = :id")
    suspend fun getById(id: String): AppConfigAuditEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AppConfigAuditEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<AppConfigAuditEntity>)

    @Query("UPDATE app_config_audit SET reverted_at = :revertedAt WHERE id = :id")
    suspend fun markReverted(id: String, revertedAt: Long)

    @Query("DELETE FROM app_config_audit WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM app_config_audit")
    suspend fun clear()
}
