package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import kotlin.uuid.Uuid

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/** 一次 read_file 默认最多返回的行数 */
private const val READ_DEFAULT_LINES = 2000
private const val LIST_DEFAULT_LIMIT = 200
private const val GLOB_DEFAULT_LIMIT = 100
private const val GREP_DEFAULT_LIMIT = 120
private const val TOOL_LIMIT_MAX = 1000

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_cd" to false,
)

/** 直接写文件的工具, 只在支持 [WorkspaceCapabilities.fileWriteTools] 的工作区提供 */
val WorkspaceFileWriteToolNames: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

/**
 * 本轮的"当前目录"。
 *
 * 一次生成里可能有多步工具调用，模型在第一步 cd 之后，后续步骤的 shell 和相对路径都要跟着变，
 * 所以状态挂在工具闭包外面由一次 [createWorkspaceTools] 调用持有，而不是构造工具时固化。
 */
internal class TurnCwdState(@Volatile var cwd: String?) {
    fun current(): String? = cwd
}

/** 一次生成需要的全部工作区上下文，避免给每个工具传一长串参数 */
data class WorkspaceToolContext(
    val workspaceId: String,
    /** 会话 id，cd 之后写回会话要用；为 null 时 cd 只在本轮生效 */
    val conversationId: Uuid? = null,
    /** 会话当前目录（绝对路径，环境内位置） */
    val cwd: String? = null,
    /** 用户锁定了目录，不提供 workspace_cd */
    val cwdLocked: Boolean = false,
    val cwdWriter: ConversationCwdWriter? = null,
)

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    conversationId: Uuid? = null,
    cwdLocked: Boolean = false,
    cwdWriter: ConversationCwdWriter? = null,
    includeShell: Boolean = true,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    return createWorkspaceTools(
        context = WorkspaceToolContext(
            workspaceId = workspaceId,
            conversationId = conversationId,
            cwd = cwd,
            cwdLocked = cwdLocked,
            cwdWriter = cwdWriter,
        ),
        workspaceRepository = workspaceRepository,
        layout = layout,
        capabilities = workspace.capabilities(),
        toolApprovalOverrides = workspace.toolApprovalOverrides(),
        includeShell = includeShell,
    )
}

suspend fun createWorkspaceTools(
    context: WorkspaceToolContext,
    workspaceRepository: WorkspaceRepository,
    layout: WorkspaceLayout,
    capabilities: WorkspaceCapabilities,
    toolApprovalOverrides: Map<String, Boolean>,
    includeShell: Boolean = true,
): List<Tool> {
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, toolApprovalOverrides)
    val cwdState = TurnCwdState(context.cwd)
    val paths = WorkspacePaths(layout, cwdState)

    return buildList {
        // 只读工具先给：shell 没就绪时模型也得能看目录、搜文件
        add(createReadFileTool(context.workspaceId, paths, ::needsApproval, workspaceRepository))
        add(createListTool(context.workspaceId, paths, workspaceRepository))
        add(createGlobTool(context.workspaceId, paths, workspaceRepository))
        add(createGrepTool(context.workspaceId, paths, workspaceRepository))
        if (capabilities.fileWriteTools) {
            add(createWriteFileTool(context.workspaceId, paths, ::needsApproval, workspaceRepository))
            add(createEditFileTool(context.workspaceId, paths, ::needsApproval, workspaceRepository))
        }
        if (includeShell) {
            add(createShellTool(context.workspaceId, paths, ::needsApproval, workspaceRepository, cwdState))
        }
        // APK 工具只在本机工作区有意义: 远程工作区里的 APK 也是先下载到本机再装
        runCatching { getKoin().get<android.content.Context>() }.getOrNull()?.let { appContext ->
            addAll(createApkTools(appContext, context.workspaceId, workspaceRepository, paths))
        }
        if (!context.cwdLocked) {
            add(
                createCdTool(
                    workspaceId = context.workspaceId,
                    paths = paths,
                    cwdState = cwdState,
                    needsApproval = ::needsApproval,
                    workspaceRepository = workspaceRepository,
                    conversationId = context.conversationId,
                    cwdWriter = context.cwdWriter,
                )
            )
        }
    }
}

/**
 * 相对路径解析。
 *
 * 绝对路径原样使用（和以前一致：整个 Rootfs 都可读，写操作由 [WorkspaceLayout.isWritable] 把关）；
 * 相对路径按当前目录解析，当前目录为空时按工作区根。
 */
