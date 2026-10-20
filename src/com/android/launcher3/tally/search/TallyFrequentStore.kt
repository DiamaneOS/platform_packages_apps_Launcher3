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

package com.android.launcher3.tally.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.util.AtomicFile
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.launcher3.ConstantItem
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.tally.home.TallyHomeItem
import com.android.launcher3.tally.moments.MomentsHome
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** Also named in res/xml/tally_launcher_preferences.xml. */
private const val ENABLED_PREF_KEY = "pref_tally_search_frequent"
private const val DOCK_PREF_KEY = "pref_tally_search_frequent_dock"

/**
 * Counts what Home opens for the "Suggestions" row of Home's search, only while its Home setting is
 * on (off by default). Counted are apps, shortcuts and Settings pages opened from Home, in the
 * user's own profile and a work profile. Never the private space or a cloned profile.
 *
 * The record is one file in Launcher's credential-encrypted files, read and written on the model
 * thread only. It is in no backup (Launcher's backup takes only the files it lists) and is never
 * logged. Turning the setting off, or "Clear suggestions", deletes it.
 */
object TallyFrequentStore {
    @JvmField
    val ENABLED: ConstantItem<Boolean> = LauncherPrefs.backedUpItem(ENABLED_PREF_KEY, false)

    /** Off leaves the dock's apps out of the row. They are still counted. */
    @JvmField val DOCK: ConstantItem<Boolean> = LauncherPrefs.backedUpItem(DOCK_PREF_KEY, true)

    const val ENABLED_KEY = ENABLED_PREF_KEY
    const val DOCK_KEY = DOCK_PREF_KEY
    const val UNHIDE_KEY = "pref_tally_search_frequent_unhide"
    const val CLEAR_KEY = "pref_tally_search_frequent_clear"

    private const val FILE_NAME = "tally_frequent"
    /** More than a full record takes. A larger file is not read. */
    private const val MAX_FILE_BYTES = 256 * 1024L

    sealed class Shown(val key: TallyFrequent.Key)

    class ShownApp(key: TallyFrequent.Key, val app: AppInfo) : Shown(key)

    class ShownPage(key: TallyFrequent.Key, val page: TallySettingsSearch.Page) : Shown(key)

    /** Null until read. Model thread only, like [absent] and [listening]. */
    private var state: TallyFrequent.State? = null
    private var absent = false
    private var listening = false
    /** Called on the main thread with the number of "Don't suggest" items. */
    private val hiddenWatchers = CopyOnWriteArrayList<(Int) -> Unit>()

    /** Called for every activity Home starts. Only an app's own launch or a shortcut is counted. */
    @JvmStatic
    @MainThread
    fun onLaunched(context: Context, intent: Intent, item: ItemInfo?) {
        if (item == null) return
        val component = item.targetComponent ?: return
        val counted =
            when (item.itemType) {
                ITEM_TYPE_APPLICATION ->
                    intent.action == Intent.ACTION_MAIN && intent.component == component
                ITEM_TYPE_DEEP_SHORTCUT -> true
                else -> false
            }
        if (!counted) return
        val user = item.user
        val app = context.applicationContext
        MODEL_EXECUTOR.execute {
            if (!counts(app)) return@execute
            val profile = profileOf(app, user) ?: return@execute
            val key =
                TallyFrequent.appKey(profile, component.packageName, component.className)
                    ?: return@execute
            update(app) { TallyFrequent.record(it, key, "", today()) }
        }
    }

    @MainThread
    fun onPageOpened(context: Context, page: TallySettingsSearch.Page) {
        if (page.kind != TallySettingsSearch.KIND_PAGE) return
        val app = context.applicationContext
        MODEL_EXECUTOR.execute {
            if (!counts(app)) return@execute
            val key = TallyFrequent.pageKey(page.key) ?: return@execute
            update(app) { TallyFrequent.record(it, key, page.title, today()) }
        }
    }

    /** Gives the row's items to [callback] on the main thread. None while the setting is off. */
    @MainThread
    fun load(context: Context, callback: (List<Shown>) -> Unit) {
        val app = context.applicationContext
        LauncherAppState.getInstance(app).model.enqueueModelUpdateTask { _, data, apps ->
            val shown = if (kept(app)) find(app, apps.data, dockApps(app, data)) else emptyList()
            MAIN_EXECUTOR.execute { callback(shown) }
        }
    }

