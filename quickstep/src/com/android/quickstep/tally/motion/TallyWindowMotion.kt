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

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A window's place on screen in Tally's motion, as the prototype's window rects `{x, y, w, h, r}`:
 * a [rect] and its corner [radius], in px. Mutable, so a running animation reuses one per frame.
 */
class TallyWindowRect {
    val rect = RectF()
    var radius = 0f

    fun set(other: TallyWindowRect): TallyWindowRect {
        rect.set(other.rect)
        radius = other.radius
        return this
    }

    fun set(r: RectF, cornerRadius: Float): TallyWindowRect {
        rect.set(r)
        radius = cornerRadius
        return this
    }

    /** Sets this to [a] moved [t] of the way to [b], every edge and the radius alike. */
    fun lerp(a: TallyWindowRect, b: TallyWindowRect, t: Float): TallyWindowRect {
        rect.set(
            a.rect.left + (b.rect.left - a.rect.left) * t,
            a.rect.top + (b.rect.top - a.rect.top) * t,
            a.rect.right + (b.rect.right - a.rect.right) * t,
            a.rect.bottom + (b.rect.bottom - a.rect.bottom) * t,
        )
        radius = a.radius + (b.radius - a.radius) * t
        return this
    }

    override fun toString() = "TallyWindowRect($rect, r=$radius)"
}

/**
 * The prototype's window motion (`apps.js`: `wm.homeRect`, `wm.backMove`, `Win.render`,
 * `wm.homeTarget`) in px on the device's screen. The prototype measures in dp on the FP6's 372 ×
 * 828 dp canvas; the lengths here are its dp values times the display density, and its 34 dp window
 * corner is the device's own window corner radius, as stock reads it.
 */
object TallyWindowMotion {
    /** Return to Home: the window's scale after [HOME_TRAVEL_DP] of upward travel. */
    const val HOME_SCALE = 0.46f
    /** Return to Home: the upward travel over which the window scales to [HOME_SCALE]. */
    const val HOME_TRAVEL_DP = 360f
    /** Return to Home: the window follows the finger sideways at this rate. */
    const val HOME_SIDEWAYS = 0.9f
    /** Return to Home: the corners reached at [HOME_TRAVEL_DP]. */
    const val HOME_RADIUS_DP = 24f
    /** Return to Home: how much further the scale may give past [HOME_TRAVEL_DP]. */
    const val HOME_OVERSCALE = 0.08f
    /** Return to Home: the window's give when the finger goes below where it started. */
    const val DOWN_RUBBER_DP = 24f
    /**
     * The rubber band's coefficient, the token library's `tally_rubber_band_coefficient`, which
     * Launcher reads (see [homeRect]); this copy is for tests and the harness.
     */
    const val RUBBER = 0.35f

    /**
     * Back: the finger's travel that makes a full progress (the prototype's `|dx| / 160`, as WM
     * Shell's `TallyPageMotion.BACK_DISTANCE_DP`).
     */
    const val BACK_DISTANCE_DP = 160f
    /** Back: the window's (or page's) scale at full progress. */
    const val BACK_SCALE = 0.9f
    /** Back: how far the window moves with the finger, away from the edge the swipe starts at. */
    const val BACK_SHIFT_DP = 8f
    /** Back: the share of the finger's vertical travel the window follows. */
    const val BACK_FOLLOW = 0.25f
    /** Back: the most the window follows the finger vertically. */
    const val BACK_FOLLOW_MAX_DP = 40f
    /** Back: the corners at full progress. */
    const val BACK_RADIUS_DP = 20f

    /** Home's dim while an app is in front (`T.home.dim`, 30 % black). */
    const val HOME_DIM = 0.3f
    /** Back: the share of Home's dim that lifts with the progress. */
    const val BACK_DIM_LIFT = 0.6f

