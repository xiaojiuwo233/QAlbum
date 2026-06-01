package com.xjw.qalbum.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.R
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.CleanupConfig
import com.xjw.qalbum.domain.model.CleanupPreset
import com.xjw.qalbum.service.ai.AiPrivacyGuard
import org.koin.androidx.compose.koinViewModel

/**
 * 设置类别：每个类别一个独立子页面。设置首页只列出这些入口。
 */
enum class SettingsCategory(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    ACCESS("settings_access", "权限与访问", "Shizuku / Root 授权与切换", Icons.Outlined.Security),
    SCAN("settings_scan", "扫描", "chatpic 路径与扫描触发", Icons.Outlined.Storage),
    ANALYSIS("settings_analysis", "分析", "每次上限、顺序与重置标记", Icons.Outlined.Analytics),
    CLEANUP("settings_cleanup", "清理规则", "各维度开关与门限", Icons.Outlined.CleaningServices),
    AI("settings_ai", "AI 配置", "Endpoint / Key / 模型", Icons.Outlined.AutoAwesome),
    PERSONALIZATION("settings_personalization", "个性化", "Material You 动态取色", Icons.Outlined.Palette),
    ABOUT("settings_about", "关于", "版本信息", Icons.Outlined.Info),
    ;

    companion object {
        fun fromRoute(route: String?): SettingsCategory? = entries.firstOrNull { it.route == route }
    }
}

