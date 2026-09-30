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
import android.graphics.Path
import android.graphics.Rect
import android.icu.text.ListFormatter
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Bundle
import android.os.SystemClock
import android.text.BoringLayout
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DraggableView
import com.android.launcher3.popup.Poppable
import com.android.launcher3.popup.PoppableType
import com.android.launcher3.tally.keycap.TallyKeycapLed
import com.android.launcher3.tally.lamp.TallyLampSize
import com.android.launcher3.tally.lamp.TallyLampState
import com.android.launcher3.tally.lamp.TallyLampView
import com.android.launcher3.tally.live.TallyLiveItem
import com.android.launcher3.tally.live.TallyLiveRules
import com.android.launcher3.util.SafeCloseable
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Home's tallies band, the prototype's `.tallies`: one row of 48 dp keys, a lamp, a name and a
 * readout each, with hairline dividers like a gauge cluster and colour only in the lamps. It shows
 * the most urgent thing live or failed now (failed, then live, then requested, then on) and, when
 * there are more than two, "+n more", which opens the notification shade. Two keys sit side by side
 * (one alone spans the row); from 150 % text they stack. With nothing live the band is empty, and
 * Home keeps its place, so the grid never moves.
 *
 * On Home, the band comes off as a widget does ([lift], [TallyHomeLift]): a long press anywhere on
 * it, and for TalkBack, the band's own node (named Tallies) with a widget's Remove action.
 */
class TallyTalliesRow(context: Context) : ViewGroup(context), DraggableView, Poppable {

    /** Called with a tapped thing (or null for "+n more") and its key. */
    var onTap: ((TallyLiveItem?, View) -> Unit)? = null

    /** How the band comes off Home, or null where it cannot (a preview). */
    var lift: TallyHomeLift? = null
        set(value) {
            field = value
            isScreenReaderFocusable = value != null
            contentDescription =
                if (value != null) context.getString(R.string.tally_home_tallies) else null
        }

