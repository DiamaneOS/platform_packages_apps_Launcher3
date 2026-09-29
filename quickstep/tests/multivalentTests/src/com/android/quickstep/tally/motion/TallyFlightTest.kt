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

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The flight's hand-off (`Win.flyTo`), a window closing into its key or to the dock ([TallyClose])
 * and a key's neighbours ([TallyParting.direction]).
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyFlightTest {

    @Test
    fun handOff_projectsTheFingerOnTheLineBetweenTheCentres() {
        val start = RectF(0f, 0f, 100f, 100f)
        val target = RectF(0f, -300f, 100f, -200f)
        // Straight up at 600 px/s over a 300 px line: 2 q/s.
        assertThat(TallyFlight.handOff(start, target, 0f, -600f)).isWithin(1e-5f).of(2f)
        // Sideways does not count.
        assertThat(TallyFlight.handOff(start, target, 800f, -600f)).isWithin(1e-5f).of(2f)
    }

    @Test
    fun handOff_isHeldBetweenMinus2And12() {
        val start = RectF(0f, 0f, 100f, 100f)
        val target = RectF(0f, -300f, 100f, -200f)
        assertThat(TallyFlight.handOff(start, target, 0f, -30000f)).isEqualTo(12f)
        assertThat(TallyFlight.handOff(start, target, 0f, 30000f)).isEqualTo(-2f)
        assertThat(TallyFlight.handOff(start, start, 0f, -600f)).isEqualTo(0f)
    }

    @Test
    fun aFlightWithNoFinger_startsWithTheTapImpulse() {
        val slab = TallySpring(TallySpring.SLAB)
        val flight = TallyFlight(slab)
        val move = flight.move(RectF(), RectF(), 0f, 0f)
        assertThat(move.v0).isWithin(1e-9).of(slab.impulse(1.0))
        assertThat(move.wall).isTrue()
        val flung = flight.move(RectF(0f, 0f, 1f, 1f), RectF(0f, -300f, 1f, -299f), 0f, -3000f)
        assertThat(flung.v0).isWithin(1e-6).of(10.0)
    }

    @Test
    fun closingIntoAKey_theKeyComesBackOverTheWindow() {
        val close = TallyClose(102f, 37f, true, 1f / 0.92f, W, D)
        val window = RectF(0f, 0f, W, H)
        close.update(RectF(0f, 0f, W, H), 0f, window)
        assertThat(close.windowAlpha).isEqualTo(1f)
        assertThat(close.iconAlpha).isEqualTo(0f)
        assertThat(close.window.radius).isWithin(1e-4f).of(102f)
        // Half way between half the screen and 96 dp: the window is half faded over the icon.
        val mid = (0.51f * W + 96f * D) / 2f
        close.update(RectF(0f, 0f, mid, mid * H / W), 0.5f, window)
        assertThat(close.windowAlpha).isWithin(1e-4f).of(0.5f)
        assertThat(close.iconAlpha).isEqualTo(1f)
        assertThat(close.window.radius).isWithin(1e-4f).of((102f + 37f) / 2f)
        // Landed on the key: the icon, no window.
        val key = RectF(300f, 900f, 468f, 1068f)
        close.update(key, 1f, window)
        assertThat(close.windowAlpha).isEqualTo(0f)
        assertThat(close.window.radius).isWithin(1e-4f).of(37f)
        // The icon's bounds are the key grown to the icon's bounds.
        val icon = close.icon(key)
        assertThat(icon.width()).isWithin(0.01f).of(168f / 0.92f)
        assertThat(icon.centerX()).isWithin(0.01f).of(key.centerX())
    }

    @Test
    fun closingWithNoKey_theWindowFadesToTheDock() {
        val close = TallyClose(102f, 33f, false, 1f, W, D)
        val window = RectF(0f, 0f, W, H)
        close.update(RectF(0f, 0f, W, H), 0.5f, window)
        assertThat(close.windowAlpha).isWithin(1e-6f).of(0.4f)
        assertThat(close.iconAlpha).isEqualTo(0f)
        close.update(RectF(486f, 1900f, 630f, 2044f), 0.9f, window)
        assertThat(close.windowAlpha).isEqualTo(0f)
    }

    @Test
    fun neighbours_withinReach_moveStraightAway() {
        val out = FloatArray(2)
        // A key 93 px to the right moves right; the key itself and keys out of reach stay.
        assertThat(TallyParting.direction(100f, 100f, 193f, 100f, 140f, out)).isTrue()
        assertThat(out[0]).isWithin(1e-6f).of(1f)
        assertThat(out[1]).isWithin(1e-6f).of(0f)
        assertThat(TallyParting.direction(100f, 100f, 100.5f, 100f, 140f, out)).isFalse()
        assertThat(TallyParting.direction(100f, 100f, 300f, 100f, 140f, out)).isFalse()
        // Diagonal neighbours on the 93 × 100 dp grid are within 1.6 × 88 dp.
        assertThat(TallyParting.direction(0f, 0f, 93f, 100f, 1.6f * 88f, out)).isTrue()
        assertThat(out[0] * out[0] + out[1] * out[1]).isWithin(1e-5f).of(1f)
        // Two columns over is not.
        assertThat(TallyParting.direction(0f, 0f, 186f, 0f, 1.6f * 88f, out)).isFalse()
    }

    private companion object {
        const val W = 1116f
        const val H = 2484f
        const val D = 3f
    }
}
