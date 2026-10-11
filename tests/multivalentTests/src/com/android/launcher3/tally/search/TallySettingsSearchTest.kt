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

package com.android.launcher3.tally.search

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How Home's search asks SettingsIntelligence to open a Settings result. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallySettingsSearchTest {

    private val search = TallySettingsSearch(getInstrumentation().targetContext)

    @Test
    fun opensThroughSettingsIntelligenceWithKindKeyAndTitle() {
        val app = TallySettingsSearch.Page("org.example.notes", "Notes", "Apps", "app")
        val intent = search.openIntent(app)
        assertEquals(TallySettingsSearch.PACKAGE, intent.component!!.packageName)
        assertEquals(
            "${TallySettingsSearch.PACKAGE}.search.TallyHomeSearchActivity",
            intent.component!!.className,
        )
        assertNull(intent.action)
        assertNull(intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val extras = intent.extras!!
        assertEquals(
            setOf(
                "de.diamaneos.settingssearch.extra.KEY",
                "de.diamaneos.settingssearch.extra.TITLE",
                "de.diamaneos.settingssearch.extra.KIND",
            ),
            extras.keySet(),
        )
        assertEquals("org.example.notes", extras.getString("de.diamaneos.settingssearch.extra.KEY"))
        assertEquals("Notes", extras.getString("de.diamaneos.settingssearch.extra.TITLE"))
        assertEquals("app", extras.getString("de.diamaneos.settingssearch.extra.KIND"))
    }

    @Test
    fun aResultIsAPageUnlessSaidOtherwise() {
        val page = TallySettingsSearch.Page("battery_saver", "Battery Saver", "Battery")
        assertEquals(TallySettingsSearch.KIND_PAGE, page.kind)
        assertEquals(
            TallySettingsSearch.KIND_PAGE,
            search.openIntent(page).getStringExtra("de.diamaneos.settingssearch.extra.KIND"),
        )
    }
}
