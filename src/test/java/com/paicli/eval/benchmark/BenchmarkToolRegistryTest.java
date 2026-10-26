package com.paicli.eval.benchmark;

import com.paicli.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkToolRegistryTest {
    @Test
    void reasoningOnlyExposesNoToolsAndFailsClosedOnExecution() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.REASONING_ONLY);

        assertTrue(registry.getToolDefinitions().isEmpty());
        ToolOutput denied = registry.executeToolOutput("read_file", "{\"path\":\"README.md\"}");
        assertFalse(denied.successful());
        assertTrue(denied.text().contains("benchmark tool policy denied"));
        assertFalse(BenchmarkToolProfile.REASONING_ONLY.commandSandboxRequired());
    }

    @Test
    void readOnlyExposesExplorationButNoMutationTools() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.READ_ONLY);
        Set<String> exposed = registry.getToolDefinitions().stream()
                .map(tool -> tool.name())
                .collect(Collectors.toSet());

        assertEquals(BenchmarkToolProfile.READ_ONLY.allowedTools(), exposed);
        assertTrue(exposed.contains("read_file"));
        assertFalse(exposed.contains("write_file"));
        assertFalse(exposed.contains("create_project"));
        assertFalse(exposed.contains("execute_command"));
        assertFalse(registry.executeToolOutput(
                "write_file", "{\"path\":\"blocked.txt\",\"content\":\"x\"}").successful());
        assertFalse(BenchmarkToolProfile.READ_ONLY.commandSandboxRequired());
    }

    @Test
    void codeRagAddsOnlySemanticSearchToTheReadOnlySurface() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.CODE_RAG);
        Set<String> exposed = registry.getToolDefinitions().stream()
                .map(tool -> tool.name())
                .collect(Collectors.toSet());

        assertEquals(BenchmarkToolProfile.CODE_RAG.allowedTools(), exposed);
        assertTrue(exposed.containsAll(BenchmarkToolProfile.READ_ONLY.allowedTools()));
        assertTrue(exposed.contains("search_code"));
        assertFalse(exposed.contains("write_file"));
        assertFalse(exposed.contains("execute_command"));
        assertFalse(BenchmarkToolProfile.CODE_RAG.commandSandboxRequired());
        ToolOutput unavailable = registry.executeToolOutput(
                "search_code", "{\"query\":\"trial settlement\"}");
        assertFalse(unavailable.successful());
        assertTrue(unavailable.text().contains("frozen semantic index is unavailable"));

        registry.setFrozenCodeSearch(arguments -> ToolOutput.text("ranked frozen result"));
        ToolOutput frozen = registry.executeToolOutput(
                "search_code", "{\"query\":\"trial settlement\"}");
        assertTrue(frozen.successful());
        assertEquals("ranked frozen result", frozen.text());
    }

    @Test
    void fileOnlyIsDefaultAndDoesNotExposeExecuteCommand() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry();

        Set<String> exposed = registry.getToolDefinitions().stream()
                .map(tool -> tool.name())
                .collect(Collectors.toSet());

        assertEquals(BenchmarkToolProfile.FILE_ONLY.allowedTools(), exposed);
        assertEquals(BenchmarkToolProfile.FILE_ONLY, registry.profile());
        assertFalse(exposed.contains("execute_command"));
        assertFalse(exposed.contains("web_search"));
        assertFalse(exposed.contains("web_fetch"));
        assertFalse(exposed.contains("save_memory"));
        assertFalse(exposed.contains("load_skill"));
        assertFalse(exposed.contains("revert_turn"));
        assertTrue(registry.promptPolicy().contains("Frozen profile: FILE_ONLY"));
        assertFalse(registry.promptPolicy().contains("execute_command"));
    }

    @Test
    void localCommandAddsOnlyExecuteCommandToFrozenFileSurface() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.LOCAL_COMMAND);

        Set<String> exposed = registry.getToolDefinitions().stream()
                .map(tool -> tool.name())
                .collect(Collectors.toSet());

        assertEquals(BenchmarkToolProfile.LOCAL_COMMAND.allowedTools(), exposed);
        assertTrue(exposed.contains("execute_command"));
        assertFalse(exposed.contains("web_search"));
        assertTrue(registry.promptPolicy().contains("Frozen profile: LOCAL_COMMAND"));
        assertTrue(registry.promptPolicy().contains("execute_command"));
        assertFalse(BenchmarkToolProfile.FILE_ONLY.commandSandboxRequired());
        assertTrue(BenchmarkToolProfile.LOCAL_COMMAND.commandSandboxRequired());
    }

    @Test
    void alsoDeniesNonAllowlistedToolsAtExecutionBoundary() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry();

        ToolOutput denied = registry.executeToolOutput("web_search", "{\"query\":\"example\"}");

        assertFalse(denied.successful());
        assertTrue(denied.text().contains("benchmark tool policy denied"));
    }

    @Test
    void fileOnlyDeniesExecuteCommandAtExecutionBoundary() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.FILE_ONLY);

        ToolOutput denied = registry.executeToolOutput("execute_command", "{\"command\":\"pwd\"}");

        assertFalse(denied.successful());
        assertTrue(denied.text().contains("benchmark tool policy denied"));
    }

    @Test
    void executionObserverReceivesTrustedResultsInInvocationOrder() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.REASONING_ONLY);
        List<com.paicli.tool.ToolRegistry.ToolExecutionResult> observed = new ArrayList<>();
        registry.setExecutionObserver(observed::add);

        var policy = com.paicli.tool.TurnToolPolicy.forExplicitTask("Read local files only", false, false);
        List<com.paicli.tool.ToolRegistry.ToolExecutionResult> returned = policy.execute(registry, List.of(
                new com.paicli.tool.ToolRegistry.ToolInvocation(
                        "call-2", "write_file", "{\"path\":\"blocked.txt\",\"content\":\"x\"}"),
                new com.paicli.tool.ToolRegistry.ToolInvocation(
                        "call-1", "read_file", "{\"path\":\"README.md\"}")), policy.expose(registry.getToolDefinitions()));

        assertEquals(returned, observed);
        assertEquals(List.of("call-2", "call-1"),
                observed.stream().map(result -> result.id()).toList());
        assertEquals(List.of("write_file", "read_file"),
                observed.stream().map(result -> result.name()).toList());
        assertEquals("{\"path\":\"blocked.txt\",\"content\":\"x\"}",
                observed.get(0).argumentsJson());
        assertTrue(observed.stream().noneMatch(result -> result.successful()));
        assertTrue(observed.stream().allMatch(
                result -> result.result().contains("TOOL_NOT_ADVERTISED")));
    }

    @Test
    void policyObserverIncludesDeniedAndExecutedCallsExactlyOnceInOriginalOrder(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        java.nio.file.Files.writeString(workspace.resolve("input.txt"), "known control");
        var registry = new BenchmarkToolRegistry(BenchmarkToolProfile.READ_ONLY);
        registry.setProjectPath(workspace.toString());
        List<com.paicli.tool.ToolRegistry.ToolExecutionResult> observed = new ArrayList<>();
        registry.setExecutionObserver(observed::add);
        var policy = com.paicli.tool.TurnToolPolicy.forExplicitTask("Read local files only", false, false);
        var calls = List.of(
                new com.paicli.tool.ToolRegistry.ToolInvocation("deny-first", "write_file", "{\"path\":\"blocked.txt\",\"content\":\"x\"}"),
                new com.paicli.tool.ToolRegistry.ToolInvocation("read", "read_file", "{\"path\":\"input.txt\"}"),
                new com.paicli.tool.ToolRegistry.ToolInvocation("deny-middle", "mcp__calendar__approve", "{}"),
                new com.paicli.tool.ToolRegistry.ToolInvocation("list", "list_dir", "{\"path\":\".\"}"));
        var returned = policy.execute(registry, calls, policy.expose(registry.getToolDefinitions()));
        assertEquals(returned, observed);
        assertEquals(calls.stream().map(c -> c.id()).toList(), observed.stream().map(r -> r.id()).toList());
        assertEquals(List.of(false, true, false, true), observed.stream().map(r -> r.successful()).toList());
        assertFalse(java.nio.file.Files.exists(workspace.resolve("blocked.txt")));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> returned.clear());
    }

    @Test
    void nullExecutionObserverRestoresNoOpObserver() {
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.REASONING_ONLY);
        registry.setExecutionObserver(null);

        var policy = com.paicli.tool.TurnToolPolicy.forExplicitTask("Read local files only", false, false);
        List<com.paicli.tool.ToolRegistry.ToolExecutionResult> results = policy.execute(registry, List.of(
                new com.paicli.tool.ToolRegistry.ToolInvocation(
                        "call-1", "read_file", "{\"path\":\"README.md\"}")), policy.expose(registry.getToolDefinitions()));

        assertEquals(1, results.size());
        assertFalse(results.get(0).successful());
    }
}
