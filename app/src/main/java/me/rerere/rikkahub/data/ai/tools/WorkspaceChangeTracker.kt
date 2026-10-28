package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * 记录 AI 在本次进程生命周期里写过哪些文件，供文件浏览器打「本轮改动」小圆点和「只看改动」过滤。
 *
 * 故意只在内存里：这不是审计日志（那个在 F2 的计划里），只是让用户一眼看出这轮 agent 动了什么；
 * 进程重启后清空是合理的——上一轮的改动不该继续标成"本轮"。
 */
object WorkspaceChangeTracker {
    /** 每个工作区最多记这么多条，超了丢掉最老的一半，避免长时间挂着无限长内存 */
    private const val MAX_ENTRIES_PER_WORKSPACE = 2_000

    private val changes = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    fun record(workspaceId: String, absolutePath: String) {
        val entries = changes.getOrPut(workspaceId) { ConcurrentHashMap() }
        entries[absolutePath] = System.currentTimeMillis()
        if (entries.size > MAX_ENTRIES_PER_WORKSPACE) {
            entries.entries
                .sortedBy { it.value }
                .take(entries.size - MAX_ENTRIES_PER_WORKSPACE / 2)
                .forEach { (path, at) -> entries.remove(path, at) }
        }
    }

    /** 本进程内被写过的环境内绝对路径 */
    fun changedPaths(workspaceId: String): Set<String> =
        changes[workspaceId]?.keys?.toSet().orEmpty()

    fun clear(workspaceId: String) {
        changes.remove(workspaceId)
    }
}
