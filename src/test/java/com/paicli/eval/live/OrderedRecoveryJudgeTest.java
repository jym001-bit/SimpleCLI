package com.paicli.eval.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolResultBoundary;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderedRecoveryJudgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERROR = "缺少必填参数 path";
    private static JsonNode policy() throws Exception {
        return JSON.readTree("{\"tool\":\"read_file\",\"firstArguments\":{},\"recoveryArguments\":{\"path\":\"alpha.txt\"},\"recoveryResultContains\":\"ALPHA=17\"}");
    }
    private static OrderedRecoveryJudge.Event error() {
        return new OrderedRecoveryJudge.Event("error-id", "read_file", "{}", false, ERROR, 1, List.of());
    }
    private static OrderedRecoveryJudge.Event recovery(int turn, boolean success, String result, List<LlmClient.Message> messages) {
        return new OrderedRecoveryJudge.Event("recovery-id", "read_file", "{\"path\":\"alpha.txt\"}", success, result, turn, messages);
    }
    private static List<LlmClient.Message> observed() {
        return List.of(LlmClient.Message.tool("error-id", ToolResultBoundary.wrap("read_file", ERROR)));
    }

    @Test void acceptsObservedErrorThenSuccessfulAssociatedRecovery() throws Exception {
        assertEquals(OrderedRecoveryJudge.Status.PASSED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(2, true, "ALPHA=17", observed()))).status());
    }
    @Test void laterErrorAndRecoveryCannotEraseAnEarlyRead() throws Exception {
        assertEquals(OrderedRecoveryJudge.Status.FAILED, OrderedRecoveryJudge.assess(policy(), List.of(
                recovery(1, true, "ALPHA=17", List.of()), error(), recovery(3, true, "ALPHA=17", observed()))).status());
    }
    @Test void sameTurnBatchDoesNotProveTheModelSawTheError() throws Exception {
        assertEquals(OrderedRecoveryJudge.Status.FAILED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(1, true, "ALPHA=17", observed()))).status());
    }
    @Test void unrelatedFailureWithSameIdIsNotTheRequiredErrorEvidence() throws Exception {
        var unrelated = List.of(LlmClient.Message.tool("error-id", ToolResultBoundary.wrap("read_file", "some other error")));
        assertEquals(OrderedRecoveryJudge.Status.FAILED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(2, true, "ALPHA=17", unrelated))).status());
    }
    @Test void failedOrWrongValueRecoveryCannotBePromotedByFinalAnswer() throws Exception {
        assertEquals(OrderedRecoveryJudge.Status.FAILED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(2, false, "读取失败", observed()))).status());
        assertEquals(OrderedRecoveryJudge.Status.FAILED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(2, true, "ALPHA=99", observed()))).status());
        assertEquals(OrderedRecoveryJudge.Status.FAILED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(2, true, "ALPHA=170", observed()))).status());
        assertEquals(OrderedRecoveryJudge.Status.PASSED, OrderedRecoveryJudge.assess(policy(),
                List.of(error(), recovery(2, true, "    1 | ALPHA=17", observed()))).status());
        assertEquals(OrderedRecoveryJudge.Status.PASSED, OrderedRecoveryJudge.assess(policy(), List.of(error(),
                recovery(2, false, "读取失败", observed()), recovery(3, true, "ALPHA=17", observed()))).status());
    }
    @Test void missingHistoricalArgumentsAreUnknownInsteadOfAssumedEmptyObject() throws Exception {
        var missing = new OrderedRecoveryJudge.Event(null, "read_file", null, false, null, 0, null);
        assertEquals(OrderedRecoveryJudge.Status.INSUFFICIENT_EVIDENCE,
                OrderedRecoveryJudge.assess(policy(), List.of(missing)).status());
        var wrongArguments = new OrderedRecoveryJudge.Event("bad", "read_file", "{\"path\":\"\"}", false, ERROR, 1, List.of());
        assertEquals(OrderedRecoveryJudge.Status.FAILED,
                OrderedRecoveryJudge.assess(policy(), List.of(wrongArguments)).status());
    }
}
