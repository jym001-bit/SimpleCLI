package com.paicli.plan;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void sendsEvenOneStepGoalsToLlmAndAcceptsSingleTaskPlan() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "列出当前目录",
                  "tasks": [
                    {"id": "list", "description": "列出当前目录的文件", "type": "COMMAND", "dependencies": []}
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals(1, client.calls, "一步就能完成的目标也由模型规划，不在调用前按关键词短路");
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        assertEquals("列出当前目录的文件", plan.getTask("task_1").getDescription());
    }

    @Test
    void doesNotTreatShortKeywordGoalsAsSimpleWithoutPlanning() throws Exception {
        for (String goal : List.of("删除 src 下所有文件", "执行 mvn test，修复失败的测试", "查看 README")) {
            StubGLMClient client = new StubGLMClient("""
                    {"summary": "s", "tasks": [{"id": "t", "description": "d", "type": "ANALYSIS", "dependencies": []}]}
                    """);

            new Planner(client).createPlan(goal);

            assertEquals(1, client.calls, goal);
        }
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        planner.setProjectMemorySupplier(() -> "## PAI.md 项目记忆\n- 计划前必须读取项目规则");

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
        assertTrue(client.lastSystemPrompt.contains("计划前必须读取项目规则"));
    }

    private static final class StubGLMClient extends GLMClient {
        private final String content;
        private String lastSystemPrompt = "";
        private int calls;

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            this.lastSystemPrompt = messages.get(0).content();
            calls++;
            return new ChatResponse("assistant", content, null, 100, 20);
        }
    }
}
