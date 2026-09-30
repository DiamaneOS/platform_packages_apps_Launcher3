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
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Home settings' switches follow their settings while open, as Home changes them too. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyHomeItemSwitchTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefs: SharedPreferences
    private lateinit var screen: PreferenceScreen
    private lateinit var switch: TallyHomeItemSwitch

    @Before
    fun setUp() = onMain {
        val manager = PreferenceManager(context)
        manager.sharedPreferencesName = PREFS
        prefs = manager.sharedPreferences!!
        prefs.edit().clear().commit()
        screen = manager.createPreferenceScreen(context)
        switch =
            TallyHomeItemSwitch(context).apply {
                key = TallyHomeItem.TALLIES_KEY
                setDefaultValue(true)
            }
        screen.addPreference(switch)
        // As Home settings shows its screen.
        screen.onAttached()
    }

    @After
    fun tearDown() = onMain {
        screen.onDetached()
        prefs.edit().clear().commit()
    }

    @Test
    fun showsTheSettingWhenHomeChangesIt() = onMain {
        assertTrue(switch.isChecked) // on Home by default
        // Removed from Home with Home settings open behind it.
        prefs.edit().putBoolean(TallyHomeItem.TALLIES_KEY, false).commit()
        assertFalse(switch.isChecked)
        // Undo.
        prefs.edit().putBoolean(TallyHomeItem.TALLIES_KEY, true).commit()
        assertTrue(switch.isChecked)
    }

    @Test
    fun aTapAfterwardsSetsWhatItShows() = onMain {
        prefs.edit().putBoolean(TallyHomeItem.TALLIES_KEY, false).commit()
        // A tap turns the shown value over (TwoStatePreference.onClick).
        switch.isChecked = !switch.isChecked
        assertTrue(prefs.getBoolean(TallyHomeItem.TALLIES_KEY, false))
    }

    @Test
    fun followsOnlyItsOwnSettingWhileShown() = onMain {
        prefs.edit().putBoolean(TallyHomeItem.SEARCH_KEY, false).commit()
        assertTrue(switch.isChecked)
        // Off screen it stops listening, and it reads the setting again when shown.
        screen.onDetached()
        prefs.edit().putBoolean(TallyHomeItem.TALLIES_KEY, false).commit()
        assertTrue(switch.isChecked)
        screen.onAttached()
        assertFalse(switch.isChecked)
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val PREFS = "tally_home_item_switch_test"
    }
}
