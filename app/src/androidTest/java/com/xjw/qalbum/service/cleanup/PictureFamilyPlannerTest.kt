package com.xjw.qalbum.service.cleanup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.TestDatabase
import com.xjw.qalbum.addOrigins
import com.xjw.qalbum.appContext
import com.xjw.qalbum.data.db.entity.QqOriginFamilyEntity
import com.xjw.qalbum.familyPaths
import com.xjw.qalbum.fileops.inProcessFiles
import com.xjw.qalbum.imageRow
import com.xjw.qalbum.md5
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceScope
import com.xjw.qalbum.qqOrigin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 删除规划按家族分组：同一条消息的几个 MD5 并成一组、并进原图那一家的发送图片缓存跟着原图、没入库的别名也能找到，
 * 「共用」按会话 / 发送人判断（不限来源与限定来源两种）。文件在本 App 目录下，特权操作在本进程内执行。
 */
@RunWith(AndroidJUnit4::class)
class PictureFamilyPlannerTest {
    @get:Rule val database = TestDatabase()
    private val db get() = database.db
    private val root = File(appContext.getExternalFilesDir(null), "planner-test/chatpic")
    private val bound = inProcessFiles()
    private var origins = 0

    @Before
    fun setUp() {
        root.parentFile!!.deleteRecursively()
    }

    @After
    fun tearDown() {
        root.parentFile!!.deleteRecursively()
    }

    /** 一张图的各版本（chatimg、chatraw、chatthumb、_hd）文件 */
    private fun paths(md5: String) = familyPaths(md5, root.path).map(::File)

    /** 写文件；[indexed] 时同时入库 */
    private suspend fun file(file: File, indexed: Boolean = true, variantGroup: Long? = QqCachePaths.familyKey(file.path)): Long? {
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(64) { it.toByte() })
        file.setLastModified(1_700_000_000_000L)
        if (!indexed) return null
        val type = file.path.split('/').let { it[it.size - 3] }
        return db.imageDao().insert(imageRow(file.path, type, file.length(), file.lastModified(), variantGroup = variantGroup))
    }

    private suspend fun origin(md5: String, conversation: String, sender: String, vararg more: String) {
        val id = "origin-${++origins}"
        db.addOrigins(qqOrigin(id, md5, conversation, sender))
        db.qqSourceDao().insertFamilies(more.map { QqOriginFamilyEntity(QqCachePaths.md5Family(it)!!, id) })
    }

    private suspend fun plan(ids: List<Long>, options: PictureDeleteOptions = PictureDeleteOptions(), scope: QqSourceScope? = null): List<PictureFileGroup> {
        val groups = ArrayList<PictureFileGroup>()
        PictureFamilyPlanner(db, root.path).batches(ids, options, bound, scope) { groups += it }
        return groups
    }

    private fun PictureFileGroup.files() = assets.map { File(it.path).name }.toSet()

    @Test
    fun oneMessageWithTwoMd5sIsOnePicture() = runBlocking {
        val (img, raw, thumb) = paths(md5(1))
        val imgId = file(img)!!
        file(raw)
        file(thumb)
        // 消息另记了一个 MD5：那一家只有原图
        val other = paths(md5(2))[1]
        file(other)
        origin(md5(1), "group", "alice", md5(2))
        // 另一张不相干的图
        file(paths(md5(3))[0])

        val group = plan(listOf(imgId)).single()
        assertNull(group.blocked)
        assertEquals(setOf(img.name, raw.name, thumb.name, other.name), group.files())
    }

    @Test
    fun mergedSentPreviewAndUnindexedAliasGoWithTheOriginal() = runBlocking {
        val (_, raw, thumb, hd) = paths(md5(4))
        val family = QqCachePaths.familyKey(raw.path)!!
        val rawId = file(raw)!!
        file(thumb)
        file(hd, indexed = false)
        val alias = File(raw.path + ".PNG")
        file(alias, indexed = false)
        // 自己发图时 QQ 另存的预览：按它自己内容的 MD5 命名，已并进原图那一家
        val preview = paths(md5(5))[0]
        val previewId = file(preview, variantGroup = family)!!

        val expected = setOf(raw.name, thumb.name, hd.name, alias.name, preview.name)
        assertEquals(expected, plan(listOf(rawId)).single().files())
        assertEquals(expected, plan(listOf(previewId)).single().files())
        // 不合并版本：只处理选中的文件
        assertEquals(setOf(raw.name), plan(listOf(rawId), PictureDeleteOptions(linkedVersions = false)).single().files())
    }

    @Test
    fun sharedPicturesFollowConversationsAndSenders() = runBlocking {
        val shared = file(paths(md5(6))[0])!!
        origin(md5(6), "group", "alice")
        origin(md5(6), "other", "bob")
        val own = file(paths(md5(7))[0])!!
        origin(md5(7), "group", "alice")
        origin(md5(7), "group", "alice")
        // 另一个 MD5 出现在别的会话：整张图也算共用
        val linked = file(paths(md5(8))[0])!!
        origin(md5(8), "group", "alice", md5(9))
        origin(md5(9), "other", "bob")

        val blocks = plan(listOf(shared, own, linked)).associate { group -> group.selectedIds.single() to group.blocked }
        assertEquals(mapOf(shared to PictureFileGroup.Block.SHARED, own to null, linked to PictureFileGroup.Block.SHARED), blocks)
        assertEquals(setOf(null), plan(listOf(shared, own, linked), PictureDeleteOptions(includeShared = true)).map { it.blocked }.toSet())
        // 限定来源：范围之外也有才算共用
        val inGroup = plan(listOf(shared, own), scope = QqSourceScope(groupKey = "group")).associate { it.selectedIds.single() to it.blocked }
        assertEquals(mapOf(shared to PictureFileGroup.Block.SHARED, own to null), inGroup)
        val bySender = plan(listOf(own), scope = QqSourceScope(groupKey = "group", senderKey = "alice")).single()
        assertNull(bySender.blocked)
    }
}
