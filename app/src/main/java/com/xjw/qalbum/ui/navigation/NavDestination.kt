package com.xjw.qalbum.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjw.qalbum.R

enum class NavDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
    val showInBottomBar: Boolean = true,
) {
    Dashboard(
        route = "dashboard",
        labelRes = R.string.nav_dashboard,
        outlinedIcon = Icons.Outlined.AutoAwesome,
        filledIcon = Icons.Rounded.AutoAwesome,
    ),
    Gallery(
        route = "gallery",
        labelRes = R.string.nav_gallery,
        outlinedIcon = Icons.Outlined.PhotoLibrary,
        filledIcon = Icons.Rounded.PhotoLibrary,
    ),
    Search(
        route = "search",
        labelRes = R.string.nav_search,
        outlinedIcon = Icons.Outlined.Search,
        filledIcon = Icons.Rounded.Search,
        showInBottomBar = false,
    ),
    Cleanup(
        route = "cleanup",
        labelRes = R.string.nav_cleanup,
        outlinedIcon = Icons.Outlined.CleaningServices,
        filledIcon = Icons.Rounded.CleaningServices,
    ),
    Settings(
        route = "settings",
        labelRes = R.string.nav_settings,
        outlinedIcon = Icons.Outlined.Settings,
        filledIcon = Icons.Rounded.Settings,
    );

    companion object {
        val bottomBarEntries: List<NavDestination> = entries.filter { it.showInBottomBar }

        fun fromRoute(route: String?): NavDestination =
            entries.firstOrNull { it.route == route } ?: Dashboard
    }
}
