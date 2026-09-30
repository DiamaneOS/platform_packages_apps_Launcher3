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

package com.android.launcher3.tally.allapps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.android.launcher3.R
import com.android.launcher3.tally.home.TallyHomeLayout
import kotlin.math.roundToInt

/**
 * All apps' search field as the prototype's (`.aa-search`), beside Home's search slot
 * ([com.android.launcher3.tally.home.TallySearchSlot]): the search icon at the slot's 24 dp, in
 * ink, 10 dp before the words, and words that stop growing at 130 % text, as the slot's do. The
 * icon is part of the hint, so it goes while the user types, as stock's does.
 */
object TallySearchField {
    /** The gap between the icon and the words (the prototype's 10 dp). */
    private const val ICON_GAP_DP = 10f
    /** The words' size (sp) before their cap: TextAppearance.Tally.Item's. */
    private const val TEXT_SP = 16f

    /** [hint] with the search icon before it. */
    @JvmStatic
    fun hintWithIcon(context: Context, hint: CharSequence): CharSequence =
        SpannableString(" $hint").apply {
            setSpan(IconSpan(context), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** Stops [field]'s words growing past 130 % text. */
    @JvmStatic
    fun capTextSize(field: TextView) {
        val metrics = field.resources.displayMetrics
        val capPx =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SP, metrics) *
                TallyHomeLayout.TEXT_SCALE_CAP
        if (field.textSize > capPx) field.setTextSize(TypedValue.COMPLEX_UNIT_PX, capPx)
    }

    /**
     * The icon, centred on the line and followed by the gap. It leaves the line's height to the
     * words, so the words sit where the typed text will.
     */
    private class IconSpan(context: Context) : ReplacementSpan() {
        private val icon =
            requireNotNull(context.getDrawable(R.drawable.ic_allapps_search)).mutate().apply {
                setTint(context.getColor(R.color.tally_ink))
            }
        private val size = context.resources.getDimensionPixelSize(R.dimen.tally_icon_size)
        private val gap =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    ICON_GAP_DP,
                    context.resources.displayMetrics,
                )
                .roundToInt()
        private val rtl =
            context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int = size + gap

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            // At the start of the space the span takes, the gap between it and the words.
            val left = (if (rtl) x + gap else x).roundToInt()
            val iconTop = (top + bottom - size) / 2
            icon.setBounds(left, iconTop, left + size, iconTop + size)
            icon.draw(canvas)
        }
    }
}
