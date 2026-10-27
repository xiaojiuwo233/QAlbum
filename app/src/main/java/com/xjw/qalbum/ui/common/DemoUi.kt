package com.xjw.qalbum.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/** 演示模式开着（见 DemoMode）：界面上要换掉的文件名、路径等据此显示虚构的 */
val LocalDemoMode = staticCompositionLocalOf { false }
