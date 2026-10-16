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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.R
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
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
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 设置分组：首页按分组聚合二级入口 */
enum class SettingsGroup(val title: String) {
    DATA("数据与清理"),
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
    CLEANUP("settings_cleanup", "清理", "回收站、原图压缩与定时清理", Icons.Outlined.CleaningServices, SettingsGroup.DATA),
    PERSONALIZATION("settings_personalization", "外观与导出", "取色、图库显示、动效与导出目录", Icons.Outlined.Palette, SettingsGroup.SYSTEM),
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
                        supporting = "TIM、国际版 QQ 的目录不同，按需修改。",
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
                        "增量扫描只看有变化的目录，几秒完成；全量扫描核对全部文件，能发现已删除的。",
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
                    supporting = { Text("重读全部文件头，修正格式误判。较慢，识别有误时再用。") },
                )
            }
        }
    }
}

@Composable
fun CleanupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val retentionDays by viewModel.quarantineRetentionDays.collectAsState()
    val maxSide by viewModel.compressMaxSide.collectAsState()
    val keepOriginal by viewModel.compressKeepOriginal.collectAsState()
    val quality by viewModel.compressQuality.collectAsState()
    val compare by viewModel.compressCompare.collectAsState()
    val demo by viewModel.compressDemoResult.collectAsState()
    val scheduled by viewModel.scheduledCleanup.collectAsState()
    val scheduledRules by viewModel.scheduledRules.collectAsState()
    val scheduledLast by viewModel.scheduledCleanupLast.collectAsState()

    SettingsDetailScaffold(SettingsCategory.CLEANUP, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "回收站") {
            item("retention") { position ->
                SegmentedCard(position) {
                    Text("保留天数", style = MaterialTheme.typography.titleSmall)
                    ConnectedChoiceRow(
                        options = RETENTION_OPTIONS,
                        selected = retentionDays.takeIf { it in RETENTION_OPTIONS },
                        label = { "$it 天" },
                        onSelect = { days ->
                            viewModel.setQuarantineRetentionDays(days)
                            notify("回收站保留 $days 天")
                        },
                    )
                    SettingsBlurb("回收站里的图片和压缩前的原图到期自动删除，只对之后移入的生效。")
                }
            }
        }
        SegmentedGroup(header = "原图压缩") {
            item("quality") { position ->
                SegmentedCard(position) {
                    Text("压缩质量", style = MaterialTheme.typography.titleSmall)
                    ConnectedChoiceRow(
                        options = SettingsRepository.COMPRESS_QUALITY_OPTIONS,
                        selected = quality.takeIf { it in SettingsRepository.COMPRESS_QUALITY_OPTIONS },
                        label = { "$it%" },
                        onSelect = { value ->
                            viewModel.setCompressQuality(value)
                            notify("压缩质量 $value%")
                        },
                    )
                    SettingsBlurb("越低越省空间，也越模糊。推荐 80%；60% 以下放大能看出色块。")
                    CompressQualityDemo(demo?.takeIf { it.quality == quality })
                }
            }
            item("compare") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "压缩后先对比",
                    subtitle = if (compare) "先看前后对比和省下的空间，确认后才替换"
                    else "压缩后直接替换原图",
                    checked = compare,
                    onCheckedChange = viewModel::setCompressCompare,
                )
            }
            item("max-side") { position ->
                SegmentedCard(position) {
                    Text("压缩后的长边", style = MaterialTheme.typography.titleSmall)
                    ConnectedChoiceRow(
                        options = MAX_SIDE_OPTIONS,
                        selected = maxSide.takeIf { it in MAX_SIDE_OPTIONS },
                        label = { if (it == 0) "不缩小" else "$it" },
                        onSelect = { side ->
                            viewModel.setCompressMaxSide(side)
                            notify(if (side == 0) "只重新编码，不缩小分辨率" else "长边缩到 $side 像素")
                        },
                    )
                    SettingsBlurb("2560 足够看清手机照片；「不缩小」省得少些。不对比时，省不到一成的图不替换。")
                }
            }
            item("keep") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "压缩时保留原图",
                    subtitle = if (keepOriginal) "原图暂存回收站，到期前可以还原"
                    else "压缩后立即释放空间，无法还原",
                    checked = keepOriginal,
                    onCheckedChange = viewModel::setCompressKeepOriginal,
                )
            }
        }
        SegmentedGroup(header = "定时清理") {
            item("scheduled-info") { position ->
                SegmentedCard(position) {
                    SettingsBlurb(
                        "每周自动把选中规则的图移入回收站，参数用规则页上选的。" +
                            (scheduledLast?.let { "\n上次：$it" } ?: ""),
                    )
                }
            }
            scheduledRules.forEach { (rule, option) ->
                item("scheduled-${rule.id}") { position ->
                    SegmentedSwitchItem(
                        position = position,
                        title = rule.title,
                        subtitle = option.ifBlank { null },
                        checked = rule.id in scheduled,
                        onCheckedChange = { on -> viewModel.setScheduledCleanup(rule.id, on) },
                    )
                }
            }
        }
    }
}

private val RETENTION_OPTIONS = listOf(7, 15, 30, 60)
private val MAX_SIDE_OPTIONS = listOf(2560, 3840, 0)

@Composable
fun PersonalizationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val sharedTransition by viewModel.detailSharedTransition.collectAsState()
    val stackVariants by viewModel.galleryStackVariants.collectAsState()
    val fastScroll by viewModel.fastScrollEnabled.collectAsState()
    val archiveVisible by viewModel.archiveVisible.collectAsState()
    val exportSubdirs by viewModel.exportSubdirs.collectAsState()
    SettingsDetailScaffold(SettingsCategory.PERSONALIZATION, onBack, sharedScope, animatedScope) { _ ->
        SegmentedGroup(header = "外观") {
            item("dynamic") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "动态取色",
                    subtitle = "跟随壁纸配色（Android 12+），关闭则用默认配色",
                    checked = state.dynamicColorEnabled,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
        }
        SegmentedGroup(header = "图库") {
            item("stack") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "合并同一张图的多个版本",
                    subtitle = "原图、预览图、缩略图合成一张显示，大小合计、一起删除，看图时可切换版本",
                    checked = stackVariants,
                    onCheckedChange = viewModel::setGalleryStackVariants,
                )
            }
            item("fastScroll") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "快速滚动条",
                    subtitle = "滚动时右侧出现把手，拖动快速定位",
                    checked = fastScroll,
                    onCheckedChange = viewModel::setFastScrollEnabled,
                )
            }
            item("archiveVisible") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "归档的图片也在图库中显示",
                    subtitle = "关闭时只在「图库 → 归档」里显示；无论开关，归档的图都不会被清理",
                    checked = archiveVisible,
                    onCheckedChange = viewModel::setArchiveVisible,
                )
            }
        }
        SegmentedGroup(header = "动效") {
            item("shared") { position ->
                SegmentedSwitchItem(
                    position = position,
                    title = "缩略图与大图连贯过渡",
                    subtitle = "打开图片时缩略图放大成全屏（实验性）",
                    checked = sharedTransition,
                    onCheckedChange = viewModel::setDetailSharedTransition,
                )
            }
        }
        SegmentedGroup(header = "导出相册目录") {
            item("hint") { position ->
                SegmentedCard(position) {
                    SettingsBlurb("保存图片时可选目录（Pictures/QAlbum/<目录名>），弹窗里也能新建。")
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
                    SettingsBlurb("通过 Shizuku 或 Root 读取 QQ 图片目录；「聊天来源」需要 Root。")
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
                                AccessStatus.NotReady -> "无法访问，请检查授权"
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
