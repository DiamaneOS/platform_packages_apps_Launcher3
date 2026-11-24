/* Copyright (C) 2026 DiamaneOS */
package com.android.launcher3.icons;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import android.os.UserManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Durable generations and bounded package batches for overflow recovery. */
final class IconOverflow {
    private static final String GENERATION = "rescan_generation";
    private static final String ACK = "rescan_acknowledged";
    private static final String DONE = "rescan_profiles_done";
    private static final String AFTER = "rescan_profiles_after";
    private static final int BATCH = 128;
    private static final String AGAIN = "rescan_again";
    private static final String DUE = "rescan_due";
    private static Batch active;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("diamaneos_clean_copies", Context.MODE_PRIVATE);
    }

    static synchronized void overflow(Context context) { overflow(context, false); }

    static synchronized void overflow(Context context, boolean recovering) {
        final SharedPreferences prefs = prefs(context);
        final long prior = generation(context);
        if (prior != 0 && prefs.getLong(ACK, 0) != prior) {
            if (!prefs.getBoolean(AGAIN, false) && (active != null
                    || !prefs.getStringSet(DONE, Set.of()).isEmpty()
                    || !prefs.getStringSet(AFTER, Set.of()).isEmpty())) {
                prefs.edit().putBoolean(AGAIN, true).putBoolean("rescan_needed", true)
                        .putStringSet(DONE, Set.of()).putStringSet(AFTER, Set.of()).commit();
            }
            return;
        }
        if (recovering) {
            defer(context);
            return;
        }
        final long next = prior == Long.MAX_VALUE ? 1 : prior + 1;
        prefs.edit().putLong(GENERATION, next).putLong(ACK, 0)
                .putStringSet(DONE, Set.of()).putStringSet(AFTER, Set.of())
                .putBoolean(AGAIN, false).putLong(DUE, 0)
                .putBoolean("rescan_needed", false).commit();
        active = null;
    }

    private static void defer(Context context) {
        prefs(context).edit().putLong(GENERATION, 0).putLong(ACK, 0)
                .putStringSet(DONE, Set.of()).putStringSet(AFTER, Set.of())
                .putBoolean(AGAIN, false).putBoolean("rescan_needed", true)
                .putLong(DUE, System.currentTimeMillis() + 60_000).commit();
        active = null;
        CleanCopyReloads.rescanLater();
    }

    static synchronized long generation(Context context) {
        final SharedPreferences prefs = prefs(context);
        long generation = prefs.getLong(GENERATION, 0);
        if (generation == 0 && prefs.getBoolean("rescan_needed", false)) {
            final long remaining = prefs.getLong(DUE, 0) - System.currentTimeMillis();
            if (remaining > 0 && remaining <= 60_000) {
                CleanCopyReloads.rescanLater();
                return 0;
            }
            generation = 1;
            prefs.edit().putLong(GENERATION, generation).commit();
        }
        return generation;
    }

    static synchronized void addTargets(Context context, Map<UserHandle, Set<String>> targets) {
        final SharedPreferences prefs = prefs(context);
        final long generation = generation(context);
        if (generation == 0 || prefs.getLong(ACK, 0) == generation) return;
        if (active != null && active.generation == generation && sameContext(context)
                && !active.pending.isEmpty() && discardUnavailable(context, prefs)) {
            finishIfComplete(context);
            if (active == null) return;
        }
        if (active == null || active.generation != generation || !sameContext(context)
                || active.pending.isEmpty()) {
            active = plan(context, generation, active);
        }
        if (active == null) return;
        for (Map.Entry<UserHandle, Set<String>> entry : active.pending.entrySet()) {
            targets.computeIfAbsent(entry.getKey(), unused -> new HashSet<>()).addAll(entry.getValue());
        }
        finishIfComplete(context);
    }

    private static boolean sameContext(Context context) {
        return active.context.getApplicationContext() == context.getApplicationContext();
    }

    static boolean profileVisible(LauncherApps apps, UserHandle user) {
        try {
            return apps != null && apps.getProfiles().contains(user);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static synchronized void retryProfile(Context context, UserHandle user) {
        if (active != null && sameContext(context) && active.pending.containsKey(user)) {
            CleanCopyReloads.rescan();
        }
    }

    private static boolean discardUnavailable(Context context, SharedPreferences prefs) {
        final LauncherApps apps = context.getSystemService(LauncherApps.class);
        final UserManager users = context.getSystemService(UserManager.class);
        if (apps == null || users == null) return false;
        final var profiles = apps.getProfiles();
        return active.pending.keySet().removeIf(user -> {
            final long serial = active.serials.get(user);
            final boolean sameLifetime = users.getSerialNumberForUser(user) == serial;
            if (sameLifetime && profiles.contains(user) && users.isUserUnlocked(user)) return false;
            final String key = Long.toString(serial);
            active.done.remove(key);
            active.after.remove(key);
            active.catalog.remove(key);
            if (!sameLifetime) {
                active.profiles.remove(key);
                return true;
            }
            active.after.put(key, "");
            for (String entry : prefs.getStringSet(AFTER, Set.of())) {
                if (entry.startsWith(key + ":")) active.after.put(key, entry.substring(key.length() + 1));
            }
            return true;
        });
    }

    private static Batch plan(Context context, long generation, Batch previous) {
        final LauncherApps apps = context.getSystemService(LauncherApps.class);
        final UserManager users = context.getSystemService(UserManager.class);
        if (apps == null || users == null) return null;
        final Batch batch = new Batch(context, generation);
        final SharedPreferences prefs = prefs(context);
        if (previous != null && previous.generation == generation && sameContext(context)) {
            batch.done.addAll(previous.done);
            batch.after.putAll(previous.after);
            batch.catalog.putAll(previous.catalog);
            // An unavailable profile can disappear from the launcher inventory before it unlocks.
            previous.serials.forEach((user, serial) -> {
                if (users.getSerialNumberForUser(user) == serial) {
                    batch.profiles.add(Long.toString(serial));
                    batch.serials.put(user, serial);
                }
            });
        } else {
            batch.done.addAll(prefs.getStringSet(DONE, Set.of()));
            for (String entry : prefs.getStringSet(AFTER, Set.of())) {
                final int colon = entry.indexOf(':');
                if (colon > 0) batch.after.put(entry.substring(0, colon), entry.substring(colon + 1));
            }
            final Set<String> saved = new HashSet<>(batch.done);
            saved.addAll(batch.after.keySet());
            for (String key : saved) {
                try {
                    final long serial = Long.parseLong(key);
                    if (serial < 0 || !key.equals(Long.toString(serial))) continue;
                    final UserHandle user = users.getUserForSerialNumber(serial);
                    if (user != null && users.getSerialNumberForUser(user) == serial) {
                        batch.profiles.add(key);
                        batch.serials.put(user, serial);
                    }
                } catch (NumberFormatException invalid) { }
            }
        }
        int left = BATCH;
        for (UserHandle user : apps.getProfiles()) {
            final long serial = users.getSerialNumberForUser(user);
            if (serial < 0) return null;
            final String key = Long.toString(serial);
            batch.profiles.add(key);
            batch.serials.put(user, serial);
            batch.after.putIfAbsent(key, "");
            if (batch.done.contains(key)) continue;
            if (!users.isUserUnlocked(user)) continue;
            if (left == 0) { batch.moreUnlocked = true; continue; }
            try {
                final TreeSet<String> names = new TreeSet<>();
                final String after = batch.after.getOrDefault(key, "");
                Set<String> catalog = batch.catalog.get(key);
                if (catalog == null) {
                    catalog = new TreeSet<>();
                    for (LauncherActivityInfo activity : apps.getActivityList(null, user)) {
                        catalog.add(activity.getComponentName().getPackageName());
                    }
                    batch.catalog.put(key, catalog);
                }
                for (String name : catalog) if (name.compareTo(after) > 0) names.add(name);
                final Set<String> pending = new HashSet<>();
                for (String name : names) {
                    if (left == 0) { batch.moreUnlocked = true; break; }
                    pending.add(name);
                    batch.after.put(key, name);
                    left--;
                }
                if (!pending.isEmpty()) batch.pending.put(user, pending);
                if (pending.size() == names.size()) batch.done.add(key);
            } catch (RuntimeException ignored) {
                // An inaccessible profile remains pending until another refill or unlock.
            }
        }
        return batch;
    }

    static synchronized void completed(Context context, String pkg, UserHandle user, long generation) {
        if (active == null || !sameContext(context) || active.generation != generation
                || generation(context) != generation) return;
        final UserManager users = context.getSystemService(UserManager.class);
        if (!active.pending.containsKey(user) || users == null) return;
        final LauncherApps apps = context.getSystemService(LauncherApps.class);
        if (!profileVisible(apps, user) || !users.isUserUnlocked(user)
                || !java.util.Objects.equals(active.serials.get(user), users.getSerialNumberForUser(user))) {
            CleanCopyReloads.rescan();
            return;
        }
        final Set<String> pending = active.pending.get(user);
        if (pending == null || !pending.remove(pkg)) return;
        if (pending.isEmpty()) active.pending.remove(user);
        finishIfComplete(context);
    }

    static synchronized void failed(Context context) {
        if (active != null && sameContext(context)) active = null;
        CleanCopyReloads.rescanLater();
    }

    private static void finishIfComplete(Context context) {
        if (active == null || !active.pending.isEmpty()) return;
        final Batch batch = active;
        if (generation(context) != batch.generation) { active = null; return; }
        batch.done.retainAll(batch.profiles);
        batch.after.keySet().retainAll(batch.profiles);
        final Set<String> after = new HashSet<>();
        batch.after.forEach((user, pkg) -> after.add(user + ":" + pkg));
        final boolean complete = !batch.profiles.isEmpty() && batch.done.containsAll(batch.profiles);
        final boolean again = prefs(context).getBoolean(AGAIN, false);
        if (complete && again) { defer(context); return; }
        final SharedPreferences.Editor edit = prefs(context).edit();
        if (!again) edit.putStringSet(AFTER, after).putStringSet(DONE, batch.done);
        if (complete) edit.putLong(ACK, batch.generation).putBoolean("rescan_needed", false);
        if (!edit.commit()) return;
        if (complete) active = null;
        else if (batch.moreUnlocked) CleanCopyReloads.rescan();
    }

    private static final class Batch {
        final Context context;
        final long generation;
        final Map<UserHandle, Set<String>> pending = new HashMap<>();
        final Map<String, String> after = new HashMap<>();
        final Set<String> profiles = new HashSet<>();
        final Map<UserHandle, Long> serials = new HashMap<>();
        final Map<String, Set<String>> catalog = new HashMap<>();
        final Set<String> done = new HashSet<>();
        boolean moreUnlocked;
        Batch(Context context, long generation) { this.context = context; this.generation = generation; }
    }
}
