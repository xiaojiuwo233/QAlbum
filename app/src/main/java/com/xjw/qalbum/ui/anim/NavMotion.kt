@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.xjw.qalbum.ui.anim

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * 全局转场动画 —— Material Motion 模式 + M3 Expressive 手感
 *
 * - 一级 Tab 之间：Fade Through（旧页先淡出，新页延迟淡入 + 轻微放大），切换不带方向感。
 * - 进出二级页：Shared Axis X（横向位移 1/6 屏宽 + 淡入淡出），位移用 [MaterialTheme.motionScheme]
 *   的 spring 规格，带 Expressive 回弹。
 * - 容器变换：[sharedContainer]（边界 morph + 内容各自淡入淡出，用于入口→整页）、
 *   [sharedImage]（同一元素 morph，用于缩略图↔全屏图）。
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
            initialScale = 0.98f,
            animationSpec = tween(FadeInDuration, delayMillis = FadeOutDuration, easing = LinearOutSlowInEasing),
        )

fun fadeThroughExit(): ExitTransition =
    fadeOut(tween(FadeOutDuration, easing = FastOutLinearInEasing))

// ---- 图片详情：缩放淡入淡出（看图专用，取代横向滑入；下滑退出由页面内 drag-dismiss 处理）----
fun imageDetailEnter(fadeSpec: FiniteAnimationSpec<Float>, scaleSpec: FiniteAnimationSpec<Float>): EnterTransition =
    fadeIn(fadeSpec) + scaleIn(initialScale = 0.96f, animationSpec = scaleSpec)

fun imageDetailExit(fadeSpec: FiniteAnimationSpec<Float>, scaleSpec: FiniteAnimationSpec<Float>): ExitTransition =
    fadeOut(fadeSpec) + scaleOut(targetScale = 0.96f, animationSpec = scaleSpec)

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

// ---- Container Transform 共享元素 ----

/**
 * 容器变换：容器边界在两个页面间 morph，内容各自淡入淡出（入口"长大"成整页）。
 * 用于设置入口、搜索/归档入口、清理 SummaryCard → 子页。scope 为空（如 preview）时退化为无动画。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(
    key: String,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val boundsSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    return with(sharedScope) {
        this@sharedContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> boundsSpec },
        )
    }
}

/**
 * 共享元素：同一元素在两个页面间 morph（缩略图 ↔ 全屏图）。
 * scope 为空时退化为无动画。转场中只画目标页那一份（打开时是大图，返回时是格子的小图），
 * 两边的边界都要是图片本身（大图按宽高比放、格子是正方形），内容按 Crop 填满。
 * 边界用不回弹的弹簧：落回格子时不会冲出格子、盖到旁边的图上。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedImage(
    key: String,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val boundsSpec = remember {
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
    }
    return with(sharedScope) {
        this@sharedImage.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> boundsSpec },
        )
    }
}

/**
 * 看图的共享元素转场期间（[active]），把这一块（顶栏、底栏、看图页的操作栏）画到共享元素上面：
 * 飞回格子的图从栏下面钻过去，不会一路盖在栏上、落定后再跳到栏下面。
 * 画在共享元素层时页面的淡入淡出管不到它，所以给了 [animatedScope] 时自己跟着页面淡入淡出 ——
 * 只在页面转场进行中（看 NavHost 那一层）：预测性返回拖到一半又取消后，页面自己的进出场状态会停在
 * PostExit→Visible 不动（[active] 也会一直为真），之后还跟着它的话，新显示出来的栏一直是透明的。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.aboveSharedImages(
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    active: Boolean,
): Modifier {
    if (sharedScope == null) return this
    val latestActive by rememberUpdatedState(active)
    val overlay = with(sharedScope) {
        this@aboveSharedImages.renderInSharedTransitionScopeOverlay(
            renderInOverlay = { isTransitionActive && latestActive },
            zIndexInOverlay = 1f,
        )
    }
    val navTransition = animatedScope?.transition?.let { it.parentTransition ?: it }
    if (animatedScope == null || navTransition?.currentState == navTransition?.targetState) return overlay
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return with(animatedScope) { overlay.animateEnterExit(enter = fadeIn(fadeSpec), exit = fadeOut(fadeSpec)) }
}
