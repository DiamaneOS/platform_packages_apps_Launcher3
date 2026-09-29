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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.PointF
import android.graphics.RectF
import android.view.RemoteAnimationTarget
import android.view.animation.Interpolator
import com.android.app.animation.Interpolators
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties
import com.android.quickstep.util.TaskViewSimulator
import com.android.quickstep.util.TransformParams

/**
 * The home gesture's window with Tally's motion (the prototype's `wm.homeRect`): while the finger
 * swipes up from an app, the window follows it (it scales from 1 to 0.46 over the first 360 dp up,
 * its bottom edge stays at the finger, it follows the finger sideways at 0.9, its corners go from
 * the device's to 24 dp), instead of shrinking into its Recents card as stock's does. When Recents
 * joins in (stock attaches Recents when the finger pauses or a quick switch starts) the window
 * settles into its card on the stone spring, as the prototype's window settles into its card when
 * Recents arms; stock's TaskViewSimulator still places the card.
 *
 * What the gesture leads to, and when, stays stock's (AbsSwipeUpHandler decides Home, Recents, a
 * quick switch or back to the app). On release this only changes the motion: Home flies from where
 * the window is ([current]); back to the app returns on the slab spring with the finger's velocity
 * ([returnMove]); Recents and a quick switch finish settling into the card with stock's own end
 * animation ([finishInCard]).
 *
 * The recents input consumer still takes every touch while the window moves; nothing here handles
 * input. One per gesture; nothing is allocated per frame.
 */
class TallySwipeWindow(
    private val stone: TallySpring,
    private val slab: TallySpring,
    private val screenW: Float,
    private val screenH: Float,
    private val density: Float,
    private val restRadius: Float,
    private val rubber: Float,
    private val reapply: Runnable,
) {
    private val home = TallyWindowRect()
    private val card = TallyWindowRect()
    private val window = RectF(0f, 0f, screenW, screenH)
    private val full = TallyWindowRect().set(window, restRadius)
    private val content = TallyWindowContent()

    /** The window as it is shown now (on screen). */
    val current = TallyWindowRect()

    private var upPx = 0f
    private var sidewaysPx = 0f
    private var blend = 0f
    private var attached = false
    private var blendAnimator: ValueAnimator? = null
    private var finishAnimation: ValueAnimator? = null
    private var finishFrom = 0f
    private var tvs: TaskViewSimulator? = null

    /** Builds the window's surface: stock's layers, Tally's place. */
    private val proxy =
        TransformParams.BuilderProxy {
            builder: SurfaceProperties,
            app: RemoteAnimationTarget,
            params ->
            tvs?.onBuildTargetParams(builder, app, params)
            builder
                .setMatrix(content.matrix)
                .setWindowCrop(content.crop)
                .setCornerRadius(content.layerRadius)
        }

    /** The finger's travel: [up] px up from where it started, [sideways] px to the right. */
    fun setFinger(up: Float, sideways: Float) {
        upPx = up
        sidewaysPx = sideways
    }

    /** Home's progress (0 to 1) for the finger's travel, for Home's dim when it goes Home. */
    val homeProgress: Float
        get() = TallyWindowMotion.homeProgress(upPx, density)

    /**
     * Recents joined the window ([isAttached]) or left it: the window settles into its card, or
     * back to the finger, on the stone spring ([animate]), redrawing through [reapply] each frame.
     */
    fun setAttached(isAttached: Boolean, animate: Boolean) {
        if (attached == isAttached && (blendAnimator != null || blend == target())) return
        attached = isAttached
        blendAnimator?.cancel()
        blendAnimator = null
        val to = target()
        if (!animate || blend == to) {
            blend = to
            return
        }
        val move = stone.Move(blend.toDouble(), to.toDouble(), 0.0, REST_BLEND, REST_BLEND_VELOCITY)
        blendAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = move.millis
                interpolator = Interpolators.LINEAR
                addUpdateListener {
                    blend = move.valueAtFraction(it.animatedFraction).toFloat()
                    reapply.run()
                }
                addListener(endListener())
                start()
            }
    }

    private fun target() = if (attached) 1f else 0f

    /**
     * Places the window for this frame from the finger and from [simulator]'s card (already
     * computed for the frame), and applies it through [params].
     */
    fun apply(simulator: TaskViewSimulator, params: TransformParams) {
        tvs = simulator
        val finish = finishAnimation
        if (finish != null) blend = finishFrom + (1f - finishFrom) * finish.animatedFraction
        TallyWindowMotion.homeRect(
            upPx,
            sidewaysPx,
            screenW,
            screenH,
            density,
            restRadius,
            home,
            rubber,
        )
        if (blend > 0f) {
            card.set(simulator.currentRect, simulator.scaledCornerRadius)
            current.lerp(home, card, blend)
        } else {
            current.set(home)
        }
        content.map(window, current, TallyWindowContent.fitScale(window, current.rect))
        params.applySurfaceParams(params.createSurfaceParams(proxy))
    }

    /**
     * Back to the app: the move of stock's shift from where it is to 0, as a curve of the slab
     * spring with the finger's [velocity] (px per ms) handed over as the prototype's `flyTo` does.
     */
    fun returnMove(velocity: PointF): TallySpring.Move {
        val q = TallyFlight.handOff(current.rect, full.rect, velocity.x * 1000f, velocity.y * 1000f)
        val v0 = if (q == 0f) slab.impulse(1.0) else q.toDouble()
        return slab.Move(0.0, 1.0, v0, TallyFlight.REST_Q, TallyFlight.REST_Q_VELOCITY, wall = true)
    }

    /**
     * Recents or a quick switch: the window finishes settling into its card with stock's own end
     * animation [endAnimation], so both land together.
     */
    fun finishInCard(endAnimation: ValueAnimator) {
        blendAnimator?.cancel()
        blendAnimator = null
        attached = true
        if (blend >= 1f) return
        finishFrom = blend
        finishAnimation = endAnimation
    }

    /** The gesture is over: stops settling. */
    fun cancel() {
        blendAnimator?.cancel()
        blendAnimator = null
        finishAnimation = null
        tvs = null
    }

    private fun endListener() =
        object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (blendAnimator === animation) blendAnimator = null
            }
        }

    companion object {
        /** The blend at rest within 0.1 % and 0.01 per second. */
        private const val REST_BLEND = 0.001
        private const val REST_BLEND_VELOCITY = 0.01

        /** An interpolator that plays [move] (from 0 to 1) over an Animator of its length. */
        @JvmStatic
        fun curve(move: TallySpring.Move): Interpolator = Interpolator {
            move.progressAtFraction(it)
        }
    }
}
