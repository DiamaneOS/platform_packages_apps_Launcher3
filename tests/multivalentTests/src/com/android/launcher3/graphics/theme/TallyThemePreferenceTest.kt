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

package com.android.launcher3.graphics.theme

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.ConstantItem
import com.android.launcher3.InMemoryLauncherPrefs
import com.android.launcher3.graphics.theme.ThemePreference.Companion.COLOUR_THEME_VALUE
import com.android.launcher3.graphics.theme.ThemePreference.Companion.MONO_THEME_VALUE
import com.android.launcher3.util.SandboxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** ThemePreference's Tally default, beside ThemePreferenceTest. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyThemePreferenceTest {

    @get:Rule val context = SandboxApplication()

    private val prefs = InMemoryLauncherPrefs(context)

    private fun getThemePref(legacyThemeKeys: Map<String, ConstantItem<String>> = emptyMap()) =
        ThemePreference(prefs, legacyThemeKeys)

    @Test
    fun no_choice_defaults_to_colour() {
        assertEquals(COLOUR_THEME_VALUE, getThemePref().value)

        // Choosing Minimal is kept
        getThemePref().setValue(MONO_THEME_VALUE)
        assertEquals(MONO_THEME_VALUE, getThemePref().value)

        // Choosing Default is kept
        getThemePref().setValue(null)
        assertNull(getThemePref().value)
    }
}
