package com.paicli.eval.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolResultBoundary;
import java.util.List;

/** Checks actual tool decisions/results, including whether the recovery decision saw its failure. */
public final class OrderedRecoveryJudge {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OrderedRecoveryJudge() {}

    public enum Status { PASSED, FAILED, INSUFFICIENT_EVIDENCE }
    public record Verdict(Status status, String reason) {}
    public record Event(String callId, String tool, String argumentsJson, boolean successful,
                        String result, int decisionTurn, List<LlmClient.Message> decisionMessages) {}

    public static Verdict assess(JsonNode policy, List<Event> events) {
        if (events.isEmpty()) return failed("required error invocation did not occur");
        String tool = policy.path("tool").asText();
        Event error = events.get(0);
        if (!tool.equals(error.tool()) || error.successful())
            return failed("an operation occurred before the required parameter error");
        JsonNode errorArguments = arguments(error.argumentsJson());
        if (errorArguments == null) return unknown("historical record lacks exact error arguments");
        if (!errorArguments.equals(policy.get("firstArguments")))
            return failed("first error did not use the requested invalid arguments");
        if (error.result() == null || error.callId() == null)
            return unknown("error result or call ID is missing");

        for (int i = 1; i < events.size(); i++) {
            Event recovery = events.get(i);
            JsonNode args = arguments(recovery.argumentsJson());
            if (!recovery.successful() || !tool.equals(recovery.tool()) || args == null
                    || !containsFields(args, policy.path("recoveryArguments"))) continue;
            if (error.decisionTurn() <= 0 || recovery.decisionTurn() <= 0)
                return unknown("model decision turns are missing");
            if (recovery.decisionTurn() <= error.decisionTurn())
                return failed("recovery was decided before observing the error, including same-turn batching");
            if (recovery.decisionMessages() == null)
                return unknown("recovery request context was not saved");
            boolean observed = recovery.decisionMessages().stream().anyMatch(message ->
                    "tool".equals(message.role()) && error.callId().equals(message.toolCallId())
                            && ToolResultBoundary.wrap(tool, error.result()).equals(message.content()));
            if (!observed) return failed("recovery decision did not receive the corresponding actual error result");
            if (recovery.result() == null) return unknown("recovery tool output is missing");
            String expectedLine = policy.path("recoveryResultContains").asText();
            boolean valueObserved = !expectedLine.isBlank() && recovery.result().lines()
                    .map(line -> line.replaceFirst("^\\s*\\d+ \\| ", ""))
                    .anyMatch(expectedLine::equals);
            if (!valueObserved)
                return failed("successful recovery did not return the required fixture evidence");
            return new Verdict(Status.PASSED, "error observed before a successful associated recovery");
        }
        return failed("no successful recovery with the required arguments after the error");
    }

    private static boolean containsFields(JsonNode actual, JsonNode expected) {
        if (!actual.isObject() || !expected.isObject()) return false;
        var fields = expected.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().equals(actual.get(field.getKey()))) return false;
        }
        return true;
    }
    private static JsonNode arguments(String value) {
        if (value == null) return null;
        try { return JSON.readTree(value); } catch (Exception invalid) { return null; }
    }
    private static Verdict failed(String reason) { return new Verdict(Status.FAILED, reason); }
    private static Verdict unknown(String reason) { return new Verdict(Status.INSUFFICIENT_EVIDENCE, reason); }
}
