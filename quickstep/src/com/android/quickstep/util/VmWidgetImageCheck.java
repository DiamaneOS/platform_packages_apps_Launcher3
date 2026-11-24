/*
 * Copyright (C) 2026 DiamaneOS
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

package com.android.quickstep.util;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import com.android.launcher3.widget.WidgetImageCheck;

import de.diamaneos.decode.client.WidgetImages;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * DiamaneOS: {@link WidgetImageCheck} made by the decode client's {@link WidgetImages}, as SystemUI checks the widgets
 * it hosts: URI, data and resource icons, image links and list items are re-made from pixels decoded in the
 * protected VM, charged to the widget's app. The app's resource files load through the launcher's process gate.
 */
public final class VmWidgetImageCheck extends WidgetImageCheck {
    private final WidgetImages mImages;

    public VmWidgetImageCheck(Context context) {
        mImages = WidgetImages.forLauncher(context);
    }

    @Override
    public Context context() {
        return mImages.context();
    }

    @Override
    public void setApp(@Nullable AppWidgetProviderInfo info) {
        mImages.setApp(info == null || info.providerInfo == null ? null : info.providerInfo.applicationInfo);
    }

    @Override
    public void cancel() {
        mImages.cancel();
    }

    @Override
    public void prepare(RemoteViews views, Executor executor, Consumer<RemoteViews> done) {
        mImages.prepare(views, executor, done);
    }
}
