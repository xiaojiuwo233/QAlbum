package com.xjw.qalbum.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.R
import com.xjw.qalbum.domain.model.AccessMode
import org.koin.androidx.compose.koinViewModel

/**
 * 设置页 —— Phase 0 已实现：
 * 1. 显示当前 Shizuku/Root 状态，引导用户授权
 * 2. 切换访问模式（Shizuku ↔ Root）
 * 3. 配置 chatpic 扫描路径
 * 4. 切换 Material You 动态取色
 * 5. 应用版本信息
 *
 * AI 相关设置（endpoint / apiKey / model）会在 Phase 4 接入。
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val accessStatus by viewModel.accessStatus.collectAsState()
    val shizukuStatus by rememberShizukuStatus()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // 1. 权限与访问
            SectionCard(
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.settings_section_access),
            ) {
                AccessModeRow(
                    current = state.accessMode,
                    onSelect = viewModel::setAccessMode,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ShizukuStatusRow(
                    status = shizukuStatus,
                    onRequest = {
                        requestShizukuPermission()
                        viewModel.checkAccess()
                    },
                    onInstall = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://shizuku.rikka.app/")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    },
                )

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.checkAccess() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_test_access))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (accessStatus) {
                        AccessStatus.Ready -> "✔ ${stringResource(R.string.access_mode_ready)}"
                        AccessStatus.NotReady -> "✘ ${stringResource(R.string.access_mode_not_ready)}"
                        AccessStatus.Unknown -> "尚未测试"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (accessStatus) {
                        AccessStatus.Ready -> MaterialTheme.colorScheme.primary
                        AccessStatus.NotReady -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 2. 扫描路径
            SectionCard(
                icon = Icons.Outlined.Storage,
                title = stringResource(R.string.settings_section_paths),
            ) {
                var pathInput by remember(state.chatpicPath) { mutableStateOf(state.chatpicPath) }
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text(stringResource(R.string.settings_chatpic_path)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(
                            "TIM / 国际版 QQ 路径不同，请按需修改。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pathInput = com.xjw.qalbum.data.preferences.SettingsRepository.DEFAULT_CHATPIC_PATH
                    }) {
                        Text("恢复默认")
                    }
                    Button(onClick = { viewModel.setChatpicPath(pathInput.trim()) }) {
                        Text("保存")
                    }
                }
            }

            // 3. 个性化
            SectionCard(
                icon = Icons.Outlined.PrivacyTip,
                title = "个性化",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Material You 动态取色", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Android 12+ 跟随系统壁纸取色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.dynamicColorEnabled,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }

            // 4. 关于
            SectionCard(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_section_about),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("QAlbum", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${stringResource(R.string.settings_version)} ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 统一的"分组卡片"，避免每个 Section 重复堆样式 */
@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun AccessModeRow(
    current: AccessMode,
    onSelect: (AccessMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = current == AccessMode.SHIZUKU,
            onClick = { onSelect(AccessMode.SHIZUKU) },
            label = { Text(stringResource(R.string.access_mode_shizuku)) },
        )
        FilterChip(
            selected = current == AccessMode.ROOT,
            onClick = { onSelect(AccessMode.ROOT) },
            label = { Text(stringResource(R.string.access_mode_root)) },
        )
    }
}

@Composable
private fun ShizukuStatusRow(
    status: ShizukuStatus,
    onRequest: () -> Unit,
    onInstall: () -> Unit,
) {
    val (text, action) = when (status.state) {
        ShizukuState.NotInstalled -> stringResource(R.string.shizuku_not_installed) to stringResource(R.string.shizuku_install)
        ShizukuState.NotRunning -> stringResource(R.string.shizuku_not_running) to null
        ShizukuState.PermissionDenied -> stringResource(R.string.shizuku_permission_denied) to stringResource(R.string.shizuku_request_permission)
        ShizukuState.Granted -> stringResource(R.string.shizuku_granted) to null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status.versionCode > 0) {
                Text(
                    "Shizuku v${status.versionCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.let { actionLabel ->
            TextButton(onClick = {
                when (status.state) {
                    ShizukuState.NotInstalled -> onInstall()
                    ShizukuState.PermissionDenied -> onRequest()
                    else -> Unit
                }
            }) { Text(actionLabel) }
        }
    }
}

/** 预留：当 endpoint 是公网地址时弹出隐私警告 */
@Composable
@Suppress("UnusedPrivateMember")
private fun PrivacyWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐私提示") },
        text = {
            Text(
                "你正在配置一个公网 AI 服务地址。\n" +
                    "图片（压缩后）将通过互联网发送到该服务器，可能包含隐私信息。\n" +
                    "建议优先使用 Ollama / vLLM 等本地推理服务。"
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
@Suppress("UnusedPrivateMember")
private fun ClickableRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
