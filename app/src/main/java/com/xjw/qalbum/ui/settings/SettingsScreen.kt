package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.R
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.ThemeSettings
import com.xjw.qalbum.ui.anim.StaggeredEntrance
import com.xjw.qalbum.ui.anim.pressScale
import com.xjw.qalbum.ui.common.formatBytes
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.theme.supportsWallpaperColors
import org.koin.androidx.compose.koinViewModel

/** 设置首页的分组 */
enum class SettingsGroup(val title: String) {
    FILES("文件与清理"),
    DISPLAY("显示"),
    APP("应用"),
}

/** 设置的二级页：每项一个路由，首页按 [group] 聚合入口（文件访问的入口是首页顶部的状态卡片） */
enum class SettingsCategory(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val group: SettingsGroup?,
) {
    ACCESS("settings_access", "文件访问", Icons.Outlined.Key, null),
    SCAN("settings_scan", "扫描", Icons.Outlined.FolderOpen, SettingsGroup.FILES),
    CLEANUP("settings_cleanup", "清理与回收站", Icons.Outlined.CleaningServices, SettingsGroup.FILES),
    COMPRESS("settings_compress", "原图压缩", Icons.Outlined.Compress, SettingsGroup.FILES),
    APPEARANCE("settings_appearance", "外观", Icons.Outlined.Palette, SettingsGroup.DISPLAY),
    GALLERY("settings_gallery", "图库", Icons.Outlined.PhotoLibrary, SettingsGroup.DISPLAY),
    STORAGE("settings_storage", "存储空间", Icons.Outlined.DataUsage, SettingsGroup.APP),
    LOGS("settings_logs", "日志", Icons.AutoMirrored.Outlined.Article, SettingsGroup.APP),
    ABOUT("settings_about", "关于", Icons.Outlined.Info, SettingsGroup.APP),
}

/** 设置首页：顶部是文件访问的状态卡片，下面按组列出各二级页，副标题显示当前的设置 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenCategory: (SettingsCategory) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val access by viewModel.access.collectAsState()
    val mode by viewModel.accessMode.collectAsState()
    val advanced by viewModel.advancedFeatures.collectAsState()
    val path by viewModel.chatpicPath.collectAsState()
    val retention by viewModel.quarantineRetentionDays.collectAsState()
    val scheduled by viewModel.scheduledCleanup.collectAsState()
    val quality by viewModel.compressQuality.collectAsState()
    val compare by viewModel.compressCompare.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val stack by viewModel.galleryStackVariants.collectAsState()
    val exportDirs by viewModel.exportSubdirs.collectAsState()
    val footprint by viewModel.footprint.collectAsState()
    val detailedLog by viewModel.detailedLog.collectAsState()
    // 每次回到设置首页都重新检测（可能刚在文件访问页换了方式或授了权）
    LaunchedEffect(mode) { viewModel.checkAccess(mode) }

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
                .padding(top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            StaggeredEntrance(0) {
                AccessStatusCard(
                    mode = mode,
                    advanced = advanced,
                    access = access?.takeIf { it.mode == mode },
                    onClick = { onOpenCategory(SettingsCategory.ACCESS) },
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                )
            }
            val subtitles = mapOf(
                SettingsCategory.SCAN to scanSummary(path),
                SettingsCategory.CLEANUP to cleanupSummary(retention, scheduled.size),
                SettingsCategory.COMPRESS to "质量 $quality%" + if (compare) " · 先对比" else " · 直接替换",
                SettingsCategory.APPEARANCE to appearanceSummary(theme),
                SettingsCategory.GALLERY to listOfNotNull(
                    if (stack) "合并版本" else "不合并版本",
                    exportDirs.size.takeIf { it > 0 }?.let { "$it 个保存目录" },
                ).joinToString(" · "),
                SettingsCategory.STORAGE to (footprint?.let { "数据库 ${formatBytes(it.databaseBytes)}" } ?: "数据库、图片缓存"),
                SettingsCategory.LOGS to listOfNotNull(
                    footprint?.let { "运行日志 ${formatBytes(it.logBytes)}" } ?: "运行日志",
                    "详细日志".takeIf { detailedLog },
                ).joinToString(" · "),
                SettingsCategory.ABOUT to "版本 ${BuildConfig.VERSION_NAME.substringBeforeLast('.')}",
            )
            SettingsGroup.entries.forEachIndexed { groupIndex, group ->
                StaggeredEntrance(groupIndex + 1) {
                    SegmentedGroup(header = group.title) {
                        SettingsCategory.entries.filter { it.group == group }.forEach { category ->
                            item(category.route) { position ->
                                SettingsEntryItem(
                                    category = category,
                                    subtitle = subtitles[category],
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
    subtitle: String?,
    position: SegmentPosition,
    onClick: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val interaction = remember { MutableInteractionSource() }
    val (container, content) = groupColors(category.group ?: SettingsGroup.FILES)
    SegmentedListItem(
        position = position,
        interactionSource = interaction,
        modifier = Modifier
            .settingsContainer(settingsSharedKey(category.route), sharedScope, animatedScope)
            .pressScale(interaction),
        onClick = onClick,
        leading = { ShapeIcon(category.icon, containerColor = container, contentColor = content) },
        headline = { Text(category.title) },
        supporting = subtitle?.let { { Text(it, maxLines = 1) } },
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
    )
}

/**
 * 文件访问的状态卡片（文件访问页的入口）：用哪种方式、能不能用；有问题时换成提醒色，一眼看出要去授权。
 */
