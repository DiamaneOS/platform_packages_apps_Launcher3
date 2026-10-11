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

package com.android.launcher3.tally.search

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.launcher3.R
import com.android.launcher3.tally.search.TallyFrequentStore.Shown
import com.android.launcher3.tally.search.TallyFrequentStore.ShownApp
import com.android.launcher3.tally.search.TallyFrequentStore.ShownPage

/**
 * The "Suggestions" row of Home's search, shown while nothing is typed. Its title and the word
 * "Settings" are stock Launcher strings, used for their translations.
 */
class TallyFrequentRow(context: Context) : LinearLayout(context) {

    /** The view is the item's icon, which the launch animates from. */
    var onOpen: (View, Shown) -> Unit = { _, _ -> }
    var onDismiss: (Shown) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val ink = context.getColor(R.color.tally_ink)
    private val inkMuted = context.getColor(R.color.tally_ink_muted)
    private val radius = resources.getDimension(R.dimen.tally_radius_m)
    private val spaceXs = resources.getDimensionPixelSize(R.dimen.tally_space_xs)
    private val spaceS = resources.getDimensionPixelSize(R.dimen.tally_space_s)
    private val spaceM = resources.getDimensionPixelSize(R.dimen.tally_space_m)
    private val rowMinHeight = resources.getDimensionPixelSize(R.dimen.tally_row_min_height)
    private val glyphSize = resources.getDimensionPixelSize(R.dimen.tally_icon_size)
    private val iconSize = (ICON_DP * density).toInt()

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    fun showItems(items: List<Shown>) {
        removeAllViews()
        if (items.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        addTitle()
        val shown = items.take(TallyFrequent.MAX_SHOWN)
        val cells = LinearLayout(context).apply { orientation = HORIZONTAL }
        shown.forEach { cells.addView(cell(it), LayoutParams(0, WRAP_CONTENT, 1f)) }
        // Each item keeps a fifth of the row, however few there are.
        repeat(TallyFrequent.MAX_SHOWN - shown.size) {
            cells.addView(Space(context), LayoutParams(0, 0, 1f))
        }
        addView(cells, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun addTitle() {
        addView(
            TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_Tally_Section)
                setTextColor(inkMuted)
                setText(R.string.suggested_widgets_header_title)
                isAccessibilityHeading = true
                setPaddingRelative(spaceS, spaceM, spaceS, spaceXs)
            },
            LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    private fun cell(item: Shown): View {
        val title: CharSequence
        val description: CharSequence
        val dismissLabel: CharSequence
        val iconView =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        when (item) {
            is ShownApp -> {
                title = item.app.title ?: ""
                description = item.app.contentDescription ?: title
                dismissLabel = context.getString(R.string.tally_frequent_dismiss)
                iconView.setImageDrawable(item.app.newIcon(context))
            }
            is ShownPage -> {
                title = item.page.title
                description =
                    context.getString(
                        R.string.tally_key_with_state,
                        title,
                        context.getString(R.string.widget_settings),
                    )
                dismissLabel = context.getString(R.string.tally_frequent_dismiss)
                // A page's glyph is smaller than an app's icon, so it is centred in the same room.
                val inset = (iconSize - glyphSize) / 2
                iconView.setPadding(inset, inset, inset, inset)
                iconView.setImageResource(R.drawable.tally_ic_search_settings)
                iconView.imageTintList = ColorStateList.valueOf(inkMuted)
            }
        }
        val cell =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = rowMinHeight
                setPaddingRelative(spaceXs, spaceS, spaceXs, spaceS)
                isClickable = true
                isFocusable = true
                background = pressBackground()
                contentDescription = description
            }
        cell.addView(iconView, LayoutParams(iconSize, iconSize))
        cell.addView(
            TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_Tally_Caption)
                setTextColor(ink)
                text = title
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = spaceXs },
        )
        cell.setOnClickListener { onOpen(iconView, item) }
        cell.setOnLongClickListener {
            PopupMenu(context, cell).apply {
                menu.add(dismissLabel).setOnMenuItemClickListener {
                    onDismiss(item)
                    true
                }
                show()
            }
            true
        }
        cell.accessibilityDelegate =
            object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        AccessibilityAction(R.id.action_dismiss_prediction, dismissLabel)
                    )
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    if (action != R.id.action_dismiss_prediction) {
                        return super.performAccessibilityAction(host, action, args)
                    }
                    onDismiss(item)
                    return true
                }
            }
        return cell
    }

    private fun pressBackground(): Drawable {
        val mask =
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = radius
            }
        return RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, PRESS_ALPHA)),
            null,
            mask,
        )
    }

    private companion object {
        const val ICON_DP = 40f
        /** The ink at 12 %. */
        const val PRESS_ALPHA = 0x1F
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
