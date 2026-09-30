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

import com.android.launcher3.tally.lamp.TallyLampState
import com.android.launcher3.util.PackageUserKey

/**
 * One live or failed thing on Home, reduced from a notification to what Home shows: its app, the
 * lamp state ([TallyLiveRules]) and, for the tallies row, a readout that the system itself would
 * draw as a bar or a clock. Never the notification's title, text or any other content.
 */
data class TallyLiveItem(
    /** The notification's key, only to replace or remove this item. */
    val key: String,
    /** The app, as the notification's package and user. */
    val app: PackageUserKey,
    /** [TallyLampState.LIVE] or [TallyLampState.FAILED]. */
    val state: TallyLampState,
    /** Whether the app and channel may show a dot, which the LED follows. */
    val showsLed: Boolean,
    /** Whether it may show in the tallies row (not for the private space). */
    val showsInRow: Boolean,
    /** The app's name for the row, badged for a work profile. */
    val label: CharSequence,
    /** A progress bar's fraction in thousandths, or [NO_PROGRESS]. */
    val progressPermille: Int = NO_PROGRESS,
    /**
     * The `SystemClock.elapsedRealtime()` at which a chronometer reads 0:00 (it counts up from
     * there, or down to there with [countDown]), or [NO_CHRONOMETER].
     */
    val chronometerBase: Long = NO_CHRONOMETER,
    val countDown: Boolean = false,
    /** When this became live or failed (elapsed realtime): the row shows newer things first. */
    val sinceRealtime: Long = 0L,
) {
    companion object {
        const val NO_PROGRESS = -1
        const val NO_CHRONOMETER = Long.MIN_VALUE
    }
}
