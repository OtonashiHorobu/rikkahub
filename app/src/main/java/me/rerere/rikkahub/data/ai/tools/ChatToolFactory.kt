package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.AgentPermissionLevel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.AppConfigAuditRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val auditRepository: AppConfigAuditRepository,
    /** 延迟解析，ChatService 依赖本类，直接构造会成环 */
    private val lazyCwdWriter: Lazy<ConversationCwdWriter>? = null,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
        workspaceCwdLocked: Boolean = false,
        permissionLevel: AgentPermissionLevel = assistant.permissionLevel,
    ): List<Tool> {
        val workspaceTools = createWorkspaceToolsIfReady(
            workspaceId = assistant.workspaceId?.toString(),
            conversationId = conversationId,
            cwd = workspaceCwd,
            cwdLocked = workspaceCwdLocked,
        )
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            // F2 只读层：让模型知道应用里有什么，并能把界面推到对应页面
            addAll(
                createAppReadOnlyTools(
                    settings = settings,
                    assistant = assistant,
                    conversationId = conversationId,
                    workspace = { workspaceRepository.listFlow().first() },
                    skills = { skillManager.listSkills() },
                    appEventBus = appEventBus,
                )
            )
            // F2 CONFIG 档：写助手/技能/设置/MCP/快捷消息/模式注入（不碰凭据）
            addAll(
                createAppConfigTools(
                    updateSettings = { block -> settingsStore.update(block) },
                    saveSkill = skillManager::saveSkill,
                    deleteSkill = skillManager::deleteSkill,
                    currentAssistantId = assistant.id,
                    workspaces = { workspaceRepository.listFlow().first() },
                    skills = { skillManager.listSkills() },
                ).map { tool -> withConfigAudit(tool, conversationId, assistant.id) }
            )
            // F2 SECRETS 档：凭据只写不读，返回值固定 { ok, masked }
            addAll(
                createAppSecretTools(
                    updateSettings = { block -> settingsStore.update(block) },
                    masker = SecretMasker { collectKnownSecrets(settings) },
                    workspaces = { workspaceRepository.listFlow().first() },
                    saveSshCredentials = { id, password, privateKey, passphrase ->
                        workspaceRepository.updateSshCredentials(id, password, privateKey, passphrase)
                    },
                ).map { tool -> withConfigAudit(tool, conversationId, assistant.id) }
            )
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 文件放在本机, 只有本地 Rootfs 挂载了 /tool_outputs, 远程工作区同样读不到
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        val masker = SecretMasker { collectKnownSecrets(settings) }
        val masked = tools.map { tool -> withSecretMasking(tool, masker) }
        val truncatable = if (canReadToolOutputs) masked.map(::withOutputTruncation) else masked
        // 权限等级只裁剪"注册给模型的工具"，不改变审批；未被登记的工具按 READ_ONLY 处理
        return truncatable.filter { permissionLevel.grants(AgentToolLevels.requiredLevel(it.name)) }
    }

    /** 配置类工具执行前后各取一份设置做 diff，命中变化的对象就落一行审计，失败不影响工具结果 */
    private fun withConfigAudit(tool: Tool, conversationId: Uuid?, assistantId: Uuid): Tool {
        val execute = tool.execute
        return tool.copy(execute = { args ->
            val before = settingsStore.settingsFlow.value
            val result = execute(args)
            val after = settingsStore.settingsFlow.value
            if (before != after) {
                runCatching { auditRepository.recordDiff(tool.name, conversationId, assistantId, before, after) }
                    .onFailure { Log.w(TAG, "recordDiff failed for ${tool.name}", it) }
            }
            result
        })
    }

    /** 工具回给模型的文本先过一遍脱敏，再考虑截断，避免把密钥写进 tool_outputs 文件 */
    private fun withSecretMasking(tool: Tool, masker: SecretMasker): Tool {
        val execute = tool.execute
        return tool.copy(execute = { args ->
            execute(args).map { part ->
                if (part is UIMessagePart.Text) part.copy(text = masker.mask(part.text)) else part
            }
        })
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        conversationId: Uuid?,
        cwd: String?,
        cwdLocked: Boolean,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
        // shell 没就绪也给文件工具和 cd：本地工作区可能只装了文件区，全灭会让模型彻底没有手脚
        val shellReady = workspace.shellStatus == WorkspaceShellStatus.READY.name
        if (!shellReady) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: shell not ready, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
        }
        return createWorkspaceTools(
            context = WorkspaceToolContext(
                workspaceId = workspaceId,
                conversationId = conversationId,
                cwd = cwd,
                cwdLocked = cwdLocked,
                cwdWriter = lazyCwdWriter?.value,
            ),
            workspaceRepository = workspaceRepository,
            layout = layout,
            capabilities = workspace.capabilities(),
            toolApprovalOverrides = workspace.toolApprovalOverrides(),
            includeShell = shellReady,
        )
    }
}
