package com.xjw.qalbum.ui.onboarding

import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 启动时要不要先走首次引导：没走过、库里也还没有图。已经有图的老用户直接记成走过，以后不再判断。
 * 读不了库（如升级出错）时不显示引导。
 */
suspend fun needsOnboarding(settings: SettingsRepository, images: ImageDao): Boolean {
    if (settings.onboardingDone.first()) return false
    if (runCatching { images.hasAny() }.getOrDefault(true)) {
        settings.setOnboardingDone(true)
        return false
    }
    return true
}
