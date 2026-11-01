/*
 * Copyright (C) 2026 The DiamaneOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LauncherAnimationRunnerTest {
    private static RemoteAnimationTarget target() {
        return new RemoteAnimationTarget(1, RemoteAnimationTarget.MODE_CLOSING,
                mock(SurfaceControl.class), false, null, null, 0, null, null, null,
                new Configuration().windowConfiguration, false, mock(SurfaceControl.class), null,
                new ActivityManager.RunningTaskInfo(), false);
    }

    private static void start(LauncherAnimationRunner runner, RemoteAnimationTarget[] targets,
            Runnable finished) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                runner.onAnimationStart(0, new RemoteAnimationTarget[]{targets[0]},
                        new RemoteAnimationTarget[]{targets[1]},
                        new RemoteAnimationTarget[]{targets[2]}, finished));
    }

    @Test
    public void missingFactoryReleasesAllTargetsAfterFinishingTransition() throws Exception {
        RemoteAnimationTarget[] targets = {target(), target(), target()};
        AtomicInteger released = new AtomicInteger();
        for (RemoteAnimationTarget target : targets) {
            doAnswer(call -> { released.incrementAndGet(); return null; })
                    .when(target.leash).release();
            doAnswer(call -> { released.incrementAndGet(); return null; })
                    .when(target.startLeash).release();
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean finishedBeforeRelease = new AtomicBoolean();
        LauncherAnimationRunner runner = new LauncherAnimationRunner(
                new Handler(Looper.getMainLooper()), null, false);
        start(runner, targets, () -> {
            finishedBeforeRelease.set(released.get() == 0);
            finished.countDown();
        });
        assertTrue(finished.await(3, TimeUnit.SECONDS));
        assertTrue(finishedBeforeRelease.get());
        for (RemoteAnimationTarget target : targets) {
            verify(target.leash, timeout(3000)).release();
            verify(target.startLeash, timeout(3000)).release();
        }
    }

    @Test
    public void deliveredTargetsStayOwnedByFactory() throws Exception {
        RemoteAnimationTarget[] targets = {target(), target(), target()};
        LauncherAnimationRunner.RemoteAnimationFactory factory =
                (transit, apps, wallpapers, nonApps, result) -> result.setAnimation(null, null);
        CountDownLatch finished = new CountDownLatch(1);
        LauncherAnimationRunner runner = new LauncherAnimationRunner(
                new Handler(Looper.getMainLooper()), factory, false);
        try {
            start(runner, targets, finished::countDown);
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            for (RemoteAnimationTarget target : targets) {
                verify(target.leash, never()).release();
                verify(target.startLeash, never()).release();
            }
        } finally {
            for (RemoteAnimationTarget target : targets) {
                target.leash.release();
                target.startLeash.release();
            }
        }
    }
}