internal class WorkspacePaths(
    private val layout: WorkspaceLayout,
    private val cwdState: TurnCwdState,
) {
    /** 文件区在环境内的绝对路径，仅用于拼提示词 */
    val workspaceDirDisplay: String get() = layout.workspaceDir

    /** 工作区根的绝对路径，"回到工作区根"用 */
    val workspaceRoot: String get() = layout.workspaceDir

    /** 当前目录；还没 cd 过时是工作区根 */
    fun currentDir(): String = cwdState.current()?.takeIf { it.isNotBlank() } ?: layout.workspaceDir

    fun isWritable(absolute: String): Boolean = layout.isWritable(absolute)

    fun resolve(raw: String): String {
        val path = raw.replace('\\', '/').trim()
        require(path.isNotBlank()) { "path is required" }
        require(path.none { it.code == 0 }) { "path contains invalid character" }
        if (path.startsWith("/")) return normalize(path)
        val base = currentDir()
        return normalize("${base.trimEnd('/')}/$path")
    }

    /** 把绝对路径折成相对工作区根的写法，交给需要相对路径的后端接口 */
    fun relative(absolute: String): String = layout.relativeToWorkspace(normalize(absolute))

    private fun normalize(path: String): String = runCatching {
        Paths.get(path).normalize().toString().ifBlank { "/" }
    }.getOrDefault(path)
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * 去掉 read_file 输出的 `N: ` 行号前缀。
 *
 * 只有至少一行带形如 `12: ` 的前缀时才处理，避免误伤正常的 `note: ...` 文本。
 */
internal fun stripLineNumberPrefixes(text: String): String {
    val lines = text.split('\n')
    if (lines.isEmpty()) return text
    val prefix = Regex("^\\s*\\d+: ?")
    if (lines.none { prefix.containsMatchIn(it) }) return text
    return lines.joinToString("\n") { prefix.replaceFirst(it, "") }
}

private fun createReadFileTool(
    workspaceId: String,
    paths: WorkspacePaths,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a text file from the assistant's bound workspace. Paths may be absolute inside the workspace,
        or relative to the current working directory.
        Images (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico) are returned as images.
        Output lines are prefixed with "N: " for reference only; never copy that prefix into old_text.
        Large files are windowed by line: pass offset (1-based) and limit to read further parts.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path inside the workspace, or a path relative to the current working directory."
                    )
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based first line to return. Defaults to 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of lines to return. Defaults to $READ_DEFAULT_LINES.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = paths.resolve(it.jsonObject.text("path") ?: error("path is required"))
        if (path.isImagePath()) {
            workspaceRepository.readImageInWorkspace(workspaceId, path)
        } else {
            val offset = it.jsonObject.int("offset")?.coerceAtLeast(1) ?: 1
            val limit = it.jsonObject.int("limit")?.coerceIn(1, READ_DEFAULT_LINES * 5) ?: READ_DEFAULT_LINES
            val text = workspaceRepository.readTextInWorkspace(workspaceId, path)
            // 文件以换行结尾时 split 会多出末尾空行，行数和窗口都不能算它
            val allLines = text.split('\n').let { lines ->
                if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
            val window = allLines.drop(offset - 1).take(limit)
            val truncated = offset - 1 + window.size < allLines.size
            val width = (offset + window.size - 1).toString().length
            val body = buildString {
                window.forEachIndexed { index, line ->
                    append((offset + index).toString().padStart(width)).append(": ").append(line)
                    if (index != window.lastIndex) append('\n')
                }
                if (truncated) {
                    append("\n\n(Showing lines $offset-${offset - 1 + window.size} of ${allLines.size}. ")
                    append("Use offset=${offset + window.size} to continue.)")
                } else {
                    append("\n\n(End of file - total ${allLines.size} lines)")
                }
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("offset", offset)
                        put("totalLines", allLines.size)
                        put("truncated", truncated)
                        put("text", body)
                    }.toString()
                )
            )
        }
    },
)

/**
 * 列一层目录。
 *
 * 模型需要枚举目录才能"先看有什么再动手"；只有 shell 时这一步要花一次往返，
 * 而且 shell 没就绪时完全做不到，所以单独给一个只读工具。
 */
