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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.tally.search.TallySearchRanking.Found
import com.android.launcher3.tally.search.TallySearchRanking.Kind
import com.android.launcher3.tally.search.TallySearchRanking.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How Home's search ranks what it found and fills its sections. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallySearchRankingTest {

    private fun found(title: String, kind: Kind, query: String) =
        Found(title, title, kind, TallySearchRanking.match(query, title) ?: Match.OTHER)

    @Test
    fun matches() {
        assertEquals(Match.EXACT, TallySearchRanking.match("clock", "Clock"))
        assertEquals(Match.PREFIX, TallySearchRanking.match("batt", "Battery saver"))
        assertEquals(Match.WORD_PREFIX, TallySearchRanking.match("saver", "Battery saver"))
        assertEquals(Match.WORDS, TallySearchRanking.match("sav batt", "Battery saver"))
        assertEquals(Match.CONTAINS, TallySearchRanking.match("tery", "Battery"))
        assertEquals(Match.PREFIX, TallySearchRanking.match("ecr", "Écran"))
        assertEquals(Match.EXACT, TallySearchRanking.match("  clock ", "CLOCK"))
        assertNull(TallySearchRanking.match("x", "Clock"))
        assertNull(TallySearchRanking.match("", "Clock"))
        // Each typed word needs its own word.
        assertNull(TallySearchRanking.match("bat bat", "Battery saver"))
    }

    @Test
    fun theMockSheet() {
        // Typing "batt" with no app of that name: the Battery page is on top, alone.
        val q = "batt"
        val settings =
            listOf("Battery", "Battery saver", "Battery usage").map { found(it, Kind.SETTING, q) }
        val sections = TallySearchRanking.sections(emptyList(), emptyList(), settings, false)
        assertEquals("Battery", sections.top!!.title)
        assertEquals(listOf("Battery saver", "Battery usage"), sections.settings.map { it.title })
        assertTrue(sections.apps.isEmpty())
        assertFalse(sections.answerOnTop)
    }

    @Test
    fun appsRankBestFirstAndWinTies() {
        val q = "cal"
        val apps =
            listOf("Vocal Coach", "Calendar", "Calculator", "Local Weather").map {
                found(it, Kind.APP, q)
            }
        val settings = listOf(found("Calls", Kind.SETTING, q))
        val sections = TallySearchRanking.sections(apps, emptyList(), settings, false)
        // Both prefixes beat a setting's prefix; ties go by title.
        assertEquals("Calculator", sections.top!!.title)
        assertEquals(
            listOf("Calendar", "Local Weather", "Vocal Coach"),
            sections.apps.map { it.title },
        )
        assertEquals(listOf("Calls"), sections.settings.map { it.title })
    }

    @Test
    fun aBetterShortcutOrPageCanTopTheApps() {
        val q = "new timer"
        val apps = listOf(Found("Clock", "Clock", Kind.APP, Match.OTHER))
        val shortcuts = listOf(found("New timer", Kind.SHORTCUT, q))
        val sections = TallySearchRanking.sections(apps, shortcuts, emptyList(), false)
        assertEquals("New timer", sections.top!!.title)
        assertTrue(sections.shortcuts.isEmpty())
        assertEquals(listOf("Clock"), sections.apps.map { it.title })
    }

    @Test
    fun anAnswerIsOnTop() {
        val apps = listOf(found("Calculator", Kind.APP, "calc"))
        val sections = TallySearchRanking.sections(apps, emptyList(), emptyList(), true)
        assertNull(sections.top)
        assertTrue(sections.answerOnTop)
        assertEquals(listOf("Calculator"), sections.apps.map { it.title })
    }

    @Test
    fun sectionsKeepTheirLimits() {
        val q = "a"
        val apps = (1..9).map { found("App $it", Kind.APP, q) }
        val shortcuts = (1..9).map { found("A shortcut $it", Kind.SHORTCUT, q) }
        val settings = (1..9).map { found("A page $it", Kind.SETTING, q) }
        val sections = TallySearchRanking.sections(apps, shortcuts, settings, false)
        // The top came from the apps, which keep the rest of their five.
        assertEquals("App 1", sections.top!!.title)
        assertEquals(TallySearchRanking.MAX_APPS - 1, sections.apps.size)
        assertEquals(TallySearchRanking.MAX_SHORTCUTS, sections.shortcuts.size)
        assertEquals(TallySearchRanking.MAX_SETTINGS, sections.settings.size)
        // Settings pages keep Settings search's own order.
        assertEquals("A page 1", sections.settings.first().title)
    }

    @Test
    fun anAppShowsOnceNotAgainAsAppInfo() {
        // Items are "app:package/user" for apps, "info:package/user" for app-info results.
        val appKey = { item: String ->
            if (item.startsWith("app:") || item.startsWith("info:")) {
                val (pkg, user) = item.substringAfter(':').split('/')
                TallySearchRanking.AppKey(pkg, user)
            } else {
                null
            }
        }
        val q = "notes"
        val apps =
            listOf(
                Found("app:org.notes/0", "Notes", Kind.APP, Match.EXACT),
                Found("app:org.notes/10", "Notes", Kind.APP, Match.EXACT),
            )
        val settings =
            listOf(
                Found("info:org.notes/0", "Notes", Kind.SETTING, Match.EXACT),
                // The same package in another user than any shown app is another app.
                Found("info:org.notes/11", "Notes", Kind.SETTING, Match.EXACT),
                // An app with no launcher entry keeps its app info.
                Found("info:org.notes.sync/0", "Notes sync", Kind.SETTING, Match.PREFIX),
                found("Notes on lock screen", Kind.SETTING, q),
            )
        val sections = TallySearchRanking.sections(apps, emptyList(), settings, false, appKey)
        assertEquals("app:org.notes/0", sections.top!!.item)
        assertEquals(listOf("app:org.notes/10"), sections.apps.map { it.item })
        assertEquals(
            listOf("info:org.notes/11", "info:org.notes.sync/0", "Notes on lock screen"),
            sections.settings.map { it.item },
        )
        // Without app keys nothing is hidden.
        val all = TallySearchRanking.sections(apps, emptyList(), settings, false)
        assertEquals(settings.size, all.settings.size)
    }

    @Test
    fun nothingFound() {
        val sections =
            TallySearchRanking.sections(emptyList<Found<String>>(), emptyList(), emptyList(), false)
        assertNull(sections.top)
        assertTrue(sections.apps.isEmpty() && sections.settings.isEmpty())
    }
}
