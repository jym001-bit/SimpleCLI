package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class CommandCancellationTest {
    @TempDir Path temp;

    @Test void tokenCancellationStopsActualTreeRetainsOutputAndDoesNotCancelAnotherRun() throws Exception {
        var events = new CopyOnWriteArrayList<CommandExecutionObserver.Event>();
        ToolRegistry registry = new ToolRegistry(30);
        registry.setProjectPath(temp.toString());
        registry.setCommandExecutionObserver(events::add);
        var tokens = List.of(new CompletableFuture<CancellationToken>(), new CompletableFuture<CancellationToken>());
        var results = List.of(new CompletableFuture<List<ToolRegistry.ToolExecutionResult>>(),
                new CompletableFuture<List<ToolRegistry.ToolExecutionResult>>());
        var workers = new java.util.ArrayList<Thread>();
        Path forbidden = temp.resolve("must-not-be-created.txt");
        try {
            for (int i = 0; i < 2; i++) {
                int n = i;
                Thread worker = new Thread(() -> {
                    CancellationToken token = CancellationContext.startRun();
                    tokens.get(n).complete(token);
                    try {
                        String args = new ObjectMapper().writeValueAsString(Map.of("command",
                                CommandCancellationFixture.command(temp.resolve("ready" + n))));
                        results.get(n).complete(registry.executeTools(List.of(
                                new ToolRegistry.ToolInvocation("command", "execute_command", args),
                                new ToolRegistry.ToolInvocation("later", "write_file",
                                        "{\"path\":\"must-not-be-created.txt\",\"content\":\"unexpected\"}"))));
                    } catch (Throwable e) { results.get(n).completeExceptionally(e); }
                    finally { CancellationContext.clear(token); }
                });
                workers.add(worker);
                worker.start();
            }
            CommandCancellationFixture.awaitReady(temp.resolve("ready0"));
            CommandCancellationFixture.awaitReady(temp.resolve("ready1"));
            long leaf = Long.parseLong(Files.readString(temp.resolve("ready0")));
            long independentLeaf = Long.parseLong(Files.readString(temp.resolve("ready1")));
            var target = events.stream().filter(e -> e.phase() == CommandExecutionObserver.Phase.STARTED
                    && e.command().contains(temp.resolve("ready0").toString())).findFirst().orElseThrow();
            var descendants = ProcessHandle.of(target.processId()).orElseThrow().descendants().toList();
            assertTrue(descendants.size() >= 2, "Fixture must exercise shell -> JVM -> JVM");
            tokens.get(0).get(2, TimeUnit.SECONDS).cancel();
            var cancelled = results.get(0).get(8, TimeUnit.SECONDS);
            assertFalse(cancelled.get(0).successful());
            assertTrue(cancelled.get(0).result().contains("ACTUAL_PARTIAL_OUTPUT"), cancelled.get(0).result());
            assertFalse(cancelled.get(1).successful());
            assertFalse(Files.exists(forbidden));
            assertFalse(ProcessHandle.of(leaf).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive), "All captured descendants must exit");
            assertTrue(ProcessHandle.of(independentLeaf).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(results.get(1).isDone(), "Independent run must still be running");
            var end = events.stream().filter(e -> e.outcome() == CommandExecutionObserver.Outcome.CANCELLED).findFirst().orElseThrow();
            assertFalse(ProcessHandle.of(end.processId()).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(2, events.stream().filter(e -> e.phase() == CommandExecutionObserver.Phase.STARTED).count());
            tokens.get(1).get().cancel();
            assertFalse(results.get(1).get(8, TimeUnit.SECONDS).get(0).successful());
            assertFalse(Files.exists(forbidden));
            assertFalse(ProcessHandle.of(independentLeaf).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(t -> t.isAlive() && t.getName().equals("paicli-command-output")),
                    "Output readers must have terminated before tool results are returned");
        } finally {
            for (var token : tokens) if (token.isDone()) token.get().cancel();
            for (var worker : workers) { worker.join(8000); assertFalse(worker.isAlive()); }
        }
    }

    @Test void cancellationAtProcessStartIsNotLostBeforeOutputReaderStarts() throws Exception {
        CancellationToken token = CancellationContext.startRun();
        try {
            ToolRegistry registry = new ToolRegistry(30);
            registry.setProjectPath(temp.toString());
            var events = new CopyOnWriteArrayList<CommandExecutionObserver.Event>();
            registry.setCommandExecutionObserver(event -> {
                events.add(event);
                if (event.phase() == CommandExecutionObserver.Phase.STARTED) token.cancel();
            });
            var result = registry.executeToolOutput("execute_command", new ObjectMapper().writeValueAsString(
                    Map.of("command", CommandCancellationFixture.command(temp.resolve("ready")))));
            assertFalse(result.successful());
            assertEquals(CommandExecutionObserver.Outcome.CANCELLED, events.get(1).outcome());
            assertFalse(ProcessHandle.of(events.get(0).processId()).map(ProcessHandle::isAlive).orElse(false));
        } finally { CancellationContext.clear(token); }
    }
}
