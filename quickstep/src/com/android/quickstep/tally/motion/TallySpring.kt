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

package com.android.quickstep.tally.motion

import android.content.res.Resources
import com.android.launcher3.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One of Tally's springs: Android's SpringForce with [stiffness] and a damping ratio of 1, so it
 * never bounces (the prototype's `T.SP`, the token library's `tally_spring_*`). A critically damped
 * spring has a closed form, which lets an Animator play it: a move that starts [x0] from its target
 * with velocity [v0] is `x(t) = (x0 + (v0 + ω x0) t) e^(-ω t)` from the target at time `t`, with `ω
 * = √stiffness`. That is the exact solution of SpringForce's equation; the prototype integrates the
 * same equation in 1 ms steps (`H.Spring`).
 *
 * The motion code plays each move as a [Move] on a ValueAnimator over the move's settle time, so
 * the animator duration scale applies as it does to every Animator (0.5x plays it twice as fast,
 * Remove animations ends it at once), and so it plugs into the remote-animation Animators that
 * Launcher already hands to the window manager.
 */
class TallySpring(val stiffness: Float) {
    /** The natural frequency, `√stiffness`, in radians per second. */
    val omega: Double = sqrt(stiffness.toDouble())

    /** The displacement from the target [t] seconds into a move from ([x0], [v0]). */
    fun displacement(x0: Double, v0: Double, t: Double): Double =
        (x0 + (v0 + omega * x0) * t) * exp(-omega * t)

    /** The velocity [t] seconds into a move from ([x0], [v0]). */
    fun velocity(x0: Double, v0: Double, t: Double): Double =
        (v0 - omega * (v0 + omega * x0) * t) * exp(-omega * t)

    /**
     * The tap impulse, the prototype's `impulse: true`: a move started by a tap begins towards its
     * target at `0.6 · √k · distance`, so the first 120 Hz frame visibly moves and a damping ratio
     * of 1 still cannot overshoot.
     */
    fun impulse(distance: Double): Double = TAP_IMPULSE * omega * distance

    /**
     * When a released move first reaches its target: a critically damped spring released towards
     * its target faster than `ω · distance` would cross it once. The prototype makes the target a
     * wall there (`Val.release`: it lands, it never passes), and so does [Move]. Returns
     * [Double.POSITIVE_INFINITY] for a move that does not cross.
     */
    fun wallTime(x0: Double, v0: Double): Double {
        val b = v0 + omega * x0
        if (x0 == 0.0 || b == 0.0 || (x0 > 0) == (b > 0)) return Double.POSITIVE_INFINITY
        return -x0 / b
    }

    /**
     * When a move from ([x0], [v0]) is at rest: from then on it stays within [restDelta] of its
     * target and slower than [restVelocity], as the prototype's `H.Spring` rests. Seconds, at most
     * [MAX_SECONDS].
     */
    fun settleTime(x0: Double, v0: Double, restDelta: Double, restVelocity: Double): Double {
        // After its one turn, a critically damped move only decays, so the last sample outside
        // the rest band ends the move. Called once per move, never per frame.
        var last = 0.0
        var i = 1
        while (i <= SETTLE_STEPS) {
            val t = i * SETTLE_STEP
            if (
                abs(displacement(x0, v0, t)) >= restDelta ||
                    abs(velocity(x0, v0, t)) >= restVelocity
            ) {
                last = t
            }
            i++
        }
        return minOf(last + SETTLE_STEP, MAX_SECONDS)
    }

    /**
     * One move of this spring from [from] to [to], starting with velocity [v0] (units per second),
     * resting within [restDelta] and [restVelocity], with the target as a wall when [wall] is set
     * (for a release that may cross it). Plain values: allocated once when a move starts.
     */
    inner class Move(
        val from: Double,
        val to: Double,
        val v0: Double,
        restDelta: Double,
        restVelocity: Double,
        val wall: Boolean = false,
    ) {
        private val x0 = from - to
        private val crossesAt = if (wall) wallTime(x0, v0) else Double.POSITIVE_INFINITY

        /** The move's length in seconds at an animator duration scale of 1. */
        val seconds: Double = minOf(settleTime(x0, v0, restDelta, restVelocity), crossesAt)

        /** The move's length in whole milliseconds, for an Animator's duration. */
        val millis: Long = ceil(seconds * 1000.0).toLong()

        /** The value [t] seconds into the move; the target from [seconds] on. */
        fun valueAt(t: Double): Double =
            if (t >= seconds) to else to + displacement(x0, v0, maxOf(0.0, t))

        /** The velocity [t] seconds into the move; 0 from [seconds] on. */
        fun velocityAt(t: Double): Double =
            if (t >= seconds) 0.0 else velocity(x0, v0, maxOf(0.0, t))

        /** The velocity at [fraction] of an Animator of [millis]. */
        fun velocityAtFraction(fraction: Float): Double = velocityAt(fraction * millis / 1000.0)

        /**
         * The value at [fraction] of the move, as the fraction of an Animator of [millis] runs from
         * 0 to 1.
         */
        fun valueAtFraction(fraction: Float): Double = valueAt(fraction * millis / 1000.0)

        /**
         * How far along the move is at [fraction]: 0 at the start, 1 at the target (the curve an
         * interpolator would give; above 1 only if the move overshoots, which ratio 1 does only for
         * releases, and never with [wall]).
         */
        fun progressAtFraction(fraction: Float): Float {
            if (fraction >= 1f || from == to) return 1f
            return ((valueAtFraction(fraction) - from) / (to - from)).toFloat()
        }
    }

    companion object {
        /** The prototype's tap impulse factor (`tally_motion_tap_impulse`). */
        const val TAP_IMPULSE = 0.6
        /** The longest any move may take. */
        const val MAX_SECONDS = 2.0
        private const val SETTLE_STEP = 0.0005
        private val SETTLE_STEPS = (MAX_SECONDS / SETTLE_STEP).toInt()

        /** Pebble (1500): things up to 48 dp, keys pressed in. */
        const val PEBBLE = 1500f
        /** Stone (700): cards, a key's neighbours, sheets up to half height. */
        const val STONE = 700f
        /** Slab (420): full-screen surfaces, windows and pages. */
        const val SLAB = 420f
        /** Fill (1600): effects, never overshooting (opacity, dims, lamps). */
        const val FILL = 1600f

        /**
         * The springs as the token library has them (`tally_spring_<name>_stiffness`; each damping
         * ratio there is 1). The constants above are the same values, for tests and the harness.
         */
        @JvmStatic
        fun slab(res: Resources) = TallySpring(res.getFloat(R.dimen.tally_spring_slab_stiffness))

        @JvmStatic
        fun stone(res: Resources) = TallySpring(res.getFloat(R.dimen.tally_spring_stone_stiffness))

        @JvmStatic
        fun fill(res: Resources) = TallySpring(res.getFloat(R.dimen.tally_spring_fill_stiffness))
    }
}
