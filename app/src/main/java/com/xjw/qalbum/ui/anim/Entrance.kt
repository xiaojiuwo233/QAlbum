package com.xjw.qalbum.ui.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首屏卡片错峰入场：按 [index] 依次从下方 24dp 处上浮并淡入。
 * 位移用 Expressive 的空间 spring（轻微回弹），透明度用无回弹的效果规格；
 * 只在首次组合播放，占位尺寸始终稳定，不会引起布局跳动。
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    stepMillis: Long = 45L,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val distance = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * stepMillis)
        launch { offset.animateTo(0f, spatial) }
        alpha.animateTo(1f, effects)
    }
    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = offset.value * distance
        },
    ) { content() }
}
