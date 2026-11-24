/*
 * Copyright (C) 2026 DiamaneOS
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

package com.android.launcher3.icons;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * DiamaneOS: app icons from clean copies made in the protected VM (the clean icon cache). The framework gates the
 * launcher's process: every other app's image file comes from its copy, whatever loads it, widgets and their previews
 * included. An icon load ({@link #loadIcon}, widget previews too) shows Android's default icon (or no preview) while a
 * copy is missing, after waiting up to 1.5 s on a background thread, and loads again when the system tells it the
 * copies were made ({@link #ACTION_MADE}). An app whose icon missed is remembered across launcher restarts and loaded
 * again at the next start ({@link #forEachMissed}), so a default icon never stays in the launcher's own cache. The
 * framework's gate is reached by reflection, so this code also builds against the public SDK.
 */
public final class CleanCopies {
    private static final String TAG = "CleanCopies";

    /** Sent by the system when copies of an app's images were made. */
    public static final String ACTION_MADE = "de.diamaneos.action.CLEAN_COPIES_MADE";
    private static final String EXTRA_PACKAGE = "package";

    private static final Method ICON_LOAD;
    private static final Method USER_ICON_LOAD;
    private static final Method MISSES;
    private static final Method PROVISIONAL;
    // The apps whose icon missed a copy, as "user:package", kept across restarts.
    private static final String PREFS = "diamaneos_clean_copies";
    private static final String MISSED = "missed";
    private static final int MAX_MISSED = 256;
    private static final java.util.Map<String, Refresh> REFRESHES = new java.util.HashMap<>();

    private static String packageKey(String entry) {
        final int slash = entry.indexOf('/');
        return slash < 0 ? entry : entry.substring(0, slash);
    }
    private static String packageKey(String pkg, UserHandle user) {
        return user.getIdentifier() + ":" + pkg;
    }

    static {
        Method iconLoad = null;
        Method userIconLoad = null;
        Method misses = null;
        Method provisional = null;
        try {
            final Class<?> gate =
                    Class.forName("com.android.internal.diamaneos.cleancopies.CleanCopyGate");
            iconLoad = gate.getMethod("iconLoad", Supplier.class);
            misses = gate.getMethod("misses");
            provisional = gate.getMethod("provisional");
            userIconLoad = gate.getMethod("iconLoad", int.class, Supplier.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "no clean copy gate: app icons show the default icon: " + e);
        }
        ICON_LOAD = iconLoad;
        USER_ICON_LOAD = userIconLoad;
        MISSES = misses;
        PROVISIONAL = provisional;
    }

    private CleanCopies() {
    }

    /**
     * {@code load} as an icon load: its app image files come from clean copies. Null when one of them has none yet,
     * so the caller shows the default icon, never one with holes. Fail closed: without the framework's gate (it cannot
     * be reached) no app icon file is decoded here, every app shows the default icon.
     */
    public static Drawable loadIcon(Supplier<Drawable> load) {
        return loadIcon(-1, load);
    }

