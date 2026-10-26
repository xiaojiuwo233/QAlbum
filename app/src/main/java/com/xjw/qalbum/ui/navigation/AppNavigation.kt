package com.xjw.qalbum.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.anim.aboveSharedImages
import com.xjw.qalbum.ui.anim.fadeThroughEnter
import com.xjw.qalbum.ui.anim.fadeThroughExit
import com.xjw.qalbum.ui.anim.imageDetailEnter
import com.xjw.qalbum.ui.anim.imageDetailExit
import com.xjw.qalbum.ui.anim.sharedAxisXEnter
import com.xjw.qalbum.ui.anim.sharedAxisXExit
import com.xjw.qalbum.ui.anim.sharedAxisXPopEnter
import com.xjw.qalbum.ui.anim.sharedAxisXPopExit
import com.xjw.qalbum.ui.cleanup.CleanupScreen
import com.xjw.qalbum.ui.dashboard.DashboardScreen
import com.xjw.qalbum.ui.detail.ImageDetailScreen
import com.xjw.qalbum.ui.gallery.ArchiveScreen
import com.xjw.qalbum.ui.gallery.GalleryScreen
import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.ui.logs.LogScreen
import com.xjw.qalbum.ui.onboarding.OnboardingScreen
import com.xjw.qalbum.ui.onboarding.needsOnboarding
import com.xjw.qalbum.ui.settings.AboutSettingsScreen
import com.xjw.qalbum.ui.settings.AccessSettingsScreen
import com.xjw.qalbum.ui.settings.AppearanceSettingsScreen
import com.xjw.qalbum.ui.settings.CleanupSettingsScreen
import com.xjw.qalbum.ui.settings.CompressSettingsScreen
import com.xjw.qalbum.ui.settings.GallerySettingsScreen
import com.xjw.qalbum.ui.settings.ScanSettingsScreen
import com.xjw.qalbum.ui.settings.SettingsCategory
import com.xjw.qalbum.ui.settings.SettingsScreen
import com.xjw.qalbum.ui.settings.StorageSettingsScreen
import com.xjw.qalbum.ui.sources.QqSourceBrowseScreen
import com.xjw.qalbum.ui.sources.QqSourcesScreen
import com.xjw.qalbum.ui.sources.SourceBrowseTarget
import org.koin.compose.koinInject

