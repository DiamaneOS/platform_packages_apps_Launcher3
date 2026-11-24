/* Copyright (C) 2026 DiamaneOS */
package com.android.launcher3.icons;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.os.UserManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class OverflowIsolationTest {
    @Test public void removedAndRefusedTargetsDoNotHoldLaterPackages() throws Exception {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences prefs = base.getSharedPreferences("overflow-independent-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        final LauncherApps apps = mock(LauncherApps.class);
        final UserManager users = mock(UserManager.class);
        final UserHandle user = UserHandle.of(0);
        when(users.getSerialNumberForUser(user)).thenReturn(0L);
        when(users.isUserUnlocked(user)).thenReturn(true);
        when(apps.getProfiles()).thenReturn(List.of(user));
        when(apps.isPackageEnabled(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(true);
        final List<LauncherActivityInfo> activities = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            final LauncherActivityInfo activity = mock(LauncherActivityInfo.class);
            when(activity.getComponentName()).thenReturn(new ComponentName(String.format("p%04d", index), "Main"));
            activities.add(activity);
        }
        when(apps.getActivityList(null, user)).thenReturn(activities);
        final Context context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
            @Override public Object getSystemService(String name) {
                if (Context.LAUNCHER_APPS_SERVICE.equals(name)) return apps;
                if (Context.USER_SERVICE.equals(name)) return users;
                return super.getSystemService(name);
            }
        };
        final Method remember = CleanCopies.class.getDeclaredMethod("remember", Context.class, String.class, boolean.class);
        remember.setAccessible(true);
        final Set<String> refreshed = new HashSet<>();
        try {
            IconOverflow.overflow(context);
            for (int notice = 0; notice < 8; notice++) {
                CleanCopies.forEachMissed(context, (pkg, target) -> {
                    if (pkg.equals("p0000")) {
                        when(apps.isPackageEnabled(pkg, target)).thenReturn(false);
                        CleanCopies.unavailable(context, pkg, target);
                        return;
                    }
                    final CleanCopies.Refresh refresh = CleanCopies.beginRefresh(context, pkg, target);
                    if (pkg.equals("p0001")) {
                        try { remember.invoke(null, context, "0:p0001/Main/1", true); }
                        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    } else refreshed.add(pkg);
                    CleanCopies.endRefresh(context, refresh);
                });
            }
            assertTrue("A removed or malformed target stranded the next batch", refreshed.contains("p0299"));
            assertEquals(prefs.getLong("rescan_generation", 0), prefs.getLong("rescan_acknowledged", -1));
            assertTrue(prefs.getStringSet("missed", Set.of()).contains("0:p0001"));
            verify(apps, times(1)).getActivityList(null, user);
        } finally { prefs.edit().clear().commit(); IconOverflow.failed(context); }
    }
}
