package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.PartialString

/** 值超过这个长度就折叠，展开一次看全 */
private const val LONG_VALUE_CHARS = 240

/**
 * 工具入参的结构化展示，替代原来的整块 JSON dump。
 *
 * 规则：
 * - 布尔用 ✓ / ✗，路径和命令用等宽字体，长文本折叠成三行可展开
 * - 生成中没到齐的字段标「接收中」
 * - 底部保留一个「原始 JSON」入口，需要逐字节核对时还能看原文
 */
@Composable
fun ArgumentTable(
    arguments: JsonElement,
    partial: Map<String, PartialString> = emptyMap(),
    rawInput: String = "",
    modifier: Modifier = Modifier,
) {
    val fields = remember(arguments) { arguments.objectFields() }
    var showRaw by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fields.isEmpty() && rawInput.isBlank()) {
            Text(
                text = "(no arguments)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        fields.forEach { (key, value) ->
            ArgumentRow(
                name = key,
                value = value,
                streaming = partial[key]?.complete == false,
            )
        }

        Text(
            text = if (showRaw) "隐藏原始 JSON" else "原始 JSON",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { showRaw = !showRaw }
                .padding(vertical = 4.dp),
        )
        if (showRaw) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = rawInput.ifBlank { arguments.toString() },
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ArgumentRow(
    name: String,
    value: String,
    streaming: Boolean,
) {
    var expanded by remember(name) { mutableStateOf(false) }
    val isLong = value.length > LONG_VALUE_CHARS || value.count { it == '\n' } > 2
    val shown = when {
        !isLong || expanded -> value
        else -> value.lineSequence().take(3).joinToString("\n").take(LONG_VALUE_CHARS)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(top = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = when {
                streaming -> "$shown…（接收中）"
                value == "true" -> "✓"
                value == "false" -> "✗"
                else -> shown
            },
            modifier = Modifier
                .weight(1f)
                .animateContentSize()
                .then(if (isLong) Modifier.clickable { expanded = !expanded } else Modifier),
            style = MaterialTheme.typography.bodySmall.copy(
                // 路径、命令这类值用等宽才好读；短布尔和数字保持常规字体
                fontFamily = if (value.length > 24 || value.contains('/')) FontFamily.Monospace else null,
            ),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 取顶层字段的展示值。
 *
 * 字符串去掉外层引号；嵌套对象/数组原样给 JSON 文本，不再递归铺开——
 * 生成阶段本来就看不清层级，铺开反而更乱。
 */
private fun JsonElement.objectFields(): List<Pair<String, String>> {
    val obj = this as? JsonObject ?: return emptyList()
    return obj.entries.map { (key, element) ->
        val text = when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
        key to text
    }
}

/** 供测试：把原始字符串解析成「字段 → 文本」，不依赖 Compose */
internal fun argumentFieldsForTest(arguments: JsonElement): List<Pair<String, String>> =
    arguments.objectFields()
