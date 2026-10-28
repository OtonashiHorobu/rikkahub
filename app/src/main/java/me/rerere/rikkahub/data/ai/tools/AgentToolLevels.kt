package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.model.AgentPermissionLevel

/**
 * 工具名 → 需要的权限等级。
 *
 * 放在工具定义外面而不是塞进 [me.rerere.ai.core.Tool]，因为 `ai` 模块是纯 Kotlin 库、
 * 被 search / speech 等复用，加字段会波及它们；这里一张常量表就够了。
 *
 * 表里没有的工具按 [DEFAULT_TOOL_LEVEL] 处理：MCP 工具、记忆工具、本地工具、搜索工具
 * 都在用户显式打开对应开关之后才存在，不需要再用等级二次把关。
 */
object AgentToolLevels {
    /**
     * 未登记工具的默认等级。
     *
     * 用 [AgentPermissionLevel.READ_ONLY] 而不是 WORKSPACE_WRITE：默认等级决定的是"没有明确声明的工具
     * 在最低权限下是否可用"，宽松默认会让以后新增的工具静默越过权限门槛。
     */
    val DEFAULT_TOOL_LEVEL: AgentPermissionLevel = AgentPermissionLevel.READ_ONLY

    private val levels: Map<String, AgentPermissionLevel> = mapOf(
        // 只读
        "workspace_read_file" to AgentPermissionLevel.READ_ONLY,
        "workspace_list" to AgentPermissionLevel.READ_ONLY,
        "workspace_glob" to AgentPermissionLevel.READ_ONLY,
        "workspace_grep" to AgentPermissionLevel.READ_ONLY,
        "app_state" to AgentPermissionLevel.READ_ONLY,
        "app_list_assistants" to AgentPermissionLevel.READ_ONLY,
        "app_list_providers" to AgentPermissionLevel.READ_ONLY,
        "app_list_models" to AgentPermissionLevel.READ_ONLY,
        "app_list_workspaces" to AgentPermissionLevel.READ_ONLY,
        "app_list_skills" to AgentPermissionLevel.READ_ONLY,
        "app_open" to AgentPermissionLevel.READ_ONLY,
        "workspace_read_apk" to AgentPermissionLevel.READ_ONLY,
        // 工作区写 + 改应用配置
        "workspace_write_file" to AgentPermissionLevel.WORKSPACE_WRITE,
        "workspace_edit_file" to AgentPermissionLevel.WORKSPACE_WRITE,
        "workspace_shell" to AgentPermissionLevel.WORKSPACE_WRITE,
        "workspace_cd" to AgentPermissionLevel.WORKSPACE_WRITE,
        "assistant_create" to AgentPermissionLevel.WORKSPACE_WRITE,
        "assistant_update" to AgentPermissionLevel.WORKSPACE_WRITE,
        "assistant_delete" to AgentPermissionLevel.WORKSPACE_WRITE,
        "skill_create" to AgentPermissionLevel.WORKSPACE_WRITE,
        "skill_delete" to AgentPermissionLevel.WORKSPACE_WRITE,
        "setting_set" to AgentPermissionLevel.WORKSPACE_WRITE,
        "mcp_server_upsert" to AgentPermissionLevel.WORKSPACE_WRITE,
        "mcp_server_remove" to AgentPermissionLevel.WORKSPACE_WRITE,
        "quick_message_set" to AgentPermissionLevel.WORKSPACE_WRITE,
        "mode_injection_set" to AgentPermissionLevel.WORKSPACE_WRITE,
        // 设备与凭据，只有完全放行才给
        "workspace_install_apk" to AgentPermissionLevel.FULL_ACCESS,
        "provider_upsert" to AgentPermissionLevel.FULL_ACCESS,
        "provider_api_key_set" to AgentPermissionLevel.FULL_ACCESS,
        "provider_api_key_clear" to AgentPermissionLevel.FULL_ACCESS,
        "s3_config_set" to AgentPermissionLevel.FULL_ACCESS,
        "webdav_config_set" to AgentPermissionLevel.FULL_ACCESS,
        "ssh_workspace_credential_set" to AgentPermissionLevel.FULL_ACCESS,
        "web_server_secret_set" to AgentPermissionLevel.FULL_ACCESS,
    )

    fun requiredLevel(toolName: String): AgentPermissionLevel = levels[toolName] ?: DEFAULT_TOOL_LEVEL

    /** 某个等级下可用的工具数，用于权限选择器的说明文案 */
    fun countToolsAt(level: AgentPermissionLevel, toolNames: Collection<String>): Int =
        toolNames.count { level.grants(requiredLevel(it)) }
}
