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
package com.android.quickstep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.shared.system.RecentsAnimationControllerCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecentsAnimationCallbacksTest {
    private RecentsAnimationCallbacks mCallbacks;
    private RecentsAnimationCallbacks.RecentsAnimationListener mListener;
    private RecentsAnimationControllerCompat mController;
    private RemoteAnimationTarget mApp;
    private RemoteAnimationTarget mWallpaper;
    private TransitionInfo mTransitionInfo;

    @Before
    public void setUp() {
        mCallbacks = new RecentsAnimationCallbacks(null);
        mListener = mock(RecentsAnimationCallbacks.RecentsAnimationListener.class);
        mController = mock(RecentsAnimationControllerCompat.class);
        mTransitionInfo = mock(TransitionInfo.class);
        mApp = target(RemoteAnimationTarget.MODE_CLOSING);
        mWallpaper = target(RemoteAnimationTarget.MODE_OPENING);
        onMain(() -> mCallbacks.addListener(mListener));
    }

    private static RemoteAnimationTarget target(int mode) {
        return new RemoteAnimationTarget(1, mode, mock(SurfaceControl.class), false,
                null, null, 0, null, null, null, new Configuration().windowConfiguration,
                false, mock(SurfaceControl.class), null,
                new ActivityManager.RunningTaskInfo(), false);
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void start() {
        mCallbacks.onAnimationStart(mController, new RemoteAnimationTarget[]{mApp},
                new RemoteAnimationTarget[]{mWallpaper}, new Rect(), new Bundle(), mTransitionInfo);
    }

    private void verifyReleased() {
        verify(mApp.leash).release();
        verify(mApp.startLeash).release();
        verify(mWallpaper.leash).release();
        verify(mWallpaper.startLeash).release();
        verify(mTransitionInfo).releaseAllSurfaces();
    }

    @Test
    public void noClosingAppsReleasesUndeliveredTargets() {
        mApp = target(RemoteAnimationTarget.MODE_OPENING);
        onMain(this::start);
        verifyReleased();
        verify(mListener, never()).onRecentsAnimationStart(any(), any(), any());
    }

    @Test
    public void canceledBeforeStartReleasesUndeliveredTargets() {
        onMain(() -> {
            mCallbacks.notifyAnimationCanceled();
            start();
        });
        verifyReleased();
        verify(mListener, never()).onRecentsAnimationStart(any(), any(), any());
    }

    @Test
    public void cancellationBeforeQueuedDeliveryReleasesTargets() {
        onMain(() -> {
            start();
            mCallbacks.onAnimationCanceled(new HashMap<>());
        });
        verifyReleased();
        verify(mListener, never()).onRecentsAnimationStart(any(), any(), any());
    }

    @Test
    public void removedListenersReleaseUndeliveredTargets() {
        onMain(() -> {
            start();
            mCallbacks.removeAllListeners();
        });
        verifyReleased();
    }

    @Test
    public void removedListenersReleaseAppearedTasks() {
        onMain(() -> {
            mCallbacks.onTasksAppeared(new RemoteAnimationTarget[]{mApp, mWallpaper},
                    mTransitionInfo);
            mCallbacks.removeAllListeners();
        });
        verifyReleased();
    }

    @Test
    public void deliveredTargetsStayOwnedByRecipient() {
        onMain(this::start);
        ArgumentCaptor<RecentsAnimationTargets> targets =
                ArgumentCaptor.forClass(RecentsAnimationTargets.class);
        verify(mListener).onRecentsAnimationStart(any(), targets.capture(), any());
        verify(mApp.leash, never()).release();
        verify(mWallpaper.leash, never()).release();
        verify(mTransitionInfo, never()).releaseAllSurfaces();
        onMain(() -> {
            targets.getValue().release();
            mTransitionInfo.releaseAllSurfaces();
        });
        verifyReleased();
    }
}
