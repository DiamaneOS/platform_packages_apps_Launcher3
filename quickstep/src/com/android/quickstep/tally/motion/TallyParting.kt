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
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Reorderable
import com.android.launcher3.Utilities
import com.android.launcher3.apppairs.AppPairIcon
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.util.MultiTranslateDelegate.INDEX_TALLY_PARTING
import kotlin.math.hypot
import kotlin.math.max

/**
 * A key's neighbours making room (the prototype's `T.part` and `T.unpart`): as a window grows out
 * of a key, the keys whose centres are within [TallyWindowMotion.PART_REACH] units of its centre (a
 * unit is the key's width, at least [TallyWindowMotion.PART_MIN_UNIT_DP]) move
 * [TallyWindowMotion.PART_DP] straight away from it on the stone spring with a tap impulse, and
 * settle back on stone ([TallyWindowMotion.PART_SETTLE_MS] later for a launch, as the window lands
 * for a return). Only keys move (apps, folders, app pairs), never widgets, and never the key
 * itself.
 *
 * The neighbours are the key's siblings, and on Home the keys of the visible pages and the dock
 * together (the prototype's `.home`): Home's grid, the dock, an open folder's page, All apps and
 * its search results. They move through their own translation channel ([INDEX_TALLY_PARTING]), so
 * nothing else that moves a key is disturbed. All of them move by one progress `p` (0 at rest, 1
 * parted), since they start together on the same spring. With animations removed they never visibly
 * move.
 */
class TallyParting(private val stone: TallySpring, private val density: Float) {
    private val keys = ArrayList<Reorderable>()
    private var directions = FloatArray(0)
    private val coord = FloatArray(2)
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /**
     * Finds [source]'s neighbours below [root] (the drag layer), with [extraContainers] searched as
     * well as [source]'s own parent (on Home: the visible pages and the dock). Puts any keys still
     * parted back at once first. Returns whether there is a neighbour to move.
     */
    fun collect(source: View, root: View, extraContainers: List<ViewGroup>): Boolean {
        reset()
        if (!source.isAttachedToWindow) return false
        centreOf(source, root)
        val cx = coord[0]
        val cy = coord[1]
        val reach = PART_REACH * max(source.width.toFloat(), PART_MIN_UNIT_DP * density)
        val parent = source.parent as? ViewGroup
        if (parent != null) add(parent, source, root, cx, cy, reach)
        for (container in extraContainers) {
            if (container !== parent) add(container, source, root, cx, cy, reach)
        }
        return keys.isNotEmpty()
    }

