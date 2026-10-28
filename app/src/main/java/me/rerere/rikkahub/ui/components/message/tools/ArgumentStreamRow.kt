package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.utils.PartialString

/**
 * 生成中的工具参数：一行等宽文本，随参数到达实时增长，末尾带一个光标。
 *
 * 按工具挑最有辨识度的字段展示（shell 显示命令、文件工具显示路径），
 * 没有专门规则的取第一个短字符串字段；都取不到就显示参数已收到的字节数。
 */
@Composable
fun ArgumentStreamRow(
    context: ToolUIContext,
    modifier: Modifier = Modifier,
) {
    val text = remember(context.tool.toolCallId, context.rawInput.length) {
        describeArguments(context)
    }
    if (text.isEmpty()) return
    val scroll = rememberScrollState()
    // 内容增长时滚到末尾，让人看到最新到达的部分
    LaunchedEffect(text.length) { scroll.animateScrollTo(scroll.maxValue) }

    Text(
        text = text + "▍",
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

/** 按工具名挑要展示的字段，返回一行可读文本 */
internal fun describeArguments(context: ToolUIContext): String {
    val partial: Map<String, PartialString> = context.partial
    fun value(name: String): String? = partial[name]?.value?.takeIf { it.isNotBlank() }

    return when (context.tool.toolName) {
        "workspace_shell" -> value("command")
            ?.replace('\n', ' ')
            ?.let { "$ $it" }

        "workspace_install_apk", "workspace_read_apk" -> value("path")

        "workspace_read_file", "workspace_write_file", "workspace_list",
        "workspace_glob", "workspace_grep", "workspace_cd", "workspace_edit_file" ->
            value("path") ?: value("pattern")

        "search_web", "scrape_web" -> value("query") ?: value("url")

        else -> {
            // 没有专门规则时取第一个短字符串字段（长的多半是文件内容，不适合放一行）
            partial.values
                .firstOrNull { it.complete && it.value.isNotBlank() && it.value.length <= 120 }
                ?.value
                ?: context.rawInput.takeIf { it.isNotBlank() }?.let { "${it.length} 字符" }
        }
    }.orEmpty()
}
