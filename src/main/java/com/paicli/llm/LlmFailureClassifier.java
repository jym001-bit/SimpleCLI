package com.paicli.llm;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Stable, message-free failure categories for benchmark and runtime orchestration. */
public final class LlmFailureClassifier {
    private LlmFailureClassifier() {
    }

    public static Category classify(IOException failure) {
        if (failure == null) {
            return Category.UNKNOWN;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LlmHttpException http) {
                int status = http.statusCode();
                if (status == 408 || status == 429 || status >= 500) {
                    return Category.TRANSIENT;
                }
                if (status == 401 || status == 403) {
                    return Category.AUTHENTICATION;
                }
                if (status >= 400 && status < 500) {
                    return Category.INVALID_REQUEST;
                }
                return Category.UNKNOWN;
            }
            if (current instanceof LlmStreamingApiException streaming) {
                return streaming.retryable() ? Category.TRANSIENT : Category.INVALID_RESPONSE;
            }
            if (current instanceof LlmStreamInterruptedException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof EOFException) {
                return Category.TRANSIENT;
            }
            if (current instanceof InterruptedIOException) {
                return Category.CANCELLED_OR_TIMEOUT;
            }
        }
        return Category.UNKNOWN;
    }

    public enum Category {
        TRANSIENT,
        AUTHENTICATION,
        INVALID_REQUEST,
        INVALID_RESPONSE,
        CANCELLED_OR_TIMEOUT,
        UNKNOWN
    }
}
