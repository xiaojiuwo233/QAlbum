package com.xjw.qalbum.service.cleanup

import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.dao.QqPictureContext
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceScope
import com.xjw.qalbum.qq.opaqueKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 分批查询 QAlbum 的精确缓存关联；不打开 QQ 原库，也不根据视觉相似度关联文件。 */
internal class PictureFamilyPlanner(private val db: AppDatabase, private val root: String) {
    suspend fun batches(
        ids: Collection<Long>, options: PictureDeleteOptions, bound: BoundFileOps, scope: QqSourceScope? = null,
        consume: suspend (List<PictureFileGroup>) -> Unit,
    ) {
        val seen = HashSet<String>()
        val selectedAll=ids.toHashSet()
        val visibleStatuses = if (options.includeArchived) setOf("active", "thumbnail_trash", "archived") else setOf("active", "thumbnail_trash")
        for (chunk in ids.distinct().chunked(200)) {
            currentCoroutineContext().ensureActive()
            val seeds = db.imageDao().getAnyByIds(chunk).filter { it.status in visibleStatuses }
            if (seeds.isEmpty()) continue
            val paths = seeds.associateBy { it.path }
            val parents = paths.keys.associateWith { it }.toMutableMap()
            fun find(path: String): String {
                var current=path
                while(parents.getValue(current)!=current)current=parents.getValue(current)
                var next=path
                while(next!=current) { val old=parents.getValue(next);parents[next]=current;next=old }
                return current
            }
            fun join(a: String, b: String) { val x=find(a);val y=find(b);if(x!=y)parents[y]=x }
            val linkedPaths = HashSet<String>()
            var uncertain = false
            if (options.linkedVersions) {
                var frontier = paths.keys.toList()
                while (frontier.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val added = ArrayList<String>()
                    // 同一张图的原图 / 预览图 / 缩略图由文件名 CRC 直接推出（QqCachePaths.familyPaths），不依赖聊天数据库
                    for (path in frontier) {
                        val family = QqCachePaths.familyPaths(path, QqCachePaths.ALIAS_EXTENSIONS).filter { cacheType(it) != null }
                        if (family.size < 2) continue
                        linkedPaths += path
                        for (member in family) {
                            linkedPaths += member
                            if (member !in parents) { parents[member] = member; added += member }
                            join(path, member)
                        }
                    }
                    for (page in frontier.chunked(100)) {
                        // 同一条消息里的图（按 MD5 合并）：它的各个家族（一张图有多个 MD5 时不止一个）里已入库的文件、
                        // 每个家族的规范路径（下一轮再推出原图 / 预览图 / 缩略图），以及消息另记的路径
                        val sources = db.qqSourceDao()
                        val families = familiesOf(page)
                        val byOrigin = HashMap<String, MutableSet<String>>()
                        val edges = sources.familyEdges(families, page)
                        val members = edges.map { it.family }.distinct().chunked(200).flatMap { db.imageDao().pathsInFamilies(it) }
                            .groupBy({ it.variantGroup }, { it.path })
                        for (edge in edges) {
                            val entries = byOrigin.getOrPut(edge.originId) { HashSet() }
                            entries += QqCachePaths.familyPath(root, edge.family)
                            members[edge.family]?.let(entries::addAll)
                        }
                        for (edge in sources.explicitPathEdges(families, page)) byOrigin.getOrPut(edge.originId) { HashSet() } += edge.path
                        for (entries in byOrigin.values) {
                            val verified = entries.filter { it in paths || cacheType(it) != null }
                            if (verified.isEmpty()) continue
                            val anchor = verified.first()
                            for (path in verified) {
                                linkedPaths += path
                                if (path !in parents) { parents[path]=path;added+=path }
                                join(anchor,path)
                            }
                        }
                    }
                    if (parents.size > MAX_BATCH_PATHS) { uncertain=true;break }
                    frontier=added
                }
            }
            val indexed = parents.keys.chunked(200).flatMap { db.imageDao().getAnyByPaths(it) }.associateBy { it.path }
            val states = HashMap<String,String>()
            if (!uncertain) for (page in parents.keys.chunked(200)) {
                currentCoroutineContext().ensureActive()
                val rows=bound.callWithTimeout { it.statFiles(page.toTypedArray()) }
                check(rows.size==page.size) { "文件状态返回不完整" }
                page.indices.forEach { states[page[it]]=rows[it] }
            }
            // 每个路径的会话 / 发送人：按家族键找消息（文件名算出的与已入库那张图记的），加上另记的路径与已入库图的关联
            val familyOf = parents.keys.associateWith { path -> setOfNotNull(indexed[path]?.variantGroup, QqCachePaths.familyKey(path)) }
            val contextsByFamily = familyOf.values.flatten().distinct().chunked(200).flatMap { db.qqSourceDao().familyContexts(it) }
                .groupBy({ it.family }, { QqPictureContext(it.conversationKey, it.senderKey) })
            val contextsByPath = parents.keys.chunked(200).flatMap { db.qqSourceDao().pathContexts(it) }
                .groupBy({ it.path }, { QqPictureContext(it.conversationKey, it.senderKey) })
            val groups = ArrayList<PictureFileGroup>()
            for (members in parents.keys.groupBy { find(it) }.values) {
                val selected = members.mapNotNull { indexed[it]?.id }.filter { it in selectedAll }.toSet()
                if(selected.isEmpty())continue
                val key=opaqueKey("picture-files",*members.sorted().toTypedArray())
                if(!seen.add(key))continue
                val linked=members.any { it in linkedPaths }
                val records=members.mapNotNull { indexed[it] }
                // 归档页删除（includeArchived）时归档不算保护
                val protected=records.any { it.isArchived && !options.includeArchived }
                var unavailable=uncertain || records.any { it.status !in visibleStatuses && it.status != "missing" }
                val assets=ArrayList<PictureAsset>()
                if(!unavailable) for(path in members) {
                    val parts=states[path].orEmpty().split('|')
                    if(parts.firstOrNull()=="M")continue
                    if(parts.size!=4 || parts[0]!="S") { unavailable=true;continue }
                    val size=parts[1].toLongOrNull();val mtime=parts[2].toLongOrNull()
                    if(size==null || mtime==null) { unavailable=true;continue }
                    val old=indexed[path]
                    if(old!=null && (old.size!=size || old.mtime!=mtime || old.status=="missing")) { unavailable=true;continue }
                    assets+=PictureAsset(old?.id,path,old?.sourceType?:cacheType(path)?:"unknown",size,mtime,parts[3])
                }
                val contexts=members.flatMap { path ->
                    contextsByPath[path].orEmpty() + familyOf[path].orEmpty().flatMap { contextsByFamily[it].orEmpty() }
                }.distinct()
                val shared=if(scope==null)contexts.size>1 else contexts.any {
                    (scope.groupKey!=null && it.conversationKey!=scope.groupKey) || (scope.senderKey!=null && it.senderKey!=scope.senderKey)
                }
                val block=when {
                    protected -> PictureFileGroup.Block.PROTECTED
                    shared && !options.includeShared -> PictureFileGroup.Block.SHARED
                    unavailable || assets.isEmpty() -> PictureFileGroup.Block.UNAVAILABLE
                    else -> null
                }
                groups+=PictureFileGroup(key,selected,assets,linked,block)
            }
            consume(groups)
        }
    }

    /** 这些路径的家族键：按文件名算出的，加上已入库那张图记的（发送图片缓存并进了原图那一家） */
    private suspend fun familiesOf(paths: List<String>): List<Long> =
        (paths.mapNotNull { QqCachePaths.familyKey(it) } + db.imageDao().variantGroupsOfPaths(paths)).distinct()

    private fun cacheType(path: String): String? {
        val base=root.trimEnd('/')+"/"
        if(!path.startsWith(base))return null
        val parts=path.removePrefix(base).split('/')
        if(parts.any { it.isBlank() || it=="." || it==".." })return null
        if(parts.size==3 && parts[0] in setOf("chatimg","chatraw","chatthumb") && CACHE.matches(parts[2]))return parts[0]
        if(parts.size==2 && parts[0] in setOf("Temp","temp") && QqCachePaths.convertedThumbnailSource(parts[1])!=null)return "chatthumb"
        return null
    }

    companion object {
        private val CACHE=Regex("Cache_-?[0-9a-fA-F]+(?:_hd)?(?:\\.(?:${QqCachePaths.ALIAS_EXTENSIONS.joinToString("|")}))?",RegexOption.IGNORE_CASE)
        private const val MAX_BATCH_PATHS=20_000
    }
}
