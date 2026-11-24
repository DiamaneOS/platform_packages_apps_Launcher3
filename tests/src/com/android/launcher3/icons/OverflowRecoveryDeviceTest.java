/* Copyright (C) 2026 DiamaneOS */
package com.android.launcher3.icons;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.SafeCloseable;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Real profile enumeration and cache refresh, with exact preference restoration. */
public class OverflowRecoveryDeviceTest {
    @Test public void overflowThenTwoRefillNoticesRunsOneRealRescan() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences prefs = context.getSharedPreferences("diamaneos_clean_copies", Context.MODE_PRIVATE);
        final Map<String, ?> saved = prefs.getAll();
        final LauncherApps real = context.getSystemService(LauncherApps.class);
        final Map<UserHandle, Integer> enumerations = new HashMap<>();
        final LauncherApps counted = new LauncherApps(context) {
            @Override public List<UserHandle> getProfiles() { return real.getProfiles(); }
            @Override public List<LauncherActivityInfo> getActivityList(String pkg, UserHandle user) {
                if (pkg == null) enumerations.merge(user, 1, Integer::sum);
                return real.getActivityList(pkg, user);
            }
        };
        final Context scoped = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) {
                return Context.LAUNCHER_APPS_SERVICE.equals(name) ? counted : super.getSystemService(name);
            }
        };
        final AtomicReference<IconCache> cache = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                cache.set(LauncherAppState.getInstance(context).getIconCache()));
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Executors.MODEL_EXECUTOR.execute(() -> {
            Throwable error = null;
            SafeCloseable watch = null;
            try {
                for (UserHandle user : real.getProfiles()) {
                    final Set<String> packages = new HashSet<>();
                    for (LauncherActivityInfo app : real.getActivityList(null, user)) {
                        packages.add(app.getComponentName().getPackageName());
                    }
                    for (String pkg : packages) cache.get().updateIconsForPkg(pkg, user);
                }
                assertTrue(prefs.edit().clear().commit());
                final LinkedBlockingQueue<Runnable> refills = new LinkedBlockingQueue<>();
                watch = CleanCopyReloads.watch(scoped, work -> refills.add(work),
                        (pkg, user) -> {
                            try { cache.get().updateIconsForPkg(pkg, user); }
                            catch (RuntimeException failure) {
                                throw new AssertionError("Real cache refresh failed for " + pkg,
                                        failure);
                            }
                        });
                final Method remember = CleanCopies.class.getDeclaredMethod("remember",
                        Context.class, String.class, boolean.class);
                remember.setAccessible(true);
                for (int i = 0; i < 257; i++) {
                    remember.invoke(null, context, "0:sol2cr.missed" + i + "/Main/1", true);
                }
                final long generation = prefs.getLong("rescan_generation", 0);
                assertTrue(generation > 0);
                final Class<?> gate = Class.forName(
                        "com.android.internal.diamaneos.cleancopies.CleanCopyGate");
                final Method notify = gate.getMethod("decodeAnswersForTest");
                final java.lang.reflect.Field field = gate.getDeclaredField("sMadeHandler");
                field.setAccessible(true);
                final android.os.Handler delivery = (android.os.Handler) field.get(null);
                assertNotNull("The framework refill listener was not registered", delivery);
                for (int notice = 0; notice < 3; notice++) {
                    notify.invoke(null);
                    final CountDownLatch delivered = new CountDownLatch(1);
                    assertTrue(delivery.post(delivered::countDown));
                    assertTrue("The framework notice was not delivered",
                            delivered.await(10, TimeUnit.SECONDS));
                    final Runnable refill = refills.poll(5, TimeUnit.SECONDS);
                    assertNotNull("The launcher listener did not enqueue a refill", refill);
                    refill.run();
                    assertEquals("Notice " + notice + ", generation "
                            + prefs.getLong("rescan_generation", 0) + ", enumerations "
                            + enumerations + ", pending "
                            + prefs.getStringSet("missed", Set.of()).size() + ", state "
                            + prefs.getAll().keySet(),
                            generation, prefs.getLong("rescan_acknowledged", 0));
                    for (UserHandle user : real.getProfiles()) {
                        assertEquals(Integer.valueOf(1), enumerations.get(user));
                    }
                    assertTrue("A completed notice left duplicate refill work", refills.isEmpty());
                }
            } catch (Throwable failure) { error = failure; }
            finally {
                try { if (watch != null) watch.close(); restore(prefs, saved); }
                catch (Throwable failure) { if (error == null) error = failure; }
            }
            if (error == null) done.complete(null);
            else done.completeExceptionally(error);
        });
        done.get(180, TimeUnit.SECONDS);
    }

    @Test public void removedAndMalformedIconsRecoverThroughProductionModel() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences prefs = context.getSharedPreferences("diamaneos_clean_copies", Context.MODE_PRIVATE);
        final Map<String, ?> saved = prefs.getAll();
        final UserHandle user = android.os.Process.myUserHandle();
        final LauncherApps real = context.getSystemService(LauncherApps.class);
        final String removedPackage = "com.android.launcher3.overflowfixture";
        assertTrue("The separate removable fixture must be installed", real.isPackageEnabled(removedPackage, user));
        final android.content.ComponentName broken = new android.content.ComponentName(
                "com.android.launcher3.tests", "com.android.launcher3.tests.WrongIconActivity");
        assertTrue("The malformed activity must stay enabled", real.isActivityEnabled(broken, user));
        final LauncherActivityInfo witness = real.getActivityList("com.android.settings", user).get(0);
        final java.util.concurrent.atomic.AtomicInteger scans = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger dispatches = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        final LauncherApps counted = new LauncherApps(context) {
            @Override public List<UserHandle> getProfiles() { return real.getProfiles(); }
            @Override public boolean isPackageEnabled(String pkg, UserHandle target) {
                return real.isPackageEnabled(pkg, target);
            }
            @Override public android.content.pm.ApplicationInfo getApplicationInfo(String pkg, int flags,
                    UserHandle target) throws android.content.pm.PackageManager.NameNotFoundException {
                return real.getApplicationInfo(pkg, flags, target);
            }
            @Override public List<LauncherActivityInfo> getActivityList(String pkg, UserHandle target) {
                final List<LauncherActivityInfo> result = new java.util.ArrayList<>(real.getActivityList(pkg, target));
                if (pkg == null) {
                    scans.incrementAndGet();
                    if (target.equals(user)) for (int index = 0; index < 200; index++) {
                        result.add(absentActivity(context, "a.sol2crr.absent" + index, target));
                    }
                }
                return result;
            }
        };
        final Context scoped = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) {
                return Context.LAUNCHER_APPS_SERVICE.equals(name) ? counted : super.getSystemService(name);
            }
        };
        final AtomicReference<LauncherAppState> state = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> state.set(LauncherAppState.getInstance(context)));
        final IconCache cache = state.get().getIconCache();
        state.get().getModel().startLoader("overflow-production-proof").toCompletableFuture().get(60, TimeUnit.SECONDS);
        final SafeCloseable[] watch = new SafeCloseable[1];
        try {
            Executors.MODEL_EXECUTOR.submit(() -> {
                assertTrue(state.get().getModel().isModelLoaded());
                assertTrue(prefs.edit().clear().commit());
                cache.removeIconsForPkg(witness.getComponentName().getPackageName(), user);
                assertNull(cache.getInMemoryEntryLocked(new com.android.launcher3.util.ComponentKey(witness.getComponentName(), user)));
                watch[0] = CleanCopyReloads.watch(scoped, Executors.MODEL_EXECUTOR, (pkg, target) -> {
                    dispatches.incrementAndGet();
                    if (pkg.equals(removedPackage) && removed.compareAndSet(false, true)) {
                        try (android.os.ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                                .getUiAutomation().executeShellCommand("pm uninstall --user 0 " + removedPackage);
                                java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
                            final String result = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            assertTrue(result, result.contains("Success"));
                        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                    }
                    // Enter the same model task used by the production icon-change observer.
                    state.get().getModel().enqueueModelUpdateTask(
                            new com.android.launcher3.model.tasks.PackageUpdatedTask(
                                    com.android.launcher3.model.tasks.PackageUpdatedTask.OP_UPDATE,
                                    target, pkg));
                });
                // Seed the durable overflow record that recovery replays after process death.
                assertTrue(prefs.edit().putLong("rescan_generation", android.os.SystemClock.elapsedRealtimeNanos())
                        .putLong("rescan_acknowledged", 0).putStringSet("rescan_profiles_done", Set.of())
                        .putStringSet("rescan_profiles_after", Set.of()).putBoolean("rescan_again", false)
                        .putLong("rescan_due", 0).putBoolean("rescan_needed", false).commit());
            }).get(30, TimeUnit.SECONDS);
            final long generation = prefs.getLong("rescan_generation", 0);
            sendFrameworkNotice();
            final long end = android.os.SystemClock.uptimeMillis() + 180_000;
            while (prefs.getLong("rescan_acknowledged", 0) != generation
                    && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(100);
            assertEquals("The production model stranded a removed or malformed package",
                    generation, prefs.getLong("rescan_acknowledged", 0));
            assertTrue(removed.get());
            assertFalse(real.isPackageEnabled(removedPackage, user));
            assertTrue(real.isActivityEnabled(broken, user));
            Executors.MODEL_EXECUTOR.submit(() -> {
                assertNotNull("The healthy target beyond the first batch was not refreshed",
                        cache.getInMemoryEntryLocked(new com.android.launcher3.util.ComponentKey(witness.getComponentName(), user)));
                assertTrue(prefs.getStringSet("missed", Set.of()).contains("0:com.android.launcher3.tests"));
                final com.android.launcher3.model.data.AppInfo bad = new com.android.launcher3.model.data.AppInfo();
                bad.componentName = broken;
                bad.user = user;
                bad.intent = new android.content.Intent(android.content.Intent.ACTION_MAIN).setComponent(broken);
                cache.getTitleAndIcon(bad, bad.getMatchingLookupFlag());
                assertTrue("The malformed icon displayed non-default pixels", cache.isDefaultIcon(bad.bitmap, user));
            }).get(30, TimeUnit.SECONDS);
            final int fullScans = scans.get();
            for (int notice = 0; notice < 2; notice++) {
                final int before = dispatches.get();
                sendFrameworkNotice();
                for (int turn = 0; turn < 6; turn++) Executors.MODEL_EXECUTOR.submit(() -> { }).get(30, TimeUnit.SECONDS);
                assertTrue("The later notice was not delivered", dispatches.get() > before);
                assertEquals("A completed healthy traversal was enumerated again", fullScans, scans.get());
            }
        } finally {
            Executors.MODEL_EXECUTOR.submit(() -> {
                if (watch[0] != null) watch[0].close();
                restore(prefs, saved);
            }).get(30, TimeUnit.SECONDS);
        }
    }

    private static LauncherActivityInfo absentActivity(Context context, String pkg, UserHandle user) {
        try {
            final android.content.pm.ActivityInfo activity = new android.content.pm.ActivityInfo();
            activity.packageName = pkg;
            activity.name = "Main";
            activity.applicationInfo = new android.content.pm.ApplicationInfo();
            activity.applicationInfo.packageName = pkg;
            final Class<?> internal = Class.forName("android.content.pm.LauncherActivityInfoInternal");
            final Object data = internal.getConstructor(android.content.pm.ActivityInfo.class,
                    Class.forName("android.content.pm.IncrementalStatesInfo"), UserHandle.class, boolean.class)
                    .newInstance(activity, null, user, false);
            final java.lang.reflect.Constructor<LauncherActivityInfo> constructor = LauncherActivityInfo.class
                    .getDeclaredConstructor(Context.class, internal);
            constructor.setAccessible(true);
            return constructor.newInstance(context, data);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void sendFrameworkNotice() throws Exception {
        final Class<?> gate = Class.forName("com.android.internal.diamaneos.cleancopies.CleanCopyGate");
        gate.getMethod("decodeAnswersForTest").invoke(null);
        final java.lang.reflect.Field field = gate.getDeclaredField("sMadeHandler");
        field.setAccessible(true);
        final android.os.Handler delivery = (android.os.Handler) field.get(null);
        final CountDownLatch delivered = new CountDownLatch(1);
        assertNotNull(delivery);
        assertTrue(delivery.post(delivered::countDown));
        assertTrue(delivered.await(10, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences prefs, Map<String, ?> saved) {
        final SharedPreferences.Editor edit = prefs.edit().clear();
        saved.forEach((key, value) -> {
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>) value));
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else throw new AssertionError("Unsupported saved preference type");
        });
        assertTrue(edit.commit());
    }
}
