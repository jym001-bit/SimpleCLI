package com.paicli.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRetryPolicyTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void appliesExponentialBackoffWithBoundedJitter() {
        LlmRetryPolicy policy = policy(4, 500L, 10_000L, 0.5d);

        assertEquals(500L, policy.delayMillis(1, null));
        assertEquals(1_000L, policy.delayMillis(2, null));
        assertEquals(2_000L, policy.delayMillis(3, null));
    }

    @Test
    void honorsRetryAfterSecondsAndHttpDateWithinSafetyCap() {
        LlmRetryPolicy policy = policy(3, 500L, 30_000L, 0.5d);

        assertEquals(5_000L, policy.delayMillis(1, "5"));
        assertEquals(10_000L, policy.delayMillis(
                1, "Thu, 23 Jul 2026 00:00:10 GMT"));
        assertEquals(30_000L, policy.delayMillis(1, "120"));
    }

    @Test
    void classifiesTransientAndPermanentFailures() {
        LlmRetryPolicy policy = policy(3, 0L, 0L, 0.5d);

        assertTrue(policy.isRetryableFailure(new SocketTimeoutException("timeout")));
        assertTrue(policy.isRetryableFailure(
                new LlmHttpException(429, null, "rate limited")));
        assertTrue(policy.isRetryableFailure(
                new LlmStreamInterruptedException("unexpected eof")));
        assertFalse(policy.isRetryableFailure(
                new LlmHttpException(401, null, "unauthorized")));
        assertFalse(policy.isRetryableFailure(new IOException("invalid request")));
        assertFalse(policy.isRetryableFailure(new IOException("Canceled")));
    }

    @Test
    void retrySleepStopsWhenCancellationIsRequested() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        AtomicInteger sleeps = new AtomicInteger();
        LlmRetryPolicy policy = LlmRetryPolicy.forTest(
                3,
                500L,
                30_000L,
                () -> 0.5d,
                millis -> sleeps.incrementAndGet(),
                FIXED_CLOCK,
                cancelled::get
        );

        assertThrows(InterruptedIOException.class, () -> policy.sleep(500L));
        assertEquals(0, sleeps.get());
    }

    private static LlmRetryPolicy policy(int maxAttempts,
                                         long baseDelayMillis,
                                         long maxDelayMillis,
                                         double randomValue) {
        return LlmRetryPolicy.forTest(
                maxAttempts,
                baseDelayMillis,
                maxDelayMillis,
                () -> randomValue,
                millis -> {
                },
                FIXED_CLOCK
        );
    }
}
