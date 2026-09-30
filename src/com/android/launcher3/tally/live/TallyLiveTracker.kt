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
import android.app.NotificationChannel
import android.media.session.MediaSession
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.annotation.WorkerThread
import com.android.launcher3.util.PackageUserKey

/**
 * Keeps [TallyLiveRepository] in step with the notifications Launcher's listener receives. It runs
 * on the listener's worker thread and keeps, per notification key, only a [TallyLiveItem]: never
 * the notification itself. Each call returns the apps whose LED changed.
 *
 * A notification the shade does not show ([TallyLiveRules.shownInShade]) gives nothing, neither an
 * LED nor a place in the row.
 *
 * @param labelOf the app's name for the tallies row (badged for a work profile)
 * @param profileOf which kind of profile a user is ([Profile]), which decides what the row shows
 * @param realtime the clock the row's order and chronometers use
 * @param wallTime the clock notifications' `when` uses
 */
class TallyLiveTracker
@JvmOverloads
constructor(
    private val repository: TallyLiveRepository,
    private val labelOf: (PackageUserKey) -> CharSequence,
    private val profileOf: (PackageUserKey) -> Profile,
    private val realtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallTime: () -> Long = System::currentTimeMillis,
) {
    private val items = HashMap<String, TallyLiveItem>()

    /** A notification was posted or updated. */
    @WorkerThread
    fun onPosted(input: Input): Set<PackageUserKey> {
        val item = itemFor(input)
        val previous = if (item == null) items.remove(input.key) else items.put(input.key, item)
        return if (previous == item) emptySet() else repository.publish(items.values)
    }

    /** A notification went. */
    @WorkerThread
    fun onRemoved(key: String): Set<PackageUserKey> =
        if (items.remove(key) == null) emptySet() else repository.publish(items.values)

    /** Every notification now (on connecting, disconnecting and ranking changes). */
    @WorkerThread
    fun onAll(inputs: List<Input>): Set<PackageUserKey> {
        val old = HashMap(items)
        items.clear()
        for (input in inputs) {
            // Keep when each thing became live or failed, so the row's order holds.
            itemFor(input, old[input.key])?.let { items[input.key] = it }
        }
        return repository.publish(items.values)
    }

    private fun itemFor(input: Input, previous: TallyLiveItem? = items[input.key]): TallyLiveItem? {
        if (
            !TallyLiveRules.shownInShade(
                input.suspended,
                input.suppressedVisualEffects,
                input.flags,
                input.category,
                input.media,
                input.blockable,
            )
        ) {
            return null
        }
        val state =
            TallyLiveRules.stateOf(
                input.app.mPackageName,
                input.flags,
                input.category,
                input.onDefaultChannel,
                input.minimized,
                TallyLiveRules.showsLiveActivity(
                    input.flags,
                    input.category,
                    // A running MetricStyle time is the system's chronometer too.
                    input.showsChronometer || input.metricTime?.running == true,
                    input.progressMax,
                    input.progressIndeterminate,
                    input.callStyle,
                    input.media,
                ),
            ) ?: return null
        val now = realtime()
        val since = if (previous != null && previous.state == state) previous.sinceRealtime else now
        val profile = profileOf(input.app)
        // A work profile may be locked, and then the shade shows only its notifications' public
        // versions: Launcher cannot tell, so the row shows no work readout.
        val readouts = profile == Profile.PERSONAL
        val chronometerBase =
            if (readouts && input.showsChronometer && input.whenMillis > 0L) {
                input.whenMillis - wallTime() + now
            } else {
                TallyLiveItem.NO_CHRONOMETER
            }
        val progress =
            if (readouts && input.progressMax > 0 && !input.progressIndeterminate) {
                (input.progress.coerceIn(0, input.progressMax).toLong() * 1000 / input.progressMax)
                    .toInt()
            } else {
                TallyLiveItem.NO_PROGRESS
            }
        // A MetricStyle's time (Clock's timers and stopwatch) comes before the chronometer, as in
        // the status bar chip (Notification.resolveCompactContent): running, the row counts it;
        // paused, the row shows it still.
        val metric = if (readouts) input.metricTime else null
        val metricBase = metric?.chronometerBase(now, wallTime()) ?: TallyLiveItem.NO_CHRONOMETER
        val pausedSeconds = metric?.pausedSeconds ?: TallyLiveItem.NO_PAUSED_TIME
        val metricShown =
            metricBase != TallyLiveItem.NO_CHRONOMETER ||
                pausedSeconds != TallyLiveItem.NO_PAUSED_TIME
        return TallyLiveItem(
            key = input.key,
            app = input.app,
            state = state,
            showsLed = input.canShowBadge,
            showsInRow = profile != Profile.PRIVATE,
            label = previous?.label ?: labelOf(input.app),
            progressPermille = progress,
            chronometerBase = if (metricShown) metricBase else chronometerBase,
            countDown =
                if (metricShown) metric?.countDown == true
                else readouts && input.chronometerCountDown,
            sinceRealtime = since,
            pausedSeconds = pausedSeconds,
        )
    }

    /** What the row shows of a user's things. */
    enum class Profile {
        /** The main user, a clone profile or another: the name, the state and any readout. */
        PERSONAL,
        /** The work profile: the name and the state, never a readout (progress, chronometer). */
        WORK,
        /** The private space: nothing (its keys still light, inside the unlocked space). */
        PRIVATE;

        companion object {
            /** The profile of a user of this [work] or [private] type (UserCache's user info). */
            @JvmStatic
            fun of(work: Boolean, private: Boolean): Profile =
                when {
                    private -> PRIVATE
                    work -> WORK
                    else -> PERSONAL
                }
        }
    }

    /**
     * What the tracker reads from one notification and its ranking: flags, category, channel and
     * importance, whether the shade shows it, whether it is a call or media with a session (its
     * template), and the progress bar, chronometer and MetricStyle time the system draws. Nothing
     * else: no title, text or custom view.
     */
    data class Input(
        val key: String,
        val app: PackageUserKey,
        val flags: Int,
        val category: String?,
        val onDefaultChannel: Boolean,
        val minimized: Boolean,
        val canShowBadge: Boolean,
        val progress: Int = 0,
        val progressMax: Int = 0,
        val progressIndeterminate: Boolean = false,
        val showsChronometer: Boolean = false,
        val whenMillis: Long = 0L,
        val chronometerCountDown: Boolean = false,
        /** The system suspends the app (Ranking.isSuspended). */
        val suspended: Boolean = false,
        /** What Do Not Disturb suppresses of it (Ranking.getSuppressedVisualEffects). */
        val suppressedVisualEffects: Int = 0,
        /** A media notification with a session, as Notification.isMediaNotification decides. */
        val media: Boolean = false,
        /** Whether its channel is blockable, as SystemUI's NotificationEntry decides. */
        val blockable: Boolean = true,
        /** A call (Notification.CallStyle), incoming, ongoing or screening. */
        val callStyle: Boolean = false,
        /** A Notification.MetricStyle's time: a timer or a stopwatch, running or paused. */
        val metricTime: TallyMetricTime? = null,
    ) {
        companion object {
            private val MEDIA_TEMPLATES =
                setOf(
                    Notification.MediaStyle::class.java.name,
                    Notification.DecoratedMediaCustomViewStyle::class.java.name,
                )

            /**
             * Reads [sbn] with its [ranking] (null when the ranking has no entry for it).
             * [lockedByCriticalDeviceFunction] tells whether the system locks a channel's
             * importance for a critical device function (only Quickstep can read it).
             */
            @JvmStatic
            fun from(
                sbn: StatusBarNotification,
                ranking: Ranking?,
                lockedByCriticalDeviceFunction: (NotificationChannel) -> Boolean,
            ): Input {
                val n = sbn.notification
                val extras = n.extras
                val template = extras.getString(Notification.EXTRA_TEMPLATE)
                val channel = ranking?.channel
                // A notification posted for every user (UserHandle.ALL, a system notification) is
                // shown to this user, and belongs to this user here: user -1 is no profile, and
                // asking the platform about it throws.
                val user = if (sbn.user == UserHandle.ALL) Process.myUserHandle() else sbn.user
                return Input(
                    key = sbn.key,
                    app = PackageUserKey(sbn.packageName, user),
                    flags = n.flags,
                    category = n.category,
                    onDefaultChannel = channel?.id == NotificationChannel.DEFAULT_CHANNEL_ID,
                    minimized = ranking?.isAmbient ?: false,
                    canShowBadge = ranking?.canShowBadge() ?: false,
                    suspended = ranking?.isSuspended ?: false,
                    suppressedVisualEffects = ranking?.suppressedVisualEffects ?: 0,
                    media =
                        template in MEDIA_TEMPLATES &&
                            extras.getParcelable(
                                Notification.EXTRA_MEDIA_SESSION,
                                MediaSession.Token::class.java,
                            ) != null,
                    // As SystemUI: no channel, or one locked for a critical device function that
                    // is not always blockable, is not blockable.
                    blockable =
                        channel != null &&
                            !(lockedByCriticalDeviceFunction(channel) && !channel.isBlockable),
                    progress = extras.getInt(Notification.EXTRA_PROGRESS),
                    progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX),
                    progressIndeterminate =
                        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
                    showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
                    whenMillis = n.`when`,
                    chronometerCountDown =
                        extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
                    callStyle = template == Notification.CallStyle::class.java.name,
                    metricTime =
                        if (template == TallyMetricTime.TEMPLATE) TallyMetricTime.from(extras)
                        else null,
                )
            }
        }
    }
}
