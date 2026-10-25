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

package com.android.launcher3.tally.home;

/**
 * Finds a double tap among the touches on empty Home. A tap starts on empty Home, stays within
 * the touch slop and ends before a long press would. A second tap counts when it starts within
 * the double tap timeout of the first one's end, near where the first one started.
 */
public final class TallyDoubleTap {
    private final float mTouchSlop;
    private final float mDoubleTapSlop;
    private final long mDoubleTapTimeout;
    private final long mLongPressTimeout;

    private boolean mTouchIsTap;
    private float mDownX;
    private float mDownY;
    private long mDownTime;

    private boolean mHasFirstTap;
    private float mFirstTapX;
    private float mFirstTapY;
    private long mFirstTapUpTime;

    public TallyDoubleTap(float touchSlop, float doubleTapSlop, long doubleTapTimeout,
            long longPressTimeout) {
        mTouchSlop = touchSlop;
        mDoubleTapSlop = doubleTapSlop;
        mDoubleTapTimeout = doubleTapTimeout;
        mLongPressTimeout = longPressTimeout;
    }

    /** A touch starts. onEmptySpace says whether it starts on empty Home. */
    public void onDown(long time, float x, float y, boolean onEmptySpace) {
        mTouchIsTap = onEmptySpace;
        mDownX = x;
        mDownY = y;
        mDownTime = time;
        if (mHasFirstTap && (!onEmptySpace || time - mFirstTapUpTime > mDoubleTapTimeout
                || Math.hypot(x - mFirstTapX, y - mFirstTapY) > mDoubleTapSlop)) {
            mHasFirstTap = false;
        }
    }

    public void onMove(float x, float y) {
        if (mTouchIsTap && Math.hypot(x - mDownX, y - mDownY) > mTouchSlop) {
            cancel();
        }
    }

    /** The touch is not a tap: a second finger, a scroll or a cancel. */
    public void cancel() {
        mTouchIsTap = false;
        mHasFirstTap = false;
    }

    /** The touch ends. Returns whether it is the second tap of a double tap. */
    public boolean onUp(long time) {
        final boolean tap = mTouchIsTap && time - mDownTime < mLongPressTimeout;
        mTouchIsTap = false;
        if (!tap) {
            mHasFirstTap = false;
            return false;
        }
        if (mHasFirstTap) {
            mHasFirstTap = false;
            return true;
        }
        mHasFirstTap = true;
        mFirstTapX = mDownX;
        mFirstTapY = mDownY;
        mFirstTapUpTime = time;
        return false;
    }
}
