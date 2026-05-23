package com.xjw.qalbum.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xjw.qalbum.ui.cleanup.CleanupScreen
import com.xjw.qalbum.ui.dashboard.DashboardScreen
import com.xjw.qalbum.ui.detail.ImageDetailScreen
import com.xjw.qalbum.ui.gallery.GalleryScreen
import com.xjw.qalbum.ui.search.SearchScreen
import com.xjw.qalbum.ui.settings.SettingsScreen

/**
 * 应用根导航：底部 NavigationBar + 五个 Composable 页面
 *
 * 用 Scaffold + Navigation Compose 经典组合，
 * 一级 Tab 之间切换保留状态（saveState / restoreState）。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = NavDestination.fromRoute(currentRoute)

    // 详情页路由不在底部导航中，进入详情时隐藏底部栏
    val showBottomBar = currentRoute in NavDestination.entries.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavDestination.entries.forEach { dest ->
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
        }
    ) { innerPadding ->
        // 顶部 inset 交给 NavHost 处理，底部 inset 通过 contentPadding 传给各页面，
        // 让页面内部的 LazyColumn / LazyVerticalGrid 可以正确处理"内容滚到导航栏下"的效果
        NavHost(
            navController = navController,
            startDestination = NavDestination.Dashboard.route,
            modifier = if (showBottomBar) {
                Modifier.padding(top = innerPadding.calculateTopPadding())
            } else {
                Modifier
            },
        ) {
            composable(NavDestination.Dashboard.route) {
                DashboardScreen(contentPadding = innerPadding.bottomOnly())
            }
            composable(NavDestination.Gallery.route) {
                GalleryScreen(
                    contentPadding = innerPadding.bottomOnly(),
                    onImageClick = { id -> navController.navigate("image/$id") },
                )
            }
            composable(NavDestination.Search.route) {
                SearchScreen(
                    contentPadding = innerPadding.bottomOnly(),
                    onResultClick = { id -> navController.navigate("image/$id") },
                )
            }
            composable(NavDestination.Cleanup.route) {
                CleanupScreen(contentPadding = innerPadding.bottomOnly())
            }
            composable(NavDestination.Settings.route) {
                SettingsScreen(contentPadding = innerPadding.bottomOnly())
            }
            composable(
                route = "image/{imageId}",
                arguments = listOf(navArgument("imageId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("imageId") ?: 0L
                ImageDetailScreen(
                    imageId = id,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** 把 Scaffold 的 PaddingValues 拆掉顶部，留给各页面自己挂 TopAppBar */
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
