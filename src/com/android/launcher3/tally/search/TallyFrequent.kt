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

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The rules for the "Suggestions" row of Home's search: what is counted, how a count fades and what
 * shows. Pure Kotlin; [TallyFrequentStore] keeps the record.
 *
 * Kept per item: an app's launcher activity and profile, or a Settings page's key and title, with a
 * count and the day of the last opening. Never typed words, a shortcut's id or a time of day.
 */
object TallyFrequent {
    const val MAX_ENTRIES = 40
    /** Past this the oldest "Don't suggest" goes. */
    const val MAX_HIDDEN = 100
    const val MAX_SHOWN = 5
    const val HALF_LIFE_DAYS = 14
    /** One opening, in thousandths so that a faded count keeps its fraction. */
    const val USE = 1000
    /** Between one and two openings, so that two openings show for five days. */
    const val SHOW_FROM = USE + USE / 2
    const val FORGET_BELOW = USE / 10
    const val MOST = 100 * USE
    /** The longest id or title kept. */
    const val MAX_TEXT = 256
    /** A Settings page has no profile. */
    const val NO_PROFILE = -1L

    enum class Kind(val code: String) {
        APP("a"),
        PAGE("p"),
    }

    /** An app ([id] is "package/class", [profile] the user's serial number) or a Settings page. */
    data class Key(val kind: Kind, val profile: Long, val id: String) {
        val isApp: Boolean
            get() = kind == Kind.APP

        val packageName: String?
            get() = if (kind == Kind.APP) id.substringBefore('/') else null

        val className: String?
            get() = if (kind == Kind.APP) id.substringAfter('/') else null
    }

    /** [points] as of [day] (days since 1970, local time). Only a page has a [title]. */
    data class Entry(val key: Key, val points: Int, val day: Long, val title: String = "")

    /**
     * [hidden] holds "Don't suggest" items, oldest first; [locale] is the page titles' language.
     */
    data class State(
        val entries: List<Entry> = emptyList(),
        val hidden: List<Key> = emptyList(),
        val locale: String = "",
    )

    /** The private space's own entry among the user's apps. */
    private const val PRIVATE_SPACE_PACKAGE = "com.android.privatespace"
    private const val HEADER = "tally-frequent 1"
    private const val LOCALE_MARK = "l"
    private const val HIDDEN_MARK = "h"
    private const val SEPARATOR = '\t'

    /** Null for what is never counted: the private space's entry, or a name that cannot be kept. */
    @JvmStatic
    fun appKey(profile: Long, packageName: String, className: String): Key? {
        if (profile < 0 || packageName == PRIVATE_SPACE_PACKAGE) return null
        if ('/' in packageName || '/' in className) return null
        val key = Key(Kind.APP, profile, "$packageName/$className")
        return if (packageName.isNotEmpty() && className.isNotEmpty() && keeps(key.id)) key
        else null
    }

    /** Null for a page of the private space, or a key that cannot be kept. */
    @JvmStatic
    fun pageKey(key: String): Key? =
        if (keeps(key) && !namesPrivateSpace(key)) Key(Kind.PAGE, NO_PROFILE, key) else null

    /**
     * Settings names its private space pages with the word "private" ("private_space_hidden",
     * "default_autofill_private"). "private_dns" is not one of them.
     */
    @JvmStatic
    fun namesPrivateSpace(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        if ("privatespace" in lower || "privateprofile" in lower) return true
        val words = lower.split(Regex("[^a-z0-9]+"))
        return words.withIndex().any { (at, word) ->
            word == "private" && words.getOrNull(at + 1) != "dns"
        }
    }

    /** An app matches by package and profile, so it stays hidden under a new launcher activity. */
    @JvmStatic fun hides(state: State, key: Key): Boolean = state.hidden.any { covers(it, key) }

    private fun covers(hidden: Key, key: Key): Boolean =
        hidden.kind == key.kind &&
            hidden.profile == key.profile &&
            if (key.kind == Kind.APP) hidden.packageName == key.packageName else hidden.id == key.id

    /** Control characters are refused, so a name can never add a line to the record. */
    private fun keeps(text: String): Boolean =
        text.isNotEmpty() && text.length <= MAX_TEXT && text.none { Character.isISOControl(it) }

    /** The count on [today]: halved for every [HALF_LIFE_DAYS] days since the entry's day. */
    @JvmStatic
    fun pointsOn(entry: Entry, today: Long): Int {
        val days = today - entry.day
        if (days <= 0) return entry.points
        // Past this, nothing is left of the highest count.
        if (days > HALF_LIFE_DAYS * 32L) return 0
        return (entry.points * 0.5.pow(days.toDouble() / HALF_LIFE_DAYS)).roundToInt()
    }

    /** Drops faded counts, and pulls back a day that is after [today] (the clock went back). */
    @JvmStatic
    fun settle(state: State, today: Long): State {
        val entries =
            state.entries.mapNotNull { entry ->
                when {
                    pointsOn(entry, today) < FORGET_BELOW -> null
                    entry.day > today -> entry.copy(day = today)
                    else -> entry
                }
            }
        return if (entries == state.entries) state else state.copy(entries = entries)
    }

    /** Counts one opening of [key]. A new item past [MAX_ENTRIES] replaces the least used. */
    @JvmStatic
    fun record(state: State, key: Key, title: String, today: Long): State {
        if (hides(state, key)) return state
        if (key.kind == Kind.PAGE && !keeps(title)) return state
        val settled = settle(state, today)
        val old = settled.entries.firstOrNull { it.key == key }
        val points = minOf(MOST, (old?.let { pointsOn(it, today) } ?: 0) + USE)
        val entry = Entry(key, points, today, if (key.kind == Kind.PAGE) title else "")
        var others = settled.entries.filter { it.key != key }
        if (others.size >= MAX_ENTRIES) {
            others = others.sortedWith(mostUsedFirst(today)).take(MAX_ENTRIES - 1)
        }
        return settled.copy(entries = (others + entry).sortedWith(byName))
    }

