package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.RootTaskScope;
import com.paicli.observability.LangfuseTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** One optional, tool-free semantic pass. The rule contract remains authoritative. */
public final class TaskUnderstandingEngine {
    private static final Logger LOG = LoggerFactory.getLogger(TaskUnderstandingEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmClient client;

    public TaskUnderstandingEngine(LlmClient client) { this.client = java.util.Objects.requireNonNull(client); }

    public TaskContract understand(String input) {
        return LangfuseTelemetry.observeStage("task understanding", () -> understandInternal(input));
    }

    private TaskContract understandInternal(String input) {
        TaskContract rules = TaskContract.understand(input);
        if (CancellationContext.isCancelled()) throw new java.util.concurrent.CancellationException("Task cancelled");
        if (!needsSemanticPass(rules)) return rules;
        RootTaskScope root = RootTaskScope.current();
        if (root != null && !root.canRunOptionalRequest()) return rules;
        try {
            AgentBudget budget = AgentBudget.fromSystemProperties();
            if (AgentExecutionKernel.admitIteration(budget).state() != AgentExecutionKernel.IterationState.READY)
                return rules;
            AgentExecutionKernel.ModelTurn response = AgentExecutionKernel.requestModelTurn(client,
                    List.of(LlmClient.Message.system("只分析用户任务，不执行任务。仅输出一个 JSON 对象。字段：intent, taskType, normalizedGoal, constraints, scope, deliverables, dependencies, unknowns, candidateAcceptanceCriteria, clarificationQuestion, clarificationNeeded。字符串字段可省略；列表字段只能是字符串数组；clarificationNeeded 为布尔值。不得把猜测写成用户明确要求。"),
                            LlmClient.Message.user(input)), List.of(), LlmClient.StreamListener.NO_OP,
                    budget, LOG, "task understanding");
            if (response.cancelled() || CancellationContext.isCancelled())
                throw new java.util.concurrent.CancellationException("Task cancelled during understanding");
            return parse(rules, response.response().content());
        } catch (RootTaskScope.RootRequestBudgetExceededException | java.util.concurrent.CancellationException stop) {
            throw stop;
        } catch (IOException | RuntimeException error) {
            if (CancellationContext.isCancelled())
                throw new java.util.concurrent.CancellationException("Task cancelled during understanding");
            LOG.warn("Task understanding fell back to rules: {}", error.getClass().getSimpleName());
            LangfuseTelemetry.recordFailure("UNDERSTANDING_FALLBACK");
            return rules;
        }
    }

    public static boolean needsSemanticPass(TaskContract rules) {
        String text = rules.goal();
        if (text.isBlank()) return false;
        if (rules.needsConfirmation()) return true;
        if (text.length() <= 80 && (text.contains("？") || text.endsWith("?"))) return false;
        if (text.length() <= 80 && (!rules.acceptanceLines().isEmpty()
                || !rules.explicitAcceptanceLines().isEmpty())) return false;
        return text.length() > 80 || List.of("优化", "重构", "修复", "实现", "开发", "安全审计", "多个模块")
                .stream().anyMatch(text::contains);
    }

    private static TaskContract parse(TaskContract rules, String content) throws IOException {
        JsonNode root = JSON.readTree(content);
        if (root == null || !root.isObject()) throw new IOException("understanding is not an object");
        var semantic = new TaskContract.SemanticUnderstanding(
                string(root, "intent"), string(root, "taskType"), string(root, "normalizedGoal"),
                strings(root, "constraints"), strings(root, "scope"), strings(root, "deliverables"),
                strings(root, "dependencies"), strings(root, "unknowns"),
                strings(root, "candidateAcceptanceCriteria"), string(root, "clarificationQuestion"));
        JsonNode clarify = root.get("clarificationNeeded");
        if (clarify != null && !clarify.isBoolean()) throw new IOException("invalid clarificationNeeded");
        boolean needsClarification = clarify != null && clarify.booleanValue()
                && semantic.clarificationQuestion() != null;
        return rules.withSemantic(semantic, needsClarification);
    }

    private static TaskContract.Candidate string(JsonNode root, String name) throws IOException {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.asText().length() > 500) throw new IOException("invalid " + name);
        String value = node.asText().trim();
        return value.isEmpty() ? null : new TaskContract.Candidate(value, TaskContract.Source.MODEL_CANDIDATE);
    }

    private static List<TaskContract.Candidate> strings(JsonNode root, String name) throws IOException {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 20) throw new IOException("invalid " + name);
        List<TaskContract.Candidate> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().length() > 500) throw new IOException("invalid " + name);
            if (!item.asText().isBlank()) values.add(new TaskContract.Candidate(
                    item.asText().trim(), TaskContract.Source.MODEL_CANDIDATE));
        }
        return List.copyOf(values);
    }
}
