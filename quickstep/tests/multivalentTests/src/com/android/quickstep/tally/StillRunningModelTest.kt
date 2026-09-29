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

package com.android.quickstep.tally

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Which swiped card gets Recents' "Still running · Stop" row, and when it goes. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class StillRunningModelTest {

    private val model = StillRunningModel()

    @Test
    fun swipedAppThatSystemUiLists_showsItsRow() {
        model.onStoppableAppsChanged(setOf(MUSIC, DOWNLOADS))

        model.onTaskSwiped(MUSIC, "Music")

        assertThat(model.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun swipedAppThatSystemUiDoesNotList_showsNoRow() {
        model.onStoppableAppsChanged(setOf(DOWNLOADS))

        model.onTaskSwiped(MUSIC, "Music")

        assertThat(model.row).isNull()
    }

    @Test
    fun sameAppInAnotherUser_isNotAMatch() {
        // SystemUI lists the work profile's Music only.
        model.onStoppableAppsChanged(setOf(WORK_MUSIC))

        model.onTaskSwiped(MUSIC, "Music")
        assertThat(model.row).isNull()

        model.onTaskSwiped(WORK_MUSIC, "Work Music")
        assertThat(model.row).isEqualTo(StillRunningModel.Row(WORK_MUSIC, "Work Music"))
    }

    @Test
    fun otherAppInSameUser_isNotAMatch() {
        model.onStoppableAppsChanged(setOf(MUSIC))

        model.onTaskSwiped(StoppableApp("com.example.musicplayer", 0), "Music player")

        assertThat(model.row).isNull()
    }

    @Test
    fun swipedBeforeTheListCame_showsWhenSystemUiListsIt() {
        model.onTaskSwiped(MUSIC, "Music")
        assertThat(model.row).isNull()

        model.onStoppableAppsChanged(setOf(MUSIC))

        assertThat(model.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun swipedBeforeTheListCame_neverShowsWhenSystemUiDoesNotListIt() {
        model.onTaskSwiped(MUSIC, "Music")

        model.onStoppableAppsChanged(setOf(DOWNLOADS))
        model.onStoppableAppsChanged(setOf(DOWNLOADS, MUSIC))

        assertThat(model.row).isNull()
    }

    @Test
    fun swipedBeforeTheListCame_andNotListed_keepsTheEarlierRow() {
        model.onStoppableAppsChanged(setOf(MUSIC))
        model.onTaskSwiped(MUSIC, "Music")

        // Recents closes and opens; a card is swiped before SystemUI answers.
        model.onStoppableAppsChanged(null)
        model.onTaskSwiped(DOWNLOADS, "Downloads")
        model.onStoppableAppsChanged(setOf(MUSIC))

        assertThat(model.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun rowGoes_whenSystemUiNoLongerListsTheApp_andDoesNotComeBackByItself() {
        model.onStoppableAppsChanged(setOf(MUSIC))
        model.onTaskSwiped(MUSIC, "Music")

        model.onStoppableAppsChanged(emptySet())
        assertThat(model.row).isNull()

        model.onStoppableAppsChanged(setOf(MUSIC))
        assertThat(model.row).isNull()
    }

    @Test
    fun rowHidesWhileTheListIsUnknown_andIsBackWhenRecentsOpensAgain() {
        model.onStoppableAppsChanged(setOf(MUSIC))
        model.onTaskSwiped(MUSIC, "Music")

        // Recents closes, then opens: nothing shows until SystemUI answers.
        model.onStoppableAppsChanged(null)
        assertThat(model.row).isNull()

        model.onStoppableAppsChanged(setOf(MUSIC))
        assertThat(model.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun stop_returnsTheSwipedAppAndHidesTheRow() {
        model.onStoppableAppsChanged(setOf(MUSIC, WORK_MUSIC))
        model.onTaskSwiped(WORK_MUSIC, "Work Music")

        assertThat(model.takeStop()).isEqualTo(WORK_MUSIC)
        assertThat(model.row).isNull()
        assertThat(model.takeStop()).isNull()
    }

    @Test
    fun stop_withoutARow_stopsNothing() {
        assertThat(model.takeStop()).isNull()

        model.onStoppableAppsChanged(setOf(MUSIC))
        assertThat(model.takeStop()).isNull()

        model.onTaskSwiped(DOWNLOADS, "Downloads")
        assertThat(model.takeStop()).isNull()
    }

    @Test
    fun stopRefusedBySystemUi_rowStaysHidden() {
        model.onStoppableAppsChanged(setOf(MUSIC))
        model.onTaskSwiped(MUSIC, "Music")
        model.takeStop()

        // SystemUI refused and still lists the app; another app's service changed meanwhile.
        model.onStoppableAppsChanged(setOf(MUSIC, DOWNLOADS))

        assertThat(model.row).isNull()
    }

    @Test
    fun swipedAgainAfterStop_showsTheRowAgain() {
        model.onStoppableAppsChanged(setOf(MUSIC))
        model.onTaskSwiped(MUSIC, "Music")
        model.takeStop()

        model.onTaskSwiped(MUSIC, "Music")

        assertThat(model.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun laterSwipeOfAnAppNotListed_keepsTheRow() {
        model.onStoppableAppsChanged(setOf(MUSIC))
        model.onTaskSwiped(MUSIC, "Music")

        model.onTaskSwiped(DOWNLOADS, "Downloads")

        assertThat(model.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun laterSwipeOfAnotherListedApp_replacesTheRow() {
        model.onStoppableAppsChanged(setOf(MUSIC, DOWNLOADS))
        model.onTaskSwiped(MUSIC, "Music")

        model.onTaskSwiped(DOWNLOADS, "Downloads")

        assertThat(model.row).isEqualTo(StillRunningModel.Row(DOWNLOADS, "Downloads"))
        assertThat(model.takeStop()).isEqualTo(DOWNLOADS)
    }

    @Test
    fun mayOffer_isTrueWhileUnknown_andOtherwiseOnlyForListedApps() {
        assertThat(model.mayOffer(MUSIC)).isTrue()

        model.onStoppableAppsChanged(setOf(MUSIC))

        assertThat(model.mayOffer(MUSIC)).isTrue()
        assertThat(model.mayOffer(WORK_MUSIC)).isFalse()
        assertThat(model.mayOffer(DOWNLOADS)).isFalse()
    }

    private companion object {
        val MUSIC = StoppableApp("com.example.music", 0)
        val WORK_MUSIC = StoppableApp("com.example.music", 10)
        val DOWNLOADS = StoppableApp("com.example.downloads", 0)
    }
}
