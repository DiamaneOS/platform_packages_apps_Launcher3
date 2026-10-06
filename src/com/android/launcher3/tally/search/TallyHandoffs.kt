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

/**
 * Home's search hands personal data and the web to the apps that hold them ("Contacts for "batt"",
 * "Files for "batt"", "Vanadium for "batt""): Home never reads contacts, files or messages, and
 * nothing leaves the phone until the user taps a web hand-off. Pure Kotlin; the Android side
 * resolves the apps ([Resolver]).
 *
 * Each hand-off goes to the user's default app for its kind (the one Android picks without asking:
 * the contacts and files apps for their app categories, the browser that opens web links), and only
 * when that app takes a search: `ACTION_SEARCH` with `SearchManager.QUERY` for contacts and files,
 * `ACTION_WEB_SEARCH` for the browser. No default (Android would ask which app), or a default that
 * takes no search, and there is no hand-off. Each is labelled with the app's own name.
 */
object TallyHandoffs {
    /** The most of the typed words a hand-off passes on. */
    const val MAX_QUERY_LENGTH = 200

    /** The kinds of hand-off, in the sheet's order. */
    enum class Kind {
        CONTACTS,
        FILES,
        WEB,
    }

    /**
     * An activity Android resolved: its app's [packageName], its [className], its app's [label].
     */
    data class Target(val packageName: String, val className: String, val label: CharSequence)

    /** A hand-off: [kind], to [target], with [query]. */
    data class Handoff(val kind: Kind, val target: Target, val query: String)

    /** What the Android side resolves for each kind. */
    interface Resolver {
        /** The default app for [kind] (null when there is none, or Android would ask). */
        fun defaultApp(kind: Kind): Target?

        /** The activity of [packageName] that takes [kind]'s search, or null. */
        fun searchActivity(kind: Kind, packageName: String): Target?
    }

    /** Android's own chooser, which a resolution falls to when there is no default. */
    private const val CHOOSER_PACKAGE = "android"

    /** The target for [kind]: the default app's search activity, or null. */
    @JvmStatic
    fun target(kind: Kind, resolver: Resolver): Target? {
        val app = resolver.defaultApp(kind) ?: return null
        if (isChooser(app)) return null
        val search = resolver.searchActivity(kind, app.packageName) ?: return null
        if (isChooser(search) || search.packageName != app.packageName) return null
        // The app's own name, as the user knows it.
        return search.copy(label = app.label)
    }

    /** Whether [target] is Android's chooser (no default app) rather than an app. */
    @JvmStatic
    fun isChooser(target: Target): Boolean =
        target.packageName == CHOOSER_PACKAGE || target.className.endsWith(".ResolverActivity")

    /** The words passed on: trimmed, single spaces, at most [MAX_QUERY_LENGTH]; null if blank. */
    @JvmStatic
    fun queryOf(text: String): String? {
        val words = text.trim().replace(Regex("\\s+"), " ")
        if (words.isEmpty()) return null
        if (words.length <= MAX_QUERY_LENGTH) return words
        // Not in the middle of a surrogate pair.
        var end = MAX_QUERY_LENGTH
        if (Character.isHighSurrogate(words[end - 1])) end--
        return words.substring(0, end).trimEnd()
    }

    /** The hand-offs for [text] to the resolved [targets], in the sheet's order. */
    @JvmStatic
    fun handoffs(text: String, targets: Map<Kind, Target>): List<Handoff> {
        val query = queryOf(text) ?: return emptyList()
        return Kind.entries.mapNotNull { kind -> targets[kind]?.let { Handoff(kind, it, query) } }
    }
}
