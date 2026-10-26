package com.paicli.eval.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one bounded child process while draining stdout and stderr concurrently. */
final class BenchmarkSubprocess {
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private BenchmarkSubprocess() {
    }

    static Result run(ProcessBuilder builder,
                      byte[] stdin,
                      Duration timeout,
                      int stdoutLimit,
                      int stderrLimit) throws IOException, InterruptedException {
        if (builder == null) {
            throw new IllegalArgumentException("builder must not be null");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (stdoutLimit <= 0 || stderrLimit <= 0) {
            throw new IllegalArgumentException("output limits must be positive");
        }

        long started = System.nanoTime();
        Process process = builder.start();
        ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable,
                    "paicli-benchmark-process-reader-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        Future<Collected> stdout = readers.submit(() -> collect(process.getInputStream(), stdoutLimit));
        Future<Collected> stderr = readers.submit(() -> collect(process.getErrorStream(), stderrLimit));
        Set<ProcessHandle> observedDescendants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ScheduledExecutorService tracker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "paicli-benchmark-process-tracker-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        tracker.scheduleAtFixedRate(
                () -> snapshotDescendants(process, observedDescendants), 0, 25, TimeUnit.MILLISECONDS);

        boolean timedOut = false;
        try {
            try (OutputStream childInput = process.getOutputStream()) {
                if (stdin != null && stdin.length > 0) {
                    childInput.write(stdin);
                    childInput.flush();
                }
            }

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                destroyTree(process, observedDescendants);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            snapshotDescendants(process, observedDescendants);
            destroyObserved(observedDescendants);

            Collected capturedOut = await(stdout);
            Collected capturedErr = await(stderr);
            Integer exitCode = process.isAlive() ? null : process.exitValue();
            return new Result(
                    timedOut,
                    exitCode,
                    capturedOut.text(),
                    capturedErr.text(),
                    capturedOut.truncated(),
                    capturedErr.truncated(),
                    elapsedMillis(started));
        } finally {
            if (process.isAlive()) {
                destroyTree(process, observedDescendants);
            }
            destroyObserved(observedDescendants);
            tracker.shutdownNow();
            readers.shutdownNow();
        }
    }

    private static Collected collect(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limit, 16_384));
        byte[] buffer = new byte[8_192];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            int remaining = Math.max(0, limit - total);
            int keep = Math.min(remaining, read);
            if (keep > 0) {
                captured.write(buffer, 0, keep);
                total += keep;
            }
            if (keep < read) {
                truncated = true;
            }
        }
        return new Collected(captured.toString(StandardCharsets.UTF_8), truncated);
    }

    private static Collected await(Future<Collected> future) throws IOException, InterruptedException {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("failed to collect child process output", cause);
        } catch (TimeoutException e) {
            throw new IOException("timed out collecting child process output", e);
        }
    }

    private static void snapshotDescendants(Process process, Set<ProcessHandle> observed) {
        try {
            observed.addAll(process.descendants().toList());
        } catch (RuntimeException ignored) {
            // Some restricted hosts deny process-tree enumeration. Root termination still proceeds.
        }
    }

    private static void destroyTree(Process process, Set<ProcessHandle> observed) {
        snapshotDescendants(process, observed);
        destroyObserved(observed);
        process.destroyForcibly();
    }

    private static void destroyObserved(Set<ProcessHandle> observed) {
        List<ProcessHandle> descendants = new ArrayList<>(observed);
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        for (ProcessHandle descendant : descendants) {
            try {
                if (descendant.isAlive()) {
                    descendant.destroyForcibly();
                }
            } catch (RuntimeException ignored) {
                // Best effort only; the manifest states that double-fork isolation is not proven.
            }
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    record Result(boolean timedOut,
                  Integer exitCode,
                  String stdout,
                  String stderr,
                  boolean stdoutTruncated,
                  boolean stderrTruncated,
                  long elapsedMillis) {
    }

    private record Collected(String text, boolean truncated) {
    }
}
