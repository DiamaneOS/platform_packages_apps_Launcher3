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
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.android.launcher3.CheckLongPressHelper
import com.android.launcher3.DragSource
import com.android.launcher3.DropTarget
import com.android.launcher3.DropTarget.DragObject
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAnimUtils.SPRING_LOADED_EXIT_DELAY
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.touch.ItemLongClickListener

/**
 * Lifts Home's tallies band or search slot ([item], drawn by [view]) as Launcher lifts a widget,
 * where Tally lays Home out (an upright phone, [TallyHomeLayout.appliesTo]) and Home is at rest:
 * - a long press, after the widget's timeout (CheckLongPressHelper), picks it up and shows the
 *   widget's long-press menu over it (Remove);
 * - moving the finger drags it, scaled as a widget to the workspace's edit scale, with Launcher's
 *   drop targets: Remove, or a fling up, removes it as a widget with Undo ([TallyHomeRemoval]);
 *   dropped anywhere else, it goes back to its place, its only one on Home (Launcher's drag
 *   controller hands it here instead of to the workspace: [placeOf]);
 * - TalkBack gets the Remove action a widget has.
 *
 * The view feeds its touches to [onTouchEvent] (and [hasPerformedLongPress] from its
 * onInterceptTouchEvent), passes on TalkBack's calls, and hides itself while [setLifted] says it is
 * picked up; a change of Home's layout (the view's setInsets) ends that too. It is a DraggableView
 * of the widget kind, which Launcher draws into the drag's image, and Poppable, which gives it the
 * long-press menu.
 */
class TallyHomeLift(
    private val view: View,
    private val item: TallyHomeItem,
    /** Whether the item is on Home now (its setting on, and something to show). */
    private val isOnHome: () -> Boolean,
    private val setLifted: (Boolean) -> Unit,
) : DragSource, DropTarget {
    private val longPress = CheckLongPressHelper(view) { lift() }

    /** Passes a touch on [view] to the long press. */
    fun onTouchEvent(ev: MotionEvent) = longPress.onTouchEvent(ev)

    /** Whether this touch's long press picked the item up. */
    fun hasPerformedLongPress(): Boolean = longPress.hasPerformedLongPress()

    fun cancelLongPress() = longPress.cancelLongPress()

    /** The Launcher showing [view], if the item can be picked up or removed there now. */
    private fun launcher(): Launcher? {
        val launcher = launcherOf(view.context) ?: return null
        val atRest = launcher.isInState(LauncherState.NORMAL)
        val onHome = isOnHome() && view.isShown
        return if (atRest && onHome && TallyHomeLayout.appliesTo(launcher.deviceProfile)) {
            launcher
        } else {
            null
        }
    }

    private fun lift(): Boolean {
        val launcher = launcher() ?: return false
        if (!ItemLongClickListener.canStartDrag(launcher)) return false
        val info = TallyHomeItemInfo(item, launcher.deviceProfile.inv.numColumns)
        val options = DragOptions()
        // As for an app widget (which Workspace sets up for app widgets only): the item stays put
        // under the menu until the finger moves, then shrinks to the edit scale as it follows.
        options.deferDragToPreDragEnd = true
        if (view.width > 0) {
            val scalePx = launcher.dragController.getWidgetDragScalePx(null, view, info)
            options.preDragEndScale = (view.width + scalePx) / view.width
        }
        launcher.setWaitingForResult(null)
        // The drag and the menu read the item from the view's tag, only as they start: Home's
        // views otherwise carry no item (the hotseat would take a tagged search slot for one).
        view.tag = info
        try {
            launcher.workspace.beginDragShared(view, this, options)
        } finally {
            view.tag = null
        }
        setLifted(true)
        return true
    }

    /** Offers TalkBack a widget's Remove action. */
    fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        if (launcher() == null) return
        info.addAction(
            AccessibilityAction(
                R.id.action_remove,
                view.context.getString(R.string.remove_drop_target_label),
            )
        )
    }

    /** Performs TalkBack's Remove; returns whether [action] was it. */
    fun performAccessibilityAction(action: Int): Boolean {
        if (action != R.id.action_remove) return false
        val launcher = launcher() ?: return false
        TallyHomeRemoval.remove(launcher, item)
        return true
    }

    // DragSource: removed (the drop's target took it) or not; if not, it is back where it was.
    override fun onDropCompleted(target: View?, d: DragObject, success: Boolean) {
        // Removed, it stays hidden until Home drops it; put back (onDrop), it shows once the drag's
        // image has settled.
        if (!success) setLifted(false)
    }

    // DropTarget: its place, where it goes back to from anywhere but a drop target.
    override fun isDropEnabled(): Boolean = true

    override fun onDrop(d: DragObject, options: DragOptions) {
        val launcher = launcherOf(view.context)
        val dragView = d.dragView
        if (launcher == null || dragView == null) {
            setLifted(false)
            return
        }
        // Launcher leaves the edit state for an accepted drop only when the target asks it to.
        launcher.stateManager.goToState(LauncherState.NORMAL, SPRING_LOADED_EXIT_DELAY.toLong())
        // The drag's image has the drag preview's padding around the view.
        val pos = IntArray(2)
        launcher.dragLayer.getLocationInDragLayer(view, pos)
        pos[0] -= (dragView.measuredWidth - view.width) / 2
        pos[1] -= (dragView.measuredHeight - view.height) / 2
        launcher.dragLayer.animateViewIntoPosition(
            dragView,
            pos,
            1f,
            1f,
            1f,
            DragLayer.ANIMATION_END_DISAPPEAR,
            { setLifted(false) },
            -1,
        )
    }

    override fun onDragEnter(d: DragObject) {}

    override fun onDragOver(d: DragObject) {}

    override fun onDragExit(d: DragObject) {}

    override fun acceptDrop(d: DragObject): Boolean = true

    override fun prepareAccessibilityDrop() {}

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        val launcher = launcherOf(view.context)
        if (launcher == null) outRect.setEmpty()
        else launcher.dragLayer.getDescendantRectRelativeToSelf(view, outRect)
    }

    override fun getDropView(): View = view

    companion object {
        /** Where a drag of one of Tally's Home items goes back to, or null for any other drag. */
        @JvmStatic fun placeOf(d: DragObject?): DropTarget? = d?.dragSource as? TallyHomeLift

        /** The Launcher [context] belongs to, or null (a grid preview's view, for example). */
        @JvmStatic
        fun launcherOf(context: Context): Launcher? {
            var c: Context? = context
            while (c != null) {
                if (c is Launcher) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}

/**
 * One of Tally's Home items as Launcher's drag and drop and long-press menu see it, which work on
 * ItemInfo. Its type is a custom widget, one Launcher itself provides, so the menu offers a
 * widget's Remove (CustomWidgetSystemShortcuts), and it spans the grid's width, one row tall, so it
 * scales as a widget that wide. It is never in the model: its id is a view id (an AAPT id, never a
 * database row, as the hotseat's OSE search item), and DropTargetHandler hands its removal to
 * [TallyHomeRemoval], which turns its Home setting off.
 */
class TallyHomeItemInfo(@JvmField val item: TallyHomeItem, columns: Int) : ItemInfo() {
    init {
        id =
            when (item) {
                TallyHomeItem.TALLIES -> R.id.tally_home_header
                TallyHomeItem.SEARCH -> R.id.search_container_hotseat
            }
        itemType = ITEM_TYPE_CUSTOM_APPWIDGET
        spanX = columns
        minSpanX = columns
        spanY = 1
        minSpanY = 1
    }
}