    /** The keycap splash's key (`SPLASH_KC`), centred on the window; WM Shell draws it. */
    const val SPLASH_KEYCAP_DP = 88f
    /** Where a window's content is pinned as its rect changes (`.win-content` origin). */
    const val CONTENT_PIVOT_X = 0.5f
    const val CONTENT_PIVOT_Y = 0.32f
    /** Closing: the key comes back over the content from this share of the screen's width... */
    const val KEY_BACK_FROM = 0.51f
    /** ... and covers it from this width on. */
    const val KEY_BACK_TO_DP = 96f

    /** With no key on Home, the window lands on the dock's centre in a key this size... */
    const val DOCK_TARGET_DP = 48f
    /** ... with these corners... */
    const val DOCK_TARGET_RADIUS_DP = 11f
    /** ... and fades out at this rate over the flight (gone at 1 / 1.2 of the way). */
    const val FADE_RATE = 1.2f

    /**
     * A key's neighbours part by this much (the token library's `tally_grid_parting`, which
     * Launcher reads; this copy is for tests and the harness)...
     */
    const val PART_DP = 8f
    /** ... when their centres are within this many units of the key's centre... */
    const val PART_REACH = 1.6f
    /** ... a unit being the key's width, at least this. */
    const val PART_MIN_UNIT_DP = 80f
    /** After a launch, the neighbours settle back from this time on. */
    const val PART_SETTLE_MS = 130L

    /** The prototype's rubber band (`H.rubber`): [over] gives way towards [range]. */
    @JvmStatic
    fun rubber(over: Float, range: Float, coefficient: Float = RUBBER): Float {
        val x = abs(over)
        return sign(over) * (1f - 1f / (x * coefficient / range + 1f)) * range
    }

    /** Return to Home: the progress (0 to 1) of an upward travel of [upPx]. */
    @JvmStatic
    fun homeProgress(upPx: Float, density: Float): Float =
        (max(0f, upPx) / (HOME_TRAVEL_DP * density)).coerceIn(0f, 1f)

    /**
     * Return to Home: where the window is when the finger has gone [upPx] up (negative: down) and
     * [sidewaysPx] to the right, from a window at rest in a [screenW] × [screenH] screen with
     * corners [restRadius] (`wm.homeRect`). The window keeps the screen's shape: it scales from 1
     * to [HOME_SCALE] over [HOME_TRAVEL_DP], its bottom edge follows the finger 1:1, it follows the
     * finger sideways at [HOME_SIDEWAYS], and its corners go from [restRadius] to [HOME_RADIUS_DP].
     * Past [HOME_TRAVEL_DP] the scale gives way by at most [HOME_OVERSCALE], and below the start
     * the window gives way by at most [DOWN_RUBBER_DP], both with the rubber band's
     * [rubberCoefficient] (`tally_rubber_band_coefficient`).
     *
     * The prototype passes the travel past 360 dp to its rubber band in dp against a range in scale
     * (`H.rubber(up - 360, 0.08, 0.35)`), which drops the scale by 0.065 in the first dp. The
     * travel here is first turned into the scale the finger would have reached, so the band has the
     * same range (0.08) and coefficient (0.35) without the jump.
     */
    @JvmStatic
    fun homeRect(
        upPx: Float,
        sidewaysPx: Float,
        screenW: Float,
        screenH: Float,
        density: Float,
        restRadius: Float,
        out: TallyWindowRect,
        rubberCoefficient: Float = RUBBER,
    ): TallyWindowRect {
        val up = max(0f, upPx)
        val travel = HOME_TRAVEL_DP * density
        val k = min(1f, up / travel)
        var scale = 1f + (HOME_SCALE - 1f) * k
        if (up > travel) {
            val further = (up - travel) / travel * (1f - HOME_SCALE)
            scale -= rubber(further, HOME_OVERSCALE, rubberCoefficient)
        }
        val w = screenW * scale
        val h = screenH * scale
        val down = if (upPx < 0f) rubber(-upPx, DOWN_RUBBER_DP * density, rubberCoefficient) else 0f
        val bottom = screenH - up + down
        val left = screenW / 2f + HOME_SIDEWAYS * sidewaysPx - w / 2f
        out.rect.set(left, bottom - h, left + w, bottom)
        out.radius = restRadius + (HOME_RADIUS_DP * density - restRadius) * k
        return out
    }