// ──────────────────────────────────────────────────────────────────
// 设置首页：二级入口列表
// ──────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenCategory: (SettingsCategory) -> Unit = {},
) {
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
                .padding(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column {
                    SettingsCategory.entries.forEachIndexed { index, category ->
                        SettingsEntryRow(category = category, onClick = { onOpenCategory(category) })
                        if (index < SettingsCategory.entries.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 64.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsEntryRow(category: SettingsCategory, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(category.title, style = MaterialTheme.typography.titleMedium)
            Text(
                category.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 子页面通用脚手架：返回标题栏 + 可滚动内容
// ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 各类别子页面
// ──────────────────────────────────────────────────────────────────

@Composable
fun AccessSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val accessStatus by viewModel.accessStatus.collectAsState()
    val shizukuStatus by rememberShizukuStatus()
    val context = LocalContext.current

    SettingsDetailScaffold(title = SettingsCategory.ACCESS.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.Security, title = stringResource(R.string.settings_section_access)) {
            AccessModeRow(current = state.accessMode, onSelect = viewModel::setAccessMode)
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
            Button(onClick = { viewModel.checkAccess() }, modifier = Modifier.fillMaxWidth()) {
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
    }
}

@Composable
fun ScanSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val running by viewModel.scanRunning.collectAsState()

    SettingsDetailScaffold(title = SettingsCategory.SCAN.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.Storage, title = stringResource(R.string.settings_section_paths)) {
            var pathInput by remember(state.chatpicPath) { mutableStateOf(state.chatpicPath) }
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text(stringResource(R.string.settings_chatpic_path)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("TIM / 国际版 QQ 路径不同，请按需修改。", style = MaterialTheme.typography.bodySmall)
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pathInput = SettingsRepository.DEFAULT_CHATPIC_PATH }) {
                    Text("恢复默认")
                }
                Button(onClick = { viewModel.setChatpicPath(pathInput.trim()) }) { Text("保存") }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = viewModel::cancelScan) { Text("取消扫描") }
                } else {
                    OutlinedButton(onClick = viewModel::startIncrementalScan) { Text("增量扫描") }
                    Button(onClick = viewModel::startFullScan) { Text("全量扫描") }
                }
            }
        }
    }
}

@Composable
fun AnalysisSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val limit by viewModel.analysisLimit.collectAsState()
    val oldestFirst by viewModel.analysisOldestFirst.collectAsState()
    val context = LocalContext.current
    var limitText by remember(limit) { mutableStateOf(limit.toString()) }
    var showResetDialog by remember { mutableStateOf(false) }

    SettingsDetailScaffold(title = SettingsCategory.ANALYSIS.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.Analytics, title = "每次分析上限") {
            Text(
                "每次「开始分析」最多处理多少张未分析的图片。Hash/质量、OCR、AI 标签、向量等逐图阶段都遵守此上限，处理完即停，下次再继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = limitText,
                onValueChange = { input -> limitText = input.filter { it.isDigit() }.take(6) },
                label = { Text("上限（${SettingsRepository.MIN_ANALYSIS_LIMIT}–${SettingsRepository.MAX_ANALYSIS_LIMIT}）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    limitText = SettingsRepository.DEFAULT_ANALYSIS_LIMIT.toString()
                    viewModel.setAnalysisLimit(SettingsRepository.DEFAULT_ANALYSIS_LIMIT)
                }) { Text("默认 500") }
                Button(onClick = {
                    val value = limitText.toIntOrNull()
                    if (value != null) {
                        viewModel.setAnalysisLimit(value)
                        Toast.makeText(context, "已保存上限", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            }
        }

        SectionCard(icon = Icons.Outlined.Analytics, title = "分析顺序") {
            Text(
                "决定优先分析哪一批图片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = oldestFirst,
                    onClick = { viewModel.setAnalysisOldestFirst(true) },
                    label = { Text("从早到晚") },
                )
                FilterChip(
                    selected = !oldestFirst,
                    onClick = { viewModel.setAnalysisOldestFirst(false) },
                    label = { Text("从晚到早") },
                )
            }
        }

        SectionCard(icon = Icons.Outlined.RestartAlt, title = "重置分析") {
            Text(
                "清除完成标记后，下次分析会重新处理对应图片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showResetDialog = true }) { Text("重置完成标记…") }
        }
    }

    if (showResetDialog) {
        ResetAnalysisDialog(
            onDismiss = { showResetDialog = false },
            onLocalOnly = {
                showResetDialog = false
                viewModel.resetLocalAnalysis {
                    Toast.makeText(context, "已重置本地分析标记", Toast.LENGTH_SHORT).show()
                }
            },
            onAll = {
                showResetDialog = false
                viewModel.resetAllAnalysis {
                    Toast.makeText(context, "已重置全部分析标记", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun ResetAnalysisDialog(
    onDismiss: () -> Unit,
    onLocalOnly: () -> Unit,
    onAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重置分析完成标记") },
        text = {
            Text(
                "选择重置范围：\n\n· 仅本地分析：重跑 Hash / 质量 / 重复 / 评分（免费、本地）。\n· 全部：连 OCR / AI 标签 / 向量 一起清空重跑，其中 AI 与向量会重新调用接口、可能产生费用。",
            )
        },
        confirmButton = {
            TextButton(onClick = onLocalOnly) { Text("仅本地分析") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onAll) { Text("全部") }
            }
        },
    )
}

@Composable
fun CleanupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    SettingsDetailScaffold(title = SettingsCategory.CLEANUP.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.CleaningServices, title = "清理规则") {
            CleanupRulesSection(viewModel = viewModel)
        }
    }
}

@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    SettingsDetailScaffold(title = SettingsCategory.AI.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.AutoAwesome, title = stringResource(R.string.settings_section_ai)) {
            AiSection(state = state, viewModel = viewModel)
        }
    }
}

@Composable
fun PersonalizationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    SettingsDetailScaffold(title = SettingsCategory.PERSONALIZATION.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.Palette, title = "个性化") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Material You 动态取色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Android 12+ 跟随系统壁纸取色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.dynamicColorEnabled, onCheckedChange = viewModel::setDynamicColor)
            }
        }
    }
}

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
) {
    SettingsDetailScaffold(title = SettingsCategory.ABOUT.title, onBack = onBack) {
        SectionCard(icon = Icons.Outlined.Info, title = stringResource(R.string.settings_section_about)) {
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
    }
}

// ──────────────────────────────────────────────────────────────────
// 复用组件（分组卡片 / 各类别内容片段）
// ──────────────────────────────────────────────────────────────────

/** 统一的"分组卡片"，避免每个 Section 重复堆样式 */
@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
private fun CleanupRulesSection(viewModel: SettingsViewModel) {
    val config by viewModel.cleanupConfig.collectAsState()
    val activePreset = CleanupPreset.match(config)

    Text("快捷预设", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CleanupPreset.entries.forEach { preset ->
            FilterChip(
                selected = activePreset == preset,
                onClick = { viewModel.applyCleanupPreset(preset) },
                label = { Text(preset.displayName) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (activePreset == null) "已自定义" else "点预设套用一组开关与门限，可在下方逐项微调",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp))

    CleanupDimensionRow(
        title = "重复图片",
        subtitle = "同组保留最优一张，其余建议清理",
        enabled = config.duplicateEnabled,
        onEnabledChange = viewModel::setDuplicateEnabled,
    )
    Spacer(Modifier.height(8.dp))
    CleanupDimensionRow(
        title = "低分辨率",
        subtitle = "总像素低于门限的图",
        enabled = config.lowResolutionEnabled,
        onEnabledChange = viewModel::setLowResolutionEnabled,
    ) {
        ThresholdSlider(
            value = config.lowResolutionMaxPixels.toFloat(),
            valueRange = CleanupConfig.MIN_LOW_RES_MAX_PIXELS.toFloat()..CleanupConfig.MAX_LOW_RES_MAX_PIXELS.toFloat(),
            onChange = { viewModel.setLowResolutionMaxPixels(it.toLong()) },
            format = { "${String.format("%.1f", it / 1_000_000f)} MP 以下" },
        )
    }
    Spacer(Modifier.height(8.dp))
    CleanupDimensionRow(
        title = "模糊",
        subtitle = "清晰度低于门限的图",
        enabled = config.blurEnabled,
        onEnabledChange = viewModel::setBlurEnabled,
    ) {
        ThresholdSlider(
            value = config.blurMaxScore,
            valueRange = CleanupConfig.MIN_BLUR_MAX_SCORE..CleanupConfig.MAX_BLUR_MAX_SCORE,
            onChange = { viewModel.setBlurMaxScore(it) },
            format = { "清晰度 ${it.toInt()} 以下" },
        )
    }
    Spacer(Modifier.height(8.dp))
    CleanupDimensionRow(
        title = "极小文件",
        subtitle = "文件大小低于门限的图",
        enabled = config.tinyFileEnabled,
        onEnabledChange = viewModel::setTinyFileEnabled,
    ) {
        ThresholdSlider(
            value = config.tinyFileMaxBytes.toFloat(),
            valueRange = CleanupConfig.MIN_TINY_FILE_MAX_BYTES.toFloat()..CleanupConfig.MAX_TINY_FILE_MAX_BYTES.toFloat(),
            onChange = { viewModel.setTinyFileMaxBytes(it.toLong()) },
            format = { "${(it / 1024).toInt()} KB 以下" },
        )
    }
    Spacer(Modifier.height(8.dp))
    CleanupDimensionRow(
        title = "空白 / 纯色",
        subtitle = "几乎纯色、无内容的图",
        enabled = config.blankEnabled,
        onEnabledChange = viewModel::setBlankEnabled,
    )
    Spacer(Modifier.height(8.dp))
    CleanupDimensionRow(
        title = "损坏",
        subtitle = "无法解码的图",
        enabled = config.corruptEnabled,
        onEnabledChange = viewModel::setCorruptEnabled,
    )
}

@Composable
private fun CleanupDimensionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    thresholdContent: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled && thresholdContent != null) {
            thresholdContent()
        }
    }
}

@Composable
private fun ThresholdSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String,
) {
    var sliderValue by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            format(sliderValue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            onValueChangeFinished = { onChange(sliderValue) },
        )
    }
}
