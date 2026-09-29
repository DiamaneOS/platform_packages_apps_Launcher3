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
import android.graphics.RectF

/**
 * A window's flight between two rects on one spring (the prototype's `Win.flyTo`): one progress `q`
 * from 0 to 1 moves every edge and the corners together. A finger's velocity is handed over as the
 * prototype hands it over: projected onto the line between the rects' centres, in `q` per second,
 * kept between [MIN_Q_VELOCITY] and [MAX_Q_VELOCITY], with the target as a wall (a damping ratio of
 * 1 lands, never passes). Without one, the flight starts with a tap impulse.
 *
 * [com.android.quickstep.util.RectFSpringAnim] runs it in place of its three springs when Tally's
 * motion applies. It plays on a ValueAnimator over the move's settle time, so the animator duration
 * scale applies and Remove animations lands the window at once.
 */
class TallyFlight(private val spring: TallySpring) {
    /** Called with each frame's progress. */
    fun interface Frame {
        fun onFrame(q: Float)
    }

    private var animator: ValueAnimator? = null

    /** The last flight's length at an animator duration scale of 1, once started. */
    var durationMillis = 0L
        private set

    /**
     * The flight's move from [start] to [target] with a finger's velocity ([velocityX], [velocityY]
     * in px per second), or a tap impulse when both are 0.
     */
    fun move(start: RectF, target: RectF, velocityX: Float, velocityY: Float): TallySpring.Move {
        val v0 =
            if (velocityX == 0f && velocityY == 0f) spring.impulse(1.0)
            else handOff(start, target, velocityX, velocityY).toDouble()
        return spring.Move(0.0, 1.0, v0, REST_Q, REST_Q_VELOCITY, wall = true)
    }

    /** Plays [move], calling [frame] each frame and [onEnd] when it lands (or is ended). */
    fun start(move: TallySpring.Move, frame: Frame, onEnd: Runnable) {
        animator?.cancel()
        durationMillis = move.millis
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = move.millis
                addUpdateListener {
                    frame.onFrame(move.valueAtFraction(it.animatedFraction).toFloat())
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (animator === animation) animator = null
                            onEnd.run()
                        }
                    }
                )
                start()
            }
    }

    /** Lands the window at once (a touch while it flies, or the flight being taken over). */
    fun end() {
        animator?.end()
    }

    val isRunning: Boolean
        get() = animator != null

    companion object {
        /** The hand-off's bounds, in q per second (`Win.flyTo`). */
        const val MIN_Q_VELOCITY = -2f
        const val MAX_Q_VELOCITY = 12f
        /** At rest within 0.05 % of the way and 0.005 per second (the prototype's window). */
        const val REST_Q = 0.0005
        const val REST_Q_VELOCITY = 0.005

        /**
         * A finger's velocity ([velocityX], [velocityY]) as the flight's `q` per second from
         * [start] to [target]: its projection on the line between their centres, over that line's
         * length (0 when the centres meet).
         */
        @JvmStatic
        fun handOff(start: RectF, target: RectF, velocityX: Float, velocityY: Float): Float {
            val dx = target.centerX() - start.centerX()
            val dy = target.centerY() - start.centerY()
            val d2 = dx * dx + dy * dy
            val q = if (d2 > 1f) (velocityX * dx + velocityY * dy) / d2 else 0f
            return q.coerceIn(MIN_Q_VELOCITY, MAX_Q_VELOCITY)
        }
    }
}
