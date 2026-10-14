package com.xjw.qalbum.service.cleanup

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.fileops.FileOpsImpl
import com.xjw.qalbum.qq.QqCachePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileOutputStream
import com.xjw.qalbum.fileops.HeaderBatchCodec
import com.xjw.qalbum.service.indexer.MagicBytesDetector

/** 已确认的文件组操作；所有原位置变更均使用带文件身份核验的特权接口。 */
internal class LinkedPictureOperations(
    private val db: AppDatabase,
    private val bound: BoundFileOps,
    private val directory: File,
    private val inFlight: MutableSet<Long>,
    private val thumbnail: suspend (ImageEntity) -> String?,
) {
    private val images=db.imageDao()
    private val cleanup=db.cleanupDao()
    private data class State(val size:Long,val mtime:Long,val stamp:String)

    /** 批量处理中的一组：登记后的图片行、写入的回收站记录与缩略图，以及这组的结果 */
    private class Item(val group:PictureFileGroup, val rows:List<ImageEntity>) {
        var records:List<QuarantineEntity> = emptyList()
        var thumbnail:String?=null
        var settled=false
        var result=PictureDeleteResult(skipped=1)
    }

    /** [archived]：归档页删除，归档的图也可移入（见 PictureDeleteOptions.includeArchived） */
    suspend fun quarantineAll(groups:List<PictureFileGroup>, reason:String, expiresAt:Long, archived:Boolean=false):PictureDeleteResult =
        isolate(groups,reason,expiresAt,thumbnails=true,archived=archived).sum()

    /**
     * 一批文件组移入回收站：登记、写日志、移动文件、落定各做一次批量操作，几百组只有几个事务和几次 binder 调用。
     * 每组仍有自己的组标识与 pending 日志，中断后按组对账；[then] 在这些组仍被占用时处理已就绪的组（永久删除接着删掉它们）。
     */
    private suspend fun isolate(groups:List<PictureFileGroup>, reason:String, expiresAt:Long, thumbnails:Boolean, archived:Boolean,
        then:suspend (List<Item>)->Unit={}):List<Item> {
        val registered=register(groups)
        val items=groups.indices.map { Item(groups[it],registered[it]) }
        val claimed=items.filter { it.rows.isNotEmpty() && it.rows.size==it.group.assets.size && claim(it.rows.map { r->r.id }) }
        try {
            val logged=db.withTransaction {
                val current=claimed.flatMap { it.rows }.map { it.id }.chunked(SQL_CHUNK).flatMap { images.getLinkedCleanupEligible(it,archived) }.associateBy { it.id }
                val now=System.currentTimeMillis()
                val drafts=ArrayList<QuarantineEntity>()
                val owners=ArrayList<Item>()
                for(item in claimed) {
                    val rows=item.rows.mapNotNull { current[it.id] }
                    if(rows.size!=item.rows.size || rows.any { r->item.group.assets.none { it.path==r.path && it.size==r.size && it.mtime==r.mtime } })continue
                    val token=UUID.randomUUID().toString()
                    for(image in rows) {
                        drafts+=QuarantineEntity(imageId=image.id,originalPath=image.path,quarantinePath=File(directory,"${token}_${image.id}_${image.filename}").path,
                            thumbnailPath=null,reason=reason,ocrSummary=null,size=image.size,quarantinedAt=now,expiresAt=expiresAt,
                            state="pending",groupId=token,fileCount=rows.size,sourceStamp=item.group.assets.first { it.path==image.path }.stamp)
                        owners+=item
                    }
                }
                val ids=cleanup.insertQuarantineAll(drafts)
                check(drafts.map { it.imageId }.chunked(SQL_CHUNK).sumOf { images.markLinkedQuarantinePending(it,archived) }==drafts.size)
                drafts.indices.groupBy({ owners[it] },{ drafts[it].copy(id=ids[it]) })
            }
            for((item,records) in logged)item.records=records
            val open=items.filter { it.records.isNotEmpty() }
            if(open.isEmpty())return items
            directory.mkdirs()
            if(thumbnails)makeThumbnails(open)
            val records=open.flatMap { it.records }
            val direct=move(records.map { it.originalPath },records.map { it.quarantinePath },records.map { it.sourceStamp!! }).toMutableList()
            // 改名移不过去的（如 Shizuku 写不进 App 目录）逐个复制，几个同时进行
            val failed=records.indices.filter { !direct[it] }
            if(failed.isNotEmpty())chunks(failed.size,1) { page->page.map { copyAndRemove(records[failed[it]]) } }.forEachIndexed { k,ok->direct[failed[k]]=ok }
            val moved=records.indices.associate { records[it].id to direct[it] }
            val complete=open.filter { item->item.records.all { moved.getValue(it.id) } }
            if(complete.isNotEmpty())withContext(NonCancellable) { db.withTransaction {
                val ids=complete.flatMap { it.records }.map { it.imageId }
                check(ids.chunked(SQL_CHUNK).sumOf { images.finalizeQuarantine(it) }==ids.size)
                for(item in complete)for(r in item.records)cleanup.markQuarantineReady(r.id,item.thumbnail)
            } }
            for(item in complete) {
                item.settled=true
                item.records=item.records.map { it.copy(state="ready",thumbnailPath=item.thumbnail) }
                item.result=PictureDeleteResult(pictures=1,files=item.records.size,selectedProcessed=item.group.selectedIds.size)
            }
            for(item in open)if(!item.settled) {
                val outcome=withContext(NonCancellable) { settle(item) { reconcile(it,item.records.map { r->moved.getValue(r.id) }) } }
                item.result=PictureDeleteResult(files=outcome,skipped=if(outcome==0)1 else 0,partial=if(outcome>0)1 else 0)
            }
            then(complete)
            return items
        } finally {
            withContext(NonCancellable) {
                for(item in items)if(item.records.isNotEmpty() && !item.settled) {
                    val pending=cleanup.getQuarantineById(item.records.first().id)
                    if(pending?.state=="pending")runCatching { settle(item) { reconcile(it) } }
                }
            }
            for(item in claimed)release(item.rows.map { it.id })
        }
    }

    /** 中断 / 部分失败的组按日志对账：留在回收站的文件带上缩略图，整组回滚时删掉已生成的缩略图 */
    private suspend fun settle(item:Item, reconcile:suspend (List<QuarantineEntity>)->Int):Int {
        val outcome=reconcile(item.records.map { it.copy(thumbnailPath=item.thumbnail) })
        item.settled=true
        val thumb=item.thumbnail
        if(thumb!=null && cleanup.getQuarantineGroup(item.records.first().groupId!!).isEmpty())runCatching { File(thumb).delete() }
        return outcome
    }

    /** 回收站每组只显示一张缩略图：从同一张图最小的可用版本生成，不读几 MB 的原图；几组并行 */
    private suspend fun makeThumbnails(items:List<Item>) = coroutineScope {
        val permits=Semaphore(PARALLELISM)
        items.map { item -> async(Dispatchers.IO) {
            permits.withPermit { item.thumbnail=item.rows.sortedBy { thumbnailRank(it) }.firstNotNullOfOrNull { thumbnail(it) } }
        } }.awaitAll()
    }

    private fun thumbnailRank(image:ImageEntity):Int = when {
        image.sourceType=="chatthumb" && image.path.removeSuffix(".jpg").endsWith("_hd") -> 0
        image.sourceType=="chatimg" -> 1
        image.sourceType=="chatthumb" -> 2
        else -> 3
    }

    private fun List<Item>.sum():PictureDeleteResult = fold(PictureDeleteResult()) { total,item->total+item.result }

    /** 跨分区/权限导致 rename 失败时先复制，核验原文件身份后才删除原位置。 */
    private suspend fun copyAndRemove(record:QuarantineEntity):Boolean {
        val dest=File(record.quarantinePath)
        if(rawStats(listOf(record.quarantinePath)).single()!="M")return false
        val before=states(listOf(record.originalPath)).single()?:return false
        if(before.stamp!=record.sourceStamp)return false
        var created=false
        var deletionIssued=false
        try {
            val input=bound.callWithTimeout(onAbandoned={ it?.close() }) { it.openRead(record.originalPath) }?:return false
            ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                val fd=Os.open(dest.path,OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY,0x180)
                created=true
                FileOutputStream(fd).use { stream.copyTo(it);it.fd.sync() }
            }
            if(dest.length()!=record.size)return false
            if(states(listOf(record.originalPath)).single()?.stamp!=record.sourceStamp)return false
            dest.setLastModified(before.mtime)
            deletionIssued=true
            if(delete(listOf(record.originalPath),listOf(record.sourceStamp!!)).single()) { created=false;return true }
            // 原文件可能已被 QQ 重写。保留已复制的老版本，交由持久化日志处理。
            created=false
            return false
        } finally {
            // 仅删除本次新建且原文件未被删掉的副本；已成功隔离的文件永不清理。
            if(created && !deletionIssued)runCatching { dest.delete() }
        }
    }

    /** 永久删除也先完成可恢复隔离和日志提交（不生成缩略图），存储写满或进程退出不能丢失原文件位置；再整批删掉已就绪的组。 */
    suspend fun permanentlyDeleteAll(groups:List<PictureFileGroup>, archived:Boolean=false):PictureDeleteResult =
        isolate(groups,"用户永久删除",Long.MAX_VALUE,thumbnails=false,archived=archived) { ready ->
        if(ready.isNotEmpty())withContext(NonCancellable) {
            val records=ready.flatMap { it.records }
            val info=states(records.map { it.quarantinePath }).let { list->records.indices.associate { records[it].id to list[it] } }
            // 组内任一文件对不上就整组留在回收站
            val targets=ready.filter { item->item.records.all { info[it.id]?.size==it.size } }.flatMap { it.records }
            val result=delete(targets.map { it.quarantinePath },targets.map { info.getValue(it.id)!!.stamp })
            val done=targets.filterIndexed { i,_->result[i] }
            db.withTransaction {
                for(page in done.map { it.imageId }.chunked(200))images.markDeletedFromStatus(page,System.currentTimeMillis(),"quarantined")
                for(page in done.map { it.id }.chunked(200))cleanup.deleteQuarantineByIds(page)
            }
            done.forEach { it.thumbnailPath?.let { p->runCatching { File(p).delete() } } }
            val deleted=done.mapTo(HashSet()) { it.id }
            for(item in ready) {
                val count=item.records.count { it.id in deleted }
                val complete=count==item.records.size
                item.result=PictureDeleteResult(pictures=if(complete)1 else 0,files=count,selectedProcessed=item.group.selectedIds.size,partial=if(!complete)1 else 0)
            }
        }
    }.sum()

    /** 原位置有新文件时保留整组，不覆盖、不改名成 QQ 无法定位的缓存文件。 */
    suspend fun restore(records:List<QuarantineEntity>):Boolean {
        if(records.isEmpty() || records.any { it.state!="ready" } || !claim(records.map { it.imageId }))return false
        try {
            val sources=states(records.map { it.quarantinePath })
            val targets=rawStats(records.map { it.originalPath })
            if(records.indices.any { sources[it]?.size!=records[it].size || targets[it]!="M" })return false
            if(records.map { it.originalPath }.chunked(200).any { images.getAnyByPaths(it).isNotEmpty() })return false
            db.withTransaction {
                for(record in records) {
                    check(cleanup.markQuarantineRestoring(record.id)==1)
                    check(images.markRestorePending(record.imageId,record.originalPath)==1)
                }
            }
            // 改名送不回去的（QQ 与 QAlbum 的目录各有存储配额，系统拒绝跨目录改名）复制回去
            val moved=move(records.map { it.quarantinePath },records.map { it.originalPath },sources.map { it!!.stamp })
                .mapIndexed { i,ok->ok || copyBack(records[i],sources[i]!!) }
            if(moved.all { it }) {
                withContext(NonCancellable) { finishRestore(records) }
                return true
            }
            withContext(NonCancellable) { reconcileRestore(records) }
            return false
        } finally { release(records.map { it.imageId }) }
    }

    suspend fun reconcileByRecord(record:QuarantineEntity):Int {
        val records=record.groupId?.let { cleanup.getQuarantineGroup(it) }?:listOf(record)
        if(!claim(records.map { it.imageId }))return 0
        try {
            return if(records.any { it.state=="restoring" }) {
                reconcileRestore(records);0
            } else if(records.any { it.state=="pending" })reconcile(records) else 0
        } finally { release(records.map { it.imageId }) }
    }

    /** 未完成的移动优先整组回滚；回滚受阻的已移动文件留在回收站，绝不删除它们。 */
    private suspend fun reconcile(records:List<QuarantineEntity>, knownMoved:List<Boolean>?=null):Int {
        val pending=records.filter { cleanup.getQuarantineById(it.id)?.state=="pending" }
        if(pending.isEmpty())return 0
        val original=rawStats(pending.map { it.originalPath })
        val destinations=states(pending.map { it.quarantinePath })
        if(pending.indices.all { original[it]=="M" && destinations[it]?.size==pending[it].size }) {
            db.withTransaction {
                for(page in pending.map { it.imageId }.chunked(200))images.finalizeQuarantine(page)
                for(r in pending)cleanup.markQuarantineReady(r.id,r.thumbnailPath)
            }
            return pending.size
        }
        val moved=pending.indices.filter { original[it]=="M" && destinations[it]?.size==pending[it].size }
        val restored=if(moved.isNotEmpty())move(moved.map { pending[it].quarantinePath },moved.map { pending[it].originalPath },moved.map { destinations[it]!!.stamp })else emptyList()
        val rolledBack=moved.filterIndexed { i,_->restored[i] }.toSet()
        val afterOriginal=rawStats(pending.map { it.originalPath })
        val quarantineRaw=rawStats(pending.map { it.quarantinePath })
        val afterQuarantine=quarantineRaw.map { state(it) }
        var held=0
        db.withTransaction {
            for(i in pending.indices) {
                val record=pending[i]
                if(afterQuarantine[i]?.size==record.size) {
                    // 原路径可能已由 QQ 重新下载：保留老文件，不用“原路径存在”作为删副本的理由。
                    images.finalizeQuarantine(listOf(record.imageId))
                    cleanup.markQuarantineReady(record.id,record.thumbnailPath)
                    held++
                } else if(afterOriginal[i].startsWith("S|") && quarantineRaw[i]=="M" &&
                    (i in rolledBack || state(afterOriginal[i])?.stamp==record.sourceStamp || knownMoved?.getOrNull(records.indexOfFirst { it.id==record.id })==false)) {
                    images.rollbackQuarantinePending(listOf(record.imageId))
                    cleanup.deleteQuarantine(record.id)
                }
            }
        }
        return held
    }

    /**
     * 改名送不回原位置时复制回去：特权进程按 QQ 目录的属主与权限写好原文件（修改时间不变，QQ 读得到），
     * 核对大小后再删回收站里的副本。中途退出时由 [reconcileRestore] 收尾。
     */
    private suspend fun copyBack(record:QuarantineEntity,source:State):Boolean {
        // 回收站副本多是 App 自己复制进来的，直接读；读不了（root 改名进来的）再走特权读
        val content=runCatching { ParcelFileDescriptor.open(File(record.quarantinePath),ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
            ?:bound.callWithTimeout(onAbandoned={ it?.close() }) { it.openRead(record.quarantinePath) }?:return false
        val code=content.use { pfd->bound.callWithTimeout(timeoutMs=COPY_TIMEOUT_MS) { it.writeFileChecked(record.originalPath,pfd,source.mtime) } }
        if(code!=FileOpsImpl.REPLACE_OK || states(listOf(record.originalPath)).single()?.size!=record.size)return false
        return delete(listOf(record.quarantinePath),listOf(source.stamp)).single()
    }

    private suspend fun reconcileRestore(records:List<QuarantineEntity>) {
        val current=records.mapNotNull { cleanup.getQuarantineById(it.id) }.filter { it.state=="restoring" }
        if(current.isEmpty())return
        val original=states(current.map { it.originalPath })
        var quarantine=rawStats(current.map { it.quarantinePath })
        // 复制送回后没来得及删回收站副本：原位置已经完整，删掉副本
        val copied=current.indices.filter { original[it]?.size==current[it].size && state(quarantine[it])?.size==current[it].size }
        if(copied.isNotEmpty()) {
            delete(copied.map { current[it].quarantinePath },copied.map { state(quarantine[it])!!.stamp })
            quarantine=rawStats(current.map { it.quarantinePath })
        }
        if(current.indices.all { original[it]?.size==current[it].size && quarantine[it]=="M" }) { finishRestore(current);return }
        val moved=current.indices.filter { original[it]?.size==current[it].size && quarantine[it]=="M" }
        if(moved.isNotEmpty())move(moved.map { current[it].originalPath },moved.map { current[it].quarantinePath },moved.map { original[it]!!.stamp })
        val afterOriginal=rawStats(current.map { it.originalPath })
        val afterQuarantine=states(current.map { it.quarantinePath })
        // 已经送回、又改名退不回回收站的（跨目录改名被拒）：这几张就算恢复了
        val stayed=moved.filter { afterOriginal[it].startsWith("S|") && afterQuarantine[it]==null }
        if(stayed.isNotEmpty())finishRestore(stayed.map { current[it] })
        db.withTransaction {
            for(i in current.indices)if(afterOriginal[i]=="M" && afterQuarantine[i]?.size==current[i].size) {
                images.rollbackRestorePending(current[i].imageId)
                cleanup.rollbackQuarantineRestore(current[i].id)
            }
        }
    }

    private suspend fun finishRestore(records:List<QuarantineEntity>) {
        db.withTransaction {
            for(r in records) {
                check(images.finalizeRestore(r.imageId,System.currentTimeMillis())==1)
                cleanup.deleteQuarantine(r.id)
            }
        }
        records.forEach { it.thumbnailPath?.let { path->runCatching { File(path).delete() } } }
    }

    /** 把各组里还没入库的文件（如高清缩略图）登记入库；返回与 [groups] 对应的图片行，有文件读不出类型的组为空（整组跳过） */
    private suspend fun register(groups:List<PictureFileGroup>):List<List<ImageEntity>> {
        val newAssets=groups.flatMap { group->group.assets.filter { it.id==null } }
        val types=HashMap<String,MagicBytesDetector.ImageType>()
        for(page in newAssets.chunked(200)) {
            val packed=bound.callWithTimeout { it.readHeaders(page.map { p->p.path }.toTypedArray(),32) }
            val headers=HeaderBatchCodec.unpack(packed,page.size)
            for(i in page.indices)headers[i]?.let { h->MagicBytesDetector.detect(h)?.let { types[page[i].path]=it } }
        }
        val usable=groups.filter { group->group.assets.all { it.id!=null || it.path in types } }
        return db.withTransaction {
        val now=System.currentTimeMillis()
        val drafts=usable.flatMap { group->group.assets.filter { it.id==null } }.map { asset ->
            ImageEntity(path=asset.path,filename=asset.path.substringAfterLast('/'),parentDir=asset.path.substringBeforeLast('/'),sourceType=asset.sourceType,
                cacheKey=asset.path.substringAfterLast('/').removePrefix("Cache_"),variantGroup=QqCachePaths.familyKey(asset.path),
                variantBytes=asset.size,size=asset.size,mtime=asset.mtime,firstSeen=now,lastSeen=now,
                mime=types.getValue(asset.path).mime,width=0,height=0,isAnimated=types.getValue(asset.path).isAnimated,
                status=if(asset.sourceType=="chatthumb")"thumbnail_trash" else "active")
        }
        drafts.filter { it.variantGroup!=null && it.path.endsWith(".jpg") }.map { it.path.removeSuffix(".jpg") }
            .chunked(200).forEach { db.qqSourceDao().registerJpegPaths(it) }
        for(page in drafts.chunked(200))images.insertAll(page)
        val rows=usable.flatMap { group->group.assets.map { it.path } }.chunked(200).flatMap { images.getAnyByPaths(it) }.associateBy { it.path }
        val included=usable.mapTo(HashSet()) { it.key }
        groups.map { group->if(group.key in included)group.assets.mapNotNull { rows[it.path] } else emptyList() }
        }
    }

    private fun claim(ids:List<Long>):Boolean = synchronized(inFlight) {
        val added=ArrayList<Long>()
        for(id in ids) {
            if(!inFlight.add(id)) { added.forEach { inFlight.remove(it) };return@synchronized false }
            added+=id
        }
        true
    }
    private fun release(ids:List<Long>) { ids.forEach { inFlight.remove(it) } }
    private suspend fun rawStats(paths:List<String>):List<String> = chunks(paths.size,200) { page ->
        bound.callWithTimeout { it.statFiles(page.map { i->paths[i] }.toTypedArray()) }.also { check(it.size==page.size) }.toList()
    }
    private suspend fun states(paths:List<String>):List<State?> = rawStats(paths).map { state(it) }
    private fun state(raw:String):State? { val p=raw.split('|');return if(p.size==4&&p[0]=="S")State(p[1].toLong(),p[2].toLong(),p[3])else null }
    private suspend fun move(sources:List<String>,targets:List<String>,stamps:List<String>):List<Boolean> = chunks(sources.size,100) { page ->
        bound.callWithTimeout { ops->ops.moveFilesChecked(page.map { sources[it] }.toTypedArray(),page.map { targets[it] }.toTypedArray(),page.map { stamps[it] }.toTypedArray()) }
            .also { check(it.size==page.size) }.toList()
    }
    private suspend fun delete(paths:List<String>,stamps:List<String>):List<Boolean> = chunks(paths.size,100) { page ->
        bound.callWithTimeout { ops->ops.deleteFilesChecked(page.map { paths[it] }.toTypedArray(),page.map { stamps[it] }.toTypedArray()) }
            .also { check(it.size==page.size) }.toList()
    }

    /**
     * 分块的特权批量调用，几块同时进行：特权进程的 binder 线程池并行处理，每个文件的存储往返互相重叠。
     * 结果按原顺序拼回；中途失败时其余块可能已在特权进程里完成，由调用方按日志对账（与超时的情况相同）。
     */
    private suspend fun <T> chunks(size:Int, chunk:Int, call:suspend (List<Int>)->List<T>):List<T> = coroutineScope {
        val permits=Semaphore(PARALLELISM)
        (0 until size).chunked(chunk).map { page -> async(Dispatchers.IO) { permits.withPermit { call(page) } } }.awaitAll().flatten()
    }

    private companion object {
        /** IN 查询分块（低版本系统 SQLite 变量上限 999） */
        const val SQL_CHUNK=900
        /** 缩略图解码与特权批量调用的并发数 */
        const val PARALLELISM=4
        /** 复制送回一个文件的特权调用超时（大原图要写几十 MB） */
        const val COPY_TIMEOUT_MS=60_000L
    }
}
