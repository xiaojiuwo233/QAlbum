package com.xjw.qalbum.qq

import java.io.File

/** QQ 在这台设备上的数据目录：由设置里的图片缓存目录（chatpic）推出外部目录与所属用户 */
data class QqStorageRoots(val userId: Int, val internal: String, val external: String) {
    val mobileQq: String get() = "$external/Tencent/MobileQQ"

    /** 聊天里的视频（QQ NT 的 InitSessionMobilePathConfig.videoPath） */
    val shortVideo: String get() = "$mobileQq/shortvideo"

    /** 收到的文件 */
    val fileRecv: String get() = "$external/Tencent/QQfile_recv"

    fun pathOf(target: QqCacheTargets.Target): String =
        (if (target.area == QqCacheTargets.Area.INTERNAL) internal else external) + "/" + target.relative

    companion object {
        private val EXTERNAL = Regex("^(?:/storage/emulated/(\\d+)|/sdcard|/storage/self/primary)/Android/data/com\\.tencent\\.mobileqq(?:/.*)?$")

        fun fromChatpic(chatpic: String): QqStorageRoots {
            val user = EXTERNAL.matchEntire(chatpic.trim().trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return QqStorageRoots(
                userId = user,
                internal = "/data/user/$user/${QqCacheTargets.PACKAGE}",
                external = "/storage/emulated/$user/Android/data/${QqCacheTargets.PACKAGE}",
            )
        }
    }
}

/**
 * QQ「清理缓存」会清空的目录（9.3.60 QQStorageNewUiFragment.clearCache，据反编译）。按服务器开关 clean_up_thorough 分两种：
 * - 打开时（CleanUpThoroughManager）：固定清空内部的 cache/、code_cache/ 与外部的 cache/（filescan.d.l()，也是系统「清除缓存」
 *   的那一组），其余按服务器下发的名单删，离线复刻不了；
 * - 关闭时（默认值，StorageCacheManager）：各业务登记的固定目录，清空目录里的内容、保留目录本身。
 * 这里收两边的固定目录；按账号拼出的数据库 / 配置文件、靠 QQ 内部接口清的、公共存储里的（Android 11 起 QQ 自己也写不了）
 * 和已经在上面三个目录里面的不收。
 */
object QqCacheTargets {
    const val PACKAGE = "com.tencent.mobileqq"

    enum class Area { INTERNAL, EXTERNAL }

    /** 一个要清空的目录：[relative] 相对 QQ 的内部数据目录或外部 Android/data 目录，[owner] 是 QQ 里登记它的业务 */
    data class Target(val area: Area, val relative: String, val owner: String, val system: Boolean = false) {
        /** 登记的是单个文件（QQ 空间的 ix.jpg），其余都是目录 */
        val file: Boolean get() = relative == "ix.jpg"
    }

    /** QQ 清理缓存时整个清空的三个目录 */
    val systemCaches: List<Target> = listOf(
        Target(Area.INTERNAL, "cache", "缓存", system = true),
        Target(Area.INTERNAL, "code_cache", "代码缓存", system = true),
        Target(Area.EXTERNAL, "cache", "外部缓存", system = true),
    )

