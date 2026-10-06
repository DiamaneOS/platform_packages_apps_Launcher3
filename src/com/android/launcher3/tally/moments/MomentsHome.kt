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
import android.content.Context
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import com.android.launcher3.BuildConfig
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.Workspace
import com.android.launcher3.icons.cache.CacheLookupFlag.Companion.DEFAULT_LOOKUP_FLAG
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceData
import com.android.launcher3.model.data.WorkspaceData.ImmutableWorkspaceData
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.Executors.MODEL_EXECUTOR

/**
 * Home while the Moments switch is on: only the apps the user chose in Settings, on Home and in
 * All apps, for this user only (no work profile, no private space).
 *
 * SystemUI says when Moments is on (`tally_moments_home_active`) and Settings keeps the chosen
 * apps (`tally_moments_home_apps`); both are system settings no app can read. Home's own layout
 * is never touched: Moments shows a page of its own, built in memory, and while it is up Home
 * cannot be edited, so nothing is written to Home's database. When Moments ends, Home reloads
 * its layout as it was. With no apps chosen, Home stays as it is.
 */
object MomentsHome {
    private const val TAG = "TallyMomentsHome"
    private const val KEY_ACTIVE = "tally_moments_home_active"
    private const val KEY_APPS = "tally_moments_home_apps"

    // Item ids far above the database's, so a Moments item is never taken for a stored one.
    private const val ID_BASE = 1_000_000_000

    @Volatile private var active = false
    @Volatile private var packages: Set<String> = emptySet()
    @Volatile private var items: List<WorkspaceItemInfo> = emptyList()
    private var appContext: Context? = null

    /** Starts following the settings, once per process. */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    MODEL_EXECUTOR.execute { refresh(app, reload = true) }
                }
            }
        try {
            for (key in listOf(KEY_ACTIVE, KEY_APPS)) {
                app.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key),
                    false,
                    observer,
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot follow Moments", e)
            return
        }
        // The loader runs on the same thread, after this.
        MODEL_EXECUTOR.execute { refresh(app, reload = false) }
    }

    /** Whether Home shows Moments' apps only. */
    @JvmStatic fun isActive(): Boolean = active

    /** Whether All apps lists [component] of [user]. */
    @JvmStatic
    fun shouldShowApp(component: ComponentName, user: UserHandle): Boolean =
        !active || (user == Process.myUserHandle() && component.packageName in packages)

    /** Home's items to show: Moments' page while it is on, otherwise [data] unchanged. */
    @JvmStatic
    fun homeData(data: WorkspaceData): WorkspaceData {
        if (!active) return data
        val map = SparseArray<ItemInfo>()
        items.forEach { map.put(it.id, it) }
        return ImmutableWorkspaceData(data.version, data.modificationId, map)
    }

    // Model thread.
    private fun refresh(context: Context, reload: Boolean) {
        val resolver = context.contentResolver
        val (nowActive, nowPackages) =
            try {
                val apps =
                    Settings.Secure.getString(resolver, KEY_APPS)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet() ?: emptySet()
                (Settings.Secure.getInt(resolver, KEY_ACTIVE, 0) == 1 && apps.isNotEmpty()) to apps
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read Moments", e)
                false to emptySet()
            }
        if (nowActive == active && nowPackages == packages && items.isNotEmpty() == nowActive) {
            return
        }
        items = if (nowActive) buildItems(context, nowPackages) else emptyList()
        packages = nowPackages
        active = nowActive
        if (reload) LauncherAppState.getInstance(context).model.forceReload("tally-moments")
    }

    // The chosen apps by name, row by row from the first page's top.
    private fun buildItems(context: Context, chosen: Set<String>): List<WorkspaceItemInfo> {
        val user = Process.myUserHandle()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val iconCache = LauncherAppState.getInstance(context).iconCache
        val apps =
            chosen
                .mapNotNull { launcherApps.getActivityList(it, user).firstOrNull() }
                .map { activity ->
                    AppInfo(context, activity, user).also {
                        iconCache.getTitleAndIcon(it, activity, DEFAULT_LOOKUP_FLAG)
                    }
                }
                .sortedBy { it.title?.toString()?.lowercase() ?: "" }

        val idp = InvariantDeviceProfile.INSTANCE[context]
        val columns = idp.numColumns.coerceAtLeast(1)
        val firstRow = if (BuildConfig.QSB_ON_FIRST_SCREEN) 1 else 0
        val rows = (idp.numRows - firstRow).coerceAtLeast(1)
        return apps.mapIndexed { i, app ->
            app.makeWorkspaceItem(context).apply {
                id = ID_BASE + i
                container = CONTAINER_DESKTOP
                screenId = Workspace.FIRST_SCREEN_ID + i / (columns * rows)
                cellX = i % columns
                cellY = firstRow + (i / columns) % rows
                spanX = 1
                spanY = 1
            }
        }
    }
}