    /**
     * Plays a launch's parting: out with a tap impulse, then from [settleAfterMs] back to rest from
     * wherever the keys are (keeping their speed, as `T.unpart` does). One Animator of the whole
     * move, for the launch to run next to its window.
     */
    fun launchAnimator(settleAfterMs: Long = PART_SETTLE_MS): Animator {
        val out = stone.Move(0.0, 1.0, stone.impulse(1.0), REST_DELTA, REST_VELOCITY)
        val t1 = settleAfterMs / 1000.0
        val back = stone.Move(out.valueAt(t1), 0.0, out.velocityAt(t1), REST_DELTA, REST_VELOCITY)
        val total = settleAfterMs + back.millis
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            addUpdateListener {
                val t = it.animatedFraction * total / 1000.0
                set((if (t < t1) out.valueAt(t) else back.valueAt(t - t1)).toFloat())
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        animator = animation as ValueAnimator
                        currentMove = null
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        // A launch cut short (a new gesture takes over): the keys go home at once.
                        if (animator === animation) {
                            animator = null
                            set(0f)
                            keys.clear()
                        }
                    }
                }
            )
            addListener(endListener())
        }
    }

    /** A return's parting: the keys move out with a tap impulse (`goHome`'s `T.part`). */
    fun part() {
        if (keys.isEmpty()) return
        val from = progress.toDouble()
        run(stone.Move(from, 1.0, stone.impulse(1.0 - from), REST_DELTA, REST_VELOCITY))
    }

    /**
     * The window has landed: the keys settle back from where they are, keeping their speed
     * (`wm.landed`'s `T.unpart`).
     */
    fun settle() {
        if (keys.isEmpty()) return
        val velocity = currentVelocity
        animator?.cancel()
        run(stone.Move(progress.toDouble(), 0.0, velocity, REST_DELTA, REST_VELOCITY))
    }

    /** Puts every key back at once and forgets them. */
    fun reset() {
        animator?.cancel()
        animator = null
        set(0f)
        keys.clear()
    }

    private var currentMove: TallySpring.Move? = null
    private val currentVelocity: Double
        get() {
            val move = currentMove ?: return 0.0
            val a = animator ?: return 0.0
            return move.velocityAtFraction(a.animatedFraction)
        }

    private fun run(move: TallySpring.Move) {
        animator?.cancel()
        currentMove = move
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = move.millis
                addUpdateListener { set(move.valueAtFraction(it.animatedFraction).toFloat()) }
                addListener(endListener())
                start()
            }
    }

    private fun endListener() =
        object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) {
                    animator = null
                    currentMove = null
                }
                // A move played to its end leaves the keys where it ends; back at rest, they are
                // forgotten.
                if (!cancelled && progress == 0f && animator == null) keys.clear()
            }
        }

    private fun set(p: Float) {
        progress = p
        val amount = p * PART_DP * density
        for (i in keys.indices) {
            keys[i]
                .translateDelegate
                .setTranslation(
                    INDEX_TALLY_PARTING,
                    directions[2 * i] * amount,
                    directions[2 * i + 1] * amount,
                )
        }
    }

    private fun add(
        container: ViewGroup,
        source: View,
        root: View,
        cx: Float,
        cy: Float,
        reach: Float,
    ) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === source || child.visibility != View.VISIBLE || !isKey(child)) continue
            centreOf(child, root)
            if (!direction(cx, cy, coord[0], coord[1], reach, coord)) continue
            if (directions.size < 2 * (keys.size + 1)) {
                directions = directions.copyOf(max(8, 2 * directions.size))
            }
            directions[2 * keys.size] = coord[0]
            directions[2 * keys.size + 1] = coord[1]
            keys.add(child as Reorderable)
        }
    }

    /** The centre of [view] in [root]'s coordinates, where it is laid out (no transforms). */
    private fun centreOf(view: View, root: View) {
        coord[0] = view.width / 2f
        coord[1] = view.height / 2f
        Utilities.getDescendantCoordRelativeToAncestor(view, root, coord, false, true)
    }

    companion object {
        private const val PART_DP = TallyWindowMotion.PART_DP
        private const val PART_REACH = TallyWindowMotion.PART_REACH
        private const val PART_MIN_UNIT_DP = TallyWindowMotion.PART_MIN_UNIT_DP
        private const val PART_SETTLE_MS = TallyWindowMotion.PART_SETTLE_MS

        /** At rest within 0.01 dp and 0.5 dp/s, as the prototype's nudges (in units of 8 dp). */
        private const val REST_DELTA = 0.01 / TallyWindowMotion.PART_DP
        private const val REST_VELOCITY = 0.5 / TallyWindowMotion.PART_DP

        /** Keys move; widgets and anything else stay. */
        @JvmStatic
        fun isKey(view: View): Boolean =
            view is BubbleTextView || view is FolderIcon || view is AppPairIcon

        /**
         * The direction a key at ([x], [y]) moves in, away from a key at ([cx], [cy]), as a unit
         * vector in [out], or false if it stays (the same place, or out of [reach]).
         */
        @JvmStatic
        fun direction(
            cx: Float,
            cy: Float,
            x: Float,
            y: Float,
            reach: Float,
            out: FloatArray,
        ): Boolean {
            val d = hypot(x - cx, y - cy)
            if (d < 1f || d > reach) return false
            out[0] = (x - cx) / d
            out[1] = (y - cy) / d
            return true
        }
    }
}
