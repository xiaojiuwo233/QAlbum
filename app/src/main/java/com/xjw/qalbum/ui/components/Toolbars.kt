package com.xjw.qalbum.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.ui.common.formatCountExact

/**
 * 多选态顶栏：关闭 + 「已选 N 张」（数字变化时上下滚动）+ 副标题 + 操作。
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    unit: String = "张",
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                        (slideOutVertically { if (up) -it else it } + fadeOut())
                },
                label = "selectionCount",
            ) { value -> Text("已选 ${formatCountExact(value)} $unit") }
        },
        subtitle = { if (subtitle != null) Text(subtitle) },
        navigationIcon = {
            IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Close, contentDescription = "取消选择")
            }
        },
        actions = actions,
        colors = colors,
    )
}

/**
 * 页面底部居中的 Expressive 浮动工具栏：显隐带上滑 + 缩放回弹。
 * 选择模式的批量操作、看图页的操作都用它承载。
 */
@Composable
fun FloatingActionToolbar(
    visible: Boolean,
    modifier: Modifier = Modifier,
    colors: FloatingToolbarColors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    applyNavigationBarPadding: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 2 } +
            scaleIn(spatial, initialScale = 0.85f) + fadeIn(effects),
        exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 2 } +
            scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.85f) + fadeOut(effects),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = colors,
            modifier = Modifier
                .then(if (applyNavigationBarPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(bottom = 16.dp),
            content = content,
        )
    }
}

/** 浮动工具栏中的图标操作：长按显示文字提示 */
@Composable
fun ToolbarIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
            if (tint == Color.Unspecified) {
                Icon(icon, contentDescription = label)
            } else {
                Icon(icon, contentDescription = label, tint = tint)
            }
        }
    }
}

/** 浮动工具栏里的主操作（图标 + 文字的实心按钮，按压形变） */
@Composable
fun ToolbarPrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Spacer(Modifier.width(4.dp))
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}