/**
 * 应用根导航：底部 M3 Expressive 短导航栏（ShortNavigationBar）+ Navigation Compose。
 *
 * 关键设计：
 * - **inset**：NavHost 永远铺满，不随底栏显隐临时加/去 padding；每页用自己的顶栏避让状态栏。
 * - **转场**：
 *   - 一级 Tab 之间 → Fade Through；
 *   - 设置首页 ↔ 设置二级页、图库 ↔ 归档 → Container Transform（sharedBounds）；
 *   - 其它二级页（详情、QQ 来源…）→ Shared Axis X；
 *   - 底栏在进入二级页、清理子页、图库多选时滑出，返回时滑入。系统手势走预测式返回。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation() {
    val settings = koinInject<SettingsRepository>()
    val imageDao = koinInject<ImageDao>()
    // 第一次打开（库里还没有图）先走引导；判断完之前不画，免得先闪一下概览
    val startRoute by produceState<String?>(null) {
        value = if (needsOnboarding(settings, imageDao)) ONBOARDING_ROUTE else NavDestination.Dashboard.route
    }
    val start = startRoute ?: return
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = NavDestination.fromRoute(currentRoute)
    // 规则预览 / 按时间 / 回收站是清理页内部子页；图库多选时底部换成浮动操作栏 —— 两种情况都隐藏底栏
    var cleanupSubPage by remember { mutableStateOf(false) }
    var gallerySelecting by remember { mutableStateOf(false) }
    val showBottomBar = currentRoute in bottomBarRoutes &&
        !(currentRoute == NavDestination.Cleanup.route && cleanupSubPage) &&
        !(currentRoute == NavDestination.Gallery.route && gallerySelecting)
    val density = LocalDensity.current
    val secondaryPadding = WindowInsets.navigationBars.asPaddingValues()
    var bottomBarHeight by remember(density) { mutableStateOf(64.dp + secondaryPadding.calculateBottomPadding()) }
    // 页面底部留白不随底栏逐帧滑入/滑出改变，网格与转场起止位置保持稳定
    val contentPadding = PaddingValues(bottom = bottomBarHeight)

    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scaleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // 看图转场（设置里的「优雅动画」）：true=从缩略图放大、返回时缩回格子，false=缩放进入 + 图库立即垫底
    val sharedTransition by settings.detailSharedTransition.collectAsState(initial = true)
    // 图库 / 归档 ↔ 大图的转场进行中（含预测性返回拖动中）：这时顶栏、底栏画到飞行的图上面
    val visibleEntries by navController.visibleEntries.collectAsState()
    val imageTransition = sharedTransition && visibleEntries.size > 1 && visibleEntries.any { it.isImage() }

    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = start,
                enterTransition = {
                    when {
                        targetState.isImage() -> if (sharedTransition) fadeIn(fadeSpec) else imageDetailEnter(fadeSpec, scaleSpec)
                        initialState.isImage() -> fadeIn(fadeSpec)
                        isContainerTransition() -> fadeIn(fadeSpec)
                        involvesSecondary() -> sharedAxisXEnter(slideSpec, fadeSpec)
                        else -> fadeThroughEnter()
                    }
                },
                exitTransition = {
                    when {
                        initialState.isImage() -> if (sharedTransition) fadeOut(fadeSpec) else imageDetailExit(fadeSpec, scaleSpec)
                        targetState.isImage() -> fadeOut(fadeSpec)
                        isContainerTransition() -> fadeOut(fadeSpec)
                        involvesSecondary() -> sharedAxisXExit(slideSpec, fadeSpec)
                        else -> fadeThroughExit()
                    }
                },
                popEnterTransition = {
                    when {
                        // 从详情返回：关闭共享元素时图库立即垫底（详情缩小淡出露出它，避免黑屏）；
                        // 开启时图库淡入，配合共享元素把大图缩回缩略图
                        initialState.isImage() -> if (sharedTransition) fadeIn(fadeSpec) else EnterTransition.None
                        targetState.isImage() -> if (sharedTransition) fadeIn(fadeSpec) else imageDetailEnter(fadeSpec, scaleSpec)
                        isContainerTransition() -> fadeIn(fadeSpec)
                        involvesSecondary() -> sharedAxisXPopEnter(slideSpec, fadeSpec)
                        else -> fadeThroughEnter()
                    }
                },
                popExitTransition = {
                    when {
                        initialState.isImage() -> if (sharedTransition) fadeOut(fadeSpec) else imageDetailExit(fadeSpec, scaleSpec)
                        targetState.isImage() -> fadeOut(fadeSpec)
                        isContainerTransition() -> fadeOut(fadeSpec)
                        involvesSecondary() -> sharedAxisXPopExit(slideSpec, fadeSpec)
                        else -> fadeThroughExit()
                    }
                },
            ) {
                page(ONBOARDING_ROUTE) {
                    OnboardingScreen(
                        onDone = {
                            // 从概览 /「关于」打开的回到原页；第一次打开的进概览
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(NavDestination.Dashboard.route) {
                                    popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                                }
                            }
                        },
                    )
                }
                page(NavDestination.Dashboard.route) {
                    DashboardScreen(
                        contentPadding = contentPadding,
                        onOpenAccessSettings = { navController.navigate(SettingsCategory.ACCESS.route) },
                        onOpenGuide = { navController.navigate(ONBOARDING_ROUTE) },
                        onOpenSources = { navController.navigate(QQ_SOURCES_ROUTE) },
                    )
                }
                page(NavDestination.Gallery.route) {
                    GalleryScreen(
                        contentPadding = contentPadding,
                        onImageClick = { id -> navController.navigate("image/$id") },
                        onOpenArchive = { navController.navigate("archive") },
                        onOpenSources = { navController.navigate(QQ_SOURCES_ROUTE) },
                        onSelectionModeChange = { gallerySelecting = it },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                        imageShared = sharedTransition,
                        imageTransition = imageTransition,
                    )
                }
                page("archive") {
                    ArchiveScreen(
                        contentPadding = secondaryPadding,
                        onBack = { navController.popBackStack() },
                        onImageClick = { id -> navController.navigate("archive/image/$id") },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                        imageShared = sharedTransition,
                        imageTransition = imageTransition,
                    )
                }
                page(QQ_SOURCES_ROUTE) {
                    QqSourcesScreen(
                        onBack = { navController.popBackStack() },
                        onBrowse = { target -> navController.navigate(target.route()) },
                    )
                }
                page(
                    route = "$QQ_SOURCES_ROUTE/{kind}/{key}",
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType },
                        navArgument("key") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val target = SourceBrowseTarget.fromArgs(
                        entry.arguments?.getString("kind"),
                        entry.arguments?.getString("key"),
                    )
                    QqSourceBrowseScreen(
                        target = target,
                        onBack = { navController.popBackStack() },
                        onBrowse = { next -> navController.navigate(next.route()) },
                    )
                }
                page(NavDestination.Cleanup.route) {
                    CleanupScreen(
                        contentPadding = contentPadding,
                        onOpenSources = { navController.navigate(QQ_SOURCES_ROUTE) },
                        onSubPageChange = { cleanupSubPage = it },
                    )
                }
                page(NavDestination.Settings.route) {
                    SettingsScreen(
                        contentPadding = contentPadding,
                        onOpenCategory = { category -> navController.navigate(category.route) },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.ACCESS.route) {
                    AccessSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.SCAN.route) {
                    ScanSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.CLEANUP.route) {
                    CleanupSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.COMPRESS.route) {
                    CompressSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.APPEARANCE.route) {
                    AppearanceSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.GALLERY.route) {
                    GallerySettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.STORAGE.route) {
                    StorageSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.LOGS.route) {
                    LogScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(SettingsCategory.ABOUT.route) {
                    AboutSettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenGuide = { navController.navigate(ONBOARDING_ROUTE) },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                page(
                    route = "image/{imageId}",
                    arguments = listOf(navArgument("imageId") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("imageId") ?: 0L
                    ImageDetailScreen(
                        imageId = id,
                        onBack = { navController.popBackStack() },
                        sharedScope = if (sharedTransition) this@SharedTransitionLayout else null,
                        animatedScope = this,
                        imageTransition = imageTransition,
                    )
                }
                page(
                    route = "archive/image/{imageId}",
                    arguments = listOf(navArgument("imageId") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("imageId") ?: 0L
                    ImageDetailScreen(
                        imageId = id,
                        archiveOnly = true,
                        onBack = { navController.popBackStack() },
                        sharedScope = if (sharedTransition) this@SharedTransitionLayout else null,
                        animatedScope = this,
                        imageTransition = imageTransition,
                    )
                }
            }
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(slideSpec) { it } + fadeIn(fadeSpec),
                exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .aboveSharedImages(if (sharedTransition) this@SharedTransitionLayout else null, null, imageTransition),
            ) {
                ShortNavigationBar(
                    modifier = Modifier.onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } },
                ) {
                    NavDestination.bottomBarEntries.forEach { dest ->
                        val selected = dest == currentDestination
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.filledIcon else dest.outlinedIcon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 注册一个页面：转场进行中（进入或离开）整页不接收触摸。返回时离开的页面在上层，布局还整屏铺着
 * （容器变换时画面上它已经缩回入口了），这时点下去会点到看不见的旧页面里的开关；进入时新页面还没长开，
 * 点到的却是它里面的东西。转场只有几百毫秒，期间的点按直接忽略。
 *
 * 转场是否结束看 NavHost 那一层：预测性返回拖到一半又取消后，页面自己的进出场状态会停在 PostExit→Visible
 * 不再变，按它判断的话这一页一直点不了。
 */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    val scope = this
    val navTransition = transition.parentTransition ?: transition
    val settled = navTransition.currentState == navTransition.targetState
    Box(modifier = if (settled) Modifier else Modifier.blockTouches(), propagateMinConstraints = true) {
        scope.content(entry)
    }
}

