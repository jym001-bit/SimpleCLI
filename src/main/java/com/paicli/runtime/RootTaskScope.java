package com.paicli.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import com.paicli.agent.AgentBudget;
import com.paicli.llm.LlmClient;

/** Owns one root run's cancellation signal and confirms when its execution thread has exited. */
public final class RootTaskScope implements AutoCloseable {
    private static final ConcurrentHashMap<CancellationToken, RootTaskScope> ACTIVE = new ConcurrentHashMap<>();
    private final CancellationToken token = new CancellationToken();
    private final String taskId = UUID.randomUUID().toString();
    private final AgentBudget budget = AgentBudget.fromSystemProperties();
    private final int maxRequests = Math.max(1, Integer.getInteger("paicli.root.model.request.limit", Integer.MAX_VALUE));
    private int requests;
    private boolean admissionBlocked;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Thread executionThread;

    public String taskId() { return taskId; }
    public static RootTaskScope current() {
        CancellationToken current = CancellationContext.current();
        return current == null ? null : ACTIVE.get(current);
    }
    public CancellationContext.Scope bind() {
        ACTIVE.put(token, this);
        return CancellationContext.bind(token);
    }
    public synchronized boolean canRunOptionalRequest() {
        return !token.isCancelled() && requests < maxRequests - 1 && budget.check() == AgentBudget.ExitReason.WITHIN_BUDGET;
    }
    public synchronized boolean exhausted() {
        return requests >= maxRequests || budget.check() == AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED;
    }
    public synchronized boolean admissionBlocked() { return admissionBlocked; }
    public synchronized boolean requestLimitReached() { return requests >= maxRequests; }
    public synchronized int requests() { return requests; }
    public synchronized int inputTokens() { return budget.totalInputTokens(); }
    public synchronized int outputTokens() { return budget.totalOutputTokens(); }
    public synchronized void admitRequest() {
        if (token.isCancelled()) throw new java.util.concurrent.CancellationException("Root task cancelled");
        if (exhausted()) {
            admissionBlocked = true;
            throw new RootRequestBudgetExceededException();
        }
        requests++;
    }
    public synchronized void recordUsage(LlmClient.ChatResponse response) {
        if (response != null && response.usagePresent()) {
            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        }
    }

    public CancellationToken token() { return token; }

    public <T> T run(Callable<T> action) throws Exception {
        Objects.requireNonNull(action, "action");
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Root task already started");
        executionThread = Thread.currentThread();
        try (CancellationContext.Scope ignored = bind()) {
            if (token.isCancelled()) throw new InterruptedException("Root task cancelled before execution");
            return action.call();
        } finally {
            executionThread = null;
            stopped.countDown();
            close();
        }
    }

    public void requestCancel() {
        token.cancel();
        Thread thread = executionThread;
        if (thread != null) thread.interrupt();
    }

    /** Returns false when the run has not confirmed exit within the caller's bounded wait. */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        return stopped.await(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
    }

    public boolean isStopped() { return stopped.getCount() == 0; }

    @Override public void close() { ACTIVE.remove(token, this); }

    public static final class RootRequestBudgetExceededException extends RuntimeException {
        public RootRequestBudgetExceededException() { super("Root model request budget exhausted"); }
    }
}
