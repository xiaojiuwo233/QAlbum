package com.xjw.qalbum.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
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
import com.xjw.qalbum.ui.anim.StaggeredEntrance
import com.xjw.qalbum.ui.anim.pressScale
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.components.WavyProgress
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 设置分组：首页按分组聚合二级入口 */
enum class SettingsGroup(val title: String) {
    DATA("数据与分析"),
    SYSTEM("个性化与系统"),
}

/** 设置类别：每个类别一个独立子页面，首页按 [group] 聚合入口 */
enum class SettingsCategory(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val group: SettingsGroup,
) {
    SCAN("settings_scan", "扫描", "图片目录与扫描方式", Icons.Outlined.Storage, SettingsGroup.DATA),
    ANALYSIS("settings_analysis", "分析", "各分析项的开关、数量与顺序", Icons.Outlined.Analytics, SettingsGroup.DATA),
    CLEANUP("settings_cleanup", "清理规则", "清理维度、门限与审核数量", Icons.Outlined.CleaningServices, SettingsGroup.DATA),
    AI("settings_ai", "AI 服务", "服务地址、密钥与模型", Icons.Outlined.AutoAwesome, SettingsGroup.DATA),
    PERSONALIZATION("settings_personalization", "外观与导出", "动态取色、动效与导出目录", Icons.Outlined.Palette, SettingsGroup.SYSTEM),
    ACCESS("settings_access", "权限与访问", "Shizuku / Root 授权与连通性", Icons.Outlined.Security, SettingsGroup.SYSTEM),
    LOGS("settings_logs", "日志", "运行日志、崩溃记录与导出", Icons.AutoMirrored.Outlined.Article, SettingsGroup.SYSTEM),
    ABOUT("settings_about", "关于", "版本信息", Icons.Outlined.Info, SettingsGroup.SYSTEM),
    ;

    companion object {
        fun fromRoute(route: String?): SettingsCategory? = entries.firstOrNull { it.route == route }
    }
}

/** 容器变换共享元素的 key：首页入口行与对应二级页根容器共用 */
private fun settingsSharedKey(route: String): String = "settings-container-$route"

