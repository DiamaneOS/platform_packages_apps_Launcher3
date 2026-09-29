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
import android.os.SystemClock
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.annotation.WorkerThread
import com.android.launcher3.util.PackageUserKey

/**
 * Keeps [TallyLiveRepository] in step with the notifications Launcher's listener receives. It runs
 * on the listener's worker thread and keeps, per notification key, only a [TallyLiveItem]: never
 * the notification itself. Each call returns the apps whose LED changed.
 *
 * @param labelOf the app's name for the tallies row (badged for a work profile)
 * @param isPrivateProfile whether a user is the private space, whose things stay off the row
 * @param realtime the clock the row's order and chronometers use
 * @param wallTime the clock notifications' `when` uses
 */
class TallyLiveTracker
@JvmOverloads
constructor(
    private val repository: TallyLiveRepository,
    private val labelOf: (PackageUserKey) -> CharSequence,
    private val isPrivateProfile: (PackageUserKey) -> Boolean,
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
        val state =
            TallyLiveRules.stateOf(
                input.flags,
                input.category,
                input.onDefaultChannel,
                input.minimized,
            ) ?: return null
        val now = realtime()
        val since = if (previous != null && previous.state == state) previous.sinceRealtime else now
        val chronometerBase =
            if (input.showsChronometer && input.whenMillis > 0L) {
                input.whenMillis - wallTime() + now
            } else {
                TallyLiveItem.NO_CHRONOMETER
            }
        val progress =
            if (input.progressMax > 0 && !input.progressIndeterminate) {
                (input.progress.coerceIn(0, input.progressMax).toLong() * 1000 / input.progressMax)
                    .toInt()
            } else {
                TallyLiveItem.NO_PROGRESS
            }
        return TallyLiveItem(
            key = input.key,
            app = input.app,
            state = state,
            showsLed = input.canShowBadge,
            showsInRow = !isPrivateProfile(input.app),
            label = previous?.label ?: labelOf(input.app),
            progressPermille = progress,
            chronometerBase = chronometerBase,
            countDown = input.chronometerCountDown,
            sinceRealtime = since,
        )
    }

    /**
     * What the tracker reads from one notification and its ranking: flags, category, channel and
     * importance, and the progress bar and chronometer the system draws. Nothing else.
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
    ) {
        companion object {
            /** Reads [sbn] with its [ranking] (null when the ranking has no entry for it). */
            @JvmStatic
            fun from(sbn: StatusBarNotification, ranking: Ranking?): Input {
                val n = sbn.notification
                val extras = n.extras
                return Input(
                    key = sbn.key,
                    app = PackageUserKey.fromNotification(sbn),
                    flags = n.flags,
                    category = n.category,
                    onDefaultChannel =
                        ranking?.channel?.id == NotificationChannel.DEFAULT_CHANNEL_ID,
                    minimized = ranking?.isAmbient ?: false,
                    canShowBadge = ranking?.canShowBadge() ?: false,
                    progress = extras.getInt(Notification.EXTRA_PROGRESS),
                    progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX),
                    progressIndeterminate =
                        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
                    showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
                    whenMillis = n.`when`,
                    chronometerCountDown =
                        extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
                )
            }
        }
    }
}
