package com.paicli.hitl;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmApprovalClassifierTest {

    @Test
    void allowsOnlyTheExactAllowDecision() {
        assertTrue(LlmApprovalClassifier.parse("{\"decision\":\"allow\",\"reason\":\"只读查找\"}").allowed());
        assertTrue(LlmApprovalClassifier.parse("结果：{\"decision\":\"allow\",\"reason\":\"只读\"}").allowed());
        assertFalse(LlmApprovalClassifier.parse("{\"decision\":\"ask\",\"reason\":\"会删除文件\"}").allowed());
        assertFalse(LlmApprovalClassifier.parse("{\"decision\":\"Allow\"}").allowed());
        assertFalse(LlmApprovalClassifier.parse("{\"decision\":true}").allowed());
        assertFalse(LlmApprovalClassifier.parse("allow").allowed());
        assertFalse(LlmApprovalClassifier.parse("").allowed());
        assertFalse(LlmApprovalClassifier.parse(null).allowed());
    }

    @Test
    void sendsOnlyUserRequestToolAndArgumentsWithoutTools() {
        ScriptedClient client = new ScriptedClient(messages -> "{\"decision\":\"allow\",\"reason\":\"只读查找\"}");
        LlmApprovalClassifier classifier = new LlmApprovalClassifier(() -> client);
        classifier.setCurrentUserRequest("demo 目录里有哪些 java 文件");

        AutoApprovalReviewer.Review review = classifier.review("execute_command", "{\"command\":\"find demo -name '*.java'\"}");

        assertTrue(review.allowed());
        assertEquals("只读查找", review.reason());
        assertEquals(1, client.requests.size());
        List<LlmClient.Message> messages = client.requests.get(0);
        assertEquals(2, messages.size(), "只有系统提示和一条审查请求，不带对话历史或工具结果");
        assertEquals("system", messages.get(0).role());
        String payload = messages.get(1).content();
        assertTrue(payload.contains("demo 目录里有哪些 java 文件"), payload);
        assertTrue(payload.contains("execute_command"), payload);
        assertTrue(payload.contains("find demo"), payload);
        assertTrue(client.toolsSeen.stream().allMatch(tools -> tools == null || tools.isEmpty()), "审查请求不能暴露工具");
    }

    @Test
    void failuresFallBackToHumanApproval() {
        LlmApprovalClassifier throwing = new LlmApprovalClassifier(
                () -> new ScriptedClient(messages -> { throw new IllegalStateException("boom"); }));
        LlmApprovalClassifier unavailable = new LlmApprovalClassifier(() -> null);
        LlmApprovalClassifier garbage = new LlmApprovalClassifier(() -> new ScriptedClient(messages -> "我觉得可以"));

        assertFalse(throwing.review("execute_command", "{\"command\":\"ls\"}").allowed());
        assertFalse(unavailable.review("execute_command", "{\"command\":\"ls\"}").allowed());
        assertFalse(garbage.review("execute_command", "{\"command\":\"ls\"}").allowed());
    }

    @Test
    void timeoutFallsBackToHumanApproval() {
        ScriptedClient slow = new ScriptedClient(messages -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"decision\":\"allow\",\"reason\":\"太迟了\"}";
        });
        LlmApprovalClassifier classifier = new LlmApprovalClassifier(() -> slow, Duration.ofMillis(100));

        AutoApprovalReviewer.Review review = classifier.review("execute_command", "{\"command\":\"ls\"}");

        assertFalse(review.allowed());
        assertTrue(review.reason().contains("超时"), review.reason());
    }

    @Test
    void oversizedArgumentsAreNeverSentOrAllowed() {
        ScriptedClient client = new ScriptedClient(messages -> "{\"decision\":\"allow\",\"reason\":\"x\"}");
        LlmApprovalClassifier classifier = new LlmApprovalClassifier(() -> client);

        AutoApprovalReviewer.Review review = classifier.review("execute_command",
                "{\"command\":\"echo " + "a".repeat(LlmApprovalClassifier.MAX_ARGUMENT_CHARS) + "\"}");

        assertFalse(review.allowed());
        assertTrue(client.requests.isEmpty(), "截断会漏掉危险部分，参数过长直接转人工");
    }

    @Test
    void cachesAllowOnlyWithinTheSameUserRequest() {
        ScriptedClient client = new ScriptedClient(messages -> "{\"decision\":\"allow\",\"reason\":\"只读\"}");
        LlmApprovalClassifier classifier = new LlmApprovalClassifier(() -> client);
        String args = "{\"command\":\"git status\"}";

        classifier.setCurrentUserRequest("看看改了什么");
        classifier.review("execute_command", args);
        classifier.review("execute_command", args);
        assertEquals(1, client.requests.size(), "同一请求内相同命令只审查一次");

        classifier.setCurrentUserRequest("提交代码");
        classifier.review("execute_command", args);
        assertEquals(2, client.requests.size(), "换了请求要重新审查");
    }

    @Test
    void askDecisionsAreNotCached() {
        ScriptedClient client = new ScriptedClient(messages -> "{\"decision\":\"ask\",\"reason\":\"会删除文件\"}");
        LlmApprovalClassifier classifier = new LlmApprovalClassifier(() -> client);

        classifier.review("execute_command", "{\"command\":\"rm -rf build\"}");
        classifier.review("execute_command", "{\"command\":\"rm -rf build\"}");

        assertEquals(2, client.requests.size());
    }

    private static final class ScriptedClient implements LlmClient {
        private final Function<List<Message>, String> answer;
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<Tool>> toolsSeen = new ArrayList<>();

        private ScriptedClient(Function<List<Message>, String> answer) {
            this.answer = answer;
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            requests.add(List.copyOf(messages));
            toolsSeen.add(tools);
            return new ChatResponse("assistant", answer.apply(messages), null, 10, 5);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "classifier-test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