    /** "Don't suggest": drops the count of [key] and keeps its name. */
    @AnyThread
    fun dismiss(context: Context, key: TallyFrequent.Key) {
        val app = context.applicationContext
        MODEL_EXECUTOR.execute { update(app) { TallyFrequent.hide(it, key) } }
    }

    /** Takes back every "Don't suggest". */
    @AnyThread
    fun unhide(context: Context) {
        val app = context.applicationContext
        MODEL_EXECUTOR.execute { update(app) { TallyFrequent.unhide(it) } }
    }

    /** Tells [watcher] how many "Don't suggest" items there are on each change. Never a name. */
    @AnyThread
    fun watchHidden(context: Context, watcher: (Int) -> Unit) {
        hiddenWatchers.add(watcher)
        val app = context.applicationContext
        MODEL_EXECUTOR.execute {
            val count = if (kept(app)) read(app).hidden.size else 0
            MAIN_EXECUTOR.execute { if (watcher in hiddenWatchers) watcher(count) }
        }
    }

    @AnyThread
    fun unwatchHidden(watcher: (Int) -> Unit) {
        hiddenWatchers.remove(watcher)
    }

    @WorkerThread
    private fun tellHidden(count: Int) {
        if (hiddenWatchers.isEmpty()) return
        MAIN_EXECUTOR.execute { hiddenWatchers.forEach { it(count) } }
    }

    /** Deletes every count and every "Don't suggest". */
    @JvmStatic
    @AnyThread
    fun clear(context: Context) {
        val app = context.applicationContext
        MODEL_EXECUTOR.execute { delete(app) }
    }

    @JvmStatic
    @AnyThread
    fun isOn(context: Context): Boolean = LauncherPrefs.get(context).get(ENABLED)

    /** Whether the record is kept. With the setting off, a record still there is deleted. */
    @WorkerThread
    private fun kept(context: Context): Boolean {
        if (isOn(context)) return true
        delete(context)
        return false
    }

    /** Counting pauses while Home has no search slot. */
    @WorkerThread
    private fun counts(context: Context): Boolean =
        kept(context) && LauncherPrefs.get(context).get(TallyHomeItem.SEARCH.shown)

