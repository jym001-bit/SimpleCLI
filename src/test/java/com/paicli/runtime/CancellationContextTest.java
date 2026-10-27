package com.paicli.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CancellationContextTest {

    @Test
    void explicitScopeRestoresPoolThreadAndDoesNotCarryCancelledTokenIntoNextRun() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CancellationToken first = new CancellationToken(), second = new CancellationToken();
        try {
            var previous = pool.submit(CancellationContext::currentThreadToken).get();
            pool.submit(() -> {
                try (var ignored = CancellationContext.bind(first)) {
                    first.cancel();
                    assertTrue(CancellationContext.isCancelled());
                }
                assertEquals(previous, CancellationContext.currentThreadToken());
                try (var ignored = CancellationContext.bind(second)) {
                    assertEquals(second, CancellationContext.current());
                    org.junit.jupiter.api.Assertions.assertFalse(CancellationContext.isCancelled());
                }
                assertEquals(previous, CancellationContext.currentThreadToken());
            }).get();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void liveCallbacksRunOnceAndClosedCallbacksAreDetached() {
        CancellationToken token = new CancellationToken();
        var invoked = new java.util.concurrent.atomic.AtomicInteger();
        token.onCancel(invoked::incrementAndGet);
        var closed = token.onCancel(() -> invoked.addAndGet(100));
        closed.close();
        token.cancel();
        token.cancel();
        assertEquals(1, invoked.get());
        token.onCancel(invoked::incrementAndGet).close();
        assertEquals(2, invoked.get(), "取消后的注册必须立即执行且只执行一次");
    }

    @Test
    void oneFailingCallbackCannotPreventOtherOperationsFromBeingCancelled() {
        CancellationToken token = new CancellationToken();
        var invoked = new java.util.concurrent.atomic.AtomicInteger();
        token.onCancel(() -> { throw new IllegalStateException("test cleanup failure"); });
        token.onCancel(invoked::incrementAndGet);
        token.cancel();
        assertEquals(1, invoked.get());
        assertTrue(token.isCancelled());
    }

    @Test
    void childThreadKeepsRunTokenAfterParentClearsGlobalContext() throws Exception {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch parentCleared = new CountDownLatch(1);
        try {
            Future<Boolean> result = executor.submit(() -> {
                parentCleared.await();
                return CancellationContext.current() == token;
            });

            CancellationContext.clear(token);
            parentCleared.countDown();

            assertTrue(result.get());
        } finally {
            token.cancel();
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }
}
