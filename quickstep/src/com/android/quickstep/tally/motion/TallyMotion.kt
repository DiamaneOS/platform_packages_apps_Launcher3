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

import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.graphics.RectF
import android.view.RemoteAnimationTarget
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.BubbleTextView
import com.android.launcher3.CellLayout
import com.android.launcher3.R
import com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR
import com.android.launcher3.tally.home.TallyHomeLayout
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.systemui.shared.system.QuickStepContract
import com.android.wm.shell.shared.compat.AnimatedSurface
import kotlin.math.min

/**
 * Tally's motion in one Launcher (roadmap step 5): the window growing out of its key, the return to
 * Home and Back to Home flying into it, Home's dim and the key's neighbours. It applies where
 * Tally's Home layout applies, a phone held upright ([TallyHomeLayout.appliesTo]), to a full-screen
 * window with no rotation on the way; everywhere else (landscape, tablets, a taskbar, split screen,
 * desktop windows, a third-party launcher's Recents) the motion stays stock's. It changes how
 * things move, never what opens, closes, goes back or goes Home, or when.
 *
 * [com.android.launcher3.QuickstepTransitionManager] owns one.
 */
class TallyMotion(private val launcher: QuickstepLauncher) {
    private val res = launcher.resources
    val density: Float = res.displayMetrics.density
    val slab = TallySpring.slab(res)
    val stone = TallySpring.stone(res)
    val fill = TallySpring.fill(res)

    /** A key's corners, as a share of its side (`tally_keycap_radius`, 22 %). */
    val keyRadius: Float = res.getFraction(R.fraction.tally_keycap_radius, 1, 1)

    /** The keycap splash's key, which WM Shell centres on a cold start's window. */
    val splashKeyPx: Float = TallyWindowMotion.SPLASH_KEYCAP_DP * density

    val dim = TallyHomeDim(launcher.dragLayer, fill)
    val parting = TallyParting(stone, density, res.getDimension(R.dimen.tally_grid_parting))

    /** A flight started by a tap (a close with no finger): its length at scale 1. */
    val impulseFlightMillis: Long = TallyFlight(slab).move(RectF(), RectF(), 0f, 0f).millis

    private val homeContainers = ArrayList<ViewGroup>()

    /** Whether Tally's Home layout, and so its motion, applies now. */
    fun applies(): Boolean = TallyHomeLayout.appliesTo(launcher.deviceProfile)

    /** Whether a launch of [appSurfaces] takes Tally's motion. */
    fun appliesToLaunch(appSurfaces: Array<AnimatedSurface>): Boolean {
        if (!applies()) return false
        var opening = false
        for (surface in appSurfaces) {
            if (surface.rotationChange != 0) return false
            if (surface.mode == AnimatedSurface.Mode.OPENING) {
                if (!isFullscreen(surface.windowConfiguration.windowingMode)) return false
                opening = true
            }
        }
        return opening
    }

    /** Whether a return to Home of [targets] (one closing window) takes Tally's motion. */
    fun appliesToReturn(targets: Array<RemoteAnimationTarget>): Boolean {
        if (!applies()) return false
        var closing = 0
        for (target in targets) {
            if (target.rotationChange != 0) return false
            if (target.mode == RemoteAnimationTarget.MODE_CLOSING) {
                if (!isFullscreen(target.windowConfiguration.windowingMode)) return false
                closing++
            }
        }
        return closing == 1
    }

    /** The corners of a window at rest: the device's own, as stock reads them. */
    fun restRadius(): Float =
        if (QuickStepContract.supportsRoundedCornersOnWindows(res)) {
            QuickStepContract.getWindowCornerRadius(launcher)
        } else 0f

    /** Where the dock's keys are centred, from the top of the screen (Home's rhythm). */
    fun dockCentreY(): Float {
        val properties = launcher.deviceProfile.deviceProperties
        val rhythm =
            TallyHomeLayout.rhythm(properties.heightPx / density, res.configuration.fontScale)
        return properties.heightPx - rhythm.dockCentreFromBottom * density
    }

    /** The share of [key]'s icon bounds that is its visible key. */
    fun visibleFactor(key: View?): Float =
        if (key is BubbleTextView) ICON_VISIBLE_AREA_FACTOR else 1f

    /** With no key on Home: the dock's centre, where the window lands and fades. */
    fun dockTarget(out: RectF): RectF {
        val target = TallyWindowRect()
        TallyWindowMotion.dockTarget(screenWidth(), dockCentreY(), density, target)
        out.set(target.rect)
        return out
    }

    /**
     * A window closing from corners [startRadius] into [key] (whose icon bounds are [target]), or
     * to the dock's centre when [key] is null.
     */
    fun close(key: View?, target: RectF, startRadius: Float): TallyClose {
        val factor = visibleFactor(key)
        val endRadius =
            if (key != null) keyRadius * min(target.width(), target.height()) * factor
            else TallyWindowMotion.DOCK_TARGET_RADIUS_DP * density
        return TallyClose(startRadius, endRadius, key != null, 1f / factor, screenWidth(), density)
    }

    private fun screenWidth(): Float = launcher.deviceProfile.deviceProperties.widthPx.toFloat()

    /**
     * The key that [view] stands for, from [iconBounds] (where FloatingIconView put [view]'s icon,
     * in the drag layer): an app key's visible key, or the whole of anything else (a folder, the
     * tallies band's key).
     */
    fun keyRect(view: View?, iconBounds: RectF, out: TallyWindowRect): TallyWindowRect =
        TallyWindowMotion.keyRect(iconBounds, visibleFactor(view), keyRadius, out)

    /**
     * Finds the neighbours of [key] that part as a window leaves or lands in it: on Home the keys
     * of the visible pages and the dock, elsewhere the key's siblings. Returns whether any moves.
     */
    fun collectNeighbours(key: View): Boolean {
        homeContainers.clear()
        val workspace = launcher.workspace
        val hotseat = launcher.hotseat
        if (key.parent?.parent is CellLayout && isOnHome(key)) {
            homeContainers.add(hotseat.shortcutsAndWidgets)
            workspace.forEachVisiblePage { page ->
                (page as? CellLayout)?.let { homeContainers.add(it.shortcutsAndWidgets) }
            }
        }
        val found = parting.collect(key, launcher.dragLayer, homeContainers)
        homeContainers.clear()
        return found
    }

    private fun isOnHome(key: View): Boolean {
        var v: View? = key
        while (v != null) {
            if (v === launcher.workspace || v === launcher.hotseat) return true
            v = v.parent as? View
        }
        return false
    }

    private companion object {
        fun isFullscreen(windowingMode: Int) =
            windowingMode == WINDOWING_MODE_FULLSCREEN || windowingMode == WINDOWING_MODE_UNDEFINED
    }
}
