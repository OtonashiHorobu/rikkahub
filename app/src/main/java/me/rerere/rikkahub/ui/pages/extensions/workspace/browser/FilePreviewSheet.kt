package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceFileEntry

/** 预览时最多读多少行，超出部分只提示不渲染，避免把超大日志灌进 Compose */
private const val PREVIEW_MAX_LINES = 2000

/**
 * 只读快速预览：不进入编辑器就能看清文件头部长什么样。
 * 大文件截断到 [PREVIEW_MAX_LINES] 行并明确提示，而不是悄悄截断。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(
    entry: WorkspaceFileEntry,
    load: suspend () -> String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val content by produceState<PreviewContent>(initialValue = PreviewContent.Loading, entry.path) {
        value = try {
            val text = load()
            val lines = text.lineSequence().toList()
            if (lines.size > PREVIEW_MAX_LINES) {
                PreviewContent.Truncated(lines.take(PREVIEW_MAX_LINES).joinToString("\n"), lines.size)
            } else {
                PreviewContent.Ready(text)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PreviewContent.Failed(error.message ?: "读取失败")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Text(
                    text = "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (val preview = content) {
                    PreviewContent.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("读取中…", style = MaterialTheme.typography.bodyMedium)
                    }

                    is PreviewContent.Failed -> Text(
                        text = preview.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is PreviewContent.Ready -> HighlightCodeBlock(
                        code = preview.text,
                        language = languageOf(entry.name),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    is PreviewContent.Truncated -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HighlightCodeBlock(
                            code = preview.text,
                            language = languageOf(entry.name),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "只显示前 $PREVIEW_MAX_LINES 行，共 ${preview.totalLines} 行",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("在编辑器中打开")
            }
        }
    }
}

private sealed interface PreviewContent {
    data object Loading : PreviewContent
    data class Ready(val text: String) : PreviewContent
    data class Truncated(val text: String, val totalLines: Int) : PreviewContent
    data class Failed(val message: String) : PreviewContent
}

/** 按扩展名给代码高亮挑语言，认不出就当纯文本 */
internal fun languageOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "py" -> "python"
    "js", "mjs", "cjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "json", "json5" -> "json"
    "xml", "html", "htm" -> "xml"
    "md", "markdown" -> "markdown"
    "yml", "yaml" -> "yaml"
    "sh", "bash", "zsh" -> "bash"
    "gradle" -> "groovy"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "hpp", "cc" -> "cpp"
    "css", "scss" -> "css"
    "sql" -> "sql"
    "toml", "ini", "conf", "properties", "env" -> "ini"
    else -> "plaintext"
}
