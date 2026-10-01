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
import android.os.LocaleList
import android.os.Process
import android.os.UserHandle
import androidx.annotation.WorkerThread
import com.android.launcher3.R
import com.android.launcher3.tally.live.TallyLiveRules.ClockKind
import com.android.launcher3.util.PackageUserKey

/**
 * The names the tallies row shows: an app's launcher name as Home shows it, or its application name
 * for a package without one (a system service's notification), badged for a work profile as the
 * system badges labels; and whether an app is the system Clock, whose timer and stopwatch the row
 * names in their place ([isSystemClock], [kindLabel]). Remembered per app in the language they were
 * read in; call from a worker thread.
 */
class TallyAppLabels(private val context: Context) {
    private val packageManager = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val apps = HashMap<PackageUserKey, App>()
    /** The languages [apps] were read in. */
    private var locales: LocaleList? = null

    @WorkerThread fun labelOf(app: PackageUserKey): CharSequence = appOf(app).label

    /**
     * Whether [app] is the system Clock ([Companion.isSystemClock]), whose timer and stopwatch the
     * row names by their kind ([kindLabel]). A tally has no icon, so any other app with these names
     * could pose as the system's timer on Home.
     */
    @WorkerThread
    fun isSystemClock(app: PackageUserKey): Boolean = app.mUser != null && appOf(app).systemClock

    /** Forgets every name, for example when the listener reconnects. */
    @WorkerThread fun clear() = apps.clear()

    private fun appOf(app: PackageUserKey): App {
        // Names read in another language are forgotten: an app's name is translated too.
        val now = context.resources.configuration.locales
        if (now != locales) {
            apps.clear()
            locales = now
        }
        return apps.getOrPut(app) { load(app) }
    }

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
        badged(packageManager, label, user)

    /** An app's name, and whether it is the system Clock. */
    private class App(val label: CharSequence, val systemClock: Boolean)

    companion object {
        /**
         * The row's name for the system Clock's [kind] ("Timer", "Stopwatch") in [context]'s
         * language, badged for [user] as an app's name is. Only for an app [isSystemClock] found to
         * be the system Clock. The band reads it as it binds a tally, so it follows the language.
         */
        @JvmStatic
        fun kindLabel(context: Context, kind: ClockKind, user: UserHandle?): CharSequence {
            val words =
                context.getString(
                    when (kind) {
                        ClockKind.TIMER -> R.string.tally_clock_timer
                        ClockKind.STOPWATCH -> R.string.tally_clock_stopwatch
                    }
                )
            // Launcher's own user never has a badge: no need to ask the system.
            return if (user == null || user == Process.myUserHandle()) words
            else badged(context.packageManager, words, user)
        }

        private fun badged(
            packageManager: PackageManager,
            label: CharSequence,
            user: UserHandle,
        ): CharSequence =
            try {
                packageManager.getUserBadgedLabel(label, user)
            } catch (e: SecurityException) {
                // A user outside this profile group: its badge is not this app's to ask for.
                label
            }

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
