/* Copyright (C) 2026 DiamaneOS */
package com.android.launcher3.icons;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class CleanCopiesTest {
    private Context context;
    private SharedPreferences prefs;
    private Method remember;

    @Before public void setup() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = base.getSharedPreferences("clean-copy-retry-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        final android.content.pm.LauncherApps apps = org.mockito.Mockito.mock(android.content.pm.LauncherApps.class);
        org.mockito.Mockito.when(apps.getProfiles()).thenReturn(java.util.List.of(
                android.os.UserHandle.getUserHandleForUid(10001)));
        final android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
        info.uid = 10001;
        org.mockito.Mockito.when(apps.isPackageEnabled(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(apps.getApplicationInfo(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(info);
        context = new ContextWrapper(base) {
            @Override public Object getSystemService(String name) {
                return Context.LAUNCHER_APPS_SERVICE.equals(name) ? apps : super.getSystemService(name);
            }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
        remember = CleanCopies.class.getDeclaredMethod("remember", Context.class, String.class, boolean.class);
        remember.setAccessible(true);
    }

    @After public void cleanup() { prefs.edit().clear().commit(); }

    @Test public void anotherComponentSuccessCannotClearPendingIcon() throws Exception {
        remember.invoke(null, context, "0:test.pkg/First/1", true);
        remember.invoke(null, context, "0:test.pkg/Second/2", false);
        Set<String> packages = new HashSet<>();
        CleanCopies.forEachMissed(context, (pkg, user) -> packages.add(pkg));
        assertTrue(packages.contains("test.pkg"));
        remember.invoke(null, context, "0:test.pkg/First/1", false);
        packages.clear();
        CleanCopies.forEachMissed(context, (pkg, user) -> packages.add(pkg));
        assertTrue(packages.contains("test.pkg"));
        CleanCopies.Refresh refresh = CleanCopies.beginRefresh(context, "test.pkg",
                android.os.UserHandle.getUserHandleForUid(10001));
        remember.invoke(null, context, "0:test.pkg/First/1", true);
        CleanCopies.endRefresh(context, refresh);
        assertTrue(prefs.getStringSet("missed", Set.of()).contains("0:test.pkg"));
        CleanCopies.endRefresh(context, CleanCopies.beginRefresh(context, "test.pkg",
                android.os.UserHandle.getUserHandleForUid(10001)));
        packages.clear();
        CleanCopies.forEachMissed(context, (pkg, user) -> packages.add(pkg));
        assertFalse(packages.contains("test.pkg"));
    }

    @Test public void overflowIsDurableAndStorageStaysBounded() throws Exception {
        for (int i = 0; i < 257; i++) remember.invoke(null, context, "0:test.pkg" + i + "/Main/1", true);
        assertEquals(256, prefs.getStringSet("missed", Set.of()).size());
        assertTrue(prefs.getLong("rescan_generation", 0) > prefs.getLong("rescan_acknowledged", 0));
        remember.invoke(null, context, "0:test.pkg0/Main/1", false);
        assertTrue(prefs.getLong("rescan_generation", 0) > prefs.getLong("rescan_acknowledged", 0));
    }

    @Test public void archivedRefreshDoesNotCreateOrClearAnIndependentMiss() throws Exception {
        final android.os.UserHandle user = android.os.UserHandle.getUserHandleForUid(10001);
        CleanCopies.archivedRefresh(context, CleanCopies.beginRefresh(context, "archive.pkg", user));
        assertFalse(prefs.getStringSet("missed", Set.of()).contains("0:archive.pkg"));
        remember.invoke(null, context, "0:archive.pkg/Main/1", true);
        CleanCopies.archivedRefresh(context, CleanCopies.beginRefresh(context, "archive.pkg", user));
        assertTrue(prefs.getStringSet("missed", Set.of()).contains("0:archive.pkg"));
    }
    @Test public void repeatedUnrepresentedMissKeepsTheCurrentGeneration() throws Exception {
        for (int index = 0; index < 257; index++) remember.invoke(null, context, "0:miss" + index + "/Main/1", true);
        final long generation = prefs.getLong("rescan_generation", 0);
        for (int index = 0; index < 1000; index++) remember.invoke(null, context, "0:miss256/Other/2", true);
        assertEquals(generation, prefs.getLong("rescan_generation", 0));
        assertEquals(256, prefs.getStringSet("missed", Set.of()).size());
    }
    @Test public void overflowAcknowledgesCompletedProfilesAndRetriesOnlyLockedProfile() throws Exception {
        final android.content.pm.LauncherApps apps = org.mockito.Mockito.mock(
                android.content.pm.LauncherApps.class);
        final android.os.UserManager users = org.mockito.Mockito.mock(android.os.UserManager.class);
        final android.os.UserHandle personal = android.os.UserHandle.getUserHandleForUid(10001);
        final android.os.UserHandle work = android.os.UserHandle.getUserHandleForUid(1010001);
        final android.content.pm.LauncherActivityInfo activity = org.mockito.Mockito.mock(
                android.content.pm.LauncherActivityInfo.class);
        org.mockito.Mockito.when(activity.getComponentName()).thenReturn(
                new android.content.ComponentName("overflow.pkg", "Main"));
        org.mockito.Mockito.when(apps.isPackageEnabled(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(apps.getProfiles()).thenReturn(java.util.List.of(personal, work));
        org.mockito.Mockito.when(apps.getActivityList(null, personal)).thenReturn(java.util.List.of(activity));
        org.mockito.Mockito.when(apps.getActivityList(null, work)).thenReturn(java.util.List.of(activity));
        org.mockito.Mockito.when(users.getSerialNumberForUser(personal)).thenReturn(0L);
        org.mockito.Mockito.when(users.getSerialNumberForUser(work)).thenReturn(10L);
        org.mockito.Mockito.when(users.isUserUnlocked(personal)).thenReturn(true);
        org.mockito.Mockito.when(users.isUserUnlocked(work)).thenReturn(false);
        final Context scoped = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) {
                if (Context.LAUNCHER_APPS_SERVICE.equals(name)) return apps;
                if (Context.USER_SERVICE.equals(name)) return users;
                return super.getSystemService(name);
            }
        };
        prefs.edit().putBoolean("rescan_needed", true).commit();
        final java.util.function.BiConsumer<String, android.os.UserHandle> refresh = (pkg, user) -> {
            final CleanCopies.Refresh token = CleanCopies.beginRefresh(scoped, pkg, user);
            CleanCopies.endRefresh(scoped, token);
        };
        CleanCopies.forEachMissed(scoped, refresh);
        CleanCopies.forEachMissed(scoped, refresh);
        org.mockito.Mockito.verify(apps).getActivityList(null, personal);
        org.mockito.Mockito.verify(apps, org.mockito.Mockito.never()).getActivityList(null, work);
        assertNotEquals(prefs.getLong("rescan_generation", 0), prefs.getLong("rescan_acknowledged", 0));
        org.mockito.Mockito.when(users.isUserUnlocked(work)).thenReturn(true);
        CleanCopies.forEachMissed(scoped, refresh);
        CleanCopies.forEachMissed(scoped, refresh);
        CleanCopies.forEachMissed(scoped, refresh);
        org.mockito.Mockito.verify(apps).getActivityList(null, personal);
        org.mockito.Mockito.verify(apps).getActivityList(null, work);
        assertEquals(prefs.getLong("rescan_generation", 0), prefs.getLong("rescan_acknowledged", 0));
    }

}
