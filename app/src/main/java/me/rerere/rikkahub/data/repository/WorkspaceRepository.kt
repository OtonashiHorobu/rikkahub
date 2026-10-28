package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceType
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshShell
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

private const val FIND_TIMEOUT_MS = 20_000L
private const val GREP_TIMEOUT_MS = 30_000L
private const val MAX_GREP_LINE_CHARS = 400
private const val DIRECTORY_PROBE_TIMEOUT_MS = 10_000L

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val sshPool: SshConnectionPool,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            // 文件不在本机的工作区 (如远程) 没有本地目录和 Rootfs
            if (!workspace.capabilities().localFiles) continue
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name) &&
                !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    /** 用一条临时连接测试 SSH 配置; [id] 不为空时, 表单里留空的凭据沿用该工作区已保存的值 */
    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        return runInterruptible(Dispatchers.IO) {
            sshPool.probe(draft.toSettings(previous).toConnectionConfig())
        }
    }

    /**
     * 只改 SSH 凭据，其余配置原样保留。
     *
     * 传 null 表示沿用已保存的值（[SshWorkspaceDraft] 里留空就是这个语义），
     * 所以 agent 改密码时不会把主机地址、私钥之类一起覆盖掉。
     */
    suspend fun updateSshCredentials(
        id: String,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
    ) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: $id")
        saveSsh(
            id,
            SshWorkspaceDraft(
                name = workspace.name,
                host = settings.host,
                port = settings.port,
                username = settings.username,
                authType = settings.authType,
                password = password.orEmpty(),
                privateKey = privateKey.orEmpty(),
                passphrase = passphrase.orEmpty(),
                hostKey = settings.hostKey,
            ),
        )
    }

    /** 新建 ([id] 为 null) 或更新 SSH 工作区 */
    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
        val existing = id?.let { dao.getById(it) ?: error("Workspace not found: $it") }
        require(existing == null || existing.isSsh()) { "Not an SSH workspace: $id" }
        val finalName = draft.name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        val settings = withContext(Dispatchers.IO) { draft.toSettings(existing?.sshSettings()) }
        val now = System.currentTimeMillis()
        val workspace = existing?.copy(
            name = finalName,
            sshConfig = JsonInstant.encodeToString(settings),
            updatedAt = now,
        ) ?: Uuid.random().toString().let { newId ->
            WorkspaceEntity(
                id = newId,
                name = finalName,
                root = newId,
                // 远程工作区不需要安装 Rootfs, 视为 shell 已就绪
                shellStatus = WorkspaceShellStatus.READY.name,
                createdAt = now,
                updatedAt = now,
                type = WorkspaceType.SSH.name,
                sshConfig = JsonInstant.encodeToString(settings),
            )
        }
        dao.upsert(workspace)
        withContext(Dispatchers.IO) { sshPool.close(workspace.id) }
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        require(workspace.capabilities().rootfs) { "Rootfs is not available for ${workspace.type} workspaces" }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    /**
     * 工作区在执行环境内的目录约定, 工作区不存在时返回 null。
     *
     * 远程工作区没有配置里的根目录: 默认目录是登录后的 home, 第一次用到时才连上去问一次并缓存下来。
     */
    suspend fun layout(id: String): WorkspaceLayout? {
        val workspace = dao.getById(id) ?: return null
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: return null
            return SshWorkspaceBackend.layoutOf(sshHomeDir(workspace, settings))
        }
        return manager.layout
    }

    /** 界面要展示默认目录时用, 避免各处自己解密 SSH 配置 */
    suspend fun defaultDir(id: String): String? = layout(id)?.workspaceDir

    private val sshHomeMutex = Mutex()

    /**
     * 远程工作区的 home: 已缓存直接用, 否则用一条临时连接问一次并写回配置。
     *
     * 连不上时返回 null (布局退回根目录), 让界面自己报连接错误, 而不是在这里抛。
     */
    private suspend fun sshHomeDir(
        workspace: WorkspaceEntity,
        settings: SshWorkspaceSettings,
    ): String? {
        settings.homeDir?.takeIf { it.startsWith("/") }?.let { return it }
        return sshHomeMutex.withLock {
            // 等锁期间别的调用可能已经探测过
            val latest = dao.getById(workspace.id)?.sshSettings() ?: settings
            latest.homeDir?.takeIf { it.startsWith("/") }?.let { return@withLock it }
            val home = runCatching {
                withContext(Dispatchers.IO) { sshPool.probe(latest.toConnectionConfig()).homeDir }
            }.getOrNull()?.takeIf { it.startsWith("/") } ?: return@withLock null
            val updated = latest.copy(homeDir = home)
            dao.getById(workspace.id)?.let { current ->
                dao.upsert(current.copy(sshConfig = JsonInstant.encodeToString(updated)))
            }
            home
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> {
        val workspace = dao.getById(id) ?: return emptyList()
        return runInterruptible(Dispatchers.IO) { backendOf(workspace).listFiles(area, path) }
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = readFromBackend(id) { it.readText(path) }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withBackend(id) { it.writeText(path, text, overwrite) }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceBackend.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = readFromBackend(id) { backend ->
        when (area) {
            WorkspaceStorageArea.FILES -> backend.readText(path)
            WorkspaceStorageArea.LINUX -> {
                val size = backend.fileSize(area, path)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 ($size bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    backend.exportFile(area, path, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withBackend(id) { it.importFile(area, destinationPath, fileName, inputStream) }

    /** 宿主机上可直接访问的文件; 没有本地落点的工作区返回 null */
    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): File? = withBackend(id) { it.localFile(area, path) }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportFile(area, path, outputStream) }

    /** 按环境内绝对路径读取文件大小, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun absoluteFileSize(
        id: String,
        path: String,
    ): Long = readFromBackend(id) { it.absoluteFileSize(path) }

    /** 按环境内绝对路径解析宿主机上的文件, 不存在、不是文件或没有本地落点时返回 null */
    suspend fun resolveAbsoluteFile(
        id: String,
        path: String,
    ): File? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        runCatching { backendOf(workspace).localAbsoluteFile(path) }
            .getOrNull()
            ?.takeIf { it.isFile }
    }

    /** 执行命令实际使用的 shell 路径 */
    /**
     * 按 glob 模式查找文件, [dir] 是环境内的绝对目录。
     *
     * 本地工作区走 [WorkspaceManager.glob]（纯 JVM 遍历, 不需要 shell 就绪）;
     * SSH 工作区没有本地落点, 退化为 `find` 一条命令。
     * 返回的 [WorkspaceFileEntry.path] 已经转换成环境内绝对路径。
     */
    suspend fun globAbsolute(
        id: String,
        dir: String,
        pattern: String,
        limit: Int,
    ): List<WorkspaceFileEntry> {
        val workspace = dao.getById(id) ?: return emptyList()
        val layout = layout(id) ?: return emptyList()
        if (workspace.capabilities().localFiles) {
            val relative = layout.relativeToWorkspace(dir)
            return runInterruptible(Dispatchers.IO) {
                manager.glob(workspace.root, pattern, relative)
            }.take(limit).map { entry ->
                entry.copy(path = layout.absolutePath(WorkspaceStorageArea.FILES, entry.path))
            }
        }
        // 没有本地落点: find 取 (mtime, 路径), 按修改时间倒序, 再在本地按 glob 过滤
        val base = dir.trimEnd('/').ifEmpty { "/" }
        val command = "find ${shellQuote(base)} -type f -not -path '*/.git/*' " +
            "-printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n ${limit * 20}"
        val output = executeCommand(id, command, "", FIND_TIMEOUT_MS)
        val matcher = globMatcher(pattern)
        val root = layout.workspaceDir
        return output.stdout.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf(' ')
                if (split <= 0) null else line.substring(split + 1).trim()
            }
            .filter { it.isNotBlank() }
            .filter { candidate ->
                runCatching { matcher.matches(java.nio.file.Paths.get(candidate.removePrefix("$root/"))) }
                    .getOrDefault(false)
            }
            .take(limit)
            .map { entryOf(it) }
            .toList()
    }

    /**
     * 在 [dir] 下搜索文件内容。[pattern] 默认按字面量匹配, [regex] 为真时按正则。
     * 返回的 [WorkspaceSearchMatch.path] 已经转换成环境内绝对路径。
     */
    suspend fun grepAbsolute(
        id: String,
        dir: String,
        pattern: String,
        include: String?,
        limit: Int,
        regex: Boolean,
    ): List<WorkspaceSearchMatch> {
        val workspace = dao.getById(id) ?: return emptyList()
        val layout = layout(id) ?: return emptyList()
        if (workspace.capabilities().localFiles) {
            val relative = layout.relativeToWorkspace(dir)
            return runInterruptible(Dispatchers.IO) {
                manager.grep(
                    root = workspace.root,
                    query = pattern,
                    path = relative,
                    regex = regex,
                    ignoreCase = true,
                    includeGlob = include,
                )
            }.take(limit).map { match ->
                match.copy(path = layout.absolutePath(WorkspaceStorageArea.FILES, match.path))
            }
        }
        val base = dir.trimEnd('/').ifEmpty { "/" }
        val includeArg = include?.takeIf { it.isNotBlank() }?.let { " --include=${shellQuote(it)}" }.orEmpty()
        val mode = if (regex) "-E" else "-F"
        val command = "grep -rIn $mode$includeArg --binary-files=without-match " +
            "--exclude-dir=.git --exclude-dir=node_modules " +
            "${shellQuote(pattern)} ${shellQuote(base)} 2>/dev/null | head -n $limit"
        val output = executeCommand(id, command, "", GREP_TIMEOUT_MS)
        return output.stdout.lineSequence()
            .mapNotNull { line ->
                // path:line:text, 路径里可能有冒号, 按最后一个段位切
                val first = line.indexOf(':')
                if (first <= 0) return@mapNotNull null
                val second = line.indexOf(':', first + 1)
                if (second <= 0) return@mapNotNull null
                val number = line.substring(first + 1, second).toIntOrNull() ?: return@mapNotNull null
                WorkspaceSearchMatch(
                    path = line.substring(0, first),
                    line = number,
                    text = line.substring(second + 1).take(MAX_GREP_LINE_CHARS),
                )
            }
            .toList()
    }

    private fun entryOf(absolutePath: String): WorkspaceFileEntry {
        val file = File(absolutePath)
        return WorkspaceFileEntry(
            path = absolutePath,
            name = file.name,
            isDirectory = false,
            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
            updatedAt = runCatching { file.lastModified() }.getOrDefault(0L),
        )
    }

    private fun globMatcher(pattern: String): java.nio.file.PathMatcher {
        val fs = java.nio.file.FileSystems.getDefault()
        val trimmed = pattern.trim()
        return fs.getPathMatcher("glob:$trimmed")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\''") + "'"

    /**
     * 判断 [absolutePath] 是不是目录。
     *
     * 在文件区内直接列目录；文件区外（例如 /archive/Clone/xxx）无法通过文件浏览器接口访问，
     * 只能问执行环境的 shell。本地 Rootfs 里没有这个路径时会如实返回 false。
     */
    suspend fun isDirectory(id: String, absolutePath: String, relativeToFiles: String): Boolean {
        if (!absolutePath.contains("..") && !relativeToFiles.startsWith("/")) {
            val listed = runCatching { listFiles(id, WorkspaceStorageArea.FILES, relativeToFiles) }.getOrNull()
            if (listed != null) return true
        }
        val result = executeCommand(id, "test -d ${shellQuote(absolutePath)}", "", DIRECTORY_PROBE_TIMEOUT_MS)
        return result.exitCode == 0 && !result.timedOut
    }

    /** 把 `~` / `~/x` 展开成执行环境里的 HOME */
    suspend fun expandHome(id: String, path: String): String {
        // printenv 不需要引号，省掉一层 shell 转义
        val home = executeCommand(id, "printenv HOME", "", DIRECTORY_PROBE_TIMEOUT_MS)
            .stdout.trim().trim('"')
        require(home.startsWith("/")) { "Cannot resolve HOME in this workspace" }
        return if (path == "~") home else home.trimEnd('/') + "/" + path.removePrefix("~/")
    }

    suspend fun shell(id: String): String = readFromBackend(id) { it.shell() }

    /** 按环境内绝对路径写入文本; 本地工作区在 Rootfs 内执行 shell 写入, 因此和命令一样支持取消 */
    suspend fun writeAbsoluteText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).writeAbsoluteText(path, text, overwrite)
        }
    }

    /** 按环境内绝对路径导出文件内容, 本地工作区支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportAbsoluteFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = readFromBackend(id) { it.exportAbsoluteFile(path, outputStream) }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        backendOf(workspace).deleteFile(area, path, recursive)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            backendOf(workspace).executeCommand(command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            sshPool.close(id)
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    /** 打开 SSH 工作区的交互式 shell, 阻塞到连接和 shell 都建立好, 不能在主线程调用 */
    fun openSshShell(workspace: WorkspaceEntity, columns: Int, rows: Int, cwd: String? = null): SshShell {
        val backend = backendOf(workspace) as? SshWorkspaceBackend
            ?: error("Not an SSH workspace: ${workspace.id}")
        return backend.openShell(columns, rows, cwd)
    }

    private fun backendOf(workspace: WorkspaceEntity): WorkspaceBackend =
        if (workspace.isSsh()) {
            val settings = workspace.sshSettings() ?: error("SSH workspace config is missing: ${workspace.id}")
            SshWorkspaceBackend(sshPool, workspace.id, settings.toConnectionConfig(), settings.homeDir)
        } else {
            manager.backend(workspace.root, workspace.shellCompatibilityMode)
        }

    private fun SshWorkspaceSettings.toConnectionConfig() = SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = encryptedPassword?.let(::decryptSecret),
        privateKey = encryptedPrivateKey?.let(::decryptSecret),
        passphrase = encryptedPassphrase?.let(::decryptSecret),
        hostKey = hostKey,
    )

    /**
     * 表单转成持久化配置并加密凭据。编辑时留空的凭据沿用 [previous] 的密文;
     * 私钥口令跟随私钥: 换了新私钥就以表单为准, 沿用旧私钥时口令留空也沿用。
     *
     * 远程工作目录不是配置项: 默认目录是登录后的 home, 会话 cwd 可以随时改,
     * 所以这里只沿用上一份缓存到的 [SshWorkspaceSettings.homeDir]。
     */
    private fun SshWorkspaceDraft.toSettings(previous: SshWorkspaceSettings?): SshWorkspaceSettings {
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
        require(port in 1..65535) { "Invalid port: $port" }
        val keepPrivateKey = privateKey.isBlank()
        return SshWorkspaceSettings(
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            homeDir = previous?.homeDir,
            hostKey = hostKey,
            encryptedPassword = if (authType == SshAuthType.PASSWORD) {
                password.takeIf { it.isNotEmpty() }?.let(SecretCipher::encrypt) ?: previous?.encryptedPassword
            } else {
                null
            },
            encryptedPrivateKey = if (authType == SshAuthType.PRIVATE_KEY) {
                privateKey.takeIf { it.isNotBlank() }?.let { SecretCipher.encrypt(it.trim()) }
                    ?: previous?.encryptedPrivateKey
            } else {
                null
            },
            encryptedPassphrase = when {
                authType != SshAuthType.PRIVATE_KEY -> null
                passphrase.isNotEmpty() -> SecretCipher.encrypt(passphrase)
                keepPrivateKey -> previous?.encryptedPassphrase
                else -> null
            },
        )
    }

    private fun decryptSecret(value: String): String = try {
        SecretCipher.decrypt(value)
    } catch (e: Exception) {
        throw IOException(
            "Saved SSH credentials cannot be decrypted (for example after restoring a backup on another device). " +
                "Re-enter them in the workspace settings.",
            e,
        )
    }

    /**
     * 读操作: 取消时中断线程, 远程读取卡在网络上时也能及时返回。
     * 中途放弃没有副作用; 写操作被打断会留下写了一半的文件, 走 [withBackend] 照常执行完。
     */
    private suspend fun <T> readFromBackend(id: String, block: (WorkspaceBackend) -> T): T {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return try {
            runInterruptible(Dispatchers.IO) { block(backendOf(workspace)) }
        } catch (e: Exception) {
            // 被中断的读取会抛出 JSch 包装过的 IO 异常, 已取消时统一按取消处理, 免得被当成连接失败
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private suspend fun <T> withBackend(id: String, block: (WorkspaceBackend) -> T): T =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            block(backendOf(workspace))
        }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