@Composable
private fun AccessStatusCard(
    mode: AccessMode,
    advanced: Boolean,
    access: AccessState?,
    onClick: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val problem = access != null && !access.checking && !access.ready
    val container = if (problem) colors.errorContainer else colors.primaryContainer
    val content = if (problem) colors.onErrorContainer else colors.onPrimaryContainer
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .settingsContainer(settingsSharedKey(SettingsCategory.ACCESS.route), sharedScope, animatedScope)
            .pressScale(interaction),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (access == null || access.checking) {
                LoadingIndicator(Modifier.padding(4.dp), color = content)
            } else {
                ShapeIcon(
                    SettingsCategory.ACCESS.icon,
                    containerColor = if (problem) colors.error else colors.primary,
                    contentColor = if (problem) colors.onError else colors.onPrimary,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    size = 56.dp,
                    iconSize = 26.dp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "文件访问 · " + (if (mode == AccessMode.ROOT) "Root" else "Shizuku") + if (advanced) " · 高级功能" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = content.copy(alpha = 0.8f),
                )
                AnimatedContent(
                    targetState = accessHeadline(access),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "accessHeadline",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.titleLargeEmphasized)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** 状态卡片的大字 */
internal fun accessHeadline(access: AccessState?): String = when {
    access == null || access.checking -> "检测中…"
    access.shizuku?.state == ShizukuState.NotInstalled -> "没有安装 Shizuku"
    access.shizuku?.state == ShizukuState.NotRunning -> "Shizuku 没有运行"
    access.shizuku?.state == ShizukuState.PermissionDenied -> "还没有授权"
    access.granted == false -> if (access.mode == AccessMode.ROOT) "没有 Root 权限" else "连不上 Shizuku"
    access.folderFound == false -> "找不到图片目录"
    access.folderReadable == false -> "没有目录读取权限"
    else -> "可以使用"
}

/** 首页副标题：图片目录 */
internal fun scanSummary(path: String): String =
    if (path == SettingsRepository.DEFAULT_CHATPIC_PATH) "QQ 默认目录" else path.trimEnd('/').substringAfterLast('/').ifBlank { path }

internal fun cleanupSummary(retentionDays: Int, scheduledRules: Int): String =
    "回收站保留 $retentionDays 天 · " + if (scheduledRules > 0) "每周清理 $scheduledRules 项" else "不定时清理"

internal fun appearanceSummary(theme: ThemeSettings): String =
    themeModeLabel(theme.mode) + " · " + if (theme.dynamicColor && supportsWallpaperColors) "壁纸配色" else "自选颜色"
