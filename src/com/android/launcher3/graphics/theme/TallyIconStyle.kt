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

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.launcher3.graphics.theme.ThemePreference.Companion.COLOUR_THEME_VALUE
import com.android.launcher3.graphics.theme.ThemePreference.Companion.MONO_THEME_VALUE
import com.android.launcher3.graphics.theme.ThemePreference.ThemeValue

/**
 * DiamaneOS Tally: the icon style by name. Wallpaper & style reads and sets it through Launcher's
 * customization provider ([PATH]); Launcher keeps it in a secure setting ([SETTING]) for SystemUI,
 * which draws notifications' app icons in the same style.
 *
 * Launcher's own preference ([ThemePreference]) stays the one source: the setting follows it, for
 * each user, written by Launcher (it already holds WRITE_SECURE_SETTINGS), and SystemUI only reads
 * it. Preview and boot contexts never write it.
 */
object TallyIconStyle {
    private const val TAG = "TallyIconStyle"

    /** The customization provider path that reads and sets the style, by name. */
    const val PATH = "/tally_icon_style"

    /** The style's column in [PATH]'s cursor, and its key in an update's values. */
    const val KEY = "icon_style"

    /** Each of DiamaneOS's own apps a key in its own colour; every other app its own icon. */
    const val COLOUR = "colour"

    /** Every app a monochrome glyph on the theme's key. */
    const val MINIMAL = "minimal"

    /** Every app its own icon. */
    const val NONE = "none"

    /** The secure setting SystemUI reads the style from (Settings.Secure, for each user). */
    const val SETTING = "tally_icon_style"

    /** The name of the style [value] stands for: any style other than these two is no style. */
    @JvmStatic
    fun nameOf(value: ThemeValue?): String =
        when (value) {
            COLOUR_THEME_VALUE -> COLOUR
            MONO_THEME_VALUE -> MINIMAL
            else -> NONE
        }

    /** Whether [name] is a style's name. */
    @JvmStatic
    fun isName(name: String?): Boolean = name == COLOUR || name == MINIMAL || name == NONE

    /** The preference value for the style [name] (one of [isName]'s). */
    @JvmStatic
    fun valueOf(name: String): ThemeValue? =
        when (name) {
            COLOUR -> COLOUR_THEME_VALUE
            MINIMAL -> MONO_THEME_VALUE
            else -> null
        }

    /** Keeps [SETTING] at the style [value] stands for, for this context's user. */
    @WorkerThread
    @JvmStatic
    fun publish(context: Context, value: ThemeValue?) {
        val name = nameOf(value)
        try {
            val resolver = context.contentResolver
            if (Settings.Secure.getString(resolver, SETTING) != name) {
                Settings.Secure.putString(resolver, SETTING, name)
            }
        } catch (e: SecurityException) {
            // A build of Launcher without WRITE_SECURE_SETTINGS: notifications keep their default.
            Log.w(TAG, "Cannot keep the icon style for SystemUI", e)
        }
    }
}
