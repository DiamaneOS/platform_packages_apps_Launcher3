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

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.launcher3.concurrent.annotations.LightweightBackground
import com.android.launcher3.concurrent.annotations.LightweightBackgroundPriority
import com.android.launcher3.concurrent.annotations.Ui
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.SafeCloseable
import com.android.quickstep.SystemUiProxy
import com.android.systemui.shared.recents.IStoppableAppsListener
import com.android.systemui.shared.recents.model.Task
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Recents' "Still running · Stop" (Tally): after the user swipes away the card of an app that is
 * still running a foreground service, a row names the app and offers Stop.
 *
 * Launcher only shows the row. SystemUI decides which apps may be stopped (its Active apps dialog's
 * list, told through `ISystemUiProxy.setStoppableAppsListener` while Recents shows) and does the
 * stopping (`ISystemUiProxy.stopApp`), after checking everything again. Launcher needs no
 * permission for this and never stops an app itself. The app is always the swiped card's own
 * package and user; SystemUI's list is only matched against it, and neither is logged or kept
 * anywhere but in memory.
 */
@LauncherAppSingleton
class TallyStillRunning
@VisibleForTesting
constructor(
    /** Sets SystemUI's listener; closing the result removes it. */
    private val listen: (IStoppableAppsListener) -> SafeCloseable,
    /** Asks SystemUI to stop an app. */
    private val stopApp: (StoppableApp) -> Unit,
    /** The name of an app in its user, badged for a profile, or null if it is not installed. */
    private val loadLabel: (StoppableApp) -> CharSequence?,
    private val uiExecutor: Executor,
    private val backgroundExecutor: Executor,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        systemUiProxy: SystemUiProxy,
        @Ui uiExecutor: Executor,
        @LightweightBackground(LightweightBackgroundPriority.UI) backgroundExecutor: Executor,
    ) : this(
        listen = { systemUiProxy.stoppableAppsListeners.register(it) },
        stopApp = { systemUiProxy.stopApp(it.packageName, it.userId) },
        loadLabel = { loadAppLabel(context, it) },
        uiExecutor = uiExecutor,
        backgroundExecutor = backgroundExecutor,
    )

    /** Shows the row, or hides it for null. */
    fun interface RowView {
        fun showRow(row: StillRunningModel.Row?)
    }

    private val model = StillRunningModel()
    private val rowViews = ArrayList<RowView>()
    private val recentsShowing = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var registration: SafeCloseable? = null

    @VisibleForTesting
    val listener: IStoppableAppsListener.Stub =
        object : IStoppableAppsListener.Stub() {
            // A binder thread: copy what SystemUI sent, then hand it to the UI thread.
            override fun onStoppableAppsChanged(packageNames: Array<String?>?, userIds: IntArray?) {
                val apps = StoppableApp.setOf(packageNames, userIds)
                uiExecutor.execute { onStoppableApps(apps) }
            }
        }

    /** The row there is now, or null. */
    val row: StillRunningModel.Row?
        @UiThread get() = model.row

    /**
     * Recents, shown by [owner], is open for the user to act on ([showing]), or no longer is. While
     * any Recents is open, SystemUI's listener is set; opening Recents sets it again, so SystemUI
     * checks every app's policy again and tells the whole set.
     */
    @UiThread
    fun setRecentsShowing(owner: Any, showing: Boolean) {
        val changed = if (showing) recentsShowing.add(owner) else recentsShowing.remove(owner)
        if (!changed) return
        if (recentsShowing.isEmpty()) {
            registration?.close()
            registration = null
            model.onStoppableAppsChanged(null)
        } else if (registration == null) {
            // Not known until SystemUI answers the new listener.
            model.onStoppableAppsChanged(null)
            registration = listen(listener)
        }
        updateRowViews()
    }

    /** The user swiped away a card showing [tasks] (two for a split pair). */
    @UiThread
    fun onTasksRemoved(tasks: List<Task>) {
        for (task in tasks) {
            val packageName = task.key?.packageName
            if (packageName.isNullOrEmpty()) continue
            val app = StoppableApp(packageName, task.key.userId)
            if (!model.mayOffer(app)) continue
            backgroundExecutor.execute {
                val label = loadLabel(app) ?: return@execute
                uiExecutor.execute {
                    model.onTaskSwiped(app, label)
                    updateRowViews()
                }
            }
        }
    }

    /** The user tapped the row's Stop. */
    @UiThread
    fun stop() {
        val app = model.takeStop() ?: return
        stopApp(app)
        updateRowViews()
    }

    @UiThread
    fun addRowView(rowView: RowView) {
        if (rowView in rowViews) return
        rowViews.add(rowView)
        rowView.showRow(model.row)
    }

    @UiThread
    fun removeRowView(rowView: RowView) {
        rowViews.remove(rowView)
    }

    @UiThread
    private fun onStoppableApps(apps: Set<StoppableApp>) {
        // A late answer after Recents closed: it is asked for again when Recents opens.
        if (registration == null) return
        model.onStoppableAppsChanged(apps)
        updateRowViews()
    }

    @UiThread
    private fun updateRowViews() {
        val row = model.row
        rowViews.forEach { it.showRow(row) }
    }

    private companion object {
        @WorkerThread
        fun loadAppLabel(context: Context, app: StoppableApp): CharSequence? {
            val user = UserHandle.of(app.userId)
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
            val info =
                try {
                    launcherApps.getApplicationInfo(app.packageName, 0, user)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                } catch (e: SecurityException) {
                    // Not a profile of this user: its apps are not this Recents' to name.
                    null
                } ?: return null
            val packageManager = context.packageManager
            return packageManager.getUserBadgedLabel(info.loadLabel(packageManager), user)
        }
    }
}
