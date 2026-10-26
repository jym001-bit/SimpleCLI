package com.paicli.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.prompt.PromptAssembler;
import com.paicli.prompt.PromptContext;
import com.paicli.prompt.PromptMode;
import com.paicli.prompt.ProjectMemoryLoader;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * 规划器 - 使用LLM将复杂任务分解为执行计划
 */
public class Planner {
    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final LlmClient llmClient;
    private final PrintStream out;
    // 与评测重放器的 json.loads 保持一致：JSON 后面跟着说明文字、键重复，都算不合法
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private Supplier<String> projectMemorySupplier = () ->
            ProjectMemoryLoader.createDefault(Path.of(".").toAbsolutePath().normalize()).loadForPrompt();

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    public void setProjectMemorySupplier(Supplier<String> projectMemorySupplier) {
        this.projectMemorySupplier = projectMemorySupplier == null ? () -> "" : projectMemorySupplier;
    }

    public void setConversationLedger(ConversationLedger conversationLedger) {
        this.conversationLedger = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
    }

    /**
     * 为任务创建执行计划。所有目标都交给模型规划，不在调用模型前用关键词判断任务是否“简单”：
     * 关键词判断会把“删除 src 下所有文件”“执行 mvn test，修复失败的测试”这类输入误判成单步任务，
     * 一步就能完成的目标由规划提示词约束模型只给出最短计划。
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        out.println("📋 正在规划任务: " + goal + "\n");
        return requestPlan(goal, "请为以下任务制定执行计划：\n" + goal);
    }

    /**
     * 审阅时用户按 I 补充要求：把当前计划和补充要求一起交给模型修改，
     * 不受补充要求影响的任务保持原样，修改结果仍走同一套严格校验。
     */
    public ExecutionPlan revisePlan(ExecutionPlan current, String feedback) throws IOException {
        String revisedGoal = current.getGoal() + "\n补充要求：" + feedback;
        out.println("📋 正在按补充要求修改计划: " + feedback + "\n");
        String request = "请为以下任务制定执行计划：\n" + revisedGoal
                + "\n\n下面是用户刚审阅过的当前计划。请在它的基础上按补充要求修改，"
                + "不受补充要求影响的任务保持原样，输出修改后的完整计划：\n"
                + toPlanJson(current);
        return requestPlan(revisedGoal, request);
    }

    private ExecutionPlan requestPlan(String goal, String planningRequest) throws IOException {
        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(promptAssembler.assemble(PromptMode.PLANNER, PromptContext.builder()
                        .projectMemoryContext(buildProjectMemoryContext())
                        .build())),
                LlmClient.Message.user(planningRequest)
        );
        conversationLedger.appendMessage("plan", "planner", "system_prompt", messages.get(0));
        conversationLedger.appendMessage("plan", "planner", "planning_request", messages.get(1));

        // 调用LLM生成计划
        PlanningStreamRenderer streamRenderer = new PlanningStreamRenderer(out);
        LlmClient.ChatResponse response = llmClient.chat(messages, null, streamRenderer);
        conversationLedger.appendMessage(
                "plan",
                "planner",
                "llm_response",
                LlmClient.Message.assistant(response.reasoningContent(), response.content()));
        LlmTraceLogger.logReasoning(log, "planner", llmClient, response.reasoningContent());
        streamRenderer.finish();
        String planJson = response.content();

        // 解析JSON计划
        return parsePlan(goal, planJson);
    }

    private String buildProjectMemoryContext() {
        try {
            String context = projectMemorySupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to load PAI.md project memory for planner", e);
            return "";
        }
    }

    /**
     * 解析LLM生成的计划JSON
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        if (planJson == null || planJson.isBlank()) {
            throw new IOException("计划必须包含非空 JSON 对象");
        }
        // 清理可能的markdown代码块
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new IOException("计划不是合法的 JSON：" + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("计划必须是 JSON 对象");
        }
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IOException("计划 tasks 必须是非空数组");
        }

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // 第一遍：创建所有任务（不处理依赖，因为可能有前向引用）
        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            JsonNode idNode = taskNode.path("id");
            if (!taskNode.isObject() || !idNode.isTextual() || idNode.asText().isBlank()) {
                throw new IOException("计划任务必须具有非空字符串 id");
            }
            String originalId = idNode.asText();
            String newId = "task_" + taskIndex++;
            if (idMapping.putIfAbsent(originalId, newId) != null) {
                throw new IOException("计划中存在重复任务 id");
            }

            JsonNode descriptionNode = taskNode.path("description");
            if (!descriptionNode.isMissingNode() && !descriptionNode.isTextual()) {
                throw new IOException("计划任务 description 必须是字符串");
            }
            String description = descriptionNode.asText();
            String typeStr = taskNode.path("type").asText();
            Task.TaskType type = parseTaskType(typeStr);

            plan.addTask(new Task(newId, description, type));
        }

        // 第二遍：建立依赖和被依赖关系
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (!depsNode.isMissingNode() && !depsNode.isArray()) {
                throw new IOException("计划 dependencies 必须是任务 id 数组");
            }
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    if (!depNode.isTextual() || !idMapping.containsKey(depNode.asText())) {
                        // Never silently remove an edge or resolve a guessed normalized alias.
                        throw new IOException("计划依赖必须引用已声明的任务 id");
                    }
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.get(originalDepId);
                    Task dep = plan.getTask(newDepId);
                    task.addDependency(newDepId);
                    dep.addDependent(task.getId());
                }
            }
        }

        // 计算执行顺序
        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    private String toPlanJson(ExecutionPlan plan) {
        ObjectNode root = mapper.createObjectNode();
        root.put("summary", plan.getSummary() == null ? "" : plan.getSummary());
        ArrayNode tasks = root.putArray("tasks");
        for (String taskId : plan.getExecutionOrder()) {
            Task task = plan.getTask(taskId);
            ObjectNode node = tasks.addObject();
            node.put("id", task.getId());
            node.put("description", task.getDescription());
            node.put("type", task.getType().name());
            ArrayNode deps = node.putArray("dependencies");
            task.getDependencies().forEach(deps::add);
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("计划序列化失败", e);
        }
    }

    /**
     * 解析任务类型
     */
    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    /**
     * 生成计划ID
     */
    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 根据执行结果重新规划
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        out.println("🔄 重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");

        return createPlan(context.toString());
    }

    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        private PlanningStreamRenderer(PrintStream out) {
            this.out = out == null ? System.out : out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                out.println("\n");
            }
        }
    }
}
