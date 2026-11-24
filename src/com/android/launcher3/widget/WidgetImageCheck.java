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

package com.android.launcher3.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.LauncherApps.PinItemRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DiamaneOS: the app images in a widget's views are decoded in the protected VM, never in the launcher. Every
 * RemoteViews an app hands the launcher (home screen widgets with their list items and nested views, generated
 * previews, the preview of a pin request) is shown only as a copy checked on an image thread: URI and data icons and
 * image links become pixels decoded in the VM, empty when they cannot be made, and views that cannot be checked are
 * not shown. Resource images come from clean copies through the process's gate as before.
 *
 * <p>The check is made by the implementation {@link #install}ed at the process's start (Quickstep). Without one,
 * every check gives nothing: widgets show the error view and previews fall back to their other forms.
 */
public abstract class WidgetImageCheck {
    private static final String TAG = "WidgetImageCheck";

    /** How long a background caller waits for a checked preview. */
    public static final long PREVIEW_WAIT_MS = 3_000;

    /** Makes the check of one host view, for a view made with {@code context}. */
    public interface Factory {
        WidgetImageCheck create(Context context);
    }

    private static volatile Factory sFactory;

    /** Sets the implementation, once, at the process's start. */
    public static void install(Factory factory) {
        sFactory = factory;
    }

    /** A check for one host view made with {@code context}. */
    public static WidgetImageCheck create(Context context) {
        final Factory factory = sFactory;
        if (factory != null) {
            try {
                return factory.create(context);
            } catch (RuntimeException | LinkageError e) {
                Log.e(TAG, "no widget image check, widgets are not shown", e);
            }
        }
        return new Closed(context);
    }

    /** The context to make the host view with. */
    public abstract Context context();

    /** The widget's app, from the system's provider info (null: none). Drops an update still being checked. */
    public abstract void setApp(@Nullable AppWidgetProviderInfo info);

    /** Drops the update being checked: it never delivers. */
    public abstract void cancel();

    /**
     * Checks {@code views} on an image thread and hands the checked copy, or null when it cannot be shown, to
     * {@code done} on {@code executor}. A newer call drops an older one, which then never delivers.
     */
    public abstract void prepare(RemoteViews views, Executor executor, Consumer<RemoteViews> done);

    /**
     * The checked copy of {@code views} of the widget {@code info}, waiting up to {@code timeoutMs}. Null on the main
     * thread, when it cannot be checked, or when the time is up. For background loaders.
     */
    @Nullable
    public static RemoteViews checkNow(Context context, @Nullable RemoteViews views,
            @Nullable AppWidgetProviderInfo info, long timeoutMs) {
        if (views == null || Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        final WidgetImageCheck check = create(context);
        check.setApp(info);
        final CompletableFuture<RemoteViews> result = new CompletableFuture<>();
        check.prepare(views, Runnable::run, result::complete);
        try {
            return result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            check.cancel();
            Log.w(TAG, "widget preview not checked, not shown: " + e);
            return null;
        }
    }

    /**
     * Hands {@code show} the checked copy of the preview a pin request carries, on the main thread: null when it has
     * none, when it cannot be checked, or after {@link #PREVIEW_WAIT_MS}. {@code show} runs once.
     */
    public static void checkPinPreview(Context context, PinItemRequest request, Consumer<RemoteViews> show) {
        final RemoteViews preview = pinPreview(request);
        if (preview == null) {
            show.accept(null);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] shown = {false};
        final Consumer<RemoteViews> once = views -> {
            if (!shown[0]) {
                shown[0] = true;
                show.accept(views);
            }
        };
        final WidgetImageCheck check = create(context);
        check.setApp(request.getAppWidgetProviderInfo(context));
        check.prepare(preview, main::post, once);
        main.postDelayed(() -> {
            check.cancel();
            once.accept(null);
        }, PREVIEW_WAIT_MS);
    }

    @Nullable
    private static RemoteViews pinPreview(PinItemRequest request) {
        if (request == null || request.getRequestType() != PinItemRequest.REQUEST_TYPE_APPWIDGET) {
            return null;
        }
        final Bundle extras = request.getExtras();
        return extras == null ? null
                : extras.getParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, RemoteViews.class);
    }

    // Without an implementation: nothing is checked, so nothing is shown.
    private static final class Closed extends WidgetImageCheck {
        private final Context mContext;

        Closed(Context context) {
            mContext = context;
        }

        @Override
        public Context context() {
            return mContext;
        }

        @Override
        public void setApp(@Nullable AppWidgetProviderInfo info) {
        }

        @Override
        public void cancel() {
        }

        @Override
        public void prepare(RemoteViews views, Executor executor, Consumer<RemoteViews> done) {
            executor.execute(() -> done.accept(null));
        }
    }
}
