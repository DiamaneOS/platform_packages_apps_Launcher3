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
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_SHORTCUTS
import com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.popup.PopupPopulator
import com.android.launcher3.search.StringMatcherUtility
import com.android.launcher3.shortcuts.ShortcutRequest
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR
import java.util.Locale

/**
 * What Home's search finds while its sheet is open ([TallySearchSheet]), off the main thread,
 * delivered on it for the latest words only. One session per opening; closing it drops all it found
 * (nothing is kept between openings, and nothing is logged).
 * - Apps: All apps' search's own rules on Launcher's app model ([DefaultAppSearchAlgorithm]: the
 *   same apps, work profile and private space included as there, a private space's only while
 *   unlocked, and the same matcher), all matches ranked by [TallySearchRanking].
 * - Shortcuts: the ones each app's long-press menu shows ([PopupPopulator]), read once per session
 *   through LauncherApps as the default launcher, for the apps search shows and their running,
 *   unlocked profiles.
 * - Settings pages: [TallySettingsSearch], a short pause after typing stops.
 * - Hand-off targets: [TallyHandoffs], resolved once per session.
 */
class TallySearchSession(private val context: Context, private val listener: Listener) {

    /** Receives what was found, on the main thread, for the [query] it was found for. */
    interface Listener {
        fun onAppsFound(query: String, found: AppsFound)

        fun onPagesFound(query: String, pages: List<TallySettingsSearch.Page>)

        fun onHandoffTargets(targets: Map<TallyHandoffs.Kind, TallyHandoffs.Target>)
    }

    /** A shortcut found: its launchable [info] (icon loaded), [label] and its app's title. */
    class ShortcutFound(val info: WorkspaceItemInfo, val label: String, val appTitle: String)

    /** Apps and shortcuts found, ranked best first, and what else the model gave. */
    class AppsFound(
        val apps: List<TallySearchRanking.Found<AppInfo>>,
        val shortcuts: List<TallySearchRanking.Found<ShortcutFound>>,
        /** Whether All apps' search would offer the private space entry. */
        val privateSpace: Boolean,
        /** The Settings app, for its icon on Settings pages, or null. */
        val settingsApp: AppInfo?,
    )

    /** A shortcut in the session's index (model thread only). */
    private class IndexedShortcut(val shortcut: ShortcutInfo, val label: String, val app: AppInfo)

    private val appState = LauncherAppState.getInstance(context)
    private val userCache = UserCache.INSTANCE.get(context)
    private val algorithm = DefaultAppSearchAlgorithm(context, MAIN_EXECUTOR)
    private val settingsSearch = TallySettingsSearch(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())

    /** The latest search; results of an older one are dropped (main thread). */
    private var generation = 0L
    @Volatile private var closed = false
    private var pagesSignal: CancellationSignal? = null
    private var pendingPages: Runnable? = null
    private var firstPagesQuery = true

    /** Built on the model thread on the first search, then only read there. */
    private var shortcutIndex: List<IndexedShortcut>? = null
    /** Shortcuts made launchable (icon loaded), by key (model thread only). */
    private val launchables = HashMap<String, WorkspaceItemInfo>()

    /** Resolves the hand-off targets for the session. */
    @MainThread
    fun start() {
        THREAD_POOL_EXECUTOR.execute {
            val resolver = AndroidResolver(context)
            val targets =
                TallyHandoffs.Kind.entries
                    .mapNotNull { kind -> TallyHandoffs.target(kind, resolver)?.let { kind to it } }
                    .toMap()
            MAIN_EXECUTOR.execute { if (!closed) listener.onHandoffTargets(targets) }
        }
    }

    /** Searches for [text] (trimmed); an empty one finds nothing. */
    @MainThread
    fun search(text: String) {
        val current = ++generation
        cancelPages()
        val query = text.trim()
        if (query.isEmpty()) return
        appState.model.enqueueModelUpdateTask { _, _, apps ->
            if (closed) return@enqueueModelUpdateTask
            val found = findApps(query, apps.data)
            MAIN_EXECUTOR.execute {
                if (!closed && current == generation) listener.onAppsFound(query, found)
            }
        }
        val signal = CancellationSignal()
        pagesSignal = signal
        val searchPages = Runnable {
            pendingPages = null
            // The first query that runs refreshes the index, as opening Settings search does.
            val fresh = firstPagesQuery
            firstPagesQuery = false
            THREAD_POOL_EXECUTOR.execute {
                val pages = settingsSearch.query(query, fresh, signal)
                MAIN_EXECUTOR.execute {
                    if (!closed && current == generation) listener.onPagesFound(query, pages)
                }
            }
        }
        pendingPages = searchPages
        handler.postDelayed(searchPages, PAGES_DELAY_MS)
    }

    /** The intent that opens Settings [page]. */
    fun pageIntent(page: TallySettingsSearch.Page): Intent = settingsSearch.openIntent(page)

    /** Ends the session: nothing more is delivered. */
    @MainThread
    fun close() {
        closed = true
        generation++
        cancelPages()
    }

    private fun cancelPages() {
        pendingPages?.let(handler::removeCallbacks)
        pendingPages = null
        pagesSignal?.cancel()
        pagesSignal = null
    }

