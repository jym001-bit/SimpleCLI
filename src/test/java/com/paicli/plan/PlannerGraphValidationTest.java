package com.paicli.plan;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlannerGraphValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{}", "{\"tasks\":[]}", "{\"tasks\":{\"id\":\"a\"}}",
            "{\"tasks\":[{\"description\":\"a\"}]}",
            "{\"tasks\":[{\"id\":1}]}",
            "{\"tasks\":[{\"id\":\"  \"}]}",
            "{\"tasks\":[{\"id\":\"a\"},{\"id\":\"a\"}]}",
            "{\"tasks\":[{\"id\":\"a\",\"dependencies\":[\"missing\"]}]}",
            "{\"tasks\":[{\"id\":\"a\"},{\"id\":\"b\",\"dependencies\":[\"task_1\"]}]}",
            "{\"tasks\":[{\"id\":\"a\",\"dependencies\":\"b\"},{\"id\":\"b\"}]}",
            "{\"tasks\":[{\"id\":\"1\"},{\"id\":\"b\",\"dependencies\":[1]}]}",
            "{\"tasks\":[{\"id\":\"a\",\"dependencies\":[null]}]}",
            "{\"tasks\":[{\"id\":\"a\",\"dependencies\":[\"a\"]}]}",
            "{\"tasks\":[{\"id\":\"a\",\"dependencies\":[\"b\"]},{\"id\":\"b\",\"dependencies\":[\"a\"]}]}",
            "{\"tasks\":[{\"id\":\"a\"}]}\n以上是计划说明",
            "{\"tasks\":[{\"id\":\"a\"}],\"tasks\":[{\"id\":\"b\"}]}"
    })
    void rejectsMalformedGraphsInsteadOfSilentlyChangingTheirDependencies(String json) {
        assertThrows(IOException.class, () -> planner(json).createPlan("先分析两个任务，再合并结果"));
    }

    @Test
    void normalizesForwardReferencesWithoutDroppingOrAddingEdges() throws Exception {
        var plan = planner("""
                {"tasks":[
                  {"id":"merge","dependencies":["left","right"]},
                  {"id":"left","dependencies":[]},
                  {"id":"right"}
                ]}
                """).createPlan("先分析两个任务，再合并结果");
        assertEquals(List.of("task_2", "task_3"), plan.getTask("task_1").getDependencies());
        assertTrue(plan.getTask("task_2").getDependencies().isEmpty());
        assertTrue(plan.getTask("task_3").getDependencies().isEmpty());
        assertEquals(List.of("task_2", "task_3", "task_1"), plan.getExecutionOrder());
        assertEquals("", plan.getTask("task_1").getDescription(), "omitted description remains compatible");
    }

    @ParameterizedTest @ValueSource(strings={"null", "123", "1e23", "true", "{}", "[]"})
    void rejectsPresentNonTextDescriptionBeforeExecutingAnyTask(String description) {
        assertThrows(IOException.class, () -> planner("{\"tasks\":[{\"id\":\"a\",\"description\":" + description + "}]}")
                .createPlan("先分析两个任务，再合并结果"));
    }

    @Test void nonbreakingSpaceIsNotJavaBlankAndRemainsAValidOriginalId() throws Exception {
        var plan = planner("{\"tasks\":[{\"id\":\"\u00a0\",\"description\":\"first\"},{\"id\":\"last\",\"dependencies\":[\"\u00a0\"]}]}")
                .createPlan("先分析两个任务，再合并结果");
        assertEquals(List.of("task_1"), plan.getTask("task_2").getDependencies());
    }

    private static Planner planner(String response) {
        var planner = new Planner(new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return new ChatResponse("assistant", response, null, 1, 1);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                return chat(messages, tools);
            }
            public String getModelName() { return "scripted-control"; }
            public String getProviderName() { return "offline-control"; }
        }, new PrintStream(OutputStream.nullOutputStream()));
        planner.setProjectMemorySupplier(() -> "");
        return planner;
    }
}
