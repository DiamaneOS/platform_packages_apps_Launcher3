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

import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import com.android.launcher3.DeviceProfile
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.R
import com.android.launcher3.deviceprofile.DeviceProperties
import com.android.launcher3.deviceprofile.parser.DeviceTypedMap.INDEX_DEFAULT
import com.android.launcher3.deviceprofile.parser.DisplayOption
import com.android.launcher3.display.LauncherDisplayInfo
import com.android.launcher3.folder.ClippedFolderIconLayoutRule.ICON_OVERLAP_FACTOR
import com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tally Home's layout on a phone held upright, as the prototype's `T.home.layout` lays it out on
 * the 372 × 828 dp canvas: one spacing rhythm from the top (the date, the tallies band, the grid)
 * and one from the bottom (the dock's keys and the search slot), with equal rows between them. At
 * 100 to 130 % text: the date at 64 dp, the tallies at 104, the grid from 184 (plus a tenth of any
 * height over 828), the dock's band 228 dp above the bottom with its keys centred 174 dp above it,
 * the search slot's bottom 72 dp above it, and 16 dp between the grid and the dock's band; rows are
 * at most 120 dp. From 150 % text: 56, 94, 214, 232 (keys at 178, the slot at 76), 14 dp, at most
 * 108 dp rows.
 *
 * App keys keep their size on the glass, as the prototype's `T.GLASS`: 56 dp on Home and in the
 * dock and 52 dp in All apps at density 480, scaled by 480 / density and rounded to 4 dp at other
 * display sizes. These are the keys' visible sizes; Launcher's icon sizes include the adaptive
 * icon's margin, so they are larger by 1 / ICON_VISIBLE_AREA_FACTOR. App names are 12 sp and stop
 * growing at 130 % text, as the prototype's labels do.
 *
 * The tallies band and the search slot can be taken off Home ([TallyHomeItem]); the grid takes the
 * space one leaves, all of it (see [rhythm]).
 *
 * Launcher applies this through three hooks: [applyToDisplayOption] when it picks a grid
 * (InvariantDeviceProfile), [hotseatQsbHeightPx] when it sizes the dock
 * (HotseatProfileInitialValues) and [workspacePaddingsPx] when it lays out the workspace
 * (WorkspaceProfileNonResponsiveFactory). Landscape, tablets, fixed landscape and responsive grids
 * keep stock's layout.
 */
object TallyHomeLayout {
    /** The prototype's canvas height at density 480 (dp). */
    const val CANVAS_HEIGHT_DP = 828f
    /** Text from this scale takes the large-text rhythm. */
    const val LARGE_TEXT_SCALE = 1.5f
    /** App names, the date and the search text stop growing at this text scale. */
    const val TEXT_SCALE_CAP = 1.3f
    /** The search slot's height (dp), Launcher's qsb_widget_height. */
    const val SEARCH_SLOT_DP = 48f

    /**
     * The grid a phone starts on: 4 x 4, the prototype's four rows (100 dp at 828 dp). Five rows
     * get 74 to 80 dp each, where a name at 150 % text no longer fits under its key.
     */
    const val STARTING_GRID = "4_by_4"

    /**
     * Where a name would reach the key in the next row, a Home key shrinks in [GLASS_STEP_DP] steps
     * down to this at most ([fittedKeyDp]).
     */
    const val MIN_FITTED_KEY_DP = 24f

    const val HOME_KEY_DP = 56f
    const val ALL_APPS_KEY_DP = 52f
    const val LABEL_SP = 12f
    const val ALL_APPS_ROW_DP = 100f
    private const val GLASS_DENSITY = 480f
    private const val GLASS_STEP_DP = 4f
    /** Launcher's space between a Home key and its name before the icon's margin (CellStyle). */
    private const val ICON_DRAWABLE_PADDING_DP = 7f
    /** The names' weight (styles.xml BaseIconUnBounded, TextAppearance.Tally.LabelSmall). */
    private const val LABEL_WEIGHT = 500

    /** Positions from the top (dp), from the bottom (dp), and the rows' limits. */
    data class Rhythm(
        val dateTop: Float,
        val talliesTop: Float,
        val gridTop: Float,
        /** The top of the dock's band, from the bottom of the screen. */
        val keysFromBottom: Float,
        /** The centre of the dock's keys, from the bottom. */
        val dockCentreFromBottom: Float,
        /** The bottom of the search slot, from the bottom. */
        val slotBottomFromBottom: Float,
        /** The space between the grid and the dock's band. */
        val gridGap: Float,
        /** The rows' limit, for the canvas's own height. */
        val rowMax: Float,
        /** Space removed items leave to the rows, over their limit ([rowMax]). */
        val freedForRows: Float = 0f,
    )

    /**
     * The rhythm for a canvas [heightDp] tall at [fontScale], with the tallies band ([tallies]) and
     * the search slot ([search]) on Home or taken off.
     *
     * The prototype gives a taller canvas's extra height to the grid: at 100 to 130 % text a tenth
     * goes above it (its top line moves down), the rest to its rows up to their limit, and what the
     * limit leaves stays between the grid and the dock; from 150 % text the grid's top stays and it
     * all goes to the rows, up to their limit.
     *
     * The space a removed item leaves goes to the grid, all of it: the band's (from its line to the
     * grid's: 80 dp, 120 dp from 150 %) at the top, so the grid's line moves up to the band's; the
     * slot's (78 dp) at the bottom, so the dock moves down until its keys are centred where the
     * slot was, and the grid follows it. At 100 to 130 % text a tenth of it goes above the grid, as
     * of a taller canvas's height (the date then stands as far above the first keys as the band
     * does); the rest goes to the rows, past their limit ([Rhythm.freedForRows]), so that nothing
     * is left empty where the item was nor between the grid and the dock.
     */
    @JvmStatic
    @JvmOverloads
    fun rhythm(
        heightDp: Float,
        fontScale: Float,
        tallies: Boolean = true,
        search: Boolean = true,
    ): Rhythm {
        val large = fontScale >= LARGE_TEXT_SCALE
        val base =
            if (large) Rhythm(56f, 94f, 214f, 232f, 178f, 76f, 14f, 108f)
            else Rhythm(64f, 104f, 184f, 228f, 174f, 72f, 16f, 120f)
        val bandSpace = if (tallies) 0f else base.gridTop - base.talliesTop
        val slotSpace =
            if (search) 0f
            else base.dockCentreFromBottom - base.slotBottomFromBottom - SEARCH_SLOT_DP / 2
        val extra = max(0f, heightDp - CANVAS_HEIGHT_DP)
        val freed = bandSpace + slotSpace
        val above = if (large) 0 else ((extra + freed) * 0.1f).roundToInt()
        val freedAbove = if (large) 0 else above - (extra * 0.1f).roundToInt()
        return base.copy(
            gridTop = base.gridTop - bandSpace + above,
            keysFromBottom = base.keysFromBottom - slotSpace,
            dockCentreFromBottom = base.dockCentreFromBottom - slotSpace,
            freedForRows = freed - freedAbove,
        )
    }

    /**
     * The grid Launcher starts from: [gridName] once one is chosen (by the user, a restore or an
     * earlier start), else [STARTING_GRID] on a phone.
     */
    @JvmStatic
    fun startingGridName(gridName: String?, deviceType: Int): String? =
        if (gridName.isNullOrEmpty() && deviceType == InvariantDeviceProfile.TYPE_PHONE) {
            STARTING_GRID
        } else {
            gridName
        }

    /** A key's visible size on the glass at [densityDpi], from its size at density 480. */
    @JvmStatic
    fun glassKeyDp(dp: Float, densityDpi: Int): Float =
        (dp * GLASS_DENSITY / densityDpi / GLASS_STEP_DP).roundToInt() * GLASS_STEP_DP

    /** Launcher's icon size for a key whose visible size is [keyDp]. */
    @JvmStatic fun iconSizeDp(keyDp: Float): Float = keyDp / ICON_VISIBLE_AREA_FACTOR

    /** The size (sp) for [sp] text that stops growing at [TEXT_SCALE_CAP]. */
    @JvmStatic
    fun cappedSp(sp: Float, fontScale: Float): Float =
        if (fontScale > TEXT_SCALE_CAP) sp * TEXT_SCALE_CAP / fontScale else sp

    /** Whether Tally's Home layout applies to a device of [deviceType] with [option]'s grid. */
    @JvmStatic
    fun appliesTo(deviceType: Int, option: DisplayOption): Boolean =
        deviceType == InvariantDeviceProfile.TYPE_PHONE && !option.grid.isFixedLandscape

    /**
     * Sets the upright phone sizes of a grid's display option: the keys (Home and dock, All apps),
     * the names, All apps' rows, and the dock's spacing that puts its keys and the search slot on
     * the rhythm, with the band ([tallies]) and the slot ([search]) on Home or off, as
     * InvariantDeviceProfile read them. Called as Launcher picks the grid, before it reads the
     * option.
     */
    @JvmStatic
    fun applyToDisplayOption(
        info: LauncherDisplayInfo,
        option: DisplayOption,
        tallies: Boolean,
        search: Boolean,
    ) {
        if (!appliesTo(info.deviceType, option)) return
        val dpi = info.densityDpi
        val density = dpi / 160f
        val fontScale = info.fontScale
        val i = INDEX_DEFAULT
        option.textSizes[i] = cappedSp(LABEL_SP, fontScale)
        option.allAppsIconTextSizes[i] = cappedSp(LABEL_SP, fontScale)
        // At the largest display sizes the rows can be too short for a key and its name (on the
        // FP6 from 115 % text): the key shrinks only as far as the name needs (the dock's keys,
        // which Launcher sizes as Home's, follow).
        val heightDp = max(info.currentSize.x, info.currentSize.y) / density
        val labelPx =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                option.textSizes[i],
                info.context.resources.displayMetrics,
            )
        val keyDp =
            fittedKeyDp(
                glassKeyDp(HOME_KEY_DP, dpi),
                homeRowDp(heightDp, fontScale, option.grid.numRows, tallies, search),
                density,
                nameDepthPx(info.context, labelPx),
            )
        option.iconSizes[i] = iconSizeDp(keyDp)
        option.allAppsIconSizes[i] = iconSizeDp(glassKeyDp(ALL_APPS_KEY_DP, dpi))
        option.allAppsCellSize[i].y = ALL_APPS_ROW_DP

        val qsbPx = info.context.resources.getDimensionPixelSize(R.dimen.qsb_widget_height)
        val iconPx = (option.iconSizes[i] * density).roundToInt()
        val dock = dockSpacesDp(iconPx, density, qsbPx, fontScale, search)
        option.hotseatQsbSpace[i] = dock[0]
        option.hotseatBarBottomSpace[i] = dock[1]
    }

    /** The height (dp) of Home's rows with [rows] rows on a canvas [heightDp] tall. */
    @JvmStatic
    @JvmOverloads
    fun homeRowDp(
        heightDp: Float,
        fontScale: Float,
        rows: Int,
        tallies: Boolean = true,
        search: Boolean = true,
    ): Float {
        val r = rhythm(heightDp, fontScale, tallies, search)
        val grid = heightDp - (r.keysFromBottom + r.gridGap) - r.gridTop
        return min(r.rowMax + r.freedForRows / max(1, rows), grid / max(1, rows))
    }

    /**
     * The Home key (dp) for rows [rowDp] tall: [keyDp], or, where its name would reach the key in
     * the next row ([nameClearsNextRow]), smaller in 4 dp steps (to [MIN_FITTED_KEY_DP] at most)
     * only until it no longer does. The key's touch target is its cell, which the rows keep taller
     * than 48 dp.
     */
    @JvmStatic
    fun fittedKeyDp(keyDp: Float, rowDp: Float, density: Float, nameDepthPx: Int): Float {
        var key = keyDp
        while (
            key - GLASS_STEP_DP >= MIN_FITTED_KEY_DP &&
                !nameClearsNextRow(key, rowDp, density, nameDepthPx)
        ) {
            key -= GLASS_STEP_DP
        }
        return key
    }

    /**
     * Whether a [keyDp] key's name, whose descenders end [nameDepthPx] below the top of its line,
     * stays clear of the key in the next row of [rowDp] rows. Launcher's Home icon is the key with
     * its margin, then its drawable padding (7 dp less that margin), then the name's line, centred
     * in the cell: what does not fit spills evenly above and below, and the name may reach into the
     * next cell as far as the margin above that cell's key.
     */
    @JvmStatic
    fun nameClearsNextRow(keyDp: Float, rowDp: Float, density: Float, nameDepthPx: Int): Boolean {
        val iconPx = (iconSizeDp(keyDp) * density).roundToInt()
        val marginPx = (iconPx - (iconPx * ICON_VISIBLE_AREA_FACTOR).roundToInt()) / 2
        val paddingPx = max(0, (ICON_DRAWABLE_PADDING_DP * density + 0.5f).toInt() - marginPx)
        return iconPx + paddingPx + nameDepthPx - rowDp * density <= marginPx
    }

    /**
     * How far below the top of its line (px) a Home name [textSizePx] tall reaches in the names'
     * face: Launcher sets the name's baseline the face's top below the line's top (its font
     * padding), and the name's descenders end at the face's descent.
     */
    private fun nameDepthPx(context: Context, textSizePx: Float): Int {
        val paint = Paint()
        paint.textSize = textSizePx
        paint.typeface =
            Typeface.create(
                Typeface.create(context.getString(R.string.tally_font_family), Typeface.NORMAL),
                LABEL_WEIGHT,
                false,
            )
        val fm = paint.fontMetrics
        return ceil(fm.descent - fm.top).toInt()
    }

    /**
     * The hotseat's space between its keys and the search slot, and below the slot (dp), that put
     * the dock's keys and the slot on the rhythm. The hotseat's bar is its cell (the key and the
     * reach of a folder preview), then that space, the slot and the space below it; its keys are
     * centred in the cell at the top of the bar. Without the slot ([search] false; the hotseat then
     * has no room for it, [hotseatQsbHeightPx]) the bar is the cell and the space below it.
     */
    @JvmStatic
    @JvmOverloads
    fun dockSpacesDp(
        iconPx: Int,
        density: Float,
        qsbPx: Int,
        fontScale: Float,
        search: Boolean = true,
    ): FloatArray {
        val rhythm = rhythm(CANVAS_HEIGHT_DP, fontScale, search = search)
        val cellPx = ceil(iconPx * ICON_OVERLAP_FACTOR)
        val barPx = rhythm.dockCentreFromBottom * density + cellPx / 2f
        if (!search) return floatArrayOf(0f, max(0f, (barPx - iconPx) / density))
        val slotBottomPx = rhythm.slotBottomFromBottom * density
        return floatArrayOf(
            max(0f, (barPx - iconPx - qsbPx - slotBottomPx) / density),
            rhythm.slotBottomFromBottom,
        )
    }

    /**
     * Whether Tally's Home layout applies to a device profile: a phone held upright, with no
     * taskbar, not an external display and not the fixed landscape grid.
     */
    @JvmStatic
    fun appliesTo(
        properties: DeviceProperties,
        isVerticalLayout: Boolean,
        isFixedLandscape: Boolean,
    ): Boolean =
        properties.isPhone &&
            !properties.isLandscape &&
            !isVerticalLayout &&
            !isFixedLandscape &&
            !properties.deviceConfiguration.isExternalDisplay &&
            !properties.taskbarConfiguration.isTaskbarPresent

    /** [appliesTo] for [dp]: an upright phone, where All apps takes Tally's layout too. */
    @JvmStatic
    fun appliesTo(dp: DeviceProfile): Boolean =
        appliesTo(dp.deviceProperties, dp.isVerticalBarLayout, dp.inv.isFixedLandscape)

    /**
     * The height (px) the hotseat keeps for the search slot: none where Tally lays Home out and the
     * slot is taken off ([InvariantDeviceProfile.tallySearchShown]), else Launcher's
     * qsb_widget_height.
     */
    @JvmStatic
    fun hotseatQsbHeightPx(
        res: Resources,
        properties: DeviceProperties,
        inv: InvariantDeviceProfile,
        isVerticalLayout: Boolean,
    ): Int =
        if (
            appliesTo(properties, isVerticalLayout, inv.isFixedLandscape) && !inv.tallySearchShown
        ) {
            0
        } else {
            res.getDimensionPixelSize(R.dimen.qsb_widget_height)
        }

    /**
     * The workspace's top and bottom padding (as the non-scalable workspace adds them to its edge
     * margin and its hotseat and page indicator) that put the grid on the rhythm: its top on the
     * rhythm's line, its bottom the rhythm's gap above the dock's band, rows no taller than the
     * rhythm allows, with the band and the slot as [inv] read them from Home settings. Null where
     * Tally's layout does not apply (landscape, a tablet, an external display, a taskbar).
     */
    @JvmStatic
    fun workspacePaddingsPx(
        res: Resources,
        properties: DeviceProperties,
        isVerticalLayout: Boolean,
        inv: InvariantDeviceProfile,
        edgeMarginPx: Int,
        hotseatBarSizePx: Int,
        pageIndicatorPx: Int,
    ): IntArray? {
        if (!appliesTo(properties, isVerticalLayout, inv.isFixedLandscape) || inv.numRows <= 0) {
            return null
        }
        return gridPaddingsPx(
            heightPx = properties.heightPx,
            density = res.displayMetrics.density,
            fontScale = res.configuration.fontScale,
            insetTopPx = properties.insets.top,
            numRows = inv.numRows,
            edgeMarginPx = edgeMarginPx,
            hotseatBarSizePx = hotseatBarSizePx,
            pageIndicatorPx = pageIndicatorPx,
            tallies = inv.tallyBandShown,
            search = inv.tallySearchShown,
        )
    }

    /** [workspacePaddingsPx]'s arithmetic, for a screen [heightPx] tall. */
    @JvmStatic
    @JvmOverloads
    fun gridPaddingsPx(
        heightPx: Int,
        density: Float,
        fontScale: Float,
        insetTopPx: Int,
        numRows: Int,
        edgeMarginPx: Int,
        hotseatBarSizePx: Int,
        pageIndicatorPx: Int,
        tallies: Boolean = true,
        search: Boolean = true,
    ): IntArray {
        val rhythm = rhythm(heightPx / density, fontScale, tallies, search)
        val gridTopPx = rhythm.gridTop * density
        var gridBottomPx = heightPx - (rhythm.keysFromBottom + rhythm.gridGap) * density
        gridBottomPx =
            min(gridBottomPx, gridTopPx + (rhythm.rowMax * numRows + rhythm.freedForRows) * density)
        // The cells start at the top inset plus the top padding and the edge margin, and end at
        // the hotseat's bar, the page indicator and the bottom padding.
        val top = gridTopPx - insetTopPx - edgeMarginPx
        val bottom = heightPx - gridBottomPx - hotseatBarSizePx - pageIndicatorPx
        return intArrayOf(max(0, top.roundToInt()), max(0, bottom.roundToInt()))
    }
}