private fun createListTool(
    workspaceId: String,
    paths: WorkspacePaths,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list",
    description = """
        List the entries of a directory in the assistant's bound workspace (one level, directories first).
        Paths may be absolute inside the workspace, or relative to the current working directory.
        Use workspace_glob to find files by name anywhere below a directory, and workspace_grep to search file contents.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory to list. Defaults to the current working directory.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum entries to return. Defaults to $LIST_DEFAULT_LIMIT.")
                })
            },
        )
    },
    execute = {
        val params = it.jsonObject
        val dir = params.text("path")?.takeIf { path -> path.isNotBlank() }?.let(paths::resolve)
            ?: paths.currentDir()
        val limit = (params.int("limit") ?: LIST_DEFAULT_LIMIT).coerceIn(1, TOOL_LIMIT_MAX)
        val entries = workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, paths.relative(dir))
        val shown = entries.take(limit)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", dir)
                    put("count", entries.size)
                    put("truncated", entries.size > shown.size)
                    put("entries", buildJsonArray {
                        shown.forEach { entry ->
                            add(buildJsonObject {
                                put("name", entry.name)
                                put("type", if (entry.isDirectory) "dir" else "file")
                                if (!entry.isDirectory) put("sizeBytes", entry.sizeBytes)
                            })
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createGlobTool(
    workspaceId: String,
    paths: WorkspacePaths,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        Find files by name pattern below a directory. Supports *, **, ? and {a,b}, matched against the path
        relative to the search root.
        Results are ordered newest first; VCS metadata is skipped.
        Prefer this over shelling out to find: it works even when the workspace shell is not installed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern, for example **/*.kt or branch-*")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory to search below. Defaults to the current working directory.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum paths to return. Defaults to $GLOB_DEFAULT_LIMIT.")
                })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val params = it.jsonObject
        val pattern = params.text("pattern") ?: error("pattern is required")
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        val dir = params.text("path")?.takeIf { path -> path.isNotBlank() }?.let(paths::resolve)
            ?: paths.currentDir()
        val limit = (params.int("limit") ?: GLOB_DEFAULT_LIMIT).coerceIn(1, TOOL_LIMIT_MAX)
        val matches = workspaceRepository.globAbsolute(workspaceId, dir, pattern, limit)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("path", dir)
                    put("count", matches.size)
                    put("files", buildJsonArray {
                        matches.forEach { entry ->
                            add(buildJsonObject {
                                put("path", entry.path)
                                put("sizeBytes", entry.sizeBytes)
                                put("updatedAt", entry.updatedAt)
                            })
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createGrepTool(
    workspaceId: String,
    paths: WorkspacePaths,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search file contents below a directory and return matches grouped by file with line numbers.
        By default the pattern is matched literally and case-insensitively; set regex=true for a regular expression.
        Binary files, .git and node_modules are skipped.
        Prefer this over shelling out to grep: it works even when the workspace shell is not installed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or regular expression to search for")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory to search below. Defaults to the current working directory.")
                })
                put("include", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional glob filter for file paths, for example **/*.kt")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat pattern as a regular expression. Defaults to false.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum matches to return. Defaults to $GREP_DEFAULT_LIMIT.")
                })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val params = it.jsonObject
        val pattern = params.text("pattern") ?: error("pattern is required")
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        val dir = params.text("path")?.takeIf { path -> path.isNotBlank() }?.let(paths::resolve)
            ?: paths.currentDir()
        val limit = (params.int("limit") ?: GREP_DEFAULT_LIMIT).coerceIn(1, TOOL_LIMIT_MAX)
        val include = params.text("include")?.takeIf { value -> value.isNotBlank() }
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val matches = workspaceRepository.grepAbsolute(
            id = workspaceId,
            dir = dir,
            pattern = pattern,
            include = include,
            limit = limit,
            regex = regex,
        )
        val grouped = buildJsonArray {
            matches.groupBy { match -> match.path }.forEach { (file, hits) ->
                add(buildJsonObject {
                    put("path", file)
                    put("matches", buildJsonArray {
                        hits.forEach { hit ->
                            add(buildJsonObject {
                                put("line", hit.line)
                                put("text", hit.text)
                            })
                        }
                    })
                })
            }
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("path", dir)
                    put("matchCount", matches.size)
                    put("files", grouped)
                }.toString()
            )
        )
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    paths: WorkspacePaths,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file in the assistant's bound workspace. Paths may be absolute inside the workspace,
        or relative to the current working directory.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path inside the workspace, or a path relative to the current working directory."
                    )
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { args -> needsApproval("workspace_write_file") || args.pathOutsideWritableRoots(paths) },
    execute = {
        val params = it.jsonObject
        val path = paths.resolve(params.text("path") ?: error("path is required"))
        val text = params.text("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, text, overwrite)
        WorkspaceChangeTracker.record(workspaceId, path)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    paths: WorkspacePaths,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the assistant's bound workspace. Paths may be absolute inside the workspace,
        or relative to the current working directory.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        old_text must not include the "N: " line-number prefix that workspace_read_file prints.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path inside the workspace, or a path relative to the current working directory."
                    )
                })
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { args -> needsApproval("workspace_edit_file") || args.pathOutsideWritableRoots(paths) },
    execute = {
        val params = it.jsonObject
        val path = paths.resolve(params.text("path") ?: error("path is required"))
        val oldText = params.text("old_text") ?: error("old_text is required")
        val newText = params.text("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInWorkspace(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt。
        // 读文件时输出了行号前缀, 模型偶尔会连前缀一起抄进 old_text; 匹配失败时去掉前缀再试一次
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            val stripped = stripLineNumberPrefixes(oldText)
            if (stripped == oldText) error("${e.message} (path: $path)")
            runCatching { replaceText(original, stripped, newText, replaceAll) }
                .getOrElse { error("${e.message} (path: $path)") }
        }
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, result.updated, overwrite = true)
        WorkspaceChangeTracker.record(workspaceId, path)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    paths: WorkspacePaths,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    cwdState: TurnCwdState,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace. ")
        append("The workspace files area is at ${paths.workspaceDirDisplay}. ")
        append(
            "Use cwd for a path relative to the workspace files root; it defaults to the current working directory. "
        )
        append("Requires the workspace shell to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Working directory relative to the workspace files root. Defaults to the current working directory."
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.text("command") ?: error("command is required")
        val requested = params.text("cwd")
        // cwd 参数沿用旧语义：相对工作区根。没传时用本轮当前目录（可能已被 cd 改过）
        val cwd = paths.relative(requested?.let(paths::resolve) ?: cwdState.current().orEmpty())
        val timeoutMillis = params.text("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createCdTool(
    workspaceId: String,
    paths: WorkspacePaths,
    cwdState: TurnCwdState,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    conversationId: Uuid?,
    cwdWriter: ConversationCwdWriter?,
) = Tool(
    name = "workspace_cd",
    description = """
        Change the working directory for this conversation. Shell commands and every relative path passed to
        workspace_read_file / workspace_write_file / workspace_edit_file / workspace_list / workspace_glob /
        workspace_grep resolve against it from now on.
        The change is remembered for later turns. Use "." or "/" to go back to the workspace root.
        Prefer this over asking the user to pick a directory: on a phone they cannot browse the workspace easily.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path inside the workspace, or a path relative to the current directory. \".\" or \"/\" resets to the workspace root."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_cd") },
    execute = {
        val raw = it.jsonObject.text("path")?.trim().orEmpty()
        // "." 和 "/" 是"回到工作区根"，不能按当前目录解析，否则 "." 等于原地不动
        val target = when {
            raw.isEmpty() || raw == "." || raw == "/" -> paths.workspaceRoot
            // "~" 交给 shell 展开成 HOME，工作区外也常用
            raw == "~" || raw.startsWith("~/") -> workspaceRepository.expandHome(workspaceId, raw)
            else -> paths.resolve(raw)
        }
        // 存在性校验：工作区文件区内直接列目录（本地和 SSH 同一条路径）；
        // 工作区外的目录（例如 /archive/Clone/xxx）不在文件区里，只能问 shell
        require(workspaceRepository.isDirectory(workspaceId, target, paths.relative(target))) {
            "Not a directory: $target"
        }
        val previous = cwdState.current()?.takeIf { it != target }
        cwdState.cwd = target
        if (conversationId != null && cwdWriter != null) {
            runCatching { cwdWriter.setCwd(conversationId, target) }
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("cwd", target)
                    if (previous != null) put("previous", previous)
                    put("note", "Relative paths and shell commands now start in $target")
                }.toString()
            )
        )
    },
)

private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private suspend fun WorkspaceRepository.readTextInWorkspace(
    workspaceId: String,
    path: String,
): String = readWorkspaceBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端, 本地工作区由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readWorkspaceBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = absoluteFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use workspace_read_file with offset/limit, or workspace_grep, to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInWorkspace(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readWorkspaceBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

// 免强制审批的可写安全区由工作区后端给出, 本地为工作区文件目录、临时目录和技能目录
private fun JsonElement.pathOutsideWritableRoots(paths: WorkspacePaths): Boolean = runCatching {
    val name = (this as? JsonObject)?.text("path") ?: return@runCatching true
    !paths.isWritable(paths.resolve(name))
}.getOrDefault(true)

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
