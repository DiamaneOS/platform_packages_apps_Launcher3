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

package com.android.launcher3.tally.moments

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.tally.search.TallySettingsSearch
import com.android.launcher3.tally.search.TallySettingsSearch.Companion.KIND_APP
import com.android.launcher3.tally.search.TallySettingsSearch.Page
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** What Moments lets through to Recents and search: the chosen apps of Home's own user only. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsHomeTest {

    private val me = Process.myUserHandle()
    // The next user (UserHandle.PER_USER_RANGE uids per user).
    private val other = UserHandle.getUserHandleForUid(Process.myUid() + 100_000)

    @After
    fun tearDown() {
        MomentsHome.setForTest(active = false, packages = emptySet())
    }

    @Test
    fun off_letsEverythingThrough() {
        assertThat(MomentsHome.shouldShowTask(OTHER, other)).isTrue()
        assertThat(MomentsHome.shouldShowPackage(OTHER)).isTrue()
        assertThat(TallySettingsSearch.shownByMoments(Page("battery", "Battery", null))).isTrue()
        assertThat(TallySettingsSearch.shownByMoments(Page(OTHER, "Other", null, KIND_APP)))
            .isTrue()
    }

    @Test
    fun on_tasksOfTheChosenAppsOfThisUserOnly() {
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        assertThat(MomentsHome.shouldShowTask(CHOSEN, me)).isTrue()
        assertThat(MomentsHome.shouldShowTask(CHOSEN, other)).isFalse()
        assertThat(MomentsHome.shouldShowTask(OTHER, me)).isFalse()
        assertThat(MomentsHome.shouldShowTask(null, me)).isFalse()
    }

    @Test
    fun on_searchLeadsOnlyToTheChosenApps() {
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        assertThat(MomentsHome.shouldShowPackage(CHOSEN)).isTrue()
        assertThat(MomentsHome.shouldShowPackage(OTHER)).isFalse()
        // Every Settings result opens in Settings, so none without it.
        assertThat(TallySettingsSearch.shownByMoments(Page(CHOSEN, "Chosen", null, KIND_APP)))
            .isFalse()
        assertThat(TallySettingsSearch.shownByMoments(Page("battery", "Battery", null))).isFalse()

        // With Settings chosen: its pages, and the info of chosen apps only.
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN, SETTINGS))
        assertThat(TallySettingsSearch.shownByMoments(Page("battery", "Battery", null))).isTrue()
        assertThat(TallySettingsSearch.shownByMoments(Page(CHOSEN, "Chosen", null, KIND_APP)))
            .isTrue()
        assertThat(TallySettingsSearch.shownByMoments(Page(OTHER, "Other", null, KIND_APP)))
            .isFalse()
    }

    @Test
    fun on_searchListsTheChosenAppsOfThisUserOnly() {
        // The private space's install entry has no component.
        val noComponent = AppInfo()
        assertThat(MomentsHome.shouldShowApp(app(OTHER, me))).isTrue()
        assertThat(MomentsHome.shouldShowApp(noComponent)).isTrue()

        // Without All apps' list loaded again: search asks Moments itself.
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        assertThat(MomentsHome.shouldShowApp(app(CHOSEN, me))).isTrue()
        assertThat(MomentsHome.shouldShowApp(app(CHOSEN, other))).isFalse()
        assertThat(MomentsHome.shouldShowApp(app(OTHER, me))).isFalse()
        assertThat(MomentsHome.shouldShowApp(noComponent)).isFalse()
    }

    @Test
    fun listeners_hearEachChangeUntilRemoved() {
        var heard = 0
        val listener = Runnable { heard++ }
        MomentsHome.addListener(listener)
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        MomentsHome.removeListener(listener)
        MomentsHome.setForTest(active = false, packages = emptySet())
        assertThat(heard).isEqualTo(1)
    }

    private fun app(packageName: String, user: UserHandle) =
        AppInfo(ComponentName(packageName, "$packageName.Main"), packageName, user, Intent())

    private companion object {
        const val CHOSEN = "com.example.chosen"
        const val OTHER = "com.example.other"
        const val SETTINGS = TallySettingsSearch.SETTINGS_PACKAGE
    }
}
