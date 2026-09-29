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

package com.android.launcher3.tally.keycap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.android.launcher3.R
import com.android.launcher3.tally.lamp.TallyLampState

/**
 * A keycap's LED, as the prototype's `T.badgeGlyph`: a lamp form set into a dark housing (onLamp)
 * in the key's top-right corner, 8 % in from the top and the right, where the notification dot was.
 * It takes the dot's place, but lights only while its app is live (a live readout or an activity in
 * progress, as TallyLiveRules decides, never a background service: a disc in a ring of light) or
 * failed (an error ring broken at one to two o'clock), never for new notifications, which stay the
 * shade's. The housing keeps every form at 3:1 or better on any key colour: the lamp colour on it,
 * and the error ring in the dark theme's error colour (in light theme the light-theme error would
 * be 2.6:1 on the housing).
 *
 * The LED's words go in its key's accessibility label ([describe]).
 */
class TallyKeycapLed(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val sizePx = context.resources.getDimension(R.dimen.tally_keycap_led_size)
    private val largeSizePx = context.resources.getDimension(R.dimen.tally_keycap_led_size_large)
    private val housingPx = context.resources.getDimension(R.dimen.tally_keycap_led_housing)
    private val inset = context.resources.getFraction(R.fraction.tally_keycap_led_inset, 1, 1)
    private val largeFromPx = LARGE_FROM_DP * density

    private val housingPaint = fill(context.getColor(R.color.tally_on_lamp))
    private val lampPaint = fill(context.getColor(R.color.tally_lamp))
    private val ringPaint = stroke(context.getColor(R.color.tally_lamp), LIVE_RING_WIDTH * density)
    private val failedPaint =
        stroke(context.getColor(R.color.tally_keycap_led_failed), FAILED_RING_WIDTH * density)
    private val oval = RectF()

    /**
     * Draws the LED for [state] on a key whose visible square starts at ([left], [top]) and is
     * [keySize] across. Nothing for states other than live and failed.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, keySize: Float, state: TallyLampState?) {
        if (state != TallyLampState.LIVE && state != TallyLampState.FAILED) return
        // 10 dp, or 11 dp on keys of 52 dp and up.
        val s = if (keySize >= largeFromPx - 0.5f) largeSizePx else sizePx
        val housing = s / 2f + housingPx
        val cx = left + keySize * (1f - inset) - housing
        val cy = top + keySize * inset + housing
        canvas.drawCircle(cx, cy, housing, housingPaint)
        if (state == TallyLampState.LIVE) {
            canvas.drawCircle(cx, cy, s / 2f - LIVE_DISC_INSET * density, lampPaint)
            canvas.drawCircle(cx, cy, s / 2f - LIVE_RING_INSET * density, ringPaint)
        } else {
            val r = s / 2f - FAILED_RING_INSET * density
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(
                oval,
                FAILED_START_DEGREES,
                360f - FAILED_GAP_DEGREES,
                false,
                failedPaint,
            )
        }
    }

    companion object {
        /** The prototype's glyph, in dp for a lamp of size s: the live disc is s / 2 - 2.5. */
        private const val LIVE_DISC_INSET = 2.5f
        /** The ring of light: radius s / 2 - 0.6, 1.2 wide. */
        private const val LIVE_RING_INSET = 0.6f
        private const val LIVE_RING_WIDTH = 1.2f
        /** The failed ring: radius s / 2 - 0.75, 1.5 wide, a 60° gap from -15°. */
        private const val FAILED_RING_INSET = 0.75f
        private const val FAILED_RING_WIDTH = 1.5f
        private const val FAILED_START_DEGREES = -15f
        private const val FAILED_GAP_DEGREES = 60f
        /** Keys from this size (dp) have the large LED. */
        private const val LARGE_FROM_DP = 52f

        /**
         * [label] with the words of a lit LED ("Active" for live, "Error" for failed), or [label]
         * alone when the LED is dark.
         */
        @JvmStatic
        fun describe(context: Context, label: CharSequence, state: TallyLampState?): CharSequence {
            val words =
                when (state) {
                    TallyLampState.LIVE -> context.getString(R.string.app_running_state_description)
                    TallyLampState.FAILED -> context.getString(R.string.tally_state_failed)
                    else -> return label
                }
            return context.getString(R.string.tally_key_with_state, label, words)
        }

        private fun fill(color: Int) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }

        private fun stroke(color: Int, width: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.BUTT
                this.color = color
            }
    }
}
