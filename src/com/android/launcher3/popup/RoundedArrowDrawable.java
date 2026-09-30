/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.launcher3.popup;

import static java.lang.Math.atan;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.toDegrees;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

/**
 * A drawable for a very specific purpose. Used for the caret arrow on a rounded rectangle popup
 * bubble.
 * Draws a triangle with one rounded tip, the opposite edge is clipped by the body of the popup
 * so there is no overlap when drawing them together.
 */
public class RoundedArrowDrawable extends Drawable {

    private final Path mPath;
    private final Paint mPaint;
    // DiamaneOS Tally: the popup's edge along the arrow's sides, or null for none.
    @Nullable private final Path mEdgePath;
    @Nullable private final Paint mEdgePaint;

    /**
     * Default constructor.
     *
     * @param width of the arrow.
     * @param height of the arrow.
     * @param radius of the tip of the arrow.
     * @param popupRadius of the rect to clip this by.
     * @param popupWidth of the rect to clip this by.
     * @param popupHeight of the rect to clip this by.
     * @param arrowOffsetX from the edge of the popup to the arrow.
     * @param arrowOffsetY how much the arrow will overlap the popup.
     * @param isPointingUp or not.
     * @param leftAligned or false for right aligned.
     * @param color to draw the triangle.
     */
    public RoundedArrowDrawable(float width, float height, float radius, float popupRadius,
            float popupWidth, float popupHeight,
            float arrowOffsetX, float arrowOffsetY, boolean isPointingUp, boolean leftAligned,
            int color) {
        this(width, height, radius, popupRadius, popupWidth, popupHeight, arrowOffsetX,
                arrowOffsetY, isPointingUp, leftAligned, color, 0, 0);
    }

