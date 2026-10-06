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
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Times in other places for Home's search, worked out on the phone from the time-zone data the
 * system has: "time in Tokyo", "Tokyo time", "15:30 in New York" (the time here, there), "9am
 * London to Sydney". Places are the cities the time-zone database names (in English), a few common
 * abbreviations (UTC, GMT, CET, PST, ...) and UTC offsets ("UTC+2"). A time needs its minutes or
 * am/pm ("9" alone is a number).
 */
object TallyZoneTimes {
    /** The longest query read. */
    const val MAX_LENGTH = 80

    /** A place: its [zone] and the [name] shown. */
    data class Place(val zone: ZoneId, val name: String)

    /** [time] in [place]; [from] is where an asked time was (null for now). */
    data class ZoneTime(val time: ZonedDateTime, val place: Place, val from: Place?)

    private val REGIONS =
        setOf(
            "Africa",
            "America",
            "Antarctica",
            "Asia",
            "Atlantic",
            "Australia",
            "Europe",
            "Indian",
            "Pacific",
        )

    /** Abbreviations read as these places (a summer abbreviation reads as the same place). */
    private val ABBREVIATIONS =
        mapOf(
            "utc" to "UTC",
            "gmt" to "GMT",
            "cet" to "Europe/Paris",
            "cest" to "Europe/Paris",
            "eet" to "Europe/Athens",
            "eest" to "Europe/Athens",
            "wet" to "Europe/Lisbon",
            "west" to "Europe/Lisbon",
            "bst" to "Europe/London",
            "msk" to "Europe/Moscow",
            "ist" to "Asia/Kolkata",
            "jst" to "Asia/Tokyo",
            "kst" to "Asia/Seoul",
            "hkt" to "Asia/Hong_Kong",
            "sgt" to "Asia/Singapore",
            "aest" to "Australia/Sydney",
            "aedt" to "Australia/Sydney",
            "nzst" to "Pacific/Auckland",
            "nzdt" to "Pacific/Auckland",
            "est" to "America/New_York",
            "edt" to "America/New_York",
            "cst" to "America/Chicago",
            "cdt" to "America/Chicago",
            "mst" to "America/Denver",
            "mdt" to "America/Denver",
            "pst" to "America/Los_Angeles",
            "pdt" to "America/Los_Angeles",
            "akst" to "America/Anchorage",
            "hst" to "Pacific/Honolulu",
        )

    /** Cities of the time-zone database by their folded names, built on first use. */
    private val cities: Map<String, Place> by lazy {
        val map = HashMap<String, Place>()
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            val parts = id.split('/')
            if (parts.size < 2 || parts[0] !in REGIONS) continue
            val name = parts.last().replace('_', ' ')
            val key = fold(name)
            // The shorter id is the canonical one (America/Indianapolis over its Indiana alias).
            val known = map[key]
            if (known == null || id.length < known.zone.id.length) {
                map[key] = Place(ZoneId.of(id), name)
            }
        }
        map
    }

    private val OFFSET = Regex("(utc|gmt)\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?")
    private val TIME = Regex("(\\d{1,2})(?:[:h.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?=\\s|$)")
    private val CONNECTORS = setOf("in", "to", "=", "->", "→")

    /** The place [name] is, or null. */
    @JvmStatic
    fun place(name: String): Place? {
        val key = fold(name)
        if (key.isEmpty()) return null
        ABBREVIATIONS[key]?.let { id ->
            return Place(ZoneId.of(id), key.uppercase(Locale.ROOT))
        }
        OFFSET.matchEntire(key)?.let { m ->
            val hours = m.groupValues[3].toInt()
            val minutes = m.groupValues[4].ifEmpty { "0" }.toInt()
            if (hours > 18 || minutes > 59) return null
            val sign = if (m.groupValues[2] == "-") -1 else 1
            return try {
                val offset = ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
                Place(offset, m.groupValues[1].uppercase(Locale.ROOT) + offset.id)
            } catch (e: DateTimeException) {
                null
            }
        }
        return cities[key]
    }

    /**
     * The time [text] asks for, at [now] here, or null. "time in X", "X time" and "now in X" ask
     * for the time there now; "T in X" for the time there when it is T here; "T X in Y" (or "T in X
     * to Y") for the time in Y when it is T in X.
     */
    @JvmStatic
    fun answer(text: String, now: ZonedDateTime): ZoneTime? {
        if (text.length > MAX_LENGTH) return null
        val query =
            text
                .trim()
                .lowercase(Locale.ROOT)
                .replace("→", " → ")
                .replace("->", " -> ")
                .replace(Regex("\\s+"), " ")
        if (query.isEmpty()) return null
        nowIn(query)?.let { place ->
            return ZoneTime(now.withZoneSameInstant(place.zone), place, null)
        }
        // The last connecting word whose right side is a place.
        val words = query.split(' ')
        for (i in words.size - 2 downTo 1) {
            if (words[i] !in CONNECTORS) continue
            val to = place(words.subList(i + 1, words.size).joinToString(" ")) ?: continue
            val asked = askedTime(words.subList(0, i).joinToString(" "), now) ?: continue
            return ZoneTime(asked.first.withZoneSameInstant(to.zone), to, asked.second)
        }
        return null
    }

    /** The place of "time in X", "X time" or "now in X", or null. */
    private fun nowIn(query: String): Place? {
        for (prefix in listOf("current time in ", "time in ", "time at ", "now in ")) {
            if (query.startsWith(prefix)) return place(query.removePrefix(prefix))
        }
        if (query.endsWith(" time")) return place(query.removeSuffix(" time"))
        return null
    }

    /**
     * "15:30", "9am", "9:30 pm London", "10:00 in Tokyo": that time today where it is (here when no
     * place is named), with the place named (null for here); null if it is not a time.
     */
    private fun askedTime(text: String, now: ZonedDateTime): Pair<ZonedDateTime, Place?>? {
        val match = TIME.find(text) ?: return null
        if (match.range.first != 0) return null
        val hourText = match.groupValues[1]
        val minuteText = match.groupValues[2]
        val half = match.groupValues[3].replace(".", "")
        // A bare number is not a time.
        if (minuteText.isEmpty() && half.isEmpty()) return null
        var hour = hourText.toInt()
        val minute = minuteText.ifEmpty { "0" }.toInt()
        if (minute > 59) return null
        if (half.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (half == "pm") 12 else 0
        } else if (hour > 23) {
            return null
        }
        var rest = text.substring(match.range.last + 1).trim()
        for (word in listOf("in ", "at ")) rest = rest.removePrefix(word)
        val from = if (rest.isEmpty()) null else place(rest) ?: return null
        val zone = from?.zone ?: now.zone
        val day = now.withZoneSameInstant(zone).toLocalDate()
        return ZonedDateTime.of(day, LocalTime.of(hour, minute), zone) to from
    }

    /** Lower case, without accents, single spaces: "São Paulo" is "sao paulo". */
    private fun fold(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
