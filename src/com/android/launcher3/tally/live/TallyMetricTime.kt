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

import android.os.Bundle

/**
 * The time a Notification.MetricStyle notification shows (a Notification.Metric.TimeDifference): a
 * timer counting down or a stopwatch counting up, running or paused. DiamaneOS's Clock shows its
 * timers and stopwatch this way, as Live Updates without a chronometer of their own.
 *
 * [from] reads it from the notification's extras, as Notification.MetricStyle writes them, with
 * plain Bundle getters: never through the platform's Metric classes, which throw on a malformed
 * bundle. Anything unexpected (a missing key, a wrong type, an impossible value, a value that fails
 * to unparcel) gives no time, never a crash. Only numbers are read, never a metric's label or text.
 *
 * On Home the time reads as the system's chronometer shows it, in the notification
 * (android.widget.Chronometer) and in the status bar chip (SystemUI's ChronometerState, which
 * matches it): a count-down's whole seconds rounded down, a count-up's rounded to the nearest.
 *
 * @property kind which clock [millis] is on
 * @property countDown a timer (it counts down to zero), not a stopwatch (it counts up from zero)
 */
data class TallyMetricTime(val kind: Kind, val millis: Long, val countDown: Boolean) {

    enum class Kind {
        /** Running: [millis] is when it is zero, on the wall clock (System.currentTimeMillis). */
        ZERO_TIME,
        /** Running: [millis] is when it is zero, in SystemClock.elapsedRealtime. */
        ZERO_REALTIME,
        /** Paused: [millis] is the time it shows, left on a timer or elapsed on a stopwatch. */
        PAUSED,
    }

    /** Whether it is counting (not paused). */
    val running: Boolean
        get() = kind != Kind.PAUSED

    /**
     * A paused time's whole seconds, as the system's chronometer shows it paused
     * (Chronometer.setPausedDuration), or [TallyLiveItem.NO_PAUSED_TIME] when it is running or
     * longer than [MAX_MILLIS].
     */
    val pausedSeconds: Long
        get() =
            when {
                kind != Kind.PAUSED || millis !in 0L..MAX_MILLIS -> TallyLiveItem.NO_PAUSED_TIME
                // Chronometer.updateText's Math.round((millis - 499) / 1000f) and
                // Math.round(millis / 1000f), in whole milliseconds.
                countDown -> (millis + 1) / 1000
                else -> (millis + 500) / 1000
            }

    /**
     * A running time's chronometer base for the tallies row ([TallyLiveItem.chronometerBase]) at
     * [realtime] (SystemClock.elapsedRealtime) and [wallTime] (System.currentTimeMillis) now, or
     * [TallyLiveItem.NO_CHRONOMETER] when it is paused or its zero is more than [MAX_MILLIS] away.
     *
     * The row rounds a count-down's seconds up and a count-up's down
     * (com.android.launcher3.tally.home.TallyTalliesRow.readoutOf), from the base on: so the base
     * is where the system's chronometer starts to read 0:00, 998 ms before a count-down's zero
     * (Math.round((zero - now - 499) / 1000f) is 0 from then) and 500 ms before a count-up's.
     */
    fun chronometerBase(realtime: Long, wallTime: Long): Long {
        val zeroFromNow =
            when (kind) {
                Kind.ZERO_TIME -> if (millis > 0L) millis - wallTime else return NONE
                Kind.ZERO_REALTIME -> if (millis >= 0L) millis - realtime else return NONE
                Kind.PAUSED -> return NONE
            }
        // Both terms are 0 or more, so the difference does not overflow.
        if (zeroFromNow !in -MAX_MILLIS..MAX_MILLIS) return NONE
        return realtime + zeroFromNow - if (countDown) COUNT_DOWN_LEAD_MS else COUNT_UP_LEAD_MS
    }

