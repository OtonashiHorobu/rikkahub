package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.SearchRemove
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.pages.extensions.workspace.ContentSearchState
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File

/**
 * 文件浏览器：区域切换 + 面包屑 + 搜索（名称即时过滤、内容防抖搜索）+ 排序过滤 + 分页列表。
 *
 * 列表只渲染前 [BROWSER_PAGE_SIZE] 条，SSH 上的大目录不会一次性铺满；
 * 内容搜索走 [ContentSearchState]，由 VM 在仓库层用 grep 实现（本地纯 JVM，远程一条命令）。
 */
@Composable
fun WorkspaceBrowserPage(
    state: WorkspaceDetailState,
    isActive: Boolean,
    contentSearch: ContentSearchState,
    changedPaths: Set<String>,
    /** 会话 cwd: 远程工作区里"工作目录"指的就是它 */
    cwd: String?,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onNavigate: (String) -> Unit,
    onSearchContent: (String) -> Unit,
    onResolveImage: suspend (WorkspaceFileEntry, WorkspaceStorageArea) -> File?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onPreview: (WorkspaceFileEntry) -> Unit,
    onInstallApk: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onBatchExport: (List<WorkspaceFileEntry>) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchContent by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(BrowserSort.NAME) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var onlyChanged by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(BROWSER_PAGE_SIZE) }

    var selecting by remember(state.area, state.path) { mutableStateOf(false) }
    var selectedPaths by remember(state.area, state.path) { mutableStateOf(emptySet<String>()) }

    val absoluteOf = remember(state.layout, state.area) {
        { entry: WorkspaceFileEntry -> state.layout?.absolutePath(state.area, entry.path) }
    }
    val view = remember(state.entries, query, sort, ascending, onlyChanged, changedPaths) {
        state.entries.browserView(
            query = query,
            sort = sort,
            ascending = ascending,
            onlyChanged = if (onlyChanged) changedPaths else null,
            absolutePath = absoluteOf,
        )
    }
    // 过滤条件一变就从头开始分页，否则换了搜索词还停在上一次的页数上
    LaunchedEffect(state.area, state.path, query, sort, ascending, onlyChanged) {
        shown = BROWSER_PAGE_SIZE
    }

    // 名称过滤是纯本地的，内容搜索要走仓库，250ms 防抖后再发
    LaunchedEffect(query, searchContent) {
        if (searchContent && query.isNotBlank()) {
            delay(250)
            onSearchContent(query)
        } else {
            onSearchContent("")
        }
    }

    val searchingContent = searchContent && query.isNotBlank()

    val absolutePaths = state.layout?.absolutePaths == true
    val hasRootfs = state.workspace?.capabilities()?.rootfs ?: true
    val crumbs = remember(state.path) { crumbTrail(state.path) }
    val locationLabel: String
    val locationOptions: List<BrowserLocationOption>
    if (absolutePaths) {
        // 远程工作区没有"区域"之分: 位置菜单给的是常用目录, 根目录不一定是可读的
        val cwdPath = cwd?.takeIf { it.startsWith("/") }
        val defaultDir = state.layout?.workspaceDir
        locationLabel = stringResource(R.string.workspace_browser_location_working_dir)
        locationOptions = buildList {
            cwdPath?.let {
                add(BrowserLocationOption(stringResource(R.string.workspace_browser_location_working_dir), state.path == it) {
                    onNavigate(it)
                })
            }
            defaultDir?.takeIf { it != cwdPath }?.let {
                add(BrowserLocationOption(stringResource(R.string.workspace_browser_location_home), state.path == it) {
                    onNavigate(it)
                })
            }
            add(BrowserLocationOption(stringResource(R.string.workspace_browser_location_root), state.path == "/") {
                onNavigate("/")
            })
        }
    } else {
        val filesLabel = stringResource(
            if (hasRootfs) R.string.workspace_detail_area_files else R.string.workspace_detail_area_working_dir
        )
        val rootLabel = stringResource(
            if (hasRootfs) R.string.workspace_detail_area_rootfs else R.string.workspace_detail_area_root
        )
        locationLabel = if (state.area == WorkspaceStorageArea.FILES) filesLabel else rootLabel
        locationOptions = listOf(
            BrowserLocationOption(filesLabel, state.area == WorkspaceStorageArea.FILES) {
                onSelectArea(WorkspaceStorageArea.FILES)
            },
            BrowserLocationOption(rootLabel, state.area == WorkspaceStorageArea.LINUX) {
                onSelectArea(WorkspaceStorageArea.LINUX)
            },
        )
    }
    val files = view.filterNot { it.isDirectory }
    val selectedFiles = files.filter { it.path in selectedPaths }
    val allSelected = files.isNotEmpty() && selectedFiles.size == files.size
    fun exitSelection() {
        selecting = false
        selectedPaths = emptySet()
    }
    BackHandler(enabled = selecting && isActive) { exitSelection() }

    val showLoading = rememberDelayedVisibility(state.loading)
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                // 多选时底部留出悬浮工具栏的位置
                bottom = if (selecting) 128.dp else 16.dp,
            ),
        ) {
            item(key = "chrome") {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BrowserLocationBar(
                        label = locationLabel,
                        options = locationOptions,
                        crumbs = crumbs,
                        onNavigate = onNavigate,
                        leadingIcon = if (absolutePaths) HugeIcons.Folder01 else HugeIcons.FolderOpen,
                    )
                    BrowserSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        searchContent = searchContent,
                        onSearchContentChange = { searchContent = it },
                        onlyChanged = onlyChanged,
                        sort = sort,
                        ascending = ascending,
                        onOpenFilter = { showSortSheet = true },
                    )
                    // 刷新时保留现有列表, 只在顶部提示正在加载
                    if (showLoading && state.entries.isNotEmpty()) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (state.exporting) {
                        ExportProgressCard(
                            completed = state.exportCompleted,
                            total = state.exportTotal,
                        )
                    }
                    state.error?.let { ErrorCard(it) }
                }
            }

            if (searchingContent) {
                if (contentSearch.loading) {
                    item(key = "search-loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator()
                        }
                    }
                }
                contentSearch.error?.let { message ->
                    item(key = "search-error") { ErrorCard(message) }
                }
                if (!contentSearch.loading && contentSearch.matches.isEmpty() && contentSearch.error == null &&
                    contentSearch.query == query.trim()
                ) {
                    item(key = "search-empty") {
                        ExtensionEmptyState(
                            message = "没有文件包含「${query.trim()}」",
                            icon = HugeIcons.SearchRemove,
                        )
                    }
                }
                itemsIndexed(
                    items = contentSearch.matches,
                    key = { _, match -> "match:${match.path}:${match.line}" },
                ) { index, match ->
                    ContentMatchRow(
                        match = match,
                        shape = cardGroupItemShape(index, contentSearch.matches.size),
                        onClick = {
                            onOpen(
                                WorkspaceFileEntry(
                                    path = match.path,
                                    name = match.path.substringAfterLast('/'),
                                    isDirectory = false,
                                    sizeBytes = 0,
                                    updatedAt = 0,
                                )
                            )
                        },
                        modifier = Modifier.padding(bottom = if (index == contentSearch.matches.lastIndex) 0.dp else 2.dp),
                    )
                }
                if (contentSearch.truncated) {
                    item(key = "search-truncated") {
                        Text(
                            text = "只显示前 ${contentSearch.matches.size} 条命中，缩小搜索词试试",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                if (showLoading && state.entries.isEmpty()) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                if (!state.loading && view.isEmpty() && state.error == null) {
                    item(key = "empty") {
                        val filtering = query.isNotBlank() || onlyChanged
                        ExtensionEmptyState(
                            message = if (filtering) {
                                "没有匹配的文件"
                            } else {
                                stringResource(R.string.workspace_detail_empty_directory)
                            },
                            icon = if (filtering) HugeIcons.SearchRemove else HugeIcons.FolderOpen,
                        )
                    }
                }

                val visible = view.take(shown)
                itemsIndexed(visible, key = { _, entry -> "${state.area.name}:${entry.path}" }) { index, entry ->
                    BrowserFileRow(
                        entry = entry,
                        area = state.area,
                        copyPath = absoluteOf(entry),
                        shape = cardGroupItemShape(index, visible.size),
                        changed = absoluteOf(entry)?.let { it in changedPaths } ?: false,
                        onResolveImage = { onResolveImage(entry, state.area) },
                        selecting = selecting,
                        selected = entry.path in selectedPaths,
                        onLongClick = {
                            selecting = true
                            selectedPaths = selectedPaths + entry.path
                        },
                        onOpen = {
                            if (selecting && !entry.isDirectory) {
                                selectedPaths = if (entry.path in selectedPaths) {
                                    selectedPaths - entry.path
                                } else {
                                    selectedPaths + entry.path
                                }
                            } else {
                                onOpen(entry)
                            }
                        },
                        onPreview = { onPreview(entry) },
                        onInstallApk = { onInstallApk(entry) },
                        onDelete = { onDelete(entry) },
                        onExport = { onExport(entry) },
                        onShare = { onShare(entry) },
                        modifier = Modifier.padding(bottom = if (index == visible.lastIndex) 0.dp else 2.dp),
                    )
                }
                if (view.size > visible.size) {
                    item(key = "more") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = CustomColors.listItemColors.containerColor,
                        ) {
                            TextButton(
                                onClick = { shown += BROWSER_PAGE_SIZE },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("显示更多（还有 ${view.size - visible.size} 个）")
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = CustomColors.floatingToolbarColors,
                leadingContent = {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "取消多选")
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            selectedPaths = if (allSelected) emptySet() else files.map { it.path }.toSet()
                        },
                    ) {
                        Icon(
                            HugeIcons.CheckList,
                            contentDescription = if (allSelected) "取消全选" else "全选",
                        )
                    }
                },
            ) {
                Button(
                    onClick = { onBatchExport(selectedFiles) },
                    enabled = selectedFiles.isNotEmpty() && !state.exporting,
                ) {
                    Icon(
                        imageVector = HugeIcons.FileExport,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("导出 (${selectedFiles.size})")
                }
            }
        }
    }

    if (showSortSheet) {
        BrowserSortSheet(
            sort = sort,
            ascending = ascending,
            onlyChanged = onlyChanged,
            onSortChange = { sort = it },
            onAscendingChange = { ascending = it },
            onOnlyChangedChange = { onlyChanged = it },
            onDismiss = { showSortSheet = false },
        )
    }
}
