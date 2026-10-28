package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PackageAdd01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileType
import me.rerere.rikkahub.ui.pages.extensions.workspace.detectFileType
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File

/**
 * 目录列表的一行。
 *
 * 与旧实现相比多了两处信息：本轮被 AI 改动过的文件在名字右侧带一个小圆点，
 * 以及 APK 的行内菜单里直接给「安装」入口（不用先点开再走安装面板）。
 */
@Composable
internal fun BrowserFileRow(
    entry: WorkspaceFileEntry,
    area: WorkspaceStorageArea,
    copyPath: String?,
    shape: Shape,
    changed: Boolean,
    onResolveImage: suspend () -> File?,
    selecting: Boolean,
    selected: Boolean,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onInstallApk: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fileType = if (entry.isDirectory) null else entry.detectFileType()
    val isImage = fileType == WorkspaceFileType.IMAGE
    val imageFile by produceState<File?>(
        initialValue = null,
        key1 = if (isImage) area else null,
        key2 = if (isImage) entry.path else null,
        key3 = if (isImage) "${entry.updatedAt}:${entry.sizeBytes}" else null,
    ) {
        if (isImage) {
            value = try {
                onResolveImage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            CustomColors.listItemColors.containerColor
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (entry.isDirectory) null else onLongClick,
                onLongClickLabel = if (entry.isDirectory) null else "选择文件",
            ),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isImage -> {
                    val context = LocalContext.current
                    val imageRequest = remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                        imageFile?.let {
                            ImageRequest.Builder(context)
                                .data(it)
                                .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                                .build()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        imageRequest?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }

                entry.isDirectory -> CardGroupIcon(
                    icon = HugeIcons.Folder01,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                // 选中时整行换成 secondaryContainer，图标底色跟着换一档，不然会融进背景
                else -> CardGroupIcon(
                    icon = HugeIcons.File02,
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.surfaceBright
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (changed) {
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                Text(
                    // 同一层里的祖先路径都一样, 远程工作区还是整条绝对路径, 重复显示只会被截断
                    text = if (entry.isDirectory) {
                        "目录"
                    } else {
                        entry.sizeBytes.fileSizeToString()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                // 和菜单按钮占一样大的位置，进出多选时行高不跳；点整行切换，勾选框自己不接收点击
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!entry.isDirectory) {
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                }
            } else {
                val context = LocalContext.current
                ItemActionMenu(
                    actions = buildList {
                        if (!entry.isDirectory) {
                            add(
                                ItemAction(
                                    text = "快速预览",
                                    icon = HugeIcons.Eye,
                                    onClick = onPreview,
                                )
                            )
                        }
                        if (fileType == WorkspaceFileType.APK) {
                            add(
                                ItemAction(
                                    text = stringResource(R.string.apk_install_action),
                                    icon = HugeIcons.PackageAdd01,
                                    onClick = onInstallApk,
                                )
                            )
                        }
                        if (copyPath != null) {
                            add(
                                ItemAction(
                                    text = stringResource(R.string.workspace_detail_copy_path),
                                    icon = HugeIcons.Copy01,
                                    onClick = {
                                        context.writeClipboardText(copyPath)
                                        // Android 13 起系统会自己弹复制提示
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            Toast.makeText(
                                                context,
                                                R.string.workspace_detail_path_copied,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                )
                            )
                        }
                        if (!entry.isDirectory) {
                            add(
                                ItemAction(
                                    text = stringResource(R.string.common_export),
                                    icon = HugeIcons.FileExport,
                                    onClick = onExport,
                                )
                            )
                            add(
                                ItemAction(
                                    text = stringResource(R.string.common_share),
                                    icon = HugeIcons.Share08,
                                    onClick = onShare,
                                )
                            )
                        }
                        add(
                            ItemAction(
                                text = stringResource(R.string.common_delete),
                                icon = HugeIcons.Delete01,
                                destructive = true,
                                onClick = onDelete,
                            )
                        )
                    },
                )
            }
        }
    }
}

/** 内容搜索的一条命中：路径 + 行号 + 该行内容 */
@Composable
internal fun ContentMatchRow(
    match: ContentSearchMatch,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${match.line}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = match.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
