package com.paicli.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paicli.runtime.CancellationContext;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * LLM HTTP/SSE 的有限重试策略。
 *
 * <p>这里仅负责判断瞬时故障和计算等待时间；调用方还必须确认本次流式响应
 * 尚未向消费者交付内容，避免重放已经显示给用户的增量文本。</p>
 */
final class LlmRetryPolicy {

    static final String MAX_ATTEMPTS_PROPERTY = "paicli.llm.retry.max-attempts";
    static final String BASE_DELAY_MILLIS_PROPERTY = "paicli.llm.retry.base-delay.millis";
    static final String MAX_DELAY_MILLIS_PROPERTY = "paicli.llm.retry.max-delay.millis";

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BASE_DELAY_MILLIS = 500L;
    private static final long DEFAULT_MAX_DELAY_MILLIS = 30_000L;
    private static final double JITTER_RATIO = 0.2d;

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final DoubleSupplier random;
    private final Sleeper sleeper;
    private final Clock clock;
    private final BooleanSupplier cancelled;

    private LlmRetryPolicy(int maxAttempts,
                           long baseDelayMillis,
                           long maxDelayMillis,
                           DoubleSupplier random,
                           Sleeper sleeper,
                           Clock clock,
                           BooleanSupplier cancelled) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMillis = Math.max(0L, baseDelayMillis);
        this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
        this.random = random;
        this.sleeper = sleeper;
        this.clock = clock;
        this.cancelled = cancelled;
    }

    static LlmRetryPolicy fromSystemProperties() {
        int maxAttempts = readIntProperty(MAX_ATTEMPTS_PROPERTY, DEFAULT_MAX_ATTEMPTS, 1, 10);
        long baseDelayMillis = readLongProperty(
                BASE_DELAY_MILLIS_PROPERTY, DEFAULT_BASE_DELAY_MILLIS, 0L, 60_000L);
        long maxDelayMillis = readLongProperty(
                MAX_DELAY_MILLIS_PROPERTY, DEFAULT_MAX_DELAY_MILLIS, baseDelayMillis, 300_000L);
        return new LlmRetryPolicy(
                maxAttempts,
                baseDelayMillis,
                maxDelayMillis,
                ThreadLocalRandom.current()::nextDouble,
                Thread::sleep,
                Clock.systemUTC(),
                CancellationContext::isCancelled
        );
    }

    static LlmRetryPolicy forTest(int maxAttempts,
                                  long baseDelayMillis,
                                  long maxDelayMillis,
                                  DoubleSupplier random,
                                  Sleeper sleeper,
                                  Clock clock) {
        return forTest(maxAttempts, baseDelayMillis, maxDelayMillis, random, sleeper, clock, () -> false);
    }

    static LlmRetryPolicy forTest(int maxAttempts,
                                  long baseDelayMillis,
                                  long maxDelayMillis,
                                  DoubleSupplier random,
                                  Sleeper sleeper,
                                  Clock clock,
                                  BooleanSupplier cancelled) {
        return new LlmRetryPolicy(
                maxAttempts, baseDelayMillis, maxDelayMillis, random, sleeper, clock, cancelled);
    }

    int maxAttempts() {
        return maxAttempts;
    }

    boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    boolean isRetryableFailure(IOException failure) {
        if (failure instanceof LlmHttpException httpFailure) {
            return isRetryableStatus(httpFailure.statusCode());
        }
        if (failure instanceof LlmStreamInterruptedException) {
            return true;
        }
        if (failure instanceof LlmStreamingApiException streamingFailure) {
            return streamingFailure.retryable();
        }
        if (failure instanceof JsonProcessingException || failure instanceof SSLException) {
            return false;
        }
        if (Thread.currentThread().isInterrupted() || isCancellation(failure)) {
            return false;
        }
        if (failure instanceof SocketTimeoutException
                || failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof EOFException
                || failure instanceof SocketException) {
            return true;
        }
        if (failure instanceof InterruptedIOException) {
            return true;
        }

        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("unexpected end of stream")
                || normalized.contains("connection shutdown")
                || normalized.contains("connection reset")
                || normalized.contains("stream was reset")
                || normalized.contains("timeout");
    }

    long delayMillis(int retryNumber, String retryAfterHeader) {
        int boundedRetryNumber = Math.max(1, retryNumber);
        int shift = Math.min(30, boundedRetryNumber - 1);
        long exponential;
        if (baseDelayMillis == 0L) {
            exponential = 0L;
        } else if (baseDelayMillis > Long.MAX_VALUE >> shift) {
            exponential = maxDelayMillis;
        } else {
            exponential = Math.min(maxDelayMillis, baseDelayMillis << shift);
        }

        double randomValue = Math.max(0d, Math.min(1d, random.getAsDouble()));
        double jitterMultiplier = (1d - JITTER_RATIO) + (2d * JITTER_RATIO * randomValue);
        long jittered = Math.min(maxDelayMillis, Math.max(0L, Math.round(exponential * jitterMultiplier)));
        long retryAfter = parseRetryAfterMillis(retryAfterHeader);
        return Math.min(maxDelayMillis, Math.max(jittered, retryAfter));
    }

    void sleep(long delayMillis) throws IOException {
        long remaining = Math.max(0L, delayMillis);
        while (true) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                throw new InterruptedIOException("等待 LLM 重试时任务被取消");
            }
            if (remaining == 0L) {
                return;
            }
            long slice = Math.min(100L, remaining);
            try {
                sleeper.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("等待 LLM 重试时任务被中断");
                interrupted.initCause(e);
                throw interrupted;
            }
            remaining -= slice;
        }
    }

    private long parseRetryAfterMillis(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return 0L;
        }
        String value = rawHeader.trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0L) {
                return 0L;
            }
            return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
        } catch (NumberFormatException ignored) {
            // Retry-After 也允许 RFC 1123 HTTP-date。
        }

        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0L, Duration.between(clock.instant(), retryAt).toMillis());
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    private static boolean isCancellation(IOException failure) {
        String message = failure.getMessage();
        return message != null && "canceled".equalsIgnoreCase(message.trim());
    }

    private static int readIntProperty(String key, int defaultValue, int min, int max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed >= min && parsed <= max ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long readLongProperty(String key, long defaultValue, long min, long max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed >= min && parsed <= max ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long delayMillis) throws InterruptedException;
    }
}

final class LlmHttpException extends IOException {
    private final int statusCode;
    private final String retryAfter;

    LlmHttpException(int statusCode, String retryAfter, String message) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    int statusCode() {
        return statusCode;
    }

    String retryAfter() {
        return retryAfter;
    }
}

final class LlmStreamInterruptedException extends IOException {
    LlmStreamInterruptedException(String message) {
        super(message);
    }
}

final class LlmStreamingApiException extends IOException {
    private final boolean retryable;

    LlmStreamingApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}
