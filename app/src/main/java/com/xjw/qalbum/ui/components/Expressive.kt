package com.xjw.qalbum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ──────────────────────────────────────────────────────────────────
// 分段列表（M3 Expressive segmented list）
// 同组列表项之间留 2dp 缝隙，首项上角、末项下角用大圆角，中间项小圆角；
// 按下或选中时圆角向外形变，与按钮的按压形变保持同一手感。
// ──────────────────────────────────────────────────────────────────

/** 分段列表中某一项的位置 */
@Immutable
data class SegmentPosition(val index: Int, val count: Int) {
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == count - 1

    companion object {
        val Single = SegmentPosition(0, 1)
    }
}

object SegmentedDefaults {
    val Gap: Dp = 2.dp
    val OuterCorner: Dp = 20.dp
    val InnerCorner: Dp = 4.dp
    val EmphasizedCorner: Dp = 16.dp
}

/** 分段项外形：圆角随位置 / 按压 / 选中状态以 spring 动画过渡 */
@Composable
fun animatedSegmentShape(position: SegmentPosition, emphasized: Boolean = false): Shape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val inner = if (emphasized) SegmentedDefaults.EmphasizedCorner else SegmentedDefaults.InnerCorner
    val top by animateDpAsState(if (position.isFirst) SegmentedDefaults.OuterCorner else inner, spec, label = "segTop")
    val bottom by animateDpAsState(if (position.isLast) SegmentedDefaults.OuterCorner else inner, spec, label = "segBottom")
    // 空间 spring 带回弹，圆角可能短暂越过目标，负值会让 RoundedCornerShape 抛异常
    val safeTop = top.coerceAtLeast(0.dp)
    val safeBottom = bottom.coerceAtLeast(0.dp)
    return RoundedCornerShape(topStart = safeTop, topEnd = safeTop, bottomStart = safeBottom, bottomEnd = safeBottom)
}

/**
 * 分段组构建器：在普通 lambda 里按条件 `item {}`，渲染时自动知道每项的位置。
 * 新增项淡入展开，组高度变化平滑过渡。
 */
class SegmentedGroupScope internal constructor() {
    internal val entries = mutableListOf<Pair<Any?, @Composable (SegmentPosition) -> Unit>>()

    fun item(key: Any? = null, content: @Composable (SegmentPosition) -> Unit) {
        entries += key to content
    }
}

@Composable
fun SegmentedGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    content: SegmentedGroupScope.() -> Unit,
) {
    val scope = SegmentedGroupScope().apply(content)
    val count = scope.entries.size
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) SectionHeader(header)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
        ) {
            scope.entries.forEachIndexed { index, (itemKey, item) ->
                key(itemKey ?: index) {
                    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = visible,
                        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                            expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    ) {
                        item(SegmentPosition(index, count))
                    }
                }
            }
        }
    }
}

/** 分段列表项：标准 [ListItem] 包进分段外形容器，点击 / 长按带涟漪与按压形变 */
@Composable
fun SegmentedListItem(
    position: SegmentPosition,
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    overline: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = animatedSegmentShape(position, emphasized = pressed || selected)
    val targetColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else containerColor
    val color by animateColorAsState(targetColor, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "segColor")
    val contentColor = contentColorFor(targetColor).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
    val clickable = onClick != null || onLongClick != null
    Surface(shape = shape, color = color, contentColor = contentColor, modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = headline,
            overlineContent = overline,
            supportingContent = supporting,
            leadingContent = leading,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                leadingIconColor = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconColor = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                supportingColor = if (selected) contentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = if (clickable) {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    enabled = enabled,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                )
            } else {
                Modifier
            },
        )
    }
}

/** 进入二级页的分段项：前导图标 + 标题 + 说明 + 尾部箭头 */
@Composable
fun SegmentedNavigationItem(
    position: SegmentPosition,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailingText: String? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    SegmentedListItem(
        position = position,
        modifier = modifier,
        onClick = onClick,
        headline = { Text(title) },
        supporting = subtitle?.let { { Text(it) } },
        leading = icon?.let { { ShapeIcon(it, containerColor = iconContainerColor, contentColor = iconContentColor) } },
        trailing = {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                if (trailingText != null) {
                    Text(
                        trailingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        },
    )
}

/** 开关分段项：整行可点，尾部 M3 开关（选中时拇指带对勾） */
@Composable
fun SegmentedSwitchItem(
    position: SegmentPosition,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        position = position,
        modifier = modifier,
        enabled = enabled,
        onClick = { if (enabled) onCheckedChange(!checked) },
        headline = { Text(title) },
        supporting = subtitle?.let {
            { Text(it, color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        leading = icon?.let { { Icon(it, contentDescription = null) } },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    )
}

/** 承载自定义内容（输入框、滑块、按钮行）的分段容器 */
@Composable
fun SegmentedCard(
    position: SegmentPosition,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = animatedSegmentShape(position),
        color = containerColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// 标题、图标、状态
// ──────────────────────────────────────────────────────────────────

/** 分组小标题 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 色调容器中的图标（列表前导、卡片标题） */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    shape: Shape = CircleShape,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Surface(shape = shape, color = containerColor, contentColor = contentColor, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

/** 返回按钮：按压时形变的标准图标按钮 */
@Composable
fun BackButton(onClick: () -> Unit, tint: Color = Color.Unspecified) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "返回",
            tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
        )
    }
}

/**
 * 空状态：图标放在 Expressive 曲奇形容器里，入场时轻微放大淡入。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val entrance = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { entrance.animateTo(1f, spec) }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .graphicsLayer {
                    val p = entrance.value
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = 0.88f + 0.12f * p
                    scaleY = 0.88f + 0.12f * p
                },
        ) {
            ShapeIcon(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                size = 96.dp,
                iconSize = 40.dp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

/** 加载中：Expressive 形变加载指示器 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator(modifier = Modifier.size(56.dp))
            if (label != null) {
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 波浪进度条：[fraction] 为 null 时不确定进度；确定进度时数值变化平滑过渡。
 * 常驻显示的静态进度传 [moving] = false：波纹不再流动（流动是逐帧动画，页面停留期间会一直重绘）。
 */
@Composable
fun WavyProgress(fraction: Float?, modifier: Modifier = Modifier, moving: Boolean = true) {
    if (fraction == null) {
        LinearWavyProgressIndicator(modifier = modifier.fillMaxWidth())
    } else {
        val animated by animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "wavyProgress",
        )
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = modifier.fillMaxWidth(),
            waveSpeed = if (moving) WavyProgressIndicatorDefaults.LinearDeterminateWavelength else 0.dp,
        )
    }
}

/** 普通确认对话框（带图标，危险操作的确认按钮使用错误色） */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    dismissLabel: String = "取消",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