    /** Days since 1970 in the phone's time zone. The record holds no finer time. */
    private fun today(): Long = LocalDate.now().toEpochDay()

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE_NAME))

    /** Null for the private space, a clone or a profile Home does not know. */
    private fun profileOf(context: Context, user: UserHandle): Long? {
        val info = UserCache.INSTANCE.get(context).userManagerState.getCachedInfoOrNull(user)
        val icon = info?.iconInfo ?: return null
        val own = user == Process.myUserHandle() && icon.isMain
        return if (own || icon.isWork) icon.userSerial else null
    }

    /** Null when the profile is gone. */
    private fun userOf(context: Context, serial: Long): UserHandle? =
        UserCache.INSTANCE.get(context).userProfiles.firstOrNull {
            profileOf(context, it) == serial
        }

    /** The dock's own apps. Empty with "Include dock apps" on or while Moments shows its Home. */
    @WorkerThread
    private fun dockApps(context: Context, data: BgDataModel): Set<TallyFrequent.Key> {
        if (LauncherPrefs.get(context).get(DOCK) || MomentsHome.isActive()) return emptySet()
        val keys = HashSet<TallyFrequent.Key>()
        for (item in data.itemsIdMap) {
            if (item.container != CONTAINER_HOTSEAT || item.itemType != ITEM_TYPE_APPLICATION) {
                continue
            }
            val component = item.targetComponent ?: continue
            val profile = profileOf(context, item.user) ?: continue
            TallyFrequent.appKey(profile, component.packageName, component.className)
                ?.let(keys::add)
        }
        return keys
    }

    @WorkerThread
    private fun find(
        context: Context,
        all: List<AppInfo>,
        dock: Set<TallyFrequent.Key>,
    ): List<Shown> {
        val listed = HashMap<TallyFrequent.Key, AppInfo>()
        for (app in all) {
            val component = app.componentName ?: continue
            val profile = profileOf(context, app.user) ?: continue
            TallyFrequent.appKey(profile, component.packageName, component.className)?.let {
                listed[it] = app
            }
        }
        // An app that All apps does not list may only be hidden by Moments, so it is forgotten only
        // when it is gone, and never while Home's own user is unknown.
        if (!MomentsHome.isActive() && profileOf(context, Process.myUserHandle()) != null) {
            val packages = listed.keys.mapTo(HashSet()) { it.profile to it.packageName }
            update(context) { record ->
                TallyFrequent.forget(
                    record,
                    gone = { it.isApp && it !in listed && isGone(context, it, wholeApp = false) },
                    goneHidden = {
                        it.isApp &&
                            (it.profile to it.packageName) !in packages &&
                            isGone(context, it, wholeApp = true)
                    },
                )
            }
        } else {
            update(context) { it }
        }
        val record = state ?: return emptyList()
        val top =
            TallyFrequent.top(record, today(), dock) { key ->
                key.kind == TallyFrequent.Kind.PAGE || listed[key]?.isDisabled == false
            }
        return top.mapNotNull { entry ->
            val key = entry.key
            when (key.kind) {
                TallyFrequent.Kind.APP -> listed[key]?.let { ShownApp(key, it) }
                TallyFrequent.Kind.PAGE ->
                    ShownPage(key, TallySettingsSearch.Page(key.id, entry.title, null))
            }
        }
    }

    /** With [wholeApp], asks about the package, not the activity. */
    @WorkerThread
    private fun isGone(context: Context, key: TallyFrequent.Key, wholeApp: Boolean): Boolean {
        val packageName = key.packageName ?: return false
        val className = key.className ?: return false
        val user = userOf(context, key.profile) ?: return true
        return try {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
            if (wholeApp) !launcherApps.isPackageEnabled(packageName, user)
            else !launcherApps.isActivityEnabled(ComponentName(packageName, className), user)
        } catch (e: RuntimeException) {
            // A locked profile cannot be asked. The app is not known to be gone.
            false
        }
    }

    /** Applies [change] to the record as it is today, and writes it if it changed. */
    @WorkerThread
    private fun update(context: Context, change: (TallyFrequent.State) -> TallyFrequent.State) {
        if (!kept(context)) return
        val old = read(context)
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        val current = TallyFrequent.inLocale(TallyFrequent.settle(old, today()), locale)
        val next = change(current)
        if (next != old) write(context, next)
    }

    @WorkerThread
    private fun read(context: Context): TallyFrequent.State {
        state?.let {
            return it
        }
        listen(context)
        val file = file(context)
        val read =
            try {
                val base = file.baseFile
                when {
                    !base.exists() -> TallyFrequent.State()
                    base.length() > MAX_FILE_BYTES -> TallyFrequent.State()
                    else -> TallyFrequent.decode(String(file.readFully(), Charsets.UTF_8))
                }
            } catch (e: IOException) {
                // Unreadable: counting starts again. Nothing of the record is logged.
                TallyFrequent.State()
            }
        state = read
        return read
    }

    @WorkerThread
    private fun write(context: Context, next: TallyFrequent.State) {
        state = next
        val file = file(context)
        tellHidden(next.hidden.size)
        if (TallyFrequent.isEmpty(next)) {
            file.delete()
            absent = true
            return
        }
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(TallyFrequent.encode(next).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            absent = false
        } catch (e: IOException) {
            // Not written: the file stays as it was. Nothing of the record is logged.
            out?.let(file::failWrite)
        }
    }

    @WorkerThread
    private fun delete(context: Context) {
        state = null
        if (absent) return
        file(context).delete()
        absent = true
        tellHidden(0)
    }

    /** Forgets an uninstalled app at once. */
    @WorkerThread
    private fun listen(context: Context) {
        if (listening) return
        listening = true
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) {
                    val profile = profileOf(context, user) ?: return
                    update(context) { record ->
                        TallyFrequent.forget(record) {
                            it.packageName == packageName && it.profile == profile
                        }
                    }
                }

                override fun onPackageAdded(packageName: String, user: UserHandle) {}

                override fun onPackageChanged(packageName: String, user: UserHandle) {}

                override fun onPackagesAvailable(
                    packageNames: Array<String>,
                    user: UserHandle,
                    replacing: Boolean,
                ) {}

                override fun onPackagesUnavailable(
                    packageNames: Array<String>,
                    user: UserHandle,
                    replacing: Boolean,
                ) {}
            },
            MODEL_EXECUTOR.handler,
        )
    }
}
