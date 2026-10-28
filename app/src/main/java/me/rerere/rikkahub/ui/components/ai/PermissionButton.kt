package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.data.model.AgentPermissionLevel
import me.rerere.ui.components.ToggleSurface

/**
 * 输入框旁的 Agent 权限按钮。
 *
 * 只显示当前档位的图标，点一下打开 [PermissionLevelSheet]；和思考等级按钮并排。
 * 图标区分三档：只读是锁、工作区写是盾、完全放行是闪电感的实心盾。
 */
@Composable
fun PermissionButton(
    level: AgentPermissionLevel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        checked = level != AgentPermissionLevel.READ_ONLY,
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = level.icon(),
            contentDescription = permissionLevelLabel(level),
            modifier = Modifier
                .padding(8.dp)
                .size(24.dp),
        )
    }
}

private fun AgentPermissionLevel.icon(): ImageVector = when (this) {
    AgentPermissionLevel.READ_ONLY -> HugeIcons.LockKey
    AgentPermissionLevel.WORKSPACE_WRITE -> HugeIcons.Shield01
    AgentPermissionLevel.FULL_ACCESS -> HugeIcons.Shield01
}
