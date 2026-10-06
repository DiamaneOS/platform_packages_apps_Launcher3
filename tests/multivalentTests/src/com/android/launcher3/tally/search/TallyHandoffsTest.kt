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
import com.android.launcher3.tally.search.TallyHandoffs.Kind
import com.android.launcher3.tally.search.TallyHandoffs.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Which apps Home's search hands the typed words to, and how. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyHandoffsTest {

    private val contacts = Target("com.android.contacts", ".PeopleActivity", "Contacts")
    private val contactsSearch =
        Target("com.android.contacts", "com.android.contacts.activities.PeopleActivity", "People")
    private val files = Target("com.android.documentsui", ".LauncherActivity", "Files")
    private val vanadium = Target("app.vanadium.browser", ".ChromeTabbedActivity", "Vanadium")
    private val vanadiumSearch =
        Target("app.vanadium.browser", "com.google.android.apps.chrome.IntentDispatcher", "")
    private val chooser =
        Target("android", "com.android.internal.app.ResolverActivity", "Open with")

    private class FakeResolver(
        val defaults: Map<Kind, Target>,
        val searches: Map<Pair<Kind, String>, Target>,
    ) : TallyHandoffs.Resolver {
        override fun defaultApp(kind: Kind) = defaults[kind]

        override fun searchActivity(kind: Kind, packageName: String) = searches[kind to packageName]
    }

    @Test
    fun theDefaultAppsSearchActivityWithTheAppsName() {
        val resolver =
            FakeResolver(
                mapOf(Kind.CONTACTS to contacts, Kind.WEB to vanadium),
                mapOf(
                    (Kind.CONTACTS to contacts.packageName) to contactsSearch,
                    (Kind.WEB to vanadium.packageName) to vanadiumSearch,
                ),
            )
        val target = TallyHandoffs.target(Kind.CONTACTS, resolver)!!
        assertEquals(contactsSearch.className, target.className)
        assertEquals("Contacts", target.label)
        assertEquals("Vanadium", TallyHandoffs.target(Kind.WEB, resolver)!!.label)
    }

    @Test
    fun noHandoffWithoutADefaultOrASearch() {
        val resolver =
            FakeResolver(
                // Files takes no search (DocumentsUI at the pin); the web has no default browser.
                mapOf(Kind.FILES to files, Kind.WEB to chooser),
                emptyMap(),
            )
        assertNull(TallyHandoffs.target(Kind.CONTACTS, resolver))
        assertNull(TallyHandoffs.target(Kind.FILES, resolver))
        assertNull(TallyHandoffs.target(Kind.WEB, resolver))
    }

    @Test
    fun aSearchInAnotherAppDoesNotCount() {
        val resolver =
            FakeResolver(
                mapOf(Kind.CONTACTS to contacts),
                mapOf((Kind.CONTACTS to contacts.packageName) to vanadiumSearch),
            )
        assertNull(TallyHandoffs.target(Kind.CONTACTS, resolver))
    }

    @Test
    fun handoffsInTheSheetsOrder() {
        val targets = mapOf(Kind.WEB to vanadium, Kind.CONTACTS to contacts)
        val handoffs = TallyHandoffs.handoffs("  batt  ", targets)
        assertEquals(listOf(Kind.CONTACTS, Kind.WEB), handoffs.map { it.kind })
        assertTrue(handoffs.all { it.query == "batt" })
        assertTrue(TallyHandoffs.handoffs("   ", targets).isEmpty())
        assertTrue(TallyHandoffs.handoffs("batt", emptyMap()).isEmpty())
    }

    @Test
    fun theWordsPassedOn() {
        assertEquals("new york", TallyHandoffs.queryOf(" new \n york "))
        assertNull(TallyHandoffs.queryOf(" \t "))
        val long = "a".repeat(TallyHandoffs.MAX_QUERY_LENGTH + 50)
        assertEquals(TallyHandoffs.MAX_QUERY_LENGTH, TallyHandoffs.queryOf(long)!!.length)
        // Not cut inside a character outside the basic plane.
        val emoji = "a".repeat(TallyHandoffs.MAX_QUERY_LENGTH - 1) + "😀"
        assertEquals(TallyHandoffs.MAX_QUERY_LENGTH - 1, TallyHandoffs.queryOf(emoji)!!.length)
    }
}
