package com.paicli.runtime.task;

import com.paicli.agent.ExecutionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class DurableTaskManagerTest {

    @Test
    void runsEnqueuedTaskAndPersistsResult(@TempDir Path tempDir) throws Exception {
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> ExecutionOutcome.completed("done:" + prompt),
                1)) {
            manager.start();

            DurableTask task = manager.enqueue("hello");
            DurableTask completed = waitForTerminal(manager, task.id());

            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("done:hello", completed.result());
            assertTrue(manager.list(10).stream().anyMatch(t -> t.id().equals(task.id())));
        }
    }

    @Test
    void recoversRunningTasksAsEnqueued(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        try (DurableTaskManager manager = new DurableTaskManager(db, prompt -> ExecutionOutcome.completed("never"), 1)) {
            DurableTask task = manager.enqueue("resume me");
            markRunning(manager, task.id());
        }

        try (DurableTaskManager recovered = new DurableTaskManager(db, prompt -> ExecutionOutcome.completed("ok"), 1)) {
            assertEquals(TaskStatus.ENQUEUED, recovered.find(recovered.list(1).get(0).id()).orElseThrow().status());
        }
    }

    @Test
    void cancelsRunningTask(@TempDir Path tempDir) throws Exception {
        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"),
                prompt -> {
                    Thread.sleep(5000);
                    return ExecutionOutcome.completed("late");
                },
                1)) {
            manager.start();
            DurableTask task = manager.enqueue("slow");
            waitUntilStatus(manager, task.id(), TaskStatus.RUNNING);

            assertTrue(manager.cancel(task.id()));
            DurableTask canceled = waitForTerminal(manager, task.id());

            assertEquals(TaskStatus.CANCELED, canceled.status());
            assertEquals(TaskCleanupState.STOPPED, canceled.cleanupState());
        }
    }

    @Test
    void cancellationIsIsolatedAndWaitsForExecutionExit(@TempDir Path tempDir) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicReference<com.paicli.runtime.CancellationToken> firstToken = new AtomicReference<>();
        AtomicReference<com.paicli.runtime.CancellationToken> secondToken = new AtomicReference<>();
        try (DurableTaskManager manager = new DurableTaskManager(tempDir.resolve("tasks.db"), prompt -> {
            if (prompt.equals("first")) {
                firstToken.set(com.paicli.runtime.CancellationContext.current());
                firstEntered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ignored) { return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "partial first"); }
                throw new AssertionError("unreachable");
            }
            secondToken.set(com.paicli.runtime.CancellationContext.current());
            secondEntered.countDown();
            return ExecutionOutcome.completed("second completed");
        }, 2)) {
            manager.start();
            DurableTask first = manager.enqueue("first");
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            DurableTask second = manager.enqueue("second");
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertTrue(manager.cancel(first.id()));
            assertEquals(TaskCleanupState.STOPPED, manager.find(first.id()).orElseThrow().cleanupState());
            assertEquals("partial first", manager.find(first.id()).orElseThrow().result());
            assertNotSame(firstToken.get(), secondToken.get());
            assertFalse(secondToken.get().isCancelled());
            assertEquals(TaskStatus.COMPLETED, waitForTerminal(manager, second.id()).status());
        }
    }

    @Test
    void nonCooperativeRunReportsUnconfirmedCleanupThenLaterExit(@TempDir Path tempDir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (DurableTaskManager manager = new DurableTaskManager(tempDir.resolve("tasks.db"), prompt -> {
            entered.countDown();
            while (true) {
                try { if (release.await(5, TimeUnit.SECONDS)) break; }
                catch (InterruptedException ignored) { /* Simulate a non-cooperative Java operation. */ }
            }
            return ExecutionOutcome.completed("late result");
        }, 1)) {
            manager.start();
            DurableTask task = manager.enqueue("slow");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertTrue(manager.cancel(task.id()));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs >= 1800 && elapsedMs < 4500, "cancel must wait, but stay bounded: " + elapsedMs);
            DurableTask incomplete = manager.find(task.id()).orElseThrow();
            assertEquals(TaskStatus.CANCELED, incomplete.status());
            assertEquals(TaskCleanupState.INCOMPLETE, incomplete.cleanupState());
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until
                    && manager.find(task.id()).orElseThrow().cleanupState() != TaskCleanupState.STOPPED) {
                Thread.sleep(10);
            }
            DurableTask exited = manager.find(task.id()).orElseThrow();
            assertEquals(TaskCleanupState.STOPPED, exited.cleanupState());
            assertEquals(TaskStatus.CANCELED, exited.status());
            assertEquals(incomplete.finishedAt(), exited.finishedAt());
            assertEquals(incomplete.result(), exited.result());
        } finally { release.countDown(); }
    }

    @Test
    void closingManagerCancelsItsActiveRunAndWaitsWithoutHoldingDatabaseLock(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch tokenCancelled = new CountDownLatch(1);
        DurableTaskManager manager = new DurableTaskManager(db, prompt -> {
            try (var registration = com.paicli.runtime.CancellationContext.current().onCancel(tokenCancelled::countDown)) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ignored) { return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "shutdown partial"); }
            }
            throw new AssertionError("unreachable");
        }, 1);
        manager.start();
        DurableTask task = manager.enqueue("close me");
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            manager.close();
            assertEquals(0, tokenCancelled.getCount());
            try (DurableTaskManager reopened = new DurableTaskManager(db,
                    prompt -> ExecutionOutcome.completed("unexpected replay"), 1)) {
                DurableTask completed = reopened.find(task.id()).orElseThrow();
                assertEquals(TaskStatus.CANCELED, completed.status());
                assertEquals(TaskCleanupState.STOPPED, completed.cleanupState());
                assertEquals("shutdown partial", completed.result());
            }
        } finally { manager.close(); }
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionOutcome.Reason.class, names = {"COMPLETED", "EXECUTION_ERROR", "CANCELLED"})
    void cancellationTerminalCannotBeOverwrittenByLateReturn(ExecutionOutcome.Reason late,
                                                             @TempDir Path tempDir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (DurableTaskManager manager = new DurableTaskManager(tempDir.resolve("tasks.db"), prompt -> {
            if (prompt.equals("barrier")) return ExecutionOutcome.completed("next task");
            entered.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Deliberately emulate an operation finishing late. */ }
            }
            if (late == ExecutionOutcome.Reason.EXECUTION_ERROR) throw new IllegalStateException("late failure");
            return new ExecutionOutcome(late, "actual partial output");
        }, 1)) {
            manager.start();
            DurableTask task = manager.enqueue("slow");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(manager.cancel(task.id()));
            DurableTask firstTerminal = manager.find(task.id()).orElseThrow();
            release.countDown();
            assertEquals(TaskStatus.COMPLETED, waitForTerminal(manager, manager.enqueue("barrier").id()).status());
            DurableTask afterLateReturn = manager.find(task.id()).orElseThrow();
            assertEquals(TaskStatus.CANCELED, afterLateReturn.status());
            assertEquals(ExecutionOutcome.Reason.CANCELLED, afterLateReturn.reason());
            assertEquals(firstTerminal.finishedAt(), afterLateReturn.finishedAt());
            assertEquals(firstTerminal.error(), afterLateReturn.error());
            if (late == ExecutionOutcome.Reason.CANCELLED)
                assertEquals("actual partial output", afterLateReturn.result());
            else assertEquals(firstTerminal.result(), afterLateReturn.result());
            assertFalse(manager.cancel(task.id()));
        } finally { release.countDown(); }
    }

    @Test void oldDatabaseAddsNullableReasonWithoutReclassifyingHistory(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("old.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runtime_tasks (id TEXT PRIMARY KEY, status TEXT NOT NULL,
                    prompt TEXT NOT NULL, result TEXT, error TEXT, created_at TEXT NOT NULL,
                    started_at TEXT, finished_at TEXT, updated_at TEXT, duration_ms INTEGER DEFAULT 0)
                    """);
            statement.execute("""
                    INSERT INTO runtime_tasks(id,status,prompt,result,created_at)
                    VALUES ('old','completed','original','历史中的失败文字也不重判','2026-10-01T00:00:00Z')
                    """);
        }
        for (int open = 0; open < 2; open++) {
            try (DurableTaskManager manager = new DurableTaskManager(db,
                    prompt -> ExecutionOutcome.completed("new"), 1)) {
                DurableTask old = manager.find("old").orElseThrow();
                assertEquals(TaskStatus.COMPLETED, old.status());
                assertNull(old.reason(), "Do not infer historical outcomes from stored prose");
                assertNull(old.cleanupState(), "Historical rows have no observed cleanup state");
                assertEquals("历史中的失败文字也不重判", old.result());
            }
        }
    }

    private static DurableTask waitForTerminal(DurableTaskManager manager, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            DurableTask task = manager.find(id).orElseThrow();
            if (task.terminal()) {
                return task;
            }
            Thread.sleep(20);
        }
        fail("task did not finish in time");
        return null;
    }

    private static void waitUntilStatus(DurableTaskManager manager, String id, TaskStatus status) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.find(id).orElseThrow().status() == status) {
                return;
            }
            Thread.sleep(20);
        }
        fail("task did not reach status " + status);
    }

    private static void markRunning(DurableTaskManager manager, String id) throws Exception {
        var field = DurableTaskManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        java.sql.Connection connection = (java.sql.Connection) field.get(manager);
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "UPDATE runtime_tasks SET status = 'running' WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
