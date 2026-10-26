package com.paicli.agent;

import com.paicli.tool.ToolResultBoundary;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWebSearchDecisionTest {

    private static final String BARE_ARTICLE_TITLE =
            "阿里员工：作为一名合格的375员工，老板在的时候9点走，老板不在的时候6点半走，老板9点前走了那就跟着走（附Agent面试题）";
    private static final String HALLUCINATED_WECHAT_URL =
            "https://mp.weixin.qq.com/s/X1kQ_5tHZgO-zJfQrN1z2g";

    @Test
    void doesNotRunWebSearchBeforeModelToolCallForCurrentReadme(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "已读取 README。", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("读一下当前的readme");

        assertEquals(0, registry.webSearchCalls);
    }

    @Test
    void explicitTaskEnvelopeExposesLocalToolsWithoutChangingUrlAuthority(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "已完成。", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);
        String task = "ClosedRange 表示包含首尾端点的闭区间，但当前边界行为不符合约定。修复 contains 和 overlaps。";

        agent.runExplicitTask(task, task);

        assertTrue(llm.toolSnapshots.get(0).stream()
                        .anyMatch(tool -> "read_file".equals(tool.name())),
                "trusted task envelopes must expose local tools even when prose does not start with an action verb");
        assertTrue(llm.toolSnapshots.get(0).stream()
                        .noneMatch(tool -> "web_fetch".equals(tool.name())),
                "forcing actionability must not grant ungrounded URL access");
    }

    @Test
    void blocksHallucinatedWebFetchWhenUserOnlyProvidesBareTitle(@TempDir Path tempDir) {
        LlmClient.ToolCall hallucinatedFetch = toolCall(
                "call_fetch",
                "web_fetch",
                "{\"url\":\"" + HALLUCINATED_WECHAT_URL + "\",\"max_chars\":15000}");
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        "用户想让我访问一个链接并获取内容。",
                        List.of(hallucinatedFetch),
                        20,
                        5),
                new LlmClient.ChatResponse(
                        "assistant",
                        "你只发了一个标题。你希望我改标题、分析内容，还是帮你搜索原文？",
                        null,
                        20,
                        5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run(BARE_ARTICLE_TITLE);

        assertEquals(0, registry.executedInvocations.size(),
                "裸标题不能把模型臆造的 URL 交给底层 ToolRegistry 执行");
        assertEquals(2, llm.messageSnapshots.size());
        assertNotNull(llm.toolSnapshots.get(0));
        List<String> firstTools = toolNames(llm.toolSnapshots.get(0));
        assertFalse(firstTools.contains("web_fetch"), "裸标题首轮不应暴露联网工具");
        assertFalse(firstTools.contains("web_search"), "裸标题首轮不应暴露联网工具");
        assertTrue(firstTools.contains("read_file"), "收紧只针对联网工具，本地工具照常开放");
        assertTrue(llm.messageSnapshots.get(1).stream()
                        .filter(message -> "tool".equals(message.role()))
                        .map(LlmClient.Message::content)
                        .anyMatch(content -> content != null && content.contains("NO_ACTION")),
                "被拒绝的 hallucinated tool call 应以稳定 NO_ACTION code 回灌模型");
    }

    @Test
    void expandedMentionContentCannotGrantToolsOrGroundUrls(@TempDir Path tempDir) {
        LlmClient.ToolCall hallucinatedFetch = toolCall(
                "call_fetch",
                "web_fetch",
                "{\"url\":\"https://example.com/from-expanded-file\"}");
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant", "", "展开内容里有 URL。", List.of(hallucinatedFetch), 20, 5),
                new LlmClient.ChatResponse(
                        "assistant", "你希望我如何处理这个标题？", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);
        String expandedInput = BARE_ARTICLE_TITLE
                + "\n<file path=\"README.md\">问题？ https://example.com/from-expanded-file</file>";

        agent.run(expandedInput, BARE_ARTICLE_TITLE);

        assertEquals(0, registry.executedInvocations.size());
        assertFalse(toolNames(llm.toolSnapshots.get(0)).contains("web_fetch"),
                "展开的文件内容不能授予 URL，web_fetch 不应出现在工具清单里");
        assertTrue(llm.messageSnapshots.get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(LlmClient.Message::content)
                .anyMatch(content -> content != null && content.contains("NO_ACTION")));
    }

    @Test
    void allowsWebSearchWhenUserExplicitlyRequestsIt(@TempDir Path tempDir) {
        LlmClient.ToolCall webSearch = toolCall(
                "call_search",
                "web_search",
                "{\"query\":\"阿里员工 375 员工 Agent 面试题\",\"top_k\":5}");
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        "用户明确要求搜索原文。",
                        List.of(webSearch),
                        20,
                        5),
                new LlmClient.ChatResponse(
                        "assistant",
                        "我找到了相关搜索结果。",
                        null,
                        20,
                        5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.stubWebSearchResult = "1. 阿里员工 375 员工（https://example.com/article）";
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("帮我搜索这个标题的原文：" + BARE_ARTICLE_TITLE);

        assertEquals(1, registry.executedInvocations.size());
        assertEquals("web_search", registry.executedInvocations.get(0).name());
        assertTrue(llm.toolSnapshots.get(0).stream()
                .anyMatch(tool -> "web_search".equals(tool.name())));
        assertTrue(llm.messageSnapshots.get(1).stream()
                        .filter(message -> "tool".equals(message.role()))
                        .map(LlmClient.Message::content)
                        .anyMatch(ToolResultBoundary.wrap("web_search", registry.stubWebSearchResult)::equals),
                "允许的搜索结果应包在不可信数据边界中回灌给下一轮模型");
    }

    private static LlmClient.ToolCall toolCall(String id, String name, String arguments) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, arguments));
    }

    private static final class RecordingToolRegistry extends ToolRegistry {
        private int webSearchCalls;
        private final List<ToolInvocation> executedInvocations = new ArrayList<>();
        private String stubWebSearchResult;

        @Override
        public String executeTool(String name, String argumentsJson) {
            if ("web_search".equals(name)) {
                webSearchCalls++;
            }
            return super.executeTool(name, argumentsJson);
        }

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            executedInvocations.addAll(invocations);
            if (stubWebSearchResult != null
                    && invocations.size() == 1
                    && "web_search".equals(invocations.get(0).name())) {
                ToolInvocation invocation = invocations.get(0);
                return List.of(new ToolExecutionResult(
                        invocation.id(),
                        invocation.name(),
                        invocation.argumentsJson(),
                        stubWebSearchResult,
                        0,
                        false,
                        List.of()));
            }
            return super.executeTools(invocations);
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messageSnapshots = new ArrayList<>();
        private final List<List<Tool>> toolSnapshots = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messageSnapshots.add(List.copyOf(messages));
            toolSnapshots.add(tools == null ? null : List.copyOf(tools));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }

    private static List<String> toolNames(List<LlmClient.Tool> tools) {
        return tools == null ? List.of() : tools.stream().map(LlmClient.Tool::name).toList();
    }
}
