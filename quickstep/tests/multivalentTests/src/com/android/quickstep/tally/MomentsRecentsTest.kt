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

import android.app.ActivityManager.RecentTaskInfo
import android.app.KeyguardManager
import android.companion.virtual.VirtualDeviceManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.Display.DEFAULT_DISPLAY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.automation.AutomationRepository
import com.android.launcher3.tally.moments.MomentsHome
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.android.launcher3.util.TestUtil
import com.android.quickstep.RecentTasksList
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.TopTaskTracker
import com.android.quickstep.util.GroupTask
import com.android.quickstep.util.binder.OneWayBinderList
import com.android.wm.shell.recents.IRecentTasksListener
import com.android.wm.shell.shared.GroupedTaskInfo
import com.android.wm.shell.shared.desktopmode.FakeDesktopState
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import java.util.function.Predicate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Recents while Moments is on: only the chosen apps' tasks, of Home's own user. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsRecentsTest {

    private val virtualDeviceManager = mock<VirtualDeviceManager>()
    private val context =
        mock<Context> {
            on { getSystemService(VirtualDeviceManager::class.java) } doReturn virtualDeviceManager
            on { getSystemService(KeyguardManager::class.java) } doReturn mock<KeyguardManager>()
        }
    private val sysUiProxy = mock<SystemUiProxy>()
    private val automation = mock<AutomationRepository>() // nothing is automated
    private lateinit var recents: RecentTasksList

    // This user's id (UserHandle.PER_USER_RANGE uids per user).
    private val me = Process.myUid() / 100_000

    @Before
    fun setUp() {
        whenever(virtualDeviceManager.getDeviceIdForDisplayId(any()))
            .thenReturn(Context.DEVICE_ID_DEFAULT)
        whenever(sysUiProxy.recentTasksListeners)
            .thenReturn(OneWayBinderList(IRecentTasksListener.Stub::asInterface))
        recents =
            RecentTasksList(
                context,
                MAIN_EXECUTOR,
                sysUiProxy,
                mock<TopTaskTracker>(),
                mock<DaggerSingletonTracker>(),
                automation,
                UI_HELPER_EXECUTOR,
                FakeDesktopState(),
            )
        whenever(sysUiProxy.getRecentTasks(any(), any()))
            .thenAnswer {
                arrayListOf(
                    task(1, CHOSEN, me),
                    task(2, OTHER, me),
                    task(3, CHOSEN, me + PROFILE_OFFSET),
                )
            }
    }

    @After
    fun tearDown() {
        MomentsHome.setForTest(active = false, packages = emptySet())
    }

    @Test
    fun momentsOff_showsEveryTask() {
        assertThat(loadedIds()).containsExactly(1, 2, 3)
    }

    @Test
    fun momentsOn_showsOnlyTheChosenAppsOfThisUser() {
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        assertThat(loadedIds()).containsExactly(1)
    }

    @Test
    fun momentsEnding_showsEveryTaskAgain() {
        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        MomentsHome.setForTest(active = false, packages = setOf(CHOSEN))
        assertThat(loadedIds()).containsExactly(1, 2, 3)
    }

    @Test
    fun momentsChange_dropsTheLoadedList() {
        val changeId = recents.getTasks(true, Consumer<List<GroupTask>> {}, Predicate { true })
        assertThat(recents.isTaskListValid(changeId)).isTrue()

        MomentsHome.setForTest(active = true, packages = setOf(CHOSEN))
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        assertThat(recents.isTaskListValid(changeId)).isFalse()
    }

    private fun loadedIds(): List<Int> =
        recents.loadTasksInBackground(Int.MAX_VALUE, requestId = -1, loadKeysOnly = true).flatMap {
            group ->
            group.tasks.map { it.key.id }
        }

    private fun task(id: Int, packageName: String, user: Int): GroupedTaskInfo =
        GroupedTaskInfo.forFullscreenTasks(
            RecentTaskInfo().apply {
                taskId = id
                displayId = DEFAULT_DISPLAY
                userId = user
                baseIntent = Intent().setPackage(packageName)
            }
        )

    private companion object {
        const val CHOSEN = "com.example.chosen"
        const val OTHER = "com.example.other"
        const val PROFILE_OFFSET = 10
    }
}