    /** Return to Home: Home's dim (0 to [HOME_DIM]) at [homeProgress]. */
    @JvmStatic fun homeDim(homeProgress: Float): Float = HOME_DIM * (1f - homeProgress)

    /**
     * Back: the prototype's progress, 0 to 1, for the platform's [progress]. An edge swipe's
     * platform progress is the finger's travel over [distancePx] (the BackTouchTracker's distance:
     * the display's width on a phone), and the prototype's is the same travel over
     * [BACK_DISTANCE_DP]; a Back button's (no edge) is taken as it is. WM Shell's page Back maps it
     * the same way (`TallyPageMotion.backProgress`).
     */
    @JvmStatic
    fun backProgress(progress: Float, fromEdge: Boolean, distancePx: Float, density: Float): Float =
        if (fromEdge) (progress * distancePx / (BACK_DISTANCE_DP * density)).coerceIn(0f, 1f)
        else progress.coerceIn(0f, 1f)

    /**
     * Back at an app's first page (`wm.backMove`): the window scales from 1 to [BACK_SCALE] with
     * [progress], moves [BACK_SHIFT_DP] the way the finger travels ([direction]: +1 for a swipe
     * from the left edge, −1 for one from the right edge, 0 for a Back button), follows the
     * finger's vertical travel [followPx] by [BACK_FOLLOW] (at most [BACK_FOLLOW_MAX_DP]), scaled
     * by the progress, and its corners go from [restRadius] to [BACK_RADIUS_DP].
     */
    @JvmStatic
    fun backRect(
        progress: Float,
        direction: Int,
        followPx: Float,
        screenW: Float,
        screenH: Float,
        density: Float,
        restRadius: Float,
        out: TallyWindowRect,
    ): TallyWindowRect {
        val k = progress.coerceIn(0f, 1f)
        val scale = 1f + (BACK_SCALE - 1f) * k
        val w = screenW * scale
        val h = screenH * scale
        val maxFollow = BACK_FOLLOW_MAX_DP * density
        val dy = (followPx * BACK_FOLLOW).coerceIn(-maxFollow, maxFollow) * k
        val left = (screenW - w) / 2f + direction * BACK_SHIFT_DP * density * k
        val top = (screenH - h) / 2f + dy
        out.rect.set(left, top, left + w, top + h)
        out.radius = restRadius + (BACK_RADIUS_DP * density - restRadius) * k
        return out
    }

    /** Back at an app's first page: Home's dim at [progress]. */
    @JvmStatic fun backDim(progress: Float): Float = HOME_DIM * (1f - BACK_DIM_LIFT * progress)

    /**
     * Closing into a key: how far the key has come back over the window's content (0 to 1), from
     * the window's current [rectWidth]: from [KEY_BACK_FROM] of the screen's width down to
     * [KEY_BACK_TO_DP] (the prototype's splash returning in `Win.render`).
     */
    @JvmStatic
    fun keyBack(rectWidth: Float, screenW: Float, density: Float): Float {
        val from = KEY_BACK_FROM * screenW
        val to = KEY_BACK_TO_DP * density
        if (from <= to) return if (rectWidth <= to) 1f else 0f
        return ((from - rectWidth) / (from - to)).coerceIn(0f, 1f)
    }

    /** Closing with no key on Home: the window's alpha [q] of the way to the dock. */
    @JvmStatic fun fadeOut(q: Float): Float = (1f - FADE_RATE * q).coerceIn(0f, 1f)

