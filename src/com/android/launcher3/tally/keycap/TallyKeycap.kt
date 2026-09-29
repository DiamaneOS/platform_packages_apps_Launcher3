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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.animation.AnimationUtils
import com.android.launcher3.R
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Draws an app icon as a Tally keycap: a key, not a tile. Over the icon, which the icon shape
 * already cuts to the key, it lays a highlight along the top edge (tally_keycap_highlight, 1 dp)
 * and a skirt along the bottom edge (tally_keycap_shade, 5.5 % of the key, 5 dp on dock keys), both
 * following the shape's corners as the prototype's inset shadows do, with no blur.
 *
 * A press sends the key in, as the prototype's `T.pressKey`: it scales to 97 % on the pebble spring
 * with a tap impulse, its skirt compresses to 2 % (2 dp on the dock) and the press layer
 * (tally_press) covers its face; letting go brings it back on the same spring. With animations off
 * (Remove animations) the key jumps.
 *
 * The host draws the icon itself with the icon's own press scale turned off. It calls [setPressed]
 * when its pressed state changes, and after drawing the icon it calls [draw] with the icon's bounds
 * and the scale the icon was drawn at, then [advance] for the scale of the next frame: while that
 * differs, or the key still moves ([isMoving]), it draws another frame. Frames run only while the
 * key moves.
 */
class TallyKeycap(context: Context) {
    private val highlightPaint = fillPaint(context.getColor(R.color.tally_keycap_highlight))
    private val shadePaint = fillPaint(context.getColor(R.color.tally_keycap_shade))
    private val pressPaint = fillPaint(context.getColorStateList(R.color.tally_press).defaultColor)

    private val highlightPx: Float
    private val skirtFraction: Float
    private val skirtPressedFraction: Float
    private val dockSkirtPx: Float
    private val dockSkirtPressedPx: Float
    private val stiffness: Float
    private val impulse: Float

    private val keyPath = Path()
    private var pathShape: ShapeDelegate? = null
    private var pathSize = -1

    /** The press, from 0 (at rest) to 1 (pressed in). */
    private var press = 0f
    private var pressVelocity = 0f
    private var pressTarget = 0f
    private var lastMillis = 0L
    private var pressed = false

    init {
        val res = context.resources
        highlightPx = res.getDimension(R.dimen.tally_keycap_highlight_height)
        skirtFraction = res.getFraction(R.fraction.tally_keycap_skirt, 1, 1)
        skirtPressedFraction = res.getFraction(R.fraction.tally_keycap_skirt_pressed, 1, 1)
        dockSkirtPx = res.getDimension(R.dimen.tally_dock_skirt)
        dockSkirtPressedPx = res.getDimension(R.dimen.tally_dock_skirt_pressed)
        stiffness = res.getFloat(R.dimen.tally_spring_pebble_stiffness)
        impulse = res.getFloat(R.dimen.tally_motion_tap_impulse)
    }

    /** Whether the key is moving in or out. */
    val isMoving: Boolean
        get() = press != pressTarget || pressVelocity != 0f

    /**
     * The key is pressed (or let go). Returns whether that changes anything, so the host draws
     * again.
     */
    fun setPressed(isPressed: Boolean): Boolean {
        if (pressed == isPressed) return false
        pressed = isPressed
        val now = AnimationUtils.currentAnimationTimeMillis()
        step(now)
        pressTarget = if (isPressed) 1f else 0f
        if (!animationsOn()) {
            jump()
        } else if (isPressed) {
            // The tap impulse: a start speed towards the target of 0.6 · √k · the distance.
            pressVelocity = impulse * sqrt(stiffness) * (pressTarget - press)
        }
        lastMillis = now
        return true
    }

    /** Puts the key at rest at once (a recycled view, a drag starting). */
    fun reset() {
        pressed = false
        pressTarget = 0f
        jump()
    }

    /** Moves the key to the current frame; returns the scale its icon draws at next. */
    fun advance(): Float {
        step(AnimationUtils.currentAnimationTimeMillis())
        return 1f - PRESS_DEPTH * press
    }

    /**
     * Draws the key's relief (and, while pressed, its press layer) over an icon drawn in
     * [iconBounds] at [iconScale] (about its centre). [dock] keys have the dock's deeper skirt.
     */
    fun draw(
        canvas: Canvas,
        iconBounds: Rect,
        iconScale: Float,
        shape: ShapeDelegate,
        dock: Boolean,
    ) {
        // The visible key: adaptive icons are drawn at the visible area factor of their bounds.
        val size = Math.round(iconBounds.width() * ICON_VISIBLE_AREA_FACTOR)
        if (size <= 0) return
        if (size != pathSize || shape !== pathShape) {
            keyPath.reset()
            keyPath.set(shape.getPath(Rect(0, 0, size, size)))
            pathSize = size
            pathShape = shape
        }
        val skirt =
            if (dock) lerp(dockSkirtPx, dockSkirtPressedPx, press)
            else size * lerp(skirtFraction, skirtPressedFraction, press)

        val count = canvas.save()
        canvas.translate(iconBounds.exactCenterX(), iconBounds.exactCenterY())
        canvas.scale(iconScale, iconScale)
        canvas.translate(-size / 2f, -size / 2f)
        canvas.clipPath(keyPath)
        drawEdge(canvas, highlightPx, highlightPaint, size)
        drawEdge(canvas, -skirt, shadePaint, size)
        if (pressed) canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), pressPaint)
        canvas.restoreToCount(count)
    }

    /**
     * Fills the part of the key that the key moved by [dy] does not cover: a band along the top
     * edge for a positive [dy], along the bottom edge for a negative one.
     */
    private fun drawEdge(canvas: Canvas, dy: Float, paint: Paint, size: Int) {
        if (dy == 0f) return
        val count = canvas.save()
        canvas.translate(0f, dy)
        canvas.clipOutPath(keyPath)
        canvas.translate(0f, -dy)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        canvas.restoreToCount(count)
    }

    private fun step(nowMillis: Long) {
        if (!isMoving) {
            lastMillis = nowMillis
            return
        }
        if (!animationsOn()) {
            jump()
            lastMillis = nowMillis
            return
        }
        if (nowMillis <= lastMillis) return
        val t = (nowMillis - lastMillis) / (1000.0 * ValueAnimator.getDurationScale())
        lastMillis = nowMillis
        // The pebble spring is critically damped (ratio 1): x(t) = (x0 + (v0 + w x0) t) e^(-w t).
        val w = sqrt(stiffness.toDouble())
        val x0 = (press - pressTarget).toDouble()
        val v0 = pressVelocity.toDouble()
        val b = v0 + w * x0
        val e = exp(-w * t)
        val x = (x0 + b * t) * e
        val v = (v0 - w * b * t) * e
        if (abs(x) < REST_DELTA && abs(v) < REST_VELOCITY) {
            jump()
        } else {
            press = (pressTarget + x).toFloat()
            pressVelocity = v.toFloat()
        }
    }

    private fun jump() {
        press = pressTarget
        pressVelocity = 0f
    }

    private companion object {
        /** A pressed key draws at 97 %. */
        const val PRESS_DEPTH = 0.03f
        /** At rest within these, in press units (0 to 1) and press units per second. */
        const val REST_DELTA = 0.001
        const val REST_VELOCITY = 0.01

        fun animationsOn(): Boolean =
            ValueAnimator.areAnimatorsEnabled() && ValueAnimator.getDurationScale() > 0f

        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        fun fillPaint(color: Int) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }
    }
}
