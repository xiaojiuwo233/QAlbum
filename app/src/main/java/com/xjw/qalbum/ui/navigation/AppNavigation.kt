package com.xjw.qalbum.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xjw.qalbum.data.preferences.SettingsRepository
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
import com.xjw.qalbum.ui.gallery.FavoriteGalleryScreen
import com.xjw.qalbum.ui.gallery.GalleryScreen
import com.xjw.qalbum.ui.search.SearchScreen
import com.xjw.qalbum.ui.settings.AboutSettingsScreen
import com.xjw.qalbum.ui.settings.AccessSettingsScreen
import com.xjw.qalbum.ui.settings.AiSettingsScreen
import com.xjw.qalbum.ui.settings.AnalysisSettingsScreen
import com.xjw.qalbum.ui.settings.CleanupSettingsScreen
import com.xjw.qalbum.ui.settings.PersonalizationSettingsScreen
import com.xjw.qalbum.ui.settings.ScanSettingsScreen
import com.xjw.qalbum.ui.settings.SettingsCategory
import com.xjw.qalbum.ui.settings.SettingsScreen
import org.koin.compose.koinInject

/**
 * 应用根导航：底部 NavigationBar + Navigation Compose。
 *
 * 关键设计：
 * - **inset**：外层 Scaffold 不消费系统栏 inset，NavHost 永远铺满，不随 [showBottomBar] 临时加/去
 *   top padding；每页用自己的 TopAppBar 默认 inset 避让状态栏，转场期间顶部避让稳定（修复"顶到通知栏"）。
 * - **转场**：
 *   - 一级 Tab 之间 → Fade Through；
 *   - 设置首页 ↔ 设置二级页 → Container Transform（[SharedTransitionLayout] + sharedBounds，
 *     点击的设置行"长大"成整页、返回收缩回去），转场本体走 fade 让 sharedBounds 边界变形主导；
 *   - 其它二级页（搜索/收藏/详情）→ Shared Axis X。
 *   底部栏进入二级页时滑出、返回时滑入。系统手势走预测式返回。
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = NavDestination.fromRoute(currentRoute)
    val showBottomBar = currentRoute in bottomBarRoutes

    val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scaleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // 详情转场方案开关：true=缩略图共享元素，false=缩放进入 + 图库立即垫底
    val settings = koinInject<SettingsRepository>()
    val sharedTransition by settings.detailSharedTransition.collectAsState(initial = false)

    SharedTransitionLayout {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(slideSpec) { it } + fadeIn(fadeSpec),
                    exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec),
                ) {
                    NavigationBar {
                        NavDestination.bottomBarEntries.forEach { dest ->
                            val selected = dest == currentDestination
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.filledIcon else dest.outlinedIcon,
                                        contentDescription = stringResource(dest.labelRes),
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            val contentPadding = innerPadding.bottomOnly()
            NavHost(
                navController = navController,
                startDestination = NavDestination.Dashboard.route,
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
                        // 从详情返回：off 时图库立即出现在下层（详情缩小淡出露出它，避免黑屏）；
                        // on 时图库淡入，配合共享元素把大图缩回缩略图
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
                composable(NavDestination.Dashboard.route) {
                    DashboardScreen(contentPadding = contentPadding)
                }
                composable(NavDestination.Gallery.route) {
                    GalleryScreen(
                        contentPadding = contentPadding,
                        onImageClick = { id -> navController.navigate("image/$id") },
                        onOpenSearch = { navController.navigate(NavDestination.Search.route) },
                        onOpenFavorites = { navController.navigate("favorites") },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                        imageShared = sharedTransition,
                    )
                }
                composable("favorites") {
                    FavoriteGalleryScreen(
                        contentPadding = contentPadding,
                        onBack = { navController.popBackStack() },
                        onImageClick = { id -> navController.navigate("favorites/image/$id") },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                        imageShared = sharedTransition,
                    )
                }
                composable(NavDestination.Search.route) {
                    SearchScreen(
                        contentPadding = contentPadding,
                        onResultClick = { id -> navController.navigate("image/$id") },
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(NavDestination.Cleanup.route) {
                    CleanupScreen(contentPadding = contentPadding)
                }
                composable(NavDestination.Settings.route) {
                    SettingsScreen(
                        contentPadding = contentPadding,
                        onOpenCategory = { category -> navController.navigate(category.route) },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.ACCESS.route) {
                    AccessSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.SCAN.route) {
                    ScanSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.ANALYSIS.route) {
                    AnalysisSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.CLEANUP.route) {
                    CleanupSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.AI.route) {
                    AiSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.PERSONALIZATION.route) {
                    PersonalizationSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(SettingsCategory.ABOUT.route) {
                    AboutSettingsScreen(
                        onBack = { navController.popBackStack() },
                        sharedScope = this@SharedTransitionLayout,
                        animatedScope = this,
                    )
                }
                composable(
                    route = "image/{imageId}",
                    arguments = listOf(navArgument("imageId") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("imageId") ?: 0L
                    ImageDetailScreen(
                        imageId = id,
                        onBack = { navController.popBackStack() },
                        sharedScope = if (sharedTransition) this@SharedTransitionLayout else null,
                        animatedScope = this,
                    )
                }
                composable(
                    route = "favorites/image/{imageId}",
                    arguments = listOf(navArgument("imageId") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("imageId") ?: 0L
                    ImageDetailScreen(
                        imageId = id,
                        favoritesOnly = true,
                        onBack = { navController.popBackStack() },
                        sharedScope = if (sharedTransition) this@SharedTransitionLayout else null,
                        animatedScope = this,
                    )
                }
            }
        }
    }
}

/** 底部导航栏对应的一级路由集合 */
private val bottomBarRoutes: Set<String> = NavDestination.bottomBarEntries.map { it.route }.toSet()

/** 设置域路由（首页 + 各二级页），用于识别容器变换转场 */
private val settingsRoutes: Set<String> =
    setOf(NavDestination.Settings.route) + SettingsCategory.entries.map { it.route }

/** 不在底部导航中的路由都视为"二级页"（设置子页、详情、搜索、收藏等） */
private fun NavBackStackEntry.isSecondary(): Boolean =
    (destination.route?.substringBefore("/") ?: "") !in bottomBarRoutes

/** 本次转场是否涉及二级页（决定用 Shared Axis 还是 Fade Through） */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesSecondary(): Boolean =
    initialState.isSecondary() || targetState.isSecondary()

/**
 * 本次转场是否走 Container Transform（转场本体用 fade，让共享元素 morph 主导）：
 * 设置域内、图库↔搜索/收藏、以及任一侧是图片详情（缩略图共享元素）。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isContainerTransition(): Boolean {
    val from = initialState.destination.route ?: return false
    val to = targetState.destination.route ?: return false
    if (from == to) return false
    if (from in settingsRoutes && to in settingsRoutes) return true
    val gallery = NavDestination.Gallery.route
    val galleryContainers = setOf(NavDestination.Search.route, "favorites")
    if (from == gallery && to in galleryContainers) return true
    if (to == gallery && from in galleryContainers) return true
    return false
}

private fun NavBackStackEntry.isImage(): Boolean =
    (destination.route ?: "").let { it.startsWith("image/") || it.startsWith("favorites/image/") }

/** 把 Scaffold 的 PaddingValues 拆掉顶部，只保留底部（导航栏高度）与左右 */
@Composable
private fun PaddingValues.bottomOnly(): PaddingValues {
    val ld = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(ld),
        end = calculateEndPadding(ld),
        bottom = calculateBottomPadding(),
        top = 0.dp,
    )
}
