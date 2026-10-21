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

package com.android.launcher3.tally.motion;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The window a FloatingIconView stands in for, as a rounded rect in the view's own coordinates.
 * While set, nothing of the view is drawn outside it.
 */
public final class TallyWindowClip {
    private final Path mWindowClip = new Path();
    private boolean mClipToWindow;

    /**
     * Draws {@code view} only inside the window it stands in for, the rounded rect
     * {@code windowRect} (in the coordinates of the rect given to FloatingIconView#update) with
     * corners {@code windowRadius}, or everywhere again when {@code windowRect} is null. Tally's
     * motion places the view on the key's whole icon bounds, larger than the window, so that the
     * key inside them matches the window. The icon's background fills those bounds, and would
     * show around the window as a rim. Call it after each update, which moves and scales the
     * view. Allocates nothing.
     */
    public void set(View view, @Nullable RectF windowRect, float windowRadius) {
        mWindowClip.rewind();
        mClipToWindow = windowRect != null;
        float sx = view.getScaleX();
        float sy = view.getScaleY();
        if (windowRect != null && sx > 0 && sy > 0) {
            // The view is scaled about its top left corner (ClipIconView#update).
            float x = view.getLeft() + view.getTranslationX();
            float y = view.getTop() + view.getTranslationY();
            mWindowClip.addRoundRect((windowRect.left - x) / sx, (windowRect.top - y) / sy,
                    (windowRect.right - x) / sx, (windowRect.bottom - y) / sy,
                    windowRadius / sx, windowRadius / sy, Path.Direction.CW);
        }
        view.invalidate();
    }

    /**
     * Clips {@code canvas} to the window while one is set: the icon, its background and its badge
     * stay inside it. Returns the save count for {@link #restore}, or -1.
     */
    public int clip(Canvas canvas) {
        int count = -1;
        if (mClipToWindow) {
            count = canvas.save();
            canvas.clipPath(mWindowClip);
        }
        return count;
    }

    /** Undoes {@link #clip}. */
    public static void restore(Canvas canvas, int count) {
        if (count >= 0) {
            canvas.restoreToCount(count);
        }
    }

    /** Draws everywhere again. */
    public void reset() {
        mClipToWindow = false;
        mWindowClip.rewind();
    }
}
