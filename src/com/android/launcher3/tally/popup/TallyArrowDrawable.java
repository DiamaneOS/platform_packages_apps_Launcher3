/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.launcher3.tally.popup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;

import androidx.annotation.Nullable;

import com.android.launcher3.popup.RoundedArrowDrawable;

/**
 * A popup's arrow for a popup with an edge [edgeWidth] wide in [edgeColor] (none if 0): the arrow
 * draws the edge along its sides and covers the popup's edge where the two meet, so the edge runs
 * round the arrow.
 */
public class TallyArrowDrawable extends RoundedArrowDrawable {

    // The popup's edge along the arrow's sides, or null for none.
    @Nullable private final Path mEdgePath;
    @Nullable private final Paint mEdgePaint;

    /** As RoundedArrowDrawable's default constructor, with the popup's edge. */
    public TallyArrowDrawable(float width, float height, float radius, float popupRadius,
            float popupWidth, float popupHeight,
            float arrowOffsetX, float arrowOffsetY, boolean isPointingUp, boolean leftAligned,
            int color, float edgeWidth, int edgeColor) {
        // With an edge, the arrow reaches over the popup's edge where they meet.
        super(width, height, radius, popupRadius, popupWidth, popupHeight, arrowOffsetX,
                arrowOffsetY - edgeWidth, isPointingUp, leftAligned, color);

        if (edgeWidth > 0) {
            // RoundedArrowDrawable's flip.
            Matrix pathTransform = new Matrix();
            pathTransform.setScale(
                    leftAligned ? 1 : -1, isPointingUp ? -1 : 1, width * 0.5f, height * 0.5f);
            // The sides and the tip, drawn twice as wide and clipped to the arrow: the edge is
            // inside it, as the popup's edge is inside the popup.
            mEdgePath = new Path();
            addDownPointingRoundedTriangleSidesToPath(width, height, radius, mEdgePath);
            mEdgePath.transform(pathTransform);
            mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mEdgePaint.setStyle(Paint.Style.STROKE);
            mEdgePaint.setStrokeWidth(2 * edgeWidth);
            mEdgePaint.setColor(edgeColor);
        } else {
            mEdgePath = null;
            mEdgePaint = null;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (mEdgePath != null) {
            int count = canvas.save();
            canvas.clipPath(mPath);
            canvas.drawPath(mEdgePath, mEdgePaint);
            canvas.restoreToCount(count);
        }
    }

    @Override
    public void setAlpha(int i) {
        super.setAlpha(i);
        if (mEdgePaint != null) {
            mEdgePaint.setAlpha(i);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        super.setColorFilter(colorFilter);
        if (mEdgePaint != null) {
            mEdgePaint.setColorFilter(colorFilter);
        }
    }

    /**
     * Adds the sides and the rounded tip of [addDownPointingRoundedTriangleToPath]'s triangle to
     * [path], from its top right corner round to its top left one, without its top.
     */
    private static void addDownPointingRoundedTriangleSidesToPath(float width, float height,
            float radius, Path path) {
        Path triangle = new Path();
        addDownPointingRoundedTriangleToPath(width, height, radius, triangle);
        // The triangle starts along its top from (0, 0): skip that line.
        PathMeasure measure = new PathMeasure(triangle, /* forceClosed= */ false);
        measure.getSegment(width, measure.getLength(), path, /* startWithMoveTo= */ true);
    }
}
