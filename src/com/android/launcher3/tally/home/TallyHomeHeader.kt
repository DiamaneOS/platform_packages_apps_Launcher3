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

package com.android.launcher3.tally.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import com.android.launcher3.DeviceProfile
import com.android.launcher3.Insettable
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.dagger.LauncherComponentProvider
import com.android.launcher3.tally.live.TallyLiveItem
import com.android.launcher3.util.ApiWrapper
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.SafeCloseable
import com.android.launcher3.util.Themes
import com.android.launcher3.views.ActivityContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The top of Tally Home: the date, and below it the tallies band ([TallyTalliesRow]), on the
 * rhythm's lines ([TallyHomeLayout]: 64 and 104 dp at 100 to 130 % text, 56 and 94 dp from 150 %)
 * above the grid. It stays put while Home's pages scroll, and comes and goes with the dock as
 * Home's state changes (WorkspaceStateTransitionAnimation sets its alpha). On a phone held sideways
 * or a tablet, where Home keeps stock's layout, it shows nothing.
 *
 * The band comes off Home as a widget does ([TallyHomeLift]) and comes back in Home settings
 * ([TallyHomeItem.TALLIES]); the date stays. Taken off, the band only hides: the notifications
 * behind it are read as before, for the keys' LEDs.
 *
 * The band shows what [com.android.launcher3.tally.live.TallyLiveRepository] publishes: things live
 * or failed now, read from the dots' notification listener. A tap on one opens its app through
 * Launcher's own start path, its window growing out of the band's key (or the notification shade
 * for a system service with no app on Home); "+n more" opens the shade.
 */