// ──────────────────────────────────────────────────────────────────
// 设置首页：分组的分段列表
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
            LargeFlexibleTopAppBar(
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
                .padding(horizontal = 16.dp)
                .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            SettingsGroup.entries.forEachIndexed { groupIndex, group ->
                val categories = SettingsCategory.entries.filter { it.group == group }
                StaggeredEntrance(index = groupIndex) {
                    SegmentedGroup(header = group.title) {
                        categories.forEach { category ->
                            item(category.route) { position ->
                                SettingsEntryItem(
                                    category = category,
                                    position = position,
                                    onClick = { onOpenCategory(category) },
                                    sharedScope = sharedScope,
                                    animatedScope = animatedScope,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsEntryItem(
    category: SettingsCategory,
    position: SegmentPosition,
    onClick: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val interaction = remember { MutableInteractionSource() }
    SegmentedListItem(
        position = position,
        interactionSource = interaction,
        modifier = Modifier
            .settingsContainer(settingsSharedKey(category.route), sharedScope, animatedScope)
            .pressScale(interaction),
        onClick = onClick,
        leading = { ShapeIcon(category.icon) },
        headline = { Text(category.title) },
        supporting = { Text(category.subtitle) },
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
    )
}

// ──────────────────────────────────────────────────────────────────
// 二级页通用脚手架：大标题栏（滚动收起）+ 可滚动内容 + Snackbar（即时保存提示）
// 根容器挂 Container Transform 共享元素，从首页入口行"长大"而来
// ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsDetailScaffold(
    category: SettingsCategory,
    onBack: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    content: @Composable ColumnScope.(notify: (String) -> Unit) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }
    Scaffold(
        modifier = Modifier
            .settingsContainer(settingsSharedKey(category.route), sharedScope, animatedScope)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(category.title) },
                subtitle = { Text(category.subtitle) },
                navigationIcon = { BackButton(onBack) },
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
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
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

    SettingsDetailScaffold(SettingsCategory.SCAN, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = stringResource(R.string.settings_section_paths)) {
            item("path") { position ->
                SegmentedCard(position) {
                    InstantSaveTextField(
                        value = state.chatpicPath,
                        label = stringResource(R.string.settings_chatpic_path),
                        supporting = "TIM、国际版 QQ 的目录不同，请按实际情况修改。",
                        onSave = {
                            // 仓库对空输入静默拒绝（不落盘），这里前置拦截并如实提示，避免「已保存」后回弹旧值
                            if (it.trim().isBlank()) {
                                notify("路径不能为空，未保存")
                            } else {
                                viewModel.setChatpicPath(it.trim())
                                notify("已保存路径")
                            }
                        },
                    )
                    TextButton(onClick = {
                        viewModel.setChatpicPath(SettingsRepository.DEFAULT_CHATPIC_PATH)
                        notify("已恢复默认路径")
                    }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("恢复默认路径")
                    }
                }
            }
        }
        SegmentedGroup(header = "扫描") {
            item("scan") { position ->
                SegmentedCard(position) {
                    SettingsBlurb(
                        "增量扫描只比对有变化的目录，通常几秒完成；全量扫描重新核对全部目录并检测已删除的文件。",
                    )
                    AnimatedContent(targetState = running, label = "scanButtons") { isRunning ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isRunning) {
                                OutlinedButton(onClick = viewModel::cancelScan, shapes = ButtonDefaults.shapes()) { Text("取消扫描") }
                            } else {
                                FilledTonalButton(onClick = viewModel::startIncrementalScan, shapes = ButtonDefaults.shapes()) { Text("增量扫描") }
                                Button(onClick = viewModel::startFullScan, shapes = ButtonDefaults.shapes()) { Text("全量扫描") }
                            }
                        }
                    }
                }
            }
            item("deep") { position ->
                SegmentedListItem(
                    position = position,
                    enabled = !running,
                    onClick = { if (!running) viewModel.startDeepVerify() },
                    leading = { Icon(Icons.AutoMirrored.Outlined.FactCheck, contentDescription = null) },
                    headline = { Text("深度校验") },
                    supporting = { Text("重新读取所有文件头、修正格式误判。耗时较长，仅在怀疑识别有误时使用。") },
                )
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
    val analysisResetting by viewModel.analysisResetting.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(ocrDownload) {
        if (ocrDownload is OcrModelDownloadState.Done) viewModel.refreshOcrModelStatus()
    }

    SettingsDetailScaffold(SettingsCategory.ANALYSIS, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "分析项目与每次数量") {
            item("hash") { position ->
                SegmentedCard(position) {
                    Text("哈希与画质", style = MaterialTheme.typography.titleMedium)
                    SettingsBlurb("所有图片都要经过的基础阶段，始终开启。数量留空或填 0 表示不限；每次「开始分析」处理完即停，下次从未完成处继续。")
                    PhaseLimitField(
                        label = "每次数量（留空 = 不限）",
                        limit = config.hashLimit,
                        onSave = { viewModel.setHashLimit(it); notify("已保存哈希数量") },
                    )
                }
            }
            item("ocr") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "文字识别（OCR）",
                    subtitle = "识别图中文字，用于文字搜索与敏感信息判断",
                    checked = config.ocrEnabled,
                    onCheckedChange = { viewModel.setOcrEnabled(it); notify(if (it) "已开启文字识别" else "已关闭文字识别") },
                )
            }
            if (config.ocrEnabled) {
                item("ocr-detail") { position ->
                    SegmentedCard(position) {
                        PhaseLimitField(
                            label = "OCR 每次数量（留空 = 不限）",
                            limit = config.ocrLimit,
                            onSave = { viewModel.setOcrLimit(it); notify("已保存 OCR 数量") },
                        )
                        OcrModelRow(
                            status = ocrModelStatus,
                            downloadState = ocrDownload,
                            onDownload = { viewModel.downloadOcrModel(); notify("开始下载 OCR 模型") },
                            onDelete = { viewModel.deleteOcrModel(); notify("已删除 OCR 模型") },
                            onRefresh = { viewModel.refreshOcrModelStatus(); notify("已刷新 OCR 模型状态") },
                        )
                    }
                }
            }
            item("ai") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "AI 标签",
                    subtitle = "让 AI 为图片生成描述和标签（需先在「AI 服务」中启用）",
                    checked = config.aiTagEnabled,
                    onCheckedChange = { viewModel.setAiTagEnabled(it); notify(if (it) "已开启 AI 标签" else "已关闭 AI 标签") },
                )
            }
            if (config.aiTagEnabled) {
                item("ai-limit") { position ->
                    SegmentedCard(position) {
                        PhaseLimitField(
                            label = "AI 标签每次数量（留空 = 不限）",
                            limit = config.aiLimit,
                            onSave = { viewModel.setAiLimit(it); notify("已保存 AI 标签数量") },
                        )
                    }
                }
            }
            item("embedding") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "语义向量",
                    subtitle = "生成文本向量，支持按意思搜索（需先在「AI 服务」中启用）",
                    checked = config.embeddingEnabled,
                    onCheckedChange = { viewModel.setEmbeddingEnabled(it); notify(if (it) "已开启语义向量" else "已关闭语义向量") },
                )
            }
            if (config.embeddingEnabled) {
                item("embedding-limit") { position ->
                    SegmentedCard(position) {
                        PhaseLimitField(
                            label = "向量每次数量（留空 = 不限）",
                            limit = config.embeddingLimit,
                            onSave = { viewModel.setEmbeddingLimit(it); notify("已保存向量数量") },
                        )
                    }
                }
            }
        }

        SegmentedGroup(header = "分析顺序") {
            item("order") { position ->
                SegmentedCard(position) {
                    SettingsBlurb("决定优先处理哪一批图片，「生成审核清单」也按这个顺序。")
                    ConnectedChoiceRow(
                        options = listOf(true, false),
                        selected = oldestFirst,
                        label = { if (it) "从早到晚" else "从晚到早" },
                        onSelect = viewModel::setAnalysisOldestFirst,
                    )
                }
            }
        }

        SegmentedGroup(header = "自动收藏") {
            item("auto") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "分析时自动收藏",
                    subtitle = "识别到下列敏感信息的图片会自动收藏，收藏后不会被清理",
                    checked = autoFavorite.enabled,
                    onCheckedChange = {
                        viewModel.setAutoFavoriteEnabled(it)
                        notify(if (it) "已开启自动收藏" else "已关闭自动收藏")
                    },
                )
            }
            if (autoFavorite.enabled) {
                item("id") { p -> SegmentedSwitchItem(p, "证件号", autoFavorite.idNumber, viewModel::setAutoFavoriteIdNumber, subtitle = "身份证等证件号码") }
                item("order-no") { p -> SegmentedSwitchItem(p, "订单号", autoFavorite.orderNo, viewModel::setAutoFavoriteOrderNo, subtitle = "订单、快递单号") }
                item("amount") { p -> SegmentedSwitchItem(p, "金额", autoFavorite.amount, viewModel::setAutoFavoriteAmount, subtitle = "转账、账单中的金额") }
                item("phone") { p -> SegmentedSwitchItem(p, "手机号", autoFavorite.phone, viewModel::setAutoFavoritePhone, subtitle = "手机号码") }
                item("address") { p -> SegmentedSwitchItem(p, "地址", autoFavorite.address, viewModel::setAutoFavoriteAddress, subtitle = "收货地址等") }
                if (!autoFavorite.hasAnyRule) {
                    item("no-rule") { position ->
                        SegmentedCard(position, containerColor = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                "还没有勾选任何规则，自动收藏不会生效。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }

        SegmentedGroup(header = "重置分析") {
            item("reset") { position ->
                SegmentedListItem(
                    position = position,
                    enabled = !analysisResetting,
                    onClick = { if (!analysisResetting) showResetDialog = true },
                    leading = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    headline = { Text(if (analysisResetting) "正在重置…" else "重置完成标记…") },
                    supporting = { Text("清除完成标记后，下次分析会重新处理对应图片") },
                    trailing = if (analysisResetting) {
                        { LoadingIndicator(modifier = Modifier.size(28.dp)) }
                    } else {
                        null
                    },
                )
            }
        }

        // 重置在 appScope 后台跑（约 200 万行 UPDATE 需数十秒）：完成沿状态边沿提示
        var wasResetting by remember { mutableStateOf(false) }
        LaunchedEffect(analysisResetting) {
            if (wasResetting && !analysisResetting) notify("分析标记已重置")
            wasResetting = analysisResetting
        }
    }

    if (showResetDialog) {
        ResetAnalysisDialog(
            onDismiss = { showResetDialog = false },
            onLocalOnly = { showResetDialog = false; viewModel.resetLocalAnalysis() },
            onAll = { showResetDialog = false; viewModel.resetAllAnalysis() },
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
    val recommendEnabled by viewModel.recommendEnabled.collectAsState()
    val recommendLimit by viewModel.recommendLimit.collectAsState()
    val activePreset = CleanupPreset.match(config)

    SettingsDetailScaffold(SettingsCategory.CLEANUP, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "快捷预设") {
            item("preset") { position ->
                SegmentedCard(position) {
                    ConnectedChoiceRow(
                        options = CleanupPreset.entries.toList(),
                        selected = activePreset,
                        label = { it.displayName },
                        onSelect = viewModel::applyCleanupPreset,
                    )
                    SettingsBlurb(if (activePreset == null) "当前为自定义规则" else "预设会一次套用一组开关与门限，可在下方逐项微调")
                }
            }
        }

        SegmentedGroup(header = "每次审核数量") {
            item("limit") { position ->
                SegmentedCard(position) {
                    SettingsBlurb("每次「生成审核清单」最多纳入多少项（一个重复组算 1 项），按「分析」里的顺序选取。")
                    InstantSaveTextField(
                        value = reviewLimit.toString(),
                        label = "数量（${SettingsRepository.MIN_REVIEW_LIMIT}–${SettingsRepository.MAX_REVIEW_LIMIT}）",
                        keyboardType = KeyboardType.Number,
                        onSave = { input ->
                            input.filter { it.isDigit() }.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(SettingsRepository.MIN_REVIEW_LIMIT, SettingsRepository.MAX_REVIEW_LIMIT)
                                viewModel.setReviewLimit(clamped)
                                notify("已保存审核数量 $clamped")
                            }
                        },
                    )
                    TextButton(onClick = {
                        viewModel.setReviewLimit(SettingsRepository.DEFAULT_REVIEW_LIMIT)
                        notify("已恢复默认 ${SettingsRepository.DEFAULT_REVIEW_LIMIT}")
                    }) { Text("恢复默认（${SettingsRepository.DEFAULT_REVIEW_LIMIT}）") }
                }
            }
            item("all-dup") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "一次审核全部重复图片",
                    subtitle = "重复图片不受上面的数量限制，适合一次清完大量重复；其他维度仍按数量",
                    checked = allDuplicates,
                    onCheckedChange = {
                        viewModel.setCleanupAllDuplicates(it)
                        notify(if (it) "已开启：审核纳入全部重复图片" else "已关闭")
                    },
                )
            }
        }

        SegmentedGroup(header = "推荐删除（实验）") {
            item("recommend") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "推荐删除",
                    subtitle = "从你的清理和保留操作中学习偏好，把可能想删的普通图片也加入审核（显示为「推荐」）。需要先积累一些操作才会生效。",
                    checked = recommendEnabled,
                    onCheckedChange = {
                        viewModel.setRecommendEnabled(it)
                        notify(if (it) "已开启推荐删除" else "已关闭")
                    },
                )
            }
            if (recommendEnabled) {
                item("recommend-limit") { position ->
                    SegmentedCard(position) {
                        InstantSaveTextField(
                            value = recommendLimit.toString(),
                            label = "每次推荐数量（${SettingsRepository.MIN_RECOMMEND_LIMIT}–${SettingsRepository.MAX_RECOMMEND_LIMIT}）",
                            keyboardType = KeyboardType.Number,
                            onSave = { input ->
                                input.filter { it.isDigit() }.toIntOrNull()?.let {
                                    val v = it.coerceIn(SettingsRepository.MIN_RECOMMEND_LIMIT, SettingsRepository.MAX_RECOMMEND_LIMIT)
                                    viewModel.setRecommendLimit(v)
                                    notify("已保存推荐数量 $v")
                                }
                            },
                        )
                        TextButton(onClick = {
                            viewModel.resetDeleteModel()
                            notify("已重置推荐删除的学习记录")
                        }) { Text("重置学习记录") }
                    }
                }
            }
        }

        SegmentedGroup(header = "清理维度") {
            item("dup") { p ->
                SegmentedSwitchItem(p, "重复图片", config.duplicateEnabled, viewModel::setDuplicateEnabled, subtitle = "每组保留最好的一张，其余建议清理")
            }
            if (config.duplicateEnabled) {
                item("dup-detail") { position ->
                    SegmentedCard(position) {
                        Text("匹配严格度", style = MaterialTheme.typography.titleSmall)
                        ConnectedChoiceRow(
                            options = DuplicateStrictness.entries.toList(),
                            selected = duplicateStrictness,
                            label = {
                                when (it) {
                                    DuplicateStrictness.LOOSE -> "宽松"
                                    DuplicateStrictness.STANDARD -> "标准"
                                    DuplicateStrictness.STRICT -> "严格"
                                }
                            },
                            onSelect = viewModel::setDuplicateStrictness,
                        )
                        SettingsBlurb(
                            when (duplicateStrictness) {
                                DuplicateStrictness.LOOSE -> "找得更多，但可能把相似图误判为重复（修改后需重新查重）"
                                DuplicateStrictness.STANDARD -> "推荐：兼顾查全与准确（修改后需重新查重）"
                                DuplicateStrictness.STRICT -> "宁可漏掉也尽量不误判，适合截图较多的图库（修改后需重新查重）"
                            },
                        )
                        ThresholdSlider(
                            value = maxDupGroupSize.toFloat(),
                            valueRange = SettingsRepository.MIN_MAX_DUP_GROUP_SIZE.toFloat()..SettingsRepository.MAX_MAX_DUP_GROUP_SIZE.toFloat(),
                            onChange = { viewModel.setMaxDuplicateGroupSize(it.toInt()) },
                            format = { "视觉重复组最多 ${it.toInt()} 张，超出视为误聚并丢弃（修改后需重新查重）" },
                        )
                        TextButton(onClick = {
                            viewModel.resetDuplicateModel()
                            notify("已重置重复检测的学习记录")
                        }) { Text("重置查重学习记录") }
                    }
                }
            }
            item("emoji") { p ->
                SegmentedSwitchItem(p, "表情包", config.emojiEnabled, viewModel::setEmojiEnabled, subtitle = "接近正方形的小图和动图")
            }
            if (config.emojiEnabled) {
                item("emoji-detail") { position ->
                    SegmentedCard(position) {
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
                }
            }
            item("lowres") { p ->
                SegmentedSwitchItem(p, "低分辨率", config.lowResolutionEnabled, viewModel::setLowResolutionEnabled, subtitle = "总像素低于门限的图片")
            }
            if (config.lowResolutionEnabled) {
                item("lowres-detail") { position ->
                    SegmentedCard(position) {
                        ThresholdSlider(
                            value = config.lowResolutionMaxPixels.toFloat(),
                            valueRange = CleanupConfig.MIN_LOW_RES_MAX_PIXELS.toFloat()..CleanupConfig.MAX_LOW_RES_MAX_PIXELS.toFloat(),
                            onChange = { viewModel.setLowResolutionMaxPixels(it.toLong()) },
                            format = { "${String.format("%.1f", it / 1_000_000f)} MP 以下" },
                        )
                    }
                }
            }
            item("blur") { p ->
                SegmentedSwitchItem(p, "模糊", config.blurEnabled, viewModel::setBlurEnabled, subtitle = "清晰度低于门限的图片")
            }
            if (config.blurEnabled) {
                item("blur-detail") { position ->
                    SegmentedCard(position) {
                        ThresholdSlider(
                            value = config.blurMaxScore,
                            valueRange = CleanupConfig.MIN_BLUR_MAX_SCORE..CleanupConfig.MAX_BLUR_MAX_SCORE,
                            onChange = { viewModel.setBlurMaxScore(it) },
                            format = { "清晰度 ${it.toInt()} 以下" },
                        )
                    }
                }
            }
            item("tiny") { p ->
                SegmentedSwitchItem(p, "极小文件", config.tinyFileEnabled, viewModel::setTinyFileEnabled, subtitle = "文件大小低于门限的图片")
            }
            if (config.tinyFileEnabled) {
                item("tiny-detail") { position ->
                    SegmentedCard(position) {
                        ThresholdSlider(
                            value = config.tinyFileMaxBytes.toFloat(),
                            valueRange = CleanupConfig.MIN_TINY_FILE_MAX_BYTES.toFloat()..CleanupConfig.MAX_TINY_FILE_MAX_BYTES.toFloat(),
                            onChange = { viewModel.setTinyFileMaxBytes(it.toLong()) },
                            format = { "${(it / 1024).toInt()} KB 以下" },
                        )
                    }
                }
            }
            item("blank") { p ->
                SegmentedSwitchItem(p, "空白 / 纯色", config.blankEnabled, viewModel::setBlankEnabled, subtitle = "几乎纯色、没有内容的图片")
            }
            item("corrupt") { p ->
                SegmentedSwitchItem(p, "损坏", config.corruptEnabled, viewModel::setCorruptEnabled, subtitle = "无法解码的图片")
            }
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
    // 已启用状态下把地址改成公网：同样必须走隐私确认，否则「先启用再改地址」可绕过警告
    var showEndpointPrivacyDialog by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    SettingsDetailScaffold(SettingsCategory.AI, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "启用") {
            item("enable") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "启用 AI 分析",
                    subtitle = when (safety) {
                        AiPrivacyGuard.Safety.LOCALHOST -> "本机地址，数据不离开设备"
                        AiPrivacyGuard.Safety.LOCAL_NETWORK -> "局域网地址，数据不离开局域网"
                        AiPrivacyGuard.Safety.PUBLIC_INTERNET -> "公网地址：图片会上传到该服务，请注意隐私"
                        AiPrivacyGuard.Safety.INVALID -> "尚未填写服务地址"
                    },
                    subtitleColor = when (safety) {
                        AiPrivacyGuard.Safety.PUBLIC_INTERNET -> MaterialTheme.colorScheme.error
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
        }

        SegmentedGroup(header = "服务") {
            item("endpoint") { position ->
                SegmentedCard(position) {
                    InstantSaveTextField(
                        value = state.aiEndpoint,
                        label = "服务地址（OpenAI 兼容）",
                        placeholder = "https://api.openai.com 或 http://192.168.1.100:11434",
                        supporting = "填根地址或带 /v1 都可以。本地服务（LM Studio、Ollama 等）可以不填密钥。",
                        onSave = {
                            viewModel.setAiEndpoint(it)
                            notify("已保存服务地址")
                            if (state.aiEnabled && AiPrivacyGuard.classify(it.trim()) == AiPrivacyGuard.Safety.PUBLIC_INTERNET) {
                                showEndpointPrivacyDialog = true
                            }
                        },
                    )
                }
            }
            item("key") { position ->
                SegmentedCard(position) {
                    InstantSaveTextField(
                        value = state.aiApiKey,
                        label = "API 密钥（本地服务可留空）",
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }, shapes = IconButtonDefaults.shapes()) {
                                Icon(
                                    if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (keyVisible) "隐藏" else "显示",
                                )
                            }
                        },
                        onSave = { viewModel.setAiApiKey(it); notify("已保存 API 密钥") },
                    )
                }
            }
            item("model") { position ->
                SegmentedCard(position) {
                    InstantSaveTextField(
                        value = state.aiModel,
                        label = "对话模型",
                        supporting = "给图片打标签需要支持视觉输入的多模态模型，纯文本模型会失败。",
                        onSave = { viewModel.setAiModel(it); notify("已保存对话模型") },
                    )
                }
            }
            item("embedding") { position ->
                SegmentedCard(position) {
                    InstantSaveTextField(
                        value = state.aiEmbeddingModel,
                        label = "向量模型（Embedding）",
                        onSave = { viewModel.setAiEmbeddingModel(it); notify("已保存向量模型") },
                    )
                }
            }
        }

        SegmentedGroup(header = "推理与性能") {
            item("thinking") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "过滤模型思考过程",
                    subtitle = "请求时关闭思考，并去掉回复中的 <think> 内容；使用思考模型时请保持开启",
                    checked = disableThinking,
                    onCheckedChange = { viewModel.setAiDisableThinking(it); notify(if (it) "已开启思考过滤" else "已关闭思考过滤") },
                )
            }
            item("tokens") { position ->
                SegmentedCard(position) {
                    InstantSaveTextField(
                        value = maxTokens.toString(),
                        label = "最大 token（${SettingsRepository.MIN_AI_MAX_TOKENS}–${SettingsRepository.MAX_AI_MAX_TOKENS}）",
                        supporting = "单次回复的 token 上限。思考模型会先消耗大量 token，过小会导致正文为空、打标签失败，建议 ≥ 1536。",
                        keyboardType = KeyboardType.Number,
                        onSave = { input ->
                            input.filter { it.isDigit() }.toIntOrNull()?.let { value ->
                                val clamped = value.coerceIn(SettingsRepository.MIN_AI_MAX_TOKENS, SettingsRepository.MAX_AI_MAX_TOKENS)
                                viewModel.setAiMaxTokens(clamped)
                                notify("已保存最大 token $clamped")
                            }
                        },
                    )
                }
            }
            item("concurrency") { position ->
                SegmentedCard(position) {
                    var sliderValue by remember(concurrency) { mutableStateOf(concurrency.toFloat()) }
                    Text("同时请求数：${sliderValue.toInt()}", style = MaterialTheme.typography.titleSmall)
                    SettingsBlurb("AI 标签和向量阶段同时处理多少张图片。本地推理服务建议 1–4。")
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = SettingsRepository.MIN_AI_CONCURRENCY.toFloat()..SettingsRepository.MAX_AI_CONCURRENCY.toFloat(),
                        steps = (SettingsRepository.MAX_AI_CONCURRENCY - SettingsRepository.MIN_AI_CONCURRENCY - 1).coerceAtLeast(0),
                        onValueChangeFinished = {
                            viewModel.setAiConcurrency(sliderValue.toInt())
                            notify("同时请求数已设为 ${sliderValue.toInt()}")
                        },
                    )
                }
            }
        }
    }

    if (showPrivacyDialog) {
        PrivacyWarningDialog(
            onConfirm = {
                showPrivacyDialog = false
                // 确认标记持久化：上传链路据此放行公网上传
                viewModel.acknowledgePublicAiEndpoint()
                viewModel.setAiEnabled(true)
            },
            onDismiss = { showPrivacyDialog = false },
        )
    }

    if (showEndpointPrivacyDialog) {
        // 拒绝（含点外部关闭）按安全侧处理：关闭 AI 分析，公网地址保留但不再被使用
        PrivacyWarningDialog(
            onConfirm = {
                showEndpointPrivacyDialog = false
                viewModel.acknowledgePublicAiEndpoint()
            },
            onDismiss = {
                showEndpointPrivacyDialog = false
                viewModel.setAiEnabled(false)
            },
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
    val sharedTransition by viewModel.detailSharedTransition.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    SettingsDetailScaffold(SettingsCategory.PERSONALIZATION, onBack, sharedScope, animatedScope) { _ ->
        SegmentedGroup(header = "外观") {
            item("dynamic") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "动态取色",
                    subtitle = "Android 12 及以上跟随壁纸取色；关闭后使用 QAlbum 品牌色",
                    checked = state.dynamicColorEnabled,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
        }
        SegmentedGroup(header = "动效") {
            item("shared") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "缩略图与大图连贯过渡",
                    subtitle = "从图库打开图片时，缩略图直接放大成全屏（实验性）",
                    checked = sharedTransition,
                    onCheckedChange = viewModel::setDetailSharedTransition,
                )
            }
        }
        SegmentedGroup(header = "导出相册目录") {
            item("hint") { position ->
                SegmentedCard(position) {
                    SettingsBlurb("保存图片时可以选择目录，实际存放在 Pictures/QAlbum/<目录名>。保存弹窗里也能临时新建。")
                }
            }
            exportSubdirs.forEach { dir ->
                item("dir-$dir") { position ->
                    SegmentedListItem(
                        position = position,
                        headline = { Text(dir) },
                        supporting = { Text("Pictures/QAlbum/$dir") },
                        trailing = {
                            IconButton(onClick = { viewModel.removeExportSubdir(dir) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除目录")
                            }
                        },
                    )
                }
            }
            item("add") { position ->
                SegmentedCard(position) {
                    var newDir by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newDir,
                            onValueChange = { newDir = it },
                            placeholder = { Text("新建目录名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = { viewModel.addExportSubdir(newDir); newDir = "" },
                            enabled = newDir.isNotBlank(),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("添加") }
                    }
                }
            }
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

    // Shizuku binder / 授权状态变化（授权对话框是异步回调）后刷新连通性结论，
    // 避免停留在授权前测出的过期结果；未测试过则保持「尚未测试」
    LaunchedEffect(shizukuStatus.state) {
        if (accessStatus != AccessStatus.Unknown) viewModel.checkAccess()
    }

    SettingsDetailScaffold(SettingsCategory.ACCESS, onBack, sharedScope, animatedScope) { _ ->
        SegmentedGroup(header = "访问方式") {
            item("mode") { position ->
                SegmentedCard(position) {
                    SettingsBlurb("QAlbum 通过 Shizuku 或 Root 读取 QQ 的聊天图片目录；「聊天来源」功能需要 Root。")
                    ConnectedChoiceRow(
                        options = listOf(AccessMode.SHIZUKU, AccessMode.ROOT),
                        selected = state.accessMode,
                        label = {
                            if (it == AccessMode.SHIZUKU) "Shizuku" else "Root"
                        },
                        onSelect = viewModel::setAccessMode,
                    )
                }
            }
            item("shizuku") { position ->
                ShizukuStatusItem(
                    position = position,
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
        }

        SegmentedGroup(header = "连通性") {
            item("check") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = { viewModel.checkAccess() },
                    leading = {
                        Icon(
                            when (accessStatus) {
                                AccessStatus.Ready -> Icons.Outlined.CheckCircle
                                AccessStatus.NotReady -> Icons.Outlined.ErrorOutline
                                AccessStatus.Unknown -> Icons.Outlined.Security
                            },
                            contentDescription = null,
                            tint = when (accessStatus) {
                                AccessStatus.Ready -> MaterialTheme.colorScheme.primary
                                AccessStatus.NotReady -> MaterialTheme.colorScheme.error
                                AccessStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headline = { Text(stringResource(R.string.settings_test_access)) },
                    supporting = {
                        Text(
                            when (accessStatus) {
                                AccessStatus.Ready -> "可以访问图片目录"
                                AccessStatus.NotReady -> "无法访问：请检查授权与访问方式"
                                AccessStatus.Unknown -> "尚未测试，点按开始"
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    SettingsDetailScaffold(SettingsCategory.ABOUT, onBack, sharedScope, animatedScope) { _ ->
        Spacer(Modifier.height(8.dp))
        SegmentedGroup {
            item("app") { position ->
                SegmentedCard(position, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShapeIcon(
                        Icons.Outlined.PhotoLibrary,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        size = 72.dp,
                        iconSize = 34.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("QAlbum", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("version") { position ->
                SegmentedListItem(
                    position = position,
                    headline = { Text(stringResource(R.string.settings_version)) },
                    supporting = { Text("${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）") },
                )
            }
            item("license") { position ->
                SegmentedListItem(
                    position = position,
                    headline = { Text("开源许可") },
                    supporting = { Text("GPLv3") },
                )
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

/** 说明性文字 */
@Composable
private fun SettingsBlurb(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 即时保存文本框：本地编辑，失焦 / 按键盘「完成」/ 离开页面（dispose）时若内容有变就调用 [onSave]。
 *
 * 只在失焦时提交的话，用户直接点返回往往不会触发失焦，输入就丢了；这里补充 dispose 兜底提交。
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
    val commit = { if (text != value) onSave(text) }
    // dispose 兜底提交：用最新的 text / value / onSave，避免离开页面时丢失编辑
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnSave by rememberUpdatedState(onSave)
    DisposableEffect(Unit) {
        onDispose { if (latestText != latestValue) latestOnSave(latestText) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
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

/** 分析阶段数量输入：留空 / 0 = 不限；其余为正整数上限 */
@Composable
private fun PhaseLimitField(label: String, limit: Int, onSave: (Int) -> Unit) {
    InstantSaveTextField(
        value = if (limit <= 0) "" else limit.toString(),
        label = label,
        placeholder = "不限",
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
        downloading -> (downloadState as OcrModelDownloadState.Downloading).message
        downloadState is OcrModelDownloadState.Failed -> downloadState.reason
        status is OcrModelStatus.Ready -> "${status.manifest.displayName} ${status.manifest.version}"
        status is OcrModelStatus.Failed -> status.reason
        else -> "从 Paddle 官方模型源下载 PP-OCR ONNX 模型；未安装时会跳过文字识别。"
    }
    val failed = status is OcrModelStatus.Failed || downloadState is OcrModelDownloadState.Failed
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (downloading) {
            WavyProgress(fraction = (downloadState as OcrModelDownloadState.Downloading).progress)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status is OcrModelStatus.Ready) {
                OutlinedButton(onClick = onDelete, enabled = !downloading, shapes = ButtonDefaults.shapes()) { Text("删除模型") }
            } else {
                Button(onClick = onDownload, enabled = !downloading, shapes = ButtonDefaults.shapes()) {
                    Text(if (downloading) "下载中…" else "下载模型")
                }
            }
            TextButton(onClick = onRefresh, enabled = !downloading) { Text("刷新状态") }
        }
    }
}

@Composable
private fun ShizukuStatusItem(
    position: SegmentPosition,
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
    SegmentedListItem(
        position = position,
        leading = {
            Icon(
                if (status.state == ShizukuState.Granted) Icons.Outlined.CheckCircle else Icons.Outlined.PrivacyTip,
                contentDescription = null,
                tint = if (status.state == ShizukuState.Granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headline = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supporting = if (status.versionCode > 0) {
            { Text("Shizuku v${status.versionCode}") }
        } else {
            null
        },
        trailing = action?.let { label ->
            {
                FilledTonalButton(
                    onClick = {
                        when (status.state) {
                            ShizukuState.NotInstalled -> onInstall()
                            ShizukuState.PermissionDenied -> onRequest()
                            else -> Unit
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(label) }
            }
        },
    )
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
        Text(format(sliderValue), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
        icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
        title = { Text("重置分析完成标记") },
        text = {
            Text(
                "选择重置范围：\n\n· 仅本地分析：重新计算哈希、画质、重复与评分（免费，在本机完成）。\n" +
                    "· 全部：连同 OCR、AI 标签和向量一起清空重跑；AI 与向量会再次调用服务，可能产生费用。",
            )
        },
        confirmButton = { TextButton(onClick = onLocalOnly) { Text("仅本地分析") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onAll) { Text("全部", color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

@Composable
private fun PrivacyWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
        title = { Text("隐私提示") },
        text = { Text(AiPrivacyGuard.PRIVACY_WARNING.trim()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
