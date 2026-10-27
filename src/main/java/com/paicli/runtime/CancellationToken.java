package com.paicli.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CancellationToken {
    private static final Logger log = LoggerFactory.getLogger(CancellationToken.class);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) registrations.forEach(Registration::fire);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    /** Register only a live operation; registration after cancellation fires immediately. */
    public Registration onCancel(Runnable action) {
        Registration registration = new Registration(Objects.requireNonNull(action, "action"));
        registrations.add(registration);
        if (cancelled.get()) registration.fire();
        return registration;
    }

    public final class Registration implements AutoCloseable {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final Runnable action;

        private Registration(Runnable action) { this.action = action; }

        private void fire() {
            if (!active.compareAndSet(true, false)) return;
            registrations.remove(this);
            try { action.run(); }
            catch (RuntimeException error) { log.warn("Cancellation action failed", error); }
        }

        @Override public void close() {
            active.set(false);
            registrations.remove(this);
        }
    }
}