class TallyHomeHeader @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), Insettable {
    private val density = resources.displayMetrics.density
    private val date =
        TextClock(context).apply {
            setTextAppearance(R.style.TextAppearance_Tally_Title)
            setTextColor(Themes.getAttrColor(context, R.attr.workspaceTextColor))
            // On the wallpaper, with the shadow Home's names have (none with dark text).
            setShadowLayer(
                SHADOW_BLUR_DP * density,
                0f,
                SHADOW_DY_DP * density,
                Themes.getAttrColor(context, R.attr.workspaceShadowColor),
            )
            // The date stops growing at 130 % text, as the prototype's.
            val capPx =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, DATE_SP, displayMetrics)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                min(textSize, capPx * TallyHomeLayout.TEXT_SCALE_CAP),
            )
            setLineHeight((DATE_LINE_DP * density).roundToInt())
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), DATE_SKELETON)
            format12Hour = pattern
            format24Hour = pattern
            maxLines = 1
        }
    private val tallies = TallyTalliesRow(context)
    private var rowSubscription: SafeCloseable? = null
    private var applies = true
    /** Whether the band is on Home: Tally's layout applies and the band's setting is on. */
    private var bandOn = true
    private var items: List<TallyLiveItem> = emptyList()
    private var shownOnScreen = false
    private val tick = Runnable { onTick() }

    private val displayMetrics
        get() = resources.displayMetrics

    init {
        clipChildren = false
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(tallies, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        tallies.onTap = ::open
        tallies.lift =
            TallyHomeLift(tallies, TallyHomeItem.TALLIES, { bandOn }) { tallies.setLifted(it) }
    }

    override fun setInsets(insets: Rect) {
        val activity: ActivityContext = ActivityContext.lookupContext(context)
        val dp: DeviceProfile = activity.deviceProfile
        val properties = dp.deviceProperties
        applies =
            TallyHomeLayout.appliesTo(properties, dp.isVerticalBarLayout, dp.inv.isFixedLandscape)
        bandOn = applies && TallyHomeItem.TALLIES.isShown(context)
        date.visibility = if (applies) VISIBLE else GONE
        // Home was laid out again (the band came off or back, among others): nothing is lifted.
        tallies.setLifted(false)
        show(items)
        if (!applies) return
        val rhythm =
            TallyHomeLayout.rhythm(properties.heightPx / density, resources.configuration.fontScale)
        // Equal side margins, so left and right serve either direction (start and end margins set
        // on params already in place would not be resolved).
        (date.layoutParams as LayoutParams).apply {
            topMargin = (rhythm.dateTop * density).roundToInt()
            leftMargin = (DATE_START_DP * density).roundToInt()
            rightMargin = leftMargin
        }
        (tallies.layoutParams as LayoutParams).apply {
            topMargin = (rhythm.talliesTop * density).roundToInt()
            leftMargin = (TALLIES_SIDE_DP * density).roundToInt()
            rightMargin = leftMargin
        }
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val live = LauncherComponentProvider.get(context).getNotificationRepository().live
        rowSubscription = live.row.forEach(MAIN_EXECUTOR) { items -> show(items) }
    }

    override fun onDetachedFromWindow() {
        rowSubscription?.close()
        rowSubscription = null
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shownOnScreen = isVisible
        if (isVisible) onTick() else removeCallbacks(tick)
    }

    private fun show(newItems: List<TallyLiveItem>) {
        items = newItems
        tallies.setItems(if (bandOn) newItems else emptyList())
        onTick()
    }

    /** Counts a chronometer on screen once a second, at the turn of the second. */
    private fun onTick() {
        removeCallbacks(tick)
        if (tallies.tick() && shownOnScreen) {
            postDelayed(tick, TICK_MS - SystemClock.elapsedRealtime() % TICK_MS)
        }
    }

    private fun open(item: TallyLiveItem?, key: View) {
        // "+n more", a system service with no app on Home, or an app that went or was disabled
        // since the band last changed: the shade has its words.
        if (item == null || !startApp(item, key)) {
            ApiWrapper.INSTANCE[context].openNotificationShade()
        }
    }

    /**
     * Starts [item]'s app as All apps starts it, through Launcher's own start path (its safe-mode,
     * work profile and private space handling, and the launch animation, growing out of [key]);
     * returns whether it started.
     */
    private fun startApp(item: TallyLiveItem, key: View): Boolean {
        val user = item.app.mUser ?: return false
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        val component =
            try {
                launcherApps
                    .getActivityList(item.app.mPackageName, user)
                    .firstOrNull()
                    ?.componentName ?: return false
            } catch (e: SecurityException) {
                onStartFailed(e)
                return false
            } catch (e: IllegalStateException) {
                onStartFailed(e)
                return false
            }
        val launcher = Launcher.getLauncher(context)
        // As an item tap: nothing starts while Home changes state.
        if (!launcher.workspace.isFinishedSwitchingState) return true
        val app = launcher.appsView.appsStore.getApp(ComponentKey(component, user)) ?: return false
        return try {
            launcher.startActivitySafely(key, app.intent, app) != null
        } catch (e: ActivityNotFoundException) {
            onStartFailed(e)
            false
        }
    }

    /** Logs a failed start by its kind only: the message would name the app. */
    private fun onStartFailed(e: RuntimeException) {
        Log.w(TAG, "Could not open a tally's app (${e.javaClass.simpleName}); opening the shade")
    }

    companion object {
        private const val TAG = "TallyHomeHeader"
        /** The date: 20 sp at 100 %, on a 28 dp line, 24 dp from the start. */
        private const val DATE_SP = 20f
        private const val DATE_LINE_DP = 28f
        private const val DATE_START_DP = 24f
        private const val DATE_SKELETON = "EEEEdMMMM"
        /** The date's shadow: the blur and offset of the names' shadows on Home. */
        private const val SHADOW_BLUR_DP = 1.5f
        private const val SHADOW_DY_DP = 0.5f
        /** The tallies band spans the screen less 16 dp on each side. */
        private const val TALLIES_SIDE_DP = 16f
        private const val TICK_MS = 1000L
    }
}
