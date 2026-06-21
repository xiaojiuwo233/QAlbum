package com.xjw.qalbum.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import com.xjw.qalbum.domain.model.DuplicateStrictness
import com.xjw.qalbum.service.ai.AiPrivacyGuard
import com.xjw.qalbum.service.ocr.OcrModelDownloadState
import com.xjw.qalbum.service.ocr.OcrModelStatus
import com.xjw.qalbum.ui.anim.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 设置分组：首页按分组聚合二级入口，每组一张分组卡。
 */
enum class SettingsGroup(val title: String) {
    DATA("数据与分析"),
    SYSTEM("个性化与系统"),
}

/**
 * 设置类别：每个类别一个独立子页面。首页只列出这些入口，按 [group] 聚合。
 */
enum class SettingsCategory(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val group: SettingsGroup,
) {
    SCAN("settings_scan", "扫描", "chatpic 路径与扫描触发", Icons.Outlined.Storage, SettingsGroup.DATA),
    ANALYSIS("settings_analysis", "分析", "每项开关 / 数量 / 顺序", Icons.Outlined.Analytics, SettingsGroup.DATA),
    CLEANUP("settings_cleanup", "清理规则", "各维度开关与门限", Icons.Outlined.CleaningServices, SettingsGroup.DATA),
    AI("settings_ai", "AI 配置", "Endpoint / Key / 模型", Icons.Outlined.AutoAwesome, SettingsGroup.DATA),
    PERSONALIZATION("settings_personalization", "个性化", "Material You 动态取色", Icons.Outlined.Palette, SettingsGroup.SYSTEM),
    ACCESS("settings_access", "权限与访问", "Shizuku / Root 授权与切换", Icons.Outlined.Security, SettingsGroup.SYSTEM),
    LOGS("settings_logs", "日志", "运行日志 / 崩溃记录 / 导出", Icons.AutoMirrored.Outlined.Article, SettingsGroup.SYSTEM),
    ABOUT("settings_about", "关于", "版本信息", Icons.Outlined.Info, SettingsGroup.SYSTEM),
    ;

    companion object {
        fun fromRoute(route: String?): SettingsCategory? = entries.firstOrNull { it.route == route }
    }
}

/** 容器变换共享元素的 key：首页入口行与对应二级页根容器共用 */
private fun settingsSharedKey(route: String): String = "settings-container-$route"

