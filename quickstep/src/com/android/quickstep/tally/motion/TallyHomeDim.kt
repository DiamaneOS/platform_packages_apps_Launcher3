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
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.android.app.animation.Interpolators
import kotlin.math.roundToInt

/**
 * Home's dim (the prototype's `.hm-dim`): black over everything Launcher draws, wallpaper included,
 * at up to [TallyWindowMotion.HOME_DIM] while an app's window is in front and fading on the fill
 * spring as the window comes and goes. It is a black view of its own in the overlay of Launcher's
 * drag layer, so it is above Home, the dock and All apps and below the floating icon and the app's
 * window. A new dim sets only that view's alpha, a property of its render node, so nothing of
 * Launcher is recorded again for it (a drawable's alpha recorded the drag layer again in every
 * frame of a gesture). With no dim it is not drawn at all.
 *
 * One thing drives it at a time: the Animator that started last ([claim]), or a gesture setting it
 * directly ([set] with no owner). An older Animator still running (a return's fade when an app is
 * launched at once) is then ignored, so two never write it in turn.
 */
class TallyHomeDim(private val host: ViewGroup, private val fill: TallySpring) {
    private val scrim =
        object : View(host.context) {
            // One colour: the alpha applies to it as it draws, with no offscreen layer.
            override fun hasOverlappingRendering() = false
        }
    private var added = false
    private var owner: Any? = null

    /** The dim now, from 0 to 1. */
    var value = 0f
        private set

    init {
        scrim.setBackgroundColor(Color.BLACK)
        scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scrim.alpha = 0f
    }

    /** Makes [who] (a running Animator) the one that drives the dim from now on. */
    fun claim(who: Any) {
        owner = who
    }

    /**
     * Sets the dim to [dim], for [who] if given (ignored unless [who] drives the dim), else at once
     * for whoever calls (a gesture's frame), which takes the dim over from any Animator.
     */
    @JvmOverloads
    fun set(dim: Float, who: Any? = null) {
        if (who == null) owner = null else if (who !== owner) return
        value = dim.coerceIn(0f, 1f)
        val alpha = (value * 255f).roundToInt()
        if (alpha == 0) {
            if (added) {
                host.overlay.remove(scrim)
                added = false
            }
            scrim.alpha = 0f
            return
        }
        if (!added) {
            host.overlay.add(scrim)
            added = true
        }
        if (scrim.width != host.width || scrim.height != host.height) {
            scrim.layout(0, 0, host.width, host.height)
        }
        // In steps of 1/255, as a drawable's alpha.
        scrim.alpha = alpha / 255f
    }

    /**
     * An Animator that takes the dim from where it is when it starts to [target] on the fill spring
     * (no impulse: an effect never overshoots), for a transition to play with its window.
     */
    fun animatorTo(target: Float): Animator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        var move = fill.Move(value.toDouble(), target.toDouble(), 0.0, REST_DELTA, REST_VELOCITY)
        animator.duration = move.millis
        animator.interpolator = Interpolators.LINEAR
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    claim(animation)
                    move =
                        fill.Move(
                            value.toDouble(),
                            target.toDouble(),
                            0.0,
                            REST_DELTA,
                            REST_VELOCITY,
                        )
                }
            }
        )
        animator.addUpdateListener { set(move.valueAtFraction(it.animatedFraction).toFloat(), it) }
        return animator
    }

    /** Clears the dim at once and stops whatever drove it. */
    fun clear() {
        set(0f)
    }

    private companion object {
        /** At rest within one step of the dim's alpha (1/255) and 0.5 per second. */
        const val REST_DELTA = 1.0 / 255.0
        const val REST_VELOCITY = 0.5
    }
}
