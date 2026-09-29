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

import android.graphics.RectF

/**
 * A window closing into its key, or to the dock's centre when Home has no key for it (the
 * prototype's `wm.goHome`, `wm.homeTarget` and `Win.render`), one frame at a time: the flight
 * ([RectFSpringAnim] on the slab spring) gives the window's rect and progress `q`; this gives its
 * corners, its content mapping and alpha, and the floating icon's alpha and bounds.
 *
 * With a key, the window's rect lands on the key's visible rect and its corners go from where they
 * were to the key's (22 % of its side); the key's icon comes back over the window's content as the
 * rect narrows from half the screen to [TallyWindowMotion.KEY_BACK_TO_DP] (the prototype's splash
 * returning): the window fades out over the floating icon, which lies under it. Without a key, the
 * window lands on a [TallyWindowMotion.DOCK_TARGET_DP] key on the dock's centre and fades out
 * ([TallyWindowMotion.fadeOut]). One per flight; nothing is allocated per frame.
 *
 * @param startRadius the window's corners when the flight starts, in px
 * @param endRadius the key's corners (or the dock target's), in px
 * @param hasKey whether the window lands in a key (with a floating icon under it)
 * @param iconScale the floating icon's bounds over the key's visible rect (1 / the icon's visible
 *   area for an app key)
 */
class TallyClose(
    private val startRadius: Float,
    private val endRadius: Float,
    val hasKey: Boolean,
    private val iconScale: Float,
    private val screenW: Float,
    private val density: Float,
) {
    /** The window now: its rect (as the flight gives it) and corners. */
    val window = TallyWindowRect()
    val content = TallyWindowContent()

    var windowAlpha = 1f
        private set

    /** The floating icon's alpha ([icon] gives its bounds) and corners. */
    var iconAlpha = 0f
        private set

    var iconRadius = 0f
        private set

    private val iconBounds = RectF()

    /**
     * Sets the frame for the flight's [rect] at progress [q], mapping the content of a window whose
     * bounds are [windowBounds] (in the rect's coordinates).
     */
    fun update(rect: RectF, q: Float, windowBounds: RectF): TallyClose {
        window.rect.set(rect)
        window.radius = startRadius + (endRadius - startRadius) * q
        content.map(windowBounds, window, TallyWindowContent.fitScale(windowBounds, rect))
        if (hasKey) {
            val back = TallyWindowMotion.keyBack(rect.width(), screenW, density)
            windowAlpha = 1f - back
            iconAlpha = if (back > 0f) 1f else 0f
        } else {
            windowAlpha = TallyWindowMotion.fadeOut(q)
            iconAlpha = 0f
        }
        iconRadius = window.radius * iconScale
        return this
    }

    /**
     * The floating icon's bounds for the flight's [rect] (in the icon's coordinates): the rect
     * grown to the icon's bounds about its centre, so the icon's key matches the window.
     */
    fun icon(rect: RectF): RectF {
        val hw = rect.width() * iconScale / 2f
        val hh = rect.height() * iconScale / 2f
        iconBounds.set(
            rect.centerX() - hw,
            rect.centerY() - hh,
            rect.centerX() + hw,
            rect.centerY() + hh,
        )
        return iconBounds
    }
}
