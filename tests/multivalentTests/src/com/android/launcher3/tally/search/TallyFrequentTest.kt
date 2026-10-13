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
import com.android.launcher3.tally.search.TallyFrequent.Entry
import com.android.launcher3.tally.search.TallyFrequent.Key
import com.android.launcher3.tally.search.TallyFrequent.Kind
import com.android.launcher3.tally.search.TallyFrequent.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What Home keeps for the "Suggestions" row of its search, and what the row shows from it. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyFrequentTest {

    private fun app(name: String, profile: Long = 0) =
        TallyFrequent.appKey(profile, "org.example.$name", "org.example.$name.Main")!!

    private fun page(key: String) = TallyFrequent.pageKey(key)!!

    private fun State.opened(key: Key, day: Long, times: Int = 1, title: String = ""): State {
        var state = this
        repeat(times) { state = TallyFrequent.record(state, key, title, day) }
        return state
    }

    private fun State.shown(day: Long) = TallyFrequent.top(this, day).map { it.key }

    @Test
    fun aCountHalvesInFourteenDaysAndThenGoes() {
        val entry = Entry(app("clock"), 8000, DAY)
        assertEquals(8000, TallyFrequent.pointsOn(entry, DAY))
        assertEquals(4000, TallyFrequent.pointsOn(entry, DAY + 14))
        assertEquals(2000, TallyFrequent.pointsOn(entry, DAY + 28))
        assertEquals(0, TallyFrequent.pointsOn(entry, DAY + 10_000))
        // Two openings show for five days, and are forgotten once under a tenth.
        val state = State().opened(app("clock"), DAY, times = 2)
        assertEquals(listOf(app("clock")), state.shown(DAY + 5))
        assertTrue(state.shown(DAY + 6).isEmpty())
        assertSame(state, TallyFrequent.settle(state, DAY + 60))
        assertTrue(TallyFrequent.settle(state, DAY + 61).entries.isEmpty())
    }

    @Test
    fun anOpeningAddsToTheFadedCount() {
        val clock = app("clock")
        val state = State().opened(clock, DAY, times = 4).opened(clock, DAY + 14)
        assertEquals(listOf(Entry(clock, 3000, DAY + 14)), state.entries)
    }

    @Test
    fun appsInTheDockCanBeLeftOutAndStayCounted() {
        var state = State().opened(page("hotspot"), DAY, times = 4, title = "Hotspot")
        for ((at, name) in listOf("a", "b", "c", "d", "e", "f").withIndex()) {
            state = state.opened(app(name), DAY, times = 10 - at)
        }
        // With a and c in the dock, the next most used take their places.
        val dock = setOf(app("a"), app("c"), app("unused"))
        assertEquals(
            listOf(app("b"), app("d"), app("e"), app("f"), page("hotspot")),
            TallyFrequent.top(state, DAY, dock).map { it.key },
        )
        // Apps in the dock are still counted.
        val counted = state.opened(app("a"), DAY)
        assertEquals(11_000, counted.entries.first { it.key == app("a") }.points)
        // Off the dock, a shows. The work profile's a is another app.
        assertEquals(app("a"), TallyFrequent.top(counted, DAY, setOf(app("c"))).first().key)
        val atWork = counted.opened(app("a", profile = 10), DAY, times = 20)
        assertEquals(app("a", 10), TallyFrequent.top(atWork, DAY, dock).first().key)
        // Left out and unusable together.
        val shown = TallyFrequent.top(state, DAY, dock) { it != app("b") }.map { it.key }
        assertEquals(listOf(app("d"), app("e"), app("f"), page("hotspot")), shown)
    }

    @Test
    fun showingHiddenItemsAgainKeepsEveryCount() {
        val state =
            TallyFrequent.inLocale(State(), "en-GB")
                .opened(app("clock"), DAY, times = 3)
                .opened(app("vault"), DAY, times = 5)
                .opened(page("vpn"), DAY, times = 2, title = "VPN")
        val hidden = TallyFrequent.hide(TallyFrequent.hide(state, app("vault")), page("vpn"))
        assertEquals(2, hidden.hidden.size)
        val again = TallyFrequent.unhide(hidden)
        assertTrue(again.hidden.isEmpty())
        // The other counts stay. A hidden item's count is not restored.
        assertEquals(hidden.entries, again.entries)
        assertEquals(listOf(Entry(app("clock"), 3000, DAY)), again.entries)
        assertEquals("en-GB", again.locale)
        assertFalse(TallyFrequent.encode(again).contains("vault"))
        // It counts again from nothing.
        val back = again.opened(app("vault"), DAY + 1, times = 2)
        assertEquals(listOf(app("clock"), app("vault")), back.shown(DAY + 1))
        assertSame(again, TallyFrequent.unhide(again))
    }

    @Test
    fun aNewItemTakesTheLeastUsedOnesPlace() {
        var state = State()
        for (at in 0 until TallyFrequent.MAX_ENTRIES) {
            state = state.opened(app("app$at"), DAY, times = if (at == 7) 1 else 2)
        }
        assertEquals(TallyFrequent.MAX_ENTRIES, state.entries.size)
        state = state.opened(app("newcomer"), DAY)
        assertEquals(TallyFrequent.MAX_ENTRIES, state.entries.size)
        assertTrue(state.entries.any { it.key == app("newcomer") })
        assertFalse(state.entries.any { it.key == app("app7") })
    }

    @Test
    fun dontSuggestForgetsTheCountAndStopsCounting() {
        val health = app("health")
        val state = State().opened(health, DAY, times = 5).opened(app("clock"), DAY, times = 2)
        val hidden = TallyFrequent.hide(state, health)
        assertEquals(listOf(app("clock")), hidden.entries.map { it.key })
        assertEquals(listOf(health), hidden.hidden)
        // Opened again, it is neither counted nor shown, also after the record is read back.
        val again = hidden.opened(health, DAY + 1, times = 5)
        assertSame(hidden, again)
        val read = TallyFrequent.decode(TallyFrequent.encode(hidden)).opened(health, DAY + 1, 5)
        assertEquals(listOf(app("clock")), read.entries.map { it.key })
        assertEquals(listOf(health), read.hidden)
    }

    @Test
    fun dontSuggestHoldsWhateverTheAppsActivityIsCalled() {
        // An app that swaps its icon shows another launcher activity of the same package.
        val plain = TallyFrequent.appKey(0, "org.example.vault", "org.example.vault.Main")!!
        val disguised = TallyFrequent.appKey(0, "org.example.vault", "org.example.vault.Notes")!!
        val atWork = TallyFrequent.appKey(10, "org.example.vault", "org.example.vault.Main")!!
        val state = State().opened(plain, DAY, 3).opened(disguised, DAY, 3).opened(atWork, DAY, 3)
        val hidden = TallyFrequent.hide(state, plain)
        // Both activities' counts go. The work profile's copy is another app.
        assertEquals(listOf(atWork), hidden.entries.map { it.key })
        assertTrue(TallyFrequent.hides(hidden, disguised))
        assertFalse(TallyFrequent.hides(hidden, atWork))
        assertSame(hidden, hidden.opened(disguised, DAY + 1, 5))
        // Chosen again under the other name, it is still one name.
        assertEquals(listOf(disguised), TallyFrequent.hide(hidden, disguised).hidden)
        // A record that counts a hidden app's other activity does not read it back.
        val text =
            TallyFrequent.encode(hidden) +
                "a\t0\torg.example.vault/org.example.vault.Notes\t5000\t$DAY\n"
        assertEquals(listOf(atWork), TallyFrequent.decode(text).entries.map { it.key })
    }

    @Test
    fun anUninstalledAppLeavesNothing() {
        val state =
            TallyFrequent.hide(
                State()
                    .opened(app("clock"), DAY, times = 2)
                    .opened(app("health", profile = 10), DAY, times = 2)
                    .opened(app("health"), DAY, times = 2)
                    .opened(app("notes"), DAY, times = 2),
                app("notes"),
            )
        // Health goes from the user's own profile. The work profile's copy stays.
        val gone =
            TallyFrequent.forget(state) {
                it.packageName in setOf("org.example.health", "org.example.notes") &&
                    it.profile == 0L
            }
        assertEquals(listOf(app("clock"), app("health", 10)), gone.entries.map { it.key })
        assertTrue(gone.hidden.isEmpty())
        assertFalse(TallyFrequent.encode(gone).contains("notes"))
        assertSame(gone, TallyFrequent.forget(gone) { false })
    }

    @Test
    fun thePrivateSpaceIsNeverCounted() {
        assertNull(TallyFrequent.appKey(0, "com.android.privatespace", "com.android.Main"))
        for (key in
            listOf(
                "private_space_hidden",
                "private_space",
                "change_private_space_lock",
                "sandboxed_google_play_private_space",
                "private_profile_location_switch",
                "default_autofill_private",
                "auto_sync_private_account_data",
                "private_lock_unification",
                "PrivateSpaceSettings",
            )) {
            assertNull(key, TallyFrequent.pageKey(key))
        }
        for (key in listOf("private_dns_settings", "privacy_dashboard_page", "top_level_privacy")) {
            assertNotNull(key, TallyFrequent.pageKey(key))
        }
        // Nor does a record that names it read back.
        val text =
            "tally-frequent 1\n" +
                "a\t0\tcom.android.privatespace/com.android.Main\t5000\t$DAY\n" +
                "p\t-1\tprivate_space_hidden\t5000\t$DAY\tHide\n" +
                "h\tp\t-1\tprivate_space\n"
        assertEquals(State(), TallyFrequent.decode(text))
    }

    @Test
    fun onlyNamesThatFitOnALineAreKept() {
        assertNull(TallyFrequent.appKey(0, "org.example", ""))
        assertNull(TallyFrequent.appKey(0, "org.example\n", "Main"))
        assertNull(TallyFrequent.appKey(0, "org/example", "Main"))
        assertNull(TallyFrequent.appKey(-1, "org.example", "Main"))
        assertNull(TallyFrequent.pageKey("a\tb"))
        assertNull(TallyFrequent.pageKey(""))
        assertNull(TallyFrequent.pageKey("k".repeat(TallyFrequent.MAX_TEXT + 1)))
        // A page whose title cannot be kept is not counted.
        val state = State().opened(page("hotspot"), DAY, title = "Hot\nspot")
        assertTrue(state.entries.isEmpty())
        assertTrue(State().opened(page("hotspot"), DAY, title = "").entries.isEmpty())
    }

    @Test
    fun theRecordIsAFewLinesOfText() {
        val state =
            TallyFrequent.inLocale(
                TallyFrequent.hide(
                    State()
                        .opened(page("hotspot"), DAY, times = 3, title = "Hotspot & tethering")
                        .opened(app("clock"), DAY - 2, times = 7)
                        .opened(app("mail", profile = 10), DAY, times = 2)
                        .opened(app("health"), DAY, times = 2),
                    app("health"),
                ),
                "en-GB",
            )
        // The page's count goes when the record gets its language.
        assertEquals(
            "tally-frequent 1\n" +
                "l\ten-GB\n" +
                "a\t0\torg.example.clock/org.example.clock.Main\t7000\t${DAY - 2}\n" +
                "a\t10\torg.example.mail/org.example.mail.Main\t2000\t$DAY\n" +
                "h\ta\t0\torg.example.health/org.example.health.Main\n",
            TallyFrequent.encode(state),
        )
        val withPage = state.opened(page("hotspot"), DAY, times = 3, title = "Hotspot & tethering")
        val text = TallyFrequent.encode(withPage)
        assertTrue(text.contains("p\t-1\thotspot\t3000\t$DAY\tHotspot & tethering\n"))
        assertEquals(withPage, TallyFrequent.decode(text))
    }

    @Test
    fun aDamagedRecordLosesOnlyItsBadLines() {
        assertEquals(State(), TallyFrequent.decode(""))
        assertEquals(State(), TallyFrequent.decode("something else\na\t0\ta/b\t1000\t1\n"))
        val text =
            "tally-frequent 1\n" +
                "a\t0\torg.example.clock/Main\t2000\t$DAY\n" +
                "a\t0\torg.example.clock/Main\t9000\t$DAY\n" + // a second count of the same
                "a\t0\torg.example.zero/Main\t0\t$DAY\n" +
                "a\t0\torg.example.huge/Main\t100001\t$DAY\n" +
                "a\tx\torg.example.who/Main\t2000\t$DAY\n" +
                "a\t0\torg.example.title/Main\t2000\t$DAY\tA title\n" +
                "a\t0\torg.example.day/Main\t2000\t-4\n" +
                "p\t-1\thotspot\t2000\t$DAY\n" + // a page without its title
                "p\t0\thotspot\t2000\t$DAY\tHotspot\n" + // a page in a profile
                "q\t0\tnothing\t2000\t$DAY\n" +
                "h\ta\t0\n" +
                "\n" +
                "p\t-1\tbluetooth\t3000\t$DAY\tBluetooth\n"
        val state = TallyFrequent.decode(text)
        assertEquals(
            listOf(
                Entry(Key(Kind.APP, 0, "org.example.clock/Main"), 2000, DAY),
                Entry(Key(Kind.PAGE, -1, "bluetooth"), 3000, DAY, "Bluetooth"),
            ),
            state.entries,
        )
        assertTrue(state.hidden.isEmpty())
    }

    @Test
    fun aRecordReadBackKeepsToTheLimits() {
        val text = buildString {
            append("tally-frequent 1\n")
            for (at in 0 until 60) append("a\t0\torg.example.app$at/Main\t2000\t$DAY\n")
            for (at in 0 until 130) append("h\ta\t0\torg.example.hidden$at/Main\n")
            append("h\ta\t0\torg.example.app3/Main\n")
        }
        val state = TallyFrequent.decode(text)
        assertTrue(state.entries.size <= TallyFrequent.MAX_ENTRIES)
        assertEquals(TallyFrequent.MAX_HIDDEN, state.hidden.size)
        // An item never to suggest has no count.
        assertFalse(state.entries.any { it.key.id == "org.example.app3/Main" })
    }

    @Test
    fun aClockSetBackNeitherAddsNorKeepsALaterDay() {
        val clock = app("clock")
        val state = State().opened(clock, DAY, times = 2)
        assertEquals(2000, TallyFrequent.pointsOn(state.entries.single(), DAY - 30))
        val settled = TallyFrequent.settle(state, DAY - 30)
        assertEquals(listOf(Entry(clock, 2000, DAY - 30)), settled.entries)
        assertEquals(listOf(Entry(clock, 3000, DAY - 30)), state.opened(clock, DAY - 30).entries)
    }

    private companion object {
        /** Days since 1970. */
        const val DAY = 20736L
    }
}