    private val divider = resources.getDimensionPixelSize(R.dimen.tally_stroke_hairline)
    private val radius = resources.getDimension(R.dimen.tally_radius_m)
    /** The band's border, as wide as the dividers, drawn over the keys along the whole outline. */
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = divider.toFloat()
            color = context.getColor(R.color.tally_outline_variant)
        }
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val cells = arrayOf(Cell(context), Cell(context))
    private var shown = 0
    private var stacked = false
    private var items: List<TallyLiveItem> = emptyList()
    /** Picked up by a long press: hidden under the drag's image. */
    private var lifted = false
    /** Being drawn into the drag's image, which Launcher draws without the outline's clip. */
    private var drawingDragImage = false
    private val outlinePath = Path()

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
        updateVisibility()
        requestLayout()
    }

    /** Hides the band while it is picked up ([TallyHomeLift]). */
    fun setLifted(lifted: Boolean) {
        this.lifted = lifted
        updateVisibility()
    }

    private fun updateVisibility() {
        visibility =
            when {
                shown == 0 -> GONE
                lifted -> INVISIBLE
                else -> VISIBLE
            }
    }

    /** Updates the readouts (a chronometer's time); returns whether one is counting. */
    fun tick(): Boolean {
        var counting = false
        for (i in 0 until shown) counting = cells[i].refreshReadout() or counting
        return counting
    }

    /** Whether a readout is counting, as [tick] returns, without updating them. */
    fun isCounting(): Boolean {
        for (i in 0 until shown) if (cells[i].isCounting) return true
        return false
    }

    /**
     * How long from [now] (SystemClock.elapsedRealtime) the next [tick] should wait: until a
     * counting readout turns to its next second, and a few milliseconds more, as the system's
     * chronometer and the status bar chip wait, so the band and the chip turn together; at most a
     * second.
     */
    fun nextTickDelay(now: Long = SystemClock.elapsedRealtime()): Long {
        var delay = TICK_MS
        for (i in 0 until shown) cells[i].turnIn(now)?.let { delay = min(delay, it) }
        return delay + TURN_MARGIN_MS
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

    override fun draw(canvas: Canvas) {
        if (!drawingDragImage) {
            super.draw(canvas)
            return
        }
        outlinePath.reset()
        outlinePath.addRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            radius,
            radius,
            Path.Direction.CW,
        )
        val count = canvas.save()
        canvas.clipPath(outlinePath)
        super.draw(canvas)
        canvas.restoreToCount(count)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val lift = lift ?: return false
        lift.onTouchEvent(ev)
        return lift.hasPerformedLongPress()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val lift = lift ?: return super.onTouchEvent(ev)
        lift.onTouchEvent(ev)
        // The dividers and the border are the band too: keep the touch for its long press.
        return true
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        lift?.cancelLongPress()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        lift?.onInitializeAccessibilityNodeInfo(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        lift?.performAccessibilityAction(action) == true ||
            super.performAccessibilityAction(action, arguments)

    // DraggableView: lifted as a widget, the whole band.
    override fun getViewType(): Int = DraggableView.DRAGGABLE_WIDGET

    override fun getWorkspaceVisualDragBounds(bounds: Rect) {
        bounds.set(0, 0, width, height)
    }

    override fun prepareDrawDragView(): SafeCloseable {
        drawingDragImage = true
        return SafeCloseable { drawingDragImage = false }
    }

    override fun getPoppableType(): PoppableType = PoppableType.WIDGET

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
        /** The readout's pattern (its digits as zeros) and text size its width is fixed for. */
        private var fittedPattern: String? = null
        private var fittedTextSize = 0f
        /**
         * For a time that stands still ([TallyLiveItem.isPaused]): whether the key has room for the
         * time after "Paused" beside the whole name ([onMeasure]).
         */
        private var pausedTimeFits = true

        /** Whether the readout is a counting chronometer. */
        val isCounting: Boolean
            get() = item.let { it != null && it.chronometerBase != TallyLiveItem.NO_CHRONOMETER }

        /** How long from [now] until the readout turns to its next second, or null. */
        fun turnIn(now: Long): Long? = item?.let { turnOf(it, now) }

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
            setOnClickListener { onTap?.invoke(item, this) }
        }

        fun bindItem(newItem: TallyLiveItem) {
            item = newItem
            giveWay(readout = false)
            // A time that stands still is not live: its lamp is out, and its readout says Paused.
            lamp.setState(newItem.rowState)
            label.text = newItem.kindLabel ?: newItem.label
            refreshReadout(describe = true)
        }

        fun bindMore(hidden: List<TallyLiveItem>) {
            item = null
            giveWay(readout = true)
            var state: TallyLampState? = null
            for (h in hidden) state = TallyLiveRules.moreUrgent(state, h.rowState)
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
            if (readout) {
                // "Show" takes the space the name leaves: no fixed width.
                value.minWidth = 0
                value.maxWidth = Int.MAX_VALUE
                fittedPattern = null
            }
            label.layoutParams = labelParams
            value.layoutParams = valueParams
        }

        /**
         * Updates the readout, and its words for screen readers if [describe]: by default only
         * while an accessibility service is on, as they change every second (a service that asks
         * for them gets them current, [onInitializeAccessibilityNodeInfo]). Returns whether it is a
         * counting chronometer.
         */
        fun refreshReadout(describe: Boolean = accessibility?.isEnabled == true): Boolean {
            val current = item ?: return false
            val now = SystemClock.elapsedRealtime()
            val readout = readoutOf(current, now)
            val shown = shownReadout(current, readout)
            fitReadout(shown)
            if (!TextUtils.equals(value.text, shown)) value.text = shown
            if (describe) contentDescription = describeNow(current, now, readout)
            return current.chronometerBase != TallyLiveItem.NO_CHRONOMETER
        }

        /**
         * What the key shows for [current]'s [readout]: the readout, or for a time that stands
         * still "Paused · 04:12", or "Paused" alone where the key has no room for the time
         * ([pausedTimeFits]; its words for screen readers always have it).
         */
        private fun shownReadout(current: TallyLiveItem, readout: String): String =
            when {
                !current.isPaused -> readout
                pausedTimeFits && readout.isNotEmpty() -> pausedWithTime(readout)
                else -> context.getString(R.string.tally_state_paused)
            }

        private fun pausedWithTime(readout: String): String =
            context.getString(
                R.string.tally_readout_with_state,
                context.getString(R.string.tally_state_paused),
                readout,
            )

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val current = item
            if (
                current != null &&
                    current.isPaused &&
                    MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
            ) {
                val fits = pausedTimeFitsIn(MeasureSpec.getSize(widthMeasureSpec), current)
                if (fits != pausedTimeFits) {
                    pausedTimeFits = fits
                    refreshReadout(describe = false)
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        /**
         * Whether a key [width] wide has room for "Paused · 04:12" beside [current]'s whole name:
         * what the lamp and the gaps leave, as this layout shares it out.
         */
        private fun pausedTimeFitsIn(width: Int, current: TallyLiveItem): Boolean {
            lamp.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val lampParams = lamp.layoutParams as LayoutParams
            val room =
                width -
                    paddingStart -
                    paddingEnd -
                    lamp.measuredWidth -
                    lampParams.marginStart -
                    lampParams.marginEnd -
                    (value.layoutParams as LayoutParams).marginStart
            val name =
                ceil(Layout.getDesiredWidth(label.text, label.paint)).toInt() +
                    label.compoundPaddingLeft +
                    label.compoundPaddingRight
            return name + readoutWidth(pausedWithTime(readoutOf(current))) <= room
        }

        /** The readout's width for [text] (the widest of its pattern). */
        private fun readoutWidth(text: String): Int =
            ceil(widestOf(text, value.paint)).toInt() +
                value.compoundPaddingLeft +
                value.compoundPaddingRight

        private fun describeNow(
            current: TallyLiveItem,
            now: Long,
            readout: String = readoutOf(current, now),
        ): CharSequence {
            val spoken = spokenReadoutOf(current, now, resources.configuration.locales[0])
            return describe(context, current, readout, spoken)
        }

        /**
         * Fixes the readout's width at the widest text of [readout]'s pattern (the same text with
         * any digits): a new time then only redraws the readout, where a text view as wide as its
         * text lays Home out again for every new text. Sofia Sans's tabular digits (the Readout
         * style's tnum) are all as wide, so the time never moves within it.
         */
        private fun fitReadout(readout: String) {
            val pattern = patternOf(readout)
            val textSize = value.textSize
            if (pattern == fittedPattern && textSize == fittedTextSize) return
            fittedPattern = pattern
            fittedTextSize = textSize
            value.setWidth(readoutWidth(pattern))
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            // The words as of now, whether or not the description has followed the count.
            item?.let { info.contentDescription = describeNow(it, SystemClock.elapsedRealtime()) }
        }
    }

    companion object {
        private const val GAP_DP = 10f
        private const val PADDING_START_DP = 13f
        private const val PADDING_END_DP = 14f
        private const val TICK_MS = 1000L
        /** How long past a readout's turn a tick waits, as android.widget.Chronometer's 3 ms. */
        private const val TURN_MARGIN_MS = 3L

        /** The last time format made, with its locale: making one takes milliseconds. */
        @Volatile private var spokenFormat: SpokenFormat? = null

        private class SpokenFormat(val locale: Locale, val format: MeasureFormat)

        /** [c] as [digit] in its own digits when it is a decimal digit, else [c]. */
        private fun digitAs(c: Char, digit: Int): Char =
            if (Character.isDigit(c)) c - Character.digit(c, 10) + digit else c

        /** [text] with its digits as zeros: the pattern of every text as wide in tabular digits. */
        private fun patternOf(text: String): String = buildString {
            for (c in text) append(digitAs(c, 0))
        }

        /**
         * The width in [paint] of the widest text of [text]'s pattern (the same with any digit).
         */
        private fun widestOf(text: String, paint: TextPaint): Float {
            val pattern = patternOf(text)
            var widest = 0f
            for (digit in 0..9) {
                val any = buildString { for (c in pattern) append(digitAs(c, digit)) }
                val boring = BoringLayout.isBoring(any, paint)?.width?.toFloat() ?: 0f
                widest = max(widest, max(boring, Layout.getDesiredWidth(any, paint)))
            }
            return widest
        }

        /**
         * A thing's readout: its chronometer ("4:12", counting down or up, as the system draws it),
         * else its paused time ("4:12", still), else its progress ("34%"), else nothing.
         */
        @JvmStatic
        fun readoutOf(item: TallyLiveItem, now: Long = SystemClock.elapsedRealtime()): String {
            readoutSecondsOf(item, now)?.let {
                return DateUtils.formatElapsedTime(it)
            }
            if (item.progressPermille != TallyLiveItem.NO_PROGRESS) {
                return NumberFormat.getPercentInstance().format(item.progressPermille / 1000.0)
            }
            return ""
        }

        /**
         * A thing's time in whole seconds (a chronometer's or a paused one), or null. A counting
         * time reads as the status bar chip reads it (SystemUI's ChronometerState, and the
         * notification's chronometer between its ticks): a count-up truncated, a count-down as
         * `Math.round((left - 499) / 1000f)` in whole milliseconds.
         */
        private fun readoutSecondsOf(item: TallyLiveItem, now: Long): Long? {
            if (item.chronometerBase != TallyLiveItem.NO_CHRONOMETER) {
                return if (item.countDown) (max(0L, item.chronometerBase - now) + 1) / 1000
                else max(0L, now - item.chronometerBase) / 1000
            }
            if (item.pausedSeconds != TallyLiveItem.NO_PAUSED_TIME) return item.pausedSeconds
            return null
        }

        /**
         * How long from [now] until [item]'s counting time ([readoutSecondsOf]) turns to its next
         * second, or null when it does not count or has stopped at zero.
         */
        @JvmStatic
        fun turnOf(item: TallyLiveItem, now: Long): Long? {
            val base = item.chronometerBase
            if (base == TallyLiveItem.NO_CHRONOMETER) return null
            if (item.countDown) {
                // (left + 1) / 1000 drops as left + 1 goes below a whole second.
                val left = base - now
                return if (left < 0) null else (left + 1) % 1000 + 1
            }
            val elapsed = now - base
            return if (elapsed < 0) TICK_MS - elapsed else TICK_MS - elapsed % TICK_MS
        }

        /**
         * A thing's readout as screen readers say it: a time in the user's language, each unit only
         * when it is not zero ("9 minutes, 57 seconds", "1 minute", "0 seconds"), as the system's
         * chronometer reads its time; else [readoutOf] (a percentage), or nothing.
         */
        @JvmStatic
        fun spokenReadoutOf(
            item: TallyLiveItem,
            now: Long = SystemClock.elapsedRealtime(),
            locale: Locale = Locale.getDefault(),
        ): String {
            val seconds = readoutSecondsOf(item, now) ?: return readoutOf(item, now)
            val measures = ArrayList<Measure>(3)
            if (seconds >= 3600) measures.add(Measure(seconds / 3600, MeasureUnit.HOUR))
            if (seconds % 3600 >= 60) measures.add(Measure(seconds % 3600 / 60, MeasureUnit.MINUTE))
            // Seconds when not zero or when there is nothing larger: never "1 minute, 0 seconds".
            if (seconds % 60 > 0 || measures.isEmpty()) {
                measures.add(Measure(seconds % 60, MeasureUnit.SECOND))
            }
            val cached = spokenFormat
            val format =
                if (cached != null && cached.locale == locale) cached.format
                else
                    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).also {
                        spokenFormat = SpokenFormat(locale, it)
                    }
            return format.formatMeasures(*measures.toTypedArray())
        }

        /**
         * A thing's words for screen readers: its name, with its app's after a kind the row shows
         * in its place ("Timer, Clock"), then its lamp's state (Paused for a live time that stands
         * still) and its readout as [spoken]: "Timer, Clock, Active, 9 minutes, 57 seconds",
         * "Timer, Clock, Paused, 6 minutes, 58 seconds" or "Files, Active, 34%".
         */
        @JvmStatic
        fun describe(
            context: Context,
            item: TallyLiveItem,
            readout: String,
            spoken: String = readout,
        ): CharSequence {
            val kind = item.kindLabel
            val name =
                if (kind == null) item.label
                else context.getString(R.string.tally_kind_of_app, kind, item.label)
            val description =
                if (item.isPaused) {
                    context.getString(
                        R.string.tally_key_with_state,
                        name,
                        context.getString(R.string.tally_state_paused),
                    )
                } else {
                    TallyKeycapLed.describe(context, name, item.state)
                }
            return if (spoken.isEmpty()) description
            else context.getString(R.string.tally_key_with_state, description, spoken)
        }
    }
}
