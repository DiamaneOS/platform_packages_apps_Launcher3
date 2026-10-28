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

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.util.LockedUserState
import com.android.launcher3.util.SandboxApplication
import com.android.launcher3.util.SandboxContext
import com.android.launcher3.util.rule.MockUsersRule
import com.android.launcher3.util.rule.MockUsersRule.MockUser
import com.android.users.UserType
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Where Moments starts: with Launcher's own model, and only once the user is unlocked. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsHomeStartTest {

    @get:Rule(order = 1) val context = SandboxApplication()
    @get:Rule(order = 2) val mockUser = MockUsersRule(context)

    private val executor = ListExecutor()
    private val waited = mutableListOf<Boolean>()

    @Test
    fun followsLaunchersOwnApplicationOnly() {
        val launcher =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
            }
        assertThat(MomentsHome.launcherApp(launcher)).isSameInstanceAs(launcher)

        // A preview's or the boot taskbar's sandbox: an application whose model ends with it.
        assertThat(MomentsHome.launcherApp(SandboxContext(launcher))).isNull()
        val sandboxWindow =
            object : ContextWrapper(launcher) {
                override fun getApplicationContext(): Context = context
            }
        assertThat(MomentsHome.launcherApp(sandboxWindow)).isNull()
    }

    @Test
    @MockUser(userType = UserType.MAIN, isUserUnlocked = false)
    fun locked_waitsForTheUnlock_thenReloads() {
        MomentsHome.whenUnlocked(LockedUserState.get(context), executor) { waited += it }
        assertThat(executor).isEmpty()

        mockUser.unlockMainUser()
        assertThat(executor).hasSize(1)
        executor[0].run()
        assertThat(waited).containsExactly(true)
    }

    @Test
    @MockUser(userType = UserType.MAIN, isUserUnlocked = true)
    fun unlocked_runsAtOnce() {
        MomentsHome.whenUnlocked(LockedUserState.get(context), executor) { waited += it }
        assertThat(executor).hasSize(1)
        executor[0].run()
        assertThat(waited).containsExactly(false)
    }

    private class ListExecutor : ArrayList<Runnable>(), Executor {
        override fun execute(command: Runnable) {
            add(command)
        }
    }
}
