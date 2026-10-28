package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 改配置的审计记录。
 *
 * 每行对应一个被改动对象（一个助手、一个 MCP server、一个设置项……），存改动前后的 JSON 快照，
 * 支持一次性回滚。**密钥类字段在写入前已脱敏**（provider 的 apiKey、s3/webdav 的密钥等），
 * 审计表里不会出现明文凭据。
 */
@Entity(
    tableName = "app_config_audit",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["conversation_id"]),
    ],
)
data class AppConfigAuditEntity(
    @PrimaryKey
    val id: String,
    /** 触发改动的会话，用户在聊天里让 Agent 改配置时有值 */
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    /** 触发改动的工具名，如 assistant_update */
    @ColumnInfo("tool_name")
    val toolName: String,
    /** 改的是什么：assistant / mcp_server / quick_message / mode_injection / provider / setting / s3 / webdav / web_server */
    @ColumnInfo("scope")
    val scope: String,
    /** 被改对象的 id；标量设置用 key 名 */
    @ColumnInfo("target_id")
    val targetId: String,
    /** 改动前的快照；null 表示新建 */
    @ColumnInfo("before_json")
    val beforeJson: String? = null,
    /** 改动后的快照；null 表示删除 */
    @ColumnInfo("after_json")
    val afterJson: String? = null,
    /** 给人看的一行摘要 */
    @ColumnInfo("summary")
    val summary: String,
    /** 是否支持一键撤销（密钥类改动不存明文，不能自动回滚） */
    @ColumnInfo("rollbackable", defaultValue = "1")
    val rollbackable: Boolean = true,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("reverted_at")
    val revertedAt: Long? = null,
)
