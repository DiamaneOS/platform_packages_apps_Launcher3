/* Copyright (C) 2026 DiamaneOS */
package com.android.launcher3.icons;

import android.content.Context;
import android.os.UserHandle;
import com.android.launcher3.util.SafeCloseable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Connects durable launcher misses to the framework gate's existing refill notifications. */
public final class CleanCopyReloads {
    private static volatile Watch active;
    private CleanCopyReloads() { }

    public static synchronized SafeCloseable watch(Context context, Executor executor,
            BiConsumer<String, UserHandle> reload) {
        if (active != null) active.close();
        final Watch next = new Watch(context, executor, reload);
        active = next;
        // This sentinel receives global recovery, which the gate sends to all registered listeners.
        next.register("$launcher-pending", 0);
        for (String entry : CleanCopies.pendingEntries(context)) missed(entry);
        return next;
    }

    public static void rescan() {
        final Watch current = active;
        if (current != null) current.ready();
    }

    static void rescanLater() {
        final Watch current = active;
        if (current != null) current.later();
    }

    static void missed(String entry) {
        final Watch current = active;
        if (current == null) return;
        try {
            final int colon = entry.indexOf(':');
            final int slash = entry.indexOf('/', colon + 1);
            current.register(entry.substring(colon + 1, slash < 0 ? entry.length() : slash),
                    Integer.parseInt(entry.substring(0, colon)));
        } catch (RuntimeException ignored) { }
    }

    private static final class Watch implements SafeCloseable {
        private final Context context;
        private final Executor executor;
        private final BiConsumer<String, UserHandle> reload;
        private final AtomicBoolean queued = new AtomicBoolean();
        private final Map<String, Object> listeners = new HashMap<>();
        private volatile boolean closed;
        private final AtomicBoolean delayed = new AtomicBoolean();
        private final android.os.Handler timer = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable retry = () -> { delayed.set(false); ready(); };
        private final android.content.BroadcastReceiver unlocked = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context context, android.content.Intent intent) { ready(); }
        };

        Watch(Context context, Executor executor, BiConsumer<String, UserHandle> reload) {
            this.context = context;
            this.executor = executor;
            this.reload = reload;
            final android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(android.content.Intent.ACTION_USER_UNLOCKED);
            filter.addAction(android.content.Intent.ACTION_MANAGED_PROFILE_UNLOCKED);
            filter.addAction(android.content.Intent.ACTION_PROFILE_ACCESSIBLE);
            context.registerReceiver(unlocked, filter, Context.RECEIVER_EXPORTED);
        }

        synchronized void register(String pkg, int user) {
            final String key = user + ":" + pkg;
            if (closed || listeners.containsKey(key) || listeners.size() >= 257) return;
            try {
                final Class<?> gate = Class.forName(
                        "com.android.internal.diamaneos.cleancopies.CleanCopyGate");
                final Class<?> listener = Class.forName(gate.getName() + "$MadeListener");
                final Method whenMade = gate.getMethod("whenMade", listener, String.class, int.class);
                final Object proxy = Proxy.newProxyInstance(listener.getClassLoader(),
                        new Class<?>[] {listener}, (object, method, arguments) -> {
                            if ("onMade".equals(method.getName())) { ready(); return null; }
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                            if ("equals".equals(method.getName())) return object == arguments[0];
                            return "Launcher image refill";
                        });
                listeners.put(key, proxy);
                try { whenMade.invoke(null, proxy, pkg, user); }
                catch (ReflectiveOperationException | RuntimeException e) { listeners.remove(key); }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }

        private void later() {
            if (!closed && delayed.compareAndSet(false, true)) timer.postDelayed(retry, 60_000);
        }

        private void ready() {
            if (closed || !queued.compareAndSet(false, true)) return;
            // Yield between bounded batches even when the destination executor runs inline.
            if (!timer.post(() -> {
                if (closed) { queued.set(false); return; }
                try {
                    executor.execute(() -> {
                        queued.set(false);
                        if (!closed) CleanCopies.forEachMissed(context, reload);
                    });
                } catch (RuntimeException failure) { queued.set(false); }
            })) queued.set(false);
        }

        @Override public synchronized void close() {
            closed = true;
            timer.removeCallbacks(retry);
            try { context.unregisterReceiver(unlocked); } catch (RuntimeException ignored) { }
            listeners.clear();
            if (active == this) active = null;
        }
    }
}
