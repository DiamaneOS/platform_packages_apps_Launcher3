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

package com.android.quickstep.tally

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.launcher3.R
import kotlin.math.max

/**
 * Recents' "Still running · Stop" row (Tally), under the action keys: a live lamp, "<app> is
 * running" and a Stop key, as the prototype's row (32 dp, 8 dp corners, a hairline edge; the Stop
 * key 28 dp with 4 dp corners), growing with the text size.
 *
 * The Stop key refuses touches that come through another window over it, as the whole row does
 * (`filterTouchesWhenObscured`, set in its layout): an app's overlay can cover the launcher's
 * window. Its touch target is at least 48 × 48 dp around the 28 dp key. A touch on the rest of the
 * row does nothing, so it never falls through to Recents behind it (which goes Home on a tap).
 *
 * It fades with the action keys (see [setActionsAlpha]) and shows only while [TallyStillRunning]
 * has a row. It says nothing but its words: the lamp is not for accessibility.
 */
class TallyStillRunningView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    ViewGroup(context, attrs), TallyStillRunning.RowView {

    private val rowMinHeight = resources.getDimensionPixelSize(R.dimen.tally_chip_height)
    private val rowPaddingStart =
        resources.getDimensionPixelSize(R.dimen.tally_recents_still_running_padding_start)
    private val rowPaddingEnd = resources.getDimensionPixelSize(R.dimen.tally_space_xs)
    private val rowPaddingVertical =
        resources.getDimensionPixelSize(R.dimen.tally_recents_still_running_padding_vertical)
    private val rowRadius = resources.getDimension(R.dimen.tally_radius_s)
    private val gap = resources.getDimensionPixelSize(R.dimen.tally_space_s)
    private val keyMinHeight =
        resources.getDimensionPixelSize(R.dimen.tally_recents_stop_key_height)
    private val keyPaddingHorizontal = resources.getDimensionPixelSize(R.dimen.tally_space_m)
    private val keyPaddingVertical =
        resources.getDimensionPixelSize(R.dimen.tally_recents_stop_key_padding_vertical)
    private val targetMin = resources.getDimensionPixelSize(R.dimen.tally_target_min)
    private val sideMargin = resources.getDimensionPixelSize(R.dimen.tally_space_l)

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.tally_surface) }
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimension(R.dimen.tally_stroke_hairline)
            color = context.getColor(R.color.tally_outline_variant)
        }

    private lateinit var lamp: ImageView
    private lateinit var text: TextView
    private lateinit var stopKey: TextView
    private lateinit var stopKeyDrawable: TallyStopKeyDrawable
    private var lampSize = 0
    private var lampBox = 0

    // Measured in onMeasure, placed in onLayout.
    private var rowWidth = 0
    private var rowHeight = 0
    private var keyWidth = 0
    private var keyHeight = 0
    private val rowRect = Rect()
    private val rowRectF = RectF()

    private var controller: TallyStillRunning? = null
    private var row: StillRunningModel.Row? = null
    private var rowShownAt = 0L
    private var actionsAlpha = FloatArray(0)

    override fun onFinishInflate() {
        super.onFinishInflate()
        lamp = findViewById(R.id.tally_still_running_lamp)
        text = findViewById(R.id.tally_still_running_text)
        stopKey = findViewById(R.id.tally_still_running_stop)
        stopKeyDrawable = TallyStopKeyDrawable(context)
        stopKey.background = stopKeyDrawable
        stopKey.defaultFocusHighlightEnabled = false
        stopKey.setOnClickListener { onStopClicked() }
        updateLamp()
        applyAlpha()
    }

    /** Where [TallyStillRunning] tells this row what to show. */
    fun setController(controller: TallyStillRunning) {
        if (this.controller === controller) return
        if (isAttachedToWindow) this.controller?.removeRowView(this)
        this.controller = controller
        if (isAttachedToWindow) controller.addRowView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller?.addRowView(this)
    }

    override fun onDetachedFromWindow() {
        controller?.removeRowView(this)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateLamp()
        row?.let { text.text = runningText(it.label) }
    }

    override fun showRow(row: StillRunningModel.Row?) {
        val changed = row != this.row
        this.row = row
        if (row != null && changed) rowShownAt = SystemClock.uptimeMillis()
        // Shown first, then the words, so the live region is visible when it changes.
        applyAlpha()
        if (row != null && changed) text.text = runningText(row.label)
    }

    /**
     * One of the action keys' alphas ([index] is theirs), which this row follows: it fades in and
     * out with Recents and hides where the keys hide.
     */
    fun setActionsAlpha(index: Int, value: Float) {
        if (index < 0) return
        if (index >= actionsAlpha.size) {
            actionsAlpha = actionsAlpha.copyOf(index + 1).also { it.fill(1f, actionsAlpha.size) }
        }
        actionsAlpha[index] = value
        applyAlpha()
    }

    /** Places the row's top at [actionKeysBottom], the bottom edge of the action keys. */
    fun setTopMargin(actionKeysBottom: Int) {
        val params = layoutParams as? MarginLayoutParams ?: return
        if (params.topMargin == actionKeysBottom) return
        params.topMargin = actionKeysBottom
        layoutParams = params
    }

    private fun applyAlpha() {
        var value = if (row != null) 1f else 0f
        for (factor in actionsAlpha) value *= factor
        alpha = value
        val wanted = if (value > ALPHA_CUTOFF) VISIBLE else INVISIBLE
        if (visibility != wanted) visibility = wanted
    }

    private fun updateLamp() {
        // Lamps next to text grow with it from 200 % (the prototype's T.lampSize: 12 to 16 dp).
        val large = resources.configuration.fontScale >= LARGE_LAMP_FONT_SCALE
        lampSize =
            resources.getDimensionPixelSize(
                if (large) R.dimen.tally_lamp_size_large else R.dimen.tally_lamp_size_small
            )
        lampBox =
            resources.getDimensionPixelSize(
                if (large) R.dimen.tally_lamp_box_14 else R.dimen.tally_lamp_box_10
            )
        lamp.setImageResource(
            if (large) R.drawable.tally_lamp_live_14 else R.drawable.tally_lamp_live_10
        )
    }

    /** "<app> is running", stock Android's words, with the app's name in 600. */
    private fun runningText(label: CharSequence): CharSequence {
        val name = label.toString()
        val words =
            resources.getString(com.android.internal.R.string.app_running_notification_title, name)
        val start = words.indexOf(name)
        if (start < 0 || name.isEmpty()) return words
        val adjustment = resources.configuration.fontWeightAdjustment
        val weight =
            (NAME_WEIGHT +
                    if (adjustment == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) 0
                    else adjustment)
                .coerceIn(1, 1000)
        return SpannableString(words).apply {
            setSpan(
                TypefaceSpan(Typeface.create(text.typeface, weight, false)),
                start,
                start + name.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun onStopClicked() {
        if (row == null) return
        // A row that just appeared or changed takes no Stop yet: the tap may have been meant for
        // what was there before.
        if (SystemClock.uptimeMillis() - rowShownAt < TAP_GUARD_MS) return
        controller?.stop()
    }

    // Nothing here scrolls: the Stop key shows its press at once.
    override fun shouldDelayChildPressedState() = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // super drops touches that come through a window over this one (filterTouchesWhenObscured).
        val handled = super.dispatchTouchEvent(event)
        return handled || rowRect.contains(event.x.toInt(), event.y.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else max(0, MeasureSpec.getSize(widthMeasureSpec) - 2 * sideMargin)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        // The Stop key: its word, padded to the key.
        stopKey.measure(unspecified, unspecified)
        keyWidth = stopKey.measuredWidth + 2 * keyPaddingHorizontal
        keyHeight = max(keyMinHeight, stopKey.measuredHeight + 2 * keyPaddingVertical)

        lamp.measure(exactly(lampBox), exactly(lampBox))

        val textMaxWidth =
            max(0, maxWidth - rowPaddingStart - lampSize - 2 * gap - keyWidth - rowPaddingEnd)
        text.measure(MeasureSpec.makeMeasureSpec(textMaxWidth, MeasureSpec.AT_MOST), unspecified)

        rowWidth =
            rowPaddingStart + lampSize + gap + text.measuredWidth + gap + keyWidth + rowPaddingEnd
        rowHeight = max(rowMinHeight, max(text.measuredHeight, keyHeight) + 2 * rowPaddingVertical)

        // The key's touch target: at least 48 × 48 dp, with room for its focus ring.
        val ringReach = stopKeyDrawable.ringReach
        val targetWidth = max(targetMin, keyWidth + 2 * ringReach)
        val targetHeight = max(targetMin, keyHeight + 2 * ringReach)
        stopKey.measure(exactly(targetWidth), exactly(targetHeight))

        // Past the row's end the target may reach out; keep it inside this view, row centred.
        val overhang = max(0, (targetWidth - keyWidth) / 2 - rowPaddingEnd)
        setMeasuredDimension(rowWidth + 2 * overhang, max(rowHeight + 2 * gap, targetHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rowLeft = (width - rowWidth) / 2
        val rowTop = (height - rowHeight) / 2
        rowRect.set(rowLeft, rowTop, rowLeft + rowWidth, rowTop + rowHeight)
        val centerY = rowRect.centerY()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // x of a child that starts [fromStart] into the row and is [size] wide.
        fun left(fromStart: Int, size: Int) =
            if (rtl) rowRect.right - fromStart - size else rowRect.left + fromStart

        val lampStart = rowPaddingStart - (lampBox - lampSize) / 2
        place(lamp, left(lampStart, lampBox), centerY)

        val textStart = rowPaddingStart + lampSize + gap
        place(text, left(textStart, text.measuredWidth), centerY)

        val keyStart = textStart + text.measuredWidth + gap
        val keyCenterX = left(keyStart, keyWidth) + keyWidth / 2
        place(stopKey, keyCenterX - stopKey.measuredWidth / 2, centerY)
        stopKeyDrawable.setKeySize(keyWidth, keyHeight)
    }

    private fun place(child: android.view.View, left: Int, centerY: Int) {
        val top = centerY - child.measuredHeight / 2
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val inset = edgePaint.strokeWidth / 2
        rowRectF.set(rowRect)
        canvas.drawRoundRect(rowRectF, rowRadius, rowRadius, fillPaint)
        rowRectF.inset(inset, inset)
        canvas.drawRoundRect(rowRectF, rowRadius - inset, rowRadius - inset, edgePaint)
        super.dispatchDraw(canvas)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val ALPHA_CUTOFF = 0.01f
        const val NAME_WEIGHT = 600
        const val LARGE_LAMP_FONT_SCALE = 2f
        const val TAP_GUARD_MS = 500L
    }
}
