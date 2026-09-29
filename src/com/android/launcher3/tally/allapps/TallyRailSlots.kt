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

package com.android.launcher3.tally.allapps

/**
 * The slots of All apps' letter rail ([TallyLetterRail]), worked out from the list's sections.
 * Every slot is at least 48 dp tall, so when the sections don't fit, letters share a slot, as the
 * prototype's rail does (on the 372 × 828 dp canvas K shares G's slot and W shares V's: "the rarest
 * letters share their neighbour's slot", each one app, one row after the letter it joins). The
 * prototype names its pairs; here they are chosen from the list itself, one merge at a time:
 * 1. the rarest letter (the fewest apps),
 * 2. then the one whose first app is the fewest rows below the first app of the letter before it
 *    (0: the same row, so a tap on the shared slot shows both letters' first apps at once),
 * 3. then the smaller shared slot,
 * 4. then the later letter.
 *
 * On the prototype's apps this gives its pairs, K with G and W with V. A letter only ever joins the
 * letter before it, and the private space and the work profile's notice keep slots of their own.
 */
object TallyRailSlots {

    /** What a section of the list is. */
    enum class Kind {
        /** Apps under a letter (or another index character, such as "#"). */
        LETTER,
        /** The private space, which the list names with its badge. */
        PRIVATE,
        /** Anything else, for example the work profile's notice at the top of the work list. */
        OTHER,
    }

    /**
     * A section of the list: its [name], the [position] of its first item, the [row] of that item
     * (-1 when it is not an app), the number of [apps] in it and its [kind].
     */
    data class Section(
        val name: String,
        val position: Int,
        val row: Int,
        val apps: Int,
        val kind: Kind,
    )

    /**
     * A slot of the rail: the names of its sections in the list's order (it shows the first), where
     * its first section starts ([position], [row]), how many apps it holds, and its kind.
     */
    data class Slot(
        val names: List<String>,
        val position: Int,
        val row: Int,
        val apps: Int,
        val kind: Kind,
    )

    /**
     * The rail's slots for the list's [sections] (in list order) when [fit] slots fit on the rail.
     * Sections that follow one another under the same name share a slot (the list repeats its last
     * section at its end, and names every private space section with the same badge).
     */
    @JvmStatic
    fun slots(sections: List<Section>, fit: Int): List<Slot> {
        val slots = ArrayList<Slot>()
        for (s in sections) {
            val last = slots.lastOrNull()
            if (
                last != null &&
                    last.kind == s.kind &&
                    (s.kind == Kind.PRIVATE || last.names.last() == s.name)
            ) {
                slots[slots.size - 1] = last.copy(apps = last.apps + s.apps)
            } else {
                slots.add(Slot(listOf(s.name), s.position, s.row, s.apps, s.kind))
            }
        }
        while (slots.size > fit.coerceAtLeast(1)) {
            val i = nextMerge(slots)
            if (i < 0) break
            val before = slots[i - 1]
            val joining = slots[i]
            slots[i - 1] =
                before.copy(names = before.names + joining.names, apps = before.apps + joining.apps)
            slots.removeAt(i)
        }
        return slots
    }

    /** The slot that next joins the slot before it, or -1 when no two letters are side by side. */
    private fun nextMerge(slots: List<Slot>): Int {
        var best = -1
        var bestApps = Int.MAX_VALUE
        var bestRows = Int.MAX_VALUE
        var bestTotal = Int.MAX_VALUE
        for (i in 1 until slots.size) {
            val before = slots[i - 1]
            val joining = slots[i]
            if (before.kind != Kind.LETTER || joining.kind != Kind.LETTER) continue
            val apps = joining.apps
            val rows =
                if (before.row < 0 || joining.row < 0) Int.MAX_VALUE - 1
                else joining.row - before.row
            val total = before.apps + joining.apps
            // Rarer first, then fewer rows, then the smaller slot; ties go to the later letter.
            val better =
                when {
                    apps != bestApps -> apps < bestApps
                    rows != bestRows -> rows < bestRows
                    else -> total <= bestTotal
                }
            if (better) {
                best = i
                bestApps = apps
                bestRows = rows
                bestTotal = total
            }
        }
        return best
    }
}
