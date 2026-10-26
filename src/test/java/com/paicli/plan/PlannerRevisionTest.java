package com.paicli.plan;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerRevisionTest {

    @Test
    void supplementRevisesTheReviewedPlanInsteadOfStartingOver() throws Exception {
        List<String> requests = new ArrayList<>();
        Planner planner = new Planner(new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                requests.add(String.valueOf(messages.get(messages.size() - 1).content()));
                return new ChatResponse("assistant", """
                        {"summary":"改后","tasks":[
                          {"id":"a","description":"读取 pom.xml","type":"FILE_READ","dependencies":[]},
                          {"id":"b","description":"用中文输出报告","type":"ANALYSIS","dependencies":["a"]}
                        ]}""", null, 1, 1);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                return chat(messages, tools);
            }
            public String getModelName() { return "scripted"; }
            public String getProviderName() { return "offline"; }
        }, new PrintStream(OutputStream.nullOutputStream()));
        planner.setProjectMemorySupplier(() -> "");

        ExecutionPlan current = new ExecutionPlan("plan-1", "列出当前目录");
        current.setSummary("直接执行");
        current.addTask(new Task("task_1", "列出当前目录", Task.TaskType.COMMAND));
        current.computeExecutionOrder();

        ExecutionPlan revised = planner.revisePlan(current, "再读一下 pom.xml，用中文输出报告");

        assertEquals(1, requests.size(), "补充要求一定交给模型修改计划");
        String request = requests.get(0);
        assertTrue(request.startsWith("请为以下任务制定执行计划：\n列出当前目录\n补充要求：再读一下 pom.xml"), request);
        assertTrue(request.contains("\"description\" : \"列出当前目录\""), "要把当前计划交给模型修改: " + request);
        assertEquals("列出当前目录\n补充要求：再读一下 pom.xml，用中文输出报告", revised.getGoal());
        assertEquals(List.of("task_1"), revised.getTask("task_2").getDependencies());
    }
}
