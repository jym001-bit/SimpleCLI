package com.paicli.hitl;

import com.paicli.browser.BrowserCheckResult;
import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.paicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;
    private volatile AutoApprovalReviewer autoApprovalReviewer;
    /** auto 模式下同一轮连续被拦的次数；到阈值转人工，放行或新一轮输入时清零。 */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveAutoDenials =
            new java.util.concurrent.atomic.AtomicInteger();
    static final int AUTO_ESCALATE_AFTER = 3;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    /** auto 模式下审查 execute_command 的分类器；未设置时这些调用一律拦回给模型。 */
    public void setAutoApprovalReviewer(AutoApprovalReviewer reviewer) {
        this.autoApprovalReviewer = reviewer;
    }

    /** 每条顶层用户输入开始时调用，连续被拦的计数只在同一轮内累计。 */
    public void startAutoReviewTurn() {
        consecutiveAutoDenials.set(0);
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        // 完整 HITL 确认全部危险工具；未开启时，默认只确认高危工具
        boolean needsApproval = hitlHandler.isEnabled()
                ? ApprovalPolicy.requiresApproval(name)
                : hitlHandler.isHighRiskConfirmationEnabled() && ApprovalPolicy.requiresConfirmationByDefault(name);
        // 参数不合法的调用注定失败：不弹确认框、不调审查模型、不计入 auto 连续被拦次数，直接走执行路径拿到校验错误
        if (!needsApproval || argumentError(name, argumentsJson) != null) {
            return super.doExecuteTool(name, argumentsJson);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        String sensitiveNotice = browserCheck.requiresPerCallApproval() ? browserCheck.sensitiveNotice() : null;
        if (sensitiveNotice == null) {
            String mcpServer = ApprovalPolicy.mcpServerName(name);
            if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
                return super.doExecuteTool(name, argumentsJson);
            }
        }
        if (inAutoMode()) {
            return executeInAutoMode(name, argumentsJson, sensitiveNotice);
        }
        return executeAfterExplicitApproval(name, argumentsJson, sensitiveNotice, null);
    }

    /**
     * auto 模式不打断用户：Shell 命令由分类器审查，放行就执行；不放行、审查失败，以及 MCP 工具、回滚快照、
     * 敏感页面改写这些不交给分类器的操作，都作为失败结果交回模型，让它换做法或向用户说明。
     * 同一轮连续被拦到 {@link #AUTO_ESCALATE_AFTER} 次才转人工审批，避免模型反复试探把任务卡住。
     */
    private ToolOutput executeInAutoMode(String name, String argumentsJson, String sensitiveNotice) {
        long start = System.nanoTime();
        String reason;
        if (sensitiveNotice != null) {
            reason = "敏感页面上的改写操作需要人工逐次确认";
        } else if (AUTO_REVIEWED_TOOLS.contains(name)) {
            AutoApprovalReviewer reviewer = autoApprovalReviewer;
            if (reviewer == null) {
                reason = "自动审查不可用";
            } else {
                AutoApprovalReviewer.Review review = reviewer.review(name, argumentsJson);
                if (review.allowed()) {
                    consecutiveAutoDenials.set(0);
                    getAuditLog().record(AuditLog.AuditEntry.allowByAutoClassifier(
                            name, argumentsJson, review.reason(), elapsedMillis(start)));
                    hitlHandler.onAutoApproved(name, review.reason());
                    // 路径围栏、命令黑名单仍在父类执行路径里生效，分类器放行绕不过
                    return super.doExecuteTool(name, argumentsJson);
                }
                reason = review.reason();
            }
        } else if (ApprovalPolicy.mcpServerName(name) != null) {
            reason = "auto 模式不自动执行 MCP 工具，它的实际行为由外部 server 决定";
        } else {
            reason = "auto 模式不自动执行" + ("revert_turn".equals(name) ? "回滚快照，它会批量覆盖工作区文件" : "该操作");
        }

        int denials = consecutiveAutoDenials.incrementAndGet();
        if (denials >= AUTO_ESCALATE_AFTER) {
            consecutiveAutoDenials.set(0);
            return executeAfterExplicitApproval(name, argumentsJson, sensitiveNotice,
                    "自动审查已连续 " + denials + " 次未放行，本次：" + reason);
        }
        getAuditLog().record(AuditLog.AuditEntry.denyByAutoClassifier(
                name, argumentsJson, reason, elapsedMillis(start)));
        hitlHandler.onAutoDenied(name, reason);
        return ToolOutput.failure("[AUTO] 自动审查未放行：" + reason
                + "。请换一种更安全、只读的做法；如果确实必须执行，请在回复里向用户说明原因，"
                + "由用户按 Shift+Tab 切到 ask 模式后再执行。");
    }

    /** auto 模式只让分类器审查 Shell 命令；MCP 工具的实际行为由第三方 server 决定，分类器只看得到名字和参数。 */
    private static final java.util.Set<String> AUTO_REVIEWED_TOOLS = java.util.Set.of("execute_command");

    private boolean inAutoMode() {
        return !hitlHandler.isEnabled() && hitlHandler.isHighRiskConfirmationEnabled();
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        return executeAfterExplicitApproval(name, argumentsJson, sensitiveNotice, null);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice,
                                                    String reviewNote) {
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, reviewNote, null, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start)));
            return ToolOutput.failure("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return ToolOutput.failure("[HITL] 操作已被跳过");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.doExecuteTool(name, effectiveArgs);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
