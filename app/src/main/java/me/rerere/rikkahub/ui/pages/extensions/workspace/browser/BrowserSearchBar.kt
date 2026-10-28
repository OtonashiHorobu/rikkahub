package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 搜索 + 筛选，一行放完。
 *
 * 搜索框右侧的内容搜索按钮把查询从"按文件名"切到"按文件内容"；
 * 只看改动、排序这种低频项收进筛选面板，按钮上带小圆点提示当前有非默认筛选。
 */
@Composable
internal fun BrowserSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searchContent: Boolean,
    onSearchContentChange: (Boolean) -> Unit,
    sort: BrowserSort,
    ascending: Boolean,
    onlyChanged: Boolean,
    onOpenFilter: () -> Unit,
) {
    val filtering = onlyChanged || sort != BrowserSort.NAME || !ascending
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = CircleShape,
            color = CustomColors.listItemColors.containerColor,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(
                                    if (searchContent) {
                                        R.string.workspace_browser_search_content_hint
                                    } else {
                                        R.string.workspace_browser_search_hint
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.workspace_browser_clear_search),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // 搜索范围开关：文件名 / 文件内容
                if (searchContent) {
                    FilledTonalIconButton(
                        onClick = { onSearchContentChange(false) },
                        enabled = query.isNotBlank(),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.GlobalSearch,
                            contentDescription = stringResource(R.string.workspace_browser_search_content),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = { onSearchContentChange(true) },
                        enabled = query.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.GlobalSearch,
                            contentDescription = stringResource(R.string.workspace_browser_search_content),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        BadgedBox(
            badge = {
                if (filtering) {
                    Badge()
                }
            },
        ) {
            FilledTonalIconButton(onClick = onOpenFilter) {
                Icon(
                    imageVector = HugeIcons.Sorting01,
                    contentDescription = stringResource(R.string.workspace_browser_filter),
                )
            }
        }
    }
}

private val BrowserSort.label: String
    get() = when (this) {
        BrowserSort.NAME -> "名称"
        BrowserSort.SIZE -> "大小"
        BrowserSort.TIME -> "时间"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserSortSheet(
    sort: BrowserSort,
    ascending: Boolean,
    onlyChanged: Boolean,
    onSortChange: (BrowserSort) -> Unit,
    onAscendingChange: (Boolean) -> Unit,
    onOnlyChangedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.workspace_browser_sort_title), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                BrowserSort.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = sort == entry,
                        onClick = { onSortChange(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index, BrowserSort.entries.size),
                    ) {
                        Text(entry.label)
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = ascending,
                    onClick = { onAscendingChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text("升序")
                }
                SegmentedButton(
                    selected = !ascending,
                    onClick = { onAscendingChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text("降序")
                }
            }
            CardGroup {
                switchItem(
                    checked = onlyChanged,
                    onCheckedChange = onOnlyChangedChange,
                    supportingContent = { Text(stringResource(R.string.workspace_browser_only_changed_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_browser_only_changed)) },
                )
            }
        }
    }
}
