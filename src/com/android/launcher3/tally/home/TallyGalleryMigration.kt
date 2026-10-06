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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherPrefs.Companion.nonRestorableItem
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.logging.FileLog
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.PackageManagerHelper
import java.net.URISyntaxException

/**
 * Keeps Home's gallery when the system gallery changes, as an OS update does when it moves from
 * Gallery2 to LineageOS's Glimpse, or later to GrapheneOS's new gallery. A Home, dock or folder
 * item that opens a former gallery which no longer has a launcher entry would be dropped when Home
 * loads; this points it at the current gallery instead, in the same place.
 *
 * It runs before Home reads its database. Only items the loader would otherwise drop change: a
 * former gallery that still has a launcher entry (one the user installed, say) keeps its items. It
 * records, per database (one per grid), the gallery it brought Home to, and runs again only when
 * the gallery changes, or when an item could not be moved yet because the current gallery has no
 * launcher entry in that item's profile. A migration that moves the same items to the same gallery
 * leaves this one nothing to do, and the other way round.
 */
object TallyGalleryMigration {

    private const val TAG = "TallyGalleryMigration"

    /** Galleries that DiamaneOS or GrapheneOS ship, or have shipped, as the system gallery. */
    @VisibleForTesting
    val GALLERIES =
        setOf(
            "com.android.gallery3d", // Gallery2
            "org.lineageos.glimpse", // LineageOS's Glimpse
            "app.grapheneos.gallery", // GrapheneOS's new gallery
        )

    private val GALLERY_CATEGORY =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)

    /** One Home item that opens an app: its row, the app's package and the profile's serial. */
    @VisibleForTesting data class AppItem(val id: Int, val pkg: String, val profileId: Long)

    /**
     * New intents by row for the items that move, and how many items could not move yet (the
     * current gallery has no launcher entry in their profile).
     */
    @VisibleForTesting data class Plan(val intents: Map<Int, String>, val pending: Int)

    /**
     * Brings Home to the current system gallery if it changed since the last run. Called by the
     * loader before it reads the database; a failure is logged and leaves Home as it was.
     */
    @JvmStatic
    @WorkerThread
    fun run(
        context: Context,
        db: ModelDbController,
        prefs: LauncherPrefs,
        userCache: UserCache,
        pmHelper: PackageManagerHelper,
    ) {
        try {
            val gallery =
                systemGallery(context.packageManager, configuredGallery(context)) ?: return
            var dbName: String? = null
            val items =
                db.query(
                        arrayOf(Favorites._ID, Favorites.INTENT, Favorites.PROFILE_ID),
                        "${Favorites.ITEM_TYPE} = ${Favorites.ITEM_TYPE_APPLICATION}",
                        null,
                        null,
                    )
                    .use { cursor ->
                        dbName = cursor.extras?.getString(ModelDbController.EXTRA_DB_NAME)
                        galleryItems(cursor)
                    }
            val done = nonRestorableItem("tally_gallery_moved_to@${dbName ?: "launcher.db"}", "")
            if (prefs.get(done) == gallery) return

            val users = userCache.userProfiles.associateBy { userCache.getSerialNumberForUser(it) }
            val plan =
                plan(items, gallery, users) { pkg, user -> pmHelper.getAppLaunchIntent(pkg, user) }
            var moved = 0
            db.newTransaction().use { transaction ->
                for ((id, intent) in plan.intents) {
                    moved +=
                        db.update(
                            ContentValues().apply { put(Favorites.INTENT, intent) },
                            "${Favorites._ID} = ?",
                            arrayOf(id.toString()),
                        )
                }
                transaction.commit()
            }
            val pending = plan.pending + plan.intents.size - moved
            if (pending == 0) prefs.putSync(done.to(gallery))
            FileLog.d(TAG, "Moved $moved Home items to $gallery ($pending pending)")
        } catch (e: Exception) {
            FileLog.e(TAG, "Could not bring Home to the current gallery", e)
        }
    }

    /** The package that the framework names for the system gallery role, if any. */
    private fun configuredGallery(context: Context): String? {
        val res = context.resources
        val id = res.getIdentifier("config_systemGallery", "string", "android")
        return if (id == 0) null else res.getString(id).ifEmpty { null }
    }

    /**
     * The current system gallery: the role's package ([configured]) when it is a system app with a
     * launcher entry, otherwise the only system app that answers the gallery category, as Home's
     * default layout picks the dock's gallery. Null if there is none or more than one.
     */
    @VisibleForTesting
    fun systemGallery(pm: PackageManager, configured: String?): String? {
        if (
            configured != null &&
                isSystemApp(pm, configured) &&
                pm.getLaunchIntentForPackage(configured) != null
        ) {
            return configured
        }
        return pm.queryIntentActivities(
                GALLERY_CATEGORY,
                PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
            )
            .mapTo(HashSet()) { it.activityInfo.packageName }
            .singleOrNull()
    }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean =
        try {
            pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Reads the items of [cursor] (id, intent, profile id) that open one of the [GALLERIES]. */
    @VisibleForTesting
    fun galleryItems(cursor: Cursor): List<AppItem> {
        val idIndex = cursor.getColumnIndexOrThrow(Favorites._ID)
        val intentIndex = cursor.getColumnIndexOrThrow(Favorites.INTENT)
        val profileIndex = cursor.getColumnIndexOrThrow(Favorites.PROFILE_ID)
        val items = ArrayList<AppItem>()
        while (cursor.moveToNext()) {
            val pkg = targetPackage(cursor.getString(intentIndex)) ?: continue
            if (pkg in GALLERIES) {
                items.add(AppItem(cursor.getInt(idIndex), pkg, cursor.getLong(profileIndex)))
            }
        }
        return items
    }

    private fun targetPackage(uri: String?): String? {
        if (uri == null) return null
        val intent =
            try {
                Intent.parseUri(uri, 0)
            } catch (e: URISyntaxException) {
                return null
            }
        return intent.component?.packageName ?: intent.`package`
    }

    /**
     * Plans the move to [gallery]: an item of another gallery moves when that gallery has no
     * launcher entry in the item's profile and [gallery] has one there. Items of profiles that are
     * gone ([users] has no such serial) are the loader's to drop.
     */
    @VisibleForTesting
    fun plan(
        items: List<AppItem>,
        gallery: String,
        users: Map<Long, UserHandle>,
        launchIntent: (String, UserHandle) -> Intent?,
    ): Plan {
        val galleryIntents = HashMap<UserHandle, String?>()
        val intents = LinkedHashMap<Int, String>()
        var pending = 0
        for (item in items) {
            val user = users[item.profileId] ?: continue
            if (item.pkg == gallery || launchIntent(item.pkg, user) != null) continue
            val intent =
                if (user in galleryIntents) galleryIntents[user]
                else launchIntent(gallery, user)?.toUri(0).also { galleryIntents[user] = it }
            if (intent == null) pending++ else intents[item.id] = intent
        }
        return Plan(intents, pending)
    }
}
