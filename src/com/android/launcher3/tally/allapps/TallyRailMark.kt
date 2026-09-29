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
import android.graphics.Path
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR
import kotlin.math.roundToInt

/**
 * The ring the letter rail ([TallyLetterRail]) puts round the first app of the letter it jumped to,
 * as the prototype's flash: 2 dp of the accent colour, 3 dp outside the key, following the icon
 * shape. It stays until the rail jumps again, the list is dragged, or All apps goes back to the top
 * of its list (closing, switching tabs, searching).
 */
internal class TallyRailMark(context: Context) : RecyclerView.ItemDecoration() {
    private val themeManager = ThemeManager.INSTANCE.get(context)
    private val strokePx = context.resources.getDimension(R.dimen.tally_focus_ring_width)
    private val offsetPx = OFFSET_DP * context.resources.displayMetrics.density
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = context.getColor(R.color.tally_accent)
        }
    private val iconBounds = Rect()
    private val ringPath = Path()
    private var ringSize = -1
    private var ringShape: ShapeDelegate? = null

    private var list: RecyclerView? = null
    private var position = RecyclerView.NO_POSITION

    /** Marks the app at [adapterPosition] in [rv], in place of any app marked before. */
    fun show(rv: RecyclerView, adapterPosition: Int) {
        if (list === rv && position == adapterPosition) return
        clear()
        list = rv
        position = adapterPosition
        rv.invalidate()
    }

    /** Takes the ring away. */
    fun clear() {
        if (position == RecyclerView.NO_POSITION) return
        position = RecyclerView.NO_POSITION
        list?.invalidate()
        list = null
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent !== list || position == RecyclerView.NO_POSITION) return
        val icon = parent.findViewHolderForAdapterPosition(position)?.itemView as? BubbleTextView
        if (icon == null) return
        icon.getIconBounds(iconBounds)
        // The key is the icon's visible area; the ring's centre line is 3 dp + half its width out.
        val size =
            (iconBounds.width() * ICON_VISIBLE_AREA_FACTOR + 2 * (offsetPx + strokePx / 2))
                .roundToInt()
        if (size <= 0) return
        val shape = themeManager.iconShape
        if (size != ringSize || shape !== ringShape) {
            ringPath.set(shape.getPath(Rect(0, 0, size, size)))
            ringSize = size
            ringShape = shape
        }
        val count = c.save()
        c.translate(
            icon.left + icon.translationX + iconBounds.exactCenterX() - size / 2f,
            icon.top + icon.translationY + iconBounds.exactCenterY() - size / 2f,
        )
        c.drawPath(ringPath, paint)
        c.restoreToCount(count)
    }

    private companion object {
        /** The ring's gap from the key (the prototype's outline offset). */
        const val OFFSET_DP = 3f
    }
}
