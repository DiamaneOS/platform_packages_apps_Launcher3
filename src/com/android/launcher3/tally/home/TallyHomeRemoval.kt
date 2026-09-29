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

import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.statemanager.StateManager.StateListener
import com.android.launcher3.views.Snackbar

/**
 * Removes Home's tallies band or search slot as Launcher removes a widget, from each of the ways a
 * widget is removed: its long-press menu's Remove and the Remove drop target (both reach
 * DropTargetHandler, which hands Tally's items here) and TalkBack's Remove ([TallyHomeLift]).
 *
 * The item's Home setting goes off ([TallyHomeItem]), Home's layout takes its space back, and the
 * snackbar a removed widget shows ("Item removed", Undo) puts it back. Launcher lays Home out again
 * for the new setting and rebinds its items, which closes open snackbars, so the setting changes
 * once Home is at rest (after the drop's return to Home) and the snackbar shows after that.
 */
object TallyHomeRemoval {

    /** Whether [info] is one of Tally's Home items, which the model never holds. */
    @JvmStatic fun handles(info: ItemInfo): Boolean = info is TallyHomeItemInfo

    /** Removes [info]'s item if it is one of Tally's Home items; returns whether it was. */
    @JvmStatic
    fun remove(launcher: Launcher, info: ItemInfo): Boolean {
        if (info !is TallyHomeItemInfo) return false
        remove(launcher, info.item)
        return true
    }

    /** Takes [item] off Home, with Undo. */
    @JvmStatic
    fun remove(launcher: Launcher, item: TallyHomeItem) {
        whenAtRest(launcher) {
            item.setShown(launcher, false)
            Snackbar.show(
                launcher,
                R.string.item_removed,
                R.string.undo,
                /* onDismissed= */ {},
                /* onActionClicked= */ { item.setShown(launcher, true) },
            )
        }
    }

    /** Runs [action] once Home is at rest (now, if it is), after the current frame's work. */
    private fun whenAtRest(launcher: Launcher, action: () -> Unit) {
        val stateManager = launcher.stateManager
        if (stateManager.isInStableState(LauncherState.NORMAL)) {
            launcher.dragLayer.post { action() }
            return
        }
        stateManager.addStateListener(
            object : StateListener<LauncherState> {
                override fun onStateTransitionComplete(finalState: LauncherState) {
                    stateManager.removeStateListener(this)
                    launcher.dragLayer.post { action() }
                }
            }
        )
    }
}
