package com.xjw.qalbum.service.demo

import com.xjw.qalbum.data.db.dao.QqOriginDetails
import com.xjw.qalbum.data.db.dao.QqSourceFacet
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QqAutoArchiveEntity
import com.xjw.qalbum.data.db.entity.VideoEntity

/**
 * 演示模式用的虚构名称与路径：真名第一次出现时按顺序分一个没用过的假名（不重名），之后同一个群 / 人在列表、浏览页、
 * 图片详情里都是这个假名（进程内）；转发的聊天记录（chatType 3）不换。
 */
object DemoNames {
    private val GROUPS = listOf(
        "周末徒步小队", "摄影交流群", "相亲相爱一家人", "大学同学群", "读书会", "猫咪日常", "美食分享", "骑行俱乐部",
        "前端技术交流", "考研加油站", "旅行搭子", "手工烘焙", "健身打卡", "羽毛球约球", "露营装备交流", "租房互助",
        "宝妈交流群", "高三（2）班", "公司篮球队", "咖啡爱好者", "追剧小分队", "春游计划", "开黑小队", "设计灵感分享",
        "小区业主群", "英语角", "钓鱼爱好者", "跑步打卡", "毕业十年聚会", "游泳训练营", "桌游局", "相机二手交易",
        "绘画交流", "自驾游车友会", "吉他弹唱", "早起打卡", "电影推荐", "天文观测", "花草种植", "烘焙原料团购",
    )
    private val PEOPLE = listOf(
        "小林", "阿杰", "Lily", "晴天", "老王", "Momo", "阿宁", "大雄", "Kiki", "小鱼",
        "阿泽", "Coco", "一一", "舟舟", "Lemon", "小北", "南风", "七七", "阿岚", "Eric",
        "橙子", "Mia", "小满", "阿远", "Leo", "糖糖", "大白", "Ivy", "小安", "阿乐",
        "Sunny", "豆豆", "小雨", "阿北", "Jack", "柚子", "小鹿", "Nina", "阿宇", "星星",
        "Tony", "米粒", "小周", "阿晨", "Amy", "西西", "小唐", "阿凯", "Ray", "布丁",
        "小陈", "阿哲", "Yuki", "朵朵", "小吴", "阿峰", "Ben", "果果", "小徐", "阿伟",
    )

    /** 已分配的假名：「g:真名」「p:真名」 → 假名；[next] 每类下一个要分的序号 */
    private val assigned = HashMap<String, String>()
    private val next = HashMap<String, Int>()

    /** 转发记录分类的账号名（不是真账号） */
    private const val ALL_ACCOUNTS = "全部账号"

    private const val QQ_PICTURES = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

    /** 用完一轮后加序号（「小林 2」） */
    @Synchronized
    private fun pick(pool: List<String>, real: String, kind: String): String = assigned.getOrPut("$kind:$real") {
        val index = next.getOrDefault(kind, 0)
        next[kind] = index + 1
        pool[index % pool.size] + if (index < pool.size) "" else " ${index / pool.size + 1}"
    }

    fun group(real: String): String = if (real.isBlank()) real else pick(GROUPS, real, "g")

    fun person(real: String): String = if (real.isBlank()) real else pick(PEOPLE, real, "p")

    fun account(real: String): String = if (real.isBlank() || real == ALL_ACCOUNTS) real else "演示账号"

    fun facet(facet: QqSourceFacet): QqSourceFacet = when {
        // 「全部来源图片」是合成的（id 0）
        facet.id == 0L -> facet
        facet.chatType == 3 -> facet.copy(accountName = account(facet.accountName))
        facet.chatType == 2 -> facet.copy(name = group(facet.name), alias = "", accountName = account(facet.accountName))
        else -> facet.copy(name = person(facet.name), alias = "", accountName = account(facet.accountName))
    }

    /** 来源首页的列表：换名后按假名搜索（[query]）、按名称排序时按假名排（转发记录照旧在前） */
    fun facetList(list: List<QqSourceFacet>, query: String, byName: Boolean): List<QqSourceFacet> {
        val mapped = list.map(::facet).filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        return if (byName) mapped.sortedWith(compareBy({ it.chatType != 3 }, { it.name })) else mapped
    }

    fun origin(origin: QqOriginDetails): QqOriginDetails = origin.copy(
        conversationName = when (origin.chatType) {
            3 -> origin.conversationName
            2 -> group(origin.conversationName)
            else -> person(origin.conversationName)
        },
        senderName = person(origin.senderName),
        memberName = "",
        accountName = account(origin.accountName),
    )

    /** 自动归档名单里的名称（「会话 · 成员」或「发送人 · 会话」） */
    fun autoArchiveLabel(entry: QqAutoArchiveEntity): String {
        val parts = entry.label.split(" · ")
        val conversationFirst = entry.conversationKey.isNotEmpty()
        return parts.mapIndexed { index, part ->
            if ((index == 0) == conversationFirst) group(part) else person(part)
        }.joinToString(" · ")
    }

    /** 详情里的文件名：QQ 缓存的命名（Cache_ + 十六进制），按图片 id 生成；保留「_hd」和扩展名 */
    fun filename(image: ImageEntity): String {
        val real = image.filename
        val hd = if (real.substringBefore('.').endsWith("_hd")) "_hd" else ""
        val extension = real.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 }?.let { ".$it" }.orEmpty()
        return cacheName(image.id) + hd + extension
    }

    /** 详情里的路径：QQ 图片缓存的目录结构（chatimg / chatraw / chatthumb / temp），文件名见 [filename] */
    fun path(image: ImageEntity): String {
        val folder = image.sourceType.takeIf { it in FOLDERS } ?: "chatimg"
        return "$QQ_PICTURES/$folder/${cacheName(image.id).takeLast(3)}/${filename(image)}"
    }

    private val FOLDERS = setOf("chatimg", "chatraw", "chatthumb", "temp")

    private fun cacheName(id: Long): String = "Cache_" + java.lang.Long.toString(mix(id), 16)

    fun videoFilename(video: VideoEntity): String {
        val extension = video.filename.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 } ?: "mp4"
        return java.lang.Long.toHexString(mix(video.id)).take(16) + "." + extension
    }

    fun videoPath(video: VideoEntity): String {
        val folder = if (video.source == VideoEntity.SOURCE_CHAT) "Tencent/MobileQQ/shortvideo" else "Tencent/QQfile_recv"
        return "/storage/emulated/0/Android/data/com.tencent.mobileqq/$folder/${videoFilename(video)}"
    }

    /** 打散 id（SplitMix64） */
    private fun mix(id: Long): Long {
        var z = id + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
