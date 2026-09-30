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
import com.android.launcher3.ConstantItem
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs

/** The settings' keys (in a file's constants: an enum's entries cannot read its companion). */
private const val TALLIES_PREF_KEY = "pref_tally_home_tallies"
private const val SEARCH_PREF_KEY = "pref_tally_home_search"

/**
 * Home's two items that come off as a widget does ([TallyHomeLift]) and come back in Home settings:
 * the tallies band under the date and the search slot under the dock. Whether each is on Home is a
 * Home setting, on by default, kept and backed up as Launcher keeps its other Home settings (in the
 * same preferences file, which InvariantDeviceProfile listens to, so Home's layout follows it).
 *
 * Home reads the settings only through InvariantDeviceProfile, which reads them with its own prefs
 * as it picks the grid (tallyBandShown, tallySearchShown), and Home's views through their device
 * profile. Nothing here resolves prefs from a context to read: a display's or window's context
 * resolves to the real app's credential-encrypted prefs, which throw before the first unlock (the
 * taskbar's boot sandbox builds a device profile then).
 *
 * Removing one only hides it: the notifications behind the band, the notification shade and All
 * apps' search (a swipe up) stay as they are.
 */
enum class TallyHomeItem(@JvmField val prefKey: String) {
    /** The tallies band under the date. */
    TALLIES(TALLIES_PREF_KEY),
    /** The search slot under the dock. */
    SEARCH(SEARCH_PREF_KEY);

    /** The setting: whether the item is on Home. */
    @JvmField val shown: ConstantItem<Boolean> = LauncherPrefs.backedUpItem(prefKey, true)

    /**
     * Puts the item on Home ([on]) or takes it off, in [context]'s Launcher: Home's own activity,
     * which runs only once the user is unlocked.
     */
    fun setShown(context: Context, on: Boolean) {
        LauncherPrefs.get(context).put(shown.to(on))
    }

    companion object {
        /** The settings' keys, which Home settings (launcher_preferences.xml) names too. */
        const val TALLIES_KEY = TALLIES_PREF_KEY
        const val SEARCH_KEY = SEARCH_PREF_KEY

        /** The item whose setting [key] is, or null. */
        @JvmStatic
        fun forKey(key: String?): TallyHomeItem? = entries.firstOrNull { it.prefKey == key }

        /**
         * Whether Home settings offers the items: on a phone, the only device Tally lays Home out
         * for (a tablet or a desktop keeps stock's Home).
         */
        @JvmStatic
        fun inSettings(context: Context): Boolean =
            InvariantDeviceProfile.INSTANCE.get(context).deviceType ==
                InvariantDeviceProfile.TYPE_PHONE
    }
}
