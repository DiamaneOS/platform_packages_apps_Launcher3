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

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.annotation.WorkerThread

/**
 * Settings pages for Home's search, from Settings search's own index: SettingsIntelligence's
 * read-only provider, which only Launcher holds the signature permission for. It returns the pages
 * Settings search shows for the same words (enabled ones only: what the device or this user cannot
 * use stays out), each with its key, title and the page it sits on; a page opens through
 * SettingsIntelligence's own activity, as a tap in Settings search opens it.
 *
 * The words go in the query's arguments (never a URI, which a permission denial would log), and
 * only to SettingsIntelligence as built into the system and signed as Launcher is: when that is not
 * so (it was disabled, and another app claims its name), Settings pages are not searched.
 */
class TallySettingsSearch(private val context: Context) {

    /** A Settings page: its index [key], [title], and the [parent] page it sits on (or null). */
    data class Page(val key: String, val title: String, val parent: String?)

    /** Whether SettingsIntelligence answers, checked once per sheet (see the class doc). */
    private val trusted: Boolean by lazy { checkTrusted() }

    /**
     * The pages for [text], at most [TallySearchRanking.MAX_SETTINGS] + 1; [fresh] on a sheet's
     * first.
     */
    @WorkerThread
    fun query(text: String, fresh: Boolean, signal: CancellationSignal): List<Page> {
        if (text.isBlank() || text.length > MAX_QUERY_LENGTH || !trusted) return emptyList()
        val args =
            Bundle().apply {
                putString(ARG_QUERY, text)
                putBoolean(ARG_FRESH, fresh)
            }
        return try {
            context.contentResolver.query(PAGES_URI, null, args, signal)?.use { cursor ->
                val key = cursor.getColumnIndex(COLUMN_KEY)
                val title = cursor.getColumnIndex(COLUMN_TITLE)
                val parent = cursor.getColumnIndex(COLUMN_PARENT)
                if (key < 0 || title < 0) return emptyList()
                buildList {
                    while (size <= TallySearchRanking.MAX_SETTINGS && cursor.moveToNext()) {
                        val pageKey = cursor.getString(key)
                        val pageTitle = cursor.getString(title)
                        if (pageKey.isNullOrEmpty() || pageTitle.isNullOrBlank()) continue
                        add(
                            Page(
                                pageKey,
                                pageTitle,
                                if (parent < 0) null else cursor.getString(parent),
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: OperationCanceledException) {
            emptyList()
        } catch (e: RuntimeException) {
            // A permission or provider failure: no pages. Never logged with the words.
            emptyList()
        }
    }

    /** The intent that opens [page] (SettingsIntelligence finds it again by its key and title). */
    fun openIntent(page: Page): Intent =
        Intent()
            .setClassName(PACKAGE, OPEN_ACTIVITY)
            .putExtra(EXTRA_KEY, page.key)
            .putExtra(EXTRA_TITLE, page.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION") // The int-flag forms: Launcher3 also builds for API 31.
    private fun checkTrusted(): Boolean {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val pm = context.packageManager
        val provider =
            pm.resolveContentProvider(AUTHORITY, PackageManager.MATCH_SYSTEM_ONLY) ?: return false
        if (provider.packageName != PACKAGE) return false
        val system = provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        return system &&
            pm.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
    }

    companion object {
        /** SettingsIntelligence, and the names of its Home search contract. */
        const val PACKAGE = "com.android.settings.intelligence"
        const val PERMISSION = "de.diamaneos.permission.QUERY_SETTINGS_SEARCH"
        const val AUTHORITY = "de.diamaneos.settingssearch"
        private val PAGES_URI: Uri = Uri.parse("content://$AUTHORITY/pages")
        private const val ARG_QUERY = "de.diamaneos.settingssearch.QUERY"
        private const val ARG_FRESH = "de.diamaneos.settingssearch.FRESH"
        private const val COLUMN_KEY = "key"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_PARENT = "parent"
        private const val OPEN_ACTIVITY = "$PACKAGE.search.TallyHomeSearchActivity"
        private const val EXTRA_KEY = "de.diamaneos.settingssearch.extra.KEY"
        private const val EXTRA_TITLE = "de.diamaneos.settingssearch.extra.TITLE"
        /** As SettingsIntelligence's limit (TallyHomeSearchContract.MAX_QUERY_LENGTH). */
        const val MAX_QUERY_LENGTH = 100
    }
}
