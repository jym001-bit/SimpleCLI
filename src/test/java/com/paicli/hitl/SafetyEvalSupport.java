package com.paicli.hitl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安全确定性测试的共享小工具。不是测试类，不含任何用例。
 *
 * <p>所有工具调用只走假 registry 或纯判定函数；不会启动进程、不会写项目外真实路径。</p>
 */
final class SafetyEvalSupport {

    private SafetyEvalSupport() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static LlmClient.Tool tool(String name) {
        return new LlmClient.Tool(name, name, JsonNodeFactory.instance.objectNode());
    }

    static ToolInvocation invocation(String id, String name, String arguments) {
        return new ToolInvocation(id, name, arguments);
    }

    static List<LlmClient.Tool> definitions() {
        return List.of(
                tool("read_file"),
                tool("write_file"),
                tool("edit_file"),
                tool("list_dir"),
                tool("execute_command"),
                tool("web_search"),
                tool("web_fetch"));
    }

    static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** 假审批处理器：记录被问次数，返回固定决策。 */
    static final class StubHandler implements HitlHandler {
        private boolean enabled;
        private final ApprovalResult result;
        int requests;

        StubHandler(boolean enabled, ApprovalResult result) {
            this.enabled = enabled;
            this.result = result;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests++;
            return result;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 假工具执行器：只记录调用，返回成功结果；不会真正执行任何命令。 */
    static final class RecordingRegistry extends ToolRegistry {
        final List<ToolInvocation> executed = new ArrayList<>();
        List<String> discoveredUrls = List.of();

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            executed.addAll(invocations);
            return invocations.stream()
                    .map(invocation -> new ToolExecutionResult(
                            invocation.id(),
                            invocation.name(),
                            invocation.argumentsJson(),
                            "web_search".equals(invocation.name()) ? "search result" : "executed",
                            0,
                            false,
                            List.of(),
                            true,
                            "web_search".equals(invocation.name()) ? discoveredUrls : List.of()))
                    .toList();
        }
    }
}
