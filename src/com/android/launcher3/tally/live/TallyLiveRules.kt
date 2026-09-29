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

import android.app.Notification
import com.android.launcher3.tally.lamp.TallyLampState

/**
 * What makes an app live or failed on Home, read from the notifications Launcher's listener already
 * receives for its dots, and nothing else: no permission, no binding, no stored content.
 * - Failed: a notification in the error category ([Notification.CATEGORY_ERROR], "error in a
 *   background operation or authentication status"). It stays failed until the notification goes.
 * - Live (running now): a notification of a foreground service
 *   ([Notification.FLAG_FOREGROUND_SERVICE]), or an ongoing one ([Notification.FLAG_ONGOING_EVENT])
 *   outside the legacy default channel (the same exception the dots make for old apps), unless it
 *   is a system status notice ([Notification.CATEGORY_SYSTEM], for example "USB debugging
 *   connected") or minimized (a channel of minimum importance, out of the way by the app's or the
 *   user's choice).
 * - A group summary gives nothing: its children do.
 *
 * Only these flags, the category and the ranking's channel are read here. For the tallies row,
 * [TallyLiveTracker] also reads the progress bar and the chronometer the system itself draws;
 * titles and texts are never read.
 */
object TallyLiveRules {

    /** The state one notification gives its app, or null for none. */
    @JvmStatic
    fun stateOf(
        flags: Int,
        category: String?,
        onDefaultChannel: Boolean,
        minimized: Boolean,
    ): TallyLampState? {
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (category == Notification.CATEGORY_ERROR) return TallyLampState.FAILED
        if (category == Notification.CATEGORY_SYSTEM || minimized) return null
        val foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0
        return if (foregroundService || (ongoing && !onDefaultChannel)) TallyLampState.LIVE
        else null
    }

    /** How urgent a state is on Home: failed, then live, then requested, then on (lower first). */
    @JvmStatic
    fun urgency(state: TallyLampState): Int =
        when (state) {
            TallyLampState.FAILED -> 0
            TallyLampState.LIVE -> 1
            TallyLampState.REQUESTED -> 2
            TallyLampState.ON -> 3
            else -> 4
        }

    /** The more urgent of two states (the first when they are equal); null is none. */
    @JvmStatic
    fun moreUrgent(a: TallyLampState?, b: TallyLampState?): TallyLampState? =
        when {
            a == null -> b
            b == null -> a
            urgency(b) < urgency(a) -> b
            else -> a
        }
}
