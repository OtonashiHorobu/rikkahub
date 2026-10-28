package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Home01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 选会话的当前目录。
 *
 * 这里始终用环境内绝对路径: 本地工作区是 `文件区目录/子目录`, 远程 (SSH) 没有固定根,
 * 可以直接选到机器上的任意目录。默认从 [currentCwd] 开始, 没设过就从工作区默认目录开始。
 */
@Composable
fun WorkspaceCwdPickerSheet(
    workspaceId: String,
    currentCwd: String?,
    cwdLocked: Boolean = false,
    onUpdateCwdLocked: (Boolean) -> Unit = {},
    onSelectCwd: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()

    var layout by remember { mutableStateOf<WorkspaceLayout?>(null) }
    var browsePath by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workspaceId) {
        val loaded = runCatching { workspaceRepository.layout(workspaceId) }.getOrNull()
        layout = loaded
        browsePath = currentCwd?.takeIf { it.startsWith("/") } ?: loaded?.workspaceDir ?: "/"
    }

    LaunchedEffect(browsePath, layout) {
        val current = layout ?: return@LaunchedEffect
        val path = browsePath ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                workspaceRepository.listFiles(
                    workspaceId,
                    WorkspaceStorageArea.FILES,
                    current.relativeToWorkspace(path),
                )
            }
            entries = result.sortedWith(
                compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entries = emptyList()
            error = e.message
        } finally {
            loading = false
        }
    }

    // 目录列表要占满屏, 半展开状态只能看到两三个目录
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_cwd_select_directory),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            // 关掉后不注册 workspace_cd，模型就不能自己切目录
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.workspace_cwd_allow_ai),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_cwd_allow_ai_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !cwdLocked, onCheckedChange = { onUpdateCwdLocked(!it) })
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.workspace_cwd_select_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val path = browsePath.orEmpty()
            val defaultDir = layout?.workspaceDir
            // 远程工作区没有固定根: 可以一路向上选到 /; 本地工作区停在文件区目录
            val canGoUp = when {
                layout == null -> false
                layout?.absolutePaths == true -> path != "/"
                else -> path != defaultDir && path != "/"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    enabled = canGoUp,
                    onClick = { browsePath = parentOf(path) },
                ) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                }
                Text(
                    text = path.ifBlank { "/" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (layout?.absolutePaths == true && defaultDir != null && path != defaultDir) {
                    IconButton(onClick = { browsePath = defaultDir }) {
                        Icon(
                            imageVector = HugeIcons.Home01,
                            contentDescription = stringResource(R.string.workspace_cwd_default_dir),
                        )
                    }
                }
            }

            HorizontalDivider()

            if (loading && entries.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularWavyProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    val dirs = entries.filter { it.isDirectory }
                    items(dirs, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = entry.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Folder01,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                browsePath = entry.path
                            },
                        )
                    }

                    if (!loading && dirs.isEmpty()) {
                        item {
                            Text(
                                text = error ?: stringResource(R.string.workspace_cwd_no_subdirectories),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentCwd != null) {
                    TextButton(onClick = {
                        onSelectCwd(null)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.workspace_cwd_reset))
                    }
                }
                FilledTonalButton(
                    enabled = path.isNotBlank(),
                    onClick = {
                        onSelectCwd(path)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.workspace_cwd_set))
                }
            }
        }
    }
}

private fun parentOf(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
