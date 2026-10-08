/*
 * Copyright (C) 2026 The DiamaneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.tally.keycap

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.platform.uiautomatorhelpers.DeviceHelpers.context
import android.util.DisplayMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.Flags
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.icons.mono.MonoThemedBitmap
import com.android.launcher3.icons.tally.TallyMonoIconThemeController
import com.android.launcher3.util.LauncherMultivalentJUnit.Companion.isRunningInRobolectric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyMonoIconThemeControllerTest {

    @get:Rule val mSetFlagsRule = SetFlagsRule()

    private val iconFactory = BaseIconFactory(context, DisplayMetrics.DENSITY_MEDIUM, 30)

    @Test
    fun `a legacy icon picture is found on its plate`() {
        val legacy = iconFactory.wrapToAdaptiveIcon(ColorDrawable(Color.GRAY))
        assertSame(legacy.foreground, TallyMonoIconThemeController.pictureOnPlate(legacy))
    }

    @Test
    fun `other icons have no plate`() {
        val picture = InsetDrawable(ColorDrawable(Color.GRAY), 0.25f)
        val white = ColorDrawable(Color.WHITE)
        // Their own monochrome layer, a coloured background, a plain foreground.
        val withMono = AdaptiveIconDrawable(white, picture, ColorDrawable(Color.RED))
        assertNull(TallyMonoIconThemeController.pictureOnPlate(withMono))
        val blue = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), picture)
        assertNull(TallyMonoIconThemeController.pictureOnPlate(blue))
        val plain = AdaptiveIconDrawable(white, ColorDrawable(Color.GRAY))
        assertNull(TallyMonoIconThemeController.pictureOnPlate(plain))
    }

    @Test
    @EnableFlags(Flags.FLAG_FORCE_MONOCHROME_APP_ICONS)
    fun `a legacy icon glyph is its picture and not the plate`() {
        // Robolectric does not draw into 8-bit bitmaps.
        assumeFalse(isRunningInRobolectric)
        val legacy = iconFactory.wrapToAdaptiveIcon(ColorDrawable(Color.GRAY))
        val info = iconFactory.createBadgedIconBitmap(legacy)
        val stock =
            MonoIconThemeController(shouldForceThemeIcon = true)
                .createThemedBitmap(legacy, info, iconFactory) as MonoThemedBitmap
        val tally =
            TallyMonoIconThemeController().createThemedBitmap(legacy, info, iconFactory)
                as MonoThemedBitmap
        // Stock inks the plate (light key) and leaves the grey picture darker (dark glyph).
        assertTrue(inkAtPlate(stock.mono) > inkAtPicture(stock.mono))
        // The Minimal style inks the picture and leaves the plate clear (dark key, light glyph).
        assertEquals(0, inkAtPlate(tally.mono))
        assertTrue(inkAtPicture(tally.mono) > 0)
    }

    @Test
    @EnableFlags(Flags.FLAG_FORCE_MONOCHROME_APP_ICONS)
    fun `a black picture glyph is its shape`() {
        assumeFalse(isRunningInRobolectric)
        val legacy = iconFactory.wrapToAdaptiveIcon(ColorDrawable(Color.BLACK))
        val info = iconFactory.createBadgedIconBitmap(legacy)
        val tally =
            TallyMonoIconThemeController().createThemedBitmap(legacy, info, iconFactory)
                as MonoThemedBitmap
        assertEquals(0, inkAtPlate(tally.mono))
        assertTrue(inkAtPicture(tally.mono) > 0)
    }

    @Test
    fun `a dark glyph on a light plate is found`() {
        // Robolectric does not draw these drawables.
        assumeFalse(isRunningInRobolectric)
        val glyph = InsetDrawable(ColorDrawable(Color.BLACK), 0.3f)
        val shortcut = AdaptiveIconDrawable(ColorDrawable(0xFFF5F5F5.toInt()), glyph)
        assertTrue(TallyMonoIconThemeController.glyphOnLightPlate(shortcut) != null)
    }

    @Test
    fun `other icons have no dark glyph on a light plate`() {
        assumeFalse(isRunningInRobolectric)
        val dark = InsetDrawable(ColorDrawable(Color.BLACK), 0.3f)
        val light = InsetDrawable(ColorDrawable(Color.LTGRAY), 0.3f)
        val plate = ColorDrawable(0xFFF5F5F5.toInt())
        // Their own monochrome layer, a dark plate, a light glyph.
        assertNull(
            TallyMonoIconThemeController.glyphOnLightPlate(
                AdaptiveIconDrawable(plate, dark, ColorDrawable(Color.RED))
            )
        )
        assertNull(
            TallyMonoIconThemeController.glyphOnLightPlate(
                AdaptiveIconDrawable(ColorDrawable(Color.DKGRAY), dark)
            )
        )
        assertNull(
            TallyMonoIconThemeController.glyphOnLightPlate(AdaptiveIconDrawable(plate, light))
        )
    }

    /**
     * The ink near the key's top edge, on the plate: a legacy picture covers the middle 70 % of the
     * key.
     */
    private fun inkAtPlate(mono: Bitmap) =
        Color.alpha(mono.getPixel(mono.width / 2, mono.height / 20))

    /** The ink at the key's centre, on the legacy picture. */
    private fun inkAtPicture(mono: Bitmap) =
        Color.alpha(mono.getPixel(mono.width / 2, mono.height / 2))
}
