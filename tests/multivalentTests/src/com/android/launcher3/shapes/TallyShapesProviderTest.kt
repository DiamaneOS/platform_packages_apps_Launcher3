/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.shapes

import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.Flags.FLAG_ENABLE_LAUNCHER_ICON_SHAPES
import com.android.launcher3.graphics.ShapeDelegate.GenericPathShape
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.shapes.ShapesProvider.KEYCAP_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Tally keycap shape, beside ShapesProviderTest. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyShapesProviderTest {

    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES)
    fun `verify valid path keycap`() {
        ShapesProvider.iconShapes
            .find { it.key == KEYCAP_KEY }!!
            .run {
                GenericPathShape(pathString)
                PathParser.createPathFromPathData(pathString).verifyInBounds()
            }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_LAUNCHER_ICON_SHAPES)
    fun `verify keycap is the first and default shape`() {
        assertEquals(KEYCAP_KEY, ShapesProvider.iconShapes.first().key)
        assertEquals(KEYCAP_KEY, ThemeManager.PREF_ICON_SHAPE.defaultValue)
    }

    private fun Path.verifyInBounds() {
        val region = Region()
        region.setPath(this, Region().apply { set(-10, -10, 110, 110) })
        val bounds = region.bounds
        assertTrue("Path outside bounds $bounds", Rect(0, 0, 100, 100).contains(bounds))
    }
}
