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
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.android.app.animation.Interpolators
import com.android.launcher3.QuickstepTransitionManager.ANIMATION_DELAY_NAV_FADE_IN
import com.android.launcher3.QuickstepTransitionManager.ANIMATION_NAV_FADE_IN_DURATION
import com.android.launcher3.QuickstepTransitionManager.ANIMATION_NAV_FADE_OUT_DURATION
import com.android.launcher3.QuickstepTransitionManager.NAV_FADE_IN_INTERPOLATOR
import com.android.launcher3.QuickstepTransitionManager.NAV_FADE_OUT_INTERPOLATOR
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.views.FloatingIconView
import com.android.quickstep.AnimatedSurfaces
import com.android.quickstep.util.SurfaceTransaction
import com.android.quickstep.util.SurfaceTransactionApplier
import com.android.wm.shell.shared.compat.AnimatedSurface
import com.android.wm.shell.shared.compat.AnimatedSurfaceUtils

/**
 * A launch from a key (the prototype's `T.launch`): the window grows out of the key, from the key's
 * rect and corners (22 % of its side) to the window's bounds and the device's window corners, on
 * the slab spring with a tap impulse; Home dims to [TallyWindowMotion.HOME_DIM] on the fill spring;
 * the key's neighbours part and settle ([TallyParting]). Stock's launch
 * (QuickstepTransitionManager's icon launch) builds the same targets, floating icon and surface
 * applier; this replaces how they move, not what is launched.
 *
 * The window's content shows as the prototype's does: a warm start's content scales uniformly to
 * fill the rect, pinned at (50 %, 32 %) of the window. On a cold start the window shows WM Shell's
 * keycap splash, whose key ([TallyMotion.splashKeyPx]) is centred on the window: the content is
 * then pinned at the centre and scaled so that the splash's key is the size of the key it grows
 * from, reaching its own size as the window opens, so the key seems to become the window. The
 * floating icon, which hides the key while the window covers it, draws nothing unless the key's
 * icon differs from the app's (a themed icon) on a cold start: then it fades over the window as
 * stock's does (25 to 75 ms).
 *
 * One Animator plays the whole launch; its duration is the slab spring's settle time (about 480
 * ms), so the animator duration scale applies, and Remove animations shows the open window at once.
 * Nothing is allocated per frame beyond the surface transaction stock also creates.
 */
