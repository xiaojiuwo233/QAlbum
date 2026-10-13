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
                        val family = QqCachePaths.familyPaths(path).filter { cacheType(it) != null }
                        if (family.size < 2) continue
                        linkedPaths += path
                        for (member in family) {
                            linkedPaths += member
                            if (member !in parents) { parents[member] = member; added += member }
                            join(path, member)
                        }
                    }
                    for (page in frontier.chunked(200)) {
                        val edges = db.qqSourceDao().picturePathEdges(page).groupBy { it.originId }
                        for (entries in edges.values) {
                            val verified = entries.map { it.path }.filter { it in paths || cacheType(it) != null }
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
                // 归档页删除（includeArchived）时归档不算保护，用户保留仍然算
                val protected=records.any { (it.isArchived && !options.includeArchived) || it.userKeep==true }
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
                val contexts=members.flatMap { contextsByPath[it].orEmpty() }.distinct()
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
        private val CACHE=Regex("Cache_-?[0-9a-fA-F]+(?:_hd)?(?:\\.jpg)?")
        private const val MAX_BATCH_PATHS=20_000
    }
}
