package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.AppConfigAuditEntity
import me.rerere.rikkahub.data.repository.AppConfigAuditRepository
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 改配置的变更记录。
 *
 * 每条对应一个被改动的对象（助手/MCP/快捷消息/模式注入/提供商/设置），可以一键撤销；
 * 含凭据的改动只能看，不能撤销（避免把掩码写回配置）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentAuditPage() {
    val repository: AppConfigAuditRepository = koinInject()
    val toaster = LocalToaster.current
    val entries by repository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var busyId by remember { mutableStateOf<String?>(null) }

    fun revert(entry: AppConfigAuditEntity) {
        busyId = entry.id
        scope.launch {
            repository.rollback(entry.id)
                .onSuccess { toaster.show("已撤销：${entry.summary}", type = ToastType.Success) }
                .onFailure { toaster.show(it.message ?: "撤销失败", type = ToastType.Error) }
            busyId = null
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.agent_audit_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = { scope.launch { repository.clear() } },
                        ) {
                            Text(stringResource(R.string.agent_audit_clear))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (entries.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                ExtensionEmptyState(message = stringResource(R.string.agent_audit_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AuditRow(
                        entry = entry,
                        busy = busyId == entry.id,
                        onRevert = { revert(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditRow(
    entry: AppConfigAuditEntity,
    busy: Boolean,
    onRevert: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = CustomColors.listItemColors.containerColor,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(entry.toolName.ifBlank { entry.scope })
                        append(" · ")
                        append(formatTime(entry.createdAt))
                        if (entry.revertedAt != null) {
                            append(" · ")
                            append(stringResource(R.string.agent_audit_reverted))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                entry.revertedAt != null -> Unit
                !entry.rollbackable -> Text(
                    text = stringResource(R.string.agent_audit_not_revertable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> TextButton(onClick = onRevert, enabled = !busy) {
                    Text(stringResource(R.string.agent_audit_revert))
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
