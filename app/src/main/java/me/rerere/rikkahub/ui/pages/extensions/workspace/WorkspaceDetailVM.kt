package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.browser.CONTENT_SEARCH_LIMIT
import me.rerere.rikkahub.ui.pages.extensions.workspace.browser.ContentSearchMatch
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    /** 从聊天页进来时的会话 cwd: 文件浏览器直接停在它上面 */
    private val initialCwd: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _contentSearch = MutableStateFlow(ContentSearchState())
    val contentSearch = _contentSearch.asStateFlow()

    private var searchJob: Job? = null

    private var pendingExport: Pair<WorkspaceStorageArea, List<WorkspaceFileEntry>>? = null

    fun prepareBatchExport(entries: List<WorkspaceFileEntry>): Boolean {
        val files = entries.filterNot { it.isDirectory }
        if (pendingExport != null || state.value.exporting || files.isEmpty()) return false
        pendingExport = state.value.area to files
        return true
    }

    fun dismissExportResult() {
        _state.update { it.copy(exportResult = null) }
    }

    fun exportFilesToDirectory(treeUri: Uri?, resolver: ContentResolver) {
        val (area, entries) = pendingExport.also { pendingExport = null } ?: return
        if (treeUri == null) return
        _state.update {
            it.copy(
                exporting = true,
                exportCompleted = 0,
                exportTotal = entries.size,
                exportResult = null
            )
        }
        viewModelScope.launch {
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    entries.forEachIndexed { index, entry ->
                        ensureActive()
                        var destination: Uri? = null
                        try {
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                entry.name.substringAfterLast('.', "").lowercase()
                            ) ?: "application/octet-stream"
                            val document = DocumentsContract.createDocument(resolver, parent, mime, entry.name)
                                ?: error("无法创建目标文件")
                            destination = document
                            val output = resolver.openOutputStream(document) ?: error("无法打开目标文件")
                            output.use { repository.exportFile(id, area, entry.path, it) }
                            succeeded++
                            destination = null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += "${entry.name}：${error.message ?: "导出失败"}"
                        } finally {
                            // 只清理本次创建但未完整写入的文件。
                            destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                        }
                        _state.update { it.copy(exportCompleted = index + 1) }
                    }
                }
                _state.update {
                    it.copy(exportResult = buildString {
                        append("已导出 $succeeded/${entries.size} 个文件")
                        if (failures.isNotEmpty()) append("\n\n" + failures.joinToString("\n"))
                    })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(exportResult = "导出失败：${error.message}") }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    init {
        viewModelScope.launch {
            loadWorkspace()
            applyInitialCwd()
            refresh()
        }
    }

    /**
     * 默认落在会话 cwd 上; 本地工作区用相对文件区的路径, 远程工作区直接存绝对路径。
     * 远程工作区没有 cwd 时落在默认目录 (登录后的 home), 面包屑才有东西可显示。
     */
    private fun applyInitialCwd() {
        val layout = state.value.layout ?: return
        val cwd = initialCwd?.takeIf { it.startsWith("/") }
        val path = when {
            cwd != null -> if (layout.absolutePaths) cwd else layout.relativeToWorkspace(cwd)
            layout.absolutePaths -> layout.workspaceDir
            else -> return
        }
        _state.update { it.copy(area = WorkspaceStorageArea.FILES, path = path) }
    }

    fun selectArea(area: WorkspaceStorageArea) {
        cancelContentSearch()
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        navigate(entry.path)
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        val parent = path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
        // 远程工作区的路径是绝对路径, 空串会被当成 home, 所以上一层是 /
        val target = if (parent.isEmpty() && state.value.layout?.absolutePaths == true) "/" else parent
        if (target == path) return
        navigate(target)
    }

    /** 跳到当前存储区内的某个相对路径，面包屑和「显示更多」都用它 */
    fun navigate(path: String) {
        if (path == state.value.path) return
        cancelContentSearch()
        _state.update { it.copy(path = path, entries = emptyList(), error = null) }
        refresh()
    }

    /**
     * 在当前目录下搜文件内容。UI 先做 250ms 防抖，这里只负责取消上一次请求，
     * 免得远程工作区上旧结果晚到把新结果盖掉。
     */
    fun searchContent(query: String) {
        searchJob?.cancel()
        val needle = query.trim()
        if (needle.isEmpty()) {
            _contentSearch.value = ContentSearchState()
            return
        }
        searchJob = viewModelScope.launch {
            _contentSearch.update {
                it.copy(query = needle, loading = true, error = null, matches = emptyList(), truncated = false)
            }
            try {
                val layout = state.value.layout
                if (layout == null) error("工作区不可用")
                val area = state.value.area
                val matches = repository.grepAbsolute(
                    id = id,
                    dir = layout.absolutePath(area, state.value.path),
                    pattern = needle,
                    include = null,
                    limit = CONTENT_SEARCH_LIMIT,
                    regex = false,
                )
                val relative = matches.mapNotNull { match ->
                    val path = when (area) {
                        // 远程工作区的路径本来就是绝对路径, 只需要把本地工作区的前缀折掉
                        WorkspaceStorageArea.FILES -> if (layout.absolutePaths) {
                            match.path
                        } else {
                            layout.relativeToWorkspace(match.path)
                                .takeIf { relative -> relative != match.path }
                        }

                        WorkspaceStorageArea.LINUX -> match.path.removePrefix("/")
                    } ?: return@mapNotNull null
                    ContentSearchMatch(
                        path = path,
                        line = match.line,
                        text = match.text.trim().take(MAX_CONTENT_MATCH_CHARS),
                    )
                }
                _contentSearch.update {
                    it.copy(
                        matches = relative,
                        loading = false,
                        truncated = matches.size >= CONTENT_SEARCH_LIMIT,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _contentSearch.update {
                    it.copy(loading = false, error = error.message ?: "内容搜索失败")
                }
            }
        }
    }

    private fun cancelContentSearch() {
        searchJob?.cancel()
        _contentSearch.value = ContentSearchState()
    }

    private var refreshJob: Job? = null

    fun refresh() {
        // 远程工作区列目录要走网络, 快速切换目录时旧请求可能晚到, 只保留最新一次
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val entries = repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
                _state.update { it.copy(entries = entries, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File? = repository.resolveFile(id, area, entry.path)

    /** 文件浏览器的只读快速预览，大小上限由仓库层把关 */
    suspend fun readPreview(entry: WorkspaceFileEntry): String =
        repository.readTextForPreview(id, state.value.area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setShellCompatibilityMode(id, enabled)
                val workspace = repository.getById(id)
                _state.update { it.copy(workspace = workspace) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private suspend fun loadWorkspace() {
        val workspace = repository.getById(id)
        val layout = repository.layout(id)
        _state.update { it.copy(workspace = workspace, layout = layout) }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val layout: WorkspaceLayout? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val exporting: Boolean = false,
    val exportCompleted: Int = 0,
    val exportTotal: Int = 0,
    val exportResult: String? = null,
)

/** 内容搜索的 UI 状态；[matches] 里的路径是相对当前存储区的路径 */
data class ContentSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val matches: List<ContentSearchMatch> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

/** 单条命中最多展示这么多字符，日志里的超长行不该占满屏幕 */
private const val MAX_CONTENT_MATCH_CHARS = 200

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
