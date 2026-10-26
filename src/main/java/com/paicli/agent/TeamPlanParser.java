package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the Team planner's JSON plan into renumbered {@code step_N} execution steps.
 *
 * <p>Validation follows the Plan-and-Execute planner: a non-empty {@code steps} (or legacy
 * {@code tasks}) array, unique non-blank string ids, dependencies that reference declared ids and
 * no cycles. Invalid plans are rejected instead of silently keeping unknown edges, which used to
 * leave steps permanently blocked.
 */
final class TeamPlanParser {

    private static final String DEFAULT_TYPE = "COMMAND";

    private TeamPlanParser() {
    }

    static List<AgentOrchestrator.ExecutionStep> parse(String planReply) throws IOException {
        JsonNode stepsNode = stepsArray(TeamStructuredReply.parseObject(planReply));

        Map<String, String> idMapping = new HashMap<>();
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            JsonNode idNode = stepNode.path("id");
            if (!stepNode.isObject() || !idNode.isTextual() || idNode.asText().isBlank()) {
                throw new IOException("计划步骤必须具有非空字符串 id");
            }
            String newId = "step_" + (nodes.size() + 1);
            if (idMapping.putIfAbsent(idNode.asText(), newId) != null) {
                throw new IOException("计划中存在重复步骤 id: " + idNode.asText());
            }
            nodes.add(stepNode);
        }

        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode stepNode = nodes.get(i);
            steps.add(AgentOrchestrator.ExecutionStep.pending(
                    "step_" + (i + 1),
                    optionalText(stepNode, "description", ""),
                    optionalText(stepNode, "type", DEFAULT_TYPE),
                    dependencies(stepNode, idMapping)));
        }
        requireAcyclic(steps);
        // The orchestrator updates step status in place, so the returned list stays mutable.
        return steps;
    }

    private static JsonNode stepsArray(JsonNode root) throws IOException {
        JsonNode steps = root.path("steps");
        if (steps.isArray() && !steps.isEmpty()) {
            return steps;
        }
        JsonNode tasks = root.path("tasks");
        if (tasks.isArray() && !tasks.isEmpty()) {
            return tasks;
        }
        throw new IOException("计划必须包含非空 steps 数组");
    }

    private static String optionalText(JsonNode stepNode, String field, String fallback) throws IOException {
        JsonNode value = stepNode.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new IOException("计划步骤 " + field + " 必须是字符串");
        }
        return value.asText();
    }

    private static List<String> dependencies(JsonNode stepNode, Map<String, String> idMapping)
            throws IOException {
        JsonNode depsNode = stepNode.get("dependencies");
        if (depsNode == null) {
            return List.of();
        }
        if (!depsNode.isArray()) {
            throw new IOException("计划 dependencies 必须是步骤 id 数组");
        }
        Set<String> deps = new LinkedHashSet<>();
        for (JsonNode depNode : depsNode) {
            String mapped = depNode.isTextual() ? idMapping.get(depNode.asText()) : null;
            if (mapped == null) {
                // Never keep an unknown edge or guess a normalized alias: fail the whole plan.
                throw new IOException("计划依赖必须引用已声明的步骤 id: " + depNode);
            }
            deps.add(mapped);
        }
        return List.copyOf(deps);
    }

    private static void requireAcyclic(List<AgentOrchestrator.ExecutionStep> steps) throws IOException {
        Map<String, Integer> pendingDeps = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (AgentOrchestrator.ExecutionStep step : steps) {
            pendingDeps.put(step.id(), step.dependencies().size());
            for (String dep : step.dependencies()) {
                dependents.computeIfAbsent(dep, key -> new ArrayList<>()).add(step.id());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        pendingDeps.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            visited++;
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (pendingDeps.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (visited != steps.size()) {
            throw new IOException("计划中存在循环依赖");
        }
    }
}
