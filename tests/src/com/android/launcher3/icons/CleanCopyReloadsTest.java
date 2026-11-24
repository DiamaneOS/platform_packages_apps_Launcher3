/* Copyright (C) 2026 DiamaneOS */
package com.android.launcher3.icons;

import static org.junit.Assert.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.launcher3.util.SafeCloseable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CleanCopyReloadsTest {
    @Test public void genericProfileAccessibleEventQueuesRecovery() throws Exception {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final IntentFilter[] filter = new IntentFilter[1];
        final BroadcastReceiver[] receiver = new BroadcastReceiver[1];
        final Context context = new ContextWrapper(base) {
            @Override public Intent registerReceiver(BroadcastReceiver next, IntentFilter actions, int flags) {
                receiver[0] = next; filter[0] = actions; return null;
            }
            @Override public void unregisterReceiver(BroadcastReceiver ignored) { }
        };
        final AtomicInteger queued = new AtomicInteger();
        final java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        try (SafeCloseable watch = CleanCopyReloads.watch(context, work -> { queued.incrementAndGet(); delivered.countDown(); }, (pkg,user) -> { })) {
            assertTrue(filter[0].hasAction(Intent.ACTION_PROFILE_ACCESSIBLE));
            receiver[0].onReceive(context, new Intent(Intent.ACTION_PROFILE_ACCESSIBLE));
            assertTrue(delivered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, queued.get());
        }
    }
}
