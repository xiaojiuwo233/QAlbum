package com.xjw.qalbum.service.cleanup

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.QuarantineEntity
import com.xjw.qalbum.fileops.BoundFileOps
import kotlinx.coroutines.NonCancellable
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
    private val purgeDerived: suspend (List<Long>) -> Unit,
) {
    private val images=db.imageDao()
    private val cleanup=db.cleanupDao()
    private data class State(val size:Long,val mtime:Long,val stamp:String)

    suspend fun quarantine(group:PictureFileGroup, reason:String, expiresAt:Long):PictureDeleteResult {
        val rows=register(group)
        if(rows.size!=group.assets.size || !claim(rows.map { it.id }))return PictureDeleteResult(skipped=1)
        var records:List<QuarantineEntity> = emptyList()
        try {
            val token=UUID.randomUUID().toString()
            records=db.withTransaction {
                val current=images.getLinkedCleanupEligible(rows.map { it.id })
                if(current.size!=rows.size || current.any { r -> group.assets.none { it.path==r.path && it.size==r.size && it.mtime==r.mtime } })return@withTransaction emptyList()
                val now=System.currentTimeMillis()
                val drafts=current.map { image ->
                    QuarantineEntity(imageId=image.id,originalPath=image.path,quarantinePath=File(directory,"${token}_${image.id}_${image.filename}").path,
                        thumbnailPath=null,reason=reason,ocrSummary=null,size=image.size,quarantinedAt=now,expiresAt=expiresAt,
                        state="pending",groupId=token,fileCount=current.size,sourceStamp=group.assets.first { it.path==image.path }.stamp)
                }
                val ids=cleanup.insertQuarantineAll(drafts)
                check(images.markLinkedQuarantinePending(current.map { it.id })==current.size)
                drafts.mapIndexed { i,r -> r.copy(id=ids[i]) }
            }
            if(records.isEmpty())return PictureDeleteResult(skipped=1)
            directory.mkdirs()
            val thumbs=HashMap<Long,String?>()
            for(row in rows)thumbs[row.id]=thumbnail(row)
            val direct=move(records.map { it.originalPath },records.map { it.quarantinePath },records.map { it.sourceStamp!! }).toMutableList()
            for(i in records.indices)if(!direct[i])direct[i]=copyAndRemove(records[i])
            val moved=direct.toList()
            if(moved.all { it }) {
                withContext(NonCancellable) { db.withTransaction {
                    check(images.finalizeQuarantine(records.map { it.imageId })==records.size)
                    for(r in records)cleanup.markQuarantineReady(r.id,thumbs[r.imageId])
                } }
                return PictureDeleteResult(pictures=1,files=records.size,selectedProcessed=group.selectedIds.size)
            }
            val outcome=withContext(NonCancellable) { reconcile(records, moved) }
            return PictureDeleteResult(files=outcome,skipped=if(outcome==0)1 else 0,partial=if(outcome>0)1 else 0)
        } finally {
            if(records.isNotEmpty())withContext(NonCancellable) {
                val pending=cleanup.getQuarantineById(records.first().id)
                if(pending?.state=="pending")runCatching { reconcile(records) }
            }
            release(rows.map { it.id })
        }
    }

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

    suspend fun permanentlyDelete(group:PictureFileGroup):PictureDeleteResult {
        // 永久删除也先完成可恢复隔离和日志提交。存储写满或进程退出不能丢失原文件位置。
        val isolated=quarantine(group,"用户永久删除",Long.MAX_VALUE)
        if(isolated.pictures==0)return isolated
        val records=group.assets.map { it.path }.chunked(200).flatMap { cleanup.getQuarantineByOriginalPaths(it) }
        if(records.size!=group.assets.size || !claim(records.map { it.imageId }))return isolated.copy(pictures=0,partial=1)
        try {
            var deleted=emptyList<Long>()
            withContext(NonCancellable) {
                val info=states(records.map { it.quarantinePath })
                if(records.indices.any { info[it]?.size!=records[it].size })return@withContext
                val result=delete(records.map { it.quarantinePath },info.map { it!!.stamp })
                val done=records.filterIndexed { i,_->result[i] }
                deleted=done.map { it.imageId }
                db.withTransaction {
                    for(page in deleted.chunked(200))images.markDeletedFromStatus(page,System.currentTimeMillis(),"quarantined")
                    for(page in done.map { it.id }.chunked(200))cleanup.deleteQuarantineByIds(page)
                }
                done.forEach { it.thumbnailPath?.let { p->runCatching { File(p).delete() } } }
                if(deleted.isNotEmpty())purgeDerived(deleted)
            }
            val complete=deleted.size==records.size
            return PictureDeleteResult(pictures=if(complete)1 else 0,files=deleted.size,selectedProcessed=isolated.selectedProcessed,
                partial=if(!complete)1 else 0)
        } finally { release(records.map { it.imageId }) }
    }

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
            val moved=move(records.map { it.quarantinePath },records.map { it.originalPath },sources.map { it!!.stamp })
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

    private suspend fun reconcileRestore(records:List<QuarantineEntity>) {
        val current=records.mapNotNull { cleanup.getQuarantineById(it.id) }.filter { it.state=="restoring" }
        if(current.isEmpty())return
        val original=states(current.map { it.originalPath })
        val quarantine=rawStats(current.map { it.quarantinePath })
        if(current.indices.all { original[it]?.size==current[it].size && quarantine[it]=="M" }) { finishRestore(current);return }
        val moved=current.indices.filter { original[it]?.size==current[it].size && quarantine[it]=="M" }
        if(moved.isNotEmpty())move(moved.map { current[it].originalPath },moved.map { current[it].quarantinePath },moved.map { original[it]!!.stamp })
        val afterOriginal=rawStats(current.map { it.originalPath })
        val afterQuarantine=states(current.map { it.quarantinePath })
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

    private suspend fun register(group:PictureFileGroup):List<ImageEntity> {
        val newAssets=group.assets.filter { it.id==null }
        val types=HashMap<String,MagicBytesDetector.ImageType>()
        for(page in newAssets.chunked(200)) {
            val packed=bound.callWithTimeout { it.readHeaders(page.map { p->p.path }.toTypedArray(),32) }
            val headers=HeaderBatchCodec.unpack(packed,page.size)
            for(i in page.indices)headers[i]?.let { h->MagicBytesDetector.detect(h)?.let { types[page[i].path]=it } }
        }
        if(types.size!=newAssets.size)return emptyList()
        return db.withTransaction {
        val now=System.currentTimeMillis()
        val drafts=newAssets.map { asset ->
            ImageEntity(path=asset.path,filename=asset.path.substringAfterLast('/'),parentDir=asset.path.substringBeforeLast('/'),sourceType=asset.sourceType,
                cacheKey=asset.path.substringAfterLast('/').removePrefix("Cache_"),size=asset.size,mtime=asset.mtime,firstSeen=now,lastSeen=now,
                mime=types.getValue(asset.path).mime,width=0,height=0,isAnimated=types.getValue(asset.path).isAnimated,
                status=if(asset.sourceType=="chatthumb")"thumbnail_trash" else "active")
        }
        for(page in drafts.chunked(200))images.insertAll(page)
        group.assets.map { it.path }.chunked(200).flatMap { images.getAnyByPaths(it) }
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
    private suspend fun rawStats(paths:List<String>):List<String> = paths.chunked(200).flatMap { page ->
        bound.callWithTimeout { it.statFiles(page.toTypedArray()) }.also { check(it.size==page.size) }.toList()
    }
    private suspend fun states(paths:List<String>):List<State?> = rawStats(paths).map { state(it) }
    private fun state(raw:String):State? { val p=raw.split('|');return if(p.size==4&&p[0]=="S")State(p[1].toLong(),p[2].toLong(),p[3])else null }
    private suspend fun move(sources:List<String>,targets:List<String>,stamps:List<String>):List<Boolean> = sources.indices.toList().chunked(100).flatMap { page ->
        bound.callWithTimeout { ops->ops.moveFilesChecked(page.map { sources[it] }.toTypedArray(),page.map { targets[it] }.toTypedArray(),page.map { stamps[it] }.toTypedArray()) }
            .also { check(it.size==page.size) }.toList()
    }
    private suspend fun delete(paths:List<String>,stamps:List<String>):List<Boolean> = paths.indices.toList().chunked(200).flatMap { page ->
        bound.callWithTimeout { ops->ops.deleteFilesChecked(page.map { paths[it] }.toTypedArray(),page.map { stamps[it] }.toTypedArray()) }
            .also { check(it.size==page.size) }.toList()
    }
}