    /**
     * As the default constructor, for a popup with an edge [edgeWidth] wide in [edgeColor] (none
     * if 0): the arrow draws the edge along its sides and covers the popup's edge where the two
     * meet, so the edge runs round the arrow (DiamaneOS Tally).
     */
    public RoundedArrowDrawable(float width, float height, float radius, float popupRadius,
            float popupWidth, float popupHeight,
            float arrowOffsetX, float arrowOffsetY, boolean isPointingUp, boolean leftAligned,
            int color, float edgeWidth, int edgeColor) {
        mPath = new Path();
        mPaint = new Paint();
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setAntiAlias(true);

        // Make the drawable with the triangle pointing down and positioned on the left..
        addDownPointingRoundedTriangleToPath(width, height, radius, mPath);
        // With an edge, the arrow reaches over the popup's edge where they meet.
        clipPopupBodyFromPath(popupRadius, popupWidth, popupHeight, arrowOffsetX,
                arrowOffsetY - edgeWidth, mPath);

        // ... then flip it horizontal or vertical based on where it will be used.
        Matrix pathTransform = new Matrix();
        pathTransform.setScale(
                leftAligned ? 1 : -1, isPointingUp ? -1 : 1, width * 0.5f, height * 0.5f);
        mPath.transform(pathTransform);

        if (edgeWidth > 0) {
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

    /**
     * Constructor for an arrow that points to the left or right.
     *
     * @param width        of the arrow.
     * @param height       of the arrow.
     * @param radius       of the tip of the arrow.
     * @param isHorizontal or not.
     * @param isLeftOrTop  or not.
     * @param color        to draw the triangle.
     */
    private RoundedArrowDrawable(float width, float height, float radius, boolean isHorizontal,
            boolean isLeftOrTop, int color) {
        mEdgePath = null;
        mEdgePaint = null;
        mPath = new Path();
        mPaint = new Paint();
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setAntiAlias(true);

        // Make the drawable with the triangle pointing down...
        addDownPointingRoundedTriangleToPath(width, height, radius, mPath);

        if (isHorizontal || isLeftOrTop) {
            // ... then rotate it to the side it needs to point.
            Matrix pathTransform = new Matrix();
            int rotationAngle;
            if (isHorizontal) {
                rotationAngle = isLeftOrTop ? 90 : -90;
            } else {
                // it could only be vertical arrow pointing up
                rotationAngle = 180;
            }
            pathTransform.setRotate(rotationAngle, width * 0.5f, height * 0.5f);
            mPath.transform(pathTransform);
        }
    }

    /**
     * factory method for an arrow that points to the left or right.
     *
     * @param width          of the arrow.
     * @param height         of the arrow.
     * @param radius         of the tip of the arrow.
     * @param isPointingLeft or not.
     * @param color          to draw the triangle.
     */
    public static RoundedArrowDrawable createHorizontalRoundedArrow(float width, float height,
            float radius, boolean isPointingLeft, int color) {
        return new RoundedArrowDrawable(width, height, radius, true, isPointingLeft, color);
    }

    /**
     * factory method for an arrow that points to the left or right.
     *
     * @param width        of the arrow.
     * @param height       of the arrow.
     * @param radius       of the tip of the arrow.
     * @param isPointingUp or not.
     * @param color        to draw the triangle.
     */
    public static RoundedArrowDrawable createVerticalRoundedArrow(float width, float height,
            float radius, boolean isPointingUp, int color) {
        return new RoundedArrowDrawable(width, height, radius, false, isPointingUp, color);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(mPath, mPaint);
        if (mEdgePath != null) {
            int count = canvas.save();
            canvas.clipPath(mPath);
            canvas.drawPath(mEdgePath, mEdgePaint);
            canvas.restoreToCount(count);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setPath(mPath);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int i) {
        mPaint.setAlpha(i);
        if (mEdgePaint != null) {
            mEdgePaint.setAlpha(i);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        if (mEdgePaint != null) {
            mEdgePaint.setColorFilter(colorFilter);
        }
    }

    /**
     * Set shadow layer to internal {@link Paint#setShadowLayer(float, float, float, int) paint}
     * object
     */
    public void setShadowLayer(float shadowBlur, float dx, float dy, int shadowColor) {
        mPaint.setShadowLayer(shadowBlur, dx, dy, shadowColor);
    }

    /**
     * Adds rounded triangle pointing down to the provided {@link Path path} argument
     */
    public static void addDownPointingRoundedTriangleToPath(float width, float height,
            float radius, Path path) {
        // Calculated for the arrow pointing down, will be flipped later if needed.

        // Theta is half of the angle inside the triangle tip
        float tanTheta = width / (2.0f * height);
        float theta = (float) atan(tanTheta);

        // Some trigonometry to find the center of the circle for the rounded tip
        float roundedPointCenterY = (float) (height - (radius / sin(theta)));

        // p is the distance along the triangle side to the intersection with the point circle
        float p = radius / tanTheta;
        float lineRoundPointIntersectFromCenter = (float) (p * sin(theta));
        float lineRoundPointIntersectFromTop = (float) (height - (p * cos(theta)));

        float centerX = width / 2.0f;
        float thetaDeg = (float) toDegrees(theta);

        path.reset();
        path.moveTo(0, 0);
        // Draw the top
        path.lineTo(width, 0);
        // Draw the right side up to the circle intersection
        path.lineTo(
                centerX + lineRoundPointIntersectFromCenter,
                lineRoundPointIntersectFromTop);
        // Draw the rounded point
        path.arcTo(
                centerX - radius,
                roundedPointCenterY - radius,
                centerX + radius,
                roundedPointCenterY + radius,
                thetaDeg,
                180 - (2 * thetaDeg),
                false);
        // Draw the left edge to close
        path.lineTo(0, 0);
        path.close();
    }

    /**
     * Adds the sides and the rounded tip of [addDownPointingRoundedTriangleToPath]'s triangle to
     * [path], from its top right corner round to its top left one, without its top (DiamaneOS
     * Tally).
     */
    private static void addDownPointingRoundedTriangleSidesToPath(float width, float height,
            float radius, Path path) {
        Path triangle = new Path();
        addDownPointingRoundedTriangleToPath(width, height, radius, triangle);
        // The triangle starts along its top from (0, 0): skip that line.
        PathMeasure measure = new PathMeasure(triangle, /* forceClosed= */ false);
        measure.getSegment(width, measure.getLength(), path, /* startWithMoveTo= */ true);
    }

    private static void clipPopupBodyFromPath(float popupRadius, float popupWidth,
            float popupHeight, float arrowOffsetX, float arrowOffsetY, Path path) {
        // Make a path that is used to clip the triangle, this represents the body of the popup
        Path clipPiece = new Path();
        clipPiece.addRoundRect(
                0, 0, popupWidth, popupHeight,
                popupRadius, popupRadius, Path.Direction.CW);
        // clipping is performed as if the arrow is pointing down and positioned on the left, the
        // resulting path will be flipped as needed later.
        // The extra 0.5 in the vertical offset is to close the gap between this anti-aliased object
        // and the anti-aliased body of the popup.
        clipPiece.offset(-arrowOffsetX, -popupHeight + arrowOffsetY - 0.5f);
        path.op(clipPiece, Path.Op.DIFFERENCE);
    }
}
