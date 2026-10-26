package com.paicli.hitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交互式 CLI 的两档确认。auto：Shell 命令由分类器审查，放行就执行，不放行和其他高危操作都拦回给模型，
 * 同一轮连续被拦 3 次才转人工；ask：全部危险操作人工确认。
 */
class HitlDefaultConfirmationTest {

    @Test
    void autoModeWritesFilesWithoutAnyReview(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);

        String write = registry.executeTool("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

        assertFalse(write.startsWith("[AUTO]"), write);
        assertTrue(delegate.requestedTools.isEmpty());
        assertTrue(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void autoModeRunsCommandsTheClassifierAllows(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);
        List<String> reviewed = new ArrayList<>();
        registry.setAutoApprovalReviewer((tool, args) -> {
            reviewed.add(tool);
            return AutoApprovalReviewer.Review.allow("只读命令");
        });

        String result = registry.executeTool("execute_command", "{\"command\":\"echo hi\"}");

        assertEquals(List.of("execute_command"), reviewed);
        assertTrue(delegate.requestedTools.isEmpty(), "分类器放行后不弹审批");
        assertTrue(result.contains("hi"), result);
        assertEquals(List.of("execute_command:只读命令"), delegate.autoApproved);
    }

    @Test
    void classifierRejectionGoesBackToTheModelWithoutPrompting(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);
        registry.setAutoApprovalReviewer((tool, args) -> AutoApprovalReviewer.Review.ask("会删除 build 目录"));

        String result = registry.executeTool("execute_command", "{\"command\":\"rm -rf build\"}");

        assertTrue(result.startsWith("[AUTO] 自动审查未放行：会删除 build 目录"), result);
        assertTrue(result.contains("ask 模式"), "要告诉模型怎么让用户放行: " + result);
        assertTrue(delegate.requestedTools.isEmpty(), "auto 模式不打断用户");
        assertEquals(List.of("execute_command:会删除 build 目录"), delegate.autoDenied);
    }

    @Test
    void missingReviewerFailsClosedToTheModel(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);

        String result = registry.executeTool("execute_command", "{\"command\":\"echo hi\"}");

