package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionBalancedPairwiseJudgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsCandidateOnlyWhenBothOrdersAgree() throws Exception {
        StubClient client = new StubClient(
                "{\"winner\":\"B\",\"reason\":\"B 满足时效约束\"}",
                "{\"winner\":\"A\",\"reason\":\"A 满足时效约束\"}");
        PositionBalancedPairwiseJudge judge = new PositionBalancedPairwiseJudge(client);

        PositionBalancedPairwiseJudge.PairwiseResult result = judge.compare(
                "推荐明天送达的电脑",
                "必须核验库存和配送时效",
                "baseline answer",
                "candidate answer",
                "约束满足优先于文风");

        assertEquals(PositionBalancedPairwiseJudge.Winner.CANDIDATE, result.winner());
        assertTrue(result.positionConsistent());
        assertEquals("baseline answer", client.payloads.get(0).path("answerA").asText());
        assertEquals("candidate answer", client.payloads.get(0).path("answerB").asText());
        assertEquals("candidate answer", client.payloads.get(1).path("answerA").asText());
        assertEquals("baseline answer", client.payloads.get(1).path("answerB").asText());
    }

    @Test
    void convertsPositionDependentJudgmentsToTie() throws Exception {
        StubClient client = new StubClient(
                "{\"winner\":\"A\",\"reason\":\"更喜欢第一个\"}",
                "{\"winner\":\"A\",\"reason\":\"仍然更喜欢第一个\"}");
        PositionBalancedPairwiseJudge judge = new PositionBalancedPairwiseJudge(client);

        PositionBalancedPairwiseJudge.PairwiseResult result = judge.compare(
                "input", "reference", "baseline", "candidate", "rubric");

        assertEquals(PositionBalancedPairwiseJudge.Winner.TIE, result.winner());
        assertFalse(result.positionConsistent());
    }

    private static final class StubClient implements LlmClient {
        private final Deque<String> responses = new ArrayDeque<>();
        private final List<JsonNode> payloads = new ArrayList<>();

        private StubClient(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            try {
                payloads.add(MAPPER.readTree(messages.get(1).content()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return new ChatResponse("assistant", responses.removeFirst(), null, 80, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages,
                                 List<Tool> tools,
                                 StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "judge-test";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