    companion object {
        /** EXTRA_TEMPLATE of a Notification.MetricStyle notification. */
        const val TEMPLATE = "android.app.Notification\$MetricStyle"

        /**
         * The longest time Home shows, 1000 hours (Clock's timers go to 99:99:99, about 101 hours).
         * A time further off is no readout.
         */
        const val MAX_MILLIS = 1000L * 60 * 60 * 1000

        private const val NONE = TallyLiveItem.NO_CHRONOMETER
        private const val COUNT_DOWN_LEAD_MS = 998L
        private const val COUNT_UP_LEAD_MS = 500L

        // Notification.MetricStyle's extras (Notification.EXTRA_METRICS and
        // EXTRA_METRICS_CRITICAL_INDEX) and, in each metric's Bundle, Notification.Metric's value
        // and MetricValue's and TimeDifference's keys: none of them public.
        private const val EXTRA_METRICS = "android.metrics"
        private const val EXTRA_METRICS_CRITICAL_INDEX = "android.metrics.criticalIndex"
        private const val KEY_VALUE = "value"
        private const val KEY_TYPE = "_type"
        private const val TYPE_TIME_DIFFERENCE = 1
        private const val KEY_COUNT_DOWN = "countDown"
        private const val KEY_FORMAT = "format"
        private const val FORMAT_ADAPTIVE = 1
        private const val FORMAT_CHRONOMETER = 2
        private val TIME_KEYS =
            mapOf(
                "zeroTime" to Kind.ZERO_TIME,
                "zeroElapsedRealtime" to Kind.ZERO_REALTIME,
                "pausedDuration" to Kind.PAUSED,
            )

        /** How many metrics the notification shows (MetricStyle.MAX_METRICS). */
        private const val SHOWN_METRICS = 3

        /**
         * The time in a MetricStyle notification's [extras] (EXTRA_TEMPLATE is [TEMPLATE]): its
         * critical metric's when the critical index is one of the metrics, else the first time
         * metric's among those the notification shows. Null for none, or for anything malformed.
         */
        @JvmStatic
        fun from(extras: Bundle): TallyMetricTime? =
            try {
                read(extras)
            } catch (e: RuntimeException) {
                // A value that fails to unparcel (BadParcelableException) comes from a malformed or
                // hostile notification: no readout. Nothing is logged: the message may name the
                // app's classes.
                null
            }

        private fun read(extras: Bundle): TallyMetricTime? {
            // A parcelled list's items are checked to be Bundles as it unparcels, but a list read
            // before in this process is not checked again: each item is checked here.
            val metrics: List<Any?> =
                extras.getParcelableArrayList(EXTRA_METRICS, Bundle::class.java) ?: return null
            // As MetricStyle.restoreFromExtras: the first unless set; -1 is none.
            val critical = extras.getInt(EXTRA_METRICS_CRITICAL_INDEX, 0)
            if (critical in metrics.indices) return timeOf(valueOf(metrics[critical]))
            for (i in 0 until minOf(metrics.size, SHOWN_METRICS)) {
                val value = valueOf(metrics[i]) ?: continue
                if (value.getInt(KEY_TYPE, 0) == TYPE_TIME_DIFFERENCE) return timeOf(value)
            }
            return null
        }

        private fun valueOf(metric: Any?): Bundle? = (metric as? Bundle)?.getBundle(KEY_VALUE)

        /** The time in a metric's [value], as TimeDifference.fromBundle and its checks read it. */
        private fun timeOf(value: Bundle?): TallyMetricTime? {
            if (value == null || value.getInt(KEY_TYPE, 0) != TYPE_TIME_DIFFERENCE) return null
            val format = value.getInt(KEY_FORMAT, 0)
            if (format != FORMAT_ADAPTIVE && format != FORMAT_CHRONOMETER) return null
            // Missing or not a boolean, it reads as each default.
            val countDown = value.getBoolean(KEY_COUNT_DOWN, false)
            if (countDown != value.getBoolean(KEY_COUNT_DOWN, true)) return null
            // Exactly one of the three times, as TimeDifference requires.
            val keys = TIME_KEYS.keys.filter { value.containsKey(it) }
            if (keys.size != 1) return null
            val kind = TIME_KEYS.getValue(keys[0])
            // Not a long, it reads as -1. A zero time is after 1970 (as a chronometer's `when`) or
            // after boot, and a paused time is not negative (the chip shows no negative one).
            val millis = value.getLong(keys[0], -1L)
            val valid =
                when (kind) {
                    Kind.ZERO_TIME -> millis > 0L
                    Kind.ZERO_REALTIME -> millis >= 0L
                    Kind.PAUSED -> millis in 0L..MAX_MILLIS
                }
            return if (valid) TallyMetricTime(kind, millis, countDown) else null
        }
    }
}