    /**
     * The key a window grows from or lands in: [iconBounds] (an app key's icon bounds, whose
     * visible key is [visibleFactor] of them, or 1 for a key drawn to its bounds) with its corners
     * [keyRadius] of the key's side.
     */
    @JvmStatic
    fun keyRect(
        iconBounds: RectF,
        visibleFactor: Float,
        keyRadius: Float,
        out: TallyWindowRect,
    ): TallyWindowRect {
        val cx = iconBounds.centerX()
        val cy = iconBounds.centerY()
        val hw = iconBounds.width() * visibleFactor / 2f
        val hh = iconBounds.height() * visibleFactor / 2f
        out.rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        out.radius = keyRadius * min(out.rect.width(), out.rect.height())
        return out
    }

    /** With no key on Home: the key-sized rect on the dock's centre, at [dockCentreY]. */
    @JvmStatic
    fun dockTarget(
        screenW: Float,
        dockCentreY: Float,
        density: Float,
        out: TallyWindowRect,
    ): TallyWindowRect {
        val half = DOCK_TARGET_DP * density / 2f
        out.rect.set(
            screenW / 2f - half,
            dockCentreY - half,
            screenW / 2f + half,
            dockCentreY + half,
        )
        out.radius = DOCK_TARGET_RADIUS_DP * density
        return out
    }

    /**
     * Cold start: the scale that shows the splash's key (the window's centre, [splashKeyPx] wide)
     * at the size the prototype draws it while the window grows from a key [keySidePx] wide: from
     * the key's size to its own over the launch's progress [q], never smaller than what
     * [TallyWindowContent.fitScale] needs to fill [target].
     */
    @JvmStatic
    fun splashScale(
        keySidePx: Float,
        splashKeyPx: Float,
        q: Float,
        window: RectF,
        target: RectF,
    ): Float {
        val shown = keySidePx + (splashKeyPx - keySidePx) * q
        return max(shown / splashKeyPx, TallyWindowContent.fitScale(window, target))
    }
}

/**
 * Maps a window's content into a [TallyWindowRect] as the prototype does (`Win.render`): the
 * content scales uniformly by [scale] and is cropped to the rect, and the content's point at
 * ([pivotX], [pivotY]) of the window stays at the same point of the rect. Gives the leash's
 * [matrix] (window to screen), its [crop] (in the window) and its corner [layerRadius] (in the
 * window, so that the screen shows the rect's radius). One per animation; no allocation per frame.
 */
class TallyWindowContent {
    val matrix = Matrix()
    val crop = Rect()
    var scale = 1f
        private set

    var layerRadius = 0f
        private set

    /**
     * Maps the content of a window whose bounds on screen are [window] into [target], at [scale]
     * (clamped so the crop never leaves the window).
     */
    fun map(
        window: RectF,
        target: TallyWindowRect,
        scale: Float,
        pivotX: Float = TallyWindowMotion.CONTENT_PIVOT_X,
        pivotY: Float = TallyWindowMotion.CONTENT_PIVOT_Y,
    ): TallyWindowContent {
        val r = target.rect
        val s = max(scale, fitScale(window, r)).coerceAtLeast(MIN_SCALE)
        val cropW = r.width() / s
        val cropH = r.height() / s
        // In the window's own coordinates (its top left at 0, 0).
        val left = pivotX * (window.width() - cropW)
        val top = pivotY * (window.height() - cropH)
        crop.set(
            left.roundToInt(),
            top.roundToInt(),
            (left + cropW).roundToInt(),
            (top + cropH).roundToInt(),
        )
        matrix.setScale(s, s)
        matrix.postTranslate(r.left - left * s, r.top - top * s)
        this.scale = s
        layerRadius = target.radius / s
        return this
    }

    companion object {
        private const val MIN_SCALE = 0.0001f

        /** The smallest scale at which a window's content fills [target] with no gap. */
        @JvmStatic
        fun fitScale(window: RectF, target: RectF): Float =
            if (window.isEmpty) 1f
            else max(target.width() / window.width(), target.height() / window.height())
    }
}