// ──────────────────────────────────────────────────────────────────
// 设置首页：分组卡 + 扁平 ListItem 入口
// ──────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenCategory: (SettingsCategory) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var entranceIndex = 0
            SettingsGroup.entries.forEach { group ->
                val categories = SettingsCategory.entries.filter { it.group == group }
                StaggeredEntrance(delayMillis = entranceIndex++ * 40) {
                    SettingsGroupHeader(group.title)
                }
                StaggeredEntrance(delayMillis = entranceIndex++ * 40) {
                    SettingsGroupCard {
                        categories.forEachIndexed { index, category ->
                            SettingsEntryItem(
                                category = category,
                                onClick = { onOpenCategory(category) },
                                sharedScope = sharedScope,
                                animatedScope = animatedScope,
                            )
                            if (index < categories.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun SettingsEntryItem(
    category: SettingsCategory,
    onClick: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val interaction = remember { MutableInteractionSource() }
    ListItem(
        modifier = Modifier
            .settingsContainer(settingsSharedKey(category.route), sharedScope, animatedScope)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(category.title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                category.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

// ──────────────────────────────────────────────────────────────────
// 二级页通用脚手架：返回标题栏 + 可滚动内容 + Snackbar（即时保存提示）
// 根容器挂 Container Transform 共享元素，从首页入口行"长大"而来
// ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    sharedKey: String? = null,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    content: @Composable ColumnScope.(notify: (String) -> Unit) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }
    Scaffold(
        modifier = Modifier
            .settingsContainer(sharedKey, sharedScope, animatedScope)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content(notify)
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 各类别二级页
// ──────────────────────────────────────────────────────────────────

@Composable
fun ScanSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val running by viewModel.scanRunning.collectAsState()

    SettingsDetailScaffold(
        title = SettingsCategory.SCAN.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.SCAN.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { notify ->
        SettingsSection(header = stringResource(R.string.settings_section_paths)) {
            InstantSaveTextField(
                value = state.chatpicPath,
                label = stringResource(R.string.settings_chatpic_path),
                supporting = "TIM / 国际版 QQ 路径不同，请按需修改。",
                onSave = { viewModel.setChatpicPath(it.trim()); notify("已保存路径") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.setChatpicPath(SettingsRepository.DEFAULT_CHATPIC_PATH)
                    notify("已恢复默认路径")
                }) { Text("恢复默认") }
            }
        }
        SettingsSection(header = "扫描") {
            SettingsBlurb(
                "增量扫描只对比有变化的目录，常态秒级完成；全量扫描重新核对全部目录" +
                    "（含删除检测）；深度校验额外重读所有文件头、修正历史格式误判，" +
                    "耗时较长，仅在怀疑识别有误时使用。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = viewModel::cancelScan) { Text("取消扫描") }
                } else {
                    OutlinedButton(onClick = viewModel::startIncrementalScan) { Text("增量扫描") }
                    Button(onClick = viewModel::startFullScan) { Text("全量扫描") }
                }
            }
            if (!running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::startDeepVerify) { Text("深度校验") }
                }
            }
        }
    }
}

@Composable
fun AnalysisSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val config by viewModel.analysisConfig.collectAsState()
    val oldestFirst by viewModel.analysisOldestFirst.collectAsState()
    val autoFavorite by viewModel.autoFavoriteConfig.collectAsState()
    val ocrModelStatus by viewModel.ocrModelStatus.collectAsState()
    val ocrDownload by viewModel.ocrModelDownloadState.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(ocrDownload) {
        if (ocrDownload is OcrModelDownloadState.Done) {
            viewModel.refreshOcrModelStatus()
        }
    }

    SettingsDetailScaffold(
        title = SettingsCategory.ANALYSIS.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.ANALYSIS.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { notify ->
        SettingsSection(header = "分析项目与每次数量") {
            SettingsBlurb(
                "每个阶段可单独开关与限量。数量留空或填 0 = 无限；每次「开始分析」处理完即停，下次从未完成处继续。",
            )

            Text("哈希 / 质量", style = MaterialTheme.typography.titleSmall)
            SettingsBlurb("所有图片的基础阶段，始终开启。")
            PhaseLimitField(
                label = "数量（留空 = 无限）",
                limit = config.hashLimit,
                onSave = { viewModel.setHashLimit(it); notify("已保存哈希数量") },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsSwitchRow(
                title = "OCR 文字识别",
                subtitle = "识别图中文字，支持文字搜索",
                checked = config.ocrEnabled,
                onCheckedChange = { viewModel.setOcrEnabled(it); notify(if (it) "已开启 OCR" else "已关闭 OCR") },
            )
            if (config.ocrEnabled) {
                PhaseLimitField(
                    label = "OCR 数量（留空 = 无限）",
                    limit = config.ocrLimit,
                    onSave = { viewModel.setOcrLimit(it); notify("已保存 OCR 数量") },
                )
                OcrModelRow(
                    status = ocrModelStatus,
                    downloadState = ocrDownload,
                    onDownload = {
                        viewModel.downloadOcrModel()
                        notify("开始下载 OCR 模型")
                    },
                    onDelete = {
                        viewModel.deleteOcrModel()
                        notify("已删除 OCR 模型")
                    },
                    onRefresh = {
                        viewModel.refreshOcrModelStatus()
                        notify("已刷新 OCR 模型状态")
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsSwitchRow(
                title = "AI 标签",
                subtitle = "调用 AI 给图片打标签（另需在「AI 配置」中启用服务）",
                checked = config.aiTagEnabled,
                onCheckedChange = { viewModel.setAiTagEnabled(it); notify(if (it) "已开启 AI 标签" else "已关闭 AI 标签") },
            )
            if (config.aiTagEnabled) {
                PhaseLimitField(
                    label = "AI 标签数量（留空 = 无限）",
                    limit = config.aiLimit,
                    onSave = { viewModel.setAiLimit(it); notify("已保存 AI 标签数量") },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsSwitchRow(
                title = "向量（语义搜索）",
                subtitle = "生成向量用于语义搜索（另需在「AI 配置」中启用服务）",
                checked = config.embeddingEnabled,
                onCheckedChange = { viewModel.setEmbeddingEnabled(it); notify(if (it) "已开启向量" else "已关闭向量") },
            )
            if (config.embeddingEnabled) {
                PhaseLimitField(
                    label = "向量数量（留空 = 无限）",
                    limit = config.embeddingLimit,
                    onSave = { viewModel.setEmbeddingLimit(it); notify("已保存向量数量") },
                )
            }
        }

        SettingsSection(header = "分析顺序") {
            SettingsBlurb("决定优先分析哪一批图片，「生成审核清理」也会遵守此顺序。")
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

        SettingsSection(header = "自动收藏") {
            SettingsSwitchRow(
                title = "分析时自动收藏",
                subtitle = "分析（评分）阶段，命中下列敏感信息的图片自动加入收藏，避免误删。默认关闭。",
                checked = autoFavorite.enabled,
                onCheckedChange = {
                    viewModel.setAutoFavoriteEnabled(it)
                    notify(if (it) "已开启自动收藏" else "已关闭自动收藏")
                },
            )
            if (autoFavorite.enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingsBlurb("命中规则（任选其一即收藏）：")
                SettingsSwitchRow(
                    title = "证件号",
                    subtitle = "身份证等证件号码",
                    checked = autoFavorite.idNumber,
                    onCheckedChange = viewModel::setAutoFavoriteIdNumber,
                )
                SettingsSwitchRow(
                    title = "订单号",
                    subtitle = "订单 / 快递单号",
                    checked = autoFavorite.orderNo,
                    onCheckedChange = viewModel::setAutoFavoriteOrderNo,
                )
                SettingsSwitchRow(
                    title = "金额",
                    subtitle = "含金额信息",
                    checked = autoFavorite.amount,
                    onCheckedChange = viewModel::setAutoFavoriteAmount,
                )
                SettingsSwitchRow(
                    title = "手机号",
                    subtitle = "含手机号码",
                    checked = autoFavorite.phone,
                    onCheckedChange = viewModel::setAutoFavoritePhone,
                )
                SettingsSwitchRow(
                    title = "地址",
                    subtitle = "含地址信息",
                    checked = autoFavorite.address,
                    onCheckedChange = viewModel::setAutoFavoriteAddress,
                )
                if (!autoFavorite.hasAnyRule) {
                    SettingsBlurb("当前未勾选任何规则，自动收藏不会生效。")
                }
            }
        }

        SettingsSection(header = "重置分析") {
            SettingsBlurb("清除完成标记后，下次分析会重新处理对应图片。")
            OutlinedButton(onClick = { showResetDialog = true }) { Text("重置完成标记…") }
        }
    }

    if (showResetDialog) {
        ResetAnalysisDialog(
            onDismiss = { showResetDialog = false },
            onLocalOnly = {
                showResetDialog = false
                viewModel.resetLocalAnalysis()
            },
            onAll = {
                showResetDialog = false
                viewModel.resetAllAnalysis()
            },
        )
    }
}

@Composable
fun CleanupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val config by viewModel.cleanupConfig.collectAsState()
    val reviewLimit by viewModel.reviewLimit.collectAsState()
    val allDuplicates by viewModel.cleanupAllDuplicates.collectAsState()
    val maxDupGroupSize by viewModel.maxDuplicateGroupSize.collectAsState()
    val duplicateStrictness by viewModel.duplicateStrictness.collectAsState()
    val activePreset = CleanupPreset.match(config)

    SettingsDetailScaffold(
        title = SettingsCategory.CLEANUP.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.CLEANUP.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { notify ->
        SettingsSection(header = "每次审核数量") {
            SettingsBlurb(
                "每次「生成审核清理」最多纳入多少个待处理项（一个重复组算 1 项）。会遵守「分析」里的顺序（从早到晚 / 从晚到早），处理完即止。",
            )
            InstantSaveTextField(
                value = reviewLimit.toString(),
                label = "数量（${SettingsRepository.MIN_REVIEW_LIMIT}–${SettingsRepository.MAX_REVIEW_LIMIT}）",
                keyboardType = KeyboardType.Number,
                onSave = { input ->
                    val value = input.filter { it.isDigit() }.toIntOrNull()
                    if (value != null) {
                        val clamped = value.coerceIn(
                            SettingsRepository.MIN_REVIEW_LIMIT,
                            SettingsRepository.MAX_REVIEW_LIMIT,
                        )
                        viewModel.setReviewLimit(clamped)
                        notify("已保存审核数量 $clamped")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.setReviewLimit(SettingsRepository.DEFAULT_REVIEW_LIMIT)
                    notify("已恢复默认 ${SettingsRepository.DEFAULT_REVIEW_LIMIT}")
                }) { Text("默认 ${SettingsRepository.DEFAULT_REVIEW_LIMIT}") }
            }
            SettingsSwitchRow(
                title = "审核全部重复图片",
                subtitle = "开启后把所有重复图一次性纳入审核（不受上面数量限制），适合一次清完大量重复；其它维度仍按数量",
                checked = allDuplicates,
                onCheckedChange = {
                    viewModel.setCleanupAllDuplicates(it)
                    notify(if (it) "已开启：审核纳入全部重复" else "已关闭")
                },
            )
        }

        SettingsSection(header = "快捷预设") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanupPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = { viewModel.applyCleanupPreset(preset) },
                        label = { Text(preset.displayName) },
                    )
                }
            }
            SettingsBlurb(
                if (activePreset == null) "已自定义" else "点预设套用一组开关与门限，可在下方逐项微调",
            )
        }

        SettingsSection(header = "清理维度") {
            CleanupDimensionRow(
                title = "重复图片",
                subtitle = "同组保留最优一张，其余建议清理",
                enabled = config.duplicateEnabled,
                onEnabledChange = viewModel::setDuplicateEnabled,
            ) {
                Text("匹配严格度", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DuplicateStrictness.entries.forEach { s ->
                        FilterChip(
                            selected = duplicateStrictness == s,
                            onClick = { viewModel.setDuplicateStrictness(s) },
                            label = {
                                Text(
                                    when (s) {
                                        DuplicateStrictness.LOOSE -> "宽松"
                                        DuplicateStrictness.STANDARD -> "标准"
                                        DuplicateStrictness.STRICT -> "严格"
                                    },
                                )
                            },
                        )
                    }
                }
                SettingsBlurb(
                    when (duplicateStrictness) {
                        DuplicateStrictness.LOOSE -> "找得更多，但可能把相似图误判为重复（改后需重新查重）"
                        DuplicateStrictness.STANDARD -> "推荐：兼顾找全与精准（改后需重新查重）"
                        DuplicateStrictness.STRICT -> "宁可漏掉也尽量不误判，适合截图多的相册（改后需重新查重）"
                    },
                )
                ThresholdSlider(
                    value = maxDupGroupSize.toFloat(),
                    valueRange = SettingsRepository.MIN_MAX_DUP_GROUP_SIZE.toFloat()..SettingsRepository.MAX_MAX_DUP_GROUP_SIZE.toFloat(),
                    onChange = { viewModel.setMaxDuplicateGroupSize(it.toInt()) },
                    format = { "视觉重复组最多 ${it.toInt()} 张，超出视为误聚丢弃（改后需重新查重）" },
                )
            }
            CleanupDimensionRow(
                title = "表情包",
                subtitle = "近似正方形的小图 / 动图，默认勾选清理",
                enabled = config.emojiEnabled,
                onEnabledChange = viewModel::setEmojiEnabled,
            ) {
                ThresholdSlider(
                    value = config.emojiSquareRatio,
                    valueRange = CleanupConfig.MIN_EMOJI_SQUARE_RATIO..CleanupConfig.MAX_EMOJI_SQUARE_RATIO,
                    onChange = { viewModel.setEmojiSquareRatio(it) },
                    format = { "长宽比 ≥ ${String.format("%.2f", it)}（越大越接近正方形）" },
                )
                ThresholdSlider(
                    value = config.emojiMaxSide.toFloat(),
                    valueRange = CleanupConfig.MIN_EMOJI_MAX_SIDE.toFloat()..CleanupConfig.MAX_EMOJI_MAX_SIDE.toFloat(),
                    onChange = { viewModel.setEmojiMaxSide(it.toInt()) },
                    format = { "最长边 ≤ ${it.toInt()} px" },
                )
                ThresholdSlider(
                    value = config.emojiMinSide.toFloat(),
                    valueRange = CleanupConfig.MIN_EMOJI_MIN_SIDE.toFloat()..CleanupConfig.MAX_EMOJI_MIN_SIDE.toFloat(),
                    onChange = { viewModel.setEmojiMinSide(it.toInt()) },
                    format = { "最短边 ≥ ${it.toInt()} px" },
                )
            }
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
            CleanupDimensionRow(
                title = "空白 / 纯色",
                subtitle = "几乎纯色、无内容的图",
                enabled = config.blankEnabled,
                onEnabledChange = viewModel::setBlankEnabled,
            )
            CleanupDimensionRow(
                title = "损坏",
                subtitle = "无法解码的图",
                enabled = config.corruptEnabled,
                onEnabledChange = viewModel::setCorruptEnabled,
            )
        }
    }
}

@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val safety = remember(state.aiEndpoint) { AiPrivacyGuard.classify(state.aiEndpoint) }
    val disableThinking by viewModel.aiDisableThinking.collectAsState()
    val concurrency by viewModel.aiConcurrency.collectAsState()
    val maxTokens by viewModel.aiMaxTokens.collectAsState()
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        title = SettingsCategory.AI.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.AI.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { notify ->
        SettingsSection(header = "启用") {
            SettingsSwitchRow(
                title = "启用 AI 分析",
                subtitle = when (safety) {
                    AiPrivacyGuard.Safety.LOCALHOST -> "本机地址，数据不离开设备"
                    AiPrivacyGuard.Safety.LOCAL_NETWORK -> "内网地址，数据不离开局域网"
                    AiPrivacyGuard.Safety.PUBLIC_INTERNET -> "公网地址，请注意隐私"
                    AiPrivacyGuard.Safety.INVALID -> "未配置 endpoint"
                },
                subtitleColor = when (safety) {
                    AiPrivacyGuard.Safety.PUBLIC_INTERNET -> MaterialTheme.colorScheme.tertiary
                    AiPrivacyGuard.Safety.INVALID -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
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

        SettingsSection(header = "服务") {
            InstantSaveTextField(
                value = state.aiEndpoint,
                label = "Endpoint（OpenAI 兼容）",
                placeholder = "https://api.openai.com 或 http://192.168.1.100:11434",
                supporting = "填根地址或带 /v1 都可（如 http://192.168.5.25:1234）。本地服务可不填 Key。",
                onSave = { viewModel.setAiEndpoint(it); notify("已保存 Endpoint") },
            )
            InstantSaveTextField(
                value = state.aiApiKey,
                label = "API Key（本地服务可留空）",
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (keyVisible) "隐藏" else "显示",
                        )
                    }
                },
                onSave = { viewModel.setAiApiKey(it); notify("已保存 API Key") },
            )
            InstantSaveTextField(
                value = state.aiModel,
                label = "Chat 模型",
                supporting = "图片打标签需要支持视觉(vision)的多模态模型，纯文本模型会失败。",
                onSave = { viewModel.setAiModel(it); notify("已保存 Chat 模型") },
            )
            InstantSaveTextField(
                value = state.aiEmbeddingModel,
                label = "Embedding 模型",
                onSave = { viewModel.setAiEmbeddingModel(it); notify("已保存 Embedding 模型") },
            )
        }

        SettingsSection(header = "推理与性能") {
            SettingsSwitchRow(
                title = "过滤模型思考（thinking）",
                subtitle = "请求时关闭思考，并自动剥离响应中的 <think> 内容；思考模型请保持开启",
                checked = disableThinking,
                onCheckedChange = { viewModel.setAiDisableThinking(it); notify(if (it) "已开启思考过滤" else "已关闭思考过滤") },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            InstantSaveTextField(
                value = maxTokens.toString(),
                label = "最大 token（${SettingsRepository.MIN_AI_MAX_TOKENS}–${SettingsRepository.MAX_AI_MAX_TOKENS}）",
                supporting = "Chat 单次回复的 token 上限。思考模型会先消耗大量 token 推理，过小会导致正文为空、打标签失败，建议 ≥1536。",
                keyboardType = KeyboardType.Number,
                onSave = { input ->
                    val value = input.filter { it.isDigit() }.toIntOrNull()
                    if (value != null) {
                        val clamped = value.coerceIn(
                            SettingsRepository.MIN_AI_MAX_TOKENS,
                            SettingsRepository.MAX_AI_MAX_TOKENS,
                        )
                        viewModel.setAiMaxTokens(clamped)
                        notify("已保存最大 token $clamped")
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            var sliderValue by remember(concurrency) { mutableStateOf(concurrency.toFloat()) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("并发请求数：${sliderValue.toInt()}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "AI 标签 / 向量阶段同时处理多少张图片。本地推理服务建议 1–4。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = SettingsRepository.MIN_AI_CONCURRENCY.toFloat()..SettingsRepository.MAX_AI_CONCURRENCY.toFloat(),
                    steps = (SettingsRepository.MAX_AI_CONCURRENCY - SettingsRepository.MIN_AI_CONCURRENCY - 1).coerceAtLeast(0),
                    onValueChangeFinished = {
                        viewModel.setAiConcurrency(sliderValue.toInt())
                        notify("并发数已设为 ${sliderValue.toInt()}")
                    },
                )
            }
        }
    }

    if (showPrivacyDialog) {
        PrivacyWarningDialog(
            onConfirm = {
                showPrivacyDialog = false
                viewModel.setAiEnabled(true)
            },
            onDismiss = { showPrivacyDialog = false },
        )
    }
}

@Composable
fun PersonalizationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    SettingsDetailScaffold(
        title = SettingsCategory.PERSONALIZATION.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.PERSONALIZATION.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { _ ->
        SettingsSection(header = "外观") {
            SettingsSwitchRow(
                title = "Material You 动态取色",
                subtitle = "Android 12+ 跟随系统壁纸取色",
                checked = state.dynamicColorEnabled,
                onCheckedChange = viewModel::setDynamicColor,
            )
        }
        SettingsSection(header = "动效") {
            val sharedTransition by viewModel.detailSharedTransition.collectAsState()
            SettingsSwitchRow(
                title = "图库动画增强",
                subtitle = "更顺滑的动画体验（实验性）",
                checked = sharedTransition,
                onCheckedChange = viewModel::setDetailSharedTransition,
            )
        }
    }
}

@Composable
fun AccessSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val accessStatus by viewModel.accessStatus.collectAsState()
    val shizukuStatus by rememberShizukuStatus()
    val context = LocalContext.current

    SettingsDetailScaffold(
        title = SettingsCategory.ACCESS.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.ACCESS.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { _ ->
        SettingsSection(header = stringResource(R.string.settings_section_access)) {
            Text("访问模式", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.accessMode == AccessMode.SHIZUKU,
                    onClick = { viewModel.setAccessMode(AccessMode.SHIZUKU) },
                    label = { Text(stringResource(R.string.access_mode_shizuku)) },
                )
                FilterChip(
                    selected = state.accessMode == AccessMode.ROOT,
                    onClick = { viewModel.setAccessMode(AccessMode.ROOT) },
                    label = { Text(stringResource(R.string.access_mode_root)) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
        }

        SettingsSection(header = "连通性") {
            Button(onClick = { viewModel.checkAccess() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_test_access))
            }
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
fun AboutSettingsScreen(
    onBack: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    SettingsDetailScaffold(
        title = SettingsCategory.ABOUT.title,
        onBack = onBack,
        sharedKey = settingsSharedKey(SettingsCategory.ABOUT.route),
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    ) { _ ->
        SettingsSection(header = stringResource(R.string.settings_section_about)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("QAlbum", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${stringResource(R.string.settings_version)} ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 复用组件
// ──────────────────────────────────────────────────────────────────

/** Container Transform 共享元素：scope 为空时（如 preview）退化为无动画 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.settingsContainer(
    key: String?,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
): Modifier {
    if (key == null || sharedScope == null || animatedScope == null) return this
    val boundsSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    return with(sharedScope) {
        this@settingsContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> boundsSpec },
        )
    }
}

/** 入场动画：按 [delayMillis] 错峰，从下方 + 淡入（占位稳定，仅做视觉位移） */
@Composable
private fun StaggeredEntrance(
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        anim.animateTo(1f, tween(durationMillis = 320, easing = LinearOutSlowInEasing))
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * 48f
        },
    ) {
        content()
    }
}

/** 分组小标题（首页用） */
@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 首页分组卡：圆角 surfaceContainerLow 容器包扁平列表项 */
@Composable
private fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** 二级页分组：小标题 + 圆角容器（内含具体设置控件） */
@Composable
private fun SettingsSection(
    header: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            header,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** 说明性小字 */
@Composable
private fun SettingsBlurb(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 标题 + 副标题 + 尾部开关 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 即时保存文本框：本地编辑，失焦 / 按键盘「完成」/ 离开页面（dispose）时若内容有变就调用 [onSave]。
 *
 * 之前只在失焦时提交，而用户直接点返回 / 系统返回时往往不会触发失焦，导致输入未保存
 * （例如分析上限输入后退出重进仍是旧值）。这里补充 dispose 时的兜底提交，确保离开即落盘。
 */
@Composable
private fun InstantSaveTextField(
    value: String,
    label: String,
    onSave: (String) -> Unit,
    placeholder: String? = null,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val commit = {
        if (text != value) onSave(text)
    }
    // dispose 兜底提交：用最新的 text / value / onSave，避免离开页面时丢失编辑
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnSave by rememberUpdatedState(onSave)
    DisposableEffect(Unit) {
        onDispose {
            if (latestText != latestValue) latestOnSave(latestText)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focused && !focusState.isFocused) commit()
                focused = focusState.isFocused
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focusManager.clearFocus()
        }),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}

/** 分析阶段数量输入：留空 / 0 = 无限；其余为正整数上限。复用 [InstantSaveTextField] 的离开即落盘。 */
@Composable
private fun PhaseLimitField(
    label: String,
    limit: Int,
    onSave: (Int) -> Unit,
) {
    InstantSaveTextField(
        value = if (limit <= 0) "" else limit.toString(),
        label = label,
        placeholder = "无限",
        keyboardType = KeyboardType.Number,
        onSave = { input -> onSave(input.filter { it.isDigit() }.toIntOrNull() ?: 0) },
    )
}

@Composable
private fun OcrModelRow(
    status: OcrModelStatus,
    downloadState: OcrModelDownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    val downloading = downloadState is OcrModelDownloadState.Downloading
    val title = when (status) {
        OcrModelStatus.NotInstalled -> "OCR 模型未安装"
        is OcrModelStatus.Ready -> "OCR 模型已安装"
        is OcrModelStatus.Failed -> "OCR 模型异常"
    }
    val subtitle = when {
        downloading -> {
            val s = downloadState as OcrModelDownloadState.Downloading
            "${s.message} ${(s.progress * 100).toInt()}%"
        }
        downloadState is OcrModelDownloadState.Failed -> downloadState.reason
        status is OcrModelStatus.Ready -> "${status.manifest.displayName} ${status.manifest.version}"
        status is OcrModelStatus.Failed -> status.reason
        else -> "从 Paddle 官方模型源下载 PP-OCRv6 ONNX 模型；未安装时 OCR 阶段会跳过。"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is OcrModelStatus.Failed || downloadState is OcrModelDownloadState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status is OcrModelStatus.Ready) {
                OutlinedButton(onClick = onDelete, enabled = !downloading) { Text("删除模型") }
                OutlinedButton(onClick = onRefresh, enabled = !downloading) { Text("刷新") }
            } else {
                Button(onClick = onDownload, enabled = !downloading) { Text(if (downloading) "下载中" else "下载模型") }
                OutlinedButton(onClick = onRefresh, enabled = !downloading) { Text("刷新") }
            }
        }
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

@Composable
private fun CleanupDimensionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    thresholdContent: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSwitchRow(
            title = title,
            subtitle = subtitle,
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
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
            style = MaterialTheme.typography.bodyMedium,
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

// ──────────────────────────────────────────────────────────────────
// 对话框
// ──────────────────────────────────────────────────────────────────

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
