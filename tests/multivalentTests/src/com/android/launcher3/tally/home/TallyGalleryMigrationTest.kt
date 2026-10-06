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

package com.android.launcher3.tally.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.MatrixCursor
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.tally.home.TallyGalleryMigration.AppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Home's items of a former system gallery open the current one, and nothing else changes. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyGalleryMigrationTest {

    private val owner = Process.myUserHandle()
    // Another profile: user 10 (a user's uids start at its id times 100000).
    private val work = UserHandle.getUserHandleForUid(1_000_000)
    private val users = mapOf(0L to owner, 10L to work)

    @Test
    fun readsTheItemsThatOpenAGallery() {
        val cursor =
            MatrixCursor(arrayOf(Favorites._ID, Favorites.INTENT, Favorites.PROFILE_ID)).apply {
                addRow(arrayOf<Any?>(1, launch(GALLERY2).toUri(0), 0L))
                // A package intent, as an older layout may hold.
                addRow(
                    arrayOf<Any?>(2, Intent(Intent.ACTION_MAIN).setPackage(GLIMPSE).toUri(0), 10L)
                )
                addRow(arrayOf<Any?>(3, launch(SETTINGS).toUri(0), 0L))
                addRow(arrayOf<Any?>(4, "https://example.org/", 0L))
                addRow(arrayOf<Any?>(5, null, 0L))
            }
        assertEquals(
            listOf(AppItem(1, GALLERY2, 0L), AppItem(2, GLIMPSE, 10L)),
            TallyGalleryMigration.galleryItems(cursor),
        )
    }

    @Test
    fun movesAGalleryThatLostItsLauncherEntryInEveryProfile() {
        val plan =
            TallyGalleryMigration.plan(
                listOf(AppItem(1, GALLERY2, 0L), AppItem(2, GALLERY2, 10L)),
                GLIMPSE,
                users,
                launchable(GLIMPSE to owner, GLIMPSE to work),
            )
        val glimpse = launch(GLIMPSE).toUri(0)
        assertEquals(mapOf(1 to glimpse, 2 to glimpse), plan.intents)
        assertEquals(0, plan.pending)
    }

    @Test
    fun movesTheOtherWayToo() {
        // Glimpse is gone after an update; GrapheneOS's gallery is the system gallery.
        val plan =
            TallyGalleryMigration.plan(
                listOf(AppItem(1, GLIMPSE, 0L), AppItem(2, GALLERY2, 0L)),
                GRAPHENEOS,
                users,
                launchable(GRAPHENEOS to owner),
            )
        val grapheneos = launch(GRAPHENEOS).toUri(0)
        assertEquals(mapOf(1 to grapheneos, 2 to grapheneos), plan.intents)
    }

    @Test
    fun keepsGalleriesThatStillOpenAndTheCurrentOne() {
        // A gallery the user installed keeps its item; the current gallery's item stays.
        val plan =
            TallyGalleryMigration.plan(
                listOf(AppItem(1, GRAPHENEOS, 0L), AppItem(2, GLIMPSE, 0L)),
                GLIMPSE,
                users,
                launchable(GLIMPSE to owner, GRAPHENEOS to owner),
            )
        assertEquals(emptyMap<Int, String>(), plan.intents)
        assertEquals(0, plan.pending)
    }

    @Test
    fun waitsForAProfileWithoutTheGallery() {
        val plan =
            TallyGalleryMigration.plan(
                listOf(AppItem(1, GALLERY2, 10L)),
                GLIMPSE,
                users,
                launchable(GLIMPSE to owner),
            )
        assertEquals(emptyMap<Int, String>(), plan.intents)
        assertEquals(1, plan.pending)
    }

    @Test
    fun leavesItemsOfRemovedProfilesToTheLoader() {
        val plan =
            TallyGalleryMigration.plan(
                listOf(AppItem(1, GALLERY2, 11L)),
                GLIMPSE,
                users,
                launchable(GLIMPSE to owner),
            )
        assertEquals(emptyMap<Int, String>(), plan.intents)
        assertEquals(0, plan.pending)
    }

    @Test
    fun theSystemGalleryIsTheRoleHolderOrTheOnlySystemGallery() {
        val pm = mock<PackageManager>()
        whenever(pm.getApplicationInfo(eq(GLIMPSE), any<Int>()))
            .thenReturn(ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM })
        whenever(pm.getLaunchIntentForPackage(GLIMPSE)).thenReturn(launch(GLIMPSE))
        whenever(pm.getApplicationInfo(eq(GALLERY2), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())
        whenever(pm.queryIntentActivities(any<Intent>(), any<Int>()))
            .thenReturn(listOf(resolve(GLIMPSE)))
        // The role's package when it opens.
        assertEquals(GLIMPSE, TallyGalleryMigration.systemGallery(pm, GLIMPSE))
        // Otherwise the only system app in the gallery category.
        assertEquals(GLIMPSE, TallyGalleryMigration.systemGallery(pm, GALLERY2))
        assertEquals(GLIMPSE, TallyGalleryMigration.systemGallery(pm, null))
        // Two system galleries: no choice.
        whenever(pm.queryIntentActivities(any<Intent>(), any<Int>()))
            .thenReturn(listOf(resolve(GLIMPSE), resolve(GRAPHENEOS)))
        assertNull(TallyGalleryMigration.systemGallery(pm, null))
    }

    private fun launchable(
        vararg entries: Pair<String, UserHandle>
    ): (String, UserHandle) -> Intent? {
        val set = entries.toSet()
        return { pkg, user -> if (pkg to user in set) launch(pkg) else null }
    }

    private fun launch(pkg: String) =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(pkg, "$pkg.MainActivity"))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun resolve(pkg: String) =
        ResolveInfo().apply {
            activityInfo =
                ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.MainActivity"
                }
        }

    private companion object {
        const val GALLERY2 = "com.android.gallery3d"
        const val GLIMPSE = "org.lineageos.glimpse"
        const val GRAPHENEOS = "app.grapheneos.gallery"
        const val SETTINGS = "com.android.settings"
    }
}
