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
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
import com.android.launcher3.tally.lamp.TallyLampState

/**
 * What makes an app live or failed on Home, read from the notifications Launcher's listener already
 * receives for its dots, and nothing else: no permission, no binding, no stored content.
 * - Failed: a notification in the error category ([Notification.CATEGORY_ERROR], "error in a
 *   background operation or authentication status"). It stays failed until the notification goes.
 * - Live (running now): a notification of a foreground service
 *   ([Notification.FLAG_FOREGROUND_SERVICE]), or an ongoing one ([Notification.FLAG_ONGOING_EVENT])
 *   outside the legacy default channel (the same exception the dots make for old apps), unless it
 *   is a system status notice ([Notification.CATEGORY_SYSTEM]) or minimized (a channel of minimum
 *   importance, out of the way by the app's or the user's choice).
 * - A group summary gives nothing: its children do.
 * - A notice the OS posts itself gives nothing ([postedByOs]): the system's own ("android", for
 *   example "USB debugging connected" and "Serial console enabled", which carry no category) and
 *   SystemUI's (for example screen recording, battery and storage notices). They have their own
 *   status bar icons, and no app on Home.
 *
 * Only these flags, the category, the posting package and the ranking's channel are read here. For
 * the tallies row, [TallyLiveTracker] also reads the progress bar and the chronometer the system
 * itself draws; titles and texts are never read.
 *
 * A notification the shade does not show gives nothing at all ([shownInShade]).
 */
object TallyLiveRules {
    /** Notification.FLAG_USER_INITIATED_JOB, which the SDK does not publish. */
    private const val FLAG_USER_INITIATED_JOB = 0x00008000

    /**
     * The packages that post the OS's own notices: the system itself (system_server's notices,
     * posted as "android") and SystemUI.
     */
    private val OS_PACKAGES = setOf("android", "com.android.systemui")

    /**
     * The categories Do Not Disturb's settings name, which it hides even from a foreground service
     * or a system notification (SystemUI's NotificationEntry.isNotificationBlockedByPolicy).
     */
    private val DND_POLICY_CATEGORIES =
        setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_EVENT,
            Notification.CATEGORY_REMINDER,
        )

    /**
     * Whether the notification shade shows a notification while the phone is awake, as SystemUI's
     * RankingCoordinator filters its list, and no stricter:
     * - an app the system suspends shows nothing ([suspended], Ranking.isSuspended);
     * - Do Not Disturb hides a notification it intercepts with SUPPRESSED_EFFECT_NOTIFICATION_LIST
     *   in [suppressedVisualEffects] (Ranking.getSuppressedVisualEffects, set only for an
     *   intercepted notification), unless it is exempt, as NotificationEntry's
     *   isExemptFromDndVisualSuppression decides: a call, message, alarm, event or reminder never
     *   is; otherwise a foreground service or a user-initiated job ([flags]), a [media]
     *   notification with a session, or one whose channel is not [blockable] is.
     */
    @JvmStatic
    fun shownInShade(
        suspended: Boolean,
        suppressedVisualEffects: Int,
        flags: Int,
        category: String?,
        media: Boolean,
        blockable: Boolean,
    ): Boolean {
        if (suspended) return false
        if (suppressedVisualEffects and SUPPRESSED_EFFECT_NOTIFICATION_LIST == 0) return true
        return exemptFromDnd(flags, category, media, blockable)
    }

    private fun exemptFromDnd(
        flags: Int,
        category: String?,
        media: Boolean,
        blockable: Boolean,
    ): Boolean {
        if (category in DND_POLICY_CATEGORIES) return false
        if (flags and (Notification.FLAG_FOREGROUND_SERVICE or FLAG_USER_INITIATED_JOB) != 0) {
            return true
        }
        return media || !blockable
    }

    /** Whether a notification from [packageName] is one of the OS's own notices. */
    @JvmStatic fun postedByOs(packageName: String?): Boolean = packageName in OS_PACKAGES

    /** The state one notification from [packageName] gives its app, or null for none. */
    @JvmStatic
    fun stateOf(
        packageName: String?,
        flags: Int,
        category: String?,
        onDefaultChannel: Boolean,
        minimized: Boolean,
    ): TallyLampState? {
        if (postedByOs(packageName)) return null
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
