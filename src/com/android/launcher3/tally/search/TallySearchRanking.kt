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

import java.text.Normalizer
import java.util.Locale

/**
 * How Home's search orders what it found, and what goes in its sections: Top, Apps (apps, then
 * their shortcuts), Settings, an answer, and the hand-offs ("Search in"). Pure Kotlin.
 *
 * A title matches by how the typed words meet it, best first: the whole title, its start, the start
 * of one of its words, the starts of several of its words (each typed word one), anywhere in it.
 * Case and accents do not count. Apps are found as All apps' search finds them (Launcher's own
 * matcher, which also reads word breaks inside names); one it finds that none of these rules meets
 * still ranks, last ([Match.OTHER]).
 */
object TallySearchRanking {
    /** The most apps, as All apps' search (DefaultAppSearchAlgorithm.MAX_RESULTS_COUNT). */
    const val MAX_APPS = 5
    /** The most shortcuts, after the apps. */
    const val MAX_SHORTCUTS = 3
    /** The most Settings pages. */
    const val MAX_SETTINGS = 5

    /** How a title matches, best first. */
    enum class Match(val score: Int) {
        EXACT(500),
        PREFIX(400),
        WORD_PREFIX(300),
        WORDS(200),
        CONTAINS(100),
        OTHER(50),
    }

    enum class Kind(val bonus: Int) {
        APP(30),
        SETTING(20),
        SHORTCUT(10),
    }

    /** A found [item] with its [title], [kind] and [match]. */
    data class Found<T>(val item: T, val title: String, val kind: Kind, val match: Match) {
        val score: Int
            get() = match.score + kind.bonus
    }

    /** The sections of the sheet, each in order; [top] is in none of the others. */
    data class Sections<T>(
        val top: Found<T>?,
        val apps: List<Found<T>>,
        val shortcuts: List<Found<T>>,
        val settings: List<Found<T>>,
        /** Whether the answer is the top result (it is, whenever there is one). */
        val answerOnTop: Boolean,
    )

    /** How [title] matches [query], or null when it does not. */
    @JvmStatic
    fun match(query: String, title: String): Match? {
        val q = fold(query)
        val t = fold(title)
        if (q.isEmpty() || t.isEmpty()) return null
        if (t == q) return Match.EXACT
        if (t.startsWith(q)) return Match.PREFIX
        val titleWords = t.split(' ')
        if (titleWords.any { it.startsWith(q) }) return Match.WORD_PREFIX
        val queryWords = q.split(' ')
        if (queryWords.size > 1 && matchesWords(queryWords, titleWords)) return Match.WORDS
        if (t.contains(q)) return Match.CONTAINS
        return null
    }

    /** Each of [queryWords] starts a different one of [titleWords], in any order. */
    private fun matchesWords(queryWords: List<String>, titleWords: List<String>): Boolean {
        val free = titleWords.toMutableList()
        for (word in queryWords.sortedByDescending { it.length }) {
            val at = free.indexOfFirst { it.startsWith(word) }
            if (at < 0) return false
            free.removeAt(at)
        }
        return true
    }

    /**
     * The sections for what was found: [apps] and [shortcuts] ranked best first (ties by title),
     * [settings] in Settings search's own order. With an answer ([hasAnswer]) it is the top result;
     * otherwise the best of the apps, shortcuts and first Settings page, which then leaves its
     * section. Each section keeps at most its limit.
     */
    @JvmStatic
    fun <T> sections(
        apps: List<Found<T>>,
        shortcuts: List<Found<T>>,
        settings: List<Found<T>>,
        hasAnswer: Boolean,
    ): Sections<T> {
        val order = compareByDescending<Found<T>> { it.score }.thenBy { fold(it.title) }
        val rankedApps = apps.sortedWith(order).take(MAX_APPS)
        val rankedShortcuts = shortcuts.sortedWith(order).take(MAX_SHORTCUTS)
        val keptSettings = settings.take(MAX_SETTINGS + 1)
        if (hasAnswer) {
            return Sections(
                null,
                rankedApps,
                rankedShortcuts,
                keptSettings.take(MAX_SETTINGS),
                answerOnTop = true,
            )
        }
        val top =
            listOfNotNull(
                    rankedApps.firstOrNull(),
                    rankedShortcuts.firstOrNull(),
                    keptSettings.firstOrNull(),
                )
                .sortedWith(order)
                .firstOrNull()
        return Sections(
            top,
            rankedApps.filter { it !== top },
            rankedShortcuts.filter { it !== top },
            keptSettings.filter { it !== top }.take(MAX_SETTINGS),
            answerOnTop = false,
        )
    }

    /** Lower case without accents, single spaces. */
    @JvmStatic
    fun fold(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
