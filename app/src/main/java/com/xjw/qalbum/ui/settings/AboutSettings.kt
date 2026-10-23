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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.BuildConfig
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.ShapeIcon

/** 关于：版本、不联网的说明、重新看新手引导、开源许可 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    SettingsPageScaffold(SettingsCategory.ABOUT, onBack, sharedScope, animatedScope) { _ ->
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
        SegmentedGroup(header = "说明") {
            item("offline") { position ->
                SegmentedListItem(
                    position = position,
                    leading = { ShapeIcon(Icons.Outlined.CloudOff) },
                    headline = { Text("不联网") },
                    supporting = { Text("没有网络权限，图片和记录都只在这台手机上") },
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
