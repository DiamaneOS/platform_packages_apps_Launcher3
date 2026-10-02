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
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.tally.TallyAppKeys
import com.android.launcher3.icons.tally.TallyColourIconThemeController
import com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_THEMED_ICON_DISABLED
import com.android.launcher3.logging.StatsLogManager.StatsLogger
import javax.inject.Inject

/**
 * DiamaneOS Tally: the Colour icon style, the default. Each of DiamaneOS's own apps is a key in its
 * own colour with a white glyph, from the Tally tokens' app key table; every other app keeps its
 * own icon ([TallyColourIconThemeController]).
 */
@LauncherAppSingleton
class TallyColourIconThemeFactory
@Inject
constructor(@ApplicationContext private val context: Context) : IconThemeFactory {

    // One controller, so the style's state compares equal across checks (ThemeManager).
    private val controller: IconThemeController by lazy {
        TallyColourIconThemeController(
            TallyAppKeys.load(
                context.resources,
                R.array.tally_app_key_packages,
                R.array.tally_app_key_plates,
                R.array.tally_app_key_glyphs,
            )
        )
    }

    override fun createController(themeId: String): IconThemeController? =
        if (themeId == TallyColourIconThemeController.THEME_ID) controller else null

    // Stats count the monochrome style as themed icons; the Colour style keeps apps' own icons.
    override fun logThemeEvent(themeId: String, logger: StatsLogger) {
        logger.log(LAUNCHER_THEMED_ICON_DISABLED)
    }

    companion object {
        const val COLOUR_FACTORY_ID = "tally-colour-icons"
    }
}
