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

package com.android.quickstep.tally.motion;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Surface.ROTATION_0;

import android.content.Context;
import android.content.res.Resources;
import android.view.RemoteAnimationTarget;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.tally.home.TallyHomeLayout;
import com.android.quickstep.GestureState;
import com.android.quickstep.RecentsAnimationDeviceState;
import com.android.quickstep.RecentsAnimationTargets;
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle;
import com.android.quickstep.util.RecentsOrientedState;
import com.android.systemui.shared.system.QuickStepContract;

/** Starts AbsSwipeUpHandler's TallySwipeWindow where Tally's swipe up applies. */
public final class TallySwipeStart {

    private TallySwipeStart() {}

    /**
     * Lets the window follow the finger as Tally's does (TallySwipeWindow), on an upright phone in
     * gesture navigation, for one full-screen app with no rotation on the way and no
     * picture-in-picture to enter. Returns null where everything keeps stock's motion.
     * {@code onFrame} reapplies the window's transform.
     */
    @Nullable
    public static TallySwipeWindow start(Context context, RecentsAnimationTargets targets,
            boolean forDesktop, @Nullable DeviceProfile dp,
            RemoteTargetHandle[] remoteTargetHandles, boolean isSwipeForSplit,
            boolean continuingLastGesture, RecentsAnimationDeviceState deviceState,
            GestureState gestureState, Runnable onFrame) {
        if (forDesktop || dp == null || remoteTargetHandles.length != 1
                || isSwipeForSplit || continuingLastGesture
                || !deviceState.isFullyGesturalNavMode() || gestureState.isTrackpadGesture()
                || !TallyHomeLayout.appliesTo(dp)) {
            return null;
        }
        RecentsOrientedState orientation =
                remoteTargetHandles[0].getTaskViewSimulator().getOrientationState();
        if (orientation.getDisplayRotation() != ROTATION_0
                || orientation.getTouchRotation() != ROTATION_0
                || orientation.getRecentsActivityRotation() != ROTATION_0) {
            return null;
        }
        RemoteAnimationTarget running = targets.findTask(gestureState.getTopRunningTaskId());
        if (running == null || running.taskInfo == null || running.rotationChange != 0
                || running.windowConfiguration.getActivityType() != ACTIVITY_TYPE_STANDARD
                || running.windowConfiguration.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                || (running.allowEnterPip && running.taskInfo.pictureInPictureParams != null
                        && running.taskInfo.pictureInPictureParams.isAutoEnterEnabled())) {
            return null;
        }
        Resources res = context.getResources();
        return new TallySwipeWindow(TallySpring.stone(res), TallySpring.slab(res),
                dp.getDeviceProperties().getWidthPx(), dp.getDeviceProperties().getHeightPx(),
                res.getDisplayMetrics().density,
                QuickStepContract.supportsRoundedCornersOnWindows(res)
                        ? QuickStepContract.getWindowCornerRadius(context) : 0,
                res.getFloat(R.dimen.tally_rubber_band_coefficient),
                onFrame);
    }
}
