/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.graphics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.FakeLauncherPrefs
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.tally.TallyColourIconThemeController
import com.android.launcher3.util.AllModulesForTest
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.FakePrefsModule
import com.android.launcher3.util.SandboxApplication
import com.android.launcher3.util.TestUtil
import com.google.common.truth.Truth.assertThat
import dagger.Component
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** ThemeManager's Tally default, beside ThemeManagerTest. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyThemeManagerTest {

    @get:Rule val context = SandboxApplication()

    lateinit var themeManager: ThemeManager

    @Before
    fun setUp() {
        context.initDaggerComponent(DaggerTallyThemeManagerComponent.builder())
        themeManager = ThemeManager.INSTANCE[context]
    }

    @Test
    fun `the Colour icon style is the default`() {
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        assertFalse(themeManager.isMonoThemeEnabled)
        assertThat(themeManager.iconState.themeController)
            .isInstanceOf(TallyColourIconThemeController::class.java)
    }
}

@LauncherAppSingleton
@Component(modules = [AllModulesForTest::class, FakePrefsModule::class])
interface TallyThemeManagerComponent : LauncherAppComponent {

    override fun getLauncherPrefs(): FakeLauncherPrefs

    @Component.Builder
    interface Builder : LauncherAppComponent.Builder {

        override fun build(): TallyThemeManagerComponent
    }
}