        assertTrue(result.startsWith("[AUTO]"), result);
        assertFalse(result.contains("hi\n"), "没有审查就不能执行");
        assertTrue(delegate.requestedTools.isEmpty());
    }

    @Test
    void mcpAndRevertAreReturnedToTheModelWithoutClassifier(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);
        List<String> reviewed = new ArrayList<>();
        registry.setAutoApprovalReviewer((tool, args) -> {
            reviewed.add(tool);
            return AutoApprovalReviewer.Review.allow("x");
        });

        String mcp = registry.executeTool("mcp__fs__read", "{}");
        registry.startAutoReviewTurn();
        String revert = registry.executeTool("revert_turn", "{\"steps\":1}");

        assertTrue(reviewed.isEmpty(), "MCP 与回滚不交给分类器");
        assertTrue(mcp.startsWith("[AUTO]") && mcp.contains("MCP"), mcp);
        assertTrue(revert.startsWith("[AUTO]") && revert.contains("回滚"), revert);
        assertTrue(delegate.requestedTools.isEmpty());
    }

    @Test
    void thirdConsecutiveRejectionInOneTurnEscalatesToHuman(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);
        registry.setAutoApprovalReviewer((tool, args) -> AutoApprovalReviewer.Review.ask("有风险"));

        registry.executeTool("execute_command", "{\"command\":\"rm a\"}");
        registry.executeTool("execute_command", "{\"command\":\"rm b\"}");
        String third = registry.executeTool("execute_command", "{\"command\":\"rm c\"}");

        assertEquals(List.of("execute_command"), delegate.requestedTools, "第 3 次才弹审批");
        assertTrue(delegate.requests.get(0).suggestion().contains("连续 3 次"), delegate.requests.get(0).suggestion());
        assertTrue(third.startsWith("[HITL]"), "人工拒绝后返回 HITL 结果: " + third);
    }

    @Test
    void allowOrNewTurnResetsTheRejectionStreak(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        HitlToolRegistry registry = registry(interactiveDefault(delegate), tempDir);
        registry.setAutoApprovalReviewer((tool, args) -> args.contains("echo")
                ? AutoApprovalReviewer.Review.allow("只读")
                : AutoApprovalReviewer.Review.ask("有风险"));

        registry.executeTool("execute_command", "{\"command\":\"rm a\"}");
        registry.executeTool("execute_command", "{\"command\":\"rm b\"}");
        registry.executeTool("execute_command", "{\"command\":\"echo ok\"}");
        registry.executeTool("execute_command", "{\"command\":\"rm c\"}");
        registry.executeTool("execute_command", "{\"command\":\"rm d\"}");
        registry.startAutoReviewTurn();
        registry.executeTool("execute_command", "{\"command\":\"rm e\"}");
        registry.executeTool("execute_command", "{\"command\":\"rm f\"}");

        assertTrue(delegate.requestedTools.isEmpty(), "放行或新一轮输入都会清零连续计数");
    }

    @Test
    void askModeConfirmsEverythingAndSkipsClassifier(@TempDir Path tempDir) {
        RecordingHandler delegate = new RecordingHandler();
        SwitchableHitlHandler handler = interactiveDefault(delegate);
        handler.switchConfirmationMode("on");
        HitlToolRegistry registry = registry(handler, tempDir);
        List<String> reviewed = new ArrayList<>();
        registry.setAutoApprovalReviewer((tool, args) -> {
            reviewed.add(tool);
            return AutoApprovalReviewer.Review.allow("x");
        });

        String write = registry.executeTool("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");
        registry.executeTool("execute_command", "{\"command\":\"echo hi\"}");

        assertTrue(reviewed.isEmpty());
        assertEquals(List.of("write_file", "execute_command"), delegate.requestedTools);
        assertTrue(write.startsWith("[HITL]"), write);
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void nonInteractiveHandlersKeepHighRiskConfirmationOff() {
        // 评测等非交互路径直接实现 HitlHandler，不经过可切换处理器，默认不会弹确认卡住流程
        assertFalse(new RecordingHandler().isHighRiskConfirmationEnabled());
        assertFalse(new TerminalHitlHandler(false).isConfirmationActive());
    }

    @Test
    void approvalPolicySeparatesHighRiskFromOtherDangerousTools() {
        assertTrue(ApprovalPolicy.requiresConfirmationByDefault("execute_command"));
        assertTrue(ApprovalPolicy.requiresConfirmationByDefault("revert_turn"));
        assertTrue(ApprovalPolicy.requiresConfirmationByDefault("mcp__chrome-devtools__click"));
        assertFalse(ApprovalPolicy.requiresConfirmationByDefault("write_file"));
        assertFalse(ApprovalPolicy.requiresConfirmationByDefault("edit_file"));
        assertFalse(ApprovalPolicy.requiresConfirmationByDefault("create_project"));
        assertFalse(ApprovalPolicy.requiresConfirmationByDefault("read_file"));
    }

    private static SwitchableHitlHandler interactiveDefault(RecordingHandler delegate) {
        SwitchableHitlHandler handler = new SwitchableHitlHandler(delegate);
        handler.setHighRiskConfirmationEnabled(true);
        return handler;
    }

    private static HitlToolRegistry registry(HitlHandler handler, Path projectRoot) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }

    /** 收到的审批请求一律拒绝，只记录被问到的工具和 auto 放行 / 拦回的通知。 */
    private static final class RecordingHandler implements HitlHandler {
        private final List<String> requestedTools = new ArrayList<>();
        private final List<ApprovalRequest> requests = new ArrayList<>();
        private final List<String> autoApproved = new ArrayList<>();
        private final List<String> autoDenied = new ArrayList<>();
        private boolean enabled;

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestedTools.add(request.toolName());
            requests.add(request);
            return ApprovalResult.reject("测试中统一拒绝");
        }

        @Override
        public void onAutoApproved(String toolName, String reason) {
            autoApproved.add(toolName + ":" + reason);
        }

        @Override
        public void onAutoDenied(String toolName, String reason) {
            autoDenied.add(toolName + ":" + reason);
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
}
