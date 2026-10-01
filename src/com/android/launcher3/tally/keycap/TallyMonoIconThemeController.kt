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
import android.graphics.Canvas
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
 * monochrome layer) is treated alike, which turns its glyph the right way round too.
 *
 * A picture drawn dark on a plain light plate gives little or no ink by its brightness, so its
 * shape is taken as the glyph instead, as the monochrome layer the app did not draw
 * ([glyphOnLightPlate]). Material's shortcut icons are made so (Clock's "Create new alarm" is a
 * black glyph on #F5F5F5), and they came out light with a dark glyph in an app's long-press menu,
 * among dark keys. If a picture on a white plate gives no ink either way, the stock glyph stays.
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
        glyphOnLightPlate(icon)?.let { glyph ->
            return base.createThemedBitmap(
                AdaptiveIconDrawable(icon.background, icon.foreground, glyph),
                info,
                factory,
                sourceHint,
            )
        }
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

        /** A plate at least this light (relative luminance) takes a dark glyph's shape. */
        private const val LIGHT_PLATE = 0.75f
        /** A glyph whose ink is at most this light (relative luminance) on average is dark. */
        private const val DARK_GLYPH = 0.2f
        /** The size the glyph is drawn at to tell its colour. */
        private const val SAMPLE_PX = 48

        /**
         * The glyph of an icon drawn dark on a plain light plate, or null: an icon with no
         * monochrome layer whose background is one opaque light colour and whose foreground's ink
         * is dark (on average at most [DARK_GLYPH] luminance, the plate at least [LIGHT_PLATE]).
         */
        @JvmStatic
        fun glyphOnLightPlate(icon: AdaptiveIconDrawable): Drawable? {
            if (icon.monochrome != null) return null
            val plate = icon.background as? ColorDrawable ?: return null
            if (Color.alpha(plate.color) != 255 || Color.luminance(plate.color) < LIGHT_PLATE) {
                return null
            }
            val glyph = icon.foreground?.constantState?.newDrawable()?.mutate() ?: return null
            val sample = Bitmap.createBitmap(SAMPLE_PX, SAMPLE_PX, Bitmap.Config.ARGB_8888)
            try {
                glyph.setBounds(0, 0, SAMPLE_PX, SAMPLE_PX)
                glyph.draw(Canvas(sample))
            } catch (e: IllegalArgumentException) {
                // A hardware bitmap cannot be drawn in software: leave the icon to the others.
                return null
            }
            val pixels = IntArray(SAMPLE_PX * SAMPLE_PX)
            sample.getPixels(pixels, 0, SAMPLE_PX, 0, 0, SAMPLE_PX, SAMPLE_PX)
            sample.recycle()
            var ink = 0f
            var light = 0f
            for (pixel in pixels) {
                val alpha = Color.alpha(pixel) / 255f
                if (alpha == 0f) continue
                ink += alpha
                light += alpha * Color.luminance(pixel or (0xFF shl 24))
            }
            return if (ink > 0f && light / ink <= DARK_GLYPH) glyph else null
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
