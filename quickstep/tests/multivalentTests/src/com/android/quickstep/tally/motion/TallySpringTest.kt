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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tally's springs in closed form against the prototype's (`harness.js` `H.Spring`, which steps
 * SpringForce's equation in 1 ms substeps each 120 Hz frame), at animator duration scales 1 and
 * 0.5, with the prototype's tap impulse and its wall for releases.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallySpringTest {

    @Test
    fun slabWithImpulse_matchesThePrototypeFrameByFrame() {
        assertMatchesPrototype(TallySpring.SLAB, from = 0.0, to = 1.0, impulse = true)
    }

    @Test
    fun stoneWithImpulse_matchesThePrototypeFrameByFrame() {
        assertMatchesPrototype(TallySpring.STONE, from = 0.0, to = 1.0, impulse = true)
    }

    @Test
    fun fillFromRest_matchesThePrototypeFrameByFrame() {
        assertMatchesPrototype(TallySpring.FILL, from = 0.0, to = 0.3, impulse = false)
    }

    @Test
    fun releaseWithVelocity_matchesThePrototypeFrameByFrame() {
        // A fling towards Home: 6 q/s, slower than the wall.
        assertMatchesPrototype(TallySpring.SLAB, from = 0.0, to = 1.0, impulse = false, v0 = 6.0)
    }

    @Test
    fun theWindowHasOpenedBy220ms() {
        val slab = TallySpring(TallySpring.SLAB)
        val grow = slab.Move(0.0, 1.0, slab.impulse(1.0), 0.0005, 0.005)
        // "The window has opened by 220 ms" (the prototype's splash note): 97 % of the way.
        assertThat(grow.valueAt(0.220)).isGreaterThan(0.96)
        // At rest after about 480 ms, as the prototype's window rests.
        assertThat(grow.millis).isIn(com.google.common.collect.Range.closed(440L, 520L))
    }

    @Test
    fun theTapImpulse_isPointSixRootKTimesTheDistance() {
        val stone = TallySpring(TallySpring.STONE)
        assertThat(stone.impulse(8.0)).isWithin(1e-9).of(0.6 * sqrt(700.0) * 8.0)
        // The token library's factor, when it is given (tally_motion_tap_impulse).
        assertThat(TallySpring(TallySpring.STONE, 0.5).impulse(8.0))
            .isWithin(1e-9)
            .of(0.5 * sqrt(700.0) * 8.0)
    }

    @Test
    fun ratioOne_neverOvershootsFromATap() {
        for (k in
            listOf(TallySpring.PEBBLE, TallySpring.STONE, TallySpring.SLAB, TallySpring.FILL)) {
            val spring = TallySpring(k)
            val move = spring.Move(0.0, 1.0, spring.impulse(1.0), 1e-4, 1e-3)
            var t = 0.0
            while (t < move.seconds) {
                assertThat(move.valueAt(t)).isAtMost(1.0)
                t += 0.001
            }
        }
    }

    @Test
    fun aFastRelease_stopsAtTheWall() {
        val slab = TallySpring(TallySpring.SLAB)
        // 12 q/s is past omega (20.5 rad/s times 1): without the wall it would cross the target.
        val free = slab.Move(0.0, 1.0, 12.0 * 3, 0.0005, 0.005, wall = false)
        var peak = 0.0
        var t = 0.0
        while (t < free.seconds) {
            peak = max(peak, free.valueAt(t))
            t += 0.0005
        }
        assertThat(peak).isGreaterThan(1.0)
        val walled = slab.Move(0.0, 1.0, 12.0 * 3, 0.0005, 0.005, wall = true)
        t = 0.0
        while (t <= walled.seconds + 0.01) {
            assertThat(walled.valueAt(t)).isAtMost(1.0)
            t += 0.0005
        }
        assertThat(walled.valueAt(walled.seconds)).isEqualTo(1.0)
    }

    @Test
    fun atHalfScale_theSameCurvePlaysInHalfTheTime() {
        val slab = TallySpring(TallySpring.SLAB)
        val grow = slab.Move(0.0, 1.0, slab.impulse(1.0), 0.0005, 0.005)
        val prototype = PrototypeSpring(TallySpring.SLAB.toDouble(), 0.0, 1.0, grow.v0)
        // A ValueAnimator of grow.millis at scale 0.5 runs grow.millis / 2 of wall time; its
        // fraction at wall time w is w / (0.5 · millis), so the curve is at 2w.
        val frame = 1.0 / 120.0
        var w = 0.0
        while (w < grow.seconds / 2) {
            val fraction = (w / (0.5 * grow.millis / 1000.0)).toFloat().coerceAtMost(1f)
            val atHalf = grow.valueAtFraction(fraction)
            assertThat(atHalf).isWithin(1e-6).of(grow.valueAt(2 * w))
            assertThat(atHalf).isWithin(TOLERANCE).of(prototype.valueAt(2 * w))
            w += frame
        }
    }

    @Test
    fun theEndOfAMove_isTheTarget() {
        val fill = TallySpring(TallySpring.FILL)
        val dim = fill.Move(0.3, 0.0, 0.0, 1.0 / 255, 0.5)
        assertThat(dim.valueAtFraction(1f)).isWithin(0.0).of(0.0)
        assertThat(dim.progressAtFraction(1f)).isWithin(0f).of(1f)
        assertThat(dim.progressAtFraction(0f)).isWithin(0f).of(0f)
    }

    @Test
    fun settleTimes_areThePrototypesRestTimes() {
        // The prototype rests when within restDelta and restVelocity at a frame.
        for ((k, rest) in listOf(TallySpring.SLAB to 0.0005, TallySpring.STONE to 0.00125)) {
            val spring = TallySpring(k)
            val move = spring.Move(0.0, 1.0, spring.impulse(1.0), rest, rest * 10)
            val prototype = PrototypeSpring(k.toDouble(), 0.0, 1.0, move.v0)
            val restsAt = prototype.restTime(rest, rest * 10)
            // Within two frames of the prototype's own rest.
            assertThat(abs(move.seconds - restsAt)).isLessThan(2.0 / 120.0)
        }
    }

    private fun assertMatchesPrototype(
        k: Float,
        from: Double,
        to: Double,
        impulse: Boolean,
        v0: Double = 0.0,
    ) {
        val spring = TallySpring(k)
        val start = if (impulse) spring.impulse(to - from) else v0
        val move = spring.Move(from, to, start, 1e-4, 1e-3)
        val prototype = PrototypeSpring(k.toDouble(), from, to, start)
        var t = 0.0
        while (t < move.seconds) {
            assertThat(move.valueAt(t))
                .isWithin(TOLERANCE * abs(to - from))
                .of(prototype.valueAt(t))
            t += 1.0 / 120.0
        }
    }

    /**
     * The prototype's spring (`H.Spring._step`): each 120 Hz frame steps the equation in 1 ms
     * substeps with semi-implicit Euler.
     */
    class PrototypeSpring(
        private val k: Double,
        private val from: Double,
        private val to: Double,
        private val v0: Double,
        private val dampingRatio: Double = 1.0,
    ) {
        /** The value after the frames up to time [t] (frames of 1/120 s). */
        fun valueAt(t: Double): Double {
            var x = from
            var v = v0
            val frames = (t * 120.0 + 1e-9).toInt()
            val c = 2 * dampingRatio * sqrt(k)
            repeat(frames) {
                val dt = 1.0 / 120.0
                val n = max(1, ceil(dt / 0.001).toInt())
                val h = dt / n
                repeat(n) {
                    v += (-k * (x - to) - c * v) * h
                    x += v * h
                }
            }
            return x
        }

        /** When the prototype rests: the first frame within [restDelta] and [restVelocity]. */
        fun restTime(restDelta: Double, restVelocity: Double): Double {
            var x = from
            var v = v0
            val c = 2 * dampingRatio * sqrt(k)
            var frame = 0
            while (frame < 1000) {
                frame++
                val dt = 1.0 / 120.0
                val n = max(1, ceil(dt / 0.001).toInt())
                val h = dt / n
                repeat(n) {
                    v += (-k * (x - to) - c * v) * h
                    x += v * h
                }
                if (abs(x - to) < restDelta && abs(v) < restVelocity) return frame / 120.0
            }
            return frame / 120.0
        }
    }

    private companion object {
        /**
         * The closed form (SpringForce's own solution) and the prototype's 1 ms semi-implicit Euler
         * steps differ by less than 1.5 % of the travel; the difference is the prototype's step
         * error, which grows with the stiffness (1.1 % for fill, 0.5 % for slab).
         */
        const val TOLERANCE = 0.015
    }
}
