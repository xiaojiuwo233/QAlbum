package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xjw.qalbum.AppInfo
import com.xjw.qalbum.service.demo.DemoMode
import com.xjw.qalbum.service.update.AppUpdates
import com.xjw.qalbum.service.update.UpdateResult
import com.xjw.qalbum.ui.common.openUrl
import com.xjw.qalbum.ui.common.shortVersionName
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import com.xjw.qalbum.ui.components.ShapeIcon

/** 关于：版本、项目地址、作者、检查更新、演示模式、联网的说明、重新看新手引导、开源许可 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val updates = koinInject<AppUpdates>()
    val demo = koinInject<DemoMode>()
    val demoOn by demo.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    SettingsPageScaffold(SettingsCategory.ABOUT, onBack, sharedScope, animatedScope) { notify ->
        Spacer(Modifier.height(8.dp))
        SegmentedCard(
            SegmentPosition.Single,
            contentPadding = PaddingValues(vertical = 28.dp, horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShapeIcon(
                    Icons.Outlined.PhotoLibrary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    size = 88.dp,
                    iconSize = 40.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text("QAlbum", style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(
                    "QQ 聊天图片的浏览与清理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SegmentedGroup(header = "项目") {
            item("project") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = { openUrl(context, AppInfo.PROJECT_URL) },
                    leading = { ShapeIcon(Icons.Outlined.Code) },
                    headline = { Text("项目地址") },
                    supporting = { Text(AppInfo.PROJECT_URL.removePrefix("https://")) },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                )
            }
            item("author") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = { openUrl(context, AppInfo.AUTHOR_URL) },
                    leading = { ShapeIcon(Icons.Outlined.Person) },
                    headline = { Text("作者") },
                    supporting = { Text(AppInfo.AUTHOR) },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                )
            }
            item("update") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = {
                        if (checking) return@SegmentedListItem
                        checking = true
                        scope.launch {
                            val result = updates.checkNow()
                            checking = false
                            checkResult = when (result) {
                                is UpdateResult.Available -> null
                                UpdateResult.UpToDate -> "已是最新版本".also { notify(it) }
                                is UpdateResult.Failed -> "检查失败：${result.reason}"
                            }
                        }
                    },
                    leading = { ShapeIcon(Icons.Outlined.SystemUpdate) },
                    headline = { Text("检查更新") },
                    supporting = { Text(if (checking) "正在检查…" else checkResult ?: "当前 ${shortVersionName(BuildConfig.VERSION_NAME)}") },
                    trailing = { if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                )
            }
        }
        SegmentedGroup(header = "演示") {
            item("demo") { position ->
                SegmentedSwitchItem(
                    position = position,
                    leading = { ShapeIcon(Icons.Outlined.Slideshow) },
                    title = "演示模式",
                    subtitle = "截图用：显示示意图和虚构的名称，不能改动图片",
                    checked = demoOn,
                    onCheckedChange = { on -> scope.launch { demo.set(on) } },
                )
            }
        }
        SegmentedGroup(header = "说明") {
            item("offline") { position ->
                SegmentedListItem(
                    position = position,
                    leading = { ShapeIcon(Icons.Outlined.PrivacyTip) },
                    headline = { Text("联网只为检查更新") },
                    supporting = { Text("只读取 GitHub 上的版本信息，图片和记录都只在这台手机上") },
                )
            }
            item("guide") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = onOpenGuide,
                    leading = { ShapeIcon(Icons.Outlined.School) },
                    headline = { Text("新手引导") },
                    supporting = { Text("再看一遍第一次打开时的引导") },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                )
            }
            item("license") { position ->
                SegmentedListItem(
                    position = position,
                    leading = { ShapeIcon(Icons.Outlined.Gavel) },
                    headline = { Text("开源许可") },
                    supporting = { Text("GPLv3") },
                )
            }
        }
    }
}
