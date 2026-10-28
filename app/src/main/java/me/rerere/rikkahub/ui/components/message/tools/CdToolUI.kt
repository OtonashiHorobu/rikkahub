package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * workspace_cd：折叠时显示目标目录，展开摘要显示 旧目录 -> 新目录
 */
object CdToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_cd"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Folder01

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) {
            stringResource(R.string.tool_ui_cd, path)
        } else {
            stringResource(R.string.tool_ui_cd_default)
        }
    }

    private fun targetOf(context: ToolUIContext): String? =
        context.content.getStringContent("cwd") ?: context.arguments.getStringContent("path")

    override fun hasSummary(context: ToolUIContext): Boolean = targetOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val target = targetOf(context) ?: return
        val previous = context.content.getStringContent("previous")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (previous != null) "$previous → $target" else target,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_ui_cd_default),
                style = MaterialTheme.typography.titleMedium,
            )
            HighlightCodeBlock(
                code = targetOf(context).orEmpty(),
                language = "text",
                style = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
            )
        }
    }
}
