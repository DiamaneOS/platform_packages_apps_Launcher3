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

/**
 * What Recents' "Still running · Stop" row shows (Tally).
 *
 * While Recents shows, SystemUI tells it which apps its Active apps dialog would let the user stop,
 * always as the whole set. The row names the app of a card the user swiped away, taken from that
 * card's own task (its package and user), and shows while SystemUI lists that app, until the user
 * taps Stop. It never names an app that no swiped card named, and never offers Stop for an app that
 * SystemUI does not list.
 *
 * As in the prototype, the swiped app is remembered while Recents is closed, so the row is there
 * again the next time Recents opens, as long as SystemUI still lists the app. It is kept in memory
 * only. UI thread only.
 */
class StillRunningModel {

    /** The row: the app that Stop stops, and the words that name it. */
    data class Row(val app: StoppableApp, val label: CharSequence)

    /** SystemUI's set, or null while it is not known: Recents closed, or not heard from yet. */
    var stoppable: Set<StoppableApp>? = null
        private set

    /** The app of the last swiped card that SystemUI listed. */
    private var swiped: Row? = null

    /** A card swiped before SystemUI's set was known: checked when the set comes. */
    private var pending: Row? = null

    /** The user tapped Stop for [swiped]: its row stays hidden. */
    private var stopRequested = false

    /** The row to show now, or null for none. */
    val row: Row?
        get() = swiped?.takeIf { !stopRequested && stoppable?.contains(it.app) == true }

    /** Whether a card of [app] swiped away now could bring up the row. */
    fun mayOffer(app: StoppableApp): Boolean = stoppable?.contains(app) ?: true

    /** SystemUI's whole set, or null when it is no longer known (Recents closed or reopened). */
    fun onStoppableAppsChanged(apps: Set<StoppableApp>?) {
        stoppable = apps
        if (apps == null) return
        pending?.let {
            pending = null
            if (it.app in apps) swipe(it)
        }
        swiped?.let {
            // Stopped, or no longer stoppable: forget it, so it never comes back by itself.
            if (it.app !in apps) {
                swiped = null
                stopRequested = false
            }
        }
    }

    /** The user swiped away a card of [app], which its task names [label]. */
    fun onTaskSwiped(app: StoppableApp, label: CharSequence) {
        val row = Row(app, label)
        val apps = stoppable
        when {
            apps == null -> pending = row
            app in apps -> {
                pending = null
                swipe(row)
            }
            // Not running anything SystemUI would stop: keep the row there is.
            else -> {}
        }
    }

    /** The user tapped Stop: returns the app to stop, or null if no row shows. Hides the row. */
    fun takeStop(): StoppableApp? {
        val row = row ?: return null
        stopRequested = true
        return row.app
    }

    private fun swipe(row: Row) {
        swiped = row
        stopRequested = false
    }
}
