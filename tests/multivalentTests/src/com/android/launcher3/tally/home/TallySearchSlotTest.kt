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

import android.view.View.MeasureSpec
import androidx.test.filters.LargeTest
import com.android.launcher3.Hotseat
import com.android.launcher3.Launcher
import com.android.launcher3.integration.util.LauncherActivityScenarioRule
import com.android.launcher3.testutil.rule.TestRules.overrideApplicationInActivity
import com.android.launcher3.util.LauncherMultivalentJUnit
import com.android.launcher3.util.SandboxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit

/** Home's search slot spans the hotseat as it is now, after a narrower layout too. */
@LargeTest
@RunWith(LauncherMultivalentJUnit::class)
class TallySearchSlotTest {

    @get:Rule val mockito = MockitoJUnit.rule()
    @get:Rule val app = SandboxApplication().withModelDependency()
    @get:Rule val appOverride = overrideApplicationInActivity(app, mockito)
    @get:Rule val launcherActivity = LauncherActivityScenarioRule<Launcher>()

    @Test
    fun spansTheHotseatAfterLandscapesNarrowBar() {
        launcherActivity.executeOnLauncher { launcher ->
            val hotseat = launcher.hotseat
            val slot = hotseat.qsb as TallySearchSlot
            assertTrue(slot.widthIn(UPRIGHT_PX) > slot.widthIn(BAR_PX))
            // Landscape lays the hotseat out as a narrow bar; back upright the slot shows again,
            // which asks for its layout, and the hotseat is measured at the screen's width.
            layOut(hotseat, BAR_PX)
            slot.requestLayout()
            layOut(hotseat, UPRIGHT_PX)
            assertEquals(slot.widthIn(UPRIGHT_PX), slot.width)
            // A width change alone measures it again too.
            layOut(hotseat, BAR_PX)
            layOut(hotseat, UPRIGHT_PX)
            assertEquals(slot.widthIn(UPRIGHT_PX), slot.width)
        }
    }

    private fun layOut(hotseat: Hotseat, widthPx: Int) {
        hotseat.measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(HEIGHT_PX, MeasureSpec.EXACTLY),
        )
        hotseat.layout(0, 0, widthPx, HEIGHT_PX)
    }

    private companion object {
        /** The FP6's screen width, and its hotseat's width as landscape's bar (px). */
        const val UPRIGHT_PX = 1116
        const val BAR_PX = 280
        const val HEIGHT_PX = 600
    }
}