    private static Drawable loadIcon(int user, Supplier<Drawable> load) {
        final Method request = user < 0 ? ICON_LOAD : USER_ICON_LOAD;
        if (request == null || MISSES == null || PROVISIONAL == null) {
            return null;
        }
        try {
            final int before = (int) MISSES.invoke(null);
            final int provisional = (int) PROVISIONAL.invoke(null);
            final Drawable icon = (Drawable) (user < 0 ? request.invoke(null, load)
                    : request.invoke(null, user, load));
            return (int) MISSES.invoke(null) != before || (int) PROVISIONAL.invoke(null) != provisional
                    ? null : icon;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * {@link #loadIcon} for an icon of the app {@code appInfo}: a miss is remembered across launcher restarts until
     * a full package cache refresh completes without another missing copy.
     */
    public static Drawable loadAppIcon(Context context, ApplicationInfo appInfo, Supplier<Drawable> load) {
        return loadAppIcon(context, appInfo, appInfo, load);
    }

    /** The retry key names the component, its resource and the user that supplied it. */
    public static Drawable loadAppIcon(Context context, PackageItemInfo item, ApplicationInfo appInfo,
            Supplier<Drawable> load) {
        if (appInfo.uid < 0) return null;
        Drawable icon;
        try {
            icon = loadIcon(appInfo.uid / PER_USER_RANGE, load);
        } catch (RuntimeException refused) {
            // A malformed resource keeps the default icon and a package retry.
            icon = null;
        }
        final String component = item.name == null ? "$application" : item.name;
        remember(context, appInfo.uid / PER_USER_RANGE + ":" + appInfo.packageName
                + "/" + component + "/" + item.icon, icon == null);
        return icon;
    }

    private static final int PER_USER_RANGE = 100000;

    private static synchronized void remember(Context context, String entry, boolean missed) {
        if (!missed) return;
        entry = packageKey(entry);
        final Refresh refreshing = REFRESHES.get(entry);
        if (refreshing != null) refreshing.missed = true;
        try {
            final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final Set<String> stored = prefs.getStringSet(MISSED, Set.of());
            final Set<String> set = new HashSet<>();
            for (String prior : stored) set.add(packageKey(prior));
            final boolean migrated = !stored.equals(set);
            final SharedPreferences.Editor edit = prefs.edit();
            boolean changed;
            if (missed && !set.contains(entry) && set.size() >= MAX_MISSED) {
                IconOverflow.overflow(context, refreshing != null);
                changed = migrated;
            } else {
                final boolean added = set.add(entry);
                changed = added || migrated;
            }
            if (changed && !edit.putStringSet(MISSED, set).commit()) {
                Log.w(TAG, "missed icon marker not committed");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "missed icons not remembered: " + e);
        }
        CleanCopyReloads.missed(entry);
    }

    static Set<String> pendingEntries(Context context) {
        try {
            return new HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(MISSED, new HashSet<>()));
        } catch (RuntimeException e) { return new HashSet<>(); }
    }

    public static final class Refresh {
        private final Set<String> entries;
        private final String pkg;
        private final UserHandle user;
        private final long overflow;
        private final long serial;
        private final String packageIdentity;
        private boolean missed;
        private Refresh(Context context, Set<String> entries, String pkg,
                UserHandle user, long overflow) {
            this.entries = entries;
            this.pkg = pkg;
            this.user = user;
            this.overflow = overflow;
            final android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
            serial = users == null ? -1 : users.getSerialNumberForUser(user);
            packageIdentity = identity(context, pkg, user);
        }
    }

    private static String identity(Context context, String pkg, UserHandle user) {
        try {
            final ApplicationInfo app = context.getSystemService(LauncherApps.class)
                    .getApplicationInfo(pkg, 0, user);
            return app.uid + ":" + app.longVersionCode + ":" + app.sourceDir;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException failure) {
            return null;
        }
    }

    /** Captures pending components before a complete package cache refresh. */
    public static synchronized Refresh beginRefresh(Context context, String pkg, UserHandle user) {
        final Set<String> entries = new HashSet<>();
        try {
            for (String entry : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(MISSED, new HashSet<>())) {
                final int colon = entry.indexOf(':');
                final int slash = entry.indexOf('/', colon + 1);
                if (entry.substring(colon + 1, slash < 0 ? entry.length() : slash).equals(pkg)
                        && UserHandle.getUserHandleForUid(Integer.parseInt(entry.substring(0, colon))
                                * PER_USER_RANGE).equals(user)) entries.add(entry);
            }
        } catch (RuntimeException ignored) {
            // A malformed marker does not acknowledge any component.
        }
        final Refresh refresh = new Refresh(context, entries, pkg, user,
                IconOverflow.generation(context));
        if (REFRESHES.size() >= 128) REFRESHES.clear();
        REFRESHES.put(packageKey(pkg, user), refresh);
        return refresh;
    }

    /** Each finished attempt advances traversal while a refused icon remains independently pending. */
    public static synchronized void endRefresh(Context context, Refresh refresh) {
        final String key = packageKey(refresh.pkg, refresh.user);
        if (REFRESHES.get(key) != refresh) return;
        REFRESHES.remove(key);
        final android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
        if (users == null || refresh.serial < 0 || !users.isUserUnlocked(refresh.user)
                || users.getSerialNumberForUser(refresh.user) != refresh.serial) return;
        if (!IconOverflow.profileVisible(context.getSystemService(LauncherApps.class), refresh.user)) {
            IconOverflow.retryProfile(context, refresh.user);
            return;
        }
        final String current = identity(context, refresh.pkg, refresh.user);
        if (!java.util.Objects.equals(current, refresh.packageIdentity)) {
            unavailable(context, refresh.pkg, refresh.user);
            return;
        }
        if (!refresh.missed) {
            try {
                final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                final Set<String> set = new HashSet<>(prefs.getStringSet(MISSED, Set.of()));
                if (set.removeAll(refresh.entries) && !prefs.edit().putStringSet(MISSED, set).commit()) return;
            } catch (RuntimeException failure) { return; }
        }
        IconOverflow.completed(context, refresh.pkg, refresh.user, refresh.overflow);
    }

    public static synchronized void refusedRefresh(Context context, Refresh refresh) {
        remember(context, packageKey(refresh.pkg, refresh.user), true);
        endRefresh(context, refresh);
    }

    /** Archiving preserves the trusted cache and any independently pending clean-copy miss. */
    public static synchronized void archivedRefresh(Context context, Refresh refresh) {
        refresh.missed = true;
        endRefresh(context, refresh);
    }

    /** Removed packages can complete without rendering, only after a current accessibility check. */
    public static synchronized void unavailable(Context context, String pkg, UserHandle user) {
        final android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
        if (users == null) return;
        final LauncherApps apps = context.getSystemService(LauncherApps.class);
        try {
            final long serial = users.getSerialNumberForUser(user);
            if (serial < 0 || !users.isUserUnlocked(user)
                    || !IconOverflow.profileVisible(apps, user)) {
                IconOverflow.retryProfile(context, user);
                return;
            }
            if (apps.isPackageEnabled(pkg, user)) return;
            // LauncherApps also reports a package disabled when it loses access to the profile.
            if (!IconOverflow.profileVisible(apps, user) || !users.isUserUnlocked(user)
                    || users.getSerialNumberForUser(user) != serial) {
                IconOverflow.retryProfile(context, user);
                return;
            }
            final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final Set<String> set = new HashSet<>(prefs.getStringSet(MISSED, Set.of()));
            final String key = packageKey(pkg, user);
            if (set.removeIf(entry -> packageKey(entry).equals(key))
                    && !prefs.edit().putStringSet(MISSED, set).commit()) return;
            REFRESHES.remove(key);
            IconOverflow.completed(context, pkg, user, IconOverflow.generation(context));
        } catch (RuntimeException failure) { }
    }

    /** Invalidates packages with a pending component, including markers from an earlier launcher process. */
    public static void forEachMissed(Context context, BiConsumer<String, UserHandle> reload) {
        forEachMissed(context, reload, true);
    }

    static void forEachExactMiss(Context context, BiConsumer<String, UserHandle> reload) {
        forEachMissed(context, reload, false);
    }

    private static void forEachMissed(Context context, BiConsumer<String, UserHandle> reload,
            boolean includeOverflow) {
        final Set<String> set;
        synchronized (CleanCopies.class) {
            try {
                final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                set = new HashSet<>(prefs.getStringSet(MISSED, new HashSet<>()));
            } catch (RuntimeException e) {
                return;
            }
        }
        final java.util.Map<UserHandle, Set<String>> packages = new java.util.HashMap<>();
        for (String entry : set) {
            final int colon = entry.indexOf(':');
            try {
                final int slash = entry.indexOf('/', colon + 1);
                final String pkg = entry.substring(colon + 1, slash < 0 ? entry.length() : slash);
                final UserHandle user = UserHandle.getUserHandleForUid(
                        Integer.parseInt(entry.substring(0, colon)) * PER_USER_RANGE);
                packages.computeIfAbsent(user, unused -> new HashSet<>()).add(pkg);
            } catch (RuntimeException ignored) {
                // A broken entry is skipped.
            }
        }
        if (includeOverflow) {
            try { IconOverflow.addTargets(context, packages); }
            catch (RuntimeException ignored) { IconOverflow.failed(context); }
        }
        packages.forEach((user, names) -> names.forEach(pkg -> {
            try {
                final android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
                if (!includeOverflow) {
                    if (users != null && users.isUserUnlocked(user)) reload.accept(pkg, user);
                    return;
                }
                final LauncherApps apps = context.getSystemService(LauncherApps.class);
                if (users != null && users.isUserUnlocked(user) && apps != null
                        && !apps.isPackageEnabled(pkg, user)) unavailable(context, pkg, user);
                else reload.accept(pkg, user);
            }
            catch (RuntimeException ignored) { if (includeOverflow) IconOverflow.failed(context); }
        }));
    }

    /** The app of an {@link #ACTION_MADE} broadcast, or null. */
    public static String madePackage(Intent intent) {
        return ACTION_MADE.equals(intent.getAction()) ? intent.getStringExtra(EXTRA_PACKAGE) : null;
    }

    /** The user of an {@link #ACTION_MADE} broadcast, or null. */
    public static UserHandle madeUser(Intent intent) {
        final Object user = intent.getParcelableExtra(Intent.EXTRA_USER);
        return user instanceof UserHandle ? (UserHandle) user : null;
    }
}