/** 吞掉所有触摸（在最外层先拿到，里面的控件收不到） */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** QQ 来源首页路由；浏览页为 `qq-sources/{kind}/{key}` */
private const val QQ_SOURCES_ROUTE = "qq-sources"

/** 首次引导 */
private const val ONBOARDING_ROUTE = "onboarding"

private fun SourceBrowseTarget.route(): String = "$QQ_SOURCES_ROUTE/${kind.arg}/$key"

/** 底部导航栏对应的一级路由集合 */
private val bottomBarRoutes: Set<String> = NavDestination.bottomBarEntries.map { it.route }.toSet()

/** 设置域路由（首页 + 各二级页），用于识别容器变换转场 */
private val settingsRoutes: Set<String> =
    setOf(NavDestination.Settings.route) + SettingsCategory.entries.map { it.route }

/** 不在底部导航中的路由都视为"二级页"（设置子页、详情、归档、QQ 来源等） */
private fun NavBackStackEntry.isSecondary(): Boolean =
    (destination.route?.substringBefore("/") ?: "") !in bottomBarRoutes

/** 本次转场是否涉及二级页（决定用 Shared Axis 还是 Fade Through） */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesSecondary(): Boolean =
    initialState.isSecondary() || targetState.isSecondary()

/**
 * 本次转场是否走 Container Transform（转场本体用 fade，让共享元素 morph 主导）：
 * 设置域内、图库↔归档。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isContainerTransition(): Boolean {
    val from = initialState.destination.route ?: return false
    val to = targetState.destination.route ?: return false
    if (from == to) return false
    if (from in settingsRoutes && to in settingsRoutes) return true
    val gallery = NavDestination.Gallery.route
    val galleryContainers = setOf("archive")
    if (from == gallery && to in galleryContainers) return true
    if (to == gallery && from in galleryContainers) return true
    return false
}

private fun NavBackStackEntry.isImage(): Boolean =
    (destination.route ?: "").let { it.startsWith("image/") || it.startsWith("archive/image/") }
