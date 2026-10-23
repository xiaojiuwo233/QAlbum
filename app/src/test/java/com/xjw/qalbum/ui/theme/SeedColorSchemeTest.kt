package com.xjw.qalbum.ui.theme

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.xjw.qalbum.domain.model.PaletteStyle
import com.xjw.qalbum.domain.model.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@SuppressLint("RestrictedApi")
class SeedColorSchemeTest {

    private val brand = ThemeSettings.DEFAULT_SEED_COLOR

    @Test
    fun `默认主题色与鲜明风格就是旧版的固定配色`() {
        val light = seedColorScheme(brand, PaletteStyle.VIBRANT, dark = false)
        assertEquals(Color(0xFF005DB7), light.primary)
        assertEquals(Color(0xFFD6E3FF), light.primaryContainer)
        assertEquals(Color(0xFF575C7E), light.secondary)
        assertEquals(Color(0xFF5F588A), light.tertiary)
        assertEquals(Color(0xFFF9F9FF), light.surface)
        assertEquals(Color(0xFFEAEEFA), light.surfaceContainer)
        assertEquals(Color(0xFFDFE2EF), light.surfaceContainerHighest)
        assertEquals(Color(0xFFBA1A1A), light.error)

        val dark = seedColorScheme(brand, PaletteStyle.VIBRANT, dark = true)
        assertEquals(Color(0xFFA9C7FF), dark.primary)
        assertEquals(Color(0xFF00468C), dark.primaryContainer)
        assertEquals(Color(0xFFBFC4EB), dark.secondary)
        assertEquals(Color(0xFF0F131C), dark.surface)
        assertEquals(Color(0xFF0A0E17), dark.surfaceContainerLowest)
        assertEquals(Color(0xFF8B919F), dark.outline)
    }

    @Test
    fun `四种配色风格各不相同`() {
        val schemes = PaletteStyle.entries.map { seedColorScheme(brand, it, dark = false) }
        val signatures = schemes.map { Triple(it.primary, it.secondary, it.tertiary) }
        assertEquals(PaletteStyle.entries.size, signatures.toSet().size)
    }

    @Test
    fun `鲜明风格的主色保留主题色的色相`() {
        listOf(0xFFE53935, 0xFF2E7D32, 0xFF8E24AA, 0xFFF9A825).forEach { argb ->
            val seed = argb.toInt()
            val primary = seedColorScheme(seed, PaletteStyle.VIBRANT, dark = false).primary
            val diff = hueDistance(Hct.fromInt(seed).hue, Hct.fromInt(primary.toArgb()).hue)
            assertTrue("seed ${Integer.toHexString(seed)} hue moved $diff", diff < 3.0)
        }
    }

    @Test
    fun `深浅色的主色明暗相反`() {
        val light = seedColorScheme(brand, PaletteStyle.TONAL_SPOT, dark = false)
        val dark = seedColorScheme(brand, PaletteStyle.TONAL_SPOT, dark = true)
        assertTrue(Hct.fromInt(light.primary.toArgb()).tone < 50)
        assertTrue(Hct.fromInt(dark.primary.toArgb()).tone > 50)
        assertTrue(Hct.fromInt(dark.surface.toArgb()).tone < Hct.fromInt(light.surface.toArgb()).tone)
    }

    @Test
    fun `纯黑背景只换背景和最低层容器`() {
        val dark = seedColorScheme(brand, PaletteStyle.VIBRANT, dark = true)
        val black = dark.withPureBlack()
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.surfaceContainerLowest)
        assertEquals(dark.surfaceContainer, black.surfaceContainer)
        assertEquals(dark.surfaceContainerHigh, black.surfaceContainerHigh)
        assertEquals(dark.primary, black.primary)
    }

    private fun hueDistance(a: Double, b: Double): Double = abs(a - b).let { if (it > 180) 360 - it else it }
}
