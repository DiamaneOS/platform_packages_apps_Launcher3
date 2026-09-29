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

package com.android.quickstep.tally

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.android.launcher3.R
import kotlin.math.ceil

/**
 * The Stop key of Recents' "Still running · Stop" row (Tally): a filled key in the accent with 4 dp
 * corners, centred in the bounds of a larger touch target. Pressed, the press layer lies over it;
 * with keyboard focus, the accent focus ring sits 2 dp clear of it.
 */
internal class TallyStopKeyDrawable(context: Context) : Drawable() {

    private val radius = context.resources.getDimension(R.dimen.tally_radius_xs)
    private val ringWidth = context.resources.getDimension(R.dimen.tally_focus_ring_width)
    private val ringOffset = context.resources.getDimension(R.dimen.tally_focus_ring_offset)

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.tally_accent) }
    private val pressPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.tally_press) }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringWidth
            color = context.getColor(R.color.tally_accent)
        }

    private val key = RectF()
    private val ring = RectF()
    private var keyWidth = 0
    private var keyHeight = 0
    private var pressed = false
    private var focused = false

    /** How far the focus ring reaches past the key, in whole pixels. */
    val ringReach: Int = ceil(ringOffset + ringWidth).toInt()

    fun setKeySize(width: Int, height: Int) {
        if (width == keyWidth && height == keyHeight) return
        keyWidth = width
        keyHeight = height
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (keyWidth <= 0 || keyHeight <= 0) return
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        key.set(
            centerX - keyWidth / 2f,
            centerY - keyHeight / 2f,
            centerX + keyWidth / 2f,
            centerY + keyHeight / 2f,
        )
        canvas.drawRoundRect(key, radius, radius, fillPaint)
        if (pressed) canvas.drawRoundRect(key, radius, radius, pressPaint)
        if (focused) {
            val out = ringOffset + ringWidth / 2
            ring.set(key)
            ring.inset(-out, -out)
            canvas.drawRoundRect(ring, radius + out, radius + out, ringPaint)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = android.R.attr.state_pressed in state
        val nowFocused = android.R.attr.state_focused in state
        if (nowPressed == pressed && nowFocused == focused) return false
        pressed = nowPressed
        focused = nowFocused
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
