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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.R
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.service.ai.AiPrivacyGuard
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                Spacer(Modifier.height(8.dp))
                val running by viewModel.scanRunning.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        OutlinedButton(onClick = viewModel::cancelScan) { Text("取消扫描") }
                    } else {
                        OutlinedButton(onClick = viewModel::startIncrementalScan) { Text("增量扫描") }
                        Button(onClick = viewModel::startFullScan) { Text("全量扫描") }
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

            // 4. AI 配置
            SectionCard(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(R.string.settings_section_ai),
            ) {
                AiSection(state = state, viewModel = viewModel)
            }

            // 5. 关于
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

/** 公网 endpoint 时弹出的隐私提示 */
@Composable
private fun PrivacyWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐私提示") },
        text = { Text(AiPrivacyGuard.PRIVACY_WARNING.trim()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AiSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val safety = remember(state.aiEndpoint) { AiPrivacyGuard.classify(state.aiEndpoint) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var endpointInput by remember(state.aiEndpoint) { mutableStateOf(state.aiEndpoint) }
    var keyInput by remember(state.aiApiKey) { mutableStateOf(state.aiApiKey) }
    var modelInput by remember(state.aiModel) { mutableStateOf(state.aiModel) }
    var embModelInput by remember(state.aiEmbeddingModel) { mutableStateOf(state.aiEmbeddingModel) }
    var keyVisible by remember { mutableStateOf(false) }

    if (showPrivacyDialog) {
        PrivacyWarningDialog(
            onConfirm = {
                showPrivacyDialog = false
                viewModel.setAiEnabled(true)
            },
            onDismiss = { showPrivacyDialog = false },
        )
    }

    // 启用开关
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("启用 AI 分析", style = MaterialTheme.typography.titleSmall)
            Text(
                when (safety) {
                    AiPrivacyGuard.Safety.LOCALHOST -> "✔ 本机地址，数据不离开设备"
                    AiPrivacyGuard.Safety.LOCAL_NETWORK -> "✔ 内网地址，数据不离开局域网"
                    AiPrivacyGuard.Safety.PUBLIC_INTERNET -> "⚠ 公网地址，请注意隐私"
                    AiPrivacyGuard.Safety.INVALID -> "未配置 endpoint"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (safety) {
                    AiPrivacyGuard.Safety.PUBLIC_INTERNET -> MaterialTheme.colorScheme.tertiary
                    AiPrivacyGuard.Safety.INVALID -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        Switch(
            checked = state.aiEnabled,
            onCheckedChange = { newValue ->
                if (newValue && safety == AiPrivacyGuard.Safety.PUBLIC_INTERNET) {
                    showPrivacyDialog = true
                } else {
                    viewModel.setAiEnabled(newValue)
                }
            },
        )
    }
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = endpointInput,
        onValueChange = { endpointInput = it },
        label = { Text("Endpoint（OpenAI 兼容）") },
        placeholder = { Text("https://api.openai.com 或 http://192.168.1.100:11434") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text("API Key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (keyVisible) "隐藏" else "显示",
                )
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            label = { Text("Chat 模型") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(
            value = embModelInput,
            onValueChange = { embModelInput = it },
            label = { Text("Embedding 模型") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            // 重置回默认（OpenAI 主流配置）
            endpointInput = ""
            keyInput = ""
            modelInput = "gpt-4o-mini"
            embModelInput = "text-embedding-3-small"
        }) { Text("重置") }
        Button(onClick = {
            viewModel.setAiEndpoint(endpointInput)
            viewModel.setAiApiKey(keyInput)
            viewModel.setAiModel(modelInput)
            viewModel.setAiEmbeddingModel(embModelInput)
        }) { Text("保存") }
    }
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
