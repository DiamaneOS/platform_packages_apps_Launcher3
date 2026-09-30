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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.animation.AnimationUtils
import com.android.launcher3.R
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR
import com.android.launcher3.tally.lamp.TallyLampState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
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
 * Its LED ([TallyKeycapLed]) sits in the key's top-right corner and moves with the key.
 *
 * The highlight, the skirt and the press layer are filled shapes built once for each key size and
 * icon shape ([Relief]) and shared by every key of that size, not the key's shape clipped for every
 * key in every frame: on the GPU each clip to the keycap's curves renders a clip mask, again on
 * every frame, which cost about 13 % of Launcher's RenderThread in All apps and the Home swipe. A
 * key at rest draws its relief from a bitmap of those shapes ([RestImage]), drawn once in software
 * and shared by every key of that size and colours: the GPU keeps it as a texture, where it would
 * draw the shapes' curves into its path atlas again in every frame.
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
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val highlightPx: Float
    private val skirtFraction: Float
    private val skirtPressedFraction: Float
    private val dockSkirtPx: Float
    private val dockSkirtPressedPx: Float
    private val stiffness: Float
    private val impulse: Float

    private val ledPainter = TallyKeycapLed(context)
    private var led: TallyLampState? = null

    /** This key's relief, from [reliefOf]; null until it is first drawn. */
    private var relief: Relief? = null
    /** This key's relief at rest as a bitmap, from [restImageOf]; null until drawn at rest. */
    private var restImage: RestImage? = null
    /** A skirt between rest and pressed in, built for each frame while the key moves. */
    private val movingSkirt = Path()
    private val movedKey = Path()

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

    /**
     * Sets the key's LED: live or failed, or null for none. Returns whether that changes anything,
     * so the host draws again.
     */
    fun setLed(state: TallyLampState?): Boolean {
        if (led == state) return false
        led = state
        return true
    }

    /** The key's LED, as last set. */
    val ledState: TallyLampState?
        get() = led

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
     * [iconBounds] at [iconScale] (about its centre), and its LED unless [hideLed] (the dot's
     * forced-hidden state, for example while the key is dragged). [dock] keys have the dock's
     * deeper skirt.
     */
    fun draw(
        canvas: Canvas,
        iconBounds: Rect,
        iconScale: Float,
        shape: ShapeDelegate,
        dock: Boolean,
        hideLed: Boolean,
    ) {
        // The visible key: adaptive icons are drawn at the visible area factor of their bounds.
        val size = Math.round(iconBounds.width() * ICON_VISIBLE_AREA_FACTOR)
        if (size <= 0) return
        val relief = reliefFor(shape, size, dock)
        val skirt = skirtAt(press, size, dock)
        // At rest or pressed in, the skirt is the relief's; moving, it is built for this frame.
        val skirtPath =
            when {
                !relief.built -> null
                skirt == relief.restSkirtPx -> relief.restSkirt
                skirt == relief.pressedSkirtPx -> relief.pressedSkirt
                relief.band(-skirt, movingSkirt, movedKey) -> movingSkirt
                else -> null
            }

        // At rest, unpressed and unscaled, the relief is its bitmap, put on the pixel grid where
        // the shapes would be drawn: the same pixels.
        val left = iconBounds.exactCenterX() - size / 2f
        val top = iconBounds.exactCenterY() - size / 2f
        val image =
            if (skirtPath != null && press == 0f && !pressed && iconScale == 1f) {
                restImageFor(relief, left, top)
            } else {
                null
            }
        if (image != null) {
            canvas.drawBitmap(
                image.bitmap,
                floor(left) - IMAGE_MARGIN,
                floor(top) - IMAGE_MARGIN,
                imagePaint,
            )
        }

        val count = canvas.save()
        canvas.translate(iconBounds.exactCenterX(), iconBounds.exactCenterY())
        canvas.scale(iconScale, iconScale)
        canvas.translate(-size / 2f, -size / 2f)
        when {
            image != null -> Unit // Drawn above, on the pixel grid.
            skirtPath != null -> drawShapes(canvas, relief, skirtPath, skirt)
            else -> drawClipped(canvas, relief.key, skirt, size)
        }
        if (!hideLed) ledPainter.draw(canvas, 0f, 0f, size.toFloat(), led)
        canvas.restoreToCount(count)
    }

    /** Draws the relief's shapes, in the key's own coordinates, with [skirtPath] as its skirt. */
    private fun drawShapes(canvas: Canvas, relief: Relief, skirtPath: Path, skirt: Float) {
        if (highlightPx != 0f) canvas.drawPath(relief.highlight, highlightPaint)
        if (skirt != 0f) canvas.drawPath(skirtPath, shadePaint)
        if (pressed) canvas.drawPath(relief.face, pressPaint)
    }

    /**
     * The bitmap of [relief] at rest for a key whose visible square starts at ([left], [top]) in
     * the view. This key's own while those and its colours stay the same.
     */
    private fun restImageFor(relief: Relief, left: Float, top: Float): RestImage {
        val highlight = highlightPaint.color
        val shade = shadePaint.color
        restImage?.let { if (it.fits(relief, highlight, shade, left, top)) return it }
        return restImageOf(relief, highlight, shade, left, top) { drawRestImage(relief, left, top) }
            .also { restImage = it }
    }

    /**
     * Draws [relief] at rest, with the shapes and paints a key at rest draws on screen, into a new
     * bitmap in software, as it lies in the view at ([left], [top]) (Skia's anti-aliasing of a
     * curve can depend on where it lies), with [IMAGE_MARGIN] empty pixels around it. It is drawn
     * to be put at the whole pixel left of and above that less the margin. The bitmap is immutable,
     * so the GPU uploads it once and keeps it as a texture.
     */
    private fun drawRestImage(relief: Relief, left: Float, top: Float): Bitmap {
        // Where it lies in the view, moved by whole pixels only to leave the margin.
        val x = if (left >= IMAGE_MARGIN) left else left - floor(left) + IMAGE_MARGIN
        val y = if (top >= IMAGE_MARGIN) top else top - floor(top) + IMAGE_MARGIN
        val cropX = floor(x).toInt() - IMAGE_MARGIN
        val cropY = floor(y).toInt() - IMAGE_MARGIN
        val width = ceil(x + relief.size).toInt() + IMAGE_MARGIN - cropX
        val height = ceil(y + relief.size).toInt() + IMAGE_MARGIN - cropY
        val drawn = Bitmap.createBitmap(cropX + width, cropY + height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(drawn)
        canvas.translate(x, y)
        drawShapes(canvas, relief, relief.restSkirt, relief.restSkirtPx)
        val cut = Bitmap.createBitmap(drawn, cropX, cropY, width, height)
        val image = cut.copy(Bitmap.Config.ARGB_8888, false)
        cut.recycle()
        drawn.recycle()
        // Drawn pixel for pixel on any canvas, never scaled for a density.
        image.density = Bitmap.DENSITY_NONE
        image.prepareToDraw()
        return image
    }

    /** The relief of a key of [size] in [shape], this key's own while those stay the same. */
    private fun reliefFor(shape: ShapeDelegate, size: Int, dock: Boolean): Relief {
        val restSkirt = skirtAt(0f, size, dock)
        val pressedSkirt = skirtAt(1f, size, dock)
        relief?.let { if (it.fits(shape, size, highlightPx, restSkirt, pressedSkirt)) return it }
        return reliefOf(shape, size, highlightPx, restSkirt, pressedSkirt).also { relief = it }
    }

    /** The skirt's height at [press] (0 at rest to 1 pressed in). */
    private fun skirtAt(press: Float, size: Int, dock: Boolean): Float =
        if (dock) lerp(dockSkirtPx, dockSkirtPressedPx, press)
        else size * lerp(skirtFraction, skirtPressedFraction, press)

    /**
     * Draws the relief the clipped way, which covers what the relief's paths cover: only for a
     * relief whose path operations failed.
     */
    private fun drawClipped(canvas: Canvas, key: Path, skirt: Float, size: Int) {
        val count = canvas.save()
        canvas.clipPath(key)
        drawEdge(canvas, key, highlightPx, highlightPaint, size)
        drawEdge(canvas, key, -skirt, shadePaint, size)
        if (pressed) canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), pressPaint)
        canvas.restoreToCount(count)
    }

    /**
     * Fills the part of the key that the key moved by [dy] does not cover: a band along the top
     * edge for a positive [dy], along the bottom edge for a negative one.
     */
    private fun drawEdge(canvas: Canvas, key: Path, dy: Float, paint: Paint, size: Int) {
        if (dy == 0f) return
        val count = canvas.save()
        canvas.translate(0f, dy)
        canvas.clipOutPath(key)
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

    /**
     * A key's relief for one icon shape (the same object), key size, highlight and skirts, as
     * filled paths: [face], the key's visible square cut to its shape (where the press layer
     * covers), and the bands of it that the key moved down by the highlight, or up by a skirt,
     * leaves uncovered. It is what clipping to the key and then outside the moved key covers.
     */
    private class Relief(
        val shape: ShapeDelegate,
        val size: Int,
        val highlightPx: Float,
        val restSkirtPx: Float,
        val pressedSkirtPx: Float,
    ) {
        val key: Path = shape.getPath(Rect(0, 0, size, size))
        val face = Path()
        val highlight = Path()
        val restSkirt = Path()
        val pressedSkirt = Path()
        /** Whether the paths were built; if not, the key is drawn clipped. */
        val built: Boolean

        init {
            val square = Path()
            square.addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
            val moved = Path()
            built =
                face.op(key, square, Path.Op.INTERSECT) &&
                    band(highlightPx, highlight, moved) &&
                    band(-restSkirtPx, restSkirt, moved) &&
                    band(-pressedSkirtPx, pressedSkirt, moved)
        }

        fun fits(
            shape: ShapeDelegate,
            size: Int,
            highlightPx: Float,
            restSkirtPx: Float,
            pressedSkirtPx: Float,
        ) =
            shape === this.shape &&
                size == this.size &&
                highlightPx == this.highlightPx &&
                restSkirtPx == this.restSkirtPx &&
                pressedSkirtPx == this.pressedSkirtPx

        /**
         * Sets [out] to the band of [face] that the key moved down by [dy] (up for a negative one)
         * does not cover, using [moved]; returns whether that worked.
         */
        fun band(dy: Float, out: Path, moved: Path): Boolean {
            if (dy == 0f) {
                out.reset()
                return true
            }
            key.offset(0f, dy, moved)
            return out.op(face, moved, Path.Op.DIFFERENCE)
        }
    }

    /**
     * A relief at rest as a bitmap, for its colours and its place in the view (keys of one kind all
     * have the same). Its relief's shape, sizes and density, and the theme's colours, are all part
     * of what it is for: a key whose shape, size, theme or configuration changes draws another one.
     */
    private class RestImage(
        val relief: Relief,
        val highlightColor: Int,
        val shadeColor: Int,
        val left: Float,
        val top: Float,
        val bitmap: Bitmap,
    ) {
        fun fits(relief: Relief, highlight: Int, shade: Int, left: Float, top: Float) =
            relief.fits(
                this.relief.shape,
                this.relief.size,
                this.relief.highlightPx,
                this.relief.restSkirtPx,
                this.relief.pressedSkirtPx,
            ) &&
                highlight == highlightColor &&
                shade == shadeColor &&
                left == this.left &&
                top == this.top
    }

    private companion object {
        /**
         * The reliefs last drawn, the most recent last: a handful of key sizes (Home, the dock,
         * folders, All apps), and a few more while the grid or icon shape changes.
         */
        const val MAX_RELIEFS = 8
        private val reliefs = ArrayList<Relief>(MAX_RELIEFS)

        /**
         * The bitmaps of reliefs at rest last drawn, the most recent last, at most [MAX_IMAGES] and
         * [MAX_IMAGE_BYTES] (on the FP6 about 115 kB each for Home and the dock, 100 kB for All
         * apps, in each theme).
         */
        const val MAX_IMAGES = 8
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private val restImages = ArrayList<RestImage>(MAX_IMAGES)
        /** The empty pixels around a bitmap's relief, for its edges' anti-aliasing. */
        const val IMAGE_MARGIN = 1

        /** The bitmap for these, drawn with [draw] when there is none: shared by every key. */
        fun restImageOf(
            relief: Relief,
            highlight: Int,
            shade: Int,
            left: Float,
            top: Float,
            draw: () -> Bitmap,
        ): RestImage =
            synchronized(restImages) {
                val i = restImages.indexOfFirst { it.fits(relief, highlight, shade, left, top) }
                val image =
                    if (i >= 0) restImages.removeAt(i)
                    else RestImage(relief, highlight, shade, left, top, draw())
                restImages.add(image)
                var bytes = restImages.sumOf { it.bitmap.allocationByteCount }
                while (
                    restImages.size > MAX_IMAGES || (bytes > MAX_IMAGE_BYTES && restImages.size > 1)
                ) {
                    bytes -= restImages.removeAt(0).bitmap.allocationByteCount
                }
                image
            }

        /** The relief for these, shared by every key of that size and shape. */
        fun reliefOf(
            shape: ShapeDelegate,
            size: Int,
            highlightPx: Float,
            restSkirtPx: Float,
            pressedSkirtPx: Float,
        ): Relief =
            synchronized(reliefs) {
                val i =
                    reliefs.indexOfFirst {
                        it.fits(shape, size, highlightPx, restSkirtPx, pressedSkirtPx)
                    }
                val relief =
                    if (i >= 0) reliefs.removeAt(i)
                    else Relief(shape, size, highlightPx, restSkirtPx, pressedSkirtPx)
                if (reliefs.size == MAX_RELIEFS) reliefs.removeAt(0)
                reliefs.add(relief)
                relief
            }

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
