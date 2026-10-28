package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid

/**
 * 把模型切换后的工作目录写回会话。
 *
 * 工具拿不到 ChatService（Koin 循环依赖），由 [me.rerere.rikkahub.service.ChatService] 实现。
 */
fun interface ConversationCwdWriter {
    suspend fun setCwd(conversationId: Uuid, cwd: String?)
}
