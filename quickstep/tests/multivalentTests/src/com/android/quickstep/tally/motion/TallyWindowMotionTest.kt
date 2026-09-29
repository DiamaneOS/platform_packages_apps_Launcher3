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
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The prototype's window rects (`apps.js`) on the FP6: 1116 × 2484 px at density 3 (the prototype's
 * 372 × 828 dp canvas), windows at rest with 34 dp corners as the prototype's.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyWindowMotionTest {
    private val out = TallyWindowRect()

    @Test
    fun homeRect_atRest_isTheWholeScreen() {
        TallyWindowMotion.homeRect(0f, 0f, W, H, D, R, out)
        assertRect(out.rect, 0f, 0f, W, H)
        assertThat(out.radius).isWithin(0.01f).of(R)
    }

    @Test
    fun homeRect_after360dp_isAt046WithItsBottomUnderTheFinger() {
        TallyWindowMotion.homeRect(360f * D, 0f, W, H, D, R, out)
        assertThat(out.rect.width()).isWithin(0.5f).of(W * 0.46f)
        assertThat(out.rect.height()).isWithin(0.5f).of(H * 0.46f)
        assertThat(out.rect.bottom).isWithin(0.5f).of(H - 360f * D)
        assertThat(out.rect.centerX()).isWithin(0.5f).of(W / 2f)
        assertThat(out.radius).isWithin(0.01f).of(24f * D)
    }

    @Test
    fun homeRect_matchesThePrototypeHalfway() {
        // wm.homeRect({dx: 40, dy: -180}) on the 372 × 828 dp canvas.
        val up = 180f
        val dx = 40f
        val k = up / 360f
        val s = 1f + (0.46f - 1f) * k
        val w = 372f * s
        val h = 828f * s
        val bottom = 828f - up
        val x = 372f / 2f + dx * 0.9f - w / 2f
        TallyWindowMotion.homeRect(up * D, dx * D, W, H, D, R, out)
        assertRect(out.rect, x * D, (bottom - h) * D, (x + w) * D, bottom * D)
        assertThat(out.radius).isWithin(0.01f).of((34f + (24f - 34f) * k) * D)
    }

    @Test
    fun homeRect_pastItsTravel_givesWayWithoutAJump() {
        TallyWindowMotion.homeRect(360f * D, 0f, W, H, D, R, out)
        val at = out.rect.width()
        TallyWindowMotion.homeRect(361f * D, 0f, W, H, D, R, out)
        val oneDpOn = out.rect.width()
        // Before the band the width drops 0.54 × 372 / 360 dp per dp; after it, less, never a jump.
        val before = 0.54f * 372f / 360f * D
        assertThat(at - oneDpOn).isLessThan(before)
        assertThat(at - oneDpOn).isGreaterThan(0f)
        TallyWindowMotion.homeRect(5000f * D, 0f, W, H, D, R, out)
        assertThat(out.rect.width() / W).isAtLeast(0.46f - 0.08f - 1e-4f)
    }

    @Test
    fun homeRect_belowTheStart_givesWayAtMost24dp() {
        TallyWindowMotion.homeRect(-1000f * D, 0f, W, H, D, R, out)
        assertThat(out.rect.width()).isWithin(0.01f).of(W)
        assertThat(out.rect.bottom - H).isAtMost(24f * D)
        assertThat(out.rect.bottom - H).isGreaterThan(20f * D)
    }

    @Test
    fun homeDim_liftsWithTheProgress() {
        assertThat(TallyWindowMotion.homeDim(0f)).isWithin(1e-6f).of(0.3f)
        assertThat(TallyWindowMotion.homeDim(1f)).isWithin(1e-6f).of(0f)
        assertThat(TallyWindowMotion.homeProgress(180f * D, D)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun backRect_atFullProgress_isAt09MovedWithTheFinger() {
        TallyWindowMotion.backRect(1f, 1, 400f * D, W, H, D, R, out)
        assertThat(out.rect.width()).isWithin(0.5f).of(W * 0.9f)
        // From the left edge the window moves 8 dp right, away from the edge, with the finger.
        assertThat(out.rect.centerX() - W / 2f).isWithin(0.5f).of(8f * D)
        // A quarter of 400 dp is 100 dp, held to 40 dp.
        assertThat(out.rect.centerY() - H / 2f).isWithin(0.5f).of(40f * D)
        assertThat(out.radius).isWithin(0.01f).of(20f * D)
        TallyWindowMotion.backRect(0.5f, -1, -40f * D, W, H, D, R, out)
        assertThat(out.rect.centerX() - W / 2f).isWithin(0.5f).of(-4f * D)
        assertThat(out.rect.centerY() - H / 2f).isWithin(0.5f).of(-5f * D)
        assertThat(out.rect.width()).isWithin(0.5f).of(W * 0.95f)
        // A Back button's window does not move sideways.
        TallyWindowMotion.backRect(1f, 0, 0f, W, H, D, R, out)
        assertThat(out.rect.centerX()).isWithin(0.5f).of(W / 2f)
        assertThat(out.rect.centerY()).isWithin(0.5f).of(H / 2f)
    }

    @Test
    fun backProgress_isTheFingersTravelOver160dp() {
        // The platform's progress is the travel over the display's width (372 dp on the FP6).
        val at80dp = 80f / 372f
        assertThat(TallyWindowMotion.backProgress(at80dp, true, W, D)).isWithin(1e-5f).of(0.5f)
        assertThat(TallyWindowMotion.backProgress(160f / 372f, true, W, D)).isWithin(1e-5f).of(1f)
        assertThat(TallyWindowMotion.backProgress(0.9f, true, W, D)).isEqualTo(1f)
        // A Back button's progress is taken as it is.
        assertThat(TallyWindowMotion.backProgress(at80dp, false, W, D)).isWithin(1e-6f).of(at80dp)
    }

    @Test
    fun backDim_liftsBy60Percent() {
        assertThat(TallyWindowMotion.backDim(0f)).isWithin(1e-6f).of(0.3f)
        assertThat(TallyWindowMotion.backDim(1f)).isWithin(1e-6f).of(0.12f)
    }

    @Test
    fun theKeyComesBack_betweenHalfTheScreenAnd96dp() {
        assertThat(TallyWindowMotion.keyBack(W, W, D)).isEqualTo(0f)
        assertThat(TallyWindowMotion.keyBack(0.51f * W, W, D)).isEqualTo(0f)
        assertThat(TallyWindowMotion.keyBack(96f * D, W, D)).isEqualTo(1f)
        assertThat(TallyWindowMotion.keyBack(56f * D, W, D)).isEqualTo(1f)
        val mid = (0.51f * W + 96f * D) / 2f
        assertThat(TallyWindowMotion.keyBack(mid, W, D)).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun withNoKey_theWindowFadesByFiveSixths() {
        assertThat(TallyWindowMotion.fadeOut(0f)).isEqualTo(1f)
        assertThat(TallyWindowMotion.fadeOut(0.5f)).isWithin(1e-6f).of(0.4f)
        assertThat(TallyWindowMotion.fadeOut(1f / 1.2f)).isWithin(1e-6f).of(0f)
        assertThat(TallyWindowMotion.fadeOut(1f)).isEqualTo(0f)
    }

    @Test
    fun keyRect_isTheVisibleKeyWith22PercentCorners() {
        val icon = RectF(100f, 200f, 100f + 183f, 200f + 183f)
        TallyWindowMotion.keyRect(icon, 0.92f, 0.22f, out)
        assertThat(out.rect.width()).isWithin(0.01f).of(183f * 0.92f)
        assertThat(out.rect.centerX()).isWithin(0.01f).of(icon.centerX())
        assertThat(out.radius).isWithin(0.01f).of(0.22f * 183f * 0.92f)
    }

    @Test
    fun dockTarget_is48dpWith11dpCorners() {
        TallyWindowMotion.dockTarget(W, 2000f, D, out)
        assertRect(out.rect, W / 2f - 72f, 2000f - 72f, W / 2f + 72f, 2000f + 72f)
        assertThat(out.radius).isWithin(0.01f).of(33f)
    }

    @Test
    fun content_fillsTheRectUniformly_pinnedAt50And32Percent() {
        val window = RectF(0f, 0f, W, H)
        val key = TallyWindowRect().set(RectF(300f, 900f, 468f, 1068f), 37f)
        val content = TallyWindowContent()
        content.map(window, key, TallyWindowContent.fitScale(window, key.rect))
        // Scale max(sx, sy): the key's width over the screen's.
        assertThat(content.scale).isWithin(1e-4f).of(168f / W)
        // The crop is the whole width, as tall as the key over the scale, 32 % of the way down.
        assertThat(content.crop.width()).isWithin(1).of(W.toInt())
        val cropH = 168f / (168f / W)
        assertThat(content.crop.height().toFloat()).isWithin(1f).of(cropH)
        assertThat(content.crop.top.toFloat()).isWithin(1f).of(0.32f * (H - cropH))
        // The crop's corners land on the key's.
        val pts = floatArrayOf(content.crop.left.toFloat(), content.crop.top.toFloat())
        content.matrix.mapPoints(pts)
        assertThat(pts[0]).isWithin(1f).of(300f)
        assertThat(pts[1]).isWithin(1f).of(900f)
        // The layer's corners, scaled by the matrix, are the key's.
        assertThat(content.layerRadius * content.scale).isWithin(0.01f).of(37f)
    }

    @Test
    fun content_ofAFullScreenRect_isTheWholeWindow() {
        val window = RectF(0f, 0f, W, H)
        TallyWindowMotion.homeRect(200f * D, 30f * D, W, H, D, R, out)
        val content = TallyWindowContent()
        content.map(window, out, TallyWindowContent.fitScale(window, out.rect))
        assertThat(content.crop.left).isEqualTo(0)
        assertThat(content.crop.top).isEqualTo(0)
        assertThat(content.crop.right).isWithin(1).of(W.toInt())
        assertThat(content.crop.bottom).isWithin(1).of(H.toInt())
    }

    @Test
    fun splashScale_showsTheSplashKeyAtTheKeysSize() {
        val window = RectF(0f, 0f, W, H)
        val keyPx = 168f
        val splashPx = 88f * D
        val start = RectF(474f, 1500f, 474f + keyPx, 1500f + keyPx)
        val s0 = TallyWindowMotion.splashScale(keyPx, splashPx, 0f, window, start)
        // The 88 dp splash key shows 56 dp wide over the 56 dp key.
        assertThat(splashPx * s0).isWithin(0.01f).of(keyPx)
        val s1 = TallyWindowMotion.splashScale(keyPx, splashPx, 1f, window, window)
        assertThat(s1).isWithin(1e-5f).of(1f)
        // Never so small that the content would not fill the rect.
        val tall = RectF(0f, 0f, 200f, 2000f)
        assertThat(TallyWindowMotion.splashScale(keyPx, splashPx, 0.1f, window, tall))
            .isAtLeast(TallyWindowContent.fitScale(window, tall))
    }

    @Test
    fun lerp_movesEveryEdgeAndTheCornersTogether() {
        val a = TallyWindowRect().set(RectF(0f, 0f, 100f, 200f), 10f)
        val b = TallyWindowRect().set(RectF(50f, 100f, 70f, 120f), 2f)
        out.lerp(a, b, 0.25f)
        assertRect(out.rect, 12.5f, 25f, 92.5f, 180f)
        assertThat(out.radius).isWithin(1e-5f).of(8f)
    }

    private fun assertRect(r: RectF, l: Float, t: Float, rr: Float, b: Float) {
        assertThat(abs(r.left - l)).isLessThan(0.5f)
        assertThat(abs(r.top - t)).isLessThan(0.5f)
        assertThat(abs(r.right - rr)).isLessThan(0.5f)
        assertThat(abs(r.bottom - b)).isLessThan(0.5f)
    }

    private companion object {
        const val W = 1116f
        const val H = 2484f
        const val D = 3f
        const val R = 34f * D
    }
}
