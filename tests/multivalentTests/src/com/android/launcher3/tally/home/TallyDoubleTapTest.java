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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home's double tap, on empty Home only. */
public class TallyDoubleTapTest {
    private static final float SLOP = 10;
    private static final float DOUBLE_TAP_SLOP = 100;
    private static final long TIMEOUT = 300;
    private static final long LONG_PRESS = 400;

    private final TallyDoubleTap mDoubleTap =
            new TallyDoubleTap(SLOP, DOUBLE_TAP_SLOP, TIMEOUT, LONG_PRESS);

    private boolean tap(long down, long up, float x, float y, boolean onEmptySpace) {
        mDoubleTap.onDown(down, x, y, onEmptySpace);
        return mDoubleTap.onUp(up);
    }

    @Test
    public void twoQuickTaps_onEmptySpace() {
        assertFalse(tap(0, 50, 200, 200, true));
        assertTrue(tap(200, 250, 220, 210, true));
        // A third tap starts over.
        assertFalse(tap(300, 350, 220, 210, true));
    }

    @Test
    public void cancelledTouchBetweenTaps() {
        assertFalse(tap(0, 50, 200, 200, true));
        mDoubleTap.onDown(100, 200, 200, true);
        mDoubleTap.cancel();
        assertFalse(tap(200, 250, 200, 200, true));
    }

    @Test
    public void eitherTapOffEmptySpace() {
        assertFalse(tap(0, 50, 200, 200, false));
        assertFalse(tap(200, 250, 200, 200, true));
        assertFalse(tap(1000, 1050, 200, 200, true));
        assertFalse(tap(1200, 1250, 200, 200, false));
    }

    @Test
    public void tapsTooSlowOrApart() {
        assertFalse(tap(0, 50, 200, 200, true));
        assertFalse(tap(50 + TIMEOUT + 1, 400, 200, 200, true));
        assertFalse(tap(1000, 1050, 200, 200, true));
        assertFalse(tap(1100, 1150, 200, 200 + DOUBLE_TAP_SLOP + 1, true));
    }

    @Test
    public void longPressIsNoTap() {
        assertFalse(tap(0, LONG_PRESS, 200, 200, true));
        assertFalse(tap(LONG_PRESS + 100, LONG_PRESS + 150, 200, 200, true));
        assertFalse(tap(1000, 1050, 200, 200, true));
        assertFalse(tap(1100, 1100 + LONG_PRESS, 200, 200, true));
    }

    @Test
    public void moveOrCancelIsNoTap() {
        mDoubleTap.onDown(0, 200, 200, true);
        mDoubleTap.onMove(200, 200 + SLOP + 1);
        assertFalse(mDoubleTap.onUp(50));
        assertFalse(tap(100, 150, 200, 200, true));
        mDoubleTap.onDown(200, 200, 200, true);
        mDoubleTap.cancel();
        assertFalse(mDoubleTap.onUp(250));
        assertFalse(tap(300, 350, 200, 200, true));
        mDoubleTap.onDown(400, 200, 200, true);
        mDoubleTap.onMove(200 + SLOP, 200);
        assertTrue(mDoubleTap.onUp(450));
    }
}
