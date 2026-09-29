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
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.android.app.animation.Interpolators
import kotlin.math.roundToInt

/**
 * Home's dim (the prototype's `.hm-dim`): black over everything Launcher draws, wallpaper included,
 * at up to [TallyWindowMotion.HOME_DIM] while an app's window is in front and fading on the fill
 * spring as the window comes and goes. It lies in the overlay of Launcher's drag layer, so it is
 * above Home, the dock and All apps and below the floating icon and the app's window. With no dim
 * it is not drawn at all.
 *
 * One thing drives it at a time: the Animator that started last ([claim]), or a gesture setting it
 * directly ([set] with no owner). An older Animator still running (a return's fade when an app is
 * launched at once) is then ignored, so two never write it in turn.
 */
class TallyHomeDim(private val host: View, private val fill: TallySpring) {
    private val drawable = ColorDrawable(Color.BLACK).apply { alpha = 0 }
    private var added = false
    private var owner: Any? = null

    /** The dim now, from 0 to 1. */
    var value = 0f
        private set

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
                host.overlay.remove(drawable)
                added = false
            }
            drawable.alpha = 0
            return
        }
        if (!added) {
            drawable.setBounds(0, 0, host.width, host.height)
            host.overlay.add(drawable)
            added = true
        } else if (drawable.bounds.right != host.width || drawable.bounds.bottom != host.height) {
            drawable.setBounds(0, 0, host.width, host.height)
        }
        drawable.alpha = alpha
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
