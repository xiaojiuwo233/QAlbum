package com.xjw.qalbum.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.service.update.AppRelease
import com.xjw.qalbum.service.update.AppUpdates
import com.xjw.qalbum.ui.common.openUrl
import com.xjw.qalbum.ui.common.shortVersionName
import com.xjw.qalbum.ui.components.MarkdownText
import com.xjw.qalbum.ui.components.ShapeIcon
import org.koin.compose.koinInject

/** 有新版本时弹窗（打开 App 自动检查、「关于」里手动检查都走这里） */
@Composable
fun UpdateDialogHost() {
    val updates = koinInject<AppUpdates>()
    val release by updates.pending.collectAsState()
    val context = LocalContext.current
    release?.let { found ->
        UpdateDialog(
            release = found,
            onDismiss = { updates.dismiss() },
            onDownload = {
                updates.dismiss(download = true)
                openUrl(context, found.url)
            },
            onOpenUrl = { openUrl(context, it) },
        )
    }
}

/** 新版本：版本号、更新说明（Markdown，长了可以滚），去下载打开发布页 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDialog(release: AppRelease, onDismiss: () -> Unit, onDownload: () -> Unit, onOpenUrl: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeIcon(
                Icons.Outlined.NewReleases,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                size = 56.dp,
                iconSize = 28.dp,
            )
        },
        title = { Text("发现新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            release.versionName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        "当前 ${shortVersionName(BuildConfig.VERSION_NAME)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (release.notes.isNotBlank()) {
                    MarkdownText(
                        release.notes,
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDownload, shapes = ButtonDefaults.shapes()) { Text("去下载") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("以后再说") } },
    )
}
