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

package com.android.launcher3.tally.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.icu.text.ListFormatter
import android.icu.text.NumberFormat
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.R
import com.android.launcher3.tally.keycap.TallyKeycapLed
import com.android.launcher3.tally.lamp.TallyLampSize
import com.android.launcher3.tally.lamp.TallyLampState
import com.android.launcher3.tally.lamp.TallyLampView
import com.android.launcher3.tally.live.TallyLiveItem
import com.android.launcher3.tally.live.TallyLiveRules
import kotlin.math.ceil
import kotlin.math.max

/**
 * Home's tallies band, the prototype's `.tallies`: one row of 48 dp keys, a lamp, a name and a
 * readout each, with hairline dividers like a gauge cluster and colour only in the lamps. It shows
 * the most urgent thing live or failed now (failed, then live, then requested, then on) and, when
 * there are more than two, "+n more", which opens the notification shade. Two keys sit side by side
 * (one alone spans the row); from 150 % text they stack. With nothing live the band is empty, and
 * Home keeps its place, so the grid never moves.
 */
class TallyTalliesRow(context: Context) : ViewGroup(context) {

    /** Called with a tapped thing, or null for "+n more". */
    var onTap: ((TallyLiveItem?) -> Unit)? = null

    private val divider = resources.getDimensionPixelSize(R.dimen.tally_stroke_hairline)
    private val radius = resources.getDimension(R.dimen.tally_radius_m)
    /** The band's border, as wide as the dividers, drawn over the keys along the whole outline. */
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = divider.toFloat()
            color = context.getColor(R.color.tally_outline_variant)
        }
    private val cells = arrayOf(Cell(context), Cell(context))
    private var shown = 0
    private var stacked = false
    private var items: List<TallyLiveItem> = emptyList()

    init {
        setBackgroundColor(context.getColor(R.color.tally_outline_variant))
        clipToOutline = true
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        for (cell in cells) addView(cell)
        visibility = GONE
    }

    /** Shows [newItems] (the row's things, most urgent first). */
    fun setItems(newItems: List<TallyLiveItem>) {
        items = newItems
        stacked = resources.configuration.fontScale >= TallyHomeLayout.LARGE_TEXT_SCALE
        // At most two keys: two things, or the most urgent one and "+n more".
        shown = if (newItems.size <= 2) newItems.size else 2
        for (i in cells.indices) cells[i].visibility = if (i < shown) VISIBLE else GONE
        if (newItems.size <= 2) {
            for (i in 0 until shown) cells[i].bindItem(newItems[i])
        } else {
            cells[0].bindItem(newItems[0])
            cells[1].bindMore(newItems.subList(1, newItems.size))
        }
        visibility = if (shown == 0) GONE else VISIBLE
        requestLayout()
    }

    /** Updates the readouts (a chronometer's time); returns whether one is counting. */
    fun tick(): Boolean {
        var counting = false
        for (i in 0 until shown) counting = cells[i].refreshReadout() or counting
        return counting
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (shown == 0) {
            setMeasuredDimension(width, 0)
            return
        }
        val inner = width - 2 * divider
        var height: Int
        if (shown == 1 || stacked) {
            height = divider
            for (i in 0 until shown) {
                cells[i].measure(exactly(inner), MeasureSpec.UNSPECIFIED)
                height += cells[i].measuredHeight + divider
            }
        } else {
            val half = (inner - divider) / 2
            var tallest = 0
            for (i in 0 until shown) {
                cells[i].measure(exactly(half), MeasureSpec.UNSPECIFIED)
                tallest = max(tallest, cells[i].measuredHeight)
            }
            // Both keys as tall as the taller one.
            for (i in 0 until shown) cells[i].measure(exactly(half), exactly(tallest))
            height = tallest + 2 * divider
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (shown == 0) return
        var y = divider
        if (shown == 1 || stacked) {
            for (i in 0 until shown) {
                val c = cells[i]
                c.layout(divider, y, divider + c.measuredWidth, y + c.measuredHeight)
                y += c.measuredHeight + divider
            }
        } else {
            // Start to end, as the prototype's grid reads.
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            var x = divider
            for (i in 0 until shown) {
                val c = cells[if (rtl) shown - 1 - i else i]
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + divider
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // The background shows as the dividers and the straight edges, but the keys are square
        // and reach into the rounded corners: the border is drawn over them, along the outline.
        val inset = divider / 2f
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            radius - inset,
            radius - inset,
            borderPaint,
        )
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    /** One key of the band: a lamp, a name and a readout. */
    private inner class Cell(context: Context) : LinearLayout(context) {
        private val lamp = TallyLampView(context).apply { setLampSize(TallyLampSize.LARGE) }
        private val label =
            TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_Tally_Label)
                setTextColor(context.getColor(R.color.tally_ink))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        private val value =
            TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_Tally_Readout)
                setTextColor(context.getColor(R.color.tally_ink))
                maxLines = 1
            }
        private var item: TallyLiveItem? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The lamp's ring of light reaches into the start padding (see below).
            clipToPadding = false
            minimumHeight = resources.getDimensionPixelSize(R.dimen.tally_target_min)
            setBackgroundColor(context.getColor(R.color.tally_surface))
            val gap = GAP_DP * resources.displayMetrics.density
            setPaddingRelative(
                (PADDING_START_DP * resources.displayMetrics.density).toInt(),
                0,
                (PADDING_END_DP * resources.displayMetrics.density).toInt(),
                0,
            )
            // The lamp's view reaches past the lamp for its ring of light: line the lamp up.
            addView(
                lamp,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = -lamp.haloReachPx
                    marginEnd = (gap - lamp.haloReachPx).toInt()
                },
            )
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(
                value,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = gap.toInt()
                },
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap?.invoke(item) }
        }

        fun bindItem(newItem: TallyLiveItem) {
            item = newItem
            giveWay(readout = false)
            lamp.setState(newItem.state)
            label.text = newItem.label
            refreshReadout()
        }

        fun bindMore(hidden: List<TallyLiveItem>) {
            item = null
            giveWay(readout = true)
            var state: TallyLampState? = null
            for (h in hidden) state = TallyLiveRules.moreUrgent(state, h.state)
            lamp.setState(state ?: TallyLampState.OFF)
            label.text = resources.getQuantityString(R.plurals.tally_more, hidden.size, hidden.size)
            value.setText(R.string.tally_more_show)
            contentDescription =
                resources.getQuantityString(
                    R.plurals.tally_more_description,
                    hidden.size,
                    hidden.size,
                    ListFormatter.getInstance().format(hidden.map { it.label.toString() }),
                )
        }

        /**
         * Which words give way in a narrow key: a thing's name (its readout is the news), or the
         * readout of "+n more" ("Show", long in some languages), so the count stays whole.
         */
        private fun giveWay(readout: Boolean) {
            val labelParams = label.layoutParams as LayoutParams
            val valueParams = value.layoutParams as LayoutParams
            labelParams.width = if (readout) LayoutParams.WRAP_CONTENT else 0
            labelParams.weight = if (readout) 0f else 1f
            valueParams.width = if (readout) 0 else LayoutParams.WRAP_CONTENT
            valueParams.weight = if (readout) 1f else 0f
            value.gravity = (if (readout) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            value.ellipsize = if (readout) TextUtils.TruncateAt.END else null
            label.layoutParams = labelParams
            value.layoutParams = valueParams
        }

        /** Updates the readout; returns whether it is a counting chronometer. */
        fun refreshReadout(): Boolean {
            val current = item ?: return false
            val readout = readoutOf(current)
            if (!TextUtils.equals(value.text, readout)) value.text = readout
            var description = TallyKeycapLed.describe(context, current.label, current.state)
            if (readout.isNotEmpty()) {
                description = context.getString(R.string.tally_key_with_state, description, readout)
            }
            contentDescription = description
            return current.chronometerBase != TallyLiveItem.NO_CHRONOMETER
        }
    }

    companion object {
        private const val GAP_DP = 10f
        private const val PADDING_START_DP = 13f
        private const val PADDING_END_DP = 14f

        /**
         * A thing's readout: its chronometer ("4:12", counting down or up, as the system draws it),
         * else its progress ("34%"), else nothing.
         */
        @JvmStatic
        fun readoutOf(item: TallyLiveItem, now: Long = SystemClock.elapsedRealtime()): String {
            if (item.chronometerBase != TallyLiveItem.NO_CHRONOMETER) {
                val millis =
                    if (item.countDown) item.chronometerBase - now else now - item.chronometerBase
                // A count-down shows the seconds it has started, as a timer's display does.
                val seconds =
                    if (item.countDown) ceil(max(0L, millis) / 1000.0).toLong()
                    else max(0L, millis) / 1000
                return DateUtils.formatElapsedTime(seconds)
            }
            if (item.progressPermille != TallyLiveItem.NO_PROGRESS) {
                return NumberFormat.getPercentInstance().format(item.progressPermille / 1000.0)
            }
            return ""
        }
    }
}
