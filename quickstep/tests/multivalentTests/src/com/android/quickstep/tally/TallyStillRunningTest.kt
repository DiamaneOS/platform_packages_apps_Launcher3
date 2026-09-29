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

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.util.SafeCloseable
import com.android.systemui.shared.recents.IStoppableAppsListener
import com.android.systemui.shared.recents.model.Task
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recents' "Still running · Stop": SystemUI's listener is set only while Recents shows, the row
 * names the swiped card's own package and user, and Stop goes to SystemUI only for the row's app.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyStillRunningTest {

    private val uiQueue = ArrayDeque<Runnable>()
    private val backgroundQueue = ArrayDeque<Runnable>()
    private val listened = mutableListOf<IStoppableAppsListener>()
    private var removedListeners = 0
    private val stopped = mutableListOf<StoppableApp>()
    private val labelsLookedUp = mutableListOf<StoppableApp>()

    private val underTest =
        TallyStillRunning(
            listen = {
                listened += it
                SafeCloseable { removedListeners++ }
            },
            stopApp = { stopped += it },
            loadLabel = {
                labelsLookedUp += it
                LABELS[it]
            },
            uiExecutor = Executor { uiQueue.addLast(it) },
            backgroundExecutor = Executor { backgroundQueue.addLast(it) },
        )

    private val recents = Any()

    @Test
    fun listensOnlyWhileRecentsShows() {
        val otherRecents = Any()

        underTest.setRecentsShowing(recents, true)
        underTest.setRecentsShowing(recents, true)
        underTest.setRecentsShowing(otherRecents, true)
        assertThat(listened).hasSize(1)

        underTest.setRecentsShowing(recents, false)
        assertThat(removedListeners).isEqualTo(0)

        underTest.setRecentsShowing(otherRecents, false)
        assertThat(removedListeners).isEqualTo(1)
    }

    @Test
    fun setsTheListenerAgainEachTimeRecentsOpens() {
        underTest.setRecentsShowing(recents, true)
        underTest.setRecentsShowing(recents, false)
        underTest.setRecentsShowing(recents, true)

        assertThat(listened).containsExactly(underTest.listener, underTest.listener)
        assertThat(removedListeners).isEqualTo(1)
    }

    @Test
    fun swipedCardOfAListedApp_showsItsRow() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC)

        underTest.onTasksRemoved(listOf(task(MUSIC)))
        runAll()

        assertThat(underTest.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun whatSystemUiSays_isReadOnTheUiThread() {
        underTest.setRecentsShowing(recents, true)
        underTest.onTasksRemoved(listOf(task(MUSIC)))
        runAll()

        // Sent on a binder thread: nothing changes until the UI thread runs it.
        sendFromSystemUi(MUSIC)
        assertThat(underTest.row).isNull()

        runAll()
        assertThat(underTest.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun swipedCard_isNamedByItsOwnPackageAndUser() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC, WORK_MUSIC)

        underTest.onTasksRemoved(listOf(task(WORK_MUSIC)))
        runAll()

        assertThat(labelsLookedUp).containsExactly(WORK_MUSIC)
        assertThat(underTest.row).isEqualTo(StillRunningModel.Row(WORK_MUSIC, "Work Music"))
    }

    @Test
    fun swipedCardOfAnAppNotListed_showsNoRow_andIsNotLookedUp() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(DOWNLOADS, WORK_MUSIC)

        underTest.onTasksRemoved(listOf(task(MUSIC)))
        runAll()

        assertThat(labelsLookedUp).isEmpty()
        assertThat(underTest.row).isNull()
    }

    @Test
    fun taskWithoutAPackage_isIgnored() {
        underTest.setRecentsShowing(recents, true)
        val noPackage = Task(Task.TaskKey(7, 1, Intent(), null, 0, 0L))

        underTest.onTasksRemoved(listOf(noPackage))
        runAll()

        assertThat(labelsLookedUp).isEmpty()
        assertThat(underTest.row).isNull()
    }

    @Test
    fun appNoLongerInstalled_showsNoRow() {
        val gone = StoppableApp("com.example.gone", 0)
        underTest.setRecentsShowing(recents, true)
        systemUiLists(gone)

        underTest.onTasksRemoved(listOf(task(gone)))
        runAll()

        assertThat(underTest.row).isNull()
    }

    @Test
    fun splitPair_showsTheRowForTheListedApp() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC)

        underTest.onTasksRemoved(listOf(task(DOWNLOADS), task(MUSIC)))
        runAll()

        assertThat(underTest.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun lateListAfterRecentsClosed_isIgnored() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC)
        underTest.onTasksRemoved(listOf(task(MUSIC)))
        runAll()

        sendFromSystemUi(MUSIC)
        underTest.setRecentsShowing(recents, false)
        runAll()
        assertThat(underTest.row).isNull()

        // Open again: still nothing until SystemUI answers the new listener.
        underTest.setRecentsShowing(recents, true)
        assertThat(underTest.row).isNull()
        systemUiLists(MUSIC)
        assertThat(underTest.row).isEqualTo(StillRunningModel.Row(MUSIC, "Music"))
    }

    @Test
    fun stop_asksSystemUiToStopTheRowsApp() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC, WORK_MUSIC)
        underTest.onTasksRemoved(listOf(task(WORK_MUSIC)))
        runAll()

        underTest.stop()

        assertThat(stopped).containsExactly(WORK_MUSIC)
        assertThat(underTest.row).isNull()
    }

    @Test
    fun stop_withoutARow_asksNothing() {
        underTest.stop()
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC)
        underTest.stop()

        assertThat(stopped).isEmpty()
    }

    @Test
    fun malformedListFromSystemUi_offersNothing() {
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC)
        underTest.onTasksRemoved(listOf(task(MUSIC)))
        runAll()

        underTest.listener.onStoppableAppsChanged(arrayOf("com.example.music"), intArrayOf(0, 10))
        runAll()

        assertThat(underTest.row).isNull()
    }

    @Test
    fun rowViews_followTheRow() {
        val shown = mutableListOf<StillRunningModel.Row?>()
        val view = TallyStillRunning.RowView { shown += it }
        underTest.addRowView(view)
        underTest.setRecentsShowing(recents, true)
        systemUiLists(MUSIC)
        underTest.onTasksRemoved(listOf(task(MUSIC)))
        runAll()
        underTest.stop()
        underTest.removeRowView(view)
        underTest.setRecentsShowing(recents, false)

        assertThat(shown.first()).isNull()
        assertThat(shown).contains(StillRunningModel.Row(MUSIC, "Music"))
        assertThat(shown.last()).isNull()
        assertThat(shown.size).isLessThan(8)
    }

    private fun systemUiLists(vararg apps: StoppableApp) {
        sendFromSystemUi(*apps)
        runAll()
    }

    private fun sendFromSystemUi(vararg apps: StoppableApp) {
        underTest.listener.onStoppableAppsChanged(
            apps.map { it.packageName }.toTypedArray(),
            apps.map { it.userId }.toIntArray(),
        )
    }

    private fun runAll() {
        while (backgroundQueue.isNotEmpty() || uiQueue.isNotEmpty()) {
            backgroundQueue.removeFirstOrNull()?.run()
            uiQueue.removeFirstOrNull()?.run()
        }
    }

    private fun task(app: StoppableApp): Task {
        val intent =
            Intent().setComponent(ComponentName(app.packageName, "${app.packageName}.Main"))
        return Task(Task.TaskKey(++taskId, 1, intent, null, app.userId, 0L))
    }

    private var taskId = 0

    private companion object {
        val MUSIC = StoppableApp("com.example.music", 0)
        val WORK_MUSIC = StoppableApp("com.example.music", 10)
        val DOWNLOADS = StoppableApp("com.example.downloads", 0)
        val LABELS = mapOf(MUSIC to "Music", WORK_MUSIC to "Work Music", DOWNLOADS to "Downloads")
    }
}
