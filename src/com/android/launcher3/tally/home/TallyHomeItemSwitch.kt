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
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.SwitchPreference

/**
 * Home settings' switch for one of Home's removable items ([TallyHomeItem]): the tallies band or
 * the search slot. A plain switch reads its setting once, but these settings also change on Home (a
 * long press and Remove, or Undo) while Home settings may still be open behind it, so the switch
 * showed the old value and a tap set the opposite of what the user meant. This one follows its
 * setting while it is on screen, as stock's notification dots row follows its system setting
 * (NotificationDotsPreference): it always shows the stored value, and a tap sets what it shows.
 */
class TallyHomeItemSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SwitchPreference(context, attrs) {

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            // A null key is a cleared file.
            if (changedKey == null || changedKey == key) showStored()
        }

    /** The preferences listened to while attached (they hold the listener only weakly). */
    private var listening: SharedPreferences? = null

    override fun onAttached() {
        super.onAttached()
        listening =
            sharedPreferences?.also { it.registerOnSharedPreferenceChangeListener(listener) }
        showStored()
    }

    override fun onDetached() {
        listening?.unregisterOnSharedPreferenceChangeListener(listener)
        listening = null
        super.onDetached()
    }

    /** Shows the stored value, or the item's default (on Home) when none is stored. */
    private fun showStored() {
        val default = TallyHomeItem.forKey(key)?.shown?.defaultValue ?: true
        isChecked = getPersistedBoolean(default)
    }
}