class TallyLaunch(
    private val motion: TallyMotion,
    private val launcher: QuickstepLauncher,
    private val source: View,
    private val appSurfaces: Array<AnimatedSurface>,
    private val openingSurfaces: AnimatedSurfaces,
    windowTargetBounds: Rect,
    launcherIconBounds: RectF,
    private val dragLayerX: Int,
    private val dragLayerY: Int,
    private val hasSplashScreen: Boolean,
    private val floatingView: FloatingIconView,
    private val surfaceApplier: SurfaceTransactionApplier,
    private val navBarSurface: AnimatedSurface?,
    private val launcherClosing: Boolean,
    private val shadowRadius: Float,
) {
    private val window = RectF(windowTargetBounds)
    private val from = TallyWindowRect()
    private val to = TallyWindowRect()
    private val current = TallyWindowRect()
    private val content = TallyWindowContent()
    private val iconRect = RectF()
    private val iconBounds = RectF(launcherIconBounds)
    private val closingMatrices = Array(appSurfaces.size) { Matrix() }
    private val closingCrops = Array(appSurfaces.size) { Rect() }
    private val navMatrix = Matrix()

    private val grow = motion.slab.Move(0.0, 1.0, motion.slab.impulse(1.0), REST_Q, REST_Q_VELOCITY)
    private val dimIn =
        motion.fill.Move(
            0.0,
            TallyWindowMotion.HOME_DIM.toDouble(),
            0.0,
            REST_DIM,
            REST_DIM_VELOCITY,
        )
    private val totalMillis = maxOf(grow.millis, if (launcherClosing) dimIn.millis else 0L)

    /** Whether the key's icon shows over the window at first (a themed icon on a cold start). */
    private val iconShows = hasSplashScreen && floatingView.isDifferentFromAppIcon
    private var iconHidden = false

    init {
        // The key, on screen (the icon bounds are in the drag layer).
        iconRect.set(launcherIconBounds)
        iconRect.offset(dragLayerX.toFloat(), dragLayerY.toFloat())
        motion.keyRect(source, iconRect, from)
        to.set(window, motion.restRadius())
        val pos = Point()
        for (i in appSurfaces.indices) {
            val surface = appSurfaces[i]
            if (!AnimatedSurfaceUtils.isClosing(surface)) continue
            // Stock's closing targets: in place and uncropped (Launcher behind the window).
            val local = surface.localBounds
            if (local != null) pos.set(local.left, local.top)
            else pos.set(surface.position.x, surface.position.y)
            closingMatrices[i].setTranslate(pos.x.toFloat(), pos.y.toFloat())
            closingCrops[i].set(surface.screenSpaceBounds)
            closingCrops[i].offsetTo(0, 0)
        }
    }

    /** The launch's Animator: the window, its floating icon, Home's dim and the neighbours. */
    fun animator(): Animator {
        val windowAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = totalMillis
                interpolator = Interpolators.LINEAR
                addListener(floatingView)
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            if (launcherClosing) {
                                motion.dim.claim(animation)
                                launcher.pauseExpensiveViewUpdates()
                            }
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (launcherClosing) {
                                // Launcher is behind the window now; the dim goes with it.
                                motion.dim.clear()
                                launcher.resumeExpensiveViewUpdates()
                            }
                            openingSurfaces.release()
                        }
                    }
                )
                addUpdateListener { apply(it, it.animatedFraction * totalMillis / 1000.0, false) }
            }
        // The start delay's frame shows the key as the floating icon, as stock's first pass does.
        apply(null, 0.0, true)
        if (!motion.collectNeighbours(source)) return windowAnimator
        return AnimatorSet().apply { playTogether(windowAnimator, motion.parting.launchAnimator()) }
    }

    private fun apply(animator: Animator?, t: Double, initOnly: Boolean) {
        val q = grow.valueAt(t).toFloat()
        current.lerp(from, to, q)
        val scale: Float
        val pivotY: Float
        if (hasSplashScreen) {
            val keySide = minOf(from.rect.width(), from.rect.height())
            scale =
                TallyWindowMotion.splashScale(keySide, motion.splashKeyPx, q, window, current.rect)
            pivotY = 0.5f
        } else {
            scale = TallyWindowContent.fitScale(window, current.rect)
            pivotY = TallyWindowMotion.CONTENT_PIVOT_Y
        }
        content.map(window, current, scale, TallyWindowMotion.CONTENT_PIVOT_X, pivotY)

        val ms = t * 1000.0
        val iconAlpha =
            if (!iconShows) 0f
            else (1.0 - ((ms - ICON_FADE_DELAY_MS) / ICON_FADE_MS).coerceIn(0.0, 1.0)).toFloat()
        if (initOnly) {
            // Full alpha in the first pass: the window is not shown yet (stock does the same).
            updateIcon(1f, q)
            return
        }
        if (iconShows || !iconHidden) {
            updateIcon(iconAlpha, q)
            iconHidden = iconAlpha == 0f
        }

        val transaction = SurfaceTransaction()
        for (i in appSurfaces.indices.reversed()) {
            val surface = appSurfaces[i]
            val builder = transaction.forSurface(surface.leash)
            if (AnimatedSurfaceUtils.isOpening(surface)) {
                builder
                    .setMatrix(content.matrix)
                    .setWindowCrop(content.crop)
                    .setAlpha(1f - iconAlpha)
                    .setCornerRadius(content.layerRadius)
                    .setShadowRadius(shadowRadius * q)
            } else if (AnimatedSurfaceUtils.isClosing(surface)) {
                builder.setMatrix(closingMatrices[i]).setWindowCrop(closingCrops[i]).setAlpha(1f)
            }
        }
        val nav = navBarSurface
        if (nav != null) {
            // Stock's navigation bar: out over the first 133 ms, back with the window from 234 ms.
            val navBuilder = transaction.forSurface(nav.leash)
            val fadeIn =
                ((ms - ANIMATION_DELAY_NAV_FADE_IN) / ANIMATION_NAV_FADE_IN_DURATION)
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            if (fadeIn > 0f) {
                navMatrix.set(content.matrix)
                navBuilder
                    .setMatrix(navMatrix)
                    .setWindowCrop(content.crop)
                    .setAlpha(NAV_FADE_IN_INTERPOLATOR.getInterpolation(fadeIn))
            } else {
                val fadeOut = (ms / ANIMATION_NAV_FADE_OUT_DURATION).coerceIn(0.0, 1.0).toFloat()
                navBuilder.setAlpha(1f - NAV_FADE_OUT_INTERPOLATOR.getInterpolation(fadeOut))
            }
        }
        if (launcherClosing) motion.dim.set(dimIn.valueAt(t).toFloat(), animator)
        surfaceApplier.scheduleApply(transaction)
    }

    /**
     * Puts the floating icon over the window: its icon bounds grow with the window from where the
     * key's icon is (in the drag layer), so its key matches the window's rect.
     */
    private fun updateIcon(alpha: Float, q: Float) {
        val sx = if (from.rect.width() > 0f) iconBounds.width() / from.rect.width() else 1f
        val sy = if (from.rect.height() > 0f) iconBounds.height() / from.rect.height() else 1f
        val cx = current.rect.centerX() - dragLayerX
        val cy = current.rect.centerY() - dragLayerY
        val hw = current.rect.width() * sx / 2f
        val hh = current.rect.height() * sy / 2f
        iconRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        floatingView.update(alpha, iconRect, q, 0f, current.radius * sx, true)
    }

    private companion object {
        /** At rest within 0.05 % of the way and 0.005 per second (the prototype's window). */
        const val REST_Q = 0.0005
        const val REST_Q_VELOCITY = 0.005
        /** The dim at rest within one step of its alpha. */
        const val REST_DIM = 1.0 / 255.0
        const val REST_DIM_VELOCITY = 0.5
        /** A themed key's icon fades over the window as stock's does. */
        const val ICON_FADE_DELAY_MS = 25.0
        const val ICON_FADE_MS = 50.0
    }
}
