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
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.SourceHint
import com.android.launcher3.icons.ThemedBitmap
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.icons.mono.MonoThemedBitmap
import java.nio.ByteBuffer

/**
 * The Minimal icon style: stock's forced monochrome icons ([MonoIconThemeController]), with the
 * glyph of a legacy icon the right way round, so its key is dark with a light glyph like every
 * other key.
 *
 * Launcher makes a legacy (non-adaptive) icon adaptive by setting it on a white plate
 * (BaseIconFactory.wrapToAdaptiveIcon). Such an icon has no monochrome layer, so the style
 * generates one (MonochromeIconFactory): it draws the whole icon and takes each pixel's brightness
 * as ink. The white plate, the brightest part, became solid ink and the app's own picture a darker
 * hole in it, so the key came out light with a dark glyph (SIM Toolkit's, for example). Here the
 * glyph comes from the picture alone, as the plate is Launcher's and not part of the app's icon. An
 * adaptive icon of the same make (a plain white background behind an inset foreground, and no
 * monochrome layer) is treated alike, which turns its glyph the right way round too. If the picture
 * alone gives no ink at all (drawn in pure black), the stock glyph stays.
 */
class TallyMonoIconThemeController(
    private val base: IconThemeController = MonoIconThemeController(shouldForceThemeIcon = true)
) : IconThemeController by base {

    override fun createThemedBitmap(
        icon: AdaptiveIconDrawable,
        info: BitmapInfo,
        factory: BaseIconFactory,
        sourceHint: SourceHint?,
    ): ThemedBitmap {
        val picture =
            pictureOnPlate(icon) ?: return base.createThemedBitmap(icon, info, factory, sourceHint)
        val themed =
            base.createThemedBitmap(AdaptiveIconDrawable(null, picture), info, factory, sourceHint)
        return if (themed is MonoThemedBitmap && hasInk(themed.mono)) {
            themed
        } else {
            base.createThemedBitmap(icon, info, factory, sourceHint)
        }
    }

    companion object {
        /**
         * The picture of an icon set on a plain white plate, or null: an icon with no monochrome
         * layer whose background is white and whose foreground is inset, as Launcher wraps a legacy
         * icon.
         */
        @JvmStatic
        fun pictureOnPlate(icon: AdaptiveIconDrawable): Drawable? {
            if (icon.monochrome != null) return null
            val plate = icon.background as? ColorDrawable ?: return null
            val picture = icon.foreground as? InsetDrawable ?: return null
            return if (plate.color == Color.WHITE) picture else null
        }

        /** Whether a generated glyph has any ink (a hardware bitmap is taken to have some). */
        private fun hasInk(mono: Bitmap): Boolean {
            if (mono.config != Bitmap.Config.ALPHA_8) return true
            val pixels = ByteBuffer.allocate(mono.byteCount)
            mono.copyPixelsToBuffer(pixels)
            for (i in 0 until pixels.position()) if (pixels.get(i) != 0.toByte()) return true
            return false
        }
    }
}
