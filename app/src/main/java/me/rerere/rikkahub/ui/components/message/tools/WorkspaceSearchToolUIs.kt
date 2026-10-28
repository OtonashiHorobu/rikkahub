package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SearchList01
import me.rerere.rikkahub.R

/** workspace_list：目录条目，前几条做摘要，详情里全列 */
object ListDirToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_list"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Folder01

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.content.getStringContent("path") ?: context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_list_dir, path)
        else stringResource(R.string.tool_ui_list_dir_default)
    }

    private fun entries(context: ToolUIContext): List<Pair<String, Boolean>> {
        val array = context.content?.let { it as? JsonObject }?.get("entries") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            name to ((obj["type"] as? JsonPrimitive)?.content == "dir")
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = entries(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val entries = entries(context)
        if (entries.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            entries.take(6).forEach { (name, isDir) ->
                Text(
                    text = if (isDir) "$name/" else name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entries.size > 6) {
                Text(
                    text = stringResource(R.string.tool_ui_more_entries, entries.size - 6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        EntryListSheet(
            title = title(context),
            entries = entries(context).map { (name, isDir) -> (if (isDir) "$name/" else name) to null },
            onDismissRequest = onDismissRequest,
        )
    }
}

/** workspace_glob：命中的文件路径 */
object GlobToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_glob"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.content.getStringContent("pattern") ?: context.arguments.getStringContent("pattern")
        return if (pattern != null) stringResource(R.string.tool_ui_glob, pattern)
        else stringResource(R.string.tool_ui_glob_default)
    }

    private fun files(context: ToolUIContext): List<String> {
        val array = context.content?.let { it as? JsonObject }?.get("files") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.get("path").stringOrNull() }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = files(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val files = files(context)
        if (files.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            files.take(6).forEach { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (files.size > 6) {
                Text(
                    text = stringResource(R.string.tool_ui_more_files, files.size - 6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        EntryListSheet(
            title = title(context),
            entries = files(context).map { it to null },
            onDismissRequest = onDismissRequest,
        )
    }
}

/** workspace_grep：按文件分组的命中行 */
object GrepToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_grep"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SearchList01

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.content.getStringContent("pattern") ?: context.arguments.getStringContent("pattern")
        val count = context.content?.let { it as? JsonObject }
            ?.get("matchCount")?.jsonPrimitive?.intOrNull
        return when {
            pattern == null -> stringResource(R.string.tool_ui_grep_default)
            count != null -> stringResource(R.string.tool_ui_grep, pattern, count)
            else -> stringResource(R.string.tool_ui_grep_default_pattern, pattern)
        }
    }

    private fun groups(context: ToolUIContext): List<Triple<String, List<Pair<Int, String>>, Unit>> {
        val array = context.content?.let { it as? JsonObject }?.get("files") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = obj["path"].stringOrNull() ?: return@mapNotNull null
            val matches = (obj["matches"] as? JsonArray).orEmpty().mapNotNull { hit ->
                val hitObj = hit as? JsonObject ?: return@mapNotNull null
                val line = (hitObj["line"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val text = hitObj["text"].stringOrNull() ?: return@mapNotNull null
                line to text
            }
            Triple(path, matches, Unit)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = groups(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val groups = groups(context)
        if (groups.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            groups.take(3).forEach { (path, matches, _) ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                matches.take(2).forEach { (line, text) ->
                    Text(
                        text = "$line: $text",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val groups = groups(context)
        ModalBottomSheet(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title(context),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups) { (path, matches, _) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            matches.forEach { (line, text) ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = line.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryListSheet(
    title: String,
    entries: List<Pair<String, String?>>,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(entries) { (name, detail) ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.content
