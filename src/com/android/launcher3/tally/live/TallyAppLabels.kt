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
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import androidx.annotation.WorkerThread
import com.android.launcher3.R
import com.android.launcher3.tally.live.TallyLiveRules.ClockKind
import com.android.launcher3.util.PackageUserKey
import java.util.EnumMap

/**
 * The names the tallies row shows: an app's launcher name as Home shows it, or its application name
 * for a package without one (a system service's notification), badged for a work profile as the
 * system badges labels; and the system Clock's names for its timer and stopwatch ([kindLabelOf]).
 * Remembered per app; call from a worker thread.
 */
class TallyAppLabels(private val context: Context) {
    private val packageManager = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val apps = HashMap<PackageUserKey, App>()

    @WorkerThread fun labelOf(app: PackageUserKey): CharSequence = appOf(app).label

    /**
     * The row's name for the system Clock's [kind] ("Timer", "Stopwatch"), badged as the app's name
     * is, or null unless [app] is the system Clock ([isSystemClock]). A tally has no icon, so any
     * other app with these names could pose as the system's timer on Home.
     */
    @WorkerThread
    fun kindLabelOf(app: PackageUserKey, kind: ClockKind): CharSequence? {
        val user = app.mUser ?: return null
        val entry = appOf(app)
        if (!entry.systemClock) return null
        return entry.kindLabels.getOrPut(kind) {
            val words =
                when (kind) {
                    ClockKind.TIMER -> R.string.tally_clock_timer
                    ClockKind.STOPWATCH -> R.string.tally_clock_stopwatch
                }
            badged(context.getString(words), user)
        }
    }

    /** Forgets every name, for example when the listener reconnects. */
    @WorkerThread fun clear() = apps.clear()

    private fun appOf(app: PackageUserKey): App = apps.getOrPut(app) { load(app) }

    private fun load(app: PackageUserKey): App {
        val user = app.mUser ?: return App(app.mPackageName, systemClock = false)
        var info: ApplicationInfo? = null
        val label: CharSequence =
            try {
                val activity = launcherApps?.getActivityList(app.mPackageName, user)?.firstOrNull()
                info =
                    activity?.applicationInfo
                        ?: launcherApps?.getApplicationInfo(app.mPackageName, 0, user)
                activity?.label
                    ?: info?.let { packageManager.getApplicationLabel(it) }
                    ?: app.mPackageName
            } catch (e: PackageManager.NameNotFoundException) {
                app.mPackageName
            } catch (e: SecurityException) {
                app.mPackageName
            }
        return App(badged(label, user), isSystemClock(app.mPackageName, info?.flags ?: 0))
    }

    private fun badged(label: CharSequence, user: UserHandle): CharSequence =
        try {
            packageManager.getUserBadgedLabel(label, user)
        } catch (e: SecurityException) {
            // A user outside this profile group: its badge is not this app's to ask for.
            label
        }

    /** An app's name and, for the system Clock, the names of its kinds of tally. */
    private class App(val label: CharSequence, val systemClock: Boolean) {
        val kindLabels = EnumMap<ClockKind, CharSequence>(ClockKind::class.java)
    }

    companion object {
        /**
         * Whether [packageName] with these ApplicationInfo [flags] is the system Clock: Clock's
         * package ([TallyLiveRules.SYSTEM_CLOCK_PACKAGE]) installed on the system image
         * ([ApplicationInfo.FLAG_SYSTEM], or [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP] for an
         * update of it, which only its signer can install). The package name alone proves nothing:
         * an app can take any name the system image does not have.
         */
        @JvmStatic
        fun isSystemClock(packageName: String?, flags: Int): Boolean =
            packageName == TallyLiveRules.SYSTEM_CLOCK_PACKAGE && flags and SYSTEM_FLAGS != 0

        private const val SYSTEM_FLAGS =
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
