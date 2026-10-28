package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PackageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File

/**
 * 安装前确认面板：包名、版本、targetSdk、签名摘要，以及"是升级还是新装"。
 *
 * 用户在远程机器上编译完把 APK 传到工作区，这一层是唯一能看清"装的是什么"的地方，
 * 所以信息全摊开而不是只给一个安装按钮。
 */
@Composable
fun ApkInstallSheet(
    file: File,
    info: ApkInfo?,
    installing: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val icon = remember(info) { info?.icon?.toBitmap(96, 96)?.asImageBitmap() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.PackageAdd01,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info?.label ?: file.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text(
                        text = info?.let { "${it.packageName} · ${it.versionName ?: "-"} (${it.versionCode})" }
                            ?: file.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }

            HorizontalDivider()

            if (info == null) {
                Text(
                    text = stringResource(R.string.apk_install_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                ApkRow(stringResource(R.string.apk_install_size), file.length().fileSizeToString())
                ApkRow(
                    stringResource(R.string.apk_install_sdk),
                    "min ${info.minSdk} · target ${info.targetSdk}",
                )
                info.signatureDigest?.let { digest ->
                    ApkRow(stringResource(R.string.apk_install_signature), digest, mono = true)
                }
                val installed = info.installedVersionCode
                ApkRow(
                    stringResource(R.string.apk_install_state),
                    when {
                        installed == null -> stringResource(R.string.apk_install_state_new)
                        installed == info.versionCode ->
                            stringResource(R.string.apk_install_state_same, installed)
                        installed < info.versionCode ->
                            stringResource(R.string.apk_install_state_upgrade, installed, info.versionCode)
                        else ->
                            stringResource(R.string.apk_install_state_downgrade, installed, info.versionCode)
                    },
                )
            }

            if (!canInstall) {
                Text(
                    text = stringResource(R.string.apk_install_need_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
                if (canInstall) {
                    Button(onClick = onInstall, enabled = info != null && !installing) {
                        Text(
                            stringResource(
                                if (installing) R.string.apk_install_installing else R.string.apk_install_action
                            )
                        )
                    }
                } else {
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.apk_install_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}
