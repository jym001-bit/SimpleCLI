package com.paicli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamPlanParserTest {

    @Test
    void renumbersStepsAndMapsDependencies() throws IOException {
        List<AgentOrchestrator.ExecutionStep> steps = TeamPlanParser.parse("""
                {"summary": "s", "steps": [
                  {"id": "code", "description": "CODE", "type": "FILE_WRITE", "dependencies": []},
                  {"id": "test", "description": "TEST", "type": "FILE_WRITE"},
                  {"id": "doc", "description": "DOC", "dependencies": ["code", "test", "code"]}
                ]}
                """);

        assertEquals(List.of("step_1", "step_2", "step_3"),
                steps.stream().map(AgentOrchestrator.ExecutionStep::id).toList());
        assertEquals(List.of(), steps.get(1).dependencies());
        assertEquals(List.of("step_1", "step_2"), steps.get(2).dependencies());
        assertEquals("COMMAND", steps.get(2).type());
    }

    @Test
    void returnedListIsMutableForInPlaceStatusUpdates() throws IOException {
        List<AgentOrchestrator.ExecutionStep> steps = TeamPlanParser.parse(
                "{\"steps\": [{\"id\": \"a\", \"description\": \"A\"}]}");

        steps.set(0, steps.get(0).withResult("done"));

        assertEquals(AgentOrchestrator.StepStatus.COMPLETED, steps.get(0).status());
    }

    @Test
    void keepsFencesInsideDescriptions() throws IOException {
        List<AgentOrchestrator.ExecutionStep> steps = TeamPlanParser.parse(
                "```json\n{\"steps\": [{\"id\": \"a\", \"description\": \"输出 ```bash 代码块\"}]}\n```");

        assertEquals("输出 ```bash 代码块", steps.get(0).description());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "{}",
            "{\"steps\": []}",
            "{\"steps\": [{\"description\": \"no id\"}]}",
            "{\"steps\": [{\"id\": \" \"}]}",
            "{\"steps\": [{\"id\": 1}]}",
            "{\"steps\": [{\"id\": \"a\"}, {\"id\": \"a\"}]}",
            "{\"steps\": [{\"id\": \"a\", \"dependencies\": [\"missing\"]}]}",
            "{\"steps\": [{\"id\": \"a\", \"dependencies\": \"b\"}, {\"id\": \"b\"}]}",
            "{\"steps\": [{\"id\": \"a\", \"dependencies\": [\"a\"]}]}",
            "{\"steps\": [{\"id\": \"a\", \"dependencies\": [\"b\"]}, {\"id\": \"b\", \"dependencies\": [\"a\"]}]}",
            "{\"steps\": [{\"id\": \"a\", \"description\": 42}]}",
            "{\"steps\": [{\"id\": \"a\"}]} 以上是计划",
            "计划如下：{\"steps\": [{\"id\": \"a\"}]}"
    })
    void rejectsInvalidPlans(String reply) {
        assertThrows(IOException.class, () -> TeamPlanParser.parse(reply));
    }
}
