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

package com.android.launcher3.tally.live

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import androidx.annotation.WorkerThread
import com.android.launcher3.util.PackageUserKey

/**
 * The names the tallies row shows: an app's launcher name as Home shows it, or its application name
 * for a package without one (a system service's notification), badged for a work profile as the
 * system badges labels. Remembered per app; call from a worker thread.
 */
class TallyAppLabels(context: Context) {
    private val packageManager = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val labels = HashMap<PackageUserKey, CharSequence>()

    @WorkerThread
    fun labelOf(app: PackageUserKey): CharSequence = labels.getOrPut(app) { load(app) }

    /** Forgets every name, for example when the listener reconnects. */
    @WorkerThread fun clear() = labels.clear()

    private fun load(app: PackageUserKey): CharSequence {
        val user = app.mUser ?: return app.mPackageName
        val label: CharSequence =
            try {
                launcherApps?.getActivityList(app.mPackageName, user)?.firstOrNull()?.label
                    ?: launcherApps?.let {
                        packageManager.getApplicationLabel(
                            it.getApplicationInfo(app.mPackageName, 0, user)
                        )
                    }
                    ?: app.mPackageName
            } catch (e: PackageManager.NameNotFoundException) {
                app.mPackageName
            } catch (e: SecurityException) {
                app.mPackageName
            }
        return packageManager.getUserBadgedLabel(label, user)
    }
}
