@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.xjw.qalbum.ui.anim

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * 全局转场动画 —— Material Motion 模式 + M3 Expressive 手感
 *
 * - 一级 Tab 之间：Fade Through（旧页先淡出，新页延迟淡入 + 轻微放大），切换不带方向感。
 * - 进出二级页：Shared Axis X（横向位移 1/6 屏宽 + 淡入淡出），位移用 [MaterialTheme.motionScheme]
 *   的 spring 规格，带 Expressive 回弹。
 *
 * 位移/缩放（spatial）用带回弹的 spring，透明度（effects）用无回弹规格 —— 由调用方从
 * `MaterialTheme.motionScheme` 取好 spec 传入，避免在非 @Composable 的转场 lambda 里读主题。
 */

// ---- Fade Through（一级 Tab）----
private const val FadeInDuration = 220
private const val FadeOutDuration = 90

fun fadeThroughEnter(): EnterTransition =
    fadeIn(tween(FadeInDuration, delayMillis = FadeOutDuration, easing = LinearOutSlowInEasing)) +
        scaleIn(
            initialScale = 0.92f,
            animationSpec = tween(FadeInDuration, delayMillis = FadeOutDuration, easing = LinearOutSlowInEasing),
        )

fun fadeThroughExit(): ExitTransition =
    fadeOut(tween(FadeOutDuration, easing = FastOutLinearInEasing))

// ---- Shared Axis X（进出二级页）----
// 仅位移屏宽的 1/6，配合 fade —— 符合 Material Shared Axis 规格（位移不喧宾夺主）
private fun axisOffset(fullWidth: Int): Int = fullWidth / 6

fun sharedAxisXEnter(slideSpec: FiniteAnimationSpec<IntOffset>, fadeSpec: FiniteAnimationSpec<Float>): EnterTransition =
    slideInHorizontally(slideSpec) { axisOffset(it) } + fadeIn(fadeSpec)

fun sharedAxisXExit(slideSpec: FiniteAnimationSpec<IntOffset>, fadeSpec: FiniteAnimationSpec<Float>): ExitTransition =
    slideOutHorizontally(slideSpec) { -axisOffset(it) } + fadeOut(fadeSpec)

fun sharedAxisXPopEnter(slideSpec: FiniteAnimationSpec<IntOffset>, fadeSpec: FiniteAnimationSpec<Float>): EnterTransition =
    slideInHorizontally(slideSpec) { -axisOffset(it) } + fadeIn(fadeSpec)

fun sharedAxisXPopExit(slideSpec: FiniteAnimationSpec<IntOffset>, fadeSpec: FiniteAnimationSpec<Float>): ExitTransition =
    slideOutHorizontally(slideSpec) { axisOffset(it) } + fadeOut(fadeSpec)

// ---- 按压缩放（列表项 / 卡片点击反馈）----

/**
 * 按住时轻微缩小、松开回弹。用 Expressive 的 fast spatial spring，回弹自然。
 * 配合 [androidx.compose.foundation.clickable] 等使用的 [interactionSource]。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