    /** QQ 旧版清理（StorageCacheManager 的各个 CacheTool）登记的固定目录 */
    val legacy: List<Target> = listOf(
        inner("头像与好友", "files/head", "files/pddata/prd/ef_flutter_res_v5"),
        outer("头像与好友", "Tencent/MobileQQ/head", "Tencent/MobileQQ/avgame/res"),
        outer("音视频通话", "Tencent/MobileQQ/qav", "Tencent/MobileQQ/new_ptv_template", "Tencent/MobileQQ/ptv_template"),
        outer("公众号", "Tencent/MobileQQ/pubaccount"),
        outer("附近的人", "Tencent/MobileQQ/nearby_people_photo", "Tencent/MobileQQ/.nearby/cache"),
        outer(
            "QQ 空间",
            "Tencent/Qzone/VoiceMoodAudio", "Tencent/Qzone/cache", "Tencent/Qzone/qzonedynamicalbum", "Tencent/MobileQQ/qzone/thumbs",
            "ix.jpg", "qzone/feeds", "qzone/file", "qzone/MaxVideo", "qzone/imageV2", "qzone/video", "qzone/video_cache", "qzone/image_sr",
            "qzone/personalise_font", "qzone/react", "qzone/offline", "qzone/zip_cache", "qzone/qzone_widgetai", "qzone/avatar",
            "qzone/personality_canvas", "qzone/batch_image", "qzone/gift_fullscreen", "qzone/moodlistIcons", "qzone/head_drop_operaion",
            "qzone/surprise", "qzone/smart_beautify", "qzone/audio", "qzone/facade", "qzone/AlbumTheme", "qzone/diy",
        ),
        inner("QQ 空间", "files/qzoneres"),
        inner("小程序", "tencent/mini", "Tencent/mini/files", "files/mini", "files/public/wxaapp"),
        outer("小程序", "tencent/mini", "files/wxa/videocache", "files/wxa/CodeCache", "files/wxa/wxanewfiles", "files/wxa/xlog"),
        inner("游戏中心", "files/hippy_font", "files/hippy/codecache", "files/hippy/dom", "files/gamecenter/question", "files/gamecenter/answer"),
        outer("游戏中心", "com.tencent.gamecenter.wadl"),
        inner("小游戏", "files/minigame", "files/minigame_screenrecord"),
        inner("钱包", "files/QWallet/.preloaduni", "files/QWallet/.tmp", "files/skins"),
        outer("钱包", "files/QWallet/.preloaduni", "files/QWallet/.tmp", "files/QWallet/financial_pet"),
        inner("会员装扮", "files/bubble_info", "files/bubble_paster", "files/color_screen", "files/vas_material_folder", "files/vas_so"),
        outer(
            "会员装扮",
            "Tencent/MobileQQ/.font_info", "Tencent/MobileQQ/.hiboom_font", "Tencent/MobileQQ/.pendant", "Tencent/MobileQQ/.profilecard",
            "Tencent/MobileQQ/.vipicon", "Tencent/MobileQQ/.guild", "Tencent/MobileQQ/card", "Tencent/MobileQQ/funcall",
            "Tencent/MobileQQ/.apollo/image_cache",
        ),
        outer("厘米秀", "Tencent/MobileQQ/.apollo/role", "Tencent/MobileQQ/.apollo/dress", "Tencent/MobileQQ/.apollo/action"),
        inner("广告", "files/pddata/vas/qq_health", "files/pddata/vas/qq_weather"),
        outer("广告", "tencent/TMAssistantSDK/Download/com.tencent.mobileqq"),
        inner("群视频", "files/temp"),
        outer("群视频", "files/Android/data/com.tencent.odapp"),
        outer("拍摄与编辑", "files/ae", "files/wink/editor/resources"),
        outer("聊天图片编辑", "Tencent/QQ_Images/QQEditPic"),
        outer("企业", "Tencent/MobileQQ/foward_urldrawable"),
        outer("X5 内核", "files/Download/Download"),
        outer(
            "看点",
            "Tencent/MobileQQ/pddata/proteus", "Tencent/MobileQQ/rijmmkv", "Tencent/MobileQQ/readinjoy/record", "Tencent/MobileQQ/.readinjoy",
            "Tencent/MobileQQ/viola/template", "Tencent/MobileQQ/diskcache",
        ),
        inner("看点", "app_files/Tencent/TKPublisher", "app_file_readinjoy_diandian_top_config", "app_read_in_joy_comment_emotion_data", "app_readinjoy"),
        outer("群作业", "Tencent/QQHomework_recv"),
        outer("频道", "Tencent/MobileQQ/tme/karaoke/.ktv_media_cache", "files/guild/download/pic"),
        inner("Ark 卡片", "files/ArkApp/Cache", "files/ArkApp/Install", "files/ArkApp/Res", "files/ArkApp/Storage"),
        inner("在线状态", "files/onlineStatus/avatar/action_res"),
        outer("性能监控", "files/tencent/RMonitor", "files/tencent/QAPM"),
    ).flatten()

    val all: List<Target> = systemCaches + legacy

    private fun inner(owner: String, vararg relative: String) = relative.map { Target(Area.INTERNAL, it, owner) }
    private fun outer(owner: String, vararg relative: String) = relative.map { Target(Area.EXTERNAL, it, owner) }

    // 内部目录只认 CE 存储（/data/user/0 在主用户下是 /data/data 的符号链接）
    private val INTERNAL_PATH = Regex("^(?:/data/user/\\d+|/data/data)/com\\.tencent\\.mobileqq/(.+)$")
    private val EXTERNAL_PATH = Regex("^(?:/storage/emulated/\\d+|/data/media/\\d+)/Android/data/com\\.tencent\\.mobileqq/(.+)$")

    /**
     * 特权进程清空前的核对：解析符号链接后的真实路径落在某个登记的目录上（或里面）才放行，返回真实路径，否则 null。
     * QQ 的缓存目录要是被换成指向别处的链接，真实路径就不在 QQ 的目录里，不会被清。
     */
    fun clearablePath(path: String): String? {
        if (!path.startsWith('/') || path.split('/').any { it == ".." || it == "." }) return null
        val real = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        return real.takeIf { isTarget(it) }
    }

    /** [path]（真实路径）是不是登记的目录或在它里面 */
    fun isTarget(path: String): Boolean {
        val (area, relative) = INTERNAL_PATH.matchEntire(path)?.let { Area.INTERNAL to it.groupValues[1] }
            ?: EXTERNAL_PATH.matchEntire(path)?.let { Area.EXTERNAL to it.groupValues[1] }
            ?: return false
        val clean = relative.trimEnd('/')
        return all.any { it.area == area && (clean == it.relative || clean.startsWith(it.relative + "/")) }
    }
}
