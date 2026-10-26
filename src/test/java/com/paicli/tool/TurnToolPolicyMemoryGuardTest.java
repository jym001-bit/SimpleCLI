package com.paicli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.llm.LlmClient;
import com.paicli.memory.ExternalContextTracker;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnToolPolicyMemoryGuardTest {
    private String previousGuard;

    @BeforeEach
    void enableGuard() {
        previousGuard = System.getProperty(ExternalContextTracker.GUARD_PROPERTY);
        System.setProperty(ExternalContextTracker.GUARD_PROPERTY, "true");
    }

    @AfterEach
    void restoreGuard() {
        if (previousGuard == null) {
            System.clearProperty(ExternalContextTracker.GUARD_PROPERTY);
        } else {
            System.setProperty(ExternalContextTracker.GUARD_PROPERTY, previousGuard);
        }
    }

    @Test
    void saveMemoryIsRefusedAfterWebFetchWhenUserDidNotAskToRemember() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开并总结 https://example.com/article");
        FakeRegistry registry = new FakeRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        policy.execute(registry, List.of(fetch()), exposure);
        List<ToolExecutionResult> results = policy.execute(registry, List.of(saveMemory()), exposure);

        assertTrue(results.get(0).result().contains("[MEMORY_EXTERNAL_CONTEXT]"), results.get(0).result());
        assertEquals(List.of("web_fetch"), registry.executedNames());
        assertTrue(policy.hasConsumedExternalContent());
        assertEquals(List.of("web_fetch"), registry.getExternalContextTracker().sources());
    }

    @Test
    void explicitRememberRequestStillSavesAfterExternalContent() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput(
                "打开并总结 https://example.com/article 并记住这个站点需要登录");
        FakeRegistry registry = new FakeRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        policy.execute(registry, List.of(fetch()), exposure);
        List<ToolExecutionResult> results = policy.execute(registry, List.of(saveMemory()), exposure);

        assertEquals("ok", results.get(0).result());
        assertEquals(List.of("web_fetch", "save_memory"), registry.executedNames());
    }

    @Test
    void sessionLevelExternalContextCarriesIntoLaterTurns() {
        FakeRegistry registry = new FakeRegistry();
        registry.getExternalContextTracker().record("mcp__docs__search");
        TurnToolPolicy nextTurn = TurnToolPolicy.forExplicitTask("整理刚才的结论");

        List<ToolExecutionResult> results = nextTurn.execute(
                registry, List.of(saveMemory()), nextTurn.expose(definitions()));

        assertTrue(results.get(0).result().contains("[MEMORY_EXTERNAL_CONTEXT]"));
        assertTrue(registry.executedNames().isEmpty());
    }

    @Test
    void mcpToolResultsAndCurlCommandsMarkExternalContent() {
        FakeRegistry registry = new FakeRegistry();
        TurnToolPolicy policy = TurnToolPolicy.forExplicitTask("用文档服务搜索部署说明");
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        List<ToolExecutionResult> mcpResults = policy.execute(registry, List.of(
                new ToolInvocation("mcp", "mcp__docs__search", "{\"q\":\"deploy\"}")), exposure);
        assertEquals("ok", mcpResults.get(0).result());
        assertEquals(List.of("mcp__docs__search"), registry.getExternalContextTracker().sources());

        FakeRegistry commandRegistry = new FakeRegistry();
        TurnToolPolicy commandPolicy = TurnToolPolicy.fromUserInput("运行 curl http://localhost:8080/health");
        commandPolicy.execute(commandRegistry, List.of(new ToolInvocation("cmd", "execute_command",
                "{\"command\":\"curl http://localhost:8080/health\"}")), commandPolicy.expose(definitions()));
        assertEquals(List.of("execute_command(curl/wget)"), commandRegistry.getExternalContextTracker().sources());
    }

    @Test
    void localToolsDoNotBlockSaveMemory() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("读取 README.md 并总结");
        FakeRegistry registry = new FakeRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        policy.execute(registry, List.of(new ToolInvocation("read", "read_file", "{\"path\":\"README.md\"}")),
                exposure);
        List<ToolExecutionResult> results = policy.execute(registry, List.of(saveMemory()), exposure);

        assertEquals("ok", results.get(0).result());
        assertFalse(policy.hasConsumedExternalContent());
        assertFalse(registry.getExternalContextTracker().hasExternalContext());
    }

    @Test
    void guardCanBeDisabledByConfiguration() {
        System.setProperty(ExternalContextTracker.GUARD_PROPERTY, "false");
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开并总结 https://example.com/article");
        FakeRegistry registry = new FakeRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        policy.execute(registry, List.of(fetch()), exposure);
        List<ToolExecutionResult> results = policy.execute(registry, List.of(saveMemory()), exposure);

        assertEquals("ok", results.get(0).result());
        assertTrue(registry.getExternalContextTracker().hasExternalContext(),
                "the marker is still recorded for metadata even when the guard is off");
    }

    private static ToolInvocation fetch() {
        return new ToolInvocation("fetch", "web_fetch", "{\"url\":\"https://example.com/article\"}");
    }

    private static ToolInvocation saveMemory() {
        return new ToolInvocation("save", "save_memory", "{\"fact\":\"example.com 需要登录\"}");
    }

    private static List<LlmClient.Tool> definitions() {
        return List.of(tool("read_file"), tool("execute_command"), tool("web_search"), tool("web_fetch"),
                tool("save_memory"), tool("mcp__docs__search"));
    }

    private static LlmClient.Tool tool(String name) {
        return new LlmClient.Tool(name, name, JsonNodeFactory.instance.objectNode());
    }

    private static final class FakeRegistry extends ToolRegistry {
        private final List<ToolInvocation> executed = new ArrayList<>();

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            executed.addAll(invocations);
            return invocations.stream()
                    .map(invocation -> new ToolExecutionResult(invocation.id(), invocation.name(),
                            invocation.argumentsJson(), "ok", 0, false, List.of()))
                    .toList();
        }

        private List<String> executedNames() {
            return executed.stream().map(ToolInvocation::name).toList();
        }
    }
}
