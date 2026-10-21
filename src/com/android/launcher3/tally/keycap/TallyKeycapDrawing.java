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

package com.android.launcher3.tally.keycap;

import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.Nullable;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.graphics.PreloadIconDelegate;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.model.data.ItemInfo;

/** A BubbleTextView's keycap: its relief and LED over the icon, and its press. */
public final class TallyKeycapDrawing {

    private TallyKeycapDrawing() {}

    /**
     * Draws the keycap's relief over the icon just drawn, and moves a key that is being pressed or
     * let go.
     */
    public static void drawIfNecessary(BubbleTextView view, Canvas canvas,
            @Nullable TallyKeycap keycap, @Nullable FastBitmapDrawable icon, boolean iconVisible,
            Rect keycapBounds, ThemeManager themeManager) {
        if (keycap == null || icon == null || !iconVisible
                || icon.getDelegate() instanceof PreloadIconDelegate) {
            return;
        }
        view.getIconBounds(keycapBounds);
        float drawnScale = FastBitmapDrawable.SCALE.get(icon);
        final int scrollX = view.getScrollX();
        final int scrollY = view.getScrollY();
        canvas.translate(scrollX, scrollY);
        // Its LED shows its app's state wherever the key is drawn: under its long-press menu and
        // in the drag's image too, where stock hides the dot.
        keycap.draw(canvas, keycapBounds, drawnScale,
                themeManager.getIconShape(),
                view.getTag() instanceof ItemInfo info && info.isInHotseat(), /* hideLed= */ false);
        canvas.translate(-scrollX, -scrollY);
        float nextScale = keycap.advance();
        if (nextScale != drawnScale) {
            // Invalidates the view: the next frame draws the icon at the new scale.
            FastBitmapDrawable.SCALE.set(icon, nextScale);
        } else if (keycap.isMoving()) {
            view.postInvalidateOnAnimation();
        }
    }

    /** Presses or lets go of the key with the view's drawable state. True if it must redraw. */
    public static boolean setPressed(TallyKeycap keycap, int[] drawableState) {
        boolean pressed = false;
        for (int state : drawableState) {
            if (state == android.R.attr.state_pressed) {
                pressed = true;
                break;
            }
        }
        return keycap.setPressed(pressed);
    }
}
