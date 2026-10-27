package com.paicli.runtime;

import java.util.concurrent.atomic.AtomicReference;

public final class CancellationContext {
    private static final AtomicReference<CancellationToken> CURRENT = new AtomicReference<>();
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        CURRENT.set(token);
        LOCAL.set(token);
        return token;
    }

    public static CancellationToken current() {
        CancellationToken token = LOCAL.get();
        return token == null ? CURRENT.get() : token;
    }

    /** The caller's run only; unlike current(), never borrows another run's global fallback. */
    public static CancellationToken currentThreadToken() {
        return LOCAL.get();
    }

    /** Explicitly propagate a captured run into a pool thread, restoring its previous scope. */
    public static Scope bind(CancellationToken token) {
        CancellationToken previous = LOCAL.get();
        LOCAL.set(java.util.Objects.requireNonNull(token, "token"));
        return () -> {
            if (previous == null) LOCAL.remove(); else LOCAL.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
        CURRENT.compareAndSet(token, null);
    }
}
