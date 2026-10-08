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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Insettable
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DraggableView
import com.android.launcher3.popup.Poppable
import com.android.launcher3.popup.PoppableType
import com.android.launcher3.tally.search.TallySearchSheet
import com.android.launcher3.views.ActivityContext
import kotlin.math.max
import kotlin.math.min

/**
 * Home's search slot under the dock, as the prototype's: a key recessed into the dock's band (a
 * surface with a 1 dp outline, r12, and a 2 dp shade along its inner top edge), a search icon and
 * "Search" (the framework's word, in every language). A tap opens Home's search over Home
 * ([TallySearchSheet]) with its field and the keyboard: apps and their shortcuts, Settings pages,
 * quick answers, and hand-offs to the apps that search contacts, files and the web. All apps keeps
 * its own search (apps only), a swipe up away. The hotseat lays the slot out in the space it
 * reserves for a search bar (qsb_widget_height), 16 dp from its sides ([widthIn]).
 *
 * It comes off Home as a widget does ([TallyHomeLift]: a long press, or TalkBack's Remove) and
 * comes back in Home settings ([TallyHomeItem.SEARCH]). Taken off, where Tally lays Home out, the
 * hotseat keeps no room for it ([TallyHomeLayout.hotseatQsbHeightPx]) and it shows nothing and
 * takes no touch; All apps' search stays a swipe up away.
 */
class TallySearchSlot(context: Context) :
    LinearLayout(context), Insettable, DraggableView, Poppable {
    private val density = resources.displayMetrics.density
    private val radius = resources.getDimension(R.dimen.tally_radius_m)
    private val sideMarginPx = resources.getDimensionPixelSize(R.dimen.tally_space_l)
    private val outlineWidth = resources.getDimension(R.dimen.tally_stroke_hairline)
    private val shadeHeight = SHADE_DP * density
    private val fillPaint = paint(context.getColor(R.color.tally_surface), Paint.Style.FILL)
    private val shadePaint = paint(context.getColor(R.color.tally_keycap_shade), Paint.Style.FILL)
    private val outlinePaint =
        paint(context.getColor(R.color.tally_outline), Paint.Style.STROKE).apply {
            strokeWidth = outlineWidth
        }
    private val box = RectF()
    private val boxPath = Path()
    /** Taken off Home (where Tally lays it out). */
    private var removed = false
    /** Picked up by a long press: hidden under the drag's image. */
    private var lifted = false
    private val lift =
        TallyHomeLift(this, TallyHomeItem.SEARCH, { !removed }) {
            lifted = it
            invalidate()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        val padding = resources.getDimensionPixelSize(R.dimen.tally_space_l)
        setPaddingRelative(padding, 0, padding, 0)
        isClickable = true
        isFocusable = true

        val iconSize = resources.getDimensionPixelSize(R.dimen.tally_icon_size)
        val icon =
            ImageView(context).apply {
                setImageResource(R.drawable.ic_allapps_search)
                imageTintList = context.getColorStateList(R.color.tally_ink)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        addView(icon, LayoutParams(iconSize, iconSize))

        val text =
            TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_Tally_Body)
                setTextColor(context.getColor(R.color.tally_ink))
                // The search text stops growing at 130 %, as the prototype's.
                val capPx =
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SP, displayMetrics)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    min(textSize, capPx * TallyHomeLayout.TEXT_SCALE_CAP),
                )
                setText(android.R.string.search_go)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        addView(
            text,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.tally_space_m)
            },
        )
        contentDescription = context.getString(android.R.string.search_go)
        setOnClickListener { openSearch() }
        refresh()
    }

    override fun setInsets(insets: Rect) {
        // Home was laid out again (the slot came off or back, among others).
        refresh()
    }

    private fun refresh() {
        val activity: ActivityContext? = ActivityContext.lookupContextNoThrow(context)
        val dp = activity?.deviceProfile
        // As the hotseat keeps room for it (TallyHomeLayout.hotseatQsbHeightPx).
        removed = dp != null && TallyHomeLayout.appliesTo(dp) && !dp.inv.tallySearchShown
        lifted = false
        isClickable = !removed
        isFocusable = !removed
        importantForAccessibility =
            if (removed) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        invalidate()
    }

    private val displayMetrics
        get() = resources.displayMetrics

    /**
     * The slot's width in a hotseat [hotseatWidthPx] wide, as the hotseat measures it: the
     * hotseat's width less 16 dp on each side, where stock measures its search bar at the grid's
     * width, which a phone's non-scalable grid leaves at 0. It comes in the measure spec rather
     * than from the hotseat's last layout, which may still be landscape's narrow bar, and a spec
     * that does not change does not measure the slot again.
     */
    fun widthIn(hotseatWidthPx: Int): Int = max(0, hotseatWidthPx - 2 * sideMarginPx)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A width change goes to the log, so a slot that comes out narrow can be traced.
        Log.i(TAG, "Search slot ${oldw}x$oldh -> ${w}x$h, hotseat ${(parent as? View)?.width}")
    }

    override fun draw(canvas: Canvas) {
        if (removed || lifted) return
        super.draw(canvas)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (removed) return false
        lift.onTouchEvent(ev)
        return super.onTouchEvent(ev)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        lift.cancelLongPress()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        lift.onInitializeAccessibilityNodeInfo(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        lift.performAccessibilityAction(action) ||
            super.performAccessibilityAction(action, arguments)

    // DraggableView: lifted as a widget, the whole slot.
    override fun getViewType(): Int = DraggableView.DRAGGABLE_WIDGET

    override fun getWorkspaceVisualDragBounds(bounds: Rect) {
        bounds.set(0, 0, width, height)
    }

    override fun getPoppableType(): PoppableType = PoppableType.WIDGET

    override fun onDraw(canvas: Canvas) {
        val inset = outlineWidth / 2f
        box.set(inset, inset, width - inset, height - inset)
        boxPath.reset()
        boxPath.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.drawPath(boxPath, fillPaint)
        // The recess: a shade along the inner top edge, where the box moved down does not reach.
        val count = canvas.save()
        canvas.clipPath(boxPath)
        canvas.translate(0f, shadeHeight)
        canvas.clipOutPath(boxPath)
        canvas.translate(0f, -shadeHeight)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        canvas.restoreToCount(count)
        canvas.drawPath(boxPath, outlinePaint)
        super.onDraw(canvas)
    }

    private fun openSearch() {
        // Only Home's own slot opens anything (not a grid preview's), and only on Home.
        val launcher = TallyHomeLift.launcherOf(context) ?: return
        if (!launcher.isInState(LauncherState.NORMAL)) return
        AbstractFloatingView.closeAllOpenViews(launcher)
        TallySearchSheet.show(launcher)
    }

    private companion object {
        const val TAG = "TallySearchSlot"
        /** The search text's size (sp), before its cap. */
        const val TEXT_SP = 14f
        /** The recess's shade along the top edge. */
        const val SHADE_DP = 2f

        fun paint(color: Int, style: Paint.Style) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.style = style
            }
    }
}
