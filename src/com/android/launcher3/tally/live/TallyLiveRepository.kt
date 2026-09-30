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

package com.android.launcher3.tally.live

import androidx.annotation.WorkerThread
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.tally.lamp.TallyLampState
import com.android.launcher3.util.ListenableRef
import com.android.launcher3.util.MutableListenableRef
import com.android.launcher3.util.PackageUserKey

/**
 * What is live or failed on Home now: the keycap LEDs and the tallies row. The notification
 * listener publishes it from its worker thread (through [TallyLiveTracker]); views read it on the
 * main thread. Each publish replaces immutable values, so readers never see a half update.
 */
class TallyLiveRepository {

    @Volatile private var leds: Map<PackageUserKey, TallyLampState> = emptyMap()

    private val _row = MutableListenableRef<List<TallyLiveItem>>(emptyList())

    /** The tallies row's things: the most urgent first, then the newest (see [ROW_ORDER]). */
    val row: ListenableRef<List<TallyLiveItem>> = _row.asListenable()

    /**
     * The LED of an app's key: [TallyLampState.FAILED], [TallyLampState.LIVE] or null for none.
     * Only app keys have one (not shortcuts, widgets or folders' own items).
     */
    fun ledStateFor(info: ItemInfo): TallyLampState? {
        if (info.itemType != ITEM_TYPE_APPLICATION) return null
        val key = PackageUserKey.fromItemInfo(info) ?: return null
        return leds[key]
    }

    /** The LED of [key]'s app, or null. */
    fun ledStateFor(key: PackageUserKey): TallyLampState? = leds[key]

    /**
     * Replaces everything with [items] (every live or failed notification now) and returns the apps
     * whose LED changed, so their keys can be redrawn.
     */
    @WorkerThread
    fun publish(items: Collection<TallyLiveItem>): Set<PackageUserKey> {
        val newLeds = HashMap<PackageUserKey, TallyLampState>()
        for (item in items) {
            if (!item.showsLed) continue
            newLeds[item.app] = TallyLiveRules.moreUrgent(newLeds[item.app], item.state)!!
        }
        val changed = HashSet<PackageUserKey>()
        for (key in leds.keys) if (newLeds[key] != leds[key]) changed.add(key)
        for (key in newLeds.keys) if (newLeds[key] != leds[key]) changed.add(key)
        leds = newLeds

        val newRow = items.filter { it.showsInRow }.sortedWith(ROW_ORDER)
        if (newRow != _row.value) _row.dispatchValue(newRow)
        return changed
    }

    companion object {
        /**
         * The row's order, by each thing's lamp in the row ([TallyLiveItem.rowState]): failed, then
         * live, then requested, then on, then off (a paused time); among equals the one that became
         * so most recently first, which a readout's updates do not change.
         */
        @JvmField
        val ROW_ORDER: Comparator<TallyLiveItem> =
            compareBy<TallyLiveItem> { TallyLiveRules.urgency(it.rowState) }
                .thenByDescending { it.sinceRealtime }
                .thenBy { it.key }
    }
}
