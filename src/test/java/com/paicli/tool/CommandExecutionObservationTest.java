package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandExecutionObservationTest {
    @TempDir Path temp;
    private final List<CommandExecutionObserver.Event> events = new CopyOnWriteArrayList<>();

    private ToolRegistry registry(long timeout) {
        var registry = new ToolRegistry(timeout);
        registry.setProjectPath(temp.toString());
        registry.setSanitizeCommandEnvironment(true);
        registry.setCommandExecutionObserver(events::add);
        return registry;
    }

    private ToolOutput run(ToolRegistry registry, String command) throws Exception {
        return registry.executeToolOutput("execute_command", new ObjectMapper().writeValueAsString(Map.of("command", command)));
    }

    @Test void actualStartAndExitBindTheReturnedResult() throws Exception {
        var registry = registry(5); var result = run(registry, "  printf '中文 diagnostic'  ");
        assertTrue(result.successful()); assertEquals(2, events.size());
        var start = events.get(0); var end = events.get(1);
        assertEquals(CommandExecutionObserver.Phase.STARTED, start.phase());
        assertEquals(CommandExecutionObserver.Outcome.NONE, start.outcome());
        assertEquals(List.of("bash", "-c", "printf '中文 diagnostic'"), start.arguments());
        assertTrue(start.processId() > 0); assertEquals(temp.toString(), start.workingDirectory());
        assertEquals(start.invocationId(), end.invocationId()); assertEquals(start.processId(), end.processId());
        assertEquals(start.arguments(), end.arguments()); assertEquals(0, end.exitCode());
        assertEquals(CommandExecutionObserver.Phase.FINISHED, end.phase());
        assertEquals(CommandExecutionObserver.Outcome.EXITED, end.outcome());
        assertEquals(result.text().length(), end.resultChars());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.text().getBytes(StandardCharsets.UTF_8))), end.resultSha256());
        assertEquals(0, registry.getCommandObservationFailures());
    }

    @Test void nativePolicyRejectionNeverClaimsAProcessStart() throws Exception {
        var registry = registry(5); var result = run(registry, "sudo true");
        assertFalse(result.successful()); assertTrue(result.text().contains("策略拒绝"));
        assertEquals(1, events.size()); var event = events.get(0);
        assertEquals(CommandExecutionObserver.Phase.REJECTED, event.phase());
        assertEquals(CommandExecutionObserver.Outcome.POLICY_DENIED, event.outcome());
        assertEquals(0, event.processId()); assertEquals(List.of(), event.arguments());
    }

    @Test void emptyCommandIsNotAnExecutedFailure() throws Exception {
        assertFalse(run(registry(5), "  ").successful()); assertEquals(1, events.size());
        assertEquals(CommandExecutionObserver.Outcome.EMPTY_COMMAND, events.get(0).outcome());
        assertEquals(0, events.get(0).processId());
    }

    @Test void nonzeroExitIsStillAnActualExecution() throws Exception {
        assertFalse(run(registry(5), "exit 7").successful()); assertEquals(2, events.size());
        assertEquals(CommandExecutionObserver.Outcome.EXITED, events.get(1).outcome());
        assertEquals(7, events.get(1).exitCode());
    }

    @Test void timeoutHasItsOwnTerminalDisposition() throws Exception {
        assertFalse(run(registry(1), "exec sleep 10").successful()); assertEquals(2, events.size());
        assertEquals(CommandExecutionObserver.Outcome.TIMED_OUT, events.get(1).outcome());
    }

    @Test void startFailureDoesNotInventAPid() throws Exception {
        var registry = registry(5);
        registry.setProjectPath(temp.resolve("missing-working-directory").toString());
        assertFalse(run(registry, "true").successful()); assertEquals(1, events.size());
        assertEquals(CommandExecutionObserver.Phase.FINISHED, events.get(0).phase());
        assertEquals(CommandExecutionObserver.Outcome.FAILED, events.get(0).outcome());
        assertEquals(0, events.get(0).processId()); assertNull(events.get(0).exitCode());
    }

    @Test void observerFailureAndDisableDoNotChangeProductOutput() throws Exception {
        var registry = registry(5);
        registry.setCommandExecutionObserver(event -> { throw new IllegalStateException("diagnostic sink failed"); });
        assertTrue(run(registry, "printf ok").successful());
        assertEquals(2, registry.getCommandObservationFailures());
        registry.setCommandExecutionObserver(null);
        assertTrue(run(registry, "printf ok").successful());
        assertEquals(2, registry.getCommandObservationFailures());
    }

    @Test void concurrentCommandsHaveInvocationIdsIndependentOfRepeatedCallIds() throws Exception {
        var registry = registry(5);
        var calls = new ArrayList<ToolRegistry.ToolInvocation>();
        for (int i = 0; i < 4; i++) calls.add(new ToolRegistry.ToolInvocation("same-id", "execute_command", "{\"command\":\"printf ok\"}"));
        assertTrue(registry.executeTools(calls).stream().allMatch(ToolRegistry.ToolExecutionResult::successful));
        assertEquals(8, events.size());
        assertEquals(4, events.stream().map(CommandExecutionObserver.Event::invocationId).distinct().count());
        for (long id : events.stream().map(CommandExecutionObserver.Event::invocationId).distinct().toList())
            assertEquals(List.of(CommandExecutionObserver.Phase.STARTED, CommandExecutionObserver.Phase.FINISHED),
                    events.stream().filter(e -> e.invocationId() == id).map(CommandExecutionObserver.Event::phase).toList());
    }

    @Test void assertionErrorInStartedObserverCannotAbandonTheProcess() throws Exception {
        var registry = registry(5);
        registry.setCommandExecutionObserver(event -> { events.add(event); throw new AssertionError("broken diagnostic assertion"); });
        assertTrue(run(registry, "printf ok").successful());
        assertEquals(2, registry.getCommandObservationFailures());
        assertEquals(List.of(CommandExecutionObserver.Phase.STARTED, CommandExecutionObserver.Phase.FINISHED),
                events.stream().map(CommandExecutionObserver.Event::phase).toList());
        assertEquals(CommandExecutionObserver.Outcome.EXITED, events.get(1).outcome());
        assertEquals(0, events.get(1).exitCode());
    }

    @Test void interruptIsNotMisreportedAsNormalExit() throws Exception {
        var registry = registry(30);
        var started = new CountDownLatch(1);
        registry.setCommandExecutionObserver(event -> { events.add(event); if (event.phase() == CommandExecutionObserver.Phase.STARTED) started.countDown(); });
        var result = new java.util.concurrent.atomic.AtomicReference<ToolOutput>();
        Thread thread = new Thread(() -> {
            try { result.set(run(registry, "exec sleep 30")); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        thread.start(); assertTrue(started.await(5, TimeUnit.SECONDS)); thread.interrupt(); thread.join(5000);
        assertFalse(thread.isAlive()); assertNotNull(result.get()); assertFalse(result.get().successful());
        assertEquals(2, events.size());
        assertEquals(CommandExecutionObserver.Outcome.INTERRUPTED, events.get(1).outcome());
    }
}
