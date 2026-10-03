/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.widgetpicker.repository

import android.content.Context
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.BitmapRenderer
import com.android.launcher3.icons.IconCache
import com.android.launcher3.icons.ThemedBitmap
import com.android.launcher3.icons.cache.CacheLookupFlag
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.PackageItemInfo
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.widgetpicker.data.repository.WidgetAppIconsRepository
import com.android.launcher3.widgetpicker.shared.model.AppIcon
import com.android.launcher3.widgetpicker.shared.model.AppIconBadge
import com.android.launcher3.widgetpicker.shared.model.WidgetAppIcon
import com.android.launcher3.widgetpicker.shared.model.WidgetAppId
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * An implementation of [WidgetAppIconsRepository] that provides the app icons for the widget picker
 * using the [IconCache].
 */
class WidgetAppIconsRepositoryImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val iconCache: IconCache,
    private val themeManager: ThemeManager,
) : WidgetAppIconsRepository {
    override fun initialize() {} // nothing to do here.

    override fun getAppIcon(widgetAppId: WidgetAppId) = callbackFlow {
        trySend(WidgetAppIcon(AppIcon.PlaceHolderAppIcon, AppIconBadge.NoBadge))

        val category = widgetAppId.category
        val packageItemInfo =
            if (category != null) {
                PackageItemInfo(widgetAppId.packageName, category, widgetAppId.userHandle)
            } else {
                PackageItemInfo(widgetAppId.packageName, widgetAppId.userHandle)
            }

        // DiamaneOS Tally: an app's icon follows the icon style, as its key does on Home.
        val themed = themeManager.isIconThemeEnabled
        iconCache.updateIconInBackground(
            MAIN_EXECUTOR,
            { itemInfoWithIcon ->
                itemInfoWithIcon?.let {
                    if (itemInfoWithIcon.bitmap.isLowRes) {
                        trySend(
                            WidgetAppIcon(
                                icon = AppIcon.LowResColorIcon(itemInfoWithIcon.bitmap.color),
                                badge = AppIconBadge.NoBadge,
                            )
                        )
                    } else {
                        trySend(
                            WidgetAppIcon(
                                icon =
                                    (if (themed) themedIcon(itemInfoWithIcon) else null)
                                        ?: AppIcon.HighResBitmapIcon(
                                            bitmap = itemInfoWithIcon.bitmap.icon,
                                            isFullBleed =
                                                itemInfoWithIcon.bitmap.flags and
                                                    BitmapInfo.FLAG_FULL_BLEED ==
                                                    BitmapInfo.FLAG_FULL_BLEED,
                                        ),
                                badge =
                                    itemInfoWithIcon.bitmap.getBadgeType()?.let {
                                        AppIconBadge.DrawableBadge(it.drawableRes, it.colorRes)
                                    } ?: AppIconBadge.NoBadge,
                            )
                        )
                    }
                }
            },
            packageItemInfo,
            CacheLookupFlag.DEFAULT_LOOKUP_FLAG.withThemeIcon(themed),
        )

        awaitClose()
    }

    override fun cleanUp() {} // nothing to do here.

    /**
     * DiamaneOS Tally: the app's key in the icon style, drawn in its own shape as Home draws it
     * (the badge is the picker's own), or null when the style leaves the app its own icon.
     */
    private fun themedIcon(info: ItemInfoWithIcon): AppIcon? {
        val themedBitmap = info.bitmap.themedBitmap
        if (themedBitmap == null || themedBitmap === ThemedBitmap.NOT_SUPPORTED) return null
        val icon = info.newIcon(context, BitmapInfo.FLAG_THEMED or BitmapInfo.FLAG_NO_BADGE)
        val width = info.bitmap.icon.width
        val height = info.bitmap.icon.height
        icon.setBounds(0, 0, width, height)
        // The themed glyph may be a hardware bitmap, which only a recorded picture can draw.
        return AppIcon.HighResBitmapIcon(
            bitmap = BitmapRenderer.createHardwareBitmap(width, height) { icon.draw(it) },
            isFullBleed = false,
        )
    }
}