    /** On the model thread: the apps and shortcuts for [query] among [all] apps. */
    @WorkerThread
    private fun findApps(query: String, all: List<AppInfo>): AppsFound {
        val searchable = all.filter { DefaultAppSearchAlgorithm.isSearchableApp(it, userCache) }
        // As DefaultAppSearchAlgorithm.getTitleMatchResult matches.
        val lower = query.lowercase(Locale.getDefault())
        val matcher = StringMatcherUtility.StringMatcher.getInstance()
        val apps =
            searchable.mapNotNull { app ->
                val title = app.title?.toString() ?: return@mapNotNull null
                if (!StringMatcherUtility.matches(lower, title, matcher)) return@mapNotNull null
                val match = TallySearchRanking.match(query, title) ?: TallySearchRanking.Match.OTHER
                TallySearchRanking.Found(app, title, TallySearchRanking.Kind.APP, match)
            }

        val index = shortcutIndex ?: indexShortcuts(searchable).also { shortcutIndex = it }
        val shortcuts =
            index
                .mapNotNull { entry ->
                    TallySearchRanking.match(query, entry.label)?.let { entry to it }
                }
                .sortedByDescending { it.second.score }
                .take(TallySearchRanking.MAX_SHORTCUTS)
                .map { (entry, match) ->
                    val appTitle = entry.app.title?.toString().orEmpty()
                    val found = ShortcutFound(launchable(entry), entry.label, appTitle)
                    TallySearchRanking.Found(
                        found,
                        entry.label,
                        TallySearchRanking.Kind.SHORTCUT,
                        match,
                    )
                }

        val me = android.os.Process.myUserHandle()
        val settingsApp =
            searchable.firstOrNull { it.targetPackage == SETTINGS_PACKAGE && it.user == me }
        return AppsFound(apps, shortcuts, algorithm.offersPrivateSpace(query), settingsApp)
    }

    /**
     * On the model thread: the shortcuts the long-press menus of [searchable] apps show, for each
     * profile that runs and is unlocked.
     */
    @WorkerThread
    private fun indexShortcuts(searchable: List<AppInfo>): List<IndexedShortcut> {
        val apps = HashMap<Pair<ComponentName, UserHandle>, AppInfo>()
        for (app in searchable) {
            val component = app.componentName ?: continue
            apps[component to app.user] = app
        }
        val index = ArrayList<IndexedShortcut>()
        for (user in userCache.userProfiles) {
            val state = userCache.userManagerState.getCachedInfoOrNull(user) ?: continue
            if (!state.isUnlocked || state.isQuietModeEnabled) continue
            val byActivity =
                ShortcutRequest(context, user)
                    .query(ShortcutRequest.PUBLISHED)
                    .filter { it.isEnabled && it.activity != null }
                    .groupBy { it.activity!! to user }
            for ((key, shortcuts) in byActivity) {
                val app = apps[key] ?: continue
                for (shortcut in PopupPopulator.sortAndFilterShortcuts(shortcuts.toMutableList())) {
                    val label = shortcut.shortLabel?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) continue
                    index += IndexedShortcut(shortcut, label, app)
                    if (index.size >= MAX_INDEXED_SHORTCUTS) return index
                }
            }
        }
        return index
    }

    /**
     * On the model thread: [entry] as a launchable item with its icon, as long-press menus make it.
     */
    @WorkerThread
    private fun launchable(entry: IndexedShortcut): WorkspaceItemInfo {
        val shortcut = entry.shortcut
        val key = "${shortcut.userHandle.hashCode()}/${shortcut.`package`}/${shortcut.id}"
        return launchables.getOrPut(key) {
            WorkspaceItemInfo(shortcut, context).apply {
                appState.iconCache.getShortcutIcon(this, shortcut)
                container = CONTAINER_SHORTCUTS
            }
        }
    }

    /** Resolves hand-off targets with the package manager (Launcher can see every package). */
    private class AndroidResolver(context: Context) : TallyHandoffs.Resolver {
        private val pm = context.packageManager

        override fun defaultApp(kind: TallyHandoffs.Kind): TallyHandoffs.Target? =
            when (kind) {
                TallyHandoffs.Kind.CONTACTS ->
                    resolve(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS), 0)
                TallyHandoffs.Kind.FILES ->
                    resolve(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES), 0)
                TallyHandoffs.Kind.WEB ->
                    resolve(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                            .addCategory(Intent.CATEGORY_BROWSABLE),
                        PackageManager.MATCH_DEFAULT_ONLY,
                    )
            }

        override fun searchActivity(
            kind: TallyHandoffs.Kind,
            packageName: String,
        ): TallyHandoffs.Target? =
            resolve(searchIntent(kind).setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY)

        @Suppress("DEPRECATION") // The int-flag form: Launcher3 also builds for API 31.
        private fun resolve(intent: Intent, flags: Int): TallyHandoffs.Target? {
            val activity = pm.resolveActivity(intent, flags)?.activityInfo ?: return null
            return TallyHandoffs.Target(
                activity.packageName,
                activity.name,
                activity.applicationInfo.loadLabel(pm),
            )
        }
    }

    companion object {
        /** The Settings app, whose icon Settings pages show. */
        private const val SETTINGS_PACKAGE = "com.android.settings"
        /** The pause after typing before Settings pages are searched. */
        private const val PAGES_DELAY_MS = 120L
        /** The most shortcuts a session reads. */
        private const val MAX_INDEXED_SHORTCUTS = 2000

        /** The intent that hands [handoff]'s words to its app. */
        @JvmStatic
        fun handoffIntent(handoff: TallyHandoffs.Handoff): Intent =
            searchIntent(handoff.kind)
                .setClassName(handoff.target.packageName, handoff.target.className)
                .putExtra(android.app.SearchManager.QUERY, handoff.query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun searchIntent(kind: TallyHandoffs.Kind): Intent =
            when (kind) {
                TallyHandoffs.Kind.CONTACTS,
                TallyHandoffs.Kind.FILES -> Intent(Intent.ACTION_SEARCH)
                TallyHandoffs.Kind.WEB -> Intent(Intent.ACTION_WEB_SEARCH)
            }
    }
}
