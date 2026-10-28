package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AgentPermissionLevel

/** 权限等级的三档文案，输入框旁的按钮和面板共用 */
@Composable
fun permissionLevelLabel(level: AgentPermissionLevel): String = stringResource(
    when (level) {
        AgentPermissionLevel.READ_ONLY -> R.string.permission_level_read_only
        AgentPermissionLevel.WORKSPACE_WRITE -> R.string.permission_level_workspace_write
        AgentPermissionLevel.FULL_ACCESS -> R.string.permission_level_full_access
    }
)

@Composable
fun permissionLevelDescription(level: AgentPermissionLevel): String = stringResource(
    when (level) {
        AgentPermissionLevel.READ_ONLY -> R.string.permission_level_read_only_desc
        AgentPermissionLevel.WORKSPACE_WRITE -> R.string.permission_level_workspace_write_desc
        AgentPermissionLevel.FULL_ACCESS -> R.string.permission_level_full_access_desc
    }
)

/**
 * Agent 权限等级选择：三档 + "跟随助手"。
 *
 * [selected] 为会话上固定的值，null 表示跟随助手（此时高亮 [fallback]）。
 */
@Composable
fun PermissionLevelSheet(
    selected: AgentPermissionLevel?,
    fallback: AgentPermissionLevel,
    onSelect: (AgentPermissionLevel?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Shield01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.permission_level_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }

            PermissionRow(
                title = stringResource(R.string.permission_level_follow_assistant),
                description = permissionLevelLabel(fallback),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
            HorizontalDivider()

            AgentPermissionLevel.entries.forEach { level ->
                PermissionRow(
                    title = permissionLevelLabel(level),
                    description = permissionLevelDescription(level),
                    selected = (selected ?: fallback) == level,
                    onClick = { onSelect(level) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
