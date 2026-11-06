package com.xjw.qalbum.ui.logs

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.appContext
import com.xjw.qalbum.log.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 导出日志：分享的是可读的独立文件并带读权限；也能存到「下载/QAlbum」（Android 10 起） */
@RunWith(AndroidJUnit4::class)
class LogSharingTest {
    @Test
    fun exportsAreDistinctReadableFilesAndChooserCarriesTheirReadGrant() {
        AppLog.init(appContext)
        val marker = "log-sharing-test-${System.nanoTime()}"
        AppLog.log(Log.INFO, "LogSharingTest", marker)
        val first = AppLog.exportShareUri()!!
        val second = AppLog.exportShareUri()!!
        try {
            assertNotEquals(first, second)
            for (uri in listOf(first, second)) {
                val resolver = appContext.contentResolver
                val text = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                assertTrue(text.contains(marker))
                assertEquals("text/plain", resolver.getType(uri))
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertTrue(it.getString(0).endsWith(".txt"))
                    assertTrue(it.getLong(1) > 0)
                }

                val chooser = shareLogChooser(appContext, uri)
                @Suppress("DEPRECATION")
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                for (intent in listOf(send, chooser)) {
                    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertNotNull(intent.clipData)
                    assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
                }
                @Suppress("DEPRECATION")
                val stream = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                assertEquals(uri, stream)
                // 能接收的 App 都直接拿到了读权限（不随接收的界面结束而失效）
                val receivers = appContext.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
                assertTrue(receivers.isNotEmpty())
                receivers.forEach { info ->
                    assertEquals(info.activityInfo.packageName, PackageManager.PERMISSION_GRANTED,
                        appContext.checkUriPermission(uri, -1, info.activityInfo.applicationInfo.uid, Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        } finally {
            appContext.contentResolver.delete(first, null, null)
            appContext.contentResolver.delete(second, null, null)
        }
    }

    @Test
    fun logsCanBeSavedToDownloads() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        AppLog.init(appContext)
        val marker = "log-downloads-test-${System.nanoTime()}"
        AppLog.log(Log.INFO, "LogSharingTest", marker)
        val name = AppLog.exportToDownloads()!!
        val resolver = appContext.contentResolver
        val id = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null,
        )!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("Download/${AppLog.DOWNLOADS_FOLDER}/", it.getString(1))
            assertEquals(0, it.getInt(2))
            it.getLong(0)
        }
        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        try {
            assertTrue(resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.contains(marker))
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
