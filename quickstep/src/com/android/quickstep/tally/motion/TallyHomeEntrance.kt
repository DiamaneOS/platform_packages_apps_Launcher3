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
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.Interpolator
import com.android.app.animation.Animations
import com.android.launcher3.LauncherAnimUtils.HOTSEAT_SCALE_PROPERTY_FACTORY
import com.android.launcher3.LauncherAnimUtils.SCALE_INDEX_WORKSPACE_STATE
import com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA
import com.android.launcher3.LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY
import com.android.launcher3.LauncherState
import com.android.launcher3.anim.AnimatorListeners
import com.android.launcher3.anim.PendingAnimation
import com.android.launcher3.anim.PropertySetter
import com.android.launcher3.states.StateAnimationConfig
import com.android.launcher3.states.StateAnimationConfig.ANIM_DEPTH
import com.android.launcher3.states.StateAnimationConfig.ANIM_SCRIM_FADE
import com.android.launcher3.states.StateAnimationConfig.SKIP_DEPTH_CONTROLLER
import com.android.launcher3.states.StateAnimationConfig.SKIP_OVERVIEW
import com.android.launcher3.states.StateAnimationConfig.SKIP_SCRIM
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.quickstep.util.RectFSpringAnim
import com.android.quickstep.views.RecentsView

/**
 * Home's entrance as a window comes back to it with Tally's motion (the prototype's `wm.goHome`),
 * in place of stock's scaling reveal (ScalingWorkspaceRevealAnim, which scales Home from 85 % over
 * a second with a blur): Home is simply there, at rest, and the key the window lands in makes room
 * for it. Its neighbours part as the window flies ([TallyParting.part]) and settle as it lands
 * ([TallyParting.settle]); Home's dim ([dimFrom] to none), the wallpaper's depth and the system
 * bars' scrim clear on the fill spring. Where the gesture had hidden Home (a swipe up hides it
 * behind Recents), Home fades in on fill ([fadeIn]). With no key on Home nothing parts.
 *
 * Its Animator lasts at least [holdMillis], the flight's length, so a transition that ends with it
 * never ends before the window lands.
 */
class TallyHomeEntrance(
    private val launcher: QuickstepLauncher,
    private val motion: TallyMotion,
    private val key: View?,
    private val flight: RectFSpringAnim?,
    private val dimFrom: Float,
    private val fadeIn: Boolean,
    private val holdMillis: Long,
) {
    /** The entrance's Animator, for a transition to play (stock's `getAnimators`). */
    fun animators(): AnimatorSet {
        val workspace = launcher.workspace
        val hotseat = launcher.hotseat
        // Interrupt Home's running animations, as stock's reveal does.
        Animations.cancelOngoingAnimation(workspace)
        Animations.cancelOngoingAnimation(hotseat)

        // As stock's reveal: straight to the normal state, without Recents, depth or scrim.
        val setup = StateAnimationConfig()
        setup.animFlags = SKIP_OVERVIEW or SKIP_DEPTH_CONTROLLER or SKIP_SCRIM
        setup.duration = 0
        launcher.stateManager
            .createAtomicAnimation(LauncherState.BACKGROUND_APP, LauncherState.NORMAL, setup)
            .start()
        launcher.getOverviewPanel<RecentsView<*, *>>().forceFinishScroller()
        launcher.workspace.stateTransitionAnimation.setScrim(
            PropertySetter.NO_ANIM_PROPERTY_SETTER,
            LauncherState.BACKGROUND_APP,
            setup,
        )
        // At rest: no scale.
        WORKSPACE_SCALE_PROPERTY_FACTORY[SCALE_INDEX_WORKSPACE_STATE].set(workspace, 1f)
        HOTSEAT_SCALE_PROPERTY_FACTORY[SCALE_INDEX_WORKSPACE_STATE].set(hotseat, 1f)

        val fill = motion.fill
        val effect = fill.Move(0.0, 1.0, 0.0, REST_EFFECT, REST_EFFECT_VELOCITY)
        val curve = Interpolator { effect.progressAtFraction(it) }
        val set = AnimatorSet()

        // The effects: Home's dim, the wallpaper's depth and the bars' scrim, on fill.
        motion.dim.set(dimFrom)
        set.play(motion.dim.animatorTo(0f))
        val effects = PendingAnimation(effect.millis)
        val config = StateAnimationConfig()
        config.duration = effect.millis
        config.setInterpolator(ANIM_DEPTH, curve)
        config.setInterpolator(ANIM_SCRIM_FADE, curve)
        launcher.depthController.setStateWithAnimation(LauncherState.NORMAL, config, effects)
        workspace.stateTransitionAnimation.setScrim(effects, LauncherState.NORMAL, config)
        if (fadeIn) {
            workspace.alpha = 0f
            hotseat.alpha = 0f
            effects.setFloat(workspace, VIEW_ALPHA, 1f, curve)
            effects.setViewAlpha(hotseat, 1f, curve)
        }
        set.play(effects.buildAnim())
        // Long enough for the flight.
        set.play(ValueAnimator.ofFloat(0f, 1f).setDuration(holdMillis))

        val parts = key != null && motion.collectNeighbours(key)
        set.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (!parts) return
                    motion.parting.part()
                    when {
                        flight == null -> return
                        // Landed already (Remove animations ends it at once): settle now.
                        flight.hasLanded() -> motion.parting.settle()
                        else ->
                            flight.addAnimatorListener(
                                AnimatorListeners.forEndCallback(
                                    Runnable { motion.parting.settle() }
                                )
                            )
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    // Home is visible whatever happened to the entrance (as stock ensures).
                    workspace.alpha = 1f
                    hotseat.alpha = 1f
                    Animations.setOngoingAnimation(workspace, null)
                    Animations.setOngoingAnimation(hotseat, null)
                    if (parts && flight == null) motion.parting.settle()
                }
            }
        )
        return set
    }

    /** Plays the entrance on its own (a gesture's return), as stock's reveal starts. */
    fun start() {
        val animators = animators()
        Animations.setOngoingAnimation(launcher.workspace, animators)
        Animations.setOngoingAnimation(launcher.hotseat, animators)
        launcher.stateManager.setCurrentAnimation(animators, LauncherState.NORMAL)
        animators.start()
    }

    private companion object {
        /** Effects at rest within 1/255 and 0.5 per second. */
        const val REST_EFFECT = 1.0 / 255.0
        const val REST_EFFECT_VELOCITY = 0.5
    }
}
