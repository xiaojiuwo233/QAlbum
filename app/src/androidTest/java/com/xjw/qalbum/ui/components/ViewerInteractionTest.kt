package com.xjw.qalbum.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.ui.theme.QAlbumTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ViewerInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun doubleTapPinchAndPageResetShareOneGestureState() {
        val bitmap = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (column in 0 until 8) {
            paint.color = if (column % 2 == 0) android.graphics.Color.CYAN else android.graphics.Color.RED
            canvas.drawRect(column * 100f, 0f, (column + 1) * 100f, 450f, paint)
        }
        val active = mutableStateOf(true)
        val reset = mutableStateOf(0)
        var zoom = 1f
        var taps = 0
        rule.setContent {
            QAlbumTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ZoomableImage(bitmap, "preview", Modifier.fillMaxSize(), stateKey = 1,
                        isActive = active.value, resetSignal = reset.value, onTap = { taps++ }, onZoomChanged = { zoom = it })
                }
            }
        }
        val image = rule.onNodeWithContentDescription("preview")
        rule.waitUntil(10_000) {
            val pixels = image.captureToImage().toPixelMap()
            val pixel = pixels[pixels.width * 9 / 16, pixels.height / 2]
            (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) ||
                (pixel.red < 0.2f && pixel.green > 0.8f && pixel.blue > 0.8f)
        }
        capture("viewer-fit")
        image.performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(2.5f, zoom, 0.05f); assertEquals(0, taps) }
        capture("viewer-zoom")
        image.performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1f, zoom, 0.05f) }
        image.performTouchInput { pinch(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f), centerRight - androidx.compose.ui.geometry.Offset(40f, 0f), centerLeft, centerRight) }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(zoom > 1f) }
        rule.runOnIdle { reset.value++ }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1f, zoom, 0.05f) }
        image.performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.runOnIdle { active.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1f, zoom, 0.05f) }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getExternalFilesDir(null), "qa/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