    /** The items to show, apps before pages. Items in [leftOut] stay counted. */
    @JvmStatic
    fun top(
        state: State,
        today: Long,
        leftOut: Set<Key> = emptySet(),
        usable: (Key) -> Boolean = { true },
    ): List<Entry> =
        state.entries
            .filter { pointsOn(it, today) >= SHOW_FROM && !hides(state, it.key) }
            .filter { it.key !in leftOut && usable(it.key) }
            .sortedWith(mostUsedFirst(today))
            .take(MAX_SHOWN)
            .sortedBy { it.key.kind }

    /** "Don't suggest": drops the count and keeps the name, so the item is not counted again. */
    @JvmStatic
    fun hide(state: State, key: Key): State =
        state.copy(
            entries = state.entries.filter { !covers(key, it.key) },
            hidden = (state.hidden.filter { !covers(key, it) } + key).takeLast(MAX_HIDDEN),
        )

    /** Takes back every "Don't suggest". The dropped counts do not come back. */
    @JvmStatic
    fun unhide(state: State): State =
        if (state.hidden.isEmpty()) state else state.copy(hidden = emptyList())

    /** Drops the counts and "Don't suggest" names of the items that are [gone]. */
    @JvmStatic fun forget(state: State, gone: (Key) -> Boolean): State = forget(state, gone, gone)

    /** A count goes with its activity; a "Don't suggest" name stays until the app itself goes. */
    @JvmStatic
    fun forget(state: State, gone: (Key) -> Boolean, goneHidden: (Key) -> Boolean): State {
        val entries = state.entries.filter { !gone(it.key) }
        val hidden = state.hidden.filter { !goneHidden(it) }
        return if (entries.size == state.entries.size && hidden.size == state.hidden.size) state
        else state.copy(entries = entries, hidden = hidden)
    }

    /** Page titles are kept in one language, so a change of language drops the pages' counts. */
    @JvmStatic
    fun inLocale(state: State, locale: String): State =
        if (state.locale == locale) state
        else
            state.copy(entries = state.entries.filter { it.key.kind != Kind.PAGE }, locale = locale)

    @JvmStatic
    fun isEmpty(state: State): Boolean = state.entries.isEmpty() && state.hidden.isEmpty()

    /** A header line, the language, then one tab-separated line per item. */
    @JvmStatic
    fun encode(state: State): String = buildString {
        append(HEADER).append('\n')
        if (keeps(state.locale))
            append(LOCALE_MARK).append(SEPARATOR).append(state.locale).append('\n')
        for (entry in state.entries) {
            val key = entry.key
            append(key.kind.code).append(SEPARATOR).append(key.profile)
            append(SEPARATOR).append(key.id)
            append(SEPARATOR).append(entry.points).append(SEPARATOR).append(entry.day)
            if (key.kind == Kind.PAGE) append(SEPARATOR).append(entry.title)
            append('\n')
        }
        for (key in state.hidden) {
            append(HIDDEN_MARK).append(SEPARATOR).append(key.kind.code)
            append(SEPARATOR).append(key.profile).append(SEPARATOR).append(key.id).append('\n')
        }
    }

    /** Skips every line that is not as [encode] writes it; without the header, all of them. */
    @JvmStatic
    fun decode(text: String): State {
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return State()
        val entries = LinkedHashMap<Key, Entry>()
        val hidden = LinkedHashSet<Key>()
        var locale = ""
        for (line in lines.drop(1)) {
            val fields = line.split(SEPARATOR)
            when (fields[0]) {
                LOCALE_MARK -> if (fields.size == 2 && keeps(fields[1])) locale = fields[1]
                HIDDEN_MARK ->
                    if (fields.size == 4) keyOf(fields[1], fields[2], fields[3])?.let(hidden::add)
                else -> entryOf(fields)?.let { entries.putIfAbsent(it.key, it) }
            }
        }
        val names = State(hidden = hidden.toList().takeLast(MAX_HIDDEN), locale = locale)
        return names.copy(
            entries =
                entries.values.filter { !hides(names, it.key) }.take(MAX_ENTRIES).sortedWith(byName)
        )
    }

    private fun entryOf(fields: List<String>): Entry? {
        if (fields.size < 5) return null
        val key = keyOf(fields[0], fields[1], fields[2]) ?: return null
        val points = fields[3].toIntOrNull()?.takeIf { it in 1..MOST } ?: return null
        val day = fields[4].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return when (key.kind) {
            Kind.APP -> if (fields.size == 5) Entry(key, points, day) else null
            Kind.PAGE ->
                if (fields.size == 6 && keeps(fields[5])) Entry(key, points, day, fields[5])
                else null
        }
    }

    /** Checks a line's key by the same rules as a new one. */
    private fun keyOf(kind: String, profile: String, id: String): Key? {
        val serial = profile.toLongOrNull() ?: return null
        return when (kind) {
            Kind.APP.code -> {
                val parts = id.split('/')
                if (parts.size == 2) appKey(serial, parts[0], parts[1]) else null
            }
            Kind.PAGE.code -> if (serial == NO_PROFILE) pageKey(id) else null
            else -> null
        }
    }

    /** The record's order: by name, so it says nothing about use. */
    private val byName: Comparator<Entry> =
        compareBy<Entry> { it.key.kind }.thenBy { it.key.profile }.thenBy { it.key.id }

    private fun mostUsedFirst(today: Long): Comparator<Entry> =
        compareByDescending<Entry> { pointsOn(it, today) }.thenByDescending { it.day }.then(byName)
}
