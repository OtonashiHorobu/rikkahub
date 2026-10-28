package me.rerere.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Agent 能对设备和应用做多少事。三档，会话上的值覆盖助手上的值。
 *
 * 只裁剪工具的注册，不改变已注册工具的审批行为：审批仍由 [me.rerere.ai.core.Tool.needsApproval]
 * 和路径越界判断决定，两者是独立的闸。
 */
@Serializable(with = AgentPermissionLevelSerializer::class)
enum class AgentPermissionLevel {
    /** 只读观察：读文件、列目录、搜文件、看应用状态 */
    READ_ONLY,

    /** 工作区写：改工作区文件、执行 shell、切目录、改应用配置 */
    WORKSPACE_WRITE,

    /** 完全放行：再加设备能力（通知、应用管理、安装、联系人、定位、媒体、Shizuku）与凭据写入 */
    FULL_ACCESS,
    ;

    /** 本级别是否覆盖 [required] 所需的权限 */
    fun grants(required: AgentPermissionLevel): Boolean = ordinal >= required.ordinal

    /**
     * 落盘用的值。
     *
     * 这是设置和会话 JSON 里的实际写法，直接改名字会让旧备份读不出来；兼容旧值走
     * [parseOrWorkspaceWrite] 的别名分支。
     */
    internal val wireName: String
        get() = when (this) {
            READ_ONLY -> "read_only"
            WORKSPACE_WRITE -> "workspace_write"
            FULL_ACCESS -> "full_access"
        }

    companion object {
        /**
         * 反序列化兜底：未知值（旧版本写入的 `config`/`secrets`/`device`、或手工改坏的备份）
         * 按 [WORKSPACE_WRITE] 处理，和引入权限等级之前的能力一致，不会让用户升级后丢工具。
         */
        fun parseOrWorkspaceWrite(raw: String?): AgentPermissionLevel = when (raw) {
            "read_only" -> READ_ONLY
            "workspace_write" -> WORKSPACE_WRITE
            "full_access" -> FULL_ACCESS
            // 旧的四级：只读保持只读，其余（含 config/secrets/device）都算有写权限
            "config" -> WORKSPACE_WRITE
            "secrets", "device" -> FULL_ACCESS
            else -> WORKSPACE_WRITE
        }
    }
}

/**
 * 写的时候只有一种写法，读的时候尽量认。
 *
 * 设置整份 JSON 解码失败时 [me.rerere.rikkahub.data.datastore.PreferencesStore] 会直接结束进程，
 * 所以这里不能把「旧版本写下的未知等级」抛出去：认不出的字符串落到
 * [AgentPermissionLevel.parseOrWorkspaceWrite] 的兼容分支，而不是让应用打不开。
 */
internal object AgentPermissionLevelSerializer : KSerializer<AgentPermissionLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("me.rerere.rikkahub.data.model.AgentPermissionLevel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentPermissionLevel) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): AgentPermissionLevel {
        val raw = if (decoder is JsonDecoder) {
            (decoder.decodeJsonElement() as? JsonPrimitive)?.contentOrNull
        } else {
            decoder.decodeString()
        }
        return AgentPermissionLevel.parseOrWorkspaceWrite(raw)
    }
}
