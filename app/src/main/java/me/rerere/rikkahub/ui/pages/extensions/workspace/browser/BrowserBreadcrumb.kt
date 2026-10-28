package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.ui.theme.CustomColors

/** 面包屑最多显示末尾几段, 更深的祖先前置省略号 */
private const val MAX_VISIBLE_CRUMBS = 2

/** 位置菜单的一项：区域 (本地工作区) 或常用目录 (远程工作区) */
data class BrowserLocationOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 文件浏览器的位置栏：一行里同时放"在哪个区域/哪个常用目录"和当前路径。
 *
 * 以前区域选择、面包屑、搜索、筛选各占一行，四行横条压在列表上面很挤；
 * 现在区域切换收进左侧标签，路径跟在后面滚动，搜索与筛选合成下面一行。
 */
@Composable
internal fun BrowserLocationBar(
    label: String,
    options: List<BrowserLocationOption>,
    crumbs: List<Crumb>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // 路径变长、或可用宽度变化时滚到末尾，刚进去的目录一定看得到
    LaunchedEffect(crumbs, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .clickable(enabled = options.isNotEmpty()) { expanded = true }
                        .padding(start = 16.dp, end = if (options.isEmpty()) 16.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    leadingIcon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (options.isNotEmpty()) {
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                expanded = false
                                option.onClick()
                            },
                            trailingIcon = {
                                if (option.selected) {
                                    Icon(
                                        imageVector = HugeIcons.Tick02,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // 竖分隔: 不能拿 HorizontalDivider (它 fillMaxWidth, 在 Row 里会把面包屑挤成 0 宽)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scroll)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 目录很深时只留末尾两段, 前面用省略号带过:
                // 横向滚到末尾会把第一段切成半个字, 看着像渲染 bug
                if (crumbs.size > MAX_VISIBLE_CRUMBS) {
                    Text(
                        text = "…",
                        modifier = Modifier.padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (crumbs.isEmpty()) {
                    Text(
                        text = "根目录",
                        modifier = Modifier.padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val visibleCrumbs = crumbs.takeLast(MAX_VISIBLE_CRUMBS)
                visibleCrumbs.forEachIndexed { index, crumb ->
                    val current = index == visibleCrumbs.lastIndex
                    Text(
                        text = crumb.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .then(if (current) Modifier else Modifier.clickable { onNavigate(crumb.path) })
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (current) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!current) {
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}
