package com.xjw.qalbum.qq

import androidx.room.withTransaction
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.ImageVariantSql
import com.xjw.qalbum.data.db.dao.QqPreviewCandidate
import com.xjw.qalbum.data.db.entity.QqPreviewCheckEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * 发送图片缓存：自己发图时 QQ 把压缩后的图复制到 chatraw（按它的 MD5 命名，消息里记的就是这个 MD5），再把生成的预览
 * 复制到 chatimg，却按预览文件自己的 MD5 命名（9.2.50 / 9.3.60 MsgUtil 的 createPicElement）。消息里没有这个 MD5，
 * QQ 显示聊天、看大图、保存、转发时都按消息的 MD5 找文件，之后不会再读它；按 MD5 关联不上，也不和原图算同一张图。
 *
 * 两份文件在同一次发送里先后写入：给还没核对过的 chatimg 配修改时间紧挨着、宽高比一致、由自己发出的原图，排除按关联消息的
 * MD5 命名的（那是 QQ 显示用的），再核对文件名确实是按它自己内容的 MD5 算的。核对结果记在 qq_preview_checks：
 * 是的沿用原图的消息来源、在图库里并进原图那一家（[mergeFamilies]），「发送图片缓存」列表也按它列出。
 */
internal class QqSentPreviewLinker(
    private val db: AppDatabase,
    private val open: suspend (path: String) -> InputStream?,
) {
    /** 核对 [selfSenders]（各账号自己的发送人键）发的图留下的预览，返回这次认出的张数 */
    suspend fun link(selfSenders: List<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        if (selfSenders.isEmpty()) return 0
        val dao = db.qqSourceDao()
        val candidates = dao.sentPreviewCandidates(selfSenders, BEFORE_MS, AFTER_MS)
        if (candidates.isEmpty()) {
            mergeFamilies(db)
            return 0
        }
        val previews = candidates.distinctBy { it.previewId }
        // 文件名就是某条关联消息的 MD5 算出来的：QQ 显示这张图时用的就是它
        val md5s = previews.map { it.previewId }.chunked(SQL_CHUNK).flatMap { dao.linkedMd5s(it) }.groupBy({ it.imageId }, { it.md5 })
        val referenced = previews.filter { preview ->
            md5s[preview.previewId].orEmpty().any { preview.previewPath.endsWith("/" + QqCachePaths.relativePaths(it).first()) }
        }.mapTo(HashSet()) { it.previewId }
        val verified = HashSet<Long>()
        previews.forEachIndexed { index, candidate ->
            currentCoroutineContext().ensureActive()
            if (candidate.previewId !in referenced && namedByOwnContent(candidate.previewPath)) verified += candidate.previewId
            onProgress(index + 1, previews.size)
        }
        val raws = rawsOf(candidates.filter { it.previewId in verified })
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.putPreviewChecks(previews.map { QqPreviewCheckEntity(it.previewPath, it.previewSize, raws[it.previewId], now) })
            val found = previews.filter { it.previewId in raws }
            found.forEach { dao.registerDerivedPath(it.previewPath, raws.getValue(it.previewId)) }
            // 新关联由读取结束时的增量更新计入会话 / 发送人统计
            found.map { it.previewId }.chunked(SQL_CHUNK).forEach { dao.linkImages(it) }
        }
        mergeFamilies(db)
        Timber.i("QQ sent previews: candidates=%d referenced=%d verified=%d", previews.size, referenced.size, verified.size)
        return raws.size
    }

    private suspend fun namedByOwnContent(path: String): Boolean {
        val md5 = try {
            open(path)?.use(::digest) ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "QQ sent preview unreadable")
            return false
        }
        return path.endsWith("/" + QqCachePaths.relativePaths(md5).first())
    }

    private fun digest(input: InputStream): String? {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) return null
            md5.update(buffer, 0, count)
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 原图先写、预览后写，相差不到一秒；放宽到原图在预览之前 10 秒、之后 2 秒内 */
        const val BEFORE_MS = 10_000L
        const val AFTER_MS = 2_000L
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val SHAPE_PENALTY_MS = 30_000L
        private const val SQL_CHUNK = 500

        /**
         * 发送图片缓存并进原图那一家（图库里不再多出一张，删整张图时一起处理）。家族键平时由路径算出、扫描时写入，
         * 这里改了键要自己重算两边的家族；读取来源与每次扫描结束都会做一遍，被重新入库的预览也会并回去。
         */
        suspend fun mergeFamilies(db: AppDatabase) {
            val moves = db.qqSourceDao().previewFamilyMoves()
            if (moves.isEmpty()) return
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                moves.forEach { sql.execSQL("UPDATE images SET variant_group = ? WHERE id = ?", arrayOf<Any>(it.toGroup, it.id)) }
                moves.flatMap { listOfNotNull(it.fromGroup, it.toGroup) }.distinct().forEach { group ->
                    sql.execSQL(ImageVariantSql.recomputeGroup, arrayOf<Any>(group))
                }
            }
            Timber.i("QQ sent previews merged into their pictures: %d", moves.size)
        }

        /**
         * 每张预览配一张原图：时间越近越好（预览应比原图晚写），宽高比不一致的排在后面；先按得分一对一配（一次发几张图时
         * 各配各的），一对一配不上的取它自己得分最好的那张。
         */
        fun rawsOf(candidates: List<QqPreviewCandidate>): Map<Long, String> {
            val chosen = pair(candidates).associate { it.previewId to it.rawPath }.toMutableMap()
            candidates.groupBy { it.previewId }.forEach { (preview, options) ->
                if (preview !in chosen) chosen[preview] = options.minBy(::score).rawPath
            }
            return chosen
        }

        /** 一对一配对：按得分从好到差依次配，配过的预览和原图不再参与 */
        fun pair(candidates: List<QqPreviewCandidate>): List<QqPreviewCandidate> {
            val previews = HashSet<Long>()
            val raws = HashSet<String>()
            return candidates.sortedBy(::score).filter { candidate ->
                if (candidate.previewId in previews || candidate.rawPath in raws) return@filter false
                previews += candidate.previewId
                raws += candidate.rawPath
                true
            }
        }

        private fun score(candidate: QqPreviewCandidate): Long {
            val gap = candidate.previewTime - candidate.rawTime
            return (if (gap >= 0) gap else -gap * 4) + if (sameShape(candidate)) 0 else SHAPE_PENALTY_MS
        }

        /** 宽高比相差 3% 以内（读不到宽高时不比） */
        private fun sameShape(candidate: QqPreviewCandidate): Boolean {
            val (pw, ph, rw, rh) = listOf(candidate.previewWidth, candidate.previewHeight, candidate.rawWidth, candidate.rawHeight)
            if (pw <= 0 || ph <= 0 || rw <= 0 || rh <= 0) return true
            val a = pw.toLong() * rh
            val b = ph.toLong() * rw
            return abs(a - b) * 100 <= maxOf(a, b) * 3
        }
    }
}
