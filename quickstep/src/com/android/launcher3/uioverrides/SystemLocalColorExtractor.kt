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

package com.android.launcher3.uioverrides

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.SparseIntArray
import android.widget.RemoteViews
import com.android.launcher3.widget.ColorsOverride
import com.android.launcher3.widget.LocalColorExtractor
import javax.inject.Inject

/**
 * DiamaneOS: shows the colours Wallpaper & style is previewing in Launcher's Home preview.
 *
 * The picker sends the system colours of the option being previewed (PreviewSurfaceRenderer's
 * color_resource_ids and color_values). Stock Launcher3's [LocalColorExtractor] does nothing with
 * them, so the preview kept the applied colours until the option was applied. This loads them on
 * the preview's context as a resources loader, as the platform does for a widget's colours
 * (AppWidgetHostView.setColorResources), and gives the preview's widgets the same colours. Only the
 * system palette and dynamic colours can be overridden this way; the picker sends no others that
 * Launcher reads.
 */
class SystemLocalColorExtractor @Inject constructor() : LocalColorExtractor() {

    override fun applyColorsOverride(base: Context, colors: SparseIntArray): ColorsOverride? {
        val resources = RemoteViews.ColorResources.create(base, colors) ?: return null
        return LoadedColors(resources, colors.clone()).also { it.applyTo(base) }
    }

    private class LoadedColors(
        private val resources: RemoteViews.ColorResources,
        private val colors: SparseIntArray,
    ) : ColorsOverride {

        override fun applyTo(context: Context) {
            resources.apply(context)
        }

        override fun applyTo(widget: AppWidgetHostView) {
            widget.setColorResources(colors)
        }
    }
}
