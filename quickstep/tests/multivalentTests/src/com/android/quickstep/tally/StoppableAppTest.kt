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

/** Reading SystemUI's list of stoppable apps from `IStoppableAppsListener`'s arrays. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class StoppableAppTest {

    @Test
    fun pairsPackageAndUserByIndex() {
        val apps =
            StoppableApp.setOf(
                arrayOf("com.example.music", "com.example.music", "com.example.downloads"),
                intArrayOf(0, 10, 0),
            )

        assertThat(apps)
            .containsExactly(
                StoppableApp("com.example.music", 0),
                StoppableApp("com.example.music", 10),
                StoppableApp("com.example.downloads", 0),
            )
    }

    @Test
    fun arraysThatDoNotPairUp_giveNothing() {
        assertThat(StoppableApp.setOf(arrayOf("com.example.music"), intArrayOf(0, 10))).isEmpty()
        assertThat(StoppableApp.setOf(arrayOf("com.example.music", "a.b"), intArrayOf(0))).isEmpty()
        assertThat(StoppableApp.setOf(null, intArrayOf(0))).isEmpty()
        assertThat(StoppableApp.setOf(arrayOf("com.example.music"), null)).isEmpty()
    }

    @Test
    fun entriesWithoutAPackageName_areLeftOut() {
        val apps = StoppableApp.setOf(arrayOf(null, "", "com.example.music"), intArrayOf(0, 0, 0))

        assertThat(apps).containsExactly(StoppableApp("com.example.music", 0))
    }

    @Test
    fun tooManyApps_giveNothing() {
        val count = StoppableApp.MAX_APPS + 1
        val apps = StoppableApp.setOf(Array(count) { "com.example.app$it" }, IntArray(count) { 0 })

        assertThat(apps).isEmpty()
    }

    @Test
    fun emptySet_isNothing() {
        assertThat(StoppableApp.setOf(emptyArray(), IntArray(0))).isEmpty()
    }
}
