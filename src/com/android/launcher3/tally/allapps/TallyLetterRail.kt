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

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.text.ListFormatter
import android.os.Bundle
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ImageSpan
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.DeviceProfile
import com.android.launcher3.FastScrollRecyclerView
import com.android.launcher3.Insettable
import com.android.launcher3.R
import com.android.launcher3.allapps.AllAppsRecyclerView
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.tally.allapps.TallyRailSlots.Kind
import com.android.launcher3.tally.home.TallyHomeLayout
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.RecyclerViewFastScroller
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * All apps' one letter index, the prototype's rail: a single 48 dp wide control at the list's end
 * edge, one quiet column of letters in the muted colour. Tap a letter and its apps scroll to the
 * top of the list; run a finger along the rail and the list jumps from letter to letter, with a
 * tick at each. The first app of the letter is ringed ([TallyRailMark]). Every slot is at least 48
 * dp tall: when the letters don't fit, letters share a slot ([TallyRailSlots]), which shows its
 * first letter and names all of them to screen readers ("Apps starting with G or K"). There are no
 * letters in the grid and no letter bubble. The rail shows only while the list can scroll.
 *
 * It takes the stock scroller's place, so All apps binds it to the list on screen, hides it while
 * searching and hands it the touches on it, and the lists, the tabs, the work profile and the
 * private space work as before (the private space keeps a slot with its badge). Where Tally's Home
 * layout does not apply (a phone held sideways, a tablet, the taskbar's All apps), it is the stock
 * scroller, and All apps keeps its stock paddings ([listEndPaddingPx]).
 *
 * Screen readers see one button per slot; with a keyboard, the arrows move between slots and Enter
 * jumps.
 */
class TallyLetterRail
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    RecyclerViewFastScroller(context, attrs, defStyleAttr), Insettable {

    private val density = resources.displayMetrics.density
    private val railWidthPx = resources.getDimensionPixelSize(R.dimen.tally_target_min)
    private val minSlotPx = resources.getDimensionPixelSize(R.dimen.tally_target_min)
    private val letterEndPx = LETTER_END_DP * density
    private val slopPx = SLOP_DP * density
    private val badgeSizePx = resources.getDimensionPixelSize(R.dimen.tally_icon_size_small)
    private val focusInsetPx = resources.getDimension(R.dimen.tally_focus_ring_width)
    private val focusRadius = resources.getDimension(R.dimen.tally_radius_s)
    private val workNotice = resources.getString(R.string.work_profile_edu_section)

    private val letterPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            val section = TextAppearanceSpan(context, R.style.TextAppearance_Tally_Section)
            section.updateMeasureState(this)
            // Bold text, as a TextView applies it.
            val adjustment = resources.configuration.fontWeightAdjustment
            if (
                adjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED &&
                    section.textFontWeight > 0
            ) {
                typeface =
                    Typeface.create(
                        typeface,
                        (section.textFontWeight + adjustment).coerceIn(1, 1000),
                        false,
                    )
            }
            // The letters stop growing at 130 % text, as the app names do.
            val capPx =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    LETTER_SP,
                    resources.displayMetrics,
                ) * TallyHomeLayout.TEXT_SCALE_CAP
            textSize = min(textSize, capPx)
            letterSpacing = 0f
            color = context.getColor(R.color.tally_ink_muted)
        }
    private val focusPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimension(R.dimen.tally_focus_ring_width)
            color = context.getColor(R.color.tally_accent)
        }
    private val badge =
        context.getDrawable(R.drawable.ic_private_profile_app_scroller_badge)?.mutate()?.apply {
            setTint(context.getColor(R.color.tally_ink_muted))
        }

    private val mark = TallyRailMark(context)
    private val accessibility = RailAccessibility()
    private val listListener =
        object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (recyclerView === mRv && newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    mark.clear()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // 0, 0 after a layout: the sections may have changed.
                if (recyclerView === mRv && dx == 0 && dy == 0 && tally) invalidate()
            }
        }

    /** Whether this is Tally's rail (else the stock scroller). */
    private var tally = false
    private var shown = false
    private var slots: List<TallyRailSlots.Slot> = emptyList()
    private var slotsKey = 0
    /** The list's top and bottom (where its content shows) in this view. */
    private var trackTop = 0
    private var trackBottom = 0

    private var tracking = false
    private var dragging = false
    private var downTime = -1L
    private var downY = 0f
    private var scrubSlot = -1

    private val hitRect = Rect()
    private val slotRect = Rect()
    private val focusRect = RectF()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        // The helper makes its host focusable: the rail is, only while it shows (setThumbOffsetY).
        isFocusable = false
        defaultFocusHighlightEnabled = false
    }

    override fun setInsets(insets: Rect) {
        tally = TallyHomeLayout.appliesTo(activityContext().deviceProfile)
        val lp = layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            if (tally) {
                // The rail's column at the end edge, from the top of All apps' header down.
                lp.width = railWidthPx
                lp.marginEnd = 0
                lp.topMargin = 0
            } else {
                lp.width = resources.getDimensionPixelSize(R.dimen.fastscroll_width)
                lp.marginEnd = resources.getDimensionPixelSize(R.dimen.fastscroll_end_margin)
                lp.topMargin =
                    resources.getDimensionPixelSize(R.dimen.all_apps_header_bottom_padding)
            }
            layoutParams = lp
        }
        if (tally) systemGestureExclusionRects = emptyList()
        isFocusable = tally && mThumbOffsetY >= 0
        slotsKey = 0
        accessibility.invalidateRoot()
        invalidate()
    }

    override fun setRecyclerView(rv: FastScrollRecyclerView) {
        if (rv !== mRv) {
            mark.clear()
            tracking = false
        }
        super.setRecyclerView(rv)
        // Added once to each list and kept: they only act for the list the rail is bound to.
        if (!hasDecoration(rv)) {
            rv.addItemDecoration(mark)
            rv.addOnScrollListener(listListener)
        }
        slotsKey = 0
        accessibility.invalidateRoot()
        invalidate()
    }

    override fun setThumbOffsetY(y: Int) {
        super.setThumbOffsetY(y)
        // The rail shows while the list can scroll; only then can the keyboard reach it.
        val focusable = tally && y >= 0
        if (isFocusable != focusable) isFocusable = focusable
    }

    override fun reattachThumbToScroll() {
        super.reattachThumbToScroll()
        // All apps goes back to the top of its list: closing, switching tabs, searching.
        mark.clear()
    }

    override fun endFastScrolling() {
        super.endFastScrolling()
        tracking = false
        mark.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        slotsKey = 0
    }

    override fun isHitInParent(x: Float, y: Float, outOffset: Point?): Boolean {
        if (!tally) return super.isHitInParent(x, y, outOffset)
        if (!isShown || !updateRail()) return false
        getHitRect(hitRect)
        val top = hitRect.top + trackTop
        // Touches arrive measured from the top of the rail's slots.
        outOffset?.set(hitRect.left, top)
        return x >= hitRect.left && x < hitRect.right && y >= top && y < hitRect.top + trackBottom
    }

    override fun shouldBlockIntercept(x: Int, y: Int): Boolean {
        if (!tally) return super.shouldBlockIntercept(x, y)
        // A finger on the rail runs along it: it neither scrolls the list nor pulls the sheet.
        return isShown && updateRail() && x >= 0 && x < width && y >= trackTop && y < trackBottom
    }

    override fun handleTouchEvent(ev: MotionEvent, offset: Point): Boolean {
        if (!tally) return super.handleTouchEvent(ev, offset)
        val y = ev.y - offset.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // All apps hands over a touch it takes twice: to take it, then to handle it.
                if (tracking && ev.downTime == downTime) return true
                tracking = true
                dragging = false
                downTime = ev.downTime
                downY = y
                scrubSlot = -1
                mRv?.stopScroll()
                activityContext().hideKeyboard()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!dragging && abs(y - downY) > slopPx) dragging = true
                if (dragging) {
                    val slot = slotAt(y)
                    if (slot >= 0 && slot != scrubSlot) {
                        scrubSlot = slot
                        jumpTo(slot, smooth = false)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                if (!dragging) {
                    val slot = slotAt(y)
                    if (slot >= 0) {
                        playSoundEffect(SoundEffectConstants.CLICK)
                        jumpTo(slot, smooth = true)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!tally) {
            super.onDraw(canvas)
            return
        }
        if (!updateRail()) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val slotHeight = slotHeight()
        // The letters sit 14 dp from the screen's edge, as the prototype's.
        val edgeX = if (rtl) letterEndPx else width - letterEndPx
        letterPaint.textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT
        val baselineShift = -(letterPaint.ascent() + letterPaint.descent()) / 2f
        for (i in slots.indices) {
            val slot = slots[i]
            val centreY = trackTop + (i + 0.5f) * slotHeight
            if (slot.kind == Kind.PRIVATE && badge != null) {
                val end = edgeX.roundToInt()
                val left = if (rtl) end else end - badgeSizePx
                val top = (centreY - badgeSizePx / 2f).roundToInt()
                badge.setBounds(left, top, left + badgeSizePx, top + badgeSizePx)
                badge.draw(canvas)
            } else {
                canvas.drawText(slot.names[0], edgeX, centreY + baselineShift, letterPaint)
            }
        }
        val focused = accessibility.keyboardFocusedVirtualViewId
        if (focused in slots.indices) {
            slotBounds(focused, slotRect)
            focusRect.set(slotRect)
            focusRect.inset(focusInsetPx, focusInsetPx)
            canvas.drawRoundRect(focusRect, focusRadius, focusRadius, focusPaint)
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibility.dispatchHoverEvent(event)) return true
        // Off the slots (the ends of the tabs above them), the views under the rail take it.
        return !tally && super.dispatchHoverEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    /**
     * Brings the list's place (where its content shows in this view) and the slots up to date;
     * returns whether the rail shows.
     */
    private fun updateRail(): Boolean {
        val rv = mRv as? AllAppsRecyclerView
        val apps = rv?.apps
        shown =
            tally &&
                rv != null &&
                apps != null &&
                mThumbOffsetY >= 0 &&
                rv.supportsFastScrolling() &&
                updateTrack(rv)
        if (!shown || apps == null) {
            if (slots.isNotEmpty()) {
                slots = emptyList()
                slotsKey = 0
                accessibility.invalidateRoot()
            }
            return false
        }
        val fit = (trackBottom - trackTop) / minSlotPx
        val key = keyOf(apps, fit)
        if (key != slotsKey || slots.isEmpty()) {
            slots = TallyRailSlots.slots(sectionsOf(apps), fit)
            slotsKey = key
            accessibility.invalidateRoot()
        }
        shown = slots.isNotEmpty()
        return shown
    }

    /** Finds where [rv] shows its content in this view; returns whether a slot fits. */
    private fun updateTrack(rv: RecyclerView): Boolean {
        val host = parent as? ViewGroup ?: return false
        var y = 0
        var v: View = rv
        while (true) {
            y += v.top
            val p = v.parent as? View ?: return false
            if (p === host) break
            y -= p.scrollY
            v = p
        }
        val listTop = y - top
        trackTop = (listTop + rv.paddingTop).coerceIn(0, height)
        trackBottom = (listTop + rv.height - rv.paddingBottom).coerceIn(trackTop, height)
        return trackBottom - trackTop >= minSlotPx
    }

    /** A cheap fingerprint of the list's sections, so the slots are worked out only on a change. */
    private fun keyOf(apps: AlphabeticalAppsList, fit: Int): Int {
        val sections = apps.fastScrollerSections
        val items = apps.adapterItems
        var h = 31 * fit + items.size
        for (i in sections.indices) {
            val s = sections[i]
            h = 31 * h + s.position
            h = 31 * h + s.sectionName.hashCode()
            h = 31 * h + (items.getOrNull(s.position)?.rowIndex ?: -1)
        }
        return if (h == 0) 1 else h
    }

    private fun sectionsOf(apps: AlphabeticalAppsList): List<TallyRailSlots.Section> {
        val sections = apps.fastScrollerSections
        val items = apps.adapterItems
        val out = ArrayList<TallyRailSlots.Section>(sections.size)
        for (i in sections.indices) {
            val s = sections[i]
            val end =
                min(if (i + 1 < sections.size) sections[i + 1].position else items.size, items.size)
            var count = 0
            for (p in s.position until end) {
                if (BaseAllAppsAdapter.isIconViewType(items[p].viewType)) count++
            }
            val first = items.getOrNull(s.position)
            val row =
                if (first != null && BaseAllAppsAdapter.isIconViewType(first.viewType))
                    first.rowIndex
                else -1
            out.add(
                TallyRailSlots.Section(
                    s.sectionName.toString(),
                    s.position,
                    row,
                    count,
                    kindOf(s.sectionName),
                )
            )
        }
        return out
    }

    private fun kindOf(name: CharSequence): Kind =
        when {
            // The list names the private space's sections with its badge.
            name is Spanned && name.getSpans(0, name.length, ImageSpan::class.java).isNotEmpty() ->
                Kind.PRIVATE
            name.toString() == workNotice -> Kind.OTHER
            else -> Kind.LETTER
        }

    private fun slotHeight(): Float =
        if (slots.isEmpty()) 0f else (trackBottom - trackTop).toFloat() / slots.size

    /** The slot at [y] measured from the top of the slots, or -1 for none. */
    private fun slotAt(y: Float): Int {
        val h = slotHeight()
        if (h <= 0f) return -1
        return floor(y / h).toInt().coerceIn(0, slots.size - 1)
    }

    private fun slotBounds(index: Int, out: Rect) {
        val h = slotHeight()
        out.set(
            0,
            (trackTop + index * h).roundToInt(),
            width,
            (trackTop + (index + 1) * h).roundToInt(),
        )
    }

    /** Scrolls the list to the slot's first section, and rings its first app. */
    private fun jumpTo(index: Int, smooth: Boolean) {
        val rv = mRv ?: return
        val slot = slots.getOrNull(index) ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        rv.stopScroll()
        if (smooth && animationsOn()) {
            val scroller =
                object : LinearSmoothScroller(context) {
                    override fun getVerticalSnapPreference() = SNAP_TO_START
                }
            scroller.targetPosition = slot.position
            layoutManager.startSmoothScroll(scroller)
        } else {
            layoutManager.scrollToPositionWithOffset(slot.position, 0)
        }
        if (slot.kind == Kind.LETTER) mark.show(rv, slot.position) else mark.clear()
    }

    private fun labelOf(slot: TallyRailSlots.Slot): String =
        when (slot.kind) {
            Kind.PRIVATE -> resources.getString(R.string.private_space_label)
            Kind.OTHER -> resources.getString(R.string.work_profile_edu_work_apps)
            Kind.LETTER ->
                resources.getString(
                    R.string.tally_rail_slot,
                    ListFormatter.getInstance(
                            Locale.getDefault(),
                            ListFormatter.Type.OR,
                            ListFormatter.Width.WIDE,
                        )
                        .format(slot.names),
                )
        }

    private fun activityContext(): ActivityContext = ActivityContext.lookupContext(context)

    private fun hasDecoration(rv: RecyclerView): Boolean {
        for (i in 0 until rv.itemDecorationCount) {
            if (rv.getItemDecorationAt(i) === mark) return true
        }
        return false
    }

    /** The slots as buttons for screen readers and the keyboard. */
    private inner class RailAccessibility : ExploreByTouchHelper(this@TallyLetterRail) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (!updateRail() || y < trackTop || y >= trackBottom) return INVALID_ID
            return slotAt(y - trackTop)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (!updateRail()) return
            for (i in slots.indices) virtualViewIds.add(i)
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val slot = slots.getOrNull(virtualViewId)
            if (slot == null) {
                // A slot that has just gone: the helper still needs bounds and words.
                node.contentDescription = ""
                slotRect.set(0, 0, 1, 1)
                @Suppress("DEPRECATION") node.setBoundsInParent(slotRect)
                return
            }
            node.contentDescription = labelOf(slot)
            node.className = Button::class.java.name
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            slotBounds(virtualViewId, slotRect)
            @Suppress("DEPRECATION") node.setBoundsInParent(slotRect)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (virtualViewId !in slots.indices) return false
            jumpTo(virtualViewId, smooth = true)
            return true
        }

        override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) {
            invalidate()
        }
    }

    companion object {
        /** The letters: the Section text style's 11 sp, capped at 130 %. */
        private const val LETTER_SP = 11f
        /** The letters' gap from the screen's edge. */
        private const val LETTER_END_DP = 14f
        /** How far a finger moves on the rail before it runs along it (else it is a tap). */
        private const val SLOP_DP = 4f

        /**
         * The padding All apps' A-Z lists keep at their end edge for the rail's column (px), or 0
         * where the stock scroller is used.
         */
        @JvmStatic
        fun listEndPaddingPx(context: Context, dp: DeviceProfile): Int =
            if (TallyHomeLayout.appliesTo(dp)) {
                context.resources.getDimensionPixelSize(R.dimen.tally_target_min)
            } else {
                0
            }

        private fun animationsOn(): Boolean =
            ValueAnimator.areAnimatorsEnabled() && ValueAnimator.getDurationScale() > 0f
    }
}
